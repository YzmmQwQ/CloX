package com.yzmm.clox.overlay.widgets

import android.content.ComponentName
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yzmm.clox.R
import com.yzmm.clox.overlay.widgets.model.CellSlotConfig
import com.yzmm.clox.overlay.widgets.model.WidgetCardConfig
import com.yzmm.clox.overlay.widgets.picker.ActivityPickerDialog
import com.yzmm.clox.overlay.widgets.picker.AddCardDialog
import com.yzmm.clox.overlay.widgets.picker.ShortcutPickerDialog

class WidgetsOverlay @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : FrameLayout(ctx, attrs) {

    val widgetHostManager = WidgetHostManager(ctx)

    private val widgetList: RecyclerView
    private lateinit var adapter: WidgetDrawerAdapter
    private lateinit var dragHelper: ItemTouchHelper

    private val cards get() = adapter.getCards().toMutableList()

    // ── Callbacks wired by MainActivity ──────────────────────────────────────

    /** Called when the user wants to pick an AppWidget. MainActivity handles the full pick+bind+configure flow. */
    var onPickAppWidget: ((allocatedId: Int, pendingSlot: PendingSlot?) -> Unit)? = null

    private var fragmentManager: FragmentManager? = null
    internal var pendingSlot: PendingSlot? = null

    /** Null = full-width card; non-null = slot in an existing single-cell row. */
    data class PendingSlot(val cardId: String, val slotIndex: Int)

    init {
        LayoutInflater.from(ctx).inflate(R.layout.overlay_widgets, this, true)
        widgetList = findViewById(R.id.widgetList)
        setupAdapter()
        setupDragHelper()
    }

    fun init(fm: FragmentManager) {
        fragmentManager = fm
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        widgetHostManager.startListening()
        loadCards()
        prewarmWidgets()
    }

    private var warmedUp = false

    /**
     * Pre-build the drawer's contents while it is still hidden so the first slide doesn't pay
     * for it. createView() inflates each widget's RemoteViews and the RecyclerView's first
     * layout inflates every item ViewHolder + measures the widgets — all of which otherwise
     * happen synchronously in the frame the panel becomes visible.
     *
     * Step 1 (spread one-per-frame): create the AppWidgetHostViews so getOrCreateView() is a
     * cache hit later.
     * Step 2 (once, after a short settle delay): force-measure/layout the overlay at full
     * screen size so the RecyclerView inflates and binds its ViewHolders and measures the host
     * views into its recycler pool. The real layout pass on slide then reuses these views.
     */
    private fun prewarmWidgets() {
        val awm = android.appwidget.AppWidgetManager.getInstance(context)
        val ids = buildList {
            adapter.getCards().forEach { card ->
                when (card) {
                    is WidgetCardConfig.FullWidth -> add(card.appWidgetId)
                    is WidgetCardConfig.SingleCellRow -> card.slots.forEach { slot ->
                        if (slot is CellSlotConfig.AppWidget) add(slot.appWidgetId)
                    }
                    else -> {}
                }
            }
        }
        ids.forEach { id ->
            post {
                val info = awm.getAppWidgetInfo(id) ?: return@post
                widgetHostManager.getOrCreateView(context, id, info)
            }
        }
        // Defer the layout warm so it lands after the launcher's own startup work settles.
        postDelayed({ warmUpLayout() }, 400)
    }

    /** Force one measure+layout pass while GONE so the RecyclerView pre-inflates its items. */
    private fun warmUpLayout() {
        if (warmedUp || visibility == VISIBLE || width > 0) return
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        if (w <= 0 || h <= 0) return
        measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
        layout(0, 0, w, h)
        warmedUp = true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        widgetHostManager.stopListening()
    }

    fun onPanelOpened() {
        adapter.setEditMode(false)
    }

    fun onPanelClosed() {
        adapter.setEditMode(false)
    }

    /** True when the list still has content above the visible area. */
    fun canScrollUp(): Boolean = widgetList.canScrollVertically(-1)

    // ── Widget pick result (called from MainActivity) ─────────────────────────

    fun onWidgetPicked(widgetId: Int, pendingSlot: PendingSlot?) {
        val slot = pendingSlot
        if (slot == null) {
            // Full-width card
            val config = WidgetCardConfig.FullWidth(appWidgetId = widgetId)  // defaults to Auto height
            adapter.addCard(config)
        } else {
            // Single-cell slot
            val existing = adapter.getCards().firstOrNull { it.id == slot.cardId }
                as? WidgetCardConfig.SingleCellRow ?: return
            val newSlots = existing.slots.toMutableList()
            newSlots[slot.slotIndex] = CellSlotConfig.AppWidget(widgetId)
            adapter.updateCard(existing.copy(slots = newSlots))
        }
        saveCards()
    }

