package com.ailife.clox.applist

import android.content.Context
import android.view.View
import com.ailife.clox.applist.model.AppItem

interface AppListBackend {
    fun createContentView(context: Context): View
    fun bindItems(items: List<AppItem>)
    var onAppClick: ((AppItem, View) -> Unit)?
    var onAppLongPress: ((AppItem, View) -> Unit)?
    var onRequestClose: (() -> Unit)?   // Pages only: fire when swipe past first page
    /** Called when user finishes a drag-reorder with the newly ordered visible items. */
    var onOrderChanged: ((List<AppItem>) -> Unit)?
    fun setDragEnabled(enabled: Boolean) {}
    fun onPrefsChanged(context: Context) {}
    fun refreshBadges(counts: Map<String, Int>) {}
}
