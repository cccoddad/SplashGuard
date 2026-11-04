# 技术方案

## 1. 总体架构

```
┌──────────────────────────── SplashGuard App ────────────────────────────┐
│                                                                          │
│  MainActivity ──► VpnService.prepare() ──► 系统 VPN 授权弹窗（仅首次）    │
│       │                                                                  │
│       ▼                                                                  │
│  startForegroundService(SplashGuardVpnService)                            │
│       │                                                                  │
│       ├─ Builder: addAddress(10.111.222.1/32)                            │
│       │           addDnsServer(10.111.222.2)   ← 系统把 DNS 发到这里      │
│       │           addRoute(10.111.222.2/32)    ← 只有这个网段进隧道       │
│       │           + 常见公共 DNS 的 /32 路由（拦硬编码解析器）            │
│       │           + addAllowed/DisallowedApplication（分应用）            │
│       │                                                                  │
│       ▼ establish() ──► tun fd                                          │
│  Worker Thread: read(IPv4+UDP) ──► 提取 DNS payload                      │
│       │                                                                  │
│       ├─ 命中规则 → 构造响应（0.0.0.0 / NXDOMAIN）→ 写回 tun + 记日志     │
│       └─ 未命中   → protect(socket) 转发上游 → 写回 tun（不改一个字节）   │
│                                                                          │
│  AccessibilityService（可选）──► 冷启动 6s 内点文本匹配的「跳过」         │
└──────────────────────────────────────────────────────────────────────────┘
```

关键点：**因为只路由 DNS 目标地址，非 DNS 流量根本不进入进程**，所以不存在"所有流量过一遍
用户态转发"的性能与耗电问题。这是本项目与「全局 VPN」类工具的本质区别。

## 2. 只拦 DNS 的路由设计

```kotlin
Builder()
  .addAddress("10.111.222.1", 32)   // 隧道自身的虚拟地址
  .addDnsServer("10.111.222.2")     // 系统会把这个地址下发给应用作为 DNS
  .addRoute("10.111.222.2", 32)     // ← 只把 DNS 虚拟地址放进路由表
```

- 应用发起 DNS 查询 → 目标 10.111.222.2 → 命中 /32 路由 → 进 tun → 我们收到 UDP:53 报文
- 其他所有流量 → 路由表里没有 → 走物理网卡，完全不过隧道
- 补充路由：`8.8.8.8`、`1.1.1.1`、`114.114.114.114` 等常见硬编码解析器的 /32，
  让"不听话"的 App 也把查询交给我们

**防回环**：上游转发用的 `DatagramSocket` 必须先 `protect(socket)`，
否则发往 223.5.5.5 的查询会再次进入我们自己的 tun，形成死循环。

## 3. DNS 报文处理

### 3.1 解析

只解析 Question 段的 QNAME（不依赖压缩指针，Question 段不允许压缩）：

```
[0..1] ID  [2..3] Flags  [4..5] QDCOUNT  [6..7] ANCOUNT  [8..9] NSCOUNT  [10..11] ARCOUNT
[12..] QNAME(labels, 以 0x00 结尾) + QTYPE(2) + QCLASS(2)
```

### 3.2 构造拦截响应

| 模式 | RCODE | ANCOUNT | 应答记录 |
|---|---|---|---|
| `0.0.0.0` | 0 (NOERROR) | 1 | 指向 0.0.0.0 的 A 记录，TTL 60 |
| `NXDOMAIN` | 3 | 0 | 无 |

构造时**丢弃原查询的 Additional 段**（EDNS0 OPT），并把 ARCOUNT 置 0。
这是合规做法：响应里不带 OPT 时客户端会按非 EDNS 处理，绝大多数解析器实现都接受。

应答记录用 `0xC00C` 指针回指 Question 的域名，节省字节。

### 3.3 回写 tun

需要自己拼 IPv4 + UDP 头：

- IPv4：版本/头长 `0x45`、总长、ID、TTL 64、协议 17、源/目的地址互换、校验和重算
- UDP：源/目的端口互换、长度、**校验和填 0**（IPv4 下 UDP 校验和可选，填 0 表示不校验，合法且被广泛接受）

## 4. 规则引擎

### 4.1 规则格式

```
# 注释
pangolin-sdk-toutiao.com     # 后缀匹配，命中自身及所有子域
||gdt.qq.com^                # 兼容 AdBlock 写法，解析时去掉 || 和 ^
```

### 4.2 匹配算法

域名 `a.b.example.com` 的候选后缀是 `a.b.example.com` → `b.example.com` → `example.com` → `com`，
逐个查 HashSet，命中即拦。复杂度 O(标签数)，比逐条正则快几个数量级。

用 HashSet 而非前缀树：20 万条规则也就几 MB，MVP 阶段没必要上 Trie。

### 4.3 规则来源

| 来源 | 位置 | 可编辑 |
|---|---|---|
| 内置种子库 | `assets/rules/splash_ads.txt` | 否（随版本更新） |
| 用户规则 | SharedPreferences `user_rules` | 是 |
| 远程订阅 | v0.1 不实现 | — |

