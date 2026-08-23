---
description: 修改代码后执行编译与单测验证
allowed-tools: read, search, list, bash
---
运行 `./gradlew compileJava` 确认编译通过；若改动涉及纯函数（CropGrowthTracker 的 simulateGrowth / countSuitableDays / simulateStem / seasonOfDay 等）或配置解析（parseOverride 等），再运行 `./gradlew test` 并保证全部测试通过（既有 62 项全绿）。
若编译/测试失败：分析报错日志并修复，直到通过。最后简要汇报改动与验证结果。
