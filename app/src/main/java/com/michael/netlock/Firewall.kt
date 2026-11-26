package com.michael.netlock

import android.content.Context
import java.io.File

/**
 * 规则模型:
 *   OUTPUT -> NL_OUT
 *   NL_OUT -o wlan+            -> NL_WIFI   (无线网络)
 *   NL_OUT -o rmnet+/ccmni+    -> NL_MOB    (移动数据)
 *   NL_OUT -o tun+/ppp+/wg+    -> NL_VPN    (VPN 隧道)
 * 每个子链里按 uid 命中就 REJECT, 因此同一份规则可以同时覆盖
 * wifi / 流量 / vpn 三个维度, 且接口出现或消失时自动生效。
 */
object Firewall {

    private const val NET_DIR = "/data/adb/netlock"
    private const val BOOT_SCRIPT = "/data/adb/service.d/99-netlock.sh"

    private val CHAINS = listOf("NL_OUT", "NL_WIFI", "NL_MOB", "NL_VPN")

    private val IFACE_JUMPS = listOf(
        "wlan+" to "NL_WIFI",
        "rmnet+" to "NL_MOB",
        "ccmni+" to "NL_MOB",
        "tun+" to "NL_VPN",
        "ppp+" to "NL_VPN",
        "wg+" to "NL_VPN",
    )

    fun buildRules(store: RuleStore): String {
        val sb = StringBuilder()
        sb.append("#!/system/bin/sh\n")
        sb.append("# 由网络锁自动生成\n")
        for (bin in listOf("iptables", "ip6tables")) {
            for (c in CHAINS) sb.append(bin).append(" -w -N ").append(c).append(" 2>/dev/null\n")
            for (c in CHAINS) sb.append(bin).append(" -w -F ").append(c).append(" 2>/dev/null\n")
            for ((iface, chain) in IFACE_JUMPS) {
                sb.append(bin).append(" -w -A NL_OUT -o ").append(iface)
                    .append(" -j ").append(chain).append("\n")
            }
            sb.append(bin).append(" -w -C OUTPUT -j NL_OUT 2>/dev/null || ")
                .append(bin).append(" -w -I OUTPUT 1 -j NL_OUT\n")
        }
        emit(sb, "NL_WIFI", store.deniedUids(Iface.WIFI))
        emit(sb, "NL_MOB", store.deniedUids(Iface.MOBILE))
        emit(sb, "NL_VPN", store.deniedUids(Iface.VPN))
        return sb.toString()
    }

    private fun emit(sb: StringBuilder, chain: String, uids: Set<Int>) {
        for (u in uids.sorted()) {
            sb.append("iptables -w -A ").append(chain)
                .append(" -m owner --uid-owner ").append(u).append(" -j REJECT\n")
            sb.append("ip6tables -w -A ").append(chain)
                .append(" -m owner --uid-owner ").append(u).append(" -j REJECT\n")
        }
    }

    fun buildFlush(): String {
        val sb = StringBuilder("#!/system/bin/sh\n")
        for (bin in listOf("iptables", "ip6tables")) {
            sb.append("while ").append(bin)
                .append(" -w -D OUTPUT -j NL_OUT 2>/dev/null; do :; done\n")
            for (c in CHAINS) {
                sb.append(bin).append(" -w -F ").append(c).append(" 2>/dev/null\n")
                sb.append(bin).append(" -w -X ").append(c).append(" 2>/dev/null\n")
            }
        }
        sb.append("echo FLUSHED\n")
        return sb.toString()
    }

    private fun bootScript(): String = """
        #!/system/bin/sh
        # 由网络锁自动生成, 开机时重新下发规则
        sleep 8
        [ -x $NET_DIR/rules.sh ] && sh $NET_DIR/rules.sh
    """.trimIndent() + "\n"

    /** 下发规则, 并按需安装开机自启脚本。返回 root 命令的输出。 */
    fun apply(ctx: Context, store: RuleStore): RootShell.Result {
        val rules = if (store.enabled) buildRules(store) else buildFlush()
        val rulesFile = File(ctx.cacheDir, "rules.sh").apply { writeText(rules) }
        val bootFile = File(ctx.cacheDir, "boot.sh").apply { writeText(bootScript()) }

        val sb = StringBuilder("#!/system/bin/sh\n")
        sb.append("sh ").append(rulesFile.absolutePath).append("\nRC=\$?\n")
        if (store.enabled && store.autoBoot) {
            sb.append("mkdir -p ").append(NET_DIR).append(" /data/adb/service.d\n")
            sb.append("cp ").append(rulesFile.absolutePath).append(" ").append(NET_DIR).append("/rules.sh\n")
            sb.append("chmod 700 ").append(NET_DIR).append("/rules.sh\n")
            sb.append("cp ").append(bootFile.absolutePath).append(" ").append(BOOT_SCRIPT).append("\n")
            sb.append("chmod 700 ").append(BOOT_SCRIPT).append("\n")
            sb.append("echo 开机自启已安装\n")
        } else {
            sb.append("rm -f ").append(BOOT_SCRIPT).append(" ").append(NET_DIR).append("/rules.sh\n")
            sb.append("echo 开机自启已移除\n")
        }
        sb.append("echo ----\n")
        sb.append("iptables -w -L NL_WIFI -n 2>/dev/null | tail -n +2 | wc -l\n")
        sb.append("exit \$RC\n")

        return RootShell.run(ctx, sb.toString())
    }
}
