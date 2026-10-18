package com.yzmm.clox.overlay.widgets.picker

import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yzmm.clox.R
import com.yzmm.clox.icon.renderToBitmap
import com.yzmm.clox.overlay.widgets.model.CellSlotConfig
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShortcutPickerDialog : BottomSheetDialogFragment() {

    var onShortcutSelected: ((CellSlotConfig.PinnedShortcut) -> Unit)? = null

    private lateinit var recycler: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: View

    private lateinit var la: LauncherApps
    private var byPackage: Map<String, List<ShortcutInfo>> = emptyMap()

    private data class AppRow(val pkg: String, val label: String, val icon: Bitmap)
    private data class ShortcutRow(val info: ShortcutInfo, val label: String, val icon: Bitmap)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_activity_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.activityPickerList)
        tvTitle  = view.findViewById(R.id.activityPickerTitle)
        btnBack  = view.findViewById(R.id.activityPickerBack)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        la = requireContext().getSystemService(LauncherApps::class.java)

        byPackage = try {
            val query = ShortcutQuery().setQueryFlags(
                ShortcutQuery.FLAG_MATCH_MANIFEST or
                ShortcutQuery.FLAG_MATCH_DYNAMIC   or
                ShortcutQuery.FLAG_MATCH_PINNED
            )
            la.getShortcuts(query, Process.myUserHandle())
                ?.groupBy { it.`package` }
                ?: emptyMap()
        } catch (_: Exception) { emptyMap() }

        showAppList()
    }

    private fun showAppList() {
        tvTitle.setText(R.string.widget_slot_shortcut)
        btnBack.visibility = View.GONE

        if (byPackage.isEmpty()) {
            tvTitle.setText(R.string.widget_shortcut_empty)
            return
        }

        val pm = requireContext().packageManager
        val iconSizePx = (36 * resources.displayMetrics.density + 0.5f).toInt()
        val apps = byPackage.keys.sortedBy {
            try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (_: Exception) { it }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                apps.map { pkg ->
                    val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                    val icon = (try { pm.getApplicationIcon(pkg) } catch (_: Exception) { pm.defaultActivityIcon })
                        .renderToBitmap(iconSizePx)
                    AppRow(pkg, label, icon)
                }
            }
            if (!isAdded) return@launch

            recycler.adapter = object : RecyclerView.Adapter<PickerVH>() {
                override fun getItemCount() = rows.size
                override fun onCreateViewHolder(p: ViewGroup, vt: Int) = PickerVH(
                    LayoutInflater.from(p.context).inflate(R.layout.item_activity_picker, p, false)
                )
                override fun onBindViewHolder(h: PickerVH, pos: Int) {
                    h.label.text = rows[pos].label
                    h.icon.setImageBitmap(rows[pos].icon)
                    h.itemView.setOnClickListener { showShortcutsFor(rows[pos].pkg) }
                }
            }
        }
    }

    private fun showShortcutsFor(pkg: String) {
        val pm = requireContext().packageManager
        val iconSizePx = (36 * resources.displayMetrics.density + 0.5f).toInt()
        val densityDpi = resources.displayMetrics.densityDpi

        val appLabel = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        tvTitle.text = appLabel
        btnBack.visibility = View.VISIBLE
        btnBack.setOnClickListener { showAppList() }

        val shortcuts = byPackage[pkg] ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                shortcuts.map { info ->
                    val label = (info.shortLabel ?: info.longLabel ?: info.id).toString()
                    val drawable = try { la.getShortcutIconDrawable(info, densityDpi) }
                                   catch (_: Exception) { null }
                        ?: try { pm.getApplicationIcon(pkg) } catch (_: Exception) { pm.defaultActivityIcon }
                    ShortcutRow(info, label, drawable.renderToBitmap(iconSizePx))
                }
            }
            if (!isAdded) return@launch

            recycler.adapter = object : RecyclerView.Adapter<PickerVH>() {
                override fun getItemCount() = rows.size
                override fun onCreateViewHolder(p: ViewGroup, vt: Int) = PickerVH(
                    LayoutInflater.from(p.context).inflate(R.layout.item_activity_picker, p, false)
                )
                override fun onBindViewHolder(h: PickerVH, pos: Int) {
                    h.label.text = rows[pos].label
                    h.icon.setImageBitmap(rows[pos].icon)
                    h.itemView.setOnClickListener { onPick(rows[pos].info) }
                }
            }
        }
    }

    private fun onPick(info: ShortcutInfo) {
        val label = (info.shortLabel ?: info.longLabel ?: info.id).toString()

        if (!info.isPinned) {
            try {
                val alreadyPinned = la.getShortcuts(
                    ShortcutQuery().setQueryFlags(ShortcutQuery.FLAG_MATCH_PINNED).setPackage(info.`package`),
                    Process.myUserHandle()
                )?.map { it.id } ?: emptyList()
                la.pinShortcuts(info.`package`, alreadyPinned + info.id, Process.myUserHandle())
            } catch (_: Exception) {}
        }

        PinnedShortcutPrefs.addShortcut(
            requireContext(),
            AcceptedShortcut(info.`package`, info.id, label)
        )

        onShortcutSelected?.invoke(
            CellSlotConfig.PinnedShortcut(info.`package`, info.id, label)
        )
        dismiss()
    }

    class PickerVH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView  = view.findViewById(R.id.pickerIcon)
        val label: TextView  = view.findViewById(R.id.pickerLabel)
    }

    companion object { const val TAG = "shortcut_picker" }
}
