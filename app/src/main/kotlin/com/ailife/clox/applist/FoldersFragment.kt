package com.ailife.clox.applist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Settings screen listing the user's folders. Tap a folder to edit its apps; long-press for
 * rename / delete. Folders are created from the app-list long-press menu ("New folder").
 */
class FoldersFragment : Fragment() {

    private var adapter: Adapter? = null

    private inner class Adapter(val folders: MutableList<AppListPrefs.Folder>) :
        RecyclerView.Adapter<Adapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.folderIcon)
            val name: TextView = view.findViewById(R.id.folderName)
            val count: TextView = view.findViewById(R.id.folderCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false))

        override fun getItemCount() = folders.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val f = folders[position]
            val ctx = requireContext()
            val sizePx = (44 * ctx.resources.displayMetrics.density).toInt()
            holder.icon.setImageDrawable(
                FolderIconFactory.build(ctx, f.members, sizePx, FolderIconFactory.Shape.ROUNDED_RECT))
            holder.name.text = f.name
            holder.count.text = getString(R.string.folder_member_count, f.members.size)
            holder.itemView.setOnClickListener { openMembers(f.id) }
            holder.itemView.setOnLongClickListener { showFolderOptions(f); true }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_folders, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<RecyclerView>(R.id.rvFolders).layoutManager = LinearLayoutManager(requireContext())
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()   // reflect member edits made in the child picker
    }

    private fun reload() {
        val v = view ?: return
        val folders = AppListPrefs.folders(requireContext())
        val adp = Adapter(folders)
        adapter = adp
        v.findViewById<RecyclerView>(R.id.rvFolders).adapter = adp
        v.findViewById<TextView>(R.id.tvFoldersEmpty).visibility =
            if (folders.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openMembers(folderId: String) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.settingsContainer, FolderMembersFragment.newInstance(folderId))
            .addToBackStack(null)
            .commit()
    }

    private fun showFolderOptions(folder: AppListPrefs.Folder) {
        val ctx = requireContext()
        val options = arrayOf(
            getString(R.string.menu_edit_members),
            getString(R.string.menu_rename_folder),
            getString(R.string.menu_delete_folder)
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(folder.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openMembers(folder.id)
                    1 -> promptRename(folder)
                    2 -> confirmDelete(folder)
                }
            }
            .show()
    }

    private fun promptRename(folder: AppListPrefs.Folder) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(folder.name)
            setSelection(text.length)
        }
        val pad = (20 * ctx.resources.displayMetrics.density).toInt()
        val holder = android.widget.FrameLayout(ctx).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.folder_rename_title)
            .setView(holder)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) { AppListPrefs.renameFolder(ctx, folder.id, name); reload() }
            }
            .show()
    }

    private fun confirmDelete(folder: AppListPrefs.Folder) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.folder_delete_title)
            .setMessage(getString(R.string.folder_delete_message, folder.name))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.menu_delete_folder) { _, _ ->
                AppListPrefs.deleteFolder(ctx, folder.id); reload()
            }
            .show()
    }
}
