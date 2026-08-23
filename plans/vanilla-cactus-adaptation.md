# 适配原版作物仙人掌

> 状态:已完成(compileJava + test 全绿,已备份)。
> 目标:将原版 `minecraft:cactus` 纳入日历生长体系,按「甘蔗镜像」处理。

## 结论

仙人掌与甘蔗同构:高度制生长(限高 3、追踪根部、非适季冻结),差异仅在生存规则(沙地/横向无实心与岩浆邻居)由原版 `canSurvive` 自行兜底。`AGE_15` 仅原版随机进度、不影响外观,日历接管后忽略。

## 决策

- 分类 = HEIGHT(根部追踪);非耕地(冻结,不变异),镜像甘蔗。
- 季节默认走 ES 解析链 → 未打标签 `spring,autumn`;配置页 `Kind.FREEZE, daysPerStage=3`。
- 只取消 `randomTick`,不动 `tick()`(生存自毁)。

## 改动清单

1. `CropKindResolver` — `computeHeight` 增 `CactusBlock → HeightCrop(3, CACTUS, false)` + `CACTUS_MAX_HEIGHT`。
2. `CropClassifier` — `isGrowableCrop`/`isNonArableBlock` 增 `instanceof CactusBlock`;`getCropAge/getCropMaxAge/getCropStateForAge` 镜像甘蔗分支。
3. `HeightStrategy` — 增 `getCactusHeight/growCactus/onCactusHarvest/onCactusBonemeal`。
4. `CropGrowthTracker` — 增 `onCactusHarvest/onCactusBonemeal` 委托 + `catchUpInternal` 仙人掌分支。
5. `LevelMixin` — 增「根部追踪 + 中部收获重置 + 上方放置=骨粉回推」三处。
6. `EntryStore.getOrCreate` — 增根部下探 + 高度回推 plantedDay。
7. `CropGrowthHandler.onCropGrowPre` — 增 `CactusBlock` 早退。
8. 新 `CactusBlockMixin` + 注册 `pastoralcraft.mixins.json`。
9. `CropGrowthConfig` — 增 `crop("minecraft","cactus",Kind.FREEZE,3,"","minecraft:cactus")`。
10. lang zh_cn/en_us 增 `crops.minecraft.cactus` + tooltip。
11. 测试 — `CropKindResolverTest` 增 cactus 分类断言。

## 验证

- `./gradlew compileJava` 通过;`./gradlew test` 全绿。
- `& .\tools\backup.ps1 -Title "适配原版作物仙人掌"`。
