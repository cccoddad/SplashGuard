# AGENTS.md — SplashGuard AI 工作约定

> 本文件是 SplashGuard 仓库对 AI 助手的**单一事实来源**。
> 任何新对话、任何模型（Codex / Claude / Cursor / opencode）在修改代码前都必须先读本文件。
> 完整交接背景见 `docs/HANDOFF-PROMPT.md`；当前问题清单与优化路线见 `docs/project-review.md`。

## 1. 项目是什么

SplashGuard 是一个**本地优先的 Android 广告拦截器**，当前主要覆盖开屏广告，并向部分应用内广告（广告 SDK 域名、弹窗/插屏关闭按钮）扩展。

- 包名：`com.splashguard.app`
- 技术栈：Kotlin + XML，Gradle 8.14.3，AGP 8.10.1
- `minSdk 26` / `targetSdk 34` / `compileSdk 36`
- 主要验证设备：小米 13 Pro（HyperOS）
- 两条互不依赖的防线：

```text
DNS 层：VpnService 只接管 DNS（虚拟 DNS + 常见公共 DNS 的 /32）
       命中规则 → 本机返回 0.0.0.0 / NXDOMAIN
       未命中   → 转发上游 DNS

无障碍层：冷启动窗口切换后 1.5s 内扫描
         只点小尺寸的「跳过 / 关闭广告 / Skip」控件
```

## 2. 硬约束（不可协商）

1. **不 root、不越狱、不装 Xposed / LSPosed**。只用 Android 公开 API。
2. **数据全本地**：无服务器、无账号、无统计 SDK、无崩溃上报、无远程配置。
   唯一出站流量是未拦截域名的 DNS 转发。
3. **不做 HTTPS 中间人**：不装根证书、不解密流量、不按 URL 路径过滤。
4. **不点广告本体**：无障碍只点用户本来就能点的「跳过/关闭广告」控件，绝不伪造曝光或点击。
5. **不越界**：不破解会员、不去付费墙、不做广告屏蔽以外的“破解”能力。
6. **保持轻量**：APK 不超过 8 MB；不引入 Room / Retrofit / 协程全家桶等重型依赖。
7. **结论必须有证据**：命令输出、日志、截图、单测至少给一样；没验证就写“未验证”，
   严禁用“应该可以”“理论上支持”糊弄。
8. **不提交敏感文件**：`keystore/`、`keystore.properties`、`local.properties`、
   `dist/`、`*.log`、`splashguard-*.txt` 都不得进版本库。

## 3. 架构红线

- **DNS 只路由 DNS 目标地址**，不要改成全局 VPN / 全局代理。非 DNS 流量必须直连。
- **无障碍默认关闭**；只匹配固定文案；文字节点和最终点击节点都要过尺寸闸
  （宽 ≤ 60% 屏宽、高 ≤ 20% 屏高）；不监听 `TYPE_WINDOW_CONTENT_CHANGED`。
- **iOS 只提供 DNS 描述文件**，不做 `NEPacketTunnelProvider` App，不承诺系统级拦截。
- **不引入数据库**。存储只用三处：APK 内规则 txt、SharedPreferences、内存 + TSV 日志。
- DNS 报文、封包、规则匹配保持**纯 Kotlin**，不要塞 Android 依赖，保证可 JVM 单测。
- 规则更新必须**用户可见、可回滚**；不做静默云端同步。远程导入必须用户主动触发并带校验和。

## 4. 当前目标与优先级

### 目标 A：开屏广告“几乎不出现”

- 上限由 App 自己的降级策略决定，**不要承诺 100% 零展示**。
- 可做：提高 DNS 命中率、缩短点击延迟、按 App 选择 0.0.0.0 / NXDOMAIN、平衡轮询密度与耗电。

### 目标 B：减少应用内广告

- 只做三条安全路径：
  1. 扩充“广告 SDK 独有域名”规则（不影响主站内容的域名）；
  2. 无障碍识别弹窗层的「关闭 / ×」按钮（必须小尺寸、可一键关闭）；
  3. 按 App 的拦截强度开关。
- 信息流广告只能“明显减少”，不能承诺清除；同域 CDN 规则必须标 `[高风险]`，默认关闭。

### 当前优先修复（来自 `docs/project-review.md`）

