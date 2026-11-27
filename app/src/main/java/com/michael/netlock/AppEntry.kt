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
    /** 搜索命中的那个包, 用来在行里显示具体包名 */
    val hintPkg: String? = null,
) {
    val shared: Boolean get() = packages.size > 1
    val primaryPkg: String get() = packages.first()
}
