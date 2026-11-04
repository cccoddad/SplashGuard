# SplashGuard 项目评审与优化建议

> 评审时间：2026-11-04  
> 评审范围：README、CHANGELOG、docs/、app/src、app/src/test、ios/、gradle 配置、dist/、splashtest/  
> 结论先行：**工程底子好、文档和验收强、方向正确；当前更像“可用的技术验证”，还不是“能和 GKD / AdGuard 正面竞争的产品”。下一步应先修正确性与安全问题，再补规则生态和覆盖率。**

---

## 1. 项目理解

SplashGuard 是一个 **Android 本地开屏广告拦截 MVP**，核心是两条通道：

```text
App 冷启动
├─ DNS 查询 → tun(10.111.222.2) → 命中规则? → 0.0.0.0 / NXDOMAIN
│                                  └ 未命中 → 转发上游 DNS
└─ 窗口切换 → 无障碍服务 1.5s 内扫描 → 自动点「跳过」
```

- **Android 主体**：`VpnService` 只把 DNS 地址加进路由表，非 DNS 流量完全不进进程，所以耗电和网速影响很小。
- **iOS**：不做 App，只给 DoH/DoT 描述文件，因为 iOS 没有跨 App 自动点击 API。
- **全部本地**：无账号、无统计 SDK、无云端规则、日志只在本机。
- **工程形态**：Kotlin + XML，`minSdk 26`、`targetSdk 34`，自签侧载；核心 DNS/封包/规则是纯函数，带 27 个 JVM 单测。
- **产物**：`app-release.apk` 约 4.5 MB，`dist/` 下有 0.1.3 / 0.1.4 / 0.1.5 交付包。

整体判断：这是一个工程底子很好、文档和真机验收做得远超同类的 MVP，但规则生态、覆盖率、allowlist、TCP/IPv6/加密 DNS 等关键能力还没补齐。

---

## 2. 优点

### 2.1 方案诚实，边界写得清楚

`README.md:10-29`、`docs/feasibility.md` 明确写了 HTTPDNS、信息流、自绘开屏、已缓存素材拦不到。很多同类产品会把这些包装成“全能”，SplashGuard 没有，这是长期信任的基础。

### 2.2 “只路由 DNS”的设计是对的

`SplashGuardVpnService.kt:158-190` 只加虚拟 DNS 和常见公共 DNS 的 `/32` 路由，避免全局 VPN 的用户态转发。这个决策直接决定了低耗电和不降速。

### 2.3 双通道思路正确

DNS 层负责中小 App，无障碍层补大厂 HTTPDNS 的盲区。这个组合比单纯 DNS 或单纯点跳过都更合理。

### 2.4 无障碍防误点做得很克制

`SkipAccessibilityService.kt:160-193`：

- 只匹配固定文案；
- 文字节点和最终点击的父节点都过尺寸闸；
- 1.5 秒窗口、2 秒冷却、每窗口最多一次；
- 不碰“摇一摇 / 点击查看”这类互动文案。

这是本项目最值得保留的安全设计。

### 2.5 v0.1.5 的性能修复很关键

`SplashGuardVpnService.kt:212-237` 修掉了 `read() == 0` 满速空转；`SkipAccessibilityService.kt:70-82` 不再监听 `TYPE_WINDOW_CONTENT_CHANGED`。这两个是真正会导致发烫/耗电的根因，修得对。

### 2.6 可靠性有兜底

`generation` 代际号、350ms 重建退避、`establish()` 重试、读循环死亡主动拆隧道（`SplashGuardVpnService.kt:45-52, 97-103, 128-141, 242-252`），以及开机授权丢失的通知引导（`BootReceiver.kt:36-43`），说明作者考虑过“宁可少拦，不能断网”。

### 2.7 隐私边界真实

无网络上报、`allowBackup=false`、日志私有目录、FileProvider 只暴露 `reports/`、无第三方 SDK。`docs/privacy.md` 也把“DNS 仍会发给上游”讲清楚了。

### 2.8 可测试性好

