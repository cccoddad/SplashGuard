# 变更记录

## v0.1.6 — 修复更新后无障碍「全部不跳过」+ 隐藏系统无障碍按钮（2026-11-04）

真机反馈：装 0.1.5 后，即使重新打开无障碍权限，所有 App 的开屏都不再自动跳过。

**根因（两条叠加）**

1. 0.1.5 把监听收窄为 `TYPE_WINDOW_STATE_CHANGED` + 1.5 秒窗口。Android 16 / HyperOS 冷启动不一定发 STATE 事件，且跳过按钮常晚于 1.5 秒；窗口内没扫到就彻底不点。
2. App 覆盖安装后，系统可能保留 `enabled_accessibility_services` 但解绑服务。界面显示「已授权」，实际服务没有运行；0.1.5 没有检测/修复这个状态。

**修复**

- 同时监听 `TYPE_WINDOW_STATE_CHANGED` 和 `TYPE_WINDOWS_CHANGED`；仍**不监听** `TYPE_WINDOW_CONTENT_CHANGED`（遵守 AGENTS.md 红线）。
- 轮询窗口 1.5s → 6s：0–600ms 每 100ms，600–2000ms 每 250ms，2000–6000ms 每 800ms；节点上限 150 → 400，祖先深度 4 → 8。
- 同包名窗口事件改为「重启轮询」而不是只更新时间；回桌面/打开本 App 时重置窗口。
- 新增 `A11yController`：检测「已授权但未绑定」，通过 `WRITE_SECURE_SETTINGS` 移除/重新加入服务。打开 App 时自动尝试一次；`MY_PACKAGE_REPLACED` 也会自动修复（仅当服务原本就在列表里，避免把用户主动关掉的服务重新打开）。
- 首页新增「今日跳过」；「自动跳过」按钮显示「开 / 未连接」。
- 诊断报告新增服务连接状态、最近事件/扫描/点击、今日跳过、系统无障碍快捷方式/按钮目标、最近无障碍事件流。
- 新增「一键隐藏系统无障碍按钮」：识别系统的无障碍快捷方式，关掉后不影响自动跳过；未授予 `WRITE_SECURE_SETTINGS` 时给出一次性 ADB 授权命令。
- ADB 无障碍命令改为「先移除再追加」，保留 TalkBack 等其他服务，同时修复「已授权但未绑定」的状态。

**验证**

- 单测 40 项全绿（新增 `SkipLabelMatcher` 6 项、`SkipWindowTracker` 7 项）。
- API 34 模拟器 9 场景：`skip` / `skipcount` / `delay` / `late`（按钮 2.5s 后出现）/ `deep`（6 层容器）/ `shake` 点中；`huge` / `other` / `ad` 不点。
- 更新 APK 后服务恢复绑定，首页显示「自动跳过 · 开」，`skip` 场景恢复点击。
- 一键隐藏：`accessibility_shortcut_enabled=0`、`accessibility_button_targets=null`，`enabled_accessibility_services` 仍包含本服务，`skip` 仍点中。
- 空闲 10 秒：本 App 进程 utime+stime 增量 = 0 ticks。
- **真机（小米 13 Pro / Android 16）待用户安装后确认。**

**验收注意**

连续启动同一个测试包时，两次之间需间隔 >6 秒；否则窗口状态机会把它判为「同一窗口、已点过」（这是防重复点击的设计行为）。

## v0.1.5 — 修复 tun 读循环空转（耗电/发烫根因）（2026-11-04）

真机反馈：手机发烫、卡顿，系统耗电统计里 SplashGuard 占 31.19%，后台 CPU 时间 2 分 48 秒。

**根因（P0）**

`SplashGuardVpnService.loop()` 里对 `read()` 返回值的处理是 `if (n <= 0) continue`。
tun 上没有报文时 `read` 若返回 0，这个 `continue` 就变成**满速空转**。

用 `/proc/<pid>/task/*/stat` 逐线程采样定位到 `tun-reader-1`：**6 秒烧掉 6620ms CPU（≈110%，一个核心满载）**，
而且是在完全空闲、没有任何 DNS 查询的时候。从 v0.1.0 起就存在 —— 早期验收只测了 PSS 内存增长和
"有流量时"的场景，没测空闲态，所以一直没暴露。

**修复**

