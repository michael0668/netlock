package com.michael.netlock

import android.content.Context

enum class Iface(val key: String) {
    WIFI("deny_wifi"),
    MOBILE("deny_mobile"),
    VPN("deny_vpn"),
}

class RuleStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("netlock", Context.MODE_PRIVATE)

    private val cache = HashMap<Iface, MutableSet<Int>>()

    init {
        for (i in Iface.entries) {
            val raw = sp.getStringSet(i.key, emptySet()) ?: emptySet()
            cache[i] = raw.mapNotNull { it.toIntOrNull() }.toMutableSet()
        }
    }

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    var autoBoot: Boolean
        get() = sp.getBoolean("auto_boot", true)
        set(v) = sp.edit().putBoolean("auto_boot", v).apply()

    fun deniedUids(i: Iface): Set<Int> = cache[i] ?: emptySet()

    fun isDenied(i: Iface, uid: Int): Boolean = cache[i]?.contains(uid) == true

    fun setDenied(i: Iface, uid: Int, denied: Boolean) {
        val s = cache[i] ?: return
        if (denied) s.add(uid) else s.remove(uid)
        sp.edit().putStringSet(i.key, s.map { it.toString() }.toSet()).apply()
    }

    fun deniedCount(): Int = Iface.entries.sumOf { cache[it]?.size ?: 0 }

    fun clearDenials() {
        for (i in Iface.entries) {
            cache[i]?.clear()
            sp.edit().remove(i.key).apply()
        }
    }
}
