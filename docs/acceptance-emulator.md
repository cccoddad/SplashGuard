# 模拟器验收报告（Android）

> 执行时间：2026-11-04
> 对象：`com.splashguard.app` v0.1.0（release，自签）
> 结论：**P0 全部通过，P1 全部通过**（其中 4 个缺陷为本轮发现并已修复回归）。
> 本报告只记录模拟器上真实跑出来的结果；模拟器无法覆盖的项在 §5 单列，留给真机。

---

## 1. 结论摘要

| 等级 | 项 | 结果 |
|---|---|---|
| P0 | 安装/启动/无崩溃 | ✅ 通过 |
| P0 | VPN 隧道建立、DNS 真实进入隧道、非 DNS 流量不进隧道 | ✅ 通过（含流量计数证据） |
| P0 | 规则命中/未命中/通配/大小写/热重载 | ✅ 通过 |
| P0 | 分应用隔离 | ✅ 通过（修复死锁后） |
| P1 | 无障碍点击与防误点 | ✅ 通过（5 个场景全覆盖） |
| P1 | 开机自启 | ✅ 通过（授权存在时）；⚠️ 授权被系统撤销时改为通知提醒 |
| P2 | 稳定性（20 次开关 / 后台驻留） | ✅ 通过 |
| P2 | 兼容性（minSdk 26 / targetSdk 34 / 深色 / 分辨率） | ✅ 通过 |

**本轮共发现 4 个缺陷，全部已修复并回归通过**：

| # | 等级 | 缺陷 | 状态 |
|---|---|---|---|
| 1 | **P0** | 改分应用配置触发隧道重启后，DNS 全断（等同断网） | 已修复 |
| 2 | **P1** | 分应用首次选择逻辑死锁，功能完全不可用 | 已修复 |
| 3 | **P1** | 重启后系统撤销 VPN 授权，开机自启静默失败无任何提示 | 已修复（改为通知引导） |
| 4 | **P2** | 拦截发生时首页「今日拦截」不实时刷新，要切后台再回来 | 已修复 |

---

## 2. 环境与测试矩阵

### 2.1 宿主与加速

| 项 | 值 |
|---|---|
| 机器 | HP ProBook 455 15.6 inch G10 |
| CPU / 内存 | AMD Ryzen 7 7730U（8 核）/ 31.3 GB |
| 硬件加速 | **WHPX 10.0.26200 可用**（`emulator -accel-check` → `accel: 0`） |
| 模拟器版本 | Android emulator 37.2.13.0 |
| JDK | Adoptium 17.0.18 / 21.0.11 |
| Gradle | 8.14.3（本地 wrapper 缓存，无需下载） |

> AMD 平台走 WHPX；`HypervisorPlatform` 处于启用状态，因此没有装 AEHD。若换机器且 WHPX 不可用，
> 退路依次是：`Microsoft-Hyper-V` + WHPX、AEHD 驱动、最后才是 `-accel off`（极慢，不建议）。

### 2.2 系统镜像（官方 sdkmanager，未用第三方镜像）

| 包名 | 用途 | 大小 | 下载 |
|---|---|---|---|
| `system-images;android-34;google_apis;x86_64` | 对齐 targetSdk 34 | 4.2 GB | 是（约 3 分钟） |
| `system-images;android-26;google_apis;x86_64` | 对齐 minSdk 26 | ~1.1 GB | 是 |
| `emulator` | 模拟器本体 | ~500 MB | 是 |

均选 **google_apis**（非 playstore），便于 `adb root`、免账号、可安装测试 App。

### 2.3 AVD

| AVD | 镜像 | 设备 | 分辨率 / dpi | 内存 / 核 |
|---|---|---|---|---|
| `sg_api34` | android-34 google_apis x86_64 | Pixel 6 | 1080×2400 / 420 | 2048 MB / 4 |
| `sg_api26` | android-26 google_apis x86_64 | Pixel 2 | 1080×1920 / 420 | 2048 MB / 4 |

启动命令（无窗口，`screencap` 依然可用）：

```bash
emulator -avd sg_api34 -no-window -no-audio -no-boot-anim -no-snapshot \
         -gpu swiftshader_indirect -camera-back none -camera-front none
```

### 2.4 环境侧的两个坑（不是 App 缺陷，但会误导结论）

1. **宿主走 HTTP 代理**：`http_proxy=http://127.0.0.1:63939`。模拟器 slirp 不走代理，
   一开始 `ping 公网 IP` 全是 `Network is unreachable`，容易被误判成 App 把网搞坏了。
2. **该镜像的 WiFi 网络没有默认路由**：`wlan0` 有 IP（10.0.2.16）但路由表为空，
   必须 `svc wifi disable` 让流量走 `eth0`（cellular，10.0.2.15，有 `0.0.0.0/0`）才通。
   处理前后对比：
   ```
   处理前: ip route get 202.108.22.5 → RTNETLINK answers: Network is unreachable
   处理后: ip route get 202.108.22.5 → ... via 10.0.2.2 dev eth0 table eth0
   ```
3. 该网络 **ICMP 被屏蔽**，`ping` 只能用来验证「域名解析结果」，不能验证连通性。

---

## 3. 验收明细（A–H）

### A. 安装与启动

```bash
adb install -r app-debug/release/app-release.apk        # → Success
adb shell am start -W -n com.splashguard.app/.ui.MainActivity
# Status: ok / TotalTime: 3729ms（API34 首次）/ 2254ms（API26）
adb shell dumpsys activity activities | grep topResumedActivity
# topResumedActivity=ActivityRecord{... com.splashguard.app/.ui.MainActivity}
adb logcat -d | grep -E "FATAL EXCEPTION|ANR in com.splashguard"   # 0 条
```

- 首屏渲染正常，中文无乱码，无错位：`docs/evidence/A1-first-launch.png`- **首次开启引导**：先弹系统 VPN 授权 `Connection request`（`B1-vpn-consent.png`），
  API 33+ 再弹 `POST_NOTIFICATIONS` 权限（`A2-notif-permission.png`）。两个弹窗都能正常走完。
- 判定：**通过**

### B. VPN 与流量路径

| 检查点 | 命令 / 证据 | 结果 |
|---|---|---|
| 授权弹窗 | `input tap` 后 dump 到 `Connection request / Cancel / OK` | ✅ 弹出并可授权 |
| 前台服务 | `dumpsys activity services` → `isForeground=true types=40000000` | ✅ |
| tun 建立 | `dumpsys connectivity` 出现 `InterfaceName: tun0` | ✅ |
| DNS 确实进隧道 | 拦截日志出现域名 + 上游转发日志 | ✅ |
| 非 DNS 不走隧道 | 见下方流量计数 | ✅ |

**路由表（关键证据）**——只有 DNS 相关的 /32，没有 `0.0.0.0/0`：

```
InterfaceName: tun0 LinkAddresses: [ 10.111.222.1/32 ] DnsAddresses: [ /10.111.222.2 ]
Routes: [ 10.111.222.2/32, 8.8.8.8/32, 8.8.4.4/32, 1.1.1.1/32, 1.0.0.1/32, 9.9.9.9/32,
          114.114.114.114/32, 223.5.5.5/32, 223.6.6.6/32, 119.29.29.29/32,
          ::/0 unreachable, 10.111.222.1/32 ]   ← 全部是 tun0 上的 DNS 地址
```

**流量计数（打开一个网页前后）**：

```
tun0  rx 增量: 91 bytes        ← 一条 DNS 查询
eth0  rx 增量: 162759 bytes    ← 网页本身
```

→ 非 DNS 流量确实没有进隧道，`::/0 unreachable` 也确认 IPv6 被显式阻断（避免泄漏）。

> 首次测试时上游转发报 `sendto failed: ENETUNREACH`，排查后确认是 §2.4 的环境路由问题，
> 与 App 无关；网络恢复后同一份 APK 转发正常（未命中域名能解析出真实 IP）。

### C. 规则

| 用例 | 输入 | 期望 | 实测 |
|---|---|---|---|
| 精确命中 | `gdt.qq.com` | 拦截 | `127.0.0.1` ✅ |
| 子域命中 | `a.b.pglstatp-toutiao.com` | 拦截 | `127.0.0.1` ✅ |
| 父域不误杀 | `toutiao.com` | 放行 | 真实 IP `122.14.229.38` ✅ |
| 大小写 | `GDT.QQ.COM` | 拦截 | `127.0.0.1` ✅ |
| 未命中 | `www.163.com` | 放行 | 真实 IP ✅ |
| 自定义规则热重载 | 规则页加 `bing.com` 并保存 | 立即生效 | 保存后 `www.bing.com` → `127.0.0.1` ✅（规则数 54→55，未重启 App） |
| 拦截模式切换 | NXDOMAIN | 返回 NXDOMAIN | `ping: unknown host` ✅ |
| 模式切回 | 0.0.0.0 | 返回 A 记录 | 新域名 → `127.0.0.1` ✅ |

两个需要说明的观察：

1. **`0.0.0.0` 在 Android 上会显示成 `127.0.0.1`**。这是系统解析层把 0.0.0.0 当本机回环处理的结果，
   实际效果是「连接立即失败」，比 NXDOMAIN 更快失败，对开屏场景反而是好事。已在文档中说明，不算缺陷。
2. **DNS 负缓存**：切 NXDOMAIN 再切回后，同一个域名在 TTL 内仍然是 `unknown host`。
   换一个没查过的域名验证即正常 —— 这是 Android resolver 的行为，不是规则没生效。

### D. 分应用

修复后的完整链路：

```
全部应用模式勾选 Calendar → 勾选框保持选中（旧版会拒绝）
切到「仅选中的应用」    → 成功
dumpsys connectivity    → Uids: <{10148-10148, 20148-20148}>   ← 只剩 Calendar
adb shell ping gdt.qq.com → 113.108.27.43（真实 IP）            ← shell 不在名单，绕过过滤
切回「全部应用」         → gdt.qq.com → 127.0.0.1                ← 拦截恢复
```

- 判定：**通过**（死锁修复后）
- 日志证据：`stopped, gen=2 keepFg=true` → `startVpn enter attempt=0` → `tunnel up, gen=3`

### E. 无障碍（用自建假开屏 App 验证）

测试 App：`splashtest/`（`com.splashguard.splashtest`），5 种界面，点击写 `FakeSplash:CLICKED:<variant>`。

| 场景 | 界面 | 期望 | 实测 |
|---|---|---|---|
| 标准跳过 | 右上角小按钮「跳过」(60×28dp) | 点击 | ✅ `SplashGuardA11y: clicked skip` |
| 带倒计时 | 小按钮「跳过 3」 | 点击 | ✅ 点击 |
| 大按钮 | 全宽「跳过广告」(1080×200dp) | 不点 | ✅ 未点击（尺寸守卫生效） |
| 文案不符 | 小按钮「关闭」 | 不点 | ✅ 未点击（不在白名单） |
| 整块广告 | 全屏可点「广告位」 | 不点 | ✅ 未点击 |
| 关闭服务后 | 同上「跳过」 | 不点 | ✅ 解绑后 0 次点击 |

- 判定：**通过**。防误点规则确实挡住了「大控件」和「非跳过文案」两类误伤。

### F. 开机自启

```bash
# 授权存在时，手动投递开机广播（等价于真实开机后的回调）
adb shell appops set com.splashguard.app ACTIVATE_VPN allow
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.splashguard.app
# → SplashGuardVpn: onStartCommand action=...START → tunnel up, gen=1
# → dumpsys connectivity: tun0 存在；gdt.qq.com → 127.0.0.1
```

