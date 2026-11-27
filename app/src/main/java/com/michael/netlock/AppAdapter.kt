package com.michael.netlock

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView

class AppAdapter(
    private val ctx: Context,
    private val store: RuleStore,
    private val onToggle: (AppEntry, Iface, Boolean) -> Unit,
) : BaseAdapter() {

    private val items = ArrayList<AppEntry>()

    var batchMode = false
        set(value) {
            field = value
            if (!value) selected.clear()
            notifyDataSetChanged()
        }

    val selected = LinkedHashSet<Int>()

    fun toggleSelect(uid: Int) {
        if (!selected.remove(uid)) selected.add(uid)
        notifyDataSetChanged()
    }

    fun selectAll(uids: Collection<Int>) {
        selected.clear()
        selected.addAll(uids)
        notifyDataSetChanged()
    }

    fun submit(list: List<AppEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): AppEntry = items[position]
    override fun getItemId(position: Int): Long = items[position].uid.toLong()

    private class Holder(v: View) {
        val icon: ImageView = v.findViewById(R.id.ivIcon)
        val label: TextView = v.findViewById(R.id.tvLabel)
        val pkg: TextView = v.findViewById(R.id.tvPkg)
        val wifi: CheckBox = v.findViewById(R.id.cbWifi)
        val mob: CheckBox = v.findViewById(R.id.cbMob)
        val vpn: CheckBox = v.findViewById(R.id.cbVpn)
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(ctx).inflate(R.layout.item_app, parent, false)
        val h = (v.tag as? Holder) ?: Holder(v).also { v.tag = it }
        val e = items[position]

        val picked = batchMode && selected.contains(e.uid)
        v.setBackgroundColor(if (picked) 0x339EF3F3 else 0x00000000)
        for (cb in arrayOf(h.wifi, h.mob, h.vpn)) {
            cb.isEnabled = !batchMode
            cb.alpha = if (batchMode) 0.35f else 1f
        }

        h.icon.setImageDrawable(e.icon)
        h.label.text = e.label
        h.pkg.text = buildString {
            if (e.shared) {
                if (e.hintPkg != null) {
                    append(e.hintPkg).append(" · UID ").append(e.uid)
                        .append(" · 共 ").append(e.packages.size).append(" 个包")
                } else {
                    append(e.packages.size).append(" 个应用共用 UID ").append(e.uid).append(" · 只能整组")
                }
            } else {
                append(e.primaryPkg).append("  uid=").append(e.uid)
            }
            if (e.isSystem) append("  · 系统")
        }

        bind(h.wifi, Iface.WIFI, e)
        bind(h.mob, Iface.MOBILE, e)
        bind(h.vpn, Iface.VPN, e)
        return v
    }

    private fun bind(cb: CheckBox, iface: Iface, e: AppEntry) {
        cb.setOnCheckedChangeListener(null)
        cb.isChecked = !store.isDenied(iface, e.uid)
        cb.setOnCheckedChangeListener { _, checked -> onToggle(e, iface, checked) }
    }
}