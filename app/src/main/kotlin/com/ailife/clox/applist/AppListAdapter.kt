package com.ailife.clox.applist

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.applist.model.AppItem

class AppListAdapter(
    private val backend: AppListBackend
) : ListAdapter<AppItem, AppListAdapter.AppViewHolder>(DIFF) {

    inner class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        init {
            view.setOnClickListener {
                val item = getItem(bindingAdapterPosition)
                val pm = view.context.packageManager
                pm.getLaunchIntentForPackage(item.packageName)?.let { intent ->
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    view.context.startActivity(intent)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(backend.itemLayoutRes, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        backend.bindView(holder, getItem(position))
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppItem>() {
            override fun areItemsTheSame(a: AppItem, b: AppItem) =
                a.packageName == b.packageName
            override fun areContentsTheSame(a: AppItem, b: AppItem) =
                a.label == b.label && a.isHidden == b.isHidden
        }
    }
}
