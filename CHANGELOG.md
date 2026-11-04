# 变更记录

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
