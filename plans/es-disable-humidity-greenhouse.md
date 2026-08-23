# 安装 pastoralcraft 后自动关闭 ES 湿度/温室系统(计划,待拍板)

> 状态:待用户拍板(见文末「待拍板问题」)。本方案**取代** `plans/es-humidity-greenhouse-adaptation.md`(合作路线已废弃)。

## 1. 目标

pastoralcraft 已全量接管作物生长(红线1),ES 的湿度/温室判定是多余且会打架(如 ES 的 out-of-season 死亡会在 NORMAL 优先级先于 pastoralcraft 的 LOWEST 执行)。目标:安装 pastoralcraft 后**自动**把 ES 的湿度与温室系统关闭,让 ES 只充当「季节日历 + 季节标签」提供者。

## 2. 调研结论:ES 配置项 ↔ 代码路径映射

| ES 配置键(TOML) | 字段 | 作用 | 关闭影响 |
|---|---|---|---|
| `EnableCropHumidityControl`(默认 true) | `CommonConfig.Crop.enableCropHumidityControl` | 湿度限制(`CropGrowthHandler.checkHumidity`) + 温室/水箱的湿度修正 | 关湿度、关温室湿度修正 |
| `EnableSeasonalCrop`(默认 true) | `CommonConfig.Crop.enableCrop` | 季节限制 + **温室季节覆盖**(`findNearGreenHouseProvider`+`isInRoom`) + 季节死亡 | 关温室季节覆盖、关 ES 季节限制/死亡 |
| `CropHumidityTransition`(默认 true) | `CommonConfig.Crop.cropHumidityTransition` | 湿度平滑 | 可选,仅平滑 |

关键事实:
- **温室没有独立开关**:季节覆盖挂在 `enableCrop` 分支内,湿度修正在湿度分支内。→ 要「温室系统」完全失效,必须同时关 `enableCrop` + `enableCropHumidityControl`。
- `CropGrowthHandler.beforeCropGrowUp` 运行时**每次**读 `enableCrop.get()` / `enableCropHumidityControl.get()`,无静态缓存(ES 的 `UpdateConfig` 只缓存 `cropHumidityTransition`/`forceCompatMode` 等,不缓存这两个键)。→ 内存 `set()` 即可即时生效。
- 关这两个键**不影响** pastoralcraft 自身:季节解析走 `EclipticSeasonsApi.getSeason/isSeasonEnabled` + `CropInfoManager.getSeasonInfo`/方块标签,后者在 `TagsUpdatedEvent` 无条件重建,与 `enableCrop` 无关。
- 物理方块(恒湿水箱/除湿器/温室之心)仍可放置、仍会跑内部逻辑,但**对作物生长不再产生任何效果**(变装饰/空转)。方块本体不删除。

## 3. 实现方案(推荐:内存覆盖,不改 ES 配置文件)

新建 `compat/EsGrowthDisabler.java`(或并入现有 config 事件类),在 **mod 事件总线** 注册两个监听:

- `ModConfigEvent.Loading`:若 `event.getConfig().getSpec() == CommonConfig.COMMON_CONFIG`,则
  `CommonConfig.Crop.enableCrop.set(false)`、`CommonConfig.Crop.enableCropHumidityControl.set(false)`。
- `ModConfigEvent.Reloading`:同上(文件被 `/reload` 或配置界面重载后重新覆盖)。

要点:
1. **只 `set()`,不调 `save()`**:`ConfigValue.set()` 不写盘;`ModConfigSpec.save()` 会再次触发 Reloading → 递归。内存覆盖已足够(ES 运行时读 `get()`)。
2. **两侧都跑**:pastoralcraft 在服务端/客户端都注册该监听;作物生长在服务端,服务端值 false 即生效;客户端同步 false 使 tooltip 也一致。
3. **时序安全**:ES 是硬依赖,ES 构造(注册配置+`UpdateConfig` 监听)先于 pastoralcraft 构造;`ModConfigEvent.Loading` 在所有 mod 构造完成后按注册顺序派发,ES `UpdateConfig` 先跑(读到 true 但只缓存无关键),随后我们的监听把两键置 false。无需 `FMLCommonSetupEvent` 兜底(但可作为双保险)。
4. 在 `PastoralCraft` 构造器注册:`modEventBus.addListener(EsGrowthDisabler::onLoading); modEventBus.addListener(EsGrowthDisabler::onReloading);`。
5. **硬依赖**引用 `com.teamtea.eclipticseasons.config.CommonConfig`(项目已是 ES 硬依赖,无新增依赖)。

## 4. 待拍板问题

- **Q1(核心)是否连同 `EnableSeasonalCrop` 一起关?**
  - A(推荐):两键都关 → 温室完全失效、ES 季节限制/死亡也关,把生长全权交给 pastoralcraft(ES 只剩日历+标签)。
  - B:只关 `EnableCropHumidityControl` → 关掉湿度与温室湿度修正,但**温室季节覆盖仍在**(对 pastoralcraft 不管的作物生效)。若要「温室也全关」则需额外 mixin 精准掐掉温室分支(侵入 ES,不推荐)。
- **Q2 是否写回 ES 配置文件?**
  - A(推荐):仅内存覆盖,不改 `eclipticseasons-common.toml`(非侵入、可逆、卸载即恢复;代价:文件里仍显示 true)。
  - B:额外在安全时机(`FMLCommonSetupEvent`,一次性、加防重入标志)调 `CommonConfig.COMMON_CONFIG.save()` 写回 false(文件可见、持久,但侵入用户 ES 配置,且要注意别与 Loading/Reloading 里的 set 形成递归)。
- **Q3 物理方块如何处理?**
  - A(推荐):方块保留、可放置,但对生长无效果(空转/装饰)。
  - B:连方块/合成表也一起禁用(需 ES 方块/配方级干预或 mixin ES 方块实体,侵入大,不建议)。
- **Q4 骨粉相关开关是否一起关?** ES 的 `RestrictBoneMeal`(默认 true)会让骨粉在不适条件下失败。pastoralcraft 有自己的骨粉回溯(红线3),是否也把 `restrictBoneMeal` 一并 `set(false)`?(A 一并关,避免 ES 拦截骨粉;B 保留。)

## 5. 实施步骤

1. 新建 `compat/EsGrowthDisabler.java`:两个 `@SubscribeEvent`(Loading/Reloading)+ `getSpec()==CommonConfig.COMMON_CONFIG` 判断 + 置位(按 Q1/Q4 定键)。
2. `PastoralCraft` 构造器 `modEventBus.addListener(...)` 注册两监听。
3. (可选,Q2=B)加一次性持久化 + 防重入。
4. 编译 `./gradlew compileJava`;跑 `./gradlew test`(本改动无纯函数,主要验证编译)。
5. 实机验证:进游戏确认 `eclipticseasons-common.toml` 的作物不再受湿度/温室影响(种沙漠/温室对照),`/reload` 后仍关闭。
6. 备份 `& .\tools\backup.ps1 -Title "关闭ES湿度温室"`。

## 6. 已知风险 / 边界

- ES 升级改配置键名/类路径 → 编译期 `cannot find symbol`,需对照新版源码更新(项目已有 ES 源码 `reference/mods-src/`)。
- 若用户手动在 ES 配置界面开启并保存 → 当次 `Reloading` 我们会再置回 false(这正是「自动关闭」语义)。
- 仅关「作物生长效果」;ES 的季节日历/渲染/天气等其他系统不受影响。
