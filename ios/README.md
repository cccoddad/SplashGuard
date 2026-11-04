# iOS 端：系统级 DNS 过滤（能力上限方案）

> 一句话结论：**iOS 上没有任何合法途径拦截其他 App 的原生开屏广告界面**。
> 本目录交付的是 iOS 唯一可用的那一条路——用配置描述文件把全系统 DNS 指向一个
> 过滤型加密解析器（DoH/DoT）。它能让「广告域名解析失败」，从而让一部分开屏广告
> 直接跳过或露出兜底页，但**覆盖率和可控性都明显低于 Android**，差距来源见下文。

---

## 1. 为什么 iOS 只能做到这一步

| 想要的能力 | iOS 是否可行 | 卡在哪 |
|---|---|---|
| 改全系统 DNS | ✅ 描述文件 `com.apple.dnsSettings.managed` | 无 |
| 分应用生效 | ❌ | 系统 DNS 是全局设置，没有 per-app 接口 |
| 自定义规则、看拦截日志 | ❌（本机） | 描述文件只是转发，规则在**上游解析器**里；要看日志只能去服务商后台 |
| 自动点「跳过」 | ❌ | iOS 没有跨 App 的辅助功能 / UI 自动化 API。Shortcuts 的动作库里没有"读取其他 App 界面""点击屏幕坐标"这类动作，App 自动化只能"打开 App" |
| 拦 HTTPS 内容 / MITM | ❌ | ATS + 证书固定；装根证书也无法解密固定证书的流量 |
| Safari 内容拦截器 | ⚠️ 只对 Safari 里的网页有效 | 对原生 App 开屏零作用 |
| 本地 VPN（`NEPacketTunnelProvider`）自建过滤 | ⚠️ 理论可行，实际不可用 | 需要 Apple 单独审批 Network Extension entitlement，个人开发者申请基本被拒；免费签名 7 天过期，无法日常使用 |

所以本目录**不提供 App、不提供代码注入、不承诺系统级拦截**，只提供描述文件。

---

## 2. 交付内容

| 文件 | 说明 |
|---|---|
| `SplashGuard-DNS-DoH.mobileconfig` | DoH 预设，指向 AdGuard DNS（公共过滤解析器，免账号） |
| `SplashGuard-DNS-DoT.mobileconfig` | DoT 预设，同上，走 853 端口 |
| `make_profile.py` | 生成器：可换服务商、换自己的 DoH 服务、生成 NextDNS 版本 |
| `check_profile.py` | 结构校验脚本（无需 Mac / iPhone 即可跑） |

生成的文件只包含 Apple 定义的标准键，未签名的描述文件在 iOS 上可手动安装。
两个 `.mobileconfig` 的 UUID 由 PayloadIdentifier 派生（uuid5），**同一份配置反复生成字节一致**，
便于 Git diff 和篡改核对。

---

## 3. 安装（3 分钟）

**方式 A：手机直接装（推荐）**

1. 把 `SplashGuard-DNS-DoH.mobileconfig` 传到手机：AirDrop、微信/QQ 传文件、iCloud 云盘、邮件附件都行；
2. 在「文件」App 里点它 → 系统提示"已下载描述文件"；
3. 设置 → 通用 → **VPN 与设备管理** → 已下载的描述文件 → 右上角「安装」→ 输入锁屏密码 → 再点一次「安装」；
4. 装完不需要开任何开关，DNS 立即生效。

**方式 B：Mac + Apple Configurator**（批量或多台设备时）

```bash
# 需要 macOS + Apple Configurator 2
open -a "Apple Configurator 2" SplashGuard-DNS-DoH.mobileconfig
```

**验证是否生效**

- 浏览器打开 `https://adguard-dns.io/zh_cn/test.html`，看"广告拦截"一项是否为绿色通过；
- 或打开 `https://dnsleaktest.com` 跑一次 extended test，看解析器归属是否变成所选服务商；
- 或直接冷启动一个开屏广告密集的 App，对比装前/装后的启动画面。

**卸载**