- **接收器逻辑本身正确**：只要授权在，开机就能自动拉起隧道。
- **但发现一个真实问题**：在这台 API 34 镜像上，重启后 `ACTIVATE_VPN` 会从 `allow` 变回 `ignore`，
  于是 `VpnService.prepare()` 重新要求授权，旧版本直接 `return` 静默退出 —— 用户以为还开着，其实已经停了。
- 已修复：这种情况改为发一条通知「拦截没有自动恢复 / 点此重新开启」，点通知直接回首页。
  验证：`dumpsys notification` 中可见 `channel=splashguard_alert`、`contentIntent=...MainActivity`。
- 判定：**通过（含改进）**。真机是否也会丢授权，见 §5。

### G. 稳定性

**连续开关 20 次**（UI 点击驱动）：

```
START 日志 20 条 / STOP 日志 20 条
pid 全程未变（3947）
FATAL EXCEPTION / ANR = 0
reader "died unexpectedly" / "after retries" = 0
```

**后台驻留 10 分钟**（关屏，不交互）：

```
起点 15:11:54  TOTAL PSS 41325   RSS 163660
终点 15:21:13  TOTAL PSS 40590   RSS 163148     ← 无增长（略降）
崩溃/ANR 0 条 | tun0 存活 | 被拦域名仍 → 127.0.0.1 | 普通域名正常解析
Activities: 1（MainActivity 保留），AppContexts: 7（无泄漏迹象）
```

**网络切换**（飞行模式开 → 关）：

```
开飞行模式后: tun0 仍在；日志 1 条 forward ENETUNREACH（无网络，属预期）
关飞行模式后 25s: tun0 正常；gdt.qq.com → 127.0.0.1；www.163.com → 真实 IP
全程 0 崩溃，无需手动重启拦截
```

> 说明：脚本在第二次点击后 1.2s 抓界面时偶尔拍到「运行中」，是因为状态文本刷新有延迟，
> 日志里 START/STOP 各 20 条已经证明每次开关都真实生效。

**钥匙图标**：状态栏出现系统 VPN 钥匙图标（`B2-vpn-key-icon.png`），`VpnTransportInfo{sessionId=SplashGuard}`。

### H. 兼容性

| 维度 | 组合 | 结果 |
|---|---|---|
| Android 版本 | API 26（minSdk）/ API 34（targetSdk） | 均安装、启动、拦截正常 |
| 分辨率 | 1080×2400 / 1080×1920 | 布局正常，无遮挡 |
| 深色模式 | `cmd uimode night yes` | 深底浅字，无对比度问题（`H1-dark-mode.png`） |
| 中文界面 | 全流程 | 文案完整，无截断 |

---

## 4. 本轮修复的 4 个缺陷

### 4.1【P0】隧道重启后 DNS 黑洞

**复现**：开隧道 → 设置页改上游 DNS 保存（旧逻辑会 `restart`）→ 全系统 DNS 直接不可解析，等于断网。
**根因**：`ACTION_RESTART` 里 `stopVpn()` 后立刻 `establish()`，旧 VPN 会话还没被系统回收，新隧道建不起来；
而旧 `tun` 已关闭、无人读，DNS 报文只进不出 → 黑洞。
**修复**：
1. 引入 `generation` 代际号，旧读循环自行退出，避免新旧 reader 抢同一个 fd；
2. 拆旧隧道后延迟 350ms 再建新隧道，`establish()` 最多重试 3 次；
3. **看门狗**：读循环非预期退出时主动拆隧道，宁可「不拦」也不能「断网」；
4. 设置页保存不再重启隧道（拦截模式/上游 DNS/自定义规则本来就是每次请求实时读取的）。

**回归**：改配置后 `www.163.com` 正常解析、`gdt.qq.com` 仍被拦；切分应用模式日志显示
`stopped gen=2 → startVpn attempt=0 → tunnel up gen=3`。

### 4.2【P1】分应用选择死锁

