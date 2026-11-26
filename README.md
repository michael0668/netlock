# 网络锁 NetLock

基于 root (KernelSU / Magisk) 的 Android 应用联网控制工具。按应用维度分别控制
**无线网络 / 移动数据 / VPN** 三种出口, 系统应用同样可控, 不需要常驻 VPN 服务。

已验证环境: OnePlus 15T (PLZ110), Android 17 / SDK 37, arm64, KernelSU root。

## 实现原理

per-UID 网络策略在 root 下最直接的做法是 iptables / ip6tables 的 `owner` 匹配。核心结构:

```
OUTPUT ──> NL_OUT
             ├─ -o wlan+           -> NL_WIFI   (无线)
             ├─ -o rmnet+ / ccmni+ -> NL_MOB    (移动数据)
             └─ -o tun+ / ppp+ / wg+ -> NL_VPN  (VPN 隧道)

NL_WIFI / NL_MOB / NL_VPN 内部:
    -m owner --uid-owner <uid> -j REJECT
```

三个子链各自维护一份被拦截的 UID 列表, 入口链只按出口接口分流。好处:

- 一份规则同时覆盖 IPv4 与 IPv6 (iptables + ip6tables 各一套);
- 规则是接口驱动的, 移动数据开关、WiFi 断连重连都自动生效, 不需要常驻前台服务;
- 只插入一条 `OUTPUT -j NL_OUT` 跳转, 不干扰 netd 自己的 `fw_*` / `oem_*` 链;
- 拦截 DNS 一并生效, 应用的 DNS 查询同样是该 UID 的 socket。

用 `REJECT` 而不是 `DROP`, 应用立刻收到不可达错误, 不会卡在超时上。

## 边界与已知限制

- **VPN 必须单独授权**。开 VPN 时应用流量从 `tun*` 出去, 不再经过 `wlan*` / `rmnet*`,
  只勾"无线/流量"对走 VPN 的应用无效, 需要取消"VPN"列的勾选。
- Android UID = `userId * 100000 + appId`, 分身/多用户应用有独立 UID, PackageManager
  给出的 `uid` 已是当前用户口径。
- 共享 UID 的应用会联动, 列表里标注 `共享 UID`。
- 厂商私有加速通道 (如 `vgate0`) 不在拦截范围内。
- 只拦出站流量。

## 开机自启

iptables 规则不持久, "应用规则"时会把同一份脚本写到:

```
/data/adb/netlock/rules.sh
/data/adb/service.d/99-netlock.sh
```

后者由 KernelSU 在 late_start 阶段执行。另有 `BootReceiver` 兜底。右上角菜单可关闭。

## 使用

1. 首次打开请求 root, 在 KernelSU 里允许;
2. 列表勾选 = **允许**, 取消 = 拦截, 三列分别是 无线 / 流量 / VPN;
3. 改完点右上角"应用规则";
4. 顶部"已启用/已停用"是总闸, 停用后应用规则会清空全部规则。

搜索框支持应用名、包名、UID。筛选器拆成两个独立维度, 可叠加:

| 类型 | 全部类型 / 用户应用 / 系统应用 |
| 状态 | 全部状态 / 已限制 / 未限制 |

## 校验与救急

```sh
adb shell su -c 'iptables -L NL_WIFI -n -v; iptables -L NL_OUT -n -v'
adb shell su -c 'while iptables -D OUTPUT -j NL_OUT 2>/dev/null; do :; done; iptables -F NL_OUT'
```

## 构建

```sh
export http_proxy=http://\$(ip route show default | awk '{print \$3}'):10808
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

技术栈刻意最小: Kotlin + 纯 framework 控件, 零第三方依赖 (不引 AppCompat / RecyclerView),
498 个应用用 ListView 复用足够。targetSdk 36 强制 edge-to-edge, 界面自己处理 WindowInsets
(见 `MainActivity.applyEdgeToEdge`), 否则内容会被状态栏和标题栏盖住。