`DnsPacket`、`PacketBuilder`、`DomainMatcher` 都是纯 Kotlin，单测 27 项全过。这是很多同类项目没有的。

### 2.9 真机 / ROM 适配和诊断报告

小米 HyperOS 无障碍入口引导、ADB 兜底、导出诊断报告、日志落盘，这些“脏活”做得很好，说明是按真实用户场景在做。

---

## 3. 主要问题

按优先级排：

| 级别 | 问题 | 证据 | 建议 |
|---|---|---|---|
| **P0** | **常见公共 DNS 的 `/32` 路由会黑洞非 DNS 流量** | `SplashGuardVpnService.kt:167-170` 给 `8.8.8.8`、`1.1.1.1` 等加了 `/32` 路由；`handle()` 只处理 UDP:53（`:254-257`）。任何 TCP、HTTPS、ping 到这些 IP 的流量都会进 tun 后被丢弃 | 最安全的做法是**移除这些 `/32` 路由**，承认硬编码 DNS 会绕过；如果一定要接管，需要实现完整的 UDP/TCP 转发，而不是只处理 UDP 53 |
| **P0** | **TCP 53 和超大 UDP 响应会挂死** | `forward()` 用 4096 字节缓冲（`:326`）；虚拟 DNS 的 `/32` 会连 TCP:53 一起路由进来，但没有 TCP 处理 | 至少把缓冲区改成 65535 并正确处理 TC 位；完整方案是补最小 TCP DNS 代理，或对 TCP:53 返回 RST 让客户端快速失败 |
| **P1** | **内置规则无法单独关闭，也没有 allowlist** | `RuleStore.kt:23-38` 把内置规则和用户规则合并成一个集合；`Prefs` 里只有 `userRules`，没有 allowlist / 禁用列表 | 这是最影响日常使用的缺口。加一个优先级最高的 allowlist，支持 `@@||domain^`；内置规则按 SDK 分组，可单独开关。否则某条内置规则误伤时，用户只能卸载或等更新 |
| **P1** | **`enabled` 把“用户意图”和“当前运行态”混在一起** | `stopVpn()` 里 `prefs.enabled = false`（`:393`）；`onRevoke()`、`onDestroy()` 都会走 `stopVpn()`（`:407-418`）；`BootReceiver` 又用 `prefs.enabled` 判断是否自启（`BootReceiver.kt:34`） | 拆成 `desiredEnabled`（只有用户开关能改）和 `isRunning`（实际隧道状态）。系统杀进程 / 别的 VPN 抢占时不要清掉用户意图，否则重启后不会自动恢复 |
| **P1** | **IPv6 DNS 完全没接管** | `Const.VIRTUAL_DNS` 只有 IPv4；`PacketBuilder` 只处理 IPv4 | 双栈网络下系统/App 可能走 IPv6 DNS 绕过过滤；IPv6-only 网络可能直接不可用。短期至少在自检里检测并提示，长期加虚拟 IPv6 DNS 和 IPv6 封包处理 |
| **P1** | **无障碍没有 App 内总开关，且文案覆盖窄** | `Prefs.a11yEnabled`（`Prefs.kt:69-72`）只被诊断报告读取，服务本身不读；`accessibility_service_config.xml:3` 还声明了 `typeWindowContentChanged`，和代码实际行为不一致 | 加 App 内总开关（关闭时 `disableSelf()` 或忽略事件）；把 XML 的 eventTypes 改成只 `typeWindowStateChanged`；文案匹配支持 `跳过广告5s`、`Skip 5s` 等倒计时变体 |
| **P2** | **分应用每勾选一次就重启一次隧道** | `AppPickerActivity.kt:98-103` | 改成“保存/应用”按钮或 800ms 防抖，避免连续勾选导致多次 DNS 闪断 |
| **P2** | **规则保存静默丢弃非法行，且不能导入导出** | `SettingsActivity.kt:60-75`；`RuleParser.normalize()` 直接返回 null | PRD 里承诺的“提示具体行号”没实现。加校验结果、忽略行号、导入/导出、恢复内置规则 |
| **P2** | **DNS 响应不区分 qType** | `DnsPacket.kt:92-101` 无论 A/AAAA/TXT 都返回 A 记录 `0.0.0.0` | A 查询返回 `0.0.0.0`；AAAA 返回 NODATA 或 `::`；其他类型返回 NODATA，语义更正确 |
| **P2** | **DNS 缓存 TTL 固定 45 秒，且失败时不回包** | `SplashGuardVpnService.kt:475-480`；`forward()` 失败返回 null，调用方直接 return | 解析上游 TTL 并取 min；上游失败时快速返回 SERVFAIL，避免客户端干等 2.5 秒以上。缓存 key 也建议包含 qClass/DO 位 |
| **P2** | **缺少 CI 和组件级测试** | 只有纯 JVM 单测；lint 在离线环境因缺依赖没跑完 | 加 GitHub Actions：`test + lint + assembleRelease`；补 `SplashGuardVpnService`、`BlockLog`、无障碍匹配、状态迁移的 Robolectric / instrumentation 测试 |
| **P2** | **文档版本漂移** | `docs/real-device-guide.md:16` 写 `versionCode 3`，实际 `app/build.gradle.kts:22` 是 5；README 路线图里 iOS 生成器已经交付，但还挂在 v1.0 | 版本号、CHANGELOG、README 路线图统一；加一个版本一致性检查 |

