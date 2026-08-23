# PastoralCraft 开发指南(agent 唯一入口)

> 本文件 = 上下文总纲 + 红线契约。改码前必读;红线即绑定契约,违反即错。
> 设计演进史见 `plans/`,设计文档见 `README.md`。

## 1. 项目快照

| 项 | 值 |
|---|---|
| 模组 | PastoralCraft · NeoForge 1.21.1 · Minecraft 1.21.1 · Java 21 · Mojmap+Parchment |
| mod id / 包 | `pastoralcraft` / `com.crispyraccoon.pastoralcraft` |
| 版本机制 | `mod_version`(gradle.properties 为准)为基线,构建号由 `build_number.properties` 自增计数追加(每次 jar 构建 +1)→ `pastoralcraft-X.Y.Z.N.jar` |
| 唯一硬依赖 | Ecliptic Seasons(`libs/EclipticSeasons-1.21.1-neoforge-0.14.5.jar`,build.gradle `files(...)` 引用) |
| 构建 | 改码必跑 `./gradlew compileJava`;改纯函数必跑 `./gradlew test`;打包 `./gradlew build` |
| 参考源码 | `reference/sourcesAndCompiledWithNeoForge/`(MC 1.21.1 Mojmap 源码 `net/minecraft/` 5168 个 .java + 编译 .class + NeoForge `net/neoforged/`)、`reference/mods-src/`(ES/FD/Supplementaries/AHP/KC/Jade 真实源码)、`reference/NeoForge-1.21.1/`(NeoForge 仓库:patches 补丁)、`reference/minecraft-classes/`(少量 .class + javap)、`docs/config-snapshot/`(ES 配置快照)、`docs/environment/`(运行日志) |

## 2. 核心设计

- 每株作物只持久化 `plantedDay`;阶段/惩罚/成熟由纯函数 `(plantedDay, currentDay, config)` 推导;数据挂 `ChunkAccess`(ChunkCropData),禁全局无卸载 Map。
- **四类行为**:AGE(原版五类快路径 + `CropKindResolver.ageOf` 泛化扫描带 age 的 Bush/CropBlock,覆盖 FD/KC 作物)/ HEIGHT(甘蔗/海带,只追踪根部)/ REGROW(向日葵 `has_seeds`)/ CLIMB(番茄爬绳,覆写副作用)。`AttachedStemBlock` 显式排除。
- **成熟副作用**:DOUBLE(flax/pitcher 双格)、COMPANION(rice 稻穗)、TRANSFORM(budding→tomatoes)、BONEMEAL(原版回退,已防护)。**结构声明**(DOUBLE/COMPANION/TRANSFORM/CLIMB/segmented-rice + FREEZE/water)已迁至 **P5 结构注册表**(`crop_structure` Block data map,见 `CropDataMaps`/`CropStructureRegistry`/`StructureDescriptor`,`StructureDescriptor` 现含 `doubleAge/topBlock/transformBlock/climbBlock/climbSupport/maxClimbHeight/segmentProperty/freeze/water`);仅 `customOverrides` 字符串列表保留同键 per-field 覆写(优先于 data map)。
- **季节解析链**:`seasons=` 覆写 → ES 注册表 → ES 方块标签 → `defaultUntaggedSeasons`(spring,autumn)。
- **代码组织(已拆分)**:纯函数(`CropSimulation`/`CropCalendar`/`PlantedDayMath`)、分类(`CropClassifier`)、世界写(`BlockWriter`/`EntryStore`/`MaturitySideEffects`)、按作物策略(`StemStrategy`/`HeightStrategy`/`RegrowStrategy`/`ClimbStrategy`/`AgeStrategy` + `CatchUpContext` dispatch)、门面 `CropGrowthTracker`。

## 3. 三条生长路径(行为必须一致)

1. `CropGrowEvent.Pre`(LOWEST,对 AGE/茎总是 DO_NOT_GROW 走 tracker)
2. `ChunkEvent.Load` → `onChunkLoad` catch-up
3. `ServerTickEvent.Post`(200 tick,`lastProcessedSolarDay` 早退,ArrayList 快照)

