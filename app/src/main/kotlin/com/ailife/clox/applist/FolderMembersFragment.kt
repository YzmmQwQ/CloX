package com.ailife.clox.applist

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.icon.IconResolver
import com.ailife.clox.icon.renderToBitmap

/**
 * Multi-select picker for a folder's member apps (max [AppListPrefs.MAX_FOLDER_MEMBERS]).
 * Mirrors [HideAppsFragment]; selection saves immediately via [AppListPrefs.setFolderMembers].
 */
class FolderMembersFragment : Fragment() {

    private val folderId: String get() = arguments?.getString(ARG_FOLDER_ID).orEmpty()

    private data class AppEntry(
        val packageName: String,
        val activityName: String,
        val label: String,
        val icon: Bitmap,
        var selected: Boolean
    )

    private inner class Adapter(private val items: MutableList<AppEntry>) :
        RecyclerView.Adapter<Adapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.hideAppIcon)
            val label: TextView = view.findViewById(R.id.hideAppLabel)
            val activity: TextView = view.findViewById(R.id.hideAppActivity)
            val check: CheckBox = view.findViewById(R.id.hideAppCheck)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_hide_app, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val entry = items[position]
            holder.icon.setImageBitmap(entry.icon)
            holder.label.text = entry.label
            holder.activity.text = entry.activityName.substringAfterLast('.')
            holder.check.isChecked = entry.selected
            holder.itemView.setOnClickListener {
                if (!entry.selected && selectedCount() >= AppListPrefs.MAX_FOLDER_MEMBERS) {
                    Toast.makeText(requireContext(), R.string.folder_full, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                entry.selected = !entry.selected
                holder.check.isChecked = entry.selected
                save()
                updateHint()
            }
        }

        fun selectedCount() = items.count { it.selected }

        fun save() {
            val keys = items.filter { it.selected }.map { "${it.packageName}/${it.activityName}" }
            AppListPrefs.setFolderMembers(requireContext(), folderId, keys)
        }
    }

    private var adapter: Adapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_folder_members, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val rv = view.findViewById<RecyclerView>(R.id.rvFolderApps)
        rv.layoutManager = LinearLayoutManager(ctx)
        updateHint()

        Thread {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val members = AppListPrefs.folders(ctx).firstOrNull { it.id == folderId }?.members?.toSet() ?: emptySet()
            val iconSizePx = (36 * ctx.resources.displayMetrics.density + 0.5f).toInt()
            val entries = pm.queryIntentActivities(intent, 0)
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .map { ri ->
                    val key = "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
                    AppEntry(
                        packageName  = ri.activityInfo.packageName,
                        activityName = ri.activityInfo.name,
                        label        = ri.loadLabel(pm).toString(),
                        icon         = IconResolver.resolve(ctx, ri.activityInfo.packageName, ri.activityInfo.name)
                                           .renderToBitmap(iconSizePx),
                        selected     = key in members
                    )
                }.toMutableList()

            Handler(Looper.getMainLooper()).post {
                if (!isAdded) return@post
                val adp = Adapter(entries)
                adapter = adp
                rv.adapter = adp
                updateHint()
            }
        }.start()
    }

    private fun updateHint() {
        val count = adapter?.selectedCount() ?: 0
        view?.findViewById<TextView>(R.id.tvFolderMembersHint)?.text =
            getString(R.string.folder_member_count, count) + " / ${AppListPrefs.MAX_FOLDER_MEMBERS}"
    }

    companion object {
        private const val ARG_FOLDER_ID = "folder_id"
        fun newInstance(folderId: String) = FolderMembersFragment().apply {
            arguments = Bundle().apply { putString(ARG_FOLDER_ID, folderId) }
        }
    }
}