**复现**：进分应用页 → 点「仅选中的应用」→ 提示「至少要勾选一个应用」并退回；
去勾选任意 App → 提示「请先切换到仅选中的应用」。**两条路互相堵死，功能等于不存在。**
**修复**：允许在「全部应用」模式下先勾选（存为待生效名单），并提示「已选 N 个应用，切到仅选中的应用后生效」；
只在「仅选中」模式下禁止取消最后一个。
**回归**：见 §3.D，全链路通过。

### 4.3【P1】开机授权丢失时静默失败

**复现**：重启设备 → 系统把 `ACTIVATE_VPN` 重置为 `ignore` → `BootReceiver` 静默 `return`，
用户界面上什么都看不到，以为还在拦截。
**修复**：改为发一条通知 `拦截没有自动恢复 / 点此重新开启拦截`，点击回首页重新授权。
**回归**：`appops set com.splashguard.app ACTIVATE_VPN ignore` + 投递 `BOOT_COMPLETED`
→ 日志 `ask user to re-authorize` → 通知出现在 `dumpsys notification`。

### 4.4【P2】计数不实时

**复现**：停在首页，用外部流量触发拦截 → 列表出现新记录，但「今日拦截」还是 0，切后台再回来才变。
**修复**：日志回调里同步刷新计数。
**回归**：前台停留，触发后 13 → 14，无需切后台。

---

## 5. 模拟器无法验证的项（留给真机）

| 项 | 为什么模拟器验不了 |
|---|---|
| 真实开屏广告覆盖率 | 模拟器没有国内 App，也没有真实广告 SDK；DNS 拦截率只能在真机按 App 逐个统计 |
| 耗电 | 模拟器没有真实电池模型；本项目只路由 DNS，理论上接近 0，但需真机 `Battery Historian` 对比 |
| 真实网络切换（WiFi ↔ 4G） | 模拟器的网络是 slirp 虚拟网卡，切换语义与真机不同 |
| 强杀/省电策略 | 各厂商 ROM 的后台限制差异大，模拟器不还原 |
| VPN 授权是否跨重启保留 | 本机 API 34 镜像会丢授权；真机（含国产 ROM）需实测 |
| 无障碍在真实 App 上的命中率 | 真实开屏多为自绘/游戏引擎，节点树结构差异大，需真机按 App 观察 |
| 硬件加速下的长时间稳定性 | 模拟器跑 10 分钟 ≠ 真机跑一天 |

---

## 6. 真机验收前的安全说明

**这个 App 不会让手机故障**，理由与恢复步骤：

- 只做两件事：① 建一个只路由 DNS `/32` 的本地 VPN；② 可选的读界面+点「跳过」按钮（默认关闭）。
- 不 root、不刷机、不改系统分区、不写 hosts、不动 SELinux。
- 所有权限都能在系统设置里单独关闭，卸载即彻底清除（无残留服务、无后台进程）。

**关闭拦截（保留 App）**

1. 打开 SplashGuard → 关掉首页开关（状态变「已停止」，钥匙图标消失）；
2. 或：设置 → 网络 → VPN → 断开 SplashGuard；
3. 若还想要更彻底：设置 → 无障碍 → SplashGuard 自动跳过 → 关闭。

**卸载恢复**

1. 先按上面关掉开关（推荐，避免卸载瞬间 DNS 短暂异常）；
2. 设置 → 应用 → SplashGuard → 卸载；
3. 若装过 iOS 描述文件：设置 → 通用 → VPN 与设备管理 → 移除 `SplashGuard DNS`；
4. 验证：`dumpsys connectivity | grep tun0` 应无输出，浏览器访问任意网站正常。

**建议**：真机验收先用备用机，别用主力机；重点观察「拦截期间其他 App 是否正常联网」这一项。

---

## 7. 附录：一键复现命令

