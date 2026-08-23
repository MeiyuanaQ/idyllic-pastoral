# 作物结构注册表落地蓝本(§8 方向 4 细化,不实现)

> 状态:方案待 review。本文件是 `god-class-split-refactor.md` §8(第三方作物结构探测收口)的落地蓝本,
> 依据外部参考模组 [Unloaded Activity](D:/Download/Unloaded-Activity-main) 的源码整理。只描述「怎么改」,不改码。
> 前置:`god-class-split-refactor.md` §9 的 P0–P4 全绿后,作为 P5 独立一轮进入。

---

## 0. 结论速览

Unloaded Activity 用「**datapack JSON 描述符 + 泛化属性方法原语 + 表达式 DSL + 可选跨模组注册**」解决了
与 PastoralCraft 同源的「卸载补长」问题,且自带 FD / Supplementaries / IE / BOP / NoMansLand 的兼容层。
它的**识别层架构正是 §8 想要的形态,可作为蓝本照抄其 JSON DSL 与合并语义**;但它用二项分布采样逼近
原版随机刻(统计准确、非确定),且只补卸载边界、不门控已加载区块 —— 这两点与 PastoralCraft 的
确定性日历支柱冲突,**不抄**。

| 问题 | 一句话答案 |
|---|---|
| 抄什么 | datapack 描述符 + `replace` 合并继承 + 标签覆盖全家 + 泛化属性方法 + 表达式条件 DSL + 可选跨模组注册 |
| 不抄什么 | 二项/负二项分布采样;只补卸载、不门控已加载 |
| 落地成什么 | `StructureDescriptor` 数据化,`CropKindResolver` 泛化扫描降级为默认提供者,特例改 JSON/DataMap 声明 |

---

## 1. 借鉴的架构(Unloaded Activity 源码锚点)

| 机制 | 锚点(相对 `Unloaded-Activity-main/common/src/main/`) | 借鉴点 |
|---|---|---|
| 描述符 | `resources/data/*/simulate_info/{blocks,tags,groups}/*.json` | 按 block id / tag / group 键控的 JSON 声明 |
| 合并继承 | `java/.../datapack/simulation_data/SimulationData.java`(L21-52 从后往前找 `replace` 切分 + 合并) | 覆盖优先级 + `replace` 语义 |
| 标签覆盖全家 | `resources/data/minecraft/simulate_info/tags/crops.json` | 一个描述符覆盖整族作物,特例单独覆写 |
| 泛化方法原语 | `java/.../impl/simulation_methods/{PropertyMethod,IncrementPropertyGrowthMethod,MaxPropertyGrowthMethod,ReplaceMethod,GrowBlockMethod,GrowFruitMethod,BuddingMethod}.java` | 按 `property_name` + 表达式驱动,方法体内零字符串匹配方块 |
| 表达式 DSL | `java/.../impl/number_fetchers/*` + `api/{condition,context,value_expression,number_fetcher}/*` | `raw_brightness/growth_speed/property/named_property/is_block/has_tag/random` 等 |
| 跨模组注册 | `java/.../registrations/{FarmersDelightRegistrations,SupplementariesRegistrations,NoMansLandRegistrations}.java` + `shouldDoCompat(...)` 门控 | 每个模组只暴露一两个小类,JSON 用 `ns:method` 引用 |

---

## 2. 不抄的部分(与 PastoralCraft 支柱冲突)

1. **统计采样非确定** — README「Simulation accuracy」:用二项/负二项分布采样逼近随机刻,不维护
   `plantedDay → stage` 不变量。违反 `god-class-split-refactor.md` §11.2-1(丢确定性)。
2. **只补卸载边界** — 区块加载时原版 randomTick 照跑,只在重回模拟距离时算一次差量;不门控已加载区块,
   也没有三路径收敛需求。PastoralCraft 的 `CropGrowEvent.Pre DO_NOT_GROW` + 日历单一真相它不覆盖。

→ 结论:它只能当**识别层**蓝本,`plantedDay` + `simulateGrowth` 纯函数 + 三路径收敛维持不变。

---

## 3. 落地映射:把特例改数据声明

### 3.1 `StructureDescriptor` 字段(对应现有代码锚点)

| 字段 | 现有实现(待迁) | 数据化后 |
|---|---|---|
| `ageProperty` / `maxAge` | `CropKindResolver.ageOf` 泛化扫描 + 反射 `getMaxAge` | 保留为默认提供者 |
| `doubleAge`(双格) | `CropClassifier.isDoubleCropUpperHalf` / `placeDoubleUpperHalf` | 描述符字段 |
| `topBlock`(COMPANION) | `CropGrowthConfig.CropOverride.topBlock` | 描述符字段 |
| `transformBlock`(TRANSFORM) | `CropOverride.transformBlock` | 描述符字段 |
| `climbBlock` / `climbSupport`(CLIMB) | `CropOverride.climbBlock/climbSupport` + `ClimbStrategy` | 描述符字段 |
| `segmentedLocationProp`(KC 稻) | `CropClassifier.findSegmentProperty` 扫 `location` 0..2 | 描述符字段 |
| `regrowProductProp`(has_seeds) | `CropKindResolver.computeRegrow` 扫 `has_seeds/seeded` | 保留泛化扫描 |
| 特殊 id 判定 | `FlaxDiagnostics.isFlax` 字符串、`TomatoBlockMixin` 字符串 target | 描述符条目取代 |