- `n < 0`（EOF）→ 立即退出读循环
- `n == 0` → 让出 CPU（sleep 10ms）并计数，连续 200 次仍为 0 才判定 fd 失效退出，避免误杀隧道
- 修复后同样采样：空闲 10 秒 `tun-reader` CPU = 0ms（只有 20ms 的 HeapTaskDaemon）

**顺带优化（同批）**

- 无障碍服务不再监听 `TYPE_WINDOW_CONTENT_CHANGED`：滚动列表 / 看视频 / 打字时不再触发全树遍历
- 无障碍只对「包名变化」的窗口切换响应；同包名但间隔 >1.5s 视为新一次启动（修复跳转到另一 Activity 漏点）
- 抢跑轮询改为前 600ms 每 100ms、之后每 300ms，单次扫描节点上限 500 → 150
- 新增 DNS 响应缓存（256 条 / 45 秒 TTL，键含 qType），命中直接本地回包，省掉 socket 创建 + 上游往返
- 上游 IP 解析结果缓存；去掉「每拦截 5 次刷新一次通知」的无谓 IPC
- 日志落盘的裁剪改为按文件大小触发（原先每条日志都要把整个文件读一遍）

**验证**

- 单测 27 项全绿
- 无障碍 7 场景全对：skip / skipcount / delay 点中（delay 按钮出现后 197ms 点掉），
  huge / other / ad 不点，shake 只点跳过
- 拦截功能正常：`ads.pangle.io` / `pangle.io` 解析失败，`www.baidu.com` 正常解析到 183.2.172.177

**手册更正**

`docs/real-device-guide.md` 第 2/3 步原先让用户用浏览器访问 `ads.pangle.io` 验证 —— 那是广告 SDK 的
接口域名，本来就不提供网页，**永远打不开**，与拦不拦截无关。已改为用 `example.com` 做对照：
先确认能打开 → 加规则 → 打不开 → 删规则 → 恢复。并补充说明拦截响应 TTL 60 秒，
关闭开关后立刻访问仍会失败属正常，需等 60 秒或换未访问过的域名。

## v0.1.4 — 小米 / HyperOS 无障碍入口引导（2026-11-04）

真机用户反馈：小米 13 Pro 在系统无障碍里找不到「SplashGuard 自动跳过」。
原因是 HyperOS 把无障碍藏在「更多设置」，且常把侧载应用折叠进「更多已下载的服务」。

**新增**

- 点首页「自动跳过」时先弹机型引导：小米搜索法、「更多已下载的服务」折叠项、华为应用启动管理
- 引导弹窗新增「复制 ADB 命令」，一键复制两条无障碍授权命令
- `docs/real-device-guide.md` 补小米 13 Pro 专属路径、小米「USB 调试（安全设置）」注意事项、
  ADB 兜底方案，以及「按钮变『自动跳过 · 开』即为生效」的自检方法
- 交付物同时提供 `.apk` 与 `.zip`（微信会把 apk 改名成 `.apk.1`，zip 不会），并放到桌面

**验证**

- 单测 27 项全绿
- 引导弹窗四要素完整、小米提示文案无截断（截图 `docs/evidence/a11y-guide.png`）
- 两条 ADB 命令执行后 App 显示「自动跳过 · 开」，
  `dumpsys accessibility` 的 `Enabled services` 确认包含本服务

## v0.1.3 — 真机验收支持与防摇一摇加固（2026-11-04）

**新增**

- 首页「导出报告」：一键生成含机型 / 系统 / ROM / 规则 / 分应用 / 无障碍 / 最近日志的诊断文本，
  可复制或分享。报告只在本机生成，代码里没有任何上传路径
- 拦截日志落盘 `files/recent_blocks.tsv`：进程被系统回收后记录不再丢失，最多 400 行自动截断
- 无障碍「抢跑轮询」：窗口出现后 1.4 秒内每 90ms 扫一次节点树。真实广告 SDK 的跳过按钮常延迟
  300~800ms 才渲染，只靠事件驱动会错过这段空窗（也正是摇一摇广告的作案窗口）
- `docs/real-device-guide.md`：真机安装三条路径、按 ROM 配电池与自启、5 步冒烟验收、排障手册
- `splashtest` 新增两个场景：`delay`（跳过按钮延迟 600ms 渲染）、`shake`（「摇一摇跳转详情」+「跳过」并存）

**修复**

- **P1** `<application>` 未声明 `android:name=".App"`，Application.onCreate 从未执行 ——
  日志落盘因此完全不生效，属于隐藏已久的死代码
