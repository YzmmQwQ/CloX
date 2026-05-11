package com.ailife.clox.applist

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.applist.model.AppItem
import com.ailife.clox.icon.IconPackLoader
import java.io.File
import java.io.FileOutputStream

object IconPickerDialog {

    fun show(context: Context, item: AppItem, onDone: () -> Unit) {
        val packIcons = loadPackIcons(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = 16.dp(context)
            setPadding(p, p, p, 0)
        }

        val search = EditText(context).apply {
            hint = context.getString(R.string.icon_picker_search_hint)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(search)

        val grid = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 5)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 320.dp(context)
            ).apply { topMargin = 8.dp(context) }
        }
        root.addView(grid)

        val galleryBtn = TextView(context).apply {
            text = context.getString(R.string.icon_picker_from_gallery)
            textSize = 14f
            val pv = 12.dp(context)
            setPadding(0, pv, 0, pv)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(galleryBtn)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.icon_picker_title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        val adapter = IconPackAdapter(packIcons) { name, drawable ->
            saveCustomIcon(context, item.packageName, drawable)
            onDone()
            dialog.dismiss()
        }
        grid.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                adapter.filter(q)
            }
        })

        galleryBtn.setOnClickListener {
            dialog.dismiss()
            if (context is Activity) {
                context.startActivityForResult(
                    Intent(Intent.ACTION_PICK).apply { type = "image/*" },
                    GALLERY_REQUEST_CODE
                )
                pendingPackageName = item.packageName
                pendingOnDone = onDone
            }
        }

        dialog.show()
    }

    // Called from Activity.onActivityResult
    fun handleGalleryResult(context: Context, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val pkg = pendingPackageName ?: return
        val onDone = pendingOnDone ?: return
        pendingPackageName = null; pendingOnDone = null

        try {
            val stream = context.contentResolver.openInputStream(data.data!!) ?: return
            val drawable = Drawable.createFromStream(stream, null) ?: return
            stream.close()
            saveCustomIcon(context, pkg, drawable)
            onDone()
        } catch (_: Exception) {}
    }

    const val GALLERY_REQUEST_CODE = 0xC10C

    private var pendingPackageName: String? = null
    private var pendingOnDone: (() -> Unit)? = null

    private fun saveCustomIcon(context: Context, packageName: String, drawable: Drawable) {
        try {
            val dir = File(context.filesDir, "icons").apply { mkdirs() }
            val file = File(dir, "$packageName.png")
            val bmp = drawableToBitmap(drawable, 192)
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            AppListPrefs.saveCustomIconPath(context, packageName, file.absolutePath)
        } catch (_: Exception) {}
    }

    private fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bmp
    }

    private fun loadPackIcons(context: Context): List<Pair<String, Drawable>> {
        val packPkg = IconPackLoader.getSelectedPack(context) ?: return emptyList()
        return try {
            val pm = context.packageManager
            val packRes = pm.getResourcesForApplication(packPkg)
            val packCtx = context.createPackageContext(packPkg, 0)
            val appfilter = packCtx.assets.open("appfilter.xml")
            val parser = android.util.Xml.newPullParser()
            parser.setInput(appfilter, "UTF-8")
            val result = mutableListOf<Pair<String, Drawable>>()
            while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "item") {
                    val drawableName = parser.getAttributeValue(null, "drawable") ?: ""
                    if (drawableName.isNotEmpty()) {
                        val resId = packRes.getIdentifier(drawableName, "drawable", packPkg)
                        if (resId != 0) {
                            try {
                                result.add(drawableName to packRes.getDrawable(resId, null))
                            } catch (_: Exception) {}
                        }
                    }
                }
                parser.next()
            }
            appfilter.close()
            result
        } catch (_: Exception) { emptyList() }
    }

    // ── Icon pack grid adapter ────────────────────────────────────────────────

    class IconPackAdapter(
        private val all: List<Pair<String, Drawable>>,
        private val onPick: (String, Drawable) -> Unit
    ) : RecyclerView.Adapter<IconPackAdapter.VH>() {

        private var filtered = all.toList()

        fun filter(query: String) {
            filtered = if (query.isEmpty()) all
            else all.filter { it.first.lowercase().contains(query) }
            notifyDataSetChanged()
        }

        inner class VH(val iv: ImageView) : RecyclerView.ViewHolder(iv)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val iv = ImageView(parent.context).apply {
                val s = 48.dp(parent.context)
                layoutParams = ViewGroup.LayoutParams(s, s)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val p = 4.dp(parent.context)
                setPadding(p, p, p, p)
            }
            return VH(iv)
        }

        override fun getItemCount() = filtered.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (name, drawable) = filtered[position]
            holder.iv.setImageDrawable(drawable)
            holder.iv.setOnClickListener { onPick(name, drawable) }
        }

        private fun Int.dp(ctx: Context) = (this * ctx.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun Int.dp(ctx: Context) = (this * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