    fun onWidgetPickCancelled(allocatedId: Int) {
        widgetHostManager.removeWidget(allocatedId)
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupAdapter() {
        adapter = WidgetDrawerAdapter(widgetHostManager)
        widgetList.layoutManager = LinearLayoutManager(context)
        widgetList.adapter = adapter

        adapter.onDeleteCard = { cardId ->
            val card = adapter.getCards().firstOrNull { it.id == cardId }
            // Release any widget IDs held by the card
            when (card) {
                is WidgetCardConfig.FullWidth -> widgetHostManager.removeWidget(card.appWidgetId)
                is WidgetCardConfig.SingleCellRow -> card.slots.forEach { slot ->
                    if (slot is CellSlotConfig.AppWidget) widgetHostManager.removeWidget(slot.appWidgetId)
                }
                is WidgetCardConfig.BuiltInClock, is WidgetCardConfig.BuiltInWeather, null -> {}
            }
            adapter.removeCard(cardId)
            saveCards()
        }

        adapter.onDeleteSlot = { cardId, slotIndex ->
            val card = adapter.getCards().firstOrNull { it.id == cardId }
            if (card is WidgetCardConfig.SingleCellRow) {
                val removed = card.slots.getOrNull(slotIndex)
                if (removed is CellSlotConfig.AppWidget) widgetHostManager.removeWidget(removed.appWidgetId)
                val newSlots = card.slots.toMutableList()
                newSlots[slotIndex] = null
                adapter.updateCard(card.copy(slots = newSlots))
                saveCards()
            }
        }

        adapter.onHeightChanged = { cardId, newUnits ->
            val card = adapter.getCards().firstOrNull { it.id == cardId }
            if (card is WidgetCardConfig.FullWidth) {
                adapter.updateCard(card.copy(heightUnits = newUnits))
                saveCards()
            }
        }

        adapter.onAddCard = { showAddCardDialog() }

        adapter.onAddSlot = { cardId, slotIndex ->
            pendingSlot = PendingSlot(cardId, slotIndex)
            showSlotPicker(cardId, slotIndex)
        }

        adapter.onStartDrag = { vh -> dragHelper.startDrag(vh) }

        adapter.onEditToggled = { _ ->
            if (!adapter.editMode) saveCards()
        }
    }

    private fun setupDragHelper() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder
            ): Boolean {
                val fp = from.bindingAdapterPosition
                val tp = to.bindingAdapterPosition
                val maxCard = adapter.getCards().size - 1
                if (fp > maxCard || tp > maxCard) return false
                return adapter.moveCard(fp, tp)
            }

            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                saveCards()  // persist once when drag is fully released
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
            override fun isLongPressDragEnabled() = false
        }
        dragHelper = ItemTouchHelper(callback)
        dragHelper.attachToRecyclerView(widgetList)
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showAddCardDialog() {
        val fm = fragmentManager ?: return
        AddCardDialog().also { dlg ->
            dlg.onFullWidthWidget = { pickAppWidget(null) }
            dlg.onSingleCellRow   = {
                adapter.addCard(WidgetCardConfig.SingleCellRow(slots = listOf(null, null, null)))
                saveCards()
            }
            dlg.onBuiltInClock   = {
                adapter.addCard(WidgetCardConfig.BuiltInClock())
                saveCards()
            }
            dlg.onBuiltInWeather = {
                adapter.addCard(WidgetCardConfig.BuiltInWeather())
                saveCards()
            }
        }.show(fm, AddCardDialog.TAG)
    }

    private fun showSlotPicker(cardId: String, slotIndex: Int) {
        val items = arrayOf(
            context.getString(R.string.widget_slot_appwidget),
            context.getString(R.string.widget_slot_activity),
            context.getString(R.string.widget_slot_shortcut)
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.widget_pick_slot_type)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickAppWidget(PendingSlot(cardId, slotIndex))
                    1 -> showActivityPicker(cardId, slotIndex)
                    2 -> showShortcutPicker(cardId, slotIndex)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showShortcutPicker(cardId: String, slotIndex: Int) {
        val fm = fragmentManager ?: return
        ShortcutPickerDialog().also { dlg ->
            dlg.onShortcutSelected = { shortcutSlot ->
                val card = adapter.getCards().firstOrNull { it.id == cardId }
                if (card is WidgetCardConfig.SingleCellRow) {
                    val newSlots = card.slots.toMutableList()
                    newSlots[slotIndex] = shortcutSlot
                    adapter.updateCard(card.copy(slots = newSlots))
                    saveCards()
                }
            }
        }.show(fm, ShortcutPickerDialog.TAG)
    }

    private fun pickAppWidget(slot: PendingSlot?) {
        pendingSlot = slot
        val allocatedId = widgetHostManager.allocateId()
        onPickAppWidget?.invoke(allocatedId, slot)
    }

    private fun showActivityPicker(cardId: String, slotIndex: Int) {
        val fm = fragmentManager ?: return
        ActivityPickerDialog().also { dlg ->
            dlg.onActivitySelected = { activitySlot ->
                val card = adapter.getCards().firstOrNull { it.id == cardId }
                if (card is WidgetCardConfig.SingleCellRow) {
                    val newSlots = card.slots.toMutableList()
                    newSlots[slotIndex] = activitySlot
                    adapter.updateCard(card.copy(slots = newSlots))
                    saveCards()
                }
                pendingSlot = null
            }
        }.show(fm, ActivityPickerDialog.TAG)
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun loadCards() {
        adapter.setCards(WidgetPrefs.loadCards(context))
    }

    private fun saveCards() {
        WidgetPrefs.saveCards(context, adapter.getCards())
    }
}
