package com.ailife.clox.applist.model

import android.graphics.drawable.Drawable

data class AppItem(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable,
    val isHidden: Boolean = false
)