另外两个值得注意的点：

- **`QUERY_ALL_PACKAGES`**（`AndroidManifest.xml:10-12`）对一个主打“最小权限”的 App 来说有点刺眼。分应用列表其实可以用 `<queries>` 声明 `MAIN/LAUNCHER` intent 来替代，能去掉这个敏感权限。
- **“全本地·不上传”** 的口号容易让人误解。更准确的说法是“规则和日志全本地，未拦截域名的 DNS 仍会发给上游”。`docs/privacy.md` 已经解释，但 UI 文案可以再精确一点。

---

## 4. 市面同类产品

### 4.1 无障碍自动跳过（和本项目最像）

| 产品 | 特点 | 可借鉴 | 链接 |
|---|---|---|---|
| **GKD** | 开源、无障碍 + 高级选择器 + 订阅规则，目前是同类里事实上的领先者 | 规则订阅生态、按 App / 规则组开关、触发记录、快照调试 | <https://gkd.li/> · <https://github.com/gkd-kit/gkd> |
| **开屏跳过 / Android-Touch-Helper** | 开源 MIT，三种策略：关键字、指定控件、指定坐标；不申请网络/存储权限 | 和本项目架构最接近，尤其是“关键字 + 控件 + 坐标”三级兜底 | <https://github.com/zfdang/Android-Touch-Helper> |
| **AutoSkip** | 基于 Shizuku 授权，不需要手动开无障碍 | “不想给无障碍权限”用户的替代路线，可作为高级模式 | <https://github.com/xjunz/AutoSkip> |
| **SKIP** | 开源 GPL-3.0，无障碍自动跳过，带自定义规则订阅 | 规则订阅格式、文档组织 | <https://github.com/GuoXiCheng/SKIP> |
| **李跳跳** | 经典产品，2023 年因律师函无限期停更；社区仍维护规则 | 极简 UX、规则导入、广泛兼容性 | <https://litiaotiao.cn/> · 规则：<https://github.com/Snoopy1866/LiTiaotiao-Custom-Rules> |

> 轻启动、一指禅、叮小跳、大圣净化等同类产品在 2023 年后陆续停更或移除“自动跳过”，法律风险是真实存在的。文档里已经提到这一点，建议再加一份明确的免责声明，并坚持“只点用户本来就能点的跳过，不点广告本体”。

### 4.2 DNS / VPN 过滤（能力互补）

