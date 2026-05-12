package com.ailife.clox.applist

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.ailife.clox.R
import com.ailife.clox.applist.model.AppItem
import com.ailife.clox.icon.IconPackLoader
import java.io.File
import java.io.FileOutputStream

object IconPickerDialog {

    fun show(context: Context, item: AppItem, onDone: () -> Unit) {
        val root = LayoutInflater.from(context).inflate(R.layout.dialog_icon_picker, null, false)
        val searchField    = root.findViewById<TextInputEditText>(R.id.searchField)
        val iconGrid       = root.findViewById<RecyclerView>(R.id.iconGrid)
        val loadingSpinner = root.findViewById<ProgressBar>(R.id.loadingProgress)
        val galleryBtn     = root.findViewById<MaterialButton>(R.id.galleryBtn)

        iconGrid.layoutManager = GridLayoutManager(context, 5)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.icon_picker_title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        // Adapter is set up empty; icons are filled in after background load
        val adapter = IconPackAdapter(emptyList()) { _, drawable ->
            saveCustomIcon(context, item.packageName, item.activityName, drawable)
            onDone()
            dialog.dismiss()
        }
        iconGrid.adapter = adapter

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString()?.trim()?.lowercase() ?: "")
            }
        })

        galleryBtn.setOnClickListener {
            dialog.dismiss()
            if (context is Activity) {
                pendingPackageName = item.packageName
                pendingActivityName = item.activityName
                pendingOnDone = onDone
                launchImagePicker(context)
            }
        }

        dialog.show()

        // Load pack icons off the main thread
        val handler = Handler(Looper.getMainLooper())
        Thread {
            val icons = loadPackIcons(context)
            handler.post {
                if (dialog.isShowing) {
                    loadingSpinner.visibility = View.GONE
                    iconGrid.visibility = View.VISIBLE
                    adapter.setItems(icons)
                }
            }
        }.start()
    }

    /**
     * Open the system image picker. Priority:
     *  1. Android 13+: Photo Picker (ACTION_PICK_IMAGES) — sandboxed, no permission needed
     *  2. Gallery intent with MediaStore URI — opens gallery/photos app directly
     *  3. SAF fallback (ACTION_GET_CONTENT)
     */
    fun launchImagePicker(activity: Activity) {
        val intent = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                Intent(MediaStore.ACTION_PICK_IMAGES)
            else ->
                Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }
        // If neither resolves (stripped ROM), fall back to SAF
        val resolved = intent.resolveActivity(activity.packageManager) != null
        val finalIntent = if (resolved) intent else Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(finalIntent, GALLERY_REQUEST_CODE)
        } catch (_: Exception) {
            pendingPackageName = null; pendingOnDone = null
        }
    }

    fun handleGalleryResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val pkg      = pendingPackageName  ?: return
        val activity = pendingActivityName ?: return
        val onDone   = pendingOnDone       ?: return
        pendingPackageName = null; pendingActivityName = null; pendingOnDone = null
        try {
            val bitmap = context.contentResolver.openInputStream(data.data!!)
                ?.use { BitmapFactory.decodeStream(it) } ?: return
            val dir = File(context.filesDir, "icons").apply { mkdirs() }
            val safeName = activity.substringAfterLast('.')
            val file = File(dir, "${pkg}_${safeName}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            AppListPrefs.saveCustomIconPath(context, pkg, activity, file.absolutePath)
            onDone()
        } catch (_: Exception) {}
    }

    const val GALLERY_REQUEST_CODE = 0xC10C

    private var pendingPackageName:  String? = null
    private var pendingActivityName: String? = null
    private var pendingOnDone: (() -> Unit)? = null

    private fun saveCustomIcon(context: Context, packageName: String, activityName: String, drawable: Drawable) {
        try {
            val dir = File(context.filesDir, "icons").apply { mkdirs() }
            // Use a per-activity filename so different launcher entries from the same package
            // don't overwrite each other's custom icon.
            val safeName = activityName.substringAfterLast('.')
            val file = File(dir, "${packageName}_${safeName}.png")
            val bmp = drawableToBitmap(drawable, 192)
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            AppListPrefs.saveCustomIconPath(context, packageName, activityName, file.absolutePath)
        } catch (_: Exception) {}
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }

    /** Use the cached pack map from IconPackLoader — avoids re-parsing appfilter.xml. */
    private fun loadPackIcons(context: Context): List<Pair<String, Drawable>> {
        val packPkg = IconPackLoader.getSelectedPack(context) ?: return emptyList()
        return IconPackLoader.getPackMap(context, packPkg).entries.map { it.key to it.value }
    }

    // ── Icon grid adapter ─────────────────────────────────────────────────────

    class IconPackAdapter(
        all: List<Pair<String, Drawable>>,
        private val onPick: (String, Drawable) -> Unit
    ) : RecyclerView.Adapter<IconPackAdapter.VH>() {

        private var all      = all.toList()
        private var filtered = all.toList()

        fun setItems(items: List<Pair<String, Drawable>>) {
            all      = items
            filtered = items
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            filtered = if (query.isEmpty()) all
            else all.filter { it.first.lowercase().contains(query) }
            notifyDataSetChanged()
        }

        inner class VH(val iv: ImageView) : RecyclerView.ViewHolder(iv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val density = parent.context.resources.displayMetrics.density
            val size = (52 * density + 0.5f).toInt()
            val pad  = (6  * density + 0.5f).toInt()
            val ta = parent.context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            val ripple = ta.getDrawable(0)
            ta.recycle()
            val iv = ImageView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(pad, pad, pad, pad)
                background = ripple
            }
            return VH(iv)
        }

        override fun getItemCount() = filtered.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (name, drawable) = filtered[position]
            holder.iv.setImageDrawable(drawable)
            holder.iv.setOnClickListener { onPick(name, drawable) }
        }
    }
}
