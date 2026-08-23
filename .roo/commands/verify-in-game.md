---
description: 按 runbook 分析实机验证结果与日志
allowed-tools: read, search, list, bash
---
按 `plans/in-game-verification-runbook.md` 的 P0–P8 对照用户回传的实机结果。

若某步骤失败：
1. 复现步骤 + 期望行为 vs 实际行为。
2. 结合 `docs/environment/debug.log`（debugLogging=true 时的 `[CropTracker]` / `[CropHandler]` 行）与 `crash-report/` 分析定位。
3. 定位到代码后走 fix-bug 流程（修复→compileJava→test→更新验证项）。

验证工具：`/eclipticseasons solar add/set N` + 等待 10~15 秒周期或重进区块。
