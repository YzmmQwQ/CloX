package com.ailife.clox.overlay.widgets.picker

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
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

class ActivityPickerDialog : BottomSheetDialogFragment() {

    var onActivitySelected: ((CellSlotConfig.ActivityShortcut) -> Unit)? = null

    private lateinit var recycler: RecyclerView
    private lateinit var tvTitle: TextView
    private lateinit var btnBack: View
    private var currentApps: List<ResolveInfo> = emptyList()
    private var showingActivities = false

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
        showingActivities = false
        tvTitle.setText(R.string.widget_pick_app)
        btnBack.visibility = View.GONE

        val pm = requireContext().packageManager
        val mainIntent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
        currentApps = apps

        recycler.adapter = object : RecyclerView.Adapter<PickerViewHolder>() {
            override fun getItemCount() = apps.size
            override fun onCreateViewHolder(parent: ViewGroup, vt: Int) =
                PickerViewHolder(LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_activity_picker, parent, false))
            override fun onBindViewHolder(h: PickerViewHolder, pos: Int) {
                val ri = apps[pos]
                h.icon.setImageDrawable(ri.loadIcon(pm))
                h.label.text = ri.loadLabel(pm)
                h.itemView.setOnClickListener {
                    showActivityList(ri.activityInfo.packageName)
                }
            }
        }
    }

    private fun showActivityList(packageName: String) {
        showingActivities = true
        val pm = requireContext().packageManager

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

        recycler.adapter = object : RecyclerView.Adapter<PickerViewHolder>() {
            override fun getItemCount() = activities.size
            override fun onCreateViewHolder(parent: ViewGroup, vt: Int) =
                PickerViewHolder(LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_activity_picker, parent, false))
            override fun onBindViewHolder(h: PickerViewHolder, pos: Int) {
                val ai = activities[pos]
                h.icon.setImageDrawable(try { ai.loadIcon(pm) } catch (_: Exception) {
                    pm.getApplicationIcon(packageName)
                })
                h.label.text = ai.loadLabel(pm).ifEmpty { ai.name.substringAfterLast('.') }
                h.itemView.setOnClickListener {
                    onActivitySelected?.invoke(
                        CellSlotConfig.ActivityShortcut(
                            packageName = packageName,
                            activityName = ai.name,
                            label = h.label.text.toString()
                        )
                    )
                    dismiss()
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
