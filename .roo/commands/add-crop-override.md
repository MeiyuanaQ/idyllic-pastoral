---
description: 为某个 mod 作物添加或调整 CropOverride 覆写配置
argumentHint: {{crop_id}}
allowed-tools: read, write, edit, search, list, bash
---
目标作物：{{crop_id}}

1. 在 `reference/mods-src/` 或 `reference/src/` 中查找该作物的源码，确认：方块类、age 属性、成熟行为（双格/伴生/转换/爬藤/再生）、是否水作物。
2. 决定是否需要在 `CropGrowthConfig.BUILT_IN_OVERRIDES` 新增条目。支持 10 键：daysPerStage, seasons, topBlock, transformBlock, water, doubleAge, freeze, climbBlock, climbSupport, maxClimbHeight。
3. 按 CLAUDE.md §2 红线核对：成熟分支三路径一致、非耕地冻结（freeze/countSuitableDays）、季节解析链、性能（O(1) 缓存命中）。
4. 更新对应单测断言（CropGrowthConfigTest / CropGrowthTrackerTest / MaturitySideEffectTest）。
5. `./gradlew compileJava` + `./gradlew test`。
6. 若涉及实机行为，补充 `plans/in-game-verification-runbook.md` 的回归项。
