package com.michael.netlock

import android.graphics.drawable.Drawable

data class AppEntry(
    val pkg: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
    val icon: Drawable?,
    val sharedUid: Boolean,
)