1. 常见公共 DNS 的 `/32` 路由会黑洞非 DNS 流量：移除，或实现完整 UDP/TCP 转发。
2. TCP 53 与 4 KB 响应缓冲：补 TCP 处理 / 65535 缓冲 / TC 位 / TTL 感知缓存。
3. 内置规则不可单独关闭、没有 allowlist：加高优先级 allowlist 与规则分组开关。
4. `prefs.enabled` 混用“用户意图”和“运行态”：拆成 `desiredEnabled` / `isRunning`。
5. 无障碍没有 App 内总开关：加开关，并同步 `accessibility_service_config.xml` 的 eventTypes。
6. A/AAAA/其他 qType 返回语义、规则校验、分应用防抖、CI/组件测试。

## 5. 工作方式

1. **动手前先说清楚**：要改什么、为什么、预期效果；不要闷头改一堆再汇报。
2. **先定位再修**：读日志、读代码、复现问题；禁止“试试看”。
3. **每一步给可验证结果**：命令、输出、截图、logcat 至少给一样。
4. **改完必须回归**：
   - `./gradlew test`（27 项单测，后续会增加）
   - 无障碍 7 场景：`skip` / `skipcount` / `delay` 点中；`huge` / `other` / `ad` 不点；`shake` 只点跳过
   - 一次真实冷启动；性能改动还要测**完全空闲**状态
5. **没验证不能说“已修复”**。可以说“改好了，但还没在真机验证”。
6. **改版本号时**同步 `CHANGELOG.md`、`docs/real-device-guide.md`、README。
7. 需求与硬约束冲突时，**停下来解释冲突**，不要自行放宽约束。
8. **每完成一次较大的优化/修复**（功能、性能、规则库、架构调整等），必须通过 Git 保存并推送：
   先跑回归测试，再 `git add` 本次相关文件，`git commit`，`git push origin main`。
   不要积累多个大改动只留在本地；提交前确认没有 `keystore/`、`local.properties`、`dist/`、日志或诊断报告。

## 6. 代码约定

- Kotlin + XML 视图；不要引入 Compose。
- 沿用现有代码风格；中文注释可以接受。
- 不新增依赖，除非能说明体积、构建失败面和收益。
- 不新增权限；分应用列表优先用 `<queries>`，不要轻易加 `QUERY_ALL_PACKAGES`。
- 不把网络、日志、规则逻辑写进 Activity；保持可测试的纯函数。
- 变更涉及隧道/无障碍/规则时，必须考虑失败回退：宁可少拦，不能断网。

## 7. 已知坑（不要重犯）

1. `tun.read()` 返回 0 时**不能 `continue`**，必须让出 CPU 并计数；否则空闲时烧满一个核。
2. `<application>` 必须声明 `android:name=".App"`，否则 `Application.onCreate` 是死代码。
3. 无障碍向上找可点击父节点时，**父节点也要过尺寸闸**，否则会点掉整块广告跳落地页。
4. 无障碍不要监听 `TYPE_WINDOW_CONTENT_CHANGED`；滚动/视频/打字会触发全树遍历。
5. 同包名开屏 Activity 与主页 Activity 要按 1.5 秒间隔判断“又一次启动”，否则漏点。
6. 验收不要用 `ads.pangle.io` 这类广告接口域名；用 `example.com` 做对照。
7. 拦截响应 TTL 60 秒；关闭开关后同域名仍失败是正常缓存，不是隧道没拆干净。
8. `MaterialAlertDialogBuilder.setView(ScrollView)` 长文本要重写 `onMeasure` 限高。
9. 模拟器上 `am force-stop` 会重置无障碍授权；`dumpsys cpuinfo` 受 Load 影响，不可信。
10. 更多细节见 `docs/HANDOFF-PROMPT.md` 第六节。

## 8. 常用命令

```bash
# 单元测试
./gradlew test

# Release APK
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk

# Windows 下没有 bash 时用
.\gradlew.bat test
.\gradlew.bat assembleRelease
```

> 如果 wrapper 下载 Gradle 失败，使用本机缓存的 Gradle 8.14.3
> （路径见 `docs/HANDOFF-PROMPT.md` 第九节）。这是环境问题，不是代码失败。

提交前检查：

```bash
git status
git ls-files | findstr /i "keystore local.properties dist"
```

每次较大的优化/修复完成后：

```bash
git add <本次相关文件>
git commit -m "feat/fix/docs: ..."
git push origin main
```

## 9. 沟通偏好

- 用中文沟通；**先结论，再说原因**。
- 涉及取舍时把代价讲清楚，让用户自己选。
- 不写空话，不承诺做不到的事；做不到就直说。
- 用户是真实使用者，会自己装 APK 做真机验证；优先给可操作步骤和可验证证据。
