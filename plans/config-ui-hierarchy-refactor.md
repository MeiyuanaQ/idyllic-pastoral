# 配置结构与 UI 层级重构

> 状态:已完成(第二轮:全量作物覆盖 + 按模组分组 + 番茄整合 + 未成熟茎生长机制)。
> 目标:配置按集合分组、作物配置统一化 + 差异化专属项、两级 UI、全量已知作物覆盖。

## 目标

1. debug 开关等按集合聚合归类(分组 section)。
2. 统一管理作物配置:内置作物从硬编码字符串改为结构化类型字段;保留一行式自定义覆写兜底。
3. 按各作物差异化机制建立专属细致配置项。
4. 「作物列表 → 单个作物专属配置页」两级 UI。

## 决策(用户拍板)

- 直接重构,不迁移旧配置(旧扁平键作废,NeoForge 按新结构重建默认值)。
- 全量作物清单覆盖(原版 + FD + Supplementaries + AHP + KC),按所属模组分组。
- FD 番茄三块(budding_tomatoes / tomatoes / tomatoes_on_rope)单页整合。
- 西瓜 = `minecraft:melon_stem`,南瓜 = `minecraft:pumpkin_stem`(用户口径)。
- `minecraft:pitcher_crop` 译为「瓶子草」(非「猪笼草」)。

## 配置结构(第二轮)

```
[general]   daysPerStage / unsuitableMutateChance(0.20) / unsuitableGrowChance(0.40) / 补涨×3 / defaultUntaggedSeasons
[stem]      daysPerFruit(3) / fruitDirections(east,north) / unsuitableMutateChance(0.20) / unsuitableFruitChance(0.20) / unsuitableGrowChance(0.00)
[debug]     13 个调试开关
[crops]     customOverrides + 按模组分类:
  [crops.minecraft]             wheat/carrots/potatoes/beetroots/torchflower/pitcher_crop(DOUBLE)/nether_wart/cocoa/sweet_berry_bush/sugar_cane/kelp/melon_stem(STEM)/pumpkin_stem(STEM)
  [crops.farmersdelight]        cabbages/onions/tomato(整合3块)/rice(COMPANION)/rice_panicles(FREEZE)
  [crops.supplementaries]       flax(DOUBLE+FREEZE)
  [crops.adorablehamsterpets]   sunflower_block(REGROW, seasons=spring_autumn)
  [crops.kaleidoscope_cookery]  tomato_crop/chili_crop/lettuce_crop/rice_crop(segmented)
```

## 字段规范(第二轮)

- 普通作物:`daysPerStage` + `seasons` + `unsuitableMutateChance`(非适季变草)+ `unsuitableGrowChance`(非适季生长)+ 结构 trait(FREEZE/DOUBLE/COMPANION/TRANSFORM/CLIMB)。
- 茎类作物:`daysPerStage` + `seasons` + `daysPerFruit` + `fruitDirections` + `stemMutateChance`(茎非适季变草)+ `stemFruitChance`(成熟茎非适季结果)+ `stemGrowChance`(未成熟茎非适季生长)。
- 概率字段哨兵 -1/0/空 = 回退全局;TOML comment 与 UI tooltip 均标注全局默认值。

## 运行时契约(第二轮)

- `CropOverride` 17 字段 + Builder;保留 10 参便捷构造(测试兼容)。
- `CropPage.buildOverrides()` 返回 `Map<ResourceLocation, CropOverride>`(番茄页返回 3 个 block)。
- 新增解析:`getUnsuitableMutateChance/getUnsuitableGrowChance/getStemUnsuitableGrowChance`。
- `simulateStem` 新增 10 参纯函数重载(未成熟茎非适季生长);9 参重载默认 grow=0.0 保持旧行为。
- `simulateGrowth` BlockPos 新增 10 参重载(显式概率);各策略/补涨链路透传 per-crop 概率。

## 待办

- [x] CropGrowthConfig 按模组分组 + 24 页(27 方块)+ 番茄整合 + 新字段 + Builder
- [x] CropSimulation 未成熟茎生长机制 + 概率重载
- [x] 各策略/Handler 透传 per-crop 概率
- [x] 测试(新键 + 未成熟茎生长 oracle 等价)
- [x] lang(en_us/zh_cn,瓶子草 + 全局默认标注)
- [x] compileJava + test 全绿
