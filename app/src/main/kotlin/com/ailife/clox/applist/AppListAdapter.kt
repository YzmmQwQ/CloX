package com.ailife.clox.applist

import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.applist.model.AppItem
import java.util.Collections

class AppListAdapter(
    private val itemLayoutRes: Int,
    private val hasDragHandle: Boolean = false,
    private val dragHandleId: Int = 0,
    private val binder: (View, AppItem) -> Unit
) : ListAdapter<AppItem, AppListAdapter.VH>(DIFF) {

    var onAppClick: ((AppItem) -> Unit)? = null
    var onAppLongPress: ((AppItem, View) -> Unit)? = null
    var dragEnabled = false
    var onOrderChanged: ((List<AppItem>) -> Unit)? = null

    var touchHelper: ItemTouchHelper? = null

    private val mutableItems = mutableListOf<AppItem>()

    override fun submitList(list: List<AppItem>?) {
        mutableItems.clear()
        if (list != null) mutableItems.addAll(list)
        super.submitList(list?.toList())
    }

    fun onItemMove(from: Int, to: Int) {
        Collections.swap(mutableItems, from, to)
        notifyItemMoved(from, to)
    }

    fun onDragEnd() = onOrderChanged?.invoke(mutableItems.toList())

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {

        private var longPressTriggered = false
        private var dragStarted = false
        private var downX = 0f
        private var downY = 0f

        init {
            view.setOnClickListener {
                if (!longPressTriggered) {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_ID.toInt()) onAppClick?.invoke(mutableItems[pos])
                }
                longPressTriggered = false
            }

            if (hasDragHandle && dragHandleId != 0) {
                // Drag handle path: touch on handle → startDrag immediately
                view.post {
                    view.findViewById<View>(dragHandleId)?.setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN && dragEnabled) {
                            touchHelper?.startDrag(this)
                        }
                        false
                    }
                }
                // Long press on item body → context menu
                view.setOnLongClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_ID.toInt()) {
                        longPressTriggered = true
                        onAppLongPress?.invoke(mutableItems[pos], view)
                    }
                    true
                }
            } else {
                // No drag handle: long press → if drag enabled start drag, else menu
                view.setOnLongClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_ID.toInt()) return@setOnLongClickListener true
                    longPressTriggered = true
                    dragStarted = false
                    if (dragEnabled) {
                        touchHelper?.startDrag(this)
                        dragStarted = true
                    } else {
                        onAppLongPress?.invoke(mutableItems[pos], view)
                    }
                    true
                }
                // If drag started but finger didn't move → treat as long press menu on touch-up
                view.setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
                        MotionEvent.ACTION_UP -> {
                            if (longPressTriggered && dragStarted) {
                                val dx = event.x - downX
                                val dy = event.y - downY
                                if (dx * dx + dy * dy < 100f) {
                                    val pos = bindingAdapterPosition
                                    if (pos != RecyclerView.NO_ID.toInt()) {
                                        onAppLongPress?.invoke(mutableItems[pos], view)
                                    }
                                }
                                longPressTriggered = false
                                dragStarted = false
                            }
                        }
                    }
                    false
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(itemLayoutRes, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        binder(holder.itemView, mutableItems[position])
        if (hasDragHandle && dragHandleId != 0) {
            holder.itemView.findViewById<View>(dragHandleId)?.visibility =
                if (dragEnabled) View.VISIBLE else View.GONE
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<AppItem>() {
            override fun areItemsTheSame(a: AppItem, b: AppItem) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppItem, b: AppItem) =
                a.label == b.label && a.icon === b.icon && a.customOrder == b.customOrder
        }
    }
}

class AppListTouchCallback(private val adapter: AppListAdapter) : ItemTouchHelper.Callback() {
    override fun isLongPressDragEnabled() = false
    override fun isItemViewSwipeEnabled() = false

    override fun getMovementFlags(rv: RecyclerView, holder: RecyclerView.ViewHolder): Int {
        if (!adapter.dragEnabled) return 0
        return makeMovementFlags(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        )
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
