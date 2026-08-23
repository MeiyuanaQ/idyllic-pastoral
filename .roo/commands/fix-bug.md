---
description: 定位→修复→编译→补测的标准流程
allowed-tools: read, write, edit, search, list, bash
---
1. 复述问题现象与期望行为；检索 `plans/` 确认是否已有相关决策记录（避免重复或冲突修复）。
2. 定位代码：event/CropGrowthHandler、crop/CropGrowthTracker、mixin/*（注意 LevelMixin.onSetBlock 是热路径）。
3. 最小改动修复，对照 CLAUDE.md §2（机制红线）与 §3（性能红线：无 O(N²)、无逐天循环、无热路径分配/重复 ES API 调用）。
4. `./gradlew compileJava`；改动纯函数/补涨逻辑后 `./gradlew test`。
5. 更新对应单测或 `plans/in-game-verification-runbook.md` 验证项。
