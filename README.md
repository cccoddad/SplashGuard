# SplashGuard

Android 本地开屏广告拦截器。**不 root、不越狱、不上传任何数据。**

调 VPN 只接管 DNS 查询（其他流量不过隧道），让广告域名解析不出来；
再叠加一个可选的无障碍服务，自动点用户本来就能点的「跳过」按钮。

---

## 一、它能做到什么，不能做到什么

**先说不能的**（这是方案诚实性的底线）：

| 做不到 | 原因 |
|---|---|
| 拦截所有 App 的开屏广告 | 大厂 App 普遍用 HTTPDNS（IP + 加密查询），绕过系统解析器 |
| 拦截信息流 / 详情页里的广告 | 那是 HTTPS 内容的一部分，DNS 层无法区分 |
| 拦截游戏的自绘开屏 | OpenGL/SurfaceView 无节点树，无障碍也读不到 |
| 拦截已缓存到本地的广告素材 | 不走网络就不会被拦 |
| iOS 系统级拦截 | iOS 无跨 App 自动化 API，只能做 DNS 描述文件 |

**能做到的**：

- 走系统 DNS 中小 App 的开屏广告 → 大概率直接消失或变成极短的兜底页
- 大厂 App 的「跳过」按钮 → 冷启动 6 秒内自动点掉
- 分应用控制，不影响银行 / 支付类 App
- 拦截记录全程本地可见，随时可以关掉恢复原状

详细论证见 [docs/feasibility.md](docs/feasibility.md)。

---

## 二、构建

### 环境要求

| 组件 | 版本 | 备注 |
|---|---|---|
| JDK | 17 或 21 | 本项目用 21 验证通过 |
| Android SDK | platform 36 + build-tools 36.0.0 | `compileSdk = 36` |
| Gradle | 8.14.3 | 已带 wrapper |
| AGP / Kotlin | 8.10.1 / 2.1.20 | |

### 命令