设置 → 通用 → VPN 与设备管理 → 选中 `SplashGuard DNS (...)` → 移除描述文件。立即恢复运营商/路由器 DNS。

---

## 4. 换服务商 / 用自己的 DoH

公共过滤解析器只有"通用广告域名"这一层能力，且会把你的全部 DNS 查询交给第三方——
这是这条路线**必须接受的隐私权衡**（Android 端的本地规则不存在这个问题）。

```bash
# 换成 NextDNS（可自选拦截列表、可在后台看查询日志，免费额度 30 万次/月）
python ios/make_profile.py --preset nextdns --nextdns-id <你的ID> --out ios/NextDNS.mobileconfig

# 换成你自己搭的 DoH（例如 AdGuard Home / dnsdist / mosdns）
python ios/make_profile.py \
  --doh-url https://dns.your-domain.com/dns-query \
  --server-name dns.your-domain.com \
  --label "My DNS" \
  --out ios/MyDNS.mobileconfig

# 只做 DoT
python ios/make_profile.py --preset adguard-dot --out ios/MyDoT.mobileconfig
```

自建 DoH 是这条路线的最优解：规则、日志都在你自己机器上，隐私边界等于 Android 端。
代价是需要一台公网服务器 + 域名 + 证书（AdGuard Home 自带 DoH，配置约 10 分钟）。

---

## 5. 与 Android 端的真实差距

| 维度 | Android（本项目 App） | iOS（本目录描述文件） |
|---|---|---|
| 拦截位置 | 本机 VPN 内，规则在本地 | 第三方/自建解析器，规则在远端 |
| 分应用 | ✅ 黑白名单 | ❌ 全局 |
| 规则自定义 | ✅ 内置库 + 自定义 + 导入导出 | ⚠️ 取决于上游是否支持 |
| 拦截日志 | ✅ 本机最近 200 条 | ❌ 本机无；看服务商后台 |
| 补 HTTPDNS 盲区 | ✅ 无障碍点「跳过」 | ❌ 无任何手段 |
| 需常驻 VPN | 是（只路由 DNS，耗电近 0） | 否 |
| 隐私 | 数据不出本机 | DNS 查询经过第三方（除非自建） |
| 覆盖率（体感） | 较高 | 中等偏低，且因 App 而异 |

**差距的根因**：Android 上有第二个合法通道（无障碍 UI 层）可以补网络层的漏，
而 iOS 把这条路彻底封死了。所以「iOS 版效果不如 Android」不是实现问题，是平台边界。

---

## 6. 已知无效场景（别期待这些被拦住）

- App 用 **HTTPDNS**（阿里云/腾讯云 SDK）或直接写死 IP 请求广告 → 不经系统解析器，DNS 层完全看不到；
- App **预缓存**了开屏素材，启动时直接渲染本地图片；
- 开屏是 **OpenGL / Metal 自绘**，或广告位本身由主 feed 接口下发（不是独立域名）；
- 部分 App 在 DNS 失败后**降级到兜底广告**（自售位），画面会变但仍有广告；
- 使用其他**代理/VPN 类 App** 时，它们的 DNS 设置可能覆盖描述文件。

这些在 `docs/feasibility.md` 里已作为平台限制列出，不是本方案能修的 bug。

---

## 7. 验证状态（如实说明）

| 项 | 状态 |
|---|---|
| plist 结构 / 必需键 / UUID 唯一性 | ✅ 已用 `check_profile.py` + `plistlib` 校验通过 |
| 生成确定性（重复生成字节一致） | ✅ 已验证 |
| 真机安装与拦截效果 | ⚠️ **未验证**——本机没有 iPhone，也没有 Apple 开发者环境 |
| 与具体 App 的兼容性 | ⚠️ 需你在自己设备上实测 |

```bash
python ios/check_profile.py ios/SplashGuard-DNS-DoH.mobileconfig
```

装到手机后如果出现"描述文件安装失败"，通常是文件在传输中被改成了 `.txt` 或加了 BOM——
重新用 AirDrop / iCloud 传原始文件即可。