### 3.2 特例 → 数据映射(照抄 Unloaded Activity 的写法)

| PastoralCraft 特例 | Unloaded Activity 参考写法 | 对应 JSON 字段 |
|---|---|---|
| flax DOUBLE | `supplementaries:grow_flax` + 条件 `named_property half == lower` | `doubleAge` + 上半块条件 |
| FD 稻 COMPANION | `grow_crop` + `grow_crop_final`(`grow_block: rice_panicles` + `dependencies`) | `topBlock` |
| FD 番茄 CLIMB/TRANSFORM | `upgrade`(replace + `set_properties` 迁 age)+ `climb`(grow_block + restrict_height + `can_climb`) | `climbBlock/climbSupport/maxClimbHeight` |
| budding_tomatoes→tomatoes | `convert`(replace + `dependencies:["grow_crop"]`) | `transformBlock` |
| 茎结果 | `grow_fruit`(`stem_block`/`fruit_block`) | 保留 `StemStrategy` 自研(三选一支撑无对应原语) |
| torchflower 满熟变身 | `grow_crop_final`(replace → `minecraft:torchflower`) | `transformBlock` |
| 甘蔗高度 | `max_property_growth`(`max_height:3` + `reset_on_height_change`) | 保留 `HeightStrategy` 自研 |
| 海带高度 | `increment_property_growth`(`bottom_block_replacement` + `only_in_water`) | 保留 `HeightStrategy` 自研 |

> 甘蔗/海带/茎结论:Unloaded Activity 也是用**属性自增 + 高度上限**数据化,但 PastoralCraft 现有
> `HeightStrategy`/`StemStrategy` 已覆盖其语义,且含自研逻辑(砍中段反推 plantedDay、三选一支撑、满高保留),
> 无需迁移,只把「识别」改为声明式。

---

## 4. 载体选型:datapack JSON vs NeoForge DataMap

| 维度 | datapack JSON(Unloaded Activity 式) | NeoForge DataMap(`DataMapType<Block,StructureDescriptor>`) |
|---|---|---|
| 表达式/条件能力 | 强(自带表达式 DSL) | 无(需自建 codec 字段) |
| 生态标准 | 自定义目录 `simulate_info` | NeoForge 标准(`STRIPPABLES/OXIDIZABLES` 同款) |
| datapack 覆盖 | 原生 | 原生(`data/<ns>/data_maps/block/*.json`) |
| 类型安全 | 弱(Gson 手解析) | 强(codec 编译期校验) |
| 热路径 | 需解析后缓存(它已缓存) | `Block.getData(type)` O(1) |
| 建议 | 作为 DSL 语义参考 | **作为载体**(与 AGENTS §8 的 `CropRegistry` 一致,更标准) |

> 落地建议:**载体用 NeoForge DataMap,JSON 字段语义照抄 Unloaded Activity 的 `simulate_info`**;
> `CropKindResolver.ageOf` 泛化扫描降级为默认提供者(等价其 `crops` 标签),行为不变桥。

---

## 5. 分期与验收(独立一轮,承接 P5)

| 阶段 | 内容 | 验收 |
|---|---|---|
| S1 | 定义 `DataMapType<Block, StructureDescriptor>` + codec,内置 9 条 `CropOverride` 与 flax/番茄/KC 稻迁移为内置条目 | `compileJava` + `test` 全绿 |
| S2 | `CropClassifier`/`CropKindResolver`/`MaturitySideEffects`/`ClimbStrategy` 查询点改走 DataMap,泛化扫描降级为 fallback | 同上;`git diff` 确认无字符串匹配残留 |
| S3 | 补 datapack JSON 覆盖用例 + 单测(未知模组作物走默认扫描 / 特殊结构走条目) | 新增测试全绿 |
| S4 | gametest 回归(flax 三路自毁 / FD 稻 COMPANION / KC 三段稻 / 番茄爬绳) | 与 P4 清单一致 |

每阶段「行为不变」是硬门禁;任何一步回归即回滚,不影响 P0–P4 主计划。

---

## 6. 边界(诚实标注)

- DataMap 只能**声明结构**,不能复刻任意模组私有副作用(KC 稻鱼群加速、FD 番茄概率爬绳仍靠现有 override/策略类);
- 想完全「白嫖第三方自己长」仍不可行 —— 那等于放弃门控,回到 `god-class-split-refactor.md` §11.2 三条硬阻塞。
