package com.michael.netlock

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
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
    private lateinit var lv: ListView
    private lateinit var batchBar: View
    private lateinit var tvBatch: TextView
    private lateinit var btnBatchAll: Button

    private val all = ArrayList<AppEntry>()
    private val shown = ArrayList<AppEntry>()
    private val labels = HashMap<String, String>()
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
        lv = findViewById(R.id.lv)

        batchBar = findViewById(R.id.batchBar)
        tvBatch = findViewById(R.id.tvBatch)
        btnBatchAll = findViewById(R.id.btnBatchAll)
        btnBatchAll.setOnClickListener { toggleSelectAll() }
        findViewById<Button>(R.id.btnBatchDeny).setOnClickListener { batchApply(true) }
        findViewById<Button>(R.id.btnBatchAllow).setOnClickListener { batchApply(false) }
        findViewById<Button>(R.id.btnBatchExit).setOnClickListener { exitBatch() }

        adapter = AppAdapter(this, store, ::onToggle)
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ ->
            if (adapter.batchMode) {
                adapter.toggleSelect(adapter.getItem(pos).uid)
                updateBatchBar()
            } else {
                showMembers(adapter.getItem(pos))
            }
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            if (adapter.batchMode) {
                false
            } else {
                adapter.batchMode = true
                adapter.toggleSelect(adapter.getItem(pos).uid)
                updateBatchBar()
                true
            }
        }

        val onSelect = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) = refresh()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
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

            val launcherPkgs = HashSet<String>()
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            runCatching {
                for (ri in pm.queryIntentActivities(intent, 0)) {
                    launcherPkgs.add(ri.activityInfo.packageName)
                }
            }

            val infos = pm.getInstalledApplications(0)
            for (ai in infos) {
                labels[ai.packageName] =
                    runCatching { ai.loadLabel(pm).toString() }.getOrDefault(ai.packageName)
            }
            val list = infos.groupBy { it.uid }.map { (uid, apps) ->
                val primary = apps.firstOrNull { launcherPkgs.contains(it.packageName) } ?: apps.first()
                AppEntry(
                    uid = uid,
                    label = runCatching { primary.loadLabel(pm).toString() }
                        .getOrDefault(primary.packageName),
                    packages = apps.map { it.packageName }.sorted(),
                    isSystem = apps.all { (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0 },
                    icon = runCatching { primary.loadIcon(pm) }.getOrNull(),
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
        val out = all.mapNotNull { e ->
            val hitQ = q.isEmpty() || e.label.lowercase().contains(q) ||
                e.uid.toString() == q || e.packages.any { it.lowercase().contains(q) }
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
            if (!(hitQ && hitType && hitLimit)) {
                null
            } else if (q.isNotEmpty() && e.shared) {
                // 搜到的是组内某个包时, 用它当标题, 免得搜 olc 却显示"设置"
                val hit = e.packages.firstOrNull { it.lowercase().contains(q) }
                if (hit != null) e.copy(label = labels[hit] ?: hit) else e
            } else {
                e
            }
        }
        shown.clear()
        shown.addAll(out)
        adapter.submit(shown)
        val pkgSum = out.sumOf { it.packages.size }
        tvFooter.text = "显示 " + out.size + " 个 UID (含 " + pkgSum + " 个应用) / 共 " +
            all.size + " 个 UID | 已限制 " + store.deniedCount() + " 个 | 长按多选"
    }

    private fun updateStatus() {
        val root = if (rootOk) "root 正常" else "root 不可用"
        val fw = if (store.enabled) "防火墙开启" else "防火墙关闭"
        val mark = if (dirty) "  * 未应用" else ""
        tvStatus.text = root + " | " + fw + " | 限制 " + store.deniedCount() + " 个 UID" + mark
    }

    private fun onToggle(e: AppEntry, iface: Iface, allow: Boolean) {
        if (!allow && e.shared) {
            confirmSharedUid(e, iface)
        } else {
            store.setDenied(iface, e.uid, !allow)
            afterChange()
        }
    }

    private fun afterChange() {
        dirty = true
        refresh()
        updateStatus()
    }

    /** UID 是拦截的真实单位, 共用 UID 时一次会带走多个应用, 先讲清楚。 */
    private fun confirmSharedUid(e: AppEntry, iface: Iface) {
        val head = TextView(this).apply {
            textSize = 13f
            setPadding(dp(20), dp(16), dp(20), dp(6))
            text = buildString {
                append("系统按 UID 拦截, UID ").append(e.uid).append(" 被 ")
                append(e.packages.size).append(" 个应用共用。关掉会同时断开下面全部应用:")
                if (e.uid < 10000) {
                    append("\n\n这是系统级共享 UID, 可能影响系统设置、电话、短信等基础功能。")
                }
            }
        }
        val body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            text = e.packages.joinToString("\n")
        }
        val sv = ScrollView(this).apply { addView(body) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(head)
            addView(sv)
        }
        AlertDialog.Builder(this)
            .setTitle("同时影响 " + e.packages.size + " 个应用")
            .setView(box)
            .setPositiveButton("仍然拦截") { _, _ ->
                store.setDenied(iface, e.uid, true)
                afterChange()
            }
            .setNegativeButton("取消") { _, _ -> refresh() }
            .setOnCancelListener { refresh() }
            .show()
    }

    private fun showMembers(e: AppEntry) {
        val body = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            text = "UID " + e.uid + "\n\n" + e.packages.joinToString("\n")
        }
        val sv = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this)
            .setTitle(e.label + " · " + e.packages.size + " 个应用")
            .setView(sv)
            .setPositiveButton("好", null)
            .show()
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
                Toast.makeText(
                    this,
                    if (store.autoBoot) "已开启开机自启, 应用后生效" else "已关闭开机自启, 应用后生效",
                    Toast.LENGTH_SHORT
                ).show()
                invalidateOptionsMenu()
            }
            2 -> {
                val script = if (store.enabled) Firewall.buildRules(store) else Firewall.buildFlush()
                showResult(RootShell.Result(0, script))
            }
            3 -> AlertDialog.Builder(this)
                .setTitle("清空所有规则")
                .setMessage("将清除全部拦截设置, 应用规则后所有应用恢复联网。")
                .setPositiveButton("清空") { _, _ ->
                    store.clearDenials()
                    afterChange()
                }
                .setNegativeButton("取消", null)
                .show()
        }
        return super.onOptionsItemSelected(item)
    }

    private fun exitBatch() {
        adapter.batchMode = false
        batchBar.visibility = View.GONE
    }

    private fun updateBatchBar() {
        if (!adapter.batchMode) {
            batchBar.visibility = View.GONE
            return
        }
        batchBar.visibility = View.VISIBLE
        tvBatch.text = "已选 " + adapter.selected.size + " / " + shown.size
        val allPicked = shown.isNotEmpty() && shown.all { adapter.selected.contains(it.uid) }
        btnBatchAll.text = if (allPicked) "全不选" else "全选"
    }

    /** 全选 / 全不选当前筛选结果。 */
    private fun toggleSelectAll() {
        val allPicked = shown.isNotEmpty() && shown.all { adapter.selected.contains(it.uid) }
        adapter.selectAll(if (allPicked) emptyList() else shown.map { it.uid })
        updateBatchBar()
    }

    /** 批量限制 / 解除, 三个维度分别勾选。 */
    private fun batchApply(deny: Boolean) {
        val uids = adapter.selected.toList()
        if (uids.isEmpty()) {
            Toast.makeText(this, "还没有选中任何应用", Toast.LENGTH_SHORT).show()
            return
        }
        val pkgCount = all.filter { uids.contains(it.uid) }.sumOf { it.packages.size }
        val labels = arrayOf("无线网络", "移动数据", "VPN 隧道")
        val cbs = labels.map {
            CheckBox(this).apply {
                text = it
                isChecked = true
                setPadding(dp(16), dp(6), dp(16), dp(6))
            }
        }
        val tip = TextView(this).apply {
            textSize = 12f
            setPadding(dp(20), dp(14), dp(20), dp(4))
            text = "影响 " + uids.size + " 个 UID, 共 " + pkgCount + " 个应用包"
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tip)
            cbs.forEach { addView(it) }
        }
        AlertDialog.Builder(this)
            .setTitle(if (deny) "批量限制联网" else "批量解除限制")
            .setView(wrap)
            .setPositiveButton("确定") { _, _ ->
                val picked = Iface.entries.filterIndexed { idx, _ -> cbs[idx].isChecked }
                if (picked.isEmpty()) {
                    Toast.makeText(this, "至少选择一个维度", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                for (uid in uids) {
                    for (i in picked) store.setDenied(i, uid, deny)
                }
                afterChange()
                exitBatch()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (adapter.batchMode) exitBatch() else super.onBackPressed()
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()
}
