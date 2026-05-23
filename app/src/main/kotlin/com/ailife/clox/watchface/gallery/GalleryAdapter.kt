package com.ailife.clox.watchface.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference

class GalleryAdapter(
    private val scope: CoroutineScope,
    private val onDeleteRequest: (File, Int) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.VH>() {

    private val items = mutableListOf<File>()

    fun setItems(files: List<File>) {
        items.clear()
        items.addAll(files)
        notifyDataSetChanged()
    }

    fun removeAt(position: Int) {
        if (position in items.indices) {
            items.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_gallery_image, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: VH) {
        holder.cancelLoad()
        holder.imageView.setImageBitmap(null)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.imgGalleryItem)
        private var loadJob: Job? = null

        fun bind(file: File) {
            imageView.setImageBitmap(null)
            val ref = WeakReference(imageView)
            loadJob = scope.launch {
                val bmp = loadThumbnail(file, 512)
                withContext(Dispatchers.Main) {
                    ref.get()?.setImageBitmap(bmp)
                }
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_ID.toInt()) {
                    onDeleteRequest(file, pos)
                }
                true
            }
        }

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
        }
    }

    private suspend fun loadThumbnail(file: File, maxPx: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, opts)
                opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, maxPx)
                opts.inJustDecodeBounds = false
                BitmapFactory.decodeFile(file.absolutePath, opts)
            } catch (_: Exception) {
                null
            }
        }

    private fun calculateInSampleSize(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        if (width > maxPx || height > maxPx) {
            val larger = maxOf(width, height)
            while (larger / (sample * 2) > maxPx) sample *= 2
        }
        return sample
    }
}