`LevelMixin.onSetBlock` 拦截种植/破坏/收获/骨粉;纯函数保证三路径收敛一致。

## 4. 文件地图(核心)

| 文件 | 职责 |
|---|---|
| `crop/CropGrowthTracker.java` | 门面 + 编排:`catchUpInternal` / trackedChunks 注册表 / ES 时钟 / 对外 static 委托 |
| `crop/CropSimulation.java` | 纯函数:`simulateGrowth` / `simulateStem` / `clampSimDay` / 确定性 hash |
| `crop/CropCalendar.java` | 纯函数:`seasonOfDay` / `countSuitableDays` / `isSeasonSuitable` / `resolveSuitableSeasons` |
| `crop/PlantedDayMath.java` | 纯函数:plantedDay 回推(骨粉/茎收获/COMPANION/高度) |
| `crop/CropClassifier.java` | 分类 + 缓存:`isGrowableCrop` / 非耕地 / segmented-rice / age 访问器 |
| `crop/StemStrategy.java` / `HeightStrategy.java` / `RegrowStrategy.java` / `ClimbStrategy.java` / `AgeStrategy.java` | 按作物类型拆分的生长策略 + `CatchUpContext` dispatch |
| `crop/BlockWriter.java` | 内部 setBlock 唯一收口:INTERNAL_GROWTH 守卫 + 具名 flag |
| `crop/EntryStore.java` | 条目 CRUD:`getOrCreate` / `isTracked` / `removePosition` / `resetPlantedDay` / `placeAndTrack` |
| `crop/MaturitySideEffects.java` | 成熟副作用:TRANSFORM/COMPANION/DOUBLE/BONEMEAL + `placeDoubleUpperHalf` + `mutateToShortGrass` |
| `crop/CropGrowthConfig.java` | 配置分组段([general]/[stem]/[debug]/[crops])+ 按模组分组的精选作物专属页 + `customOverrides` 兜底 + 17 字段 CropOverride Builder + 解析 |
| `crop/CropDataMaps.java` / `CropStructureRegistry.java` / `StructureDescriptor.java` | P5 结构注册表:Block data map(`crop_structure`)+ 结构描述符(DOUBLE/COMPANION/TRANSFORM/CLIMB/segmentProperty/freeze/water)+ 合并解析缓存(`customOverrides` 结构键优先 → data map) |
| `crop/CropKind.java` / `CropKindResolver.java` | 作物分类枚举(NONE/AGE/HEIGHT/REGROW)+ Block 级解析缓存(CLIMB 属 AGE + 爬藤覆写) |
| `crop/AgeCrop.java` / `HeightCrop.java` / `RegrowCrop.java` | 分类描述符(age 访问器 / 高度 / has_seeds) |
| `crop/CatchUpContext.java` | 策略 dispatch 共享上下文 + Outcome |
| `crop/SeasonSource.java` / `SeasonTagResolver.java` | 季节解析链 4 步 + Block 级缓存 + TagsUpdated 清空 |
| `crop/ChunkCropData.java` / `CropProgressEntry.java` / `InternalGrowthFlag.java` | 区块数据接口 / 仅 plantedDay 条目 / 重入守卫 ThreadLocal |
| `crop/DebugGate.java` / `DebugProfiler.java` / `DebugDataHealth.java` / `DebugRingBuffer.java` / `DebugWatchdog.java` / `FlaxDiagnostics.java` | 调试子系统(开关位掩码 / 耗时采样 / 数据健康 / 环形缓冲 / 看门狗 / flax 跟随) |
| `Config.java` / `PastoralCraft.java` | 配置加载/重载事件 / @Mod 主类 |
| `gametest/CropGrowthGameTests.java` | runGameTestServer 端到端生长(驱动 ES 时钟 + LevelMixin 全链路) |
| `event/CropGrowthHandler.java` | 事件:Pre(LOWEST)/Chunk Load/Save/ServerTick/Unload |
| `mixin/LevelMixin.java` | @WrapMethod setBlock 决策树 + REGROW 栈(热路径) |

