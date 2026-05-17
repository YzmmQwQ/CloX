package com.ailife.clox.overlay.widgets.picker

import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.os.Process
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.overlay.widgets.model.CellSlotConfig
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ShortcutPickerDialog : BottomSheetDialogFragment() {

    var onShortcutSelected: ((CellSlotConfig.PinnedShortcut) -> Unit)? = null

    private lateinit var recycler: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: View

    private lateinit var la: LauncherApps
    // Package → list of shortcuts for that package
    private var byPackage: Map<String, List<ShortcutInfo>> = emptyMap()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_activity_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.activityPickerList)
        tvTitle  = view.findViewById(R.id.activityPickerTitle)
        btnBack  = view.findViewById(R.id.activityPickerBack)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        la = requireContext().getSystemService(LauncherApps::class.java)

        // Query static + dynamic + pinned shortcuts from all apps
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

    // ── Level 1: apps that have shortcuts ────────────────────────────────────

    private fun showAppList() {
        tvTitle.setText(R.string.widget_slot_shortcut)
        btnBack.visibility = View.GONE

        if (byPackage.isEmpty()) {
            tvTitle.setText(R.string.widget_shortcut_empty)
            return
        }

        val pm = requireContext().packageManager
        val apps = byPackage.keys.sortedBy {
            try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (_: Exception) { it }
        }

        recycler.adapter = object : RecyclerView.Adapter<PickerVH>() {
            override fun getItemCount() = apps.size
            override fun onCreateViewHolder(p: ViewGroup, vt: Int) = PickerVH(
                LayoutInflater.from(p.context).inflate(R.layout.item_activity_picker, p, false)
            )
            override fun onBindViewHolder(h: PickerVH, pos: Int) {
                val pkg = apps[pos]
                h.label.text = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)) } catch (_: Exception) { pkg }
                h.icon.setImageDrawable(try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null })
                h.itemView.setOnClickListener { showShortcutsFor(pkg) }
            }
        }
    }

    // ── Level 2: shortcuts for a specific app ────────────────────────────────

    private fun showShortcutsFor(pkg: String) {
        val pm = requireContext().packageManager
        val appLabel = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
        tvTitle.text  = appLabel
        btnBack.visibility = View.VISIBLE
        btnBack.setOnClickListener { showAppList() }

        val shortcuts = byPackage[pkg] ?: return

        recycler.adapter = object : RecyclerView.Adapter<PickerVH>() {
            override fun getItemCount() = shortcuts.size
            override fun onCreateViewHolder(p: ViewGroup, vt: Int) = PickerVH(
                LayoutInflater.from(p.context).inflate(R.layout.item_activity_picker, p, false)
            )
            override fun onBindViewHolder(h: PickerVH, pos: Int) {
                val info = shortcuts[pos]
                h.label.text = (info.shortLabel ?: info.longLabel ?: info.id).toString()
                h.icon.setImageDrawable(
                    try { la.getShortcutIconDrawable(info, resources.displayMetrics.densityDpi) }
                    catch (_: Exception) { try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null } }
                )
                h.itemView.setOnClickListener { onPick(info) }
            }
        }
    }

    // ── On shortcut selected ──────────────────────────────────────────────────

    private fun onPick(info: ShortcutInfo) {
        val label = (info.shortLabel ?: info.longLabel ?: info.id).toString()

        // Pin the shortcut so it persists even if the app removes it dynamically
        if (!info.isPinned) {
            try {
                val alreadyPinned = la.getShortcuts(
                    ShortcutQuery().setQueryFlags(ShortcutQuery.FLAG_MATCH_PINNED).setPackage(info.`package`),
                    Process.myUserHandle()
                )?.map { it.id } ?: emptyList()
                la.pinShortcuts(info.`package`, alreadyPinned + info.id, Process.myUserHandle())
            } catch (_: Exception) {}
        }

        // Store in prefs so the "pinned" section always lists it
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
