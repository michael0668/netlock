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
    private val onChanged: () -> Unit,
) : BaseAdapter() {

    private val items = ArrayList<AppEntry>()

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

        h.icon.setImageDrawable(e.icon)
        h.label.text = e.label
        h.pkg.text = buildString {
            append(e.pkg)
            append("  uid=").append(e.uid)
            if (e.isSystem) append("  · 系统")
            if (e.sharedUid) append("  · 共享 UID")
        }

        bind(h.wifi, Iface.WIFI, e.uid)
        bind(h.mob, Iface.MOBILE, e.uid)
        bind(h.vpn, Iface.VPN, e.uid)
        return v
    }

    private fun bind(cb: CheckBox, iface: Iface, uid: Int) {
        cb.setOnCheckedChangeListener(null)
        cb.isChecked = !store.isDenied(iface, uid)
        cb.setOnCheckedChangeListener { _, checked ->
            store.setDenied(iface, uid, !checked)
            onChanged()
        }
    }
}
