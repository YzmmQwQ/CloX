package com.ailife.clox.settings.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.qs.tile.TileId
import com.ailife.clox.qs.tile.TileLayoutManager
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.util.Collections

class TileEditorFragment : Fragment() {

    private lateinit var activeTiles: MutableList<String>
    private lateinit var availableTiles: MutableList<String>
    private lateinit var activeAdapter: TileListAdapter
    private lateinit var availableAdapter: TileListAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_tile_editor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        activeTiles = TileLayoutManager.loadActiveTiles(ctx).toMutableList()
        val allIds = TileId.values().map { it.key } +
            TileLayoutManager.loadCustomTiles(ctx).map { "custom:${it.uuid}" }
        availableTiles = allIds.filter { it !in activeTiles }.toMutableList()

        // Active list with drag-to-reorder
        activeAdapter = TileListAdapter(activeTiles, isActive = true,
            onRemove = { id ->
                val idx = activeTiles.indexOf(id)
                if (idx >= 0) {
                    activeTiles.removeAt(idx)
                    activeAdapter.notifyItemRemoved(idx)
                }
                availableTiles.add(id)
                availableAdapter.notifyItemInserted(availableTiles.size - 1)
                TileLayoutManager.saveActiveTiles(ctx, activeTiles)
            })

        val activeRv = view.findViewById<RecyclerView>(R.id.activeTilesRv)
        activeRv.layoutManager = LinearLayoutManager(ctx)
        activeRv.adapter = activeAdapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = vh.adapterPosition
                val to = target.adapterPosition
                Collections.swap(activeTiles, from, to)
                activeAdapter.notifyItemMoved(from, to)
                TileLayoutManager.saveActiveTiles(ctx, activeTiles)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        })
        touchHelper.attachToRecyclerView(activeRv)

        // Available list
        availableAdapter = TileListAdapter(availableTiles, isActive = false,
            onAdd = { id ->
                val idx = availableTiles.indexOf(id)
                if (idx >= 0) {
                    availableTiles.removeAt(idx)
                    availableAdapter.notifyItemRemoved(idx)
                }
                activeTiles.add(id)
                activeAdapter.notifyItemInserted(activeTiles.size - 1)
                TileLayoutManager.saveActiveTiles(ctx, activeTiles)
            })

        val availableRv = view.findViewById<RecyclerView>(R.id.availableTilesRv)
        availableRv.layoutManager = LinearLayoutManager(ctx)
        availableRv.adapter = availableAdapter

        // FAB for adding custom tiles
        view.findViewById<ExtendedFloatingActionButton>(R.id.addCustomTileFab).setOnClickListener {
            CustomTileDialogFragment { config ->
                TileLayoutManager.addCustomTile(ctx, config)
                val customId = "custom:${config.uuid}"
                activeTiles.add(customId)
                activeAdapter.notifyItemInserted(activeTiles.size - 1)
                TileLayoutManager.saveActiveTiles(ctx, activeTiles)
            }.show(parentFragmentManager, "custom_tile")
        }
    }
}

class TileListAdapter(
    private val items: MutableList<String>,
    private val isActive: Boolean,
    private val onRemove: ((String) -> Unit)? = null,
    private val onAdd: ((String) -> Unit)? = null
) : RecyclerView.Adapter<TileListAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.tileEditorIcon)
        val label: TextView = view.findViewById(R.id.tileEditorLabel)
        val action: ImageView = view.findViewById(R.id.tileEditorAction)
        val dragHandle: ImageView = view.findViewById(R.id.tileEditorDragHandle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_tile_editor, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val id = items[position]
        val ctx = holder.itemView.context
        val descriptor = TileLayoutManager.descriptorFor(id, ctx)
        holder.label.text = descriptor?.defaultLabel ?: id
        descriptor?.iconRes?.let { holder.icon.setImageResource(it) }
        if (isActive) {
            holder.dragHandle.visibility = View.VISIBLE
            holder.action.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            holder.action.setOnClickListener { onRemove?.invoke(id) }
        } else {
            holder.dragHandle.visibility = View.GONE
            holder.action.setImageResource(android.R.drawable.ic_input_add)
            holder.action.setOnClickListener { onAdd?.invoke(id) }
        }
    }
}