## 5. 红线契约

### 5.1 机制红线

1. 茎完全接管:`onCropGrowPre` 对所有 growable 作物总是 `setResult(DO_NOT_GROW)`,茎生命周期由 `processStem`→`simulateStem` 驱动。
2. 瓜茎结果:成熟茎由 `tryPlaceStemFruit` 在 catch-up 处理,周期 `daysPerFruit`,仅当 `daysSincePlanted >= daysPerFruit && % daysPerFruit == 0` 触发;方向按 `stemFruitDirections` 确定性尝试;果实支撑三选一(**FarmBlock 含 FD 沃土 / `BlockTags.DIRT` / `isFaceSturdy(UP)`**),否则跳过;采收回退 StemBlock 必须 `resetPlantedDay`;非适季茎掷骰:成熟茎按 `stemUnsuitableMutateChance`/`stemUnsuitableFruitChance`(变异/结果);未成熟茎按 `stemUnsuitableMutateChance`/`stemUnsuitableGrowChance`(变异/生长,默认 0.0)。
3. 骨粉/蜜蜂等外部催熟:无跟踪项时 `getOrCreate` 按 `currentAge` 回溯 `plantedDay = currentDay - currentAge * daysPerStage`;已有跟踪项时,茎走 `onStemBonemeal`(按 age 增量回推),非茎分两路——耕地作物(arable)走 B 规则 `backCalculatePlantedDayForArableBonemeal`(当前日适季则全量回推 `currentDay - newAge * daysPerStage`,即使跨季;当前日不适季不回推),冻结/非耕地作物走保守 `backCalculatePlantedDaySuitable`(适季窗口内回推,不跨季)。三条都只许更早、不许前移。
4. 跨维度时钟:无昼夜维度回退取主世界 `solarDay`(`getSolarDays`)。
5. 甘蔗:高度制生长,仅跟踪根部;非底部条目必须移除;不适季冻结;满高保留条目;砍中段走 `onSugarCaneHarvest` 按存活高度反推 plantedDay(clamp 不晚于 currentDay)。
6. 重入守卫:所有内部 setBlock 一律走 `BlockWriter.internalSetBlock`(统一 `INTERNAL_GROWTH` save/restore 守卫);`onSetBlock` 见守卫立即跳过;ThreadLocal 只能放普通工具类(`InternalGrowthFlag`),严禁进 Mixin。
7. 区块生命周期:处理 `ChunkEvent.Unload` 移出 trackedChunks;`LevelChunkMixin` 在 ProtoChunk→LevelChunk 提升时复制数据。
8. NBT(存档兼容红线):键名 `pastoralcraft_crop_data`,ListTag,元素 `{pos: long, plantedDay: int}`;永不改名。
9. 事件优先级:`CropGrowEvent.Pre` 必须 `EventPriority.LOWEST`(ES 为 NORMAL)。
10. CropOverride(运行时合并对象)17 字段;`customOverrides` 字符串格式 `modid:crop_id=key=value,...` 支持 17 键(daysPerStage/seasons/topBlock/transformBlock/water/doubleAge/freeze/climbBlock/climbSupport/maxClimbHeight/daysPerFruit/fruitDirections/stemMutateChance/stemFruitChance/stemGrowChance/unsuitableMutateChance/unsuitableGrowChance)。精选作物走专属配置页(按模组分组);字符串列表为最低优先级兜底;解析结果缓存不可变。结构键(doubleAge/topBlock/transformBlock/climbBlock/climbSupport/maxClimbHeight/freeze/water)的默认声明在 `crop_structure` data map,`customOverrides` 同键 per-field 优先(见 `CropStructureRegistry.resolve`)。
11. REGROW 栈:`REGROW_REVERT_STACK` 占位帧必须用非 null 哨兵 `NO_REVERT`,严禁 `push(null)`(ArrayDeque 抛 NPE);每帧 setBlock 压/弹 LIFO。
12. CLIMB:`tryClimbVine` 每适季日爬 1 段封顶 `maxClimbHeight`,直接替换上方 climbSupport 为 climbBlock;TRANSFORM 当天爬 0 段且必须从 cropData 重读 **live 条目** plantedDay(禁快照旧值);climb 作物必须冻结(data map 声明 `freeze: true`);成熟条目保留。
13. DOUBLE:`placeDoubleUpperHalf` 幂等(目标态已等提前 return);上半块 age 与下方同步(flag 2);变异必须清理 UPPER 半段防浮空。
14. COMPANION:稻穗必须 `placeAndTrack(..., plantedDay)` 显式传播基株 plantedDay,禁默认当天起步。
15. 成熟分支三路径一致:climb 保留条目;带 transformBlock/topBlock/doubleAge 覆写先 `applyMaturitySideEffects` 按 keepEntry 去留;其余 removePosition。
16. BONEMEAL 回退:调用前 `isValidBonemealTarget` 守卫 + 整体 try-catch(torchflower 教训,单作物异常不得击穿 tick loop);climb 作物阻断回退。
17. 逐作物异常隔离:`catchUpInternal` 每株 try-catch,单株异常只 warn(cropId,pos,摘要)并跳过。
18. 时间预算:`catchUpTimeBudgetMs`(默认 8ms,0 关闭)截断单次补涨 pass,超时剩余条目顺延下一周期;禁单次 pass 无限阻塞主线程。
19. 经过天数钳制:`simulateGrowth`/`simulateStem` 将 `currentDay` 钳制到 `plantedDay + maxCatchUpElapsedDays`(默认 336 = 2 ES 年),防 ES 日历大跳导致单株逐天循环无界(冻结 bug)。

