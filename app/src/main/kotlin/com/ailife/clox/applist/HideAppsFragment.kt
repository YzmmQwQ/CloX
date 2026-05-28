package com.ailife.clox.applist

import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.icon.IconResolver
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.content.Intent
import android.widget.LinearLayout

class HideAppsFragment : Fragment() {

    private data class AppEntry(
        val packageName: String,
        val activityName: String,
        val label: String,
        val icon: Bitmap,   // pre-rendered at icon size — cheap to bind during scroll
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
                entry.selected = !entry.selected
                holder.check.isChecked = entry.selected
                save()
            }
        }

        fun selectAll() { items.forEach { it.selected = true }; notifyDataSetChanged(); save() }
        fun deselectAll() { items.forEach { it.selected = false }; notifyDataSetChanged(); save() }

        private fun save() {
            val ctx = requireContext()
            val selected = items.filter { it.selected }
                .map { "${it.packageName}/${it.activityName}" }.toSet()
            AppListPrefs.saveHiddenComponents(ctx, selected)
        }
    }

    private var adapter: Adapter? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_hide_apps, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        val rv = view.findViewById<RecyclerView>(R.id.rvApps)
        rv.layoutManager = LinearLayoutManager(ctx)

        // Mode chips
        val chipBlacklist = view.findViewById<Chip>(R.id.chipBlacklist)
        val chipWhitelist = view.findViewById<Chip>(R.id.chipWhitelist)
        val isWhitelist = AppListPrefs.hideMode(ctx) == AppListPrefs.HIDE_MODE_WHITELIST
        chipWhitelist.isChecked = isWhitelist
        chipBlacklist.isChecked = !isWhitelist

        fun saveMode() {
            val mode = if (chipWhitelist.isChecked) AppListPrefs.HIDE_MODE_WHITELIST
                       else AppListPrefs.HIDE_MODE_BLACKLIST
            AppListPrefs.get(ctx).edit().putString(AppListPrefs.KEY_HIDE_MODE, mode).apply()
        }
        chipBlacklist.setOnClickListener { saveMode() }
        chipWhitelist.setOnClickListener { saveMode() }

        // Buttons
        view.findViewById<View>(R.id.btnSelectAll).setOnClickListener { adapter?.selectAll() }
        view.findViewById<View>(R.id.btnDeselectAll).setOnClickListener { adapter?.deselectAll() }

        // Secret code lock switch
        val switchLock = view.findViewById<MaterialSwitch>(R.id.switchLock)
        val tvCodeHint = view.findViewById<TextView>(R.id.tvCodeHint)
        val currentPassword = AppListPrefs.hideAppsPassword(ctx)
        switchLock.isChecked = currentPassword != null
        if (currentPassword != null) {
            tvCodeHint.visibility = View.VISIBLE
            tvCodeHint.text = getString(R.string.secret_code_dial_hint, currentPassword)
        }

        switchLock.setOnCheckedChangeListener { _, checked ->
            if (checked) promptSetPassword(tvCodeHint)
            else {
                AppListPrefs.saveHideAppsPassword(ctx, null)
                tvCodeHint.visibility = View.GONE
            }
        }

        // Load apps on background thread
        Thread {
            val pm = ctx.packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val hidden = AppListPrefs.hiddenComponents(ctx)
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
                        selected     = key in hidden
                    )
                }.toMutableList()

            Handler(Looper.getMainLooper()).post {
                if (!isAdded) return@post
                val adp = Adapter(entries)
                adapter = adp
                rv.adapter = adp
            }
        }.start()
    }

    private fun promptSetPassword(tvCodeHint: TextView) {
        val ctx = requireContext()

        // Step 1: explain mechanism
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_secret_code_title)
            .setMessage(R.string.dialog_secret_code_message)
            .setPositiveButton(R.string.dialog_secret_code_positive) { _, _ -> promptPasswordInput(tvCodeHint) }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                view?.findViewById<MaterialSwitch>(R.id.switchLock)?.isChecked = false
            }
            .setCancelable(false)
            .show()
    }

    private fun promptPasswordInput(tvCodeHint: TextView) {
        val ctx = requireContext()
        val inputLayout = LayoutInflater.from(ctx)
            .inflate(R.layout.dialog_password_input, null) as TextInputLayout
        val editText = inputLayout.editText!!

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.dialog_enter_password_title)
            .setMessage(R.string.dialog_enter_password_message)
            .setView(inputLayout)
            .setPositiveButton(R.string.btn_confirm) { _, _ ->
                val pw = editText.text?.toString()?.trim() ?: ""
                if (pw.length < 4 || !pw.all { it.isDigit() }) {
                    promptPasswordInput(tvCodeHint)  // re-prompt on invalid
                    return@setPositiveButton
                }
                AppListPrefs.saveHideAppsPassword(ctx, pw)
                tvCodeHint.text = getString(R.string.secret_code_dial_hint, pw)
                tvCodeHint.visibility = View.VISIBLE
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                view?.findViewById<MaterialSwitch>(R.id.switchLock)?.isChecked = false
            }
            .setCancelable(false)
            .show()
    }
}

private fun Drawable.renderToBitmap(size: Int): Bitmap {
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bmp
}