MVP **不做远程订阅**：一来隐私原则要求全本地，二来没有维护订阅源的能力，
做了一个空壳反而误导用户。

## 5. 分应用

- 模式「全部应用」：`addDisallowedApplication(自己)`，避免自我回环
- 模式「仅选中应用」：`addAllowedApplication(each)`；列表为空时**降级为全部应用**并提示
- 两种 API 不能混用（否则 `IllegalArgumentException`），代码里用一个分支保证互斥

## 6. 无障碍自动跳过

```kotlin
// 伪代码：严格限制动作条件（v0.1.6）
if (now - lastClick < 2000) return
if (now - windowStart > 6000) return
val node = bfs(rootInActiveWindow, maxNodes = 400) { n ->
    val t = (n.text ?: n.contentDescription)?.toString()?.trim()
    SkipLabelMatcher.isSkipLabel(t) &&
        isVisible(n) &&
        widthRatio(n) <= 0.6 && heightRatio(n) <= 0.2
}
clickableAncestor(node, maxDepth = 8)?.performAction(ACTION_CLICK)
```

- **触发**：只监听 `TYPE_WINDOW_STATE_CHANGED` 和 `TYPE_WINDOWS_CHANGED`；
  **不监听** `TYPE_WINDOW_CONTENT_CHANGED`（旧版全树遍历导致发烫，AGENTS.md 红线）。
- **轮询**：窗口出现后 6 秒内，首扫延后 150ms；0–600ms 每 250ms、600–3000ms 每 700ms、
  之后每 1500ms；点到即停。晚于 1.5 秒出现的按钮由这段轮询覆盖。
- **扫描**：先做 150 节点 BFS；只有 BFS 被截断（树更深）才在 600ms 后做文本深查，
  且每 1.5 秒最多一次；缓存当前窗口根节点，用 `refresh()` 复用，减少 `rootInActiveWindow` IPC。
- **不做坐标盲点**：文字节点不可点、祖先又是满屏广告容器时，宁可不点，不能点广告本体。
- **同包名**：1.5 秒内的窗口事件会**重启轮询**（v0.1.5 只刷新时间，是漏点根因之一）。
- **更新后修复**：覆盖安装后服务可能「已授权但未绑定」，`A11yController.repairBinding()`
  移除再重新加入；打开 App 时自动尝试一次。
- **文案**：`SkipLabelMatcher` 接受「跳过 / 跳过广告 / 关闭广告 / Skip / Skip Ad」及
  「跳过 5s / 跳过广告10秒 / Skip in 5s」等倒计时变体；拒绝「关闭」「摇一摇」「点击查看」。

**为什么只点这几个词**：这些是用户本来就会手动点的控件，点击它们不改变广告的曝光/点击
统计口径。点广告本体或广告内的"关闭 X"才是伪造点击，本项目不做。

## 7. 通知与常驻

- `startForeground(NOTIF_ID, notification)`，channel 极低优先级、不可响铃
- 通知内容：运行状态 + 今日拦截数 + 「停止」按钮
- 前台服务类型 `specialUse`（targetSdk 34 要求声明类型）

## 8. 开机自启

`BootReceiver` 监听 `BOOT_COMPLETED` → `startForegroundService(ACTION_START)`。
若此前未授权 VPN，`VpnService.prepare()` 返回非 null 表示需要用户交互，
此时**静默放弃**，不弹通知、不崩溃，等用户下次手动开启。

## 9. 目录结构

```
app/src/main/java/com/splashguard/app/
├── core/
│   ├── Prefs.kt              # 唯一配置读写入口
│   └── Const.kt              # 常量（虚拟地址、超时等）
├── net/
│   ├── DnsPacket.kt          # 纯函数：解析/构造 DNS 报文（有单测）
│   ├── PacketBuilder.kt      # 纯函数：IPv4+UDP 封包（有单测）
│   └── SplashGuardVpnService.kt
├── rules/
│   ├── DomainMatcher.kt      # 纯函数：后缀匹配（有单测）
│   └── RuleStore.kt          # 加载内置 + 用户规则
├── log/BlockLog.kt           # 内存环形缓冲，最多 200 条
├── a11y/SkipAccessibilityService.kt
├── boot/BootReceiver.kt
└── ui/{MainActivity,SettingsActivity,AppPickerActivity,*Adapter}.kt
```

## 10. 关键决策记录

| 决策 | 选择 | 理由 |
|---|---|---|
| 只路由 DNS 还是全局路由 | 只路由 DNS | 全局路由要用户态转发所有流量，耗电和吞吐都不可接受 |
| Kotlin + XML 还是 Compose | XML | 减少依赖体积与构建风险，MVP 界面简单 |
| 规则存储 | 文本 + HashSet | 用户可直接编辑，调试成本低 |
| 是否做远程订阅 | 不做 | 隐私原则 + 无维护能力，做空壳会误导 |
| 拦截模式默认 | 0.0.0.0 | 兼容性最好；NXDOMAIN 作为备选解决"卡住不前进" |
| 签名 | 自签 keystore | 侧载场景，无需上架 |