### 5.2 性能红线

1. 存储:`ChunkAccessMixin` 用 HashMap(单线程串行),禁 ConcurrentHashMap 等带锁结构。
2. trackedChunks:保持 WeakHashMap 语义,禁强引用 Set;数据清空必须 unregisterTrackedChunk。
3. 周期补涨:200 tick;`lastProcessedSolarDay` 早退;solarDay/season/seasonLength 每 level 每周期只算一次;迭代前 ArrayList 快照防 CME。
4. 复杂度:耕地 O(elapsedDays/daysPerStage) attempt 迭代;非耕地 `countSuitableDays` O(1)+≤24 节气分段;全适季 O(1) 快路径;禁逐天循环。
5. 热路径(LevelMixin.onSetBlock):`isClientSide()` → `INTERNAL_GROWTH` 两道早退顺序不可颠倒;instanceof 链判定,禁反射/正则/分配;`oldState` 是 untrack 必要条件不可省,但禁 ES API/NBT/日志重操作。
6. 避免重复:季节解析走 Block 级缓存;`getKey(block)` 复用缓存;catch-up 用 `BlockWriter.FLAG_UPDATE_CLIENTS`(禁逐方块邻居更新),`tryPlaceStemFruit` 用 `FLAG_UPDATE_NEIGHBORS`;freeze/override 判定走缓存。结构解析 `CropStructureRegistry.resolve` 用 `ConcurrentHashMap` 做 Block 级缓存(无锁读,`clearCache()` 于 config/data map 重载时清空)——§5.2-1 的「禁 ConcurrentHashMap」仅约束 `ChunkAccessMixin` 的区块数据存储,不约束此解析缓存。
7. 日志短路:`logDebug` 先查 `DEBUG_LOGGING`,禁热路径无条件字符串拼接。
8. NBT:Save 仅非空序列化;Load 用 contains + TAG_LIST 检查早退。
9. 线程:所有 Level/Chunk/BlockState 读写与 setBlock 必须主线程;纯函数可离线;禁为并行引入线程池/锁。

