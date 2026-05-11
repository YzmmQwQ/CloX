package com.ailife.clox.applist.backends

import android.content.Context
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ailife.clox.R
import com.ailife.clox.applist.AppListBackend
import com.ailife.clox.applist.model.AppItem

class ListBackend : AppListBackend {

    override val itemLayoutRes: Int = R.layout.item_app_list

    override fun createLayoutManager(context: Context): RecyclerView.LayoutManager =
        LinearLayoutManager(context)

    override fun bindView(holder: RecyclerView.ViewHolder, item: AppItem) {
        holder.itemView.findViewById<ImageView>(R.id.appIcon).setImageDrawable(item.icon)
        holder.itemView.findViewById<TextView>(R.id.appLabel).text = item.label
    }
}
