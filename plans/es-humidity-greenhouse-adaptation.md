# ES 湿度 / 温室系统适配计划(已废弃)

> 状态:**已废弃**。用户改为「安装 pastoralcraft 后自动关闭 ES 湿度/温室」,见 `plans/es-disable-humidity-greenhouse.md`。本文件仅留作调研结论存档。

## 1. 目标

让 PastoralCraft 的 plantedDay 生长机制「配合」Ecliptic Seasons 的湿度与温室系统,而不是像现在这样因红线1(`CropGrowEvent.Pre` LOWEST 恒 DO_NOT_GROW)把 ES 的湿度/温室判定完全架空。

## 2. ES 湿度系统调研结论

- `Humidity` 枚举 5 级:`ARID(0)/DRY(1)/AVERAGE(2)/MOIST(3)/HUMID(4)`。
- **基础湿度**(确定性,只依赖 群系温度+降水+节气雨量):
  - 公开 API:`EclipticSeasonsApi.getBaseHumidity(Level, BlockPos)` → `Humidity`。
  - 内部:`EclipticUtil.getHumidityLevelAt(level, pos)` → float[0,4](`getHumidityLevelAt(level, solarTerm, biome, pos, serverSide)` 可离线算)。
- **作物湿度偏好**:`CropInfoManager.getHumidityInfo(Block)` → `CropHumidityInfo(min,max)`。
  - 来源:方块标签 `eclipticseasons:crops/*_*` + 程序注册 + `RegisterAndModifyCropInfoEvent`;`TagsUpdatedEvent` 时重建(与 season info 同源,已验证 pastoralcraft 已用同款 `getSeasonInfo`)。
- **湿度→生长参数**:`CropGrowthHandler.getControlMap(Block)` → `CropGrowControl.getGrowParameter(float env, BlockState)` → `GrowParameter(grow_chance, death_chance, fertile_chance, dead_state)`;数值来自数据包 `eclipticseasons:crop/humidity/*`。
- **湿度修正(水箱/除湿器/温室)**:
  - 内部:`SolarDataManager.calculateHumidityModification(pos)`(叠加范围内水箱/除湿器 level)。
  - 公开:`EclipticSeasonsApi.getAdjustedHumidity(ServerLevel, pos)`(Experimental)= 基础 + 修正 + 室内判断 + 降雨+1,`getHumidityAfterCheck` 里带 20 次随机室内采样,波动大。
- 相关 ES 配置(默认):`EnableCropHumidityControl=true`、`CropHumidityTransition=true`。

## 3. ES 温室系统调研结论

- 方块:春夏秋冬 `GreenHouseCoreBlock`(季节之心)+ 容器 + 框架;需 **密封房间**(`GreenHouseAir` BFS,漏气 >3 判不封闭)。
- **房间密封检测**:`CropGrowthHandler.isInRoom(level, pos, state, notGreenHouse)` — 射线检测(FULL 17 向 / BASIC 6 / TOP_ONLY 1),**昂贵**(17 次 raycast,且每次带随机扰动)。
- **温室季节覆盖**:`SolarDataManager.findNearGreenHouseProvider(pos, likedSeasons)`(范围内找季节匹配的核心)+ `isInRoom` → 温室内作物按核心季节生长,即 **跨季节生长**。
- `SimpleGreenHouseMode`(默认 false):无需核心,仅 `isInRoom`。
- 相关 ES 配置(默认):`GreenHouseMaxDiameter=32`、`GreenHouseMaxHeight=10`、`SeasonCoreRange=15`、`GreenHouseCheckMode=FULL`。

## 4. pastoralcraft 现状与冲突点

- 红线1:实时路径全接管,ES 的湿度/温室判定被忽略 → 必须由 pastoralcraft 自己实现湿度/温室效果,否则作物无视湿度与温室。
- 核心模型:纯函数 `(plantedDay, currentDay, config)` 确定性回放;仅持久化 `plantedDay`(红线8 / 核心设计)。
- **根本张力**:湿度修正(水箱/除湿器/降雨)与温室结构是玩家随时间建造/变化的状态,无法从 `plantedDay` 历史回放。基础湿度(群系+节气)是确定性的、可回放;温室/水箱/降雨不是。
- 现只有「季节」一个维度,无「湿度 / 温室」维度。

## 5. 候选适配方案(供对照,非定案)

### 方案 A:确定性二值「季节 ∧ 湿度」都适合才计生长日(可回放)
- 把 `countSuitableDays` 升级为「适合季 + 适合湿度」双条件计数;湿度用 **基础湿度**(biome+term,确定可回放)。
- 优点:不破坏 plantedDay 确定性回放,三路径收敛不变;湿度不适 = 冻结(同 non-arable),不引入死亡/变异。
- 缺点:基础湿度按 (biome, term) 逐节气判,需把 `simulateGrowth`/`simulateStem`/`countSuitableDays` 的签名从「仅季节」扩到「季节+湿度」;`countSuitableDays` 的 O(1) 全周期跳过需要按 (term,湿度等级) 重算;水箱/温室/降雨的湿度修正**不生效**。