### 5.3 Mixin 规范

- `@Inject` 签名与原版严格一致,禁改返回值类型/吞异常;最小侵入,避免与 Sodium/Lithium 等冲突;非私有静态字段禁入 Mixin 类。
- 严格 Mojmap+Parchment 命名,禁猜禁混 Yarn/Intermediary;`cannot find symbol` 先查映射。
- **禁改** `build.gradle`/`gradle.properties`/映射配置(未经用户许可)。
- Mixin 清单(9,`pastoralcraft.mixins.json`):ChunkAccessMixin(数据字段)/ LevelChunkMixin(提升复制)/ LevelMixin(@WrapMethod setBlock + REGROW 栈,try/finally)/ SugarCaneBlockMixin(取消 randomTick)/ CactusBlockMixin(取消 randomTick)/ KelpBlockMixin(GrowingPlantHeadBlock+instanceof 守卫)/ TomatoBlockMixin(字符串 target 取消 randomTick)/ FlaxBlockMixin(字符串 target,@WrapOperation 降级 growCropBy flag3→2 + updateShape 自毁窗口中和)/ PitcherCropBlockMixin(取消 randomTick,绕过 CropGrowEvent 直写 setBlock)。

## 6. 测试与构建纪律

- 改完代码必跑 `./gradlew compileJava` 直到通过;改纯函数必跑 `./gradlew test` 保持全绿;新增纯函数逻辑必须补单测。
- 备份约定(用户已拍板):**每完成一轮改码,手动执行 `& .\tools\backup.ps1 -Title "<本轮主题>"`** 生成工作区快照到 `backups/`;无自动备份机制,靠 agent 自觉执行。
- Level 交互判定走抽取的 package-private 纯函数 + Mockito(`CropSideEffectTest`/`MaturitySideEffectTest`);真实 setBlock 交互走实机验证或 gametest(`./gradlew runGameTestServer`,`CropGrowthGameTests` 驱动 ES 时钟 + LevelMixin 全链路)。
- 改动最小化:不做无关格式重构;新逻辑在热路径/周期补涨/catch-up 内必须对照 §5.2 自检。
- 新增/改配置项(改 `CropGrowthConfig`)须同步补全 `lang/zh_cn.json` 与 `lang/en_us.json` 的 `pastoralcraft.configuration.<key>` 与 `.tooltip` 翻译。

## 7. 已知设计决策(勿擅改)

- D1 茎类非适季掷骰现含三个结局:变异(`stemUnsuitableMutateChance`)/ 成熟茎结果(`stemUnsuitableFruitChance`)/ 未成熟茎生长(`stemUnsuitableGrowChance`,默认 0.0 保持旧行为)。
- D2 土豆/胡萝卜季界死亡是预期硬核机制,不修。
- D3 flax 非适季冻结不变异(冻结由 `crop_structure` data map 的 `freeze: true` 声明)。
- 番茄 `maxClimbHeight=2`(data map 声明);向日葵 `seasons=spring_autumn`(精选配置页默认)。
- D4 ES 集成(安装 pastoralcraft 自动生效,`compat/EsGrowthDisabler`):关 `EnableSeasonalCrop`、`EnableCropHumidityControl`、`RestrictBoneMeal`、`ShowCropGrowthInfoInProbe`——ES 作物系统(季节/湿度/温室/骨粉)全关,ES 只作日历+标签源;作物季节显示改由 pastoralcraft 自建(`client/CropSeasonDisplay`+`client/CropSeasonTooltip`+`compat/jade/JadeSeasonPlugin`),数据走 `SeasonTagResolver`(pastoralcraft `seasons=` 覆写优先)。Jade 为 compileOnly 可选依赖。

## 8. 行为守则