```bash
# 1. 配置 SDK 路径（或设 ANDROID_HOME 环境变量）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. 首次需要生成签名密钥（已生成过可跳过）
mkdir -p keystore
keytool -genkeypair -v -keystore keystore/splashguard.jks -alias splashguard \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <你的密码> -keypass <你的密码> -dname "CN=SplashGuard, O=Personal, C=CN"

cat > keystore.properties <<'EOF'
storeFile=keystore/splashguard.jks
storePassword=<你的密码>
keyAlias=splashguard
keyPassword=<你的密码>
EOF

# 3. 跑单元测试
./gradlew test

# 4. 出包
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

> 国内网络如果依赖下载超时，`settings.gradle.kts` 已配好阿里云镜像，
> `gradle.properties` 里也强制了 IPv4。仍失败时可用 `--offline`（需先成功下载一次）。

### 安装

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

或把 APK 传到手机点击安装（需允许「安装未知来源应用」）。

---

## 三、首次使用（3 步）

1. **打开开关** → 系统弹「连接请求」→ 允许。
   状态栏出现钥匙图标 = 隧道已建立。
2. **（可选）自动跳过** → 首页「自动跳过」→ 系统无障碍设置里打开 SplashGuard。
   只点文本为「跳过 / 关闭广告 / Skip」的小控件，不点广告本体。
3. **（可选）开机自启** → 设置页默认已开。建议再到系统设置里把本应用加入
   「电池优化白名单」，否则部分 ROM 会在后台杀掉它。

**验证是否真的生效**：随便打开一个 App，回首页看「最近拦截」——
出现域名记录就是拦到了。看不到记录说明这个 App 用的是 HTTPDNS，需要靠无障碍通道。

---

## 四、iOS 端（能力上限方案，只有 DNS）

iOS 没有跨 App 的 UI 自动化 API，也没有可自建规则的本地 VPN（Network Extension
entitlement 个人申请基本被拒），所以**这里不提供 App，只提供一份系统级 DNS 描述文件**。
详见 [`ios/README.md`](ios/README.md)。

```bash
python ios/make_profile.py --preset adguard-doh      # 生成 DoH 描述文件
python ios/make_profile.py --preset adguard-dot      # 生成 DoT 变体
python ios/make_profile.py --preset nextdns --nextdns-id <ID>
python ios/check_profile.py                          # 结构校验（无需 Mac/iPhone）
```

装法：把 `.mobileconfig` AirDrop 到手机 → 设置 → 通用 → VPN 与设备管理 → 安装。

| iOS 能做 | iOS 不能做 |
|---|---|
| 全系统 DNS 指向 DoH/DoT 过滤解析器 | 分应用、本地自定义规则、本机拦截日志 |
| 卸载干净、零耗电、不占 VPN 槽位 | 自动点「跳过」（Shortcuts 看不到别人的界面） |
| 换自建 DoH 后隐私边界等同 Android | 拦 HTTPDNS / 已缓存素材 / 自绘开屏 |

**覆盖率预期：iOS 明显低于 Android**，因为 Android 上还有无障碍这条第二通道，
iOS 没有。这是平台边界，不是实现缺陷。

---

## 五、目录结构

```
SplashGuard/
├── README.md                  ← 本文件
├── docs/
│   ├── feasibility.md         可行性报告（Android/iOS 能力边界）
│   ├── PRD.md                 用户故事 + 验收标准
│   ├── tech-design.md         技术方案 + 关键决策
│   ├── testing.md             测试用例（单元 + Android 真机 + iOS 描述文件）
│   ├── acceptance-emulator.md 模拟器验收报告（A–H 全项 + 4 个缺陷修复记录）
│   ├── evidence/              验收证据：截图、UI 树、内存快照
│   └── privacy.md             隐私说明
├── splashtest/                验收用「假开屏」App（5 种界面，验无障碍点击与防误点）
├── ios/                       iOS 只有 DNS：描述文件 + 生成器 + 校验脚本
│   ├── README.md              装法、换服务商、能/不能做什么
│   ├── make_profile.py        生成 .mobileconfig（UUID 可复现）
│   ├── check_profile.py       结构校验，无用例依赖，可直接跑
│   ├── SplashGuard-DNS-DoH.mobileconfig
│   └── SplashGuard-DNS-DoT.mobileconfig
├── app/src/main/
│   ├── assets/rules/splash_ads.txt   内置广告域名种子库（可改）
│   ├── java/com/splashguard/app/
│   │   ├── App.kt
│   │   ├── core/       Const.kt / Prefs.kt
│   │   ├── net/        DnsPacket.kt / PacketBuilder.kt / SplashGuardVpnService.kt
│   │   ├── rules/      DomainMatcher.kt / RuleStore.kt
│   │   ├── log/        BlockLog.kt
│   │   ├── a11y/       SkipAccessibilityService.kt
│   │   ├── boot/       BootReceiver.kt
│   │   └── ui/         MainActivity / SettingsActivity / AppPickerActivity / Adapters
│   └── res/            布局、主题、无障碍配置
└── app/src/test/       DNS 报文、封包、规则匹配的 JVM 单元测试
```

---

## 六、关键决策记录

| # | 决策 | 备选 | 为什么这么选 |
|---|---|---|---|
| 1 | **只把 DNS 目标地址加进路由表** | 全局路由所有流量 | 全局路由要在用户态转发每个 IP 包，耗电和吞吐都不可接受。只路由 DNS 时非 DNS 流量根本不进进程 |
| 2 | **VpnService + DNS 过滤** 作为主体 | 无障碍为主 / 私有 DNS 为主 | 网络层无视 App UI 改版，长期可维护；无障碍只能补盲区 |
| 3 | 默认返回 **0.0.0.0**，NXDOMAIN 作为备选 | 只做 NXDOMAIN | 0.0.0.0 兼容性更好；个别 App 在 0.0.0.0 下会卡住，留开关给用户 |
| 4 | 规则用 **文本 + HashSet 后缀匹配** | 前缀树 / 正则 | 20 万条也就几 MB，后缀匹配 O(标签数)。MVP 阶段上 Trie 是过度设计 |
| 5 | **不做远程规则订阅** | 云端下发规则 | 隐私原则要求全本地；没有维护订阅源的能力，做空壳反而误导 |
| 6 | 无障碍**只匹配 5 个固定文本**，且限制控件尺寸 | 按坐标/颜色找跳过按钮 | 宁可漏点不能误点。误点广告位 = 伪造点击，是红线 |
| 7 | Kotlin + **XML 视图** | Jetpack Compose | 减少依赖体积与构建失败面，界面本身很简单 |
| 8 | `targetSdk = 34` | 35/36 | 侧载场景不需要满足 Play 要求；34 避免被迫处理 edge-to-edge 布局 |
| 9 | 关掉 R8 混淆 | 开启 | MVP 优先可调试性，崩溃堆栈可读 |
| 10 | 自签 keystore，侧载分发 | 上架商店 | 广告拦截类在 Play 和国内商店都过不了审，无障碍 API 会被认定滥用 |
| 11 | iOS **只给描述文件，不做 App** | 做带 NEPacketTunnelProvider 的 iOS App | 该 entitlement 个人申请基本被拒，免费签名 7 天过期，做出来也无法日常用；描述文件是 iOS 唯一稳定可用的路径 |
| 12 | iOS 描述文件 UUID 用 **uuid5 从标识符派生** | 每次随机 uuid4 | 随机 UUID 会让每次生成的字节都不同，Git diff 全红、也无法核对分发的文件有没有被改 |

---

## 七、规则怎么加

内置规则在 `app/src/main/assets/rules/splash_ads.txt`，也可以直接在 App 内「设置 →
自定义规则」里加，保存即热生效，不用重启隧道。

```
# 一行一条，后缀匹配
example.com          # 命中 example.com 和 a.b.example.com
||gdt.qq.com^        # 兼容 AdBlock 写法
```

**加规则前务必想清楚**：`kwaicdn.com` 这类域名既是广告 CDN 又是视频 CDN，
封了会导致视频放不出来。所以种子库里标了 `[高风险]` 的默认全部注释掉。

---

## 八、已知问题

1. **HTTPDNS 绕过**：国内大厂 App 用阿里云/腾讯 HTTPDNS，DNS 层完全无效。
   这不是 bug，是协议层面的硬限制，只能靠无障碍通道补。
2. **部分 ROM 后台限制**：MIUI / ColorOS / EMUI 需要在系统设置里允许自启动，
   否则开机恢复会失败。
3. **同时只能有一个 VPN**：开启本应用会顶掉其他 VPN 应用（系统限制）。
4. **日志里的「未知应用」**：`getConnectionOwnerUid` 在部分机型上拿不到归属，
   显示为未知，不影响拦截本身。
5. **重启后可能丢 VPN 授权**：部分系统/机型重启后会把 VPN 授权重置。此时不会静默失败，
   而是发一条「拦截没有自动恢复」的通知，点一下即可重新授权。
6. **模拟器上 0.0.0.0 显示为 127.0.0.1**：这是 Android 解析层把 0.0.0.0 当归环处理，
   效果是连接快速失败（比 NXDOMAIN 更快），符合预期。

> 模拟器验收（API 26 / API 34，A–H 全项）见 [docs/acceptance-emulator.md](docs/acceptance-emulator.md)，
> 其中修复了 1 个 P0（隧道重启后 DNS 全断）、2 个 P1（分应用死锁、开机静默失败）。

---

## 九、隐私

不收集、不上传、不统计。唯一出站流量是未拦截域名的 DNS 转发（发往你自己配的上游）。
详见 [docs/privacy.md](docs/privacy.md)。

## 十、路线图

- [ ] v0.2 按 App 统计拦截命中率，辅助判断该不该给某个 App 开
- [ ] v0.3 规则分组开关（穿山甲 / 优量汇 / 快手 分开控制）
- [ ] v0.4 拦截 TCP 53 与常见 DoH 端点（目前只拦 UDP 53）
- [ ] v1.0 iOS 描述文件生成器（DNS over HTTPS，附局限说明）
