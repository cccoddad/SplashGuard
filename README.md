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

## 四、目录结构

```
SplashGuard/
├── README.md                  ← 本文件
├── docs/
│   ├── feasibility.md         可行性报告（Android/iOS 能力边界）
│   ├── PRD.md                 用户故事 + 验收标准
│   ├── tech-design.md         技术方案 + 关键决策
│   ├── testing.md             测试用例（单元 + 真机）
│   └── privacy.md             隐私说明
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

## 五、关键决策记录

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

---

## 六、规则怎么加

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

## 七、已知问题

1. **HTTPDNS 绕过**：国内大厂 App 用阿里云/腾讯 HTTPDNS，DNS 层完全无效。
   这不是 bug，是协议层面的硬限制，只能靠无障碍通道补。
2. **部分 ROM 后台限制**：MIUI / ColorOS / EMUI 需要在系统设置里允许自启动，
   否则开机恢复会失败。
3. **同时只能有一个 VPN**：开启本应用会顶掉其他 VPN 应用（系统限制）。
4. **日志里的「未知应用」**：`getConnectionOwnerUid` 在部分机型上拿不到归属，
   显示为未知，不影响拦截本身。

---

## 八、隐私

不收集、不上传、不统计。唯一出站流量是未拦截域名的 DNS 转发（发往你自己配的上游）。
详见 [docs/privacy.md](docs/privacy.md)。

## 九、路线图

- [ ] v0.2 按 App 统计拦截命中率，辅助判断该不该给某个 App 开
- [ ] v0.3 规则分组开关（穿山甲 / 优量汇 / 快手 分开控制）
- [ ] v0.4 拦截 TCP 53 与常见 DoH 端点（目前只拦 UDP 53）
- [ ] v1.0 iOS 描述文件生成器（DNS over HTTPS，附局限说明）
