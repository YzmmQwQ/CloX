package com.ailife.clox.watchface.selector

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.watchface.WatchfaceItem
import com.ailife.clox.watchface.WatchfaceRepository
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PagerAdapter(
    private val scope: CoroutineScope,
    private val cardWidth: Int = 0,
    private val onStoreClick: () -> Unit,
    private val onRemoteClick: () -> Unit,
    private val onImportClick: () -> Unit,
    private val onRandomClick: () -> Unit,
    private val onDefaultSelect: () -> Unit,
    private val onDelete: (WatchfaceItem) -> Unit,
    private val onRename: (WatchfaceItem) -> Unit,
    private val onPinToggle: (WatchfaceItem) -> Unit,
    private val onSelect: (WatchfaceItem, ImageView) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class PagerItem {
        object Header : PagerItem()
        object DefaultWatchface : PagerItem()
        data class Watchface(val item: WatchfaceItem) : PagerItem()
    }

    private companion object {
        const val TYPE_HEADER  = 0
        const val TYPE_DEFAULT = 1
        const val TYPE_CARD    = 2
    }

    private var items: List<PagerItem> = emptyList()
    private var activeIsDefault: Boolean = false
    var onActiveCardBound: ((ImageView) -> Unit)? = null

    fun submitList(watchfaces: List<WatchfaceItem>, isDefault: Boolean) {
        activeIsDefault = isDefault
        val pinned = watchfaces.filter { it.isPinned }
        val others = watchfaces.filter { !it.isPinned }
        items = listOf(PagerItem.Header, PagerItem.DefaultWatchface) +
                (pinned + others).map { PagerItem.Watchface(it) }
        notifyDataSetChanged()
    }

    fun getActivePosition(): Int {
        if (activeIsDefault) return 1  // DefaultWatchface is always at index 1
        val pos = items.indexOfFirst { it is PagerItem.Watchface && it.item.isActive }
        return if (pos < 0) 0 else pos
    }

    override fun getItemCount() = items.size
    override fun getItemViewType(position: Int) = when (items[position]) {
        is PagerItem.Header         -> TYPE_HEADER
        is PagerItem.DefaultWatchface -> TYPE_DEFAULT
        is PagerItem.Watchface      -> TYPE_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        val view = when (viewType) {
            TYPE_HEADER  -> inf.inflate(R.layout.item_wf_pager_header, parent, false)
            else         -> inf.inflate(R.layout.item_wf_pager_card, parent, false)
        }
        if (cardWidth > 0)
            view.layoutParams = RecyclerView.LayoutParams(cardWidth, RecyclerView.LayoutParams.WRAP_CONTENT)
        return when (viewType) {
            TYPE_HEADER  -> HeaderVH(view)
            TYPE_DEFAULT -> DefaultCardVH(view)
            else         -> CardVH(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderVH      -> holder.bind()
            is DefaultCardVH -> holder.bind()
            is CardVH        -> holder.bind((items[position] as PagerItem.Watchface).item)
        }
    }

    // ── Header card ──────────────────────────────────────────────────────────

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        fun bind() {
            itemView.findViewById<View>(R.id.btnStore).setOnClickListener { onStoreClick() }
            itemView.findViewById<View>(R.id.btnRemote).setOnClickListener { onRemoteClick() }
            itemView.findViewById<View>(R.id.btnImport).setOnClickListener { onImportClick() }
            itemView.findViewById<View>(R.id.btnRandom).setOnClickListener { onRandomClick() }
        }
    }

    // ── Default watchface card ────────────────────────────────────────────────

    inner class DefaultCardVH(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.card)
        private val previewImage: ImageView = view.findViewById(R.id.previewImage)
        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        private val btnRename: ImageButton = view.findViewById(R.id.btnRename)
        private val btnPin: ImageButton = view.findViewById(R.id.btnPin)
        private val activeFrame: View = view.findViewById(R.id.activeFrame)

        fun bind() {
            tvName.setText(R.string.wf_default_watchface)
            previewImage.setImageResource(R.mipmap.ic_launcher)
            previewImage.scaleType = ImageView.ScaleType.CENTER_INSIDE
            btnDelete.visibility = View.GONE
            btnRename.visibility = View.GONE
            btnPin.visibility = View.GONE

            val primary = MaterialColors.getColor(itemView.context,
                androidx.appcompat.R.attr.colorPrimary, 0)
            if (activeIsDefault) {
                activeFrame.visibility = View.VISIBLE
                card.strokeWidth = 4
                card.strokeColor = primary
                tvName.setBackgroundColor(Color.argb(60,
                    Color.red(primary), Color.green(primary), Color.blue(primary)))
                onActiveCardBound?.invoke(previewImage)
            } else {
                activeFrame.visibility = View.GONE
                card.strokeWidth = 0
                tvName.setBackgroundColor(Color.TRANSPARENT)
            }
            itemView.setOnClickListener { onDefaultSelect() }
        }
    }

    // ── Normal watchface card ─────────────────────────────────────────────────

    inner class CardVH(view: View) : RecyclerView.ViewHolder(view) {
        private val card: MaterialCardView = view.findViewById(R.id.card)
        private val previewImage: ImageView = view.findViewById(R.id.previewImage)
        private val tvName: TextView = view.findViewById(R.id.tvName)
        private val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        private val btnRename: ImageButton = view.findViewById(R.id.btnRename)
        private val btnPin: ImageButton = view.findViewById(R.id.btnPin)
        private val activeFrame: View = view.findViewById(R.id.activeFrame)
        private var previewJob: Job? = null

        fun bind(item: WatchfaceItem) {
            // Ensure action buttons are visible (in case VH was recycled from DefaultCardVH)
            btnDelete.visibility = View.VISIBLE
            btnRename.visibility = View.VISIBLE
            btnPin.visibility = View.VISIBLE
            previewImage.scaleType = ImageView.ScaleType.CENTER_CROP

            tvName.text = item.name
            activeFrame.visibility = if (item.isActive) View.VISIBLE else View.GONE

            val primary = MaterialColors.getColor(
                itemView.context, androidx.appcompat.R.attr.colorPrimary, 0)
            if (item.isActive) {
                card.strokeWidth = 4
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

            btnDelete.setOnClickListener { onDelete(item) }
            btnRename.setOnClickListener { onRename(item) }
            btnPin.alpha = if (item.isPinned) 1f else 0.5f
            btnPin.setOnClickListener { onPinToggle(item) }
            itemView.setOnClickListener { onSelect(item, previewImage) }

            if (item.isActive) onActiveCardBound?.invoke(previewImage)
        }
    }
}