| 产品 | 特点 | 可借鉴 | 链接 |
|---|---|---|---|
| **AdGuard for Android** | 本地 VPN + DNS 过滤 + HTTPS 过滤 + 规则/统计/allowlist | 加密上游 DNS、allowlist、统计、应用管理、QS 快捷开关 | <https://adguard.com/en/adguard-android/overview.html> |
| **RethinkDNS + Firewall** | 开源，本地 VPN，190+ 黑名单，DNS + 防火墙 + 连接日志 | 分应用防火墙、按 App 看连接、DoH/DoT | <https://rethinkdns.com/> |
| **Blokada** | 开源，Android/iOS，DNS/VPN 广告拦截 | 多平台、订阅/云 DNS 模式 | <https://blokada.org/> |
| **personalDNSfilter** | Java 写的本地 DNS 过滤代理，支持 DoH/DoT，可处理百万级 hosts，内存占用低 | 大规则库内存管理、CNAME cloaking 防护 | <https://www.zenz-solutions.de/personaldnsfilter-wp> |
| **AdAway** | 开源，hosts + 本地 VPN，allowlist、自定义源、重定向 | allowlist / 自定义源的产品化做法 | <https://adaway.org/> |
| **NextDNS** | 云端 DNS 过滤，DoH/DoT，免费 30 万次/月，可分设备/看日志 | 作为 iOS / 降级方案；规则和日志在云端 | <https://nextdns.io/> |
| **AdGuard Home / Pi-hole** | 自建全网 DNS 过滤 | 自建 DoH 上游、家庭网络覆盖 | <https://adguard.com/en/adguard-home/overview.html> · <https://pi-hole.net/> |
| **TrackerControl** | 开源，本地 VPN 监控/拦截追踪器，按 App 看连接 | “可解释性”和按 App 统计做得很好 | <https://trackercontrol.org/> |

### 4.3 iOS

| 产品 | 特点 | 链接 |
|---|---|---|
| **AdGuard for iOS** | Safari 内容拦截 + DNS 保护 | <https://adguard.com/en/adguard-ios/overview.html> |
| **1Blocker** | Safari 内容拦截 + 可选本地 VPN 追踪拦截 | <https://1blocker.com/> |
| **Wipr / Wipr 2** | Safari 拦截器；新出的 **Filtr** 利用 iOS 26 的 URL Filter 在系统层拦广告域名 | <https://kaylees.site/wipr.html> |
| **NextDNS** | iOS 描述文件 / DoH/DoT，适合作为现有 iOS 方案的替代上游 | <https://nextdns.io/> |

> 值得关注：2026 年 iOS 26 引入了 URL Filter 类能力，Wipr 的 Filtr 已经用它做“接近全 App 的域名拦截”。这不能解决“自动点跳过”，但可能让 iOS 端从“只有 DNS 描述文件”升级为“描述文件 + 本地 URL Filter App”，值得做技术验证。参考：<https://techcrunch.com/2026/06/04/filtr-is-a-new-privacy-tool-that-blocks-ads-in-almost-every-iphone-and-mac-app/>

---

## 5. 下一步优化方向

一句话：**先把“不会把用户网搞坏”做到 100%，再把“规则覆盖率和可解释性”做起来，最后才是加密 DNS、IPv6 和 iOS 新能力。**

### 5.1 v0.1.6：稳定性修复（1-3 天，优先做）

1. **移除 `Const.COMMON_DNS` 的 `/32` 路由**，或实现完整的非 DNS 流量转发。  
   这是目前最可能造成“开了 App 反而上不了网”的问题。
2. **DNS 响应缓冲区改 65535 + 正确处理 TC**；补 TCP 53 的最小处理，避免客户端挂死。
3. **缓存 TTL 化**：解析上游 TTL，TTL=0 不缓存；上游失败快速返回 SERVFAIL。
4. **A/AAAA/其他 qType 返回正确语义**。
5. **拆分 `desiredEnabled` / `isRunning`**，修复开机、`onRevoke`、系统回收后的恢复逻辑。
6. **无障碍 App 内总开关 + XML eventTypes 同步**。
7. 补测试：AAAA、EDNS、大响应、TCP、缓存 TTL、状态迁移；加 CI。
8. 修正 `real-device-guide` 的 versionCode 和 README 路线图。

