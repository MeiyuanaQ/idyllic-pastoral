# 作物季节 tooltip + Jade 显示(详细实现计划)

> 状态:已实施(Q1=同意 build.gradle 变更、Q2=所有 ES 有季节标签方块、Q3=ES 式带图标/多行)。目标:pastoralcraft 自建「季节」显示(物品 tooltip + Jade),覆盖 ES 同类功能,只显示 pastoralcraft 的;数据源自 ES,但 pastoralcraft 配置的 `seasons=` 覆写优先。

## 1. 数据源(零改动,直接复用)

- 季节集合统一走 `crop/SeasonTagResolver.resolve(Block)` → 已是完整优先级链:
  `CropGrowthConfig.getOverrideSeasons`(pastoralcraft 配置 `seasons=`)→ ES 注册表 `CropInfoManager.getSeasonInfo` → ES 方块标签 → `defaultUntaggedSeasons`。
- 满足「数据源自 ES、pastoralcraft 配置优先」。显示时不带 currentSeason(用 `SeasonTagResolver.resolve` 直接取适宜季节集合,而非 `CropCalendar.resolveSuitableSeasons`,后者在 ES 禁用时会返回全年)。
- 客户端数据可用性:即使 `CropInfoManager` 未在客户端填充,`SeasonTagResolver` 会回退到 ES 方块标签(客户端有标签),结果仍正确。

## 2. 新增文件

### 2.1 `client/CropSeasonDisplay.java`(纯显示 helper,客户端)
- `static List<Component> seasonTooltip(Block block)`:
  - `if (!CropClassifier.isGrowableCrop(block)) return List.of();`
  - `Set<Season> seasons = SeasonTagResolver.resolve(block);`
  - `seasons == ALL_SEASONS`(或空/全年)→ 返回单行「全年」(`pastoralcraft.tooltip.seasons.year_round`)。
  - 否则按 SPRING→SUMMER→AUTUMN→WINTER 拼接一行「适宜季节: 春 夏 秋 冬」。
- 季节名复用 ES `Season.getTranslation()`(硬依赖,已本地化);仅新增 pastoralcraft 标签键(见 §5)。

### 2.2 `client/CropSeasonTooltip.java`(物品 tooltip)
- `@EventBusSubscriber(modid = PastoralCraft.MODID, value = Dist.CLIENT)` + `@SubscribeEvent`:
  `onItemTooltip(ItemTooltipEvent event)`(FORGE 游戏总线,客户端)。
- 仅 `ItemStack.getItem() instanceof BlockItem`,取 block,追加 `CropSeasonDisplay.seasonTooltip(block)`。
- 与 ES 的 `ClientEventHandler.addTooltips(ItemTooltipEvent)` 同事件;ES 侧由 §4 静音,不重复。

### 2.3 `compat/jade/JadeSeasonPlugin.java` + `compat/jade/JadeSeasonProvider.java`
- `JadeSeasonPlugin`:标注 `@WailaPlugin`(无 value,插件属于本 mod),实现 `IWailaPlugin`:
  `registerClient(IWailaClientRegistration reg)` → `reg.registerBlockComponent(JadeSeasonProvider.INSTANCE, Block.class)`。
- `JadeSeasonProvider implements IBlockComponentProvider`:
  - `appendTooltip(ITooltip, BlockAccessor, IPluginConfig)`:若 `CropClassifier.isGrowableCrop(accessor.getBlock())`,则 `tooltip.addAll(CropSeasonDisplay.seasonTooltip(block))`。
  - `getUid()` → `pastoralcraft:crop_season`;`getDefaultPriority()` → 1000(与 ES crop provider 同级)。
- 覆盖方式:不是靠 priority「顶掉」ES(不同 UID 会并列显示),而是 §4 把 ES 显示静音 → 只剩 pastoralcraft 的一行。

## 3. 依赖变更(build.gradle,需许可)

- Jade API 需进编译类路径:`compileOnly files("libs/Jade-1.21.1-NeoForge-15.10.6.jar")`(jar 已存在于整合包 mods 目录,拷贝到 `libs/`)。
- 不加 runtimeOnly:Jade 运行时已由 `localRuntime fileTree(.../mods)` 提供;compileOnly 保证无 Jade 时类不被加载(`@WailaPlugin` 类仅由 Jade 扫描器在 Jade 存在时加载),故不引入硬依赖。
- 现有 `libs/` 仅 ES + neoforge-sources;需新增该 Jade jar 拷贝 + `compileOnly` 一行。
- ⚠️ AGENTS.md §5.3「禁改 build.gradle 未经用户许可」——本步需你确认。

## 4. 覆盖/静音 ES(改 `compat/EsGrowthDisabler.java`)

- 追加 `CommonConfig.Crop.enableCrop.set(false)`(把上一轮「保持 true」改回 false)。
- 最终置位:`enableCrop=false`、`enableCropHumidityControl=false`、`restrictBoneMeal=false`、`CompatModule.CommonConfig.showCropGrowthInfoInProbe=false`。
- 效果:ES 的 `CropGrowthHandler.appendInfo` 与 `CropInfoManager.appendInfo` 早退返回空 → ES 物品 tooltip 与 Jade 季节行全部消失;ES 生长诊断已关;ES 温室季节覆盖/季节限制/死亡同步关闭(原「关温室」目标同时达成)。ES 只剩雪花状态行(非季节,不在本次范围)。
- 此步同时把上一轮 AGENTS.md 的 D4 从「EnableSeasonalCrop 保持 true」改为「false,季节显示由 pastoralcraft 自建提供」。

## 5. 翻译(lang)

- `lang/zh_cn.json` / `lang/en_us.json` 新增:
  - `pastoralcraft.tooltip.seasons.label` = 适宜季节 / Suitable seasons
  - `pastoralcraft.tooltip.seasons.year_round` = 全年 / Year-round
- 季节名复用 ES `Season.getTranslation()`(不新增季节名键)。

## 6. 验证

1. `./gradlew compileJava` 通过(需先完成 §3 依赖)。
2. 实机:物品栏悬停作物种子 → 只见 pastoralcraft「适宜季节」;Jade 看作物方块 → 只见 pastoralcraft 季节行(ES 行消失);在 pastoralcraft 配置改某作物 `seasons=` → 显示随之变化(验证配置优先)。
3. `/reload` 后覆盖仍生效。
4. 备份 `& .\tools\backup.ps1 -Title "作物季节tooltip与Jade"`。

## 7. 待确认

- Q1 是否同意 build.gradle 加 `compileOnly files("libs/Jade-...jar")` + 拷贝 Jade jar 到 `libs/`?(唯一需要你许可的工程改动)
- Q2 季节显示范围:仅 pastoralcraft 管理的作物(`isGrowableCrop`,推荐)还是所有 ES 有季节标签的方块?
- Q3 显示粒度:单行「适宜季节: 春 夏 秋 冬」(推荐,轻量)还是 ES 那样的带图标/多行?
