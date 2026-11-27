package com.michael.netlock

import android.graphics.drawable.Drawable

/**
 * 一条规则的真实作用单位是 UID, 不是包名。
 * 系统应用经常多个包共用一个 UID (如 uid 1000 = android.uid.system),
 * 所以这里以 UID 为行, 把共用它的包收进 packages。
 */
data class AppEntry(
    val uid: Int,
    val label: String,
    val packages: List<String>,
    val isSystem: Boolean,
    val icon: Drawable?,
) {
    val shared: Boolean get() = packages.size > 1
    val primaryPkg: String get() = packages.first()
}