- **提问分界**:玩法设定(数值/机制/偏好)直接问用户;代码/文件/配置自己查,不凭记忆臆测 API——先查参考源码,再提问。
- **模式边界**:crop-dev 限 `src/main/java`+`src/test`;crop-docs 限 `.md`+lang json;crop-verify 只读源码、仅可更新 `.md`。
- **工作流**:新任务先在 `plans/` 建独立计划,结论/状态回填。
- **意外改动即停问**:发现工作区有非本 agent 产生的改动(未暂存修改、文件被删/新增、内容与预期不符)时,立即停下向用户报告并询问,不自行追查来源、不擅自提交或还原。
- **拿不准即问**:凡对用户的需求、设定的取舍、话语含义或真实意图不确定/存疑,直接问,不自行揣测、不长时间思考。

## 9. 工程效率红线(agent 自省)

- 机械搬码禁用「edit 粘贴整段方法体(大 old_string)」:用 pwsh/Python 按方法签名+花括号匹配物理剪切到新文件、原文件留委托 stub,再 `git diff` 核对。
- 定位锚点用 `grep`(只回命中行);大文件只完整读一次,禁止为拿精确 old_string 反复 `read` 同一区域。
- 编译报错:`gradlew compileJava --no-configuration-cache --console=plain` 后 grep 日志里的 `error:` 行;严禁 dump `build/reports/problems/problems-report.html`(内嵌巨量 CSS/JSON)。
- 计划文档/总结/状态回填保持要点级,不重复粘贴大段代码或长叙述。
- 目录/文件验证**禁全列路径**:`Get-ChildItem -Recurse` 不得接 `Select FullName`/`Format-Table`;验证体积/数量一律 `-Recurse -File | Measure-Object Length -Sum` 或按 `-Directory` 聚合。
- grep **命中压体积**:命中行预计 >60 时先收窄关键词(用最独特锚点:方法名/字段名/资源 id,勿用 `rice|location` 这类通配词),或改 `-l`(只回文件名)/`-c`(只回计数)。
- 通读边界:只**整读本次要改的文件**;其余文件用 grep 命中行 + `read` 小窗口(offset/limit)确认上下文,不整读无关大文件。
- 编辑粒度:逻辑重写也**拆小步 edit**(逐字段/逐分支),避免整方法(几十行 old_string)一次替换;大 old_string 只允许机械搬码场景且走 pwsh 剪切。
- **效率规则不 override §5/§6**:正确性兜底是「改码前全量 grep 枚举所有引用点 + compileJava/test 全绿」,不是「读得多」;读得少 ≠ 查得少。遇「行为不变」重构或不确定的跨文件依赖时,允许打破上面「通读边界/压体积」约束,优先保正确。

## 10. 已知问题登记

- 小问题 / 潜在 Bug / 既有取舍统一登记在 `plans/known-issues.md`(`[决策]` = 用户已拍板勿擅改、`[既有]` = 修复前即存在、`[观察]` = 暂无碍后续需留意)。
- 改 `StemStrategy` / `CropGrowthHandler` / `CropGrowthTracker` 的补涨链路(尤其瓜茎结果/延后/变异)前,**先查 `plans/known-issues.md`**,避免重复踩坑或误改已确认的取舍。

## 11. 交互效率(对齐用户习惯,减少 token)

- **默认简洁**:回复先给结论/结果,细节仅在用户追问时展开;用户说「简洁/只给结果/不要长篇」时,禁长段解释、大表格、重复贴代码。
- **歧义先二选一再动手**:用户简短消息含否定/对比词(不会/会、未/无、应/不应)或可两种解读时,先回一句「A 还是 B」列两种解读让用户选,再动手;禁直接猜+实现——猜反=整轮返工,是最大 token 浪费。
- **bug 报告先复述对齐**:动手前先一句话复述「期望 X,实际 Y」;复述对不上就先问,不揣测。
- **能动手就别只列计划**:改动小且无「需用户拍板的取舍/风险」时直接修;仅当存在取舍或风险时才先问/列计划。
- **文档按需**:计划/登记/总结保持要点级;除非用户要求「详细」,单节 ≤3~5 行、不粘大段代码。