### 5.2 v0.2：可用性和安全（1-2 周）

1. **allowlist + 内置规则分组开关 + `@@` 例外语法**。  
   这是产品从“能跑”到“敢日常用”的关键。
2. **规则导入/导出 + 本地规则包**：支持从文件/URL 导入，带版本号和校验和；不做默认云端同步，保持本地优先。
3. **每 App 效果统计**：这个 App 命中了哪些规则、是否有无障碍点击、是否仍出现开屏；从日志页一键“给这个 App 加白/关规则”。
4. **一键自检**：VPN 是否建立、DNS 是否真的进隧道、无障碍是否生效、Private DNS 是否冲突、电池优化是否关闭、当前 ROM 的后台限制。这个功能会极大降低支持成本。
5. **分应用页搜索/筛选 + 保存后统一生效**，不要每勾选一次重启隧道。
6. **通知显示今日拦截数、QS 快捷开关、暂停 5 分钟**。
7. **无障碍增强**：可配置关键词、倒计时正则、按 App 规则、点击记录；可选的“学习模式”（用户手动点一次“跳过”，记录控件特征，生成候选规则，默认不启用）。

### 5.3 v0.3：差异化和覆盖率（1 个月左右）

1. **加密上游 DNS（DoH/DoT）**：多上游、失败回退、证书校验。现在上游是明文 UDP，隐私故事不够完整。
2. **Private DNS / DoH 冲突检测**：检测到系统私有 DNS 或浏览器 DoH 时给出明确提示，避免“开了但没生效”。
3. **IPv6 支持**：虚拟 IPv6 DNS、IPv6 封包、AAAA 处理、双栈测试。
4. **规则生态**：从 AdGuard/uBlock/1Hosts 里整理“开屏广告 SDK”子集，按穿山甲/优量汇/快手/百度等分组，带风险标签；支持本地签名更新和回滚。
5. **合规模式**：默认“安全模式”只跳开屏、不碰信息流；把“广告过滤”作为可选增强。这样既降低误伤，也降低法律风险。
6. **iOS 26 URL Filter 验证**：如果 API 可用，做“DNS 描述文件 + URL Filter”组合；如果不可用，继续完善 NextDNS/自建 DoH 描述文件生成器。

### 5.4 v1.0：产品化

- Material 3 重做、英文/多语言、TalkBack 可用性；
- R8、baseline profile、启动速度、APK 体积；
- GitHub Actions + 可复现构建 + 发布校验和；
- 社区规则仓库、问题模板、兼容性矩阵；
- 可选 Shizuku 模式（不想开无障碍的用户）；
- 明确免责声明，保持非商业化/捐赠模式，避免重蹈李跳跳的覆辙。

---

## 6. 最小行动清单

如果只做 5 件事，按这个顺序：

1. **去掉公共 DNS 的 `/32` 路由**（或实现非 DNS 转发）。
2. **DNS 缓冲区 65535 + TTL 缓存 + AAAA/NODATA**。
3. **加 allowlist 和内置规则分组开关**。
4. **拆 `desiredEnabled` / `isRunning`**。
5. **无障碍 App 内总开关 + 修 XML eventTypes**。

这 5 件做完，产品会从“技术验证”变成“可以放心给家人装”的状态；之后再谈规则生态、加密 DNS 和 iOS。

---

## 7. 验证情况

- 用本地缓存的 Gradle 8.14.3 跑了 `gradlew test`：**27/27 通过**（DNS 10、封包 5、规则 12）。
- `lint` 在离线模式下因缺 `kotlin-stdlib-jdk8:1.8.20` 缓存没跑完；`gradlew` 直接下载 Gradle 时报本机证书错误，属于环境问题，不是代码失败。
- 评审时 `git status` 是干净的，没有未提交改动。

---

## 8. 结论

**工程底子好、文档和验收强、方向正确；最大短板是规则生态、allowlist、TCP/IPv6/加密 DNS 这些“覆盖率与正确性”问题。下一步不要继续堆文档，先把 P0/P1 的工程问题修掉，再谈差异化。**
