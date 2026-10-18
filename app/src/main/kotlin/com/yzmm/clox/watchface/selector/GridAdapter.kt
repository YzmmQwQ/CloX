package com.yzmm.clox.watchface.selector

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yzmm.clox.R
import com.yzmm.clox.watchface.WatchfaceItem
import com.yzmm.clox.watchface.WatchfaceRepository
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class GridAdapter(
    private val scope: CoroutineScope,
    private val onSelect: (WatchfaceItem, ImageView) -> Unit,
    private val onLongPress: (WatchfaceItem, View) -> Unit,
    private val onFolderLongPress: (String, View) -> Unit,
    private val onAddFolderClick: () -> Unit,
    private val onDefaultSelect: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class GridItem {
        data class SectionHeader(
            val id: String,
            val title: String,
            val count: Int,
            val folderName: String?,
            var isExpanded: Boolean = true,
        ) : GridItem()
        data class WatchfaceCell(val item: WatchfaceItem) : GridItem()
        object AddFolder : GridItem()
        object DefaultWatchface : GridItem()
    }

    companion object {
        const val TYPE_HEADER     = 0
        const val TYPE_CELL       = 1
        const val TYPE_ADD_FOLDER = 2
        const val TYPE_DEFAULT_WF = 3
    }

    private var allItems: List<WatchfaceItem> = emptyList()
    private var allFolderNames: List<String> = emptyList()
    private var activeIsDefault: Boolean = false
    private var filterQuery: String = ""
    private val collapsedSections = mutableSetOf<String>()
    var flatList: List<GridItem> = emptyList()
        private set

    fun submitData(items: List<WatchfaceItem>, folderNames: List<String>, isDefault: Boolean = false) {
        allItems = items
        allFolderNames = folderNames
        activeIsDefault = isDefault
        rebuild()
    }

    fun setQuery(q: String) {
        filterQuery = q
        rebuild()
    }

    private fun rebuild() {
        val src = if (filterQuery.isBlank()) allItems
                  else allItems.filter { it.name.contains(filterQuery, ignoreCase = true) }

        val pinned  = src.filter { it.isPinned }
        val byFolder = src.filter { !it.isPinned && it.folder != null }.groupBy { it.folder!! }
        val roots   = src.filter { !it.isPinned && it.folder == null }

        // All unique folder names: from items + from disk (empty folders)
        val allFolders = (allFolderNames + byFolder.keys).toSortedSet()

        val list = mutableListOf<GridItem>()

        // Pinned section
        if (pinned.isNotEmpty() || filterQuery.isBlank()) {
            val hdr = GridItem.SectionHeader("pinned", "Pinned", pinned.size, null,
                "pinned" !in collapsedSections)
            list += hdr
            if (hdr.isExpanded) pinned.forEach { list += GridItem.WatchfaceCell(it) }
        }

        // Folder sections — shown even when empty
        allFolders.forEach { folderName ->
            val items = byFolder[folderName] ?: emptyList()
            val hdr = GridItem.SectionHeader(folderName, folderName, items.size, folderName,
                folderName !in collapsedSections)
            list += hdr
            if (hdr.isExpanded) items.forEach { list += GridItem.WatchfaceCell(it) }
        }

        // "Other" section (root items, not pinned)
        if (roots.isNotEmpty() || filterQuery.isBlank()) {
            val hdr = GridItem.SectionHeader("other", "Other", roots.size, null,
                "other" !in collapsedSections)
            list += hdr
            if (hdr.isExpanded) roots.forEach { list += GridItem.WatchfaceCell(it) }
        }

        if (filterQuery.isBlank()) {
            list += GridItem.DefaultWatchface   // full-span card at bottom
            list += GridItem.AddFolder
        }

        flatList = list
        notifyDataSetChanged()
    }

    fun getActivePosition(): Int =
        flatList.indexOfFirst { it is GridItem.WatchfaceCell && it.item.isActive }

    override fun getItemCount() = flatList.size
    override fun getItemViewType(pos: Int) = when (flatList[pos]) {
        is GridItem.SectionHeader  -> TYPE_HEADER
        is GridItem.WatchfaceCell  -> TYPE_CELL
        is GridItem.AddFolder      -> TYPE_ADD_FOLDER
        is GridItem.DefaultWatchface -> TYPE_DEFAULT_WF
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER     -> HeaderVH(inf.inflate(R.layout.item_wf_folder_header, parent, false))
            TYPE_CELL       -> CellVH(inf.inflate(R.layout.item_wf_grid_cell, parent, false))
            TYPE_ADD_FOLDER -> AddFolderVH(inf.inflate(R.layout.item_wf_folder_footer, parent, false))
            else            -> DefaultWfVH(inf.inflate(R.layout.item_wf_grid_cell, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = flatList[position]) {
            is GridItem.SectionHeader   -> (holder as HeaderVH).bind(item)
            is GridItem.WatchfaceCell   -> (holder as CellVH).bind(item.item)
            is GridItem.AddFolder       -> (holder as AddFolderVH).bind()
            is GridItem.DefaultWatchface -> (holder as DefaultWfVH).bind()
        }
    }

    // ── Section header ────────────────────────────────────────────────────────

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvFolderName)
        private val tvCount: TextView = view.findViewById(R.id.tvCount)
        private val chevron: ImageView = view.findViewById(R.id.ivChevron)

        fun bind(item: GridItem.SectionHeader) {
            tvTitle.text = item.title
            tvCount.text = item.count.toString()
            chevron.rotation = if (item.isExpanded) 180f else 0f
            itemView.setOnClickListener {
                if (item.isExpanded) collapsedSections.add(item.id)
                else collapsedSections.remove(item.id)
                rebuild()
            }
            itemView.setOnLongClickListener(
                if (item.folderName != null) View.OnLongClickListener {
                    onFolderLongPress(item.folderName, itemView); true
                } else null
            )
        }
    }

    // ── Normal watchface cell ─────────────────────────────────────────────────

    inner class CellVH(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.card)
        private val previewImage: ImageView = view.findViewById(R.id.previewImage)
        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val activeFrame: View = view.findViewById(R.id.activeFrame)
        private var previewJob: Job? = null

        fun bind(item: WatchfaceItem) {
            tvName.text = item.name
            activeFrame.visibility = if (item.isActive) View.VISIBLE else View.GONE

            val primary = MaterialColors.getColor(itemView.context,
                androidx.appcompat.R.attr.colorPrimary, 0)
            if (item.isActive) {
                card.strokeWidth = 3
                card.strokeColor = primary
                tvName.setBackgroundColor(Color.argb(60,
                    Color.red(primary), Color.green(primary), Color.blue(primary)))
            } else {
                card.strokeWidth = 0
                tvName.setBackgroundColor(Color.TRANSPARENT)
            }

            previewJob?.cancel()
            val cached = WatchfaceRepository.getPreviewCached(item.absolutePath)
            if (cached != null) {
                previewImage.setImageBitmap(cached)
            } else {
                previewImage.setImageResource(R.drawable.ic_image)
                previewJob = scope.launch {
                    val bmp = WatchfaceRepository.loadPreview(item.file)
                    if (bmp != null) previewImage.setImageBitmap(bmp)
                }
            }

            itemView.setOnClickListener { onSelect(item, previewImage) }
            itemView.setOnLongClickListener { onLongPress(item, itemView); true }
        }
    }

    // ── Default watchface — same look as a normal cell ────────────────────────

    inner class DefaultWfVH(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.card)
        private val previewImage: ImageView = view.findViewById(R.id.previewImage)
        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val activeFrame: View = view.findViewById(R.id.activeFrame)

        fun bind() {
            previewImage.setImageResource(R.mipmap.ic_launcher)
            previewImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            tvName.setText(R.string.wf_default_watchface)

            val primary = MaterialColors.getColor(itemView.context,
                androidx.appcompat.R.attr.colorPrimary, 0)
            if (activeIsDefault) {
                activeFrame.visibility = View.VISIBLE
                card.strokeWidth = 3
                card.strokeColor = primary
                tvName.setBackgroundColor(Color.argb(60,
                    Color.red(primary), Color.green(primary), Color.blue(primary)))
            } else {
                activeFrame.visibility = View.GONE
                card.strokeWidth = 0
                tvName.setBackgroundColor(Color.TRANSPARENT)
            }

            itemView.setOnClickListener { onDefaultSelect() }
        }
    }

    // ── Add folder footer ─────────────────────────────────────────────────────

    inner class AddFolderVH(view: View) : RecyclerView.ViewHolder(view) {
        fun bind() {
            itemView.findViewById<TextView>(R.id.tvFooterLabel).setText(R.string.wf_add_folder)
            itemView.findViewById<ImageView>(R.id.ivFooterIcon).setImageResource(R.drawable.ic_add)
            itemView.setOnClickListener { onAddFolderClick() }
        }
    }
}
