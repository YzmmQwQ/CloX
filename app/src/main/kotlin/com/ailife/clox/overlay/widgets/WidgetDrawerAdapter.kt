package com.ailife.clox.overlay.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.overlay.widgets.builtin.BuiltInClockCardView
import com.ailife.clox.overlay.widgets.builtin.BuiltInWeatherCardView
import com.ailife.clox.overlay.widgets.model.CellSlotConfig
import com.ailife.clox.overlay.widgets.model.WidgetCardConfig
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class WidgetDrawerAdapter(
    private val hostManager: WidgetHostManager
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val cards = mutableListOf<WidgetCardConfig>()
    private var currentItems: List<Any> = emptyList()

    var editMode = false
        private set

    var onDeleteCard: ((cardId: String) -> Unit)? = null
    var onDeleteSlot: ((cardId: String, slotIndex: Int) -> Unit)? = null
    var onHeightChanged: ((cardId: String, newUnits: Int) -> Unit)? = null
    var onAddCard: (() -> Unit)? = null
    var onAddSlot: ((cardId: String, slotIndex: Int) -> Unit)? = null
    var onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null
    var onEditToggled: ((Boolean) -> Unit)? = null

    companion object {
        private val ADD_CARD = Any()
        private val EDIT_PILL = Any()

        const val VT_FULL_WIDTH      = 0
        const val VT_SINGLE_CELL     = 1
        const val VT_EDIT_PILL       = 2
        const val VT_ADD_CARD        = 3
        const val VT_BUILTIN_CLOCK   = 4
        const val VT_BUILTIN_WEATHER = 5

        fun builtInCardParams(ctx: android.content.Context, heightDp: Int): RecyclerView.LayoutParams {
            val d = ctx.resources.displayMetrics.density
            return RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                (heightDp * d).toInt()
            ).apply { bottomMargin = (10 * d).toInt() }
        }
    }

    init {
        setHasStableIds(true)
        rebuildItems()
    }

    fun setCards(list: List<WidgetCardConfig>) {
        cards.clear()
        cards.addAll(list)
        rebuildItems()
        notifyDataSetChanged()
    }

    fun getCards(): List<WidgetCardConfig> = cards.toList()

    fun setEditMode(enabled: Boolean) {
        if (editMode == enabled) return
        editMode = enabled
        rebuildItems()
        notifyDataSetChanged()
        onEditToggled?.invoke(enabled)
    }

    fun moveCard(from: Int, to: Int): Boolean {
        if (from < 0 || to < 0 || from >= cards.size || to >= cards.size) return false
        // Swap directly in both backing lists so item IDs stay consistent with
        // visual positions — a full rebuildItems() here would mis-assign IDs.
        cards.add(to, cards.removeAt(from))
        val mutable = currentItems.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        currentItems = mutable
        notifyItemMoved(from, to)
        return true
    }

    fun updateCard(config: WidgetCardConfig) {
        val idx = cards.indexOfFirst { it.id == config.id }
        if (idx < 0) return
        cards[idx] = config
        rebuildItems()
        notifyItemChanged(idx)
    }

    fun removeCard(cardId: String) {
        val idx = cards.indexOfFirst { it.id == cardId }
        if (idx < 0) return
        cards.removeAt(idx)
        rebuildItems()
        notifyItemRemoved(idx)
    }

    fun addCard(config: WidgetCardConfig) {
        val insertAt = cards.size
        cards.add(config)
        rebuildItems()
        notifyItemInserted(insertAt)
    }

    private fun rebuildItems() {
        currentItems = buildList {
            addAll(cards)
            if (editMode) add(ADD_CARD)
            add(EDIT_PILL)
        }
    }

    override fun getItemCount() = currentItems.size

    override fun getItemViewType(position: Int) = when (currentItems[position]) {
        ADD_CARD  -> VT_ADD_CARD
        EDIT_PILL -> VT_EDIT_PILL
        is WidgetCardConfig.FullWidth      -> VT_FULL_WIDTH
        is WidgetCardConfig.SingleCellRow  -> VT_SINGLE_CELL
        is WidgetCardConfig.BuiltInClock   -> VT_BUILTIN_CLOCK
        is WidgetCardConfig.BuiltInWeather -> VT_BUILTIN_WEATHER
        else -> throw IllegalStateException("Unknown item type at $position")
    }

    override fun getItemId(position: Int): Long = when (val item = currentItems[position]) {
        ADD_CARD -> Long.MIN_VALUE + 1
        EDIT_PILL -> Long.MIN_VALUE
        is WidgetCardConfig -> item.id.hashCode().toLong()
        else -> RecyclerView.NO_ID
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            VT_FULL_WIDTH  -> FullWidthViewHolder(inf.inflate(R.layout.item_widget_full_width, parent, false))
            VT_SINGLE_CELL -> SingleCellViewHolder(inf.inflate(R.layout.item_widget_single_cell_row, parent, false))
            VT_ADD_CARD    -> AddCardViewHolder(inf.inflate(R.layout.item_widget_add_card, parent, false))
            VT_EDIT_PILL   -> EditPillViewHolder(inf.inflate(R.layout.item_widget_edit_pill, parent, false))
            VT_BUILTIN_CLOCK -> {
                val v = BuiltInClockCardView(parent.context).apply {
                    layoutParams = builtInCardParams(parent.context, heightDp = 100)
                }
                BuiltInClockViewHolder(v)
            }
            VT_BUILTIN_WEATHER -> {
                val v = BuiltInWeatherCardView(parent.context).apply {
                    layoutParams = builtInCardParams(parent.context, heightDp = 172)
                }
                BuiltInWeatherViewHolder(v)
            }
            else -> throw IllegalStateException()
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is FullWidthViewHolder     -> holder.bind(currentItems[position] as WidgetCardConfig.FullWidth)
            is SingleCellViewHolder    -> holder.bind(currentItems[position] as WidgetCardConfig.SingleCellRow)
            is AddCardViewHolder       -> holder.bind()
            is EditPillViewHolder      -> holder.bind()
            is BuiltInClockViewHolder  -> holder.bind(currentItems[position] as WidgetCardConfig.BuiltInClock)
            is BuiltInWeatherViewHolder -> holder.bind(currentItems[position] as WidgetCardConfig.BuiltInWeather)
        }
    }

    // ── Full-width AppWidget card ──────────────────────────────────────────────

    inner class FullWidthViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val widgetCard: MaterialCardView = view.findViewById(R.id.widgetCard)
        private val widgetContainer: FrameLayout = view.findViewById(R.id.widgetContainer)
        private val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        private val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
        private val heightControls: View = view.findViewById(R.id.heightControls)
        private val btnHeightDown: ImageButton = view.findViewById(R.id.btnHeightDown)
        private val btnHeightUp: ImageButton = view.findViewById(R.id.btnHeightUp)
        private val tvHeight: TextView = view.findViewById(R.id.tvHeight)

        fun bind(config: WidgetCardConfig.FullWidth) {
            // Store units as tag so a queued post() callback picks up the latest bound value
            widgetCard.tag = config.heightUnits
            widgetCard.post {
                val units = widgetCard.tag as? Int ?: return@post
                val cardWidth = widgetCard.width
                if (cardWidth > 0) {
                    val lp = widgetCard.layoutParams
                    lp.height = cardWidth * units / 4
                    widgetCard.layoutParams = lp
                }
            }

            // Attach AppWidgetHostView
            val awm = AppWidgetManager.getInstance(itemView.context)
            val info = awm.getAppWidgetInfo(config.appWidgetId)
            if (info != null) {
                val hostView = hostManager.getOrCreateView(itemView.context, config.appWidgetId, info)
                (hostView.parent as? ViewGroup)?.removeView(hostView)
                widgetContainer.removeAllViews()
                widgetContainer.addView(hostView, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
            } else {
                widgetContainer.removeAllViews()
                val placeholder = TextView(itemView.context).apply {
                    text = "Widget unavailable"
                    gravity = android.view.Gravity.CENTER
                }
                widgetContainer.addView(placeholder)
            }

            // Edit-mode controls
            val edit = editMode
            btnDelete.visibility = if (edit) View.VISIBLE else View.GONE
            dragHandle.visibility = if (edit) View.VISIBLE else View.GONE
            heightControls.visibility = if (edit) View.VISIBLE else View.GONE

            if (edit) {
                tvHeight.text = "${config.heightUnits}"
                btnDelete.setOnClickListener { onDeleteCard?.invoke(config.id) }
                btnHeightDown.setOnClickListener {
                    if (config.heightUnits > 1) onHeightChanged?.invoke(config.id, config.heightUnits - 1)
                }
                btnHeightUp.setOnClickListener {
                    if (config.heightUnits < 4) onHeightChanged?.invoke(config.id, config.heightUnits + 1)
                }
                dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onStartDrag?.invoke(this)
                        true
                    } else false
                }
            }
        }
    }

    // ── 3-slot single-cell row ─────────────────────────────────────────────────

    inner class SingleCellViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val widgetCard: MaterialCardView = view.findViewById(R.id.widgetCard)
        private val btnDeleteRow: ImageButton = view.findViewById(R.id.btnDeleteRow)
        private val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
        private val slotContainers = listOf<FrameLayout>(
            view.findViewById(R.id.slot0),
            view.findViewById(R.id.slot1),
            view.findViewById(R.id.slot2)
        )

        fun bind(config: WidgetCardConfig.SingleCellRow) {
            // Set row height = cell width so each slot is square
            widgetCard.tag = config.id
            widgetCard.post {
                if (widgetCard.tag != config.id) return@post
                val cellWidth = slotContainers[0].width
                if (cellWidth > 0) {
                    val lp = widgetCard.layoutParams
                    lp.height = cellWidth
                    widgetCard.layoutParams = lp
                }
            }

            val edit = editMode

            btnDeleteRow.visibility = if (edit) View.VISIBLE else View.GONE
            dragHandle.visibility = if (edit) View.VISIBLE else View.GONE

            if (edit) {
                btnDeleteRow.setOnClickListener { onDeleteCard?.invoke(config.id) }
                dragHandle.setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        onStartDrag?.invoke(this)
                        true
                    } else false
                }
            }

            val slots = config.slots.let {
                // ensure exactly 3 entries
                (0 until 3).map { i -> it.getOrNull(i) }
            }

            slots.forEachIndexed { idx, slot ->
                bindSlot(slotContainers[idx], config.id, idx, slot)
            }
        }

        private fun bindSlot(container: FrameLayout, cardId: String, slotIndex: Int, slot: CellSlotConfig?) {
            container.removeAllViews()
            val inflater = LayoutInflater.from(container.context)

            if (slot == null) {
                // Empty slot: show + button in both normal and edit mode
                val addBtn = inflater.inflate(R.layout.item_widget_cell_add_btn, container, false)
                addBtn.setOnClickListener { onAddSlot?.invoke(cardId, slotIndex) }
                container.addView(addBtn)
            } else {
                // Filled slot: show content, and in edit mode overlay a delete badge
                addSlotContent(container, slot)
                if (editMode) {
                    val deleteBtn = inflater.inflate(R.layout.item_widget_slot_delete_btn, container, false)
                    deleteBtn.setOnClickListener { onDeleteSlot?.invoke(cardId, slotIndex) }
                    container.addView(deleteBtn)
                }
            }
        }

        private fun addSlotContent(container: FrameLayout, slot: CellSlotConfig) {
            val ctx = container.context
            val awm = AppWidgetManager.getInstance(ctx)

            when (slot) {
                is CellSlotConfig.AppWidget -> {
                    val info = awm.getAppWidgetInfo(slot.appWidgetId)
                    if (info != null) {
                        val hostView = hostManager.getOrCreateView(ctx, slot.appWidgetId, info)
                        (hostView.parent as? ViewGroup)?.removeView(hostView)
                        container.addView(hostView, ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        ))
                    }
                }
                is CellSlotConfig.ActivityShortcut -> {
                    val view = LayoutInflater.from(ctx)
                        .inflate(R.layout.item_widget_activity_slot, container, false)
                    val icon = view.findViewById<ImageView>(R.id.slotIcon)
                    val label = view.findViewById<TextView>(R.id.slotLabel)
                    label.text = slot.label
                    try {
                        val pm = ctx.packageManager
                        val ai = pm.getActivityInfo(
                            android.content.ComponentName(slot.packageName, slot.activityName), 0
                        )
                        icon.setImageDrawable(ai.loadIcon(pm))
                    } catch (_: PackageManager.NameNotFoundException) {
                        icon.setImageResource(R.drawable.ic_open_in_new)
                    }
                    view.setOnClickListener {
                        try {
                            val intent = Intent().apply {
                                setClassName(slot.packageName, slot.activityName)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                    container.addView(view)
                }
            }
        }
    }

    // ── Add-card placeholder ──────────────────────────────────────────────────

    inner class AddCardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        fun bind() {
            itemView.setOnClickListener { onAddCard?.invoke() }
        }
    }

    // ── Edit-mode pill toggle ─────────────────────────────────────────────────

    inner class EditPillViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val btnEdit: MaterialButton = view.findViewById(R.id.btnEditPill)

        fun bind() {
            btnEdit.text = if (editMode)
                itemView.context.getString(R.string.widget_done)
            else
                itemView.context.getString(R.string.widget_edit)
            btnEdit.setOnClickListener { setEditMode(!editMode) }
        }
    }

    // ── Built-in: clock ──────────────────────────────────────────────────────

    inner class BuiltInClockViewHolder(val card: BuiltInClockCardView) : RecyclerView.ViewHolder(card) {
        fun bind(config: WidgetCardConfig.BuiltInClock) {
            card.setEditMode(editMode)
            card.onDelete   = { onDeleteCard?.invoke(config.id) }
            card.onDragStart = { onStartDrag?.invoke(this) }
        }
    }

    // ── Built-in: weather ────────────────────────────────────────────────────

    inner class BuiltInWeatherViewHolder(val card: BuiltInWeatherCardView) : RecyclerView.ViewHolder(card) {
        fun bind(config: WidgetCardConfig.BuiltInWeather) {
            card.refresh()
            card.setEditMode(editMode)
            card.onDelete   = { onDeleteCard?.invoke(config.id) }
            card.onDragStart = { onStartDrag?.invoke(this) }
        }
    }
}
