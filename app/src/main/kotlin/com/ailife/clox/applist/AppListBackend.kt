package com.ailife.clox.applist

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.applist.model.AppItem

interface AppListBackend {
    val itemLayoutRes: Int
    fun createLayoutManager(context: Context): RecyclerView.LayoutManager
    fun bindView(holder: RecyclerView.ViewHolder, item: AppItem)
}