### 方案 B:乘性缩放 daysPerStage(仅当前时刻)
- 用「当前时刻」湿度 growChance 缩放 `daysPerStage`(如 0.5 → 翻倍)。实现最轻。
- 缺点:catch-up 从 plantedDay 回放时,历史湿度不可得 → 结果随「现在」湿度漂移,违反确定性;只能近似。

### 方案 C:湿度/温室只做「当前门控」,季节仍按历史计数
- 季节照旧(可回放);湿度/温室只在 real-time(`CropGrowEvent.Pre`)与 periodic catch-up 的「现在」评估:
  - 湿度不适 → 本周期不推进(门控),历史季节计数不变。
  - 温室内 → 当前季节视为适合(`suitableSeasons`→ALL),允许跨季生长。
- 优点:水箱/温室/降雨全生效;不破坏「季节」回放,只新增「现在」维度。
- 缺点:门控不累计(作物在湿度不适的几天里「暂停」,不追偿);温室「跨季生长」只在进入温室后开始,不回补进温室前的天数——本质是「当前状态」语义,与 ES 原行为接近。

### 推荐
先 **C(温室季节覆盖 + 湿度当前门控)** 作为第一阶段(最贴合「配合温室/水箱」),湿度数值可选基础或调整后(见 Q2);后续再评估是否把「基础湿度」并入方案 A 的可回放计数。

## 6. 待拍板问题(交由你判断)

> 建议直接回「Q1=A, Q2=B, ...」即可。

- **Q1 湿度影响形式**:A 确定性二值(适合/不适合→计/不计生长日);B 乘性缩放 daysPerStage;C 复刻 ES 概率(growChance 掷骰,破坏确定性)。_(我倾向 A)_
- **Q2 湿度取值来源**:A 仅基础湿度(确定性可回放,但水箱/温室湿度调节不生效);B 调整后湿度 `getAdjustedHumidity`(含温室/水箱/降雨,Experimental、状态化、不可回放);C 基础湿度 + `calculateHumidityModification` 手动叠加(含水箱/除湿器,不含降雨)。_(我倾向 C 或 B)_
- **Q3 温室季节覆盖**:是否启用「温室内作物跨季节生长」?(A 启用;B 不启用,温室只保留 ES 原义、pastoralcraft 不接管)。若启用,检测用 `findNearGreenHouseProvider`+`isInRoom`(昂贵,需 per-position/结构缓存)是否可接受?
- **Q4 湿度致死**:湿度严重不适时,作物是否死亡(ES 有 `death_chance`+`dead_bush`)?还是只「停止生长」不死亡?与现有季节变异/死亡(D2 硬核)如何叠加?
- **Q5 作用范围**:只约束 ES 已注册湿度信息的作物(`getHumidityInfo(Block)` 非空,与 ES 一致),未注册作物不受湿度影响;还是 pastoralcraft 另提供湿度 override 配置(仿 `seasons=`)?
- **Q6 与季节不适交互**:湿度不适与季节不适并存时,优先级/叠加?(例如:季不适已变异 → 湿度是否再判定;湿度不适是否也触发变异,还是纯冻结。)
- **Q7 配置开关**:新增 `enableHumidity` / `enableGreenhouse` 开关,默认值?(默认开以「配合系统」,还是默认关保持现状?)
- **Q8 回放确定性**:是否接受「湿度/温室只在当前时刻评估、不回补历史」的近似(方案 C 语义)?若否,是否愿意为湿度修正/温室引入额外持久化状态(会触碰「仅 plantedDay」与红线8)?

## 7. 落地步骤概览(待 Q 拍板后细化)

1. 新增 `HumidityResolver`(仿 `SeasonTagResolver` 链:ES 注册表 → 方块标签 → override → 默认)+ Block 级缓存,`TagsUpdatedEvent`/config 重载清缓存。
2. 新增温室查询门面(`GreenhouseResolver`):`findNearGreenHouseProvider` + `isInRoom`,带 per-position/结构缓存与失效策略。
3. `CropGrowthConfig` 增 `[humidity]` 组 + 开关 + 默认湿度范围/致死配置,补 `lang/zh_cn.json`、`lang/en_us.json`。
4. `CatchUpContext`/各 Strategy 接入「现在」湿度/温室判定;若走方案 A 再改 `CropSimulation`/`CropCalendar` 纯函数签名并补单测。
5. 改完跑 `./gradlew compileJava` + `./gradlew test` + 备份(`& .\tools\backup.ps1 -Title "..."`)。
