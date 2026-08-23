---
description: 为纯函数/配置解析编写 JUnit 5 单测，覆盖边界
allowed-tools: read, write, edit, search, list, bash
---
为指定代码编写 JUnit 5 单元测试（项目为纯 JUnit、无 Minecraft 依赖，沿用 CropGrowthTrackerTest / MaturitySideEffectTest / CropGrowthConfigTest / SeasonTagResolverTest 的风格）：

- 纯函数（simulateGrowth / countSuitableDays / simulateStem / seasonOfDay 等）：覆盖全适季 O(1) 快路径、不适季边界（季界 / 整周期 / 负数回拨 / termLength<=0 / clamp）、跨调用确定性、越界守卫。
- 配置解析（parseOverride / validateSeasonString / validateDirections）：覆盖 10 个覆写键、seasons 下划线格式、非法输入、内置覆写优先级。
- 开始前先检索 `plans/` 与该函数既有测试，避免重复或与决策冲突。

改后运行 `./gradlew test` 保证全绿，汇报新增用例数。