```bash
# 构建
gradle assembleRelease          # → app/build/outputs/apk/release/app-release.apk
gradle :splashtest:assembleRelease   # → 假开屏测试 App

# 安装
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
adb -s emulator-5554 install -r splashtest/build/outputs/apk/release/splashtest-release.apk

# 拦截验证（命中/未命中）
adb shell ping -c1 gdt.qq.com          # → 127.0.0.1（拦截）
adb shell ping -c1 www.163.com         # → 真实 IP（放行）

# 分应用：改成只让 Calendar 走 VPN，再用 shell 验证绕过
adb shell appops get com.splashguard.app ACTIVATE_VPN

# 无障碍：按需开启/关闭
adb shell settings put secure enabled_accessibility_services \
  com.splashguard.app/com.splashguard.app.a11y.SkipAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell settings delete secure enabled_accessibility_services
adb shell settings put secure accessibility_enabled 0

# 开机自启
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p com.splashguard.app

# 流量分流证据
adb shell cat /sys/class/net/tun0/statistics/rx_bytes
adb shell cat /sys/class/net/eth0/statistics/rx_bytes
```

**证据索引**：`docs/evidence/` —— 首页/授权弹窗/深色模式截图、各页面 UI 树、后台内存快照。

---

## 8. v0.1.6 无障碍回归（2026-11-04，API 34 模拟器）

**背景**：真机反馈 0.1.5 更新后所有 App 都不跳过。定位到两条根因：
① 只监听 `TYPE_WINDOW_STATE_CHANGED` + 1.5 秒窗口，Android 16 / HyperOS 上经常触发不到；
② 覆盖安装后 `enabled_accessibility_services` 仍有值，但系统已解绑服务，界面显示「已授权」实际不运行。

**结果：9/9 通过。**

| 场景 | 期望 | 实测 |
|---|---|---|
| `skip` | 点中 | `CLICKED:skip`（约 0.5s） |
| `skipcount`（「跳过 3」） | 点中 | `CLICKED:skipcount` |
| `delay`（按钮 600ms 后出现） | 点中 | `CLICKED:delay` |
| `late`（按钮 2500ms 后出现） | 点中 | `CLICKED:late`（出现后约 180ms） |
| `deep`（按钮在 6 层容器里） | 点中 | `CLICKED:deep` |
| `shake`（「摇一摇」+「跳过」） | 只点跳过 | `CLICKED:shake`，未点摇一摇 |
| `huge`（全宽按钮） | 不点 | 无 `CLICKED` |
| `other`（「关闭」文案） | 不点 | 无 `CLICKED` |
| `ad`（整块广告） | 不点 | 无 `CLICKED` |

**命令**（同一测试包连续启动必须间隔 >6 秒，否则会被窗口状态机判为同一窗口）：

```bash
for v in skip skipcount delay late deep shake huge other ad; do
  adb logcat -c
  adb shell am force-stop com.splashguard.splashtest
  adb shell am start -n com.splashguard.splashtest/.FakeSplashActivity --es variant $v
  sleep 7.5
  adb logcat -d -s FakeSplash:V | grep "CLICKED:$v"
  adb shell am force-stop com.splashguard.splashtest
done
```

**其他验证**

- 覆盖安装 APK 后服务恢复绑定，首页显示「自动跳过 · 开」，`skip` 恢复点击。
- 一键隐藏：`accessibility_shortcut_enabled=0`、`accessibility_button_targets=null`，
  `enabled_accessibility_services` 仍包含本服务，`skip` 仍点中。
- 空闲 10 秒：App 进程 utime+stime 增量 = 0 ticks。
- 单元测试 40 项全绿（新增文案匹配 6 项、窗口状态机 7 项）。
- **真机（小米 13 Pro / Android 16）仍待安装 v0.1.6 后确认。**

---

## 9. v0.1.7 真机反馈回归（2026-11-04，API 34 模拟器）

**背景**：真机反馈 VPN 空闲自关、懂球帝不跳过但日志有拦截、摇一摇无效、扫描卡顿、无广告时进软件慢。

