# Claude Code Instructions

This repository uses `AGENTS.md` as the single source of truth.
Read it before making any change.

@AGENTS.md

## Claude-specific notes

- 用中文沟通；先给结论，再解释原因。
- 修改前先说明「要改什么 / 为什么 / 预期效果」，不要一次性改很多再汇报。
- 没有验证过的结论必须标“未验证”，不要用“应该可以”“理论上支持”。
- 涉及 `VpnService`、无障碍、规则库、`AndroidManifest.xml` 的改动，先对照 `AGENTS.md` 的硬约束和架构红线。
- 每完成一次较大的优化/修复，必须通过 Git 提交并 `git push origin main`，不要只留在本地。
- 不要提交 `keystore/`、`keystore.properties`、`local.properties`、`dist/`、日志或诊断报告。