- **P1** 可点击父节点未做尺寸校验：当「跳过」文字节点不可点、其父节点是满屏广告容器时，
  会把整块广告点掉并跳转落地页，比不点危险得多。现在文字节点与最终点击节点都要过尺寸闸
- **P2** 诊断报告正文过长会把「复制 / 分享 / 关闭」按钮挤出屏幕
- **P2** versionName 长期滞后于 CHANGELOG（0.1.0 → 0.1.3，versionCode 1 → 3）

**验证**

- 单元测试 27 项全绿
- 无障碍 7 场景全对：`skip` / `skipcount` / `delay` 点中，`huge` / `other` / `ad` 不点，
  `shake` 只点跳过、摇一摇一次没碰
- 强杀进程重启后日志仍在（`ad.toutiao.com` / `pangle.io` 两条）
- `delay` 场景：按钮 600ms 后渲染，165ms 内被点掉（抢跑轮询生效）

## v0.1.2 — 模拟器验收与缺陷修复（2026-11-04）

**修复**

- **P0** 隧道重启后 DNS 黑洞：拆旧隧道后延迟 350ms 再 `establish()`、最多重试 3 次；
  引入 `generation` 代际号让旧读循环自行退出；读循环非预期退出时主动拆隧道（避免"断网"）；
  设置页保存不再重建隧道（拦截模式/上游 DNS/自定义规则本就实时读取）
- **P1** 分应用选择死锁：允许先在「全部应用」模式勾选暂存，再切「仅选中的应用」
- **P1** 重启后系统撤销 VPN 授权时静默失败：改为发「拦截没有自动恢复」通知，点击回首页重新授权
- **P2** 拦截发生时首页「今日拦截」不刷新：日志回调同步更新计数

**新增**

- `splashtest/`：验收用假开屏 App（5 种界面：小跳过 / 带倒计时 / 超宽按钮 / 「关闭」文案 / 整块广告）
- `docs/acceptance-emulator.md`：API 26 + API 34 的 A–H 全项验收报告、命令、证据与缺陷复现步骤
- `docs/evidence/`：截图、UI 树、内存快照

**验证**

- 单元测试 27 项全绿（DNS 报文 10 / 封包 5 / 规则 12）
- 连续开关 20 次无崩溃；后台静置 10 分钟 PSS 41.3MB → 40.6MB（无增长）
- 分流证据：打开网页时 tun0 仅 +91B，物理网卡 +162KB

## v0.1.1 — iOS DNS 描述文件（2026-11-04）

**新增**

- `ios/SplashGuard-DNS-DoH.mobileconfig`：DoH 预设（AdGuard DNS），全系统 DNS 生效
- `ios/SplashGuard-DNS-DoT.mobileconfig`：DoT 变体
- `ios/make_profile.py`：描述文件生成器，支持 `adguard-doh` / `adguard-dot` / `nextdns` 预设与自定义 DoH
- `ios/check_profile.py`：结构校验（必需键、协议合法性、UUID 唯一性），无需 macOS 或 iPhone
- `ios/README.md`：安装 / 卸载 / 换服务商 / 无效场景说明
- `docs/testing.md`：新增 I-01 ~ I-06 六条 iOS 验收用例
- `docs/privacy.md`：新增 3.1 节，说明描述文件与 Android 端的隐私边界差异

**说明**

- 描述文件 UUID 由 PayloadIdentifier 经 uuid5 派生，重复生成字节一致
- 已通过结构校验与反向用例（注入非法协议 / 非 https URL / 重复 UUID 均能报错）
- **未在真机安装验证**（本机无 iPhone），真机效果需按 `docs/testing.md` 自测

## v0.1.0 — Android MVP（2026-11-04）

- `VpnService` 仅路由 DNS 目标地址，非 DNS 流量不过隧道
- 本地规则引擎：内置种子库 + 用户自定义，后缀匹配，保存即生效
- 分应用代理（blacklist / whitelist 双模式）
- 可选无障碍服务：仅匹配「跳过 / 关闭 / Skip」类小控件，防误点
- 开机自启、前台服务常驻、拦截日志（本地 200 条）
- 27 项 JVM 单元测试：DNS 报文解析与构造、IP/UDP 封包、规则匹配
- 文档：可行性报告 / PRD / 技术方案 / 测试用例 / 隐私说明
- 产物：`app-release.apk`（4.5 MB，自签）
