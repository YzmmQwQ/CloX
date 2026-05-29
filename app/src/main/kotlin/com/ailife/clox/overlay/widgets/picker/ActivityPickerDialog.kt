package com.ailife.clox.overlay.widgets.picker

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.icon.renderToBitmap
import com.ailife.clox.overlay.widgets.model.CellSlotConfig
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ActivityPickerDialog : BottomSheetDialogFragment() {

    var onActivitySelected: ((CellSlotConfig.ActivityShortcut) -> Unit)? = null

    private lateinit var recycler: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: View

    private data class AppRow(val info: ApplicationInfo, val label: String, val icon: Bitmap)
    private data class ActivityRow(val info: ActivityInfo, val label: String, val icon: Bitmap)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_activity_picker, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recycler = view.findViewById(R.id.activityPickerList)
        tvTitle  = view.findViewById(R.id.activityPickerTitle)
        btnBack  = view.findViewById(R.id.activityPickerBack)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        btnBack.setOnClickListener { showAppList() }

        showAppList()
    }

    private fun showAppList() {
        tvTitle.setText(R.string.widget_pick_app)
        btnBack.visibility = View.GONE

        val pm = requireContext().packageManager
        val iconSizePx = (36 * resources.displayMetrics.density + 0.5f).toInt()

        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .sortedBy { it.loadLabel(pm).toString().lowercase() }
                    .map { ai ->
                        val icon = (try { ai.loadIcon(pm) } catch (_: Exception) { pm.defaultActivityIcon })
                            .renderToBitmap(iconSizePx)
                        AppRow(ai, ai.loadLabel(pm).toString(), icon)
                    }
            }
            if (!isAdded) return@launch

            recycler.adapter = object : RecyclerView.Adapter<PickerViewHolder>() {
                override fun getItemCount() = rows.size
                override fun onCreateViewHolder(parent: ViewGroup, vt: Int) =
                    PickerViewHolder(LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_activity_picker, parent, false))
                override fun onBindViewHolder(h: PickerViewHolder, pos: Int) {
                    h.icon.setImageBitmap(rows[pos].icon)
                    h.label.text = rows[pos].label
                    h.itemView.setOnClickListener { showActivityList(rows[pos].info.packageName) }
                }
            }
        }
    }

    private fun showActivityList(packageName: String) {
        val pm = requireContext().packageManager
        val iconSizePx = (36 * resources.displayMetrics.density + 0.5f).toInt()

        val appLabel = try {
            pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
        } catch (_: PackageManager.NameNotFoundException) { packageName }

        tvTitle.text = appLabel
        btnBack.visibility = View.VISIBLE

        val activities: List<ActivityInfo> = try {
            val pi = pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            (pi.activities ?: emptyArray()).filter { it.exported }.toList()
        } catch (_: PackageManager.NameNotFoundException) {
            emptyList()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                activities.map { ai ->
                    val icon = (try { ai.loadIcon(pm) } catch (_: Exception) {
                        try { pm.getApplicationIcon(packageName) } catch (_: Exception) { pm.defaultActivityIcon }
                    }).renderToBitmap(iconSizePx)
                    val label = ai.loadLabel(pm).ifEmpty { ai.name.substringAfterLast('.') }.toString()
                    ActivityRow(ai, label, icon)
                }
            }
            if (!isAdded) return@launch

            recycler.adapter = object : RecyclerView.Adapter<PickerViewHolder>() {
                override fun getItemCount() = rows.size
                override fun onCreateViewHolder(parent: ViewGroup, vt: Int) =
                    PickerViewHolder(LayoutInflater.from(parent.context)
                        .inflate(R.layout.item_activity_picker, parent, false))
                override fun onBindViewHolder(h: PickerViewHolder, pos: Int) {
                    h.icon.setImageBitmap(rows[pos].icon)
                    h.label.text = rows[pos].label
                    h.itemView.setOnClickListener {
                        onActivitySelected?.invoke(
                            CellSlotConfig.ActivityShortcut(
                                packageName  = packageName,
                                activityName = rows[pos].info.name,
                                label        = rows[pos].label
                            )
                        )
                        dismiss()
                    }
                }
            }
        }
    }

    class PickerViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.pickerIcon)
        val label: TextView = view.findViewById(R.id.pickerLabel)
    }

    companion object {
        const val TAG = "activity_picker"
    }
}
