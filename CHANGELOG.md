# 变更记录

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
