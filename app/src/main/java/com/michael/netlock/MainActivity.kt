package com.michael.netlock

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var store: RuleStore
    private lateinit var adapter: AppAdapter
    private lateinit var tvStatus: TextView
    private lateinit var tvFooter: TextView
    private lateinit var etSearch: EditText
    private lateinit var spType: Spinner
    private lateinit var spLimit: Spinner
    private lateinit var btnApply: Button
    private lateinit var toggle: ToggleButton

    private val all = ArrayList<AppEntry>()
    private val io = Executors.newSingleThreadExecutor()
    private var dirty = false
    private var rootOk = false

    private val typeFilters = arrayOf("全部类型", "用户应用", "系统应用")
    private val limitFilters = arrayOf("全部状态", "已限制", "未限制")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyEdgeToEdge()

        store = RuleStore(this)
        tvStatus = findViewById(R.id.tvStatus)
        tvFooter = findViewById(R.id.tvFooter)
        etSearch = findViewById(R.id.etSearch)
        spType = findViewById(R.id.spType)
        spLimit = findViewById(R.id.spLimit)
        btnApply = findViewById(R.id.btnApply)
        toggle = findViewById(R.id.btnToggle)

        adapter = AppAdapter(this, store) {
            dirty = true
            updateStatus()
        }
        findViewById<ListView>(R.id.lv).adapter = adapter

        val onSelect = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        spType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, typeFilters)
        spType.onItemSelectedListener = onSelect
        spLimit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, limitFilters)
        spLimit.onItemSelectedListener = onSelect

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refresh()
        })

        toggle.isChecked = store.enabled
        toggle.setOnCheckedChangeListener { _, checked ->
            store.enabled = checked
            dirty = true
            updateStatus()
        }

        btnApply.setOnClickListener { applyRules() }

        tvFooter.text = "勾选 = 允许联网；改动后点“应用规则”生效"
        updateStatus()
        bootstrap()
    }

    /** targetSdk 36 强制全屏贴边, 这里自己把状态栏/导航栏的 inset 补回来。 */
    private fun applyEdgeToEdge() {
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val root = findViewById<View>(R.id.root)
        val titleBar = findViewById<View>(R.id.titleBar)
        val padTop = titleBar.paddingTop

        window.decorView.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            titleBar.setPadding(
                titleBar.paddingLeft, padTop + bars.top, titleBar.paddingRight, titleBar.paddingBottom
            )
            root.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    private fun bootstrap() {
        io.execute {
            rootOk = RootShell.isRootAvailable(this)
            val pm = packageManager
            val infos = pm.getInstalledApplications(0)
            val uidCount = HashMap<Int, Int>()
            for (ai in infos) uidCount[ai.uid] = (uidCount[ai.uid] ?: 0) + 1
            val list = infos.map { ai ->
                AppEntry(
                    pkg = ai.packageName,
                    label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName),
                    uid = ai.uid,
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    icon = runCatching { ai.loadIcon(pm) }.getOrNull(),
                    sharedUid = (uidCount[ai.uid] ?: 0) > 1,
                )
            }.sortedWith(compareBy({ it.isSystem }, { it.label.lowercase() }))
            runOnUiThread {
                all.clear()
                all.addAll(list)
                refresh()
                updateStatus()
            }
        }
    }

    private fun refresh() {
        val q = etSearch.text.toString().trim().lowercase()
        val type = spType.selectedItemPosition
        val limit = spLimit.selectedItemPosition
        val out = all.filter { e ->
            val hitQ = q.isEmpty() || e.label.lowercase().contains(q) ||
                e.pkg.lowercase().contains(q) || e.uid.toString() == q
            val hitType = when (type) {
                1 -> !e.isSystem
                2 -> e.isSystem
                else -> true
            }
            val hitLimit = when (limit) {
                1 -> Iface.entries.any { store.isDenied(it, e.uid) }
                2 -> Iface.entries.none { store.isDenied(it, e.uid) }
                else -> true
            }
            hitQ && hitType && hitLimit
        }
        adapter.submit(out)
        tvFooter.text = "显示 " + out.size + " / " + all.size + " 个应用 | 已拦截 " + store.deniedCount() + " 条"
    }

    private fun updateStatus() {
        val root = if (rootOk) "root 正常" else "root 不可用"
        val fw = if (store.enabled) "防火墙开启" else "防火墙关闭"
        val mark = if (dirty) "  * 未应用" else ""
        tvStatus.text = root + " | " + fw + " | 拦截 " + store.deniedCount() + mark
    }

    private fun applyRules() {
        if (!rootOk) {
            Toast.makeText(this, "root 不可用, 无法下发规则", Toast.LENGTH_LONG).show()
            return
        }
        if (store.enabled && store.deniedCount() == 0) {
            Toast.makeText(this, "还没有任何拦截规则", Toast.LENGTH_SHORT).show()
        }
        btnApply.isEnabled = false
        io.execute {
            val r = Firewall.apply(this, store)
            runOnUiThread {
                btnApply.isEnabled = true
                if (r.ok) dirty = false
                updateStatus()
                refresh()
                showResult(r)
            }
        }
    }

    private fun showResult(r: RootShell.Result) {
        val body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            text = "exit=" + r.code + "\n\n" + r.output.ifBlank { "(无输出)" }
        }
        val sv = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle(if (r.ok) "规则已下发" else "执行失败")
            .setView(sv)
            .setPositiveButton("好", null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, if (store.autoBoot) "开机自启: 开" else "开机自启: 关")
        menu.add(0, 2, 1, "查看规则脚本")
        menu.add(0, 3, 2, "清空所有规则")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> {
                store.autoBoot = !store.autoBoot
                dirty = true
                Toast.makeText(this, if (store.autoBoot) "已开启开机自启, 应用后生效" else "已关闭开机自启, 应用后生效", Toast.LENGTH_SHORT).show()
                invalidateOptionsMenu()
            }
            2 -> {
                val script = if (store.enabled) Firewall.buildRules(store) else Firewall.buildFlush()
                showResult(RootShell.Result(0, script))
            }
            3 -> AlertDialog.Builder(this)
                .setTitle("清空所有规则")
                .setMessage("将清除全部应用的拦截设置, 下次应用规则后所有应用恢复联网。")
                .setPositiveButton("清空") { _, _ ->
                    store.clearDenials()
                    dirty = true
                    updateStatus()
                    refresh()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        return super.onOptionsItemSelected(item)
    }
}
