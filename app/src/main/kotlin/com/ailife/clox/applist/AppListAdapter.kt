package com.ailife.clox.applist

import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.util.SparseArray
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.applist.model.AppItem

class AppListAdapter(
    private val itemLayoutRes: Int,
    private val hasDragHandle: Boolean = false,
    private val dragHandleId: Int = 0,
    private val binder: (View, AppItem) -> Unit
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    var onAppClick: ((AppItem, View) -> Unit)? = null
    var onAppLongPress: ((AppItem, View) -> Unit)? = null
    var dragEnabled = false
        set(value) { field = value; Log.d(TAG, "dragEnabled set: $value") }
    var onOrderChanged: ((List<AppItem>) -> Unit)? = null
    /** Position to draw INVISIBLE during a Pages drag; -1 = none. Checked in onBindViewHolder. */
    var dragHiddenPosition: Int = -1

    var touchHelper: ItemTouchHelper? = null
        set(value) { field = value; Log.d(TAG, "touchHelper set: ${if (value != null) "attached" else "null"}") }

    private val mutableItems = mutableListOf<AppItem>()

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        val item = mutableItems.getOrNull(position) ?: return RecyclerView.NO_ID
        return (item.packageName + "/" + item.activityName).hashCode().toLong()
    }

    fun submitList(list: List<AppItem>) {
        val old = mutableItems.toList()
        mutableItems.clear()
        mutableItems.addAll(list)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = mutableItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                val a = old[oldPos]; val b = mutableItems[newPos]
                return a.packageName == b.packageName && a.activityName == b.activityName
            }
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                return old[oldPos] == mutableItems[newPos]
            }
        })
        diff.dispatchUpdatesTo(this)
    }

    /** Updates mutableItems without triggering any notify — caller must handle the notify. */
    fun updateItemsDirect(list: List<AppItem>) {
        mutableItems.clear()
        mutableItems.addAll(list)
    }

    /** Updates items synchronously and calls notifyDataSetChanged. */
    fun setItemsDirectly(list: List<AppItem>) {
        mutableItems.clear()
        mutableItems.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = mutableItems.size

    fun onItemMove(from: Int, to: Int) {
        // Remove+insert instead of swap — this correctly shifts all items between
        // `from` and `to` by one position, preserving their relative order.
        val item = mutableItems.removeAt(from)
        mutableItems.add(to, item)
        notifyItemMoved(from, to)
    }

    fun onDragEnd() = onOrderChanged?.invoke(mutableItems.toList())

    fun getDragItem(position: Int): AppItem? = mutableItems.getOrNull(position)

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val viewCache = SparseArray<View>()

        @Suppress("UNCHECKED_CAST")
        fun <T : View> cachedView(id: Int): T {
            var v = viewCache.get(id)
            if (v == null) {
                v = itemView.findViewById(id)
                viewCache.put(id, v)
            }
            return v as T
        }

        private var downX = 0f
        private var downY = 0f
        private var longPressActive = false

        init {
            view.setOnClickListener {
                if (!AppContextMenu.isShowing) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_ID.toInt()) onAppClick?.invoke(mutableItems[pos], view)
                }
            }

            if (hasDragHandle && dragHandleId != 0) {
                view.post {
                    view.findViewById<View>(dragHandleId)?.setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN && dragEnabled) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            touchHelper?.startDrag(this)
                        }
                        false
                    }
                }
                view.setOnLongClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_ID.toInt()) return@setOnLongClickListener true
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    onAppLongPress?.invoke(mutableItems[pos], view)
                    true
                }
            } else {
                view.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            AppContextMenu.dismissCurrent()
                            downX = event.x; downY = event.y
                            longPressActive = false
                            Log.d(TAG, "VH DOWN pos=${bindingAdapterPosition} dragEnabled=$dragEnabled")
                        }
                        MotionEvent.ACTION_MOVE -> {
                            if (longPressActive) {
                                // Walk up to GestureInterceptorLayout and disallow it from
                                // intercepting. Calling requestDisallow directly on GIL (rather than
                                // a child) means only GIL and its ancestors get the flag — all
                                // intermediate ViewGroups (RecyclerView, container, ViewPager2) remain
                                // free to intercept, which is required for both ItemTouchHelper and
                                // the Pages container drag detection to work.
                                var p: android.view.ViewParent? = view.parent
                                while (p != null) {
                                    if (p is com.ailife.clox.gesture.GestureInterceptorLayout) {
                                        p.requestDisallowInterceptTouchEvent(true)
                                        break
                                    }
                                    p = p.parent
                                }
                                val dx = event.x - downX; val dy = event.y - downY
                                val dist2 = dx * dx + dy * dy
                                Log.d(TAG, "VH MOVE longPressActive=true dist2=$dist2 threshold=$DRAG_THRESHOLD_SQ touchHelper=${touchHelper != null}")
                                if (dist2 > DRAG_THRESHOLD_SQ) {
                                    longPressActive = false
                                    AppContextMenu.dismissCurrent()
                                    Log.d(TAG, "VH MOVE threshold reached dragEnabled=$dragEnabled calling startDrag")
                                    if (dragEnabled) touchHelper?.startDrag(this)
                                }
                            }
                        }
                        MotionEvent.ACTION_UP -> {
                            Log.d(TAG, "VH UP longPressActive=$longPressActive")
                            longPressActive = false
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            // Do NOT clear longPressActive here — Android fires a false CANCEL
                            // via cancelLongPress() immediately after onLongClick() returns true.
                            // ACTION_DOWN resets longPressActive for the next independent gesture.
                            Log.d(TAG, "VH CANCEL (longPressActive kept=$longPressActive)")
                        }
                    }
                    false
                }

                view.setOnLongClickListener {
                    val pos = bindingAdapterPosition
                    Log.d(TAG, "VH longClick pos=$pos dragEnabled=$dragEnabled touchHelper=${touchHelper != null} items=${mutableItems.size}")
                    if (pos == RecyclerView.NO_ID.toInt()) return@setOnLongClickListener true
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    longPressActive = dragEnabled
                    onAppLongPress?.invoke(mutableItems[pos], view)
                    true
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(itemLayoutRes, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        binder(holder.itemView, mutableItems[position])
        holder.itemView.visibility = if (position == dragHiddenPosition) View.INVISIBLE else View.VISIBLE
        if (hasDragHandle && dragHandleId != 0) {
            holder.itemView.findViewById<View>(dragHandleId)?.visibility =
                if (dragEnabled) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private const val TAG = "CloX/AppListAdapter"
        private const val DRAG_THRESHOLD_SQ = 400f
    }
}

open class AppListTouchCallback(private val adapter: AppListAdapter) : ItemTouchHelper.Callback() {
    override fun isLongPressDragEnabled() = false
    override fun isItemViewSwipeEnabled() = false

    override fun getMovementFlags(rv: RecyclerView, holder: RecyclerView.ViewHolder): Int {
        val flags = if (adapter.dragEnabled) makeMovementFlags(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) else 0
        Log.d("CloX/TouchCallback", "getMovementFlags dragEnabled=${adapter.dragEnabled} flags=$flags")
        return flags
    }

    override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
        adapter.onItemMove(from.bindingAdapterPosition, to.bindingAdapterPosition)
        return true
    }

    override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {}

    override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
        super.clearView(rv, holder)
        adapter.onDragEnd()
    }
}