| 验证项 | 结果 |
|---|---|
| `tun.read()==0` 空闲 | VPN 开启后空闲保持 >60 秒不自动关闭 |
| 空闲 CPU（VPN+a11y 开启） | 10 秒 utime+stime 增量 6 ticks（v0.1.6 为 30） |
| 无障碍 10 场景 | `skip` / `skipcount` / `delay` / `late` / `deep` / `shake` 点中；`huge` / `other` / `ad` / `nonclick` 不点 |
| `nonclick`（文字不可点、父容器满屏） | 无 `CLICKED`，未点整块广告 |
| 扫描成本 | 55→37 次，平均 115→78ms，最近一次 2ms（模拟器） |
| 拦截应答缓存 | 直接发两次 `pangle.io` A 查询：`拦截应答: 2 / 缓存命中: 1`，第二次无新日志 |
| 转发缓冲 | 4096→65535 |
| 单测 | 40 项全绿 |

**复现命令**（10 场景，同一测试包间隔 >6 秒）：

```bash
for v in skip skipcount delay late deep nonclick shake huge other ad; do
  adb logcat -c
  adb shell am force-stop com.splashguard.splashtest
  adb shell am start -n com.splashguard.splashtest/.FakeSplashActivity --es variant $v
  sleep 7.5
  adb logcat -d -s FakeSplash:V | grep "CLICKED:"
  adb shell am force-stop com.splashguard.splashtest
done
```

**真机待验证**：小米 13 Pro / Android 16 上的 VPN 空闲自关、懂球帝扫描/点击、开屏进入速度。

---

## 10. v0.1.8 回归计划（发热 / 点击延迟 / 摇一摇诊断）

> 状态：**待执行**。本轮只完成 JVM 单测（70 项）与 release 构建，未在模拟器/真机跑无障碍场景。

### 10.1 新增场景

同一测试包连续启动间隔 >6 秒（窗口状态机防重复点击）。

```bash
for v in skip skipcount delay late late5000 deep bigtree closenum nonclick shake shakecd huge other ad; do
  adb logcat -c
  adb shell am force-stop com.splashguard.splashtest
  adb shell am start -n com.splashguard.splashtest/.FakeSplashActivity --es variant $v
  sleep 8.5
  adb logcat -d -s FakeSplash:V | grep "CLICKED:"
  adb shell am force-stop com.splashguard.splashtest
done
```

| variant | 期望 |
|---|---|
| `skip` / `skipcount` / `delay` / `late` / `late5000` / `deep` / `bigtree` / `closenum` | `CLICKED:<variant>` |
| `shake` / `shakecd` | 只出现 `CLICKED:shake`（跳过按钮），绝不出现 `CLICKED:shake-ad` |
| `huge` / `other` / `ad` / `nonclick` | 无 `CLICKED`,尤其不能出现 `CLICKED:ad-area` |

### 10.2 扫描成本与发热

```bash
# 无障碍扫描指标：导出诊断报告，看 P50/P95/平均/最大、结果计数、本次窗口延迟
adb shell am start -n com.splashguard.app/.ui.MainActivity
# 首页 → 导出诊断报告

# 逐线程 CPU（不要用 dumpsys cpuinfo，受 Load 影响）
PID=$(adb shell pidof com.splashguard.app)
adb shell "cat /proc/$PID/task/*/stat" | awk '{print $1,$14,$15}' | head
```

期望（目标值，需真机确认）：
- 扫描平均 <100ms、P95 <250ms；
- 完全空闲 3 分钟进程 CPU% 接近 0；
- 连续切换 10 个 App 3 分钟，扫描结果中 `系统界面` / `桌面` 计数不再增长；
- 典型点击延迟 <500ms。

### 10.3 开屏归因

诊断报告新增：

- `本次窗口关联拦截: N 条 / 域名...`：同一 App 在窗口开始前 3 秒到现在的 DNS 拦截；
- `本次窗口出现摇一摇文案: 是/否`：无障碍在扫描中看到互动文案（只记录，不点击）。

如果某 App 仍出现开屏且报告显示「关联拦截 >0、未点击」，说明 DNS 已拦住但素材已缓存或按钮没找到；
如果「关联拦截 =0」，更可能是 HTTPDNS / 同域素材，需要单独分析。
