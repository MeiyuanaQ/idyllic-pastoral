# PastoralCraft

一个基于 [NeoForge 1.21.1](https://neoforged.net/) 的模组，它用**确定性的、基于太阳日的生长系统**取代了 Minecraft 基于随机刻的作物生长机制，并与 [Ecliptic Seasons](https://www.curseforge.com/minecraft/mc-mods/ecliptic-seasons) 深度集成。

## 核心理念

作物不再依赖随机刻(基于概率)来生长，PastoralCraft 采用**种植日(plantedDay)方案**：每株作物都会记录它被种下的太阳日，所有生长阶段都由 `种植日 + 当前日 + 配置` 的纯函数推导而来。这意味着：

- **确定性生长** —— 适季时，作物每经过 N 个太阳日(可配置)就精确推进一个阶段
- **季节集成** —— 不适季时，耕地作物每次生长尝试按可配置概率掷骰(变异为矮草 / 生长一阶段 / 不生长)；甘蔗、海带、下界疣等非耕地作物则冻结生长
- **配置修改立即生效** —— 无需任何状态迁移
- **区块卸载补涨** —— 即使区块被卸载多日，作物也能正确生长

---

## 架构

```
┌──────────────────────────────────────────────────┐
│  CropGrowthHandler(事件层)                        │
│  - CropGrowEvent.Pre(LOWEST) → 拦截随机刻生长      │
│  - ChunkEvent.Load → 区块加载补涨                  │
│  - ServerTickEvent.Post(200 tick) → 周期性补涨     │
│  - ChunkDataEvent.Save/Load → NBT 持久化          │
│  - ChunkEvent.Unload → 清理活跃追踪                │
├──────────────────────────────────────────────────┤
│  CropGrowthTracker(逻辑层)                        │
│  - 纯函数:simulateGrowth / simulateStem /         │
│    seasonOfDay / countSuitableDays                │
│  - 条目管理:getOrCreate / removePosition          │
│  - 茎结果:tryPlaceStemFruit / processStem         │
│  - 成熟副作用:applyMaturitySideEffects            │
├──────────────────────────────────────────────────┤
│  作物模型(四类行为)                               │
│  - AGE     : 原版 5 类 + 泛化 age 扫描            │
│  - HEIGHT  : 甘蔗/海带(只追踪根部)                │
│  - REGROW  : 布尔产物(has_seeds)                  │
│  - CLIMB   : 番茄爬绳(覆写副作用)                 │
├──────────────────────────────────────────────────┤
│  CropProgressEntry(数据层)                        │
│  - 单一字段:plantedDay(种植时的太阳日)            │
│    —— 其余所有状态均为推导得出                    │
├──────────────────────────────────────────────────┤
│  Mixin 注入层(7 个)                               │
│  - ChunkAccessMixin → 每区块作物数据映射          │
│  - LevelChunkMixin → ProtoChunk→LevelChunk 迁移   │
│  - LevelMixin → setBlock 拦截 + REGROW 回退栈     │
│  - SugarCaneBlockMixin → 取消甘蔗 randomTick      │
│  - KelpBlockMixin → 取消海带 randomTick           │
│  - TomatoBlockMixin → 取消番茄 randomTick         │
│  - FlaxBlockMixin → 中和 flax growCropBy flag-3 +  │
│                    内部 updateShape 自我破坏      │
├──────────────────────────────────────────────────┤
│  CropGrowthConfig(配置层) · SeasonTagResolver(季节)│
└──────────────────────────────────────────────────┘
```

---

## 数据模型:`CropProgressEntry`

唯一持久化的状态是作物首次被检测到时的太阳日:

```java
public class CropProgressEntry {
    public final int plantedDay;
}
```

其余所有状态——当前生长阶段、是否在不适季突变为矮草——都由 `种植日 + 当前日 + 配置` 的纯函数推导得出。这消除了状态一致性问题，并能自动适应配置变更。

作物数据通过 `ChunkCropData` 接口挂在 `ChunkAccess` 上(`ChunkAccessMixin` 注入字段)，不使用任何全局无卸载 Map。

---

## 作物模型

### 四类行为

| 类型 | 代表 | 机制 |
|---|---|---|
| **AGE** | 小麦/胡萝卜/甜浆果/茎/可可/下界疣 + 泛化 `age` 扫描(瓶子草、FD/KC 作物) | 每 `daysPerStage` 个适季日推进 1 个 age 阶段 |
| **HEIGHT** | 甘蔗、海带 | 无 AGE，按高度生长，只追踪根部方块 |
| **REGROW** | AHP 向日葵(`has_seeds`) | 布尔产物属性，由日历驱动再生 |
| **CLIMB** | FD 番茄(`tomatoes_on_rope`) | 每适季日爬 1 段绳，封顶 `maxClimbHeight` |

`CropKindResolver` 以 **HEIGHT > REGROW > AGE > NONE** 的优先级对每个方块做一次判定并缓存(O(1))。`AttachedStemBlock` 显式排除(无 AGE，只有 FACING)。

### 数据驱动成熟副作用(`CropOverride`)

成熟时按优先级执行 **TRANSFORM → COMPANION → DOUBLE → BONEMEAL**：

- **DOUBLE** —— 双格作物(`doubleAge`)：`flax`、`pitcher_crop`
- **COMPANION** —— 上方放置伴生块(`topBlock`，可要求 `water=true`)：FD 水稻 → `rice_panicles`
- **TRANSFORM** —— 替换自身(`transformBlock`)：`budding_tomatoes` → `tomatoes`
- **BONEMEAL** —— 无覆写原版作物回退(带 `isValidBonemealTarget` 守卫 + try-catch)

内置覆写 9 条(`CropGrowthConfig.BUILT_IN_OVERRIDES`)，用户配置优先。

---

## 核心纯函数

### `simulateGrowth(...) → GrowthSimulation`

核心函数。给定种植日、当前日和作物位置，模拟生长过程并返回最终阶段与是否突变为矮草：

- **适季**：每累积 `daysPerStage` 天确定性地生长一阶段。
- **不适季(耕地作物)**：每累积 `daysPerStage` 天触发一次生长尝试，按 `unsuitableMutateChance` / `unsuitableGrowChance` 三选一(确定性伪随机，由 `位置 + 种植日 + 尝试序号` 决定)。
- **不适季(非耕地作物)**：走 `countSuitableDays`，冻结、不生长、不突变。
- **全季适宜**：O(1) 除法快路径，不进入逐尝试循环。

### `simulateStem(...) → StemSimulation`

茎(西瓜/南瓜)生命周期：适季日推进生长(每 `daysPerStage` 一阶段)与结果(每 `daysPerFruit` 一果)；非适季日按 `stemUnsuitableMutateChance` / `stemUnsuitableFruitChance` 掷三选一。

### `seasonOfDay(solarDay, termLength) → Season`

按 Ecliptic Seasons 二十四节气历法推导季节：`season = (solarDay / termLength) % 24 / 6`。

### `countSuitableDays(startDay, endDay, suitableSeasons, termLength) → int`

O(1) 统计区间内适宜天数(整年跳过节气分段，余数窗口至多 24 次迭代)。

---

## 生长触发机制

作物通过三条路径推进到目标阶段，纯函数保证三条路径收敛到相同目标：

1. **`CropGrowEvent.Pre`**(优先级 `LOWEST`，在 Ecliptic Seasons 之后)—— 拦截随机刻生长，总是 `DO_NOT_GROW`，改为一次性推进到计算出的目标阶段。

2. **`ChunkEvent.Load`** —— 区块载入时补涨其中所有作物，处理玩家离开期间被卸载的作物。

3. **`ServerTickEvent.Post`**(每 200 tick = 10 秒)—— 主动检查所有已加载且被追踪的区块，`lastProcessedSolarDay` 早退避免重复扫描。

---

## `Level.setBlock()` 拦截

`LevelMixin` 用 `@WrapMethod` 包裹 `Level.setBlock()` 的最深重载——这是**所有**方块变更的唯一入口，覆盖玩家种植、村民耕种、自动补种、科技模组自动化、世界生成、水流、活塞、爆炸、踩踏等所有机制。

### 决策树

```
客户端 / 内部生长守卫? → 直接放行

1. AttachedStemBlock → StemBlock → 果实被采收，回溯 plantedDay
2. 甘蔗/海带 非根部 → 只重置根部 plantedDay
3. !isOldCrop && isNewCrop → 新种植，创建条目
4. isOldCrop && isNewCrop && oldBlock != newBlock → 替换，重建条目
5. isOldCrop && isNewCrop && oldBlock == newBlock → REGROW 或 age 比较
   (newAge < oldAge → 收获补种; newAge > oldAge → 骨粉，不回溯 plantedDay)
6. isOldCrop && !isNewCrop → 破坏，移除追踪
```

### REGROW 回退栈

`REGROW_REVERT_STACK`(`ThreadLocal<ArrayDeque<BlockState>>`)以 `NO_REVERT` 哨兵配对每次非内部 `setBlock`；REGROW 分支(has_seeds false→true)用 `oldState` 替换帧，`finally` 块在异常路径也保证弹出回退，杜绝栈帧泄漏。

---

## 配置

11 个配置项(`CropGrowthConfig`，NeoForge ModConfigSpec)：

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `defaultDaysPerStage` | int | 3 | 每个生长阶段的太阳日数 |
| `daysPerFruit` | int | 3 | 瓜茎结果周期的太阳日数 |
| `stemFruitDirections` | string | `east,north` | 瓜茎结果方向的确定性检查顺序 |
| `unsuitableMutateChance` | double | 0.20 | 耕地作物非适季每次尝试变矮草的概率 |
| `unsuitableGrowChance` | double | 0.40 | 耕地作物非适季每次尝试生长一阶段的概率 |
| `stemUnsuitableMutateChance` | double | 0.20 | 成熟瓜茎非适季每周期变矮草的概率 |
| `stemUnsuitableFruitChance` | double | 0.20 | 成熟瓜茎非适季每周期结瓜的概率 |
| `catchUpSeasonLength` | int | 7 | 每个节气(节气)的太阳日数(须与 ES 的 LastingDaysOfEachTerm 一致) |
| `defaultUntaggedSeasons` | string | `spring,autumn` | 无标签作物的默认适宜季节 |
| `debugLogging` | boolean | false | 启用调试日志 |
| `debugFlaxAll` | boolean | false | 跟随记录所有 supplementaries:flax 的方块状态到 debug.log（需 debugLogging 同时开启） |
| `cropOverrides` | list | `[]` | 单作物覆写 |

### 单作物覆写格式(10 键)

```
"modid:crop_id=key=value,key2=value2,..."
```

| 键 | 说明 |
|---|---|
| `daysPerStage=N` | 每 N 个太阳日推进 1 个生长阶段 |
| `seasons=S1_S2` | 覆写适宜季节(下划线/逗号分隔，或 `year_round`) |
| `topBlock=modid:block` | 成熟时在上方放置伴生块(COMPANION) |
| `transformBlock=modid:block` | 成熟时替换自身(TRANSFORM) |
| `water=true\|false` | COMPANION 是否要求上方为水 |
| `doubleAge=N` | 双格作物：age >= N 时放置上段(DOUBLE) |
| `freeze=true\|false` | 视为非耕地，非适季完全冻结 |
| `climbBlock=modid:block` | 爬藤家族方块 |
| `climbSupport=modid:block` | 爬藤支撑方块 |
| `maxClimbHeight=N` | 最大爬藤段数 |

示例：`"farmersdelight:rice=daysPerStage=3,topBlock=farmersdelight:rice_panicles"`。旧格式 `"modid:crop_id=daysPerStage=N"` 仍兼容。

---

## 季节解析链(4 步)

`SeasonTagResolver.compute` 按顺序解析适宜季节，首个非空来源胜出：

1. 每作物 `seasons=` 覆写(显式配置优先)
2. Ecliptic Seasons 运行时注册表(`CropInfoManager`)
3. Ecliptic Seasons 方块标签(`eclipticseasons:crops/*`)
4. 可配置默认 `defaultUntaggedSeasons`(默认 `spring,autumn`)

Block 级缓存，`TagsUpdatedEvent` 与配置重载时清空。

---

## Ecliptic Seasons 集成

PastoralCraft 使用 Ecliptic Seasons 的太阳日与季节 API：

- `EclipticSeasonsApi.getInstance().getSolarDays(level)` —— 当前太阳日
- `EclipticSeasonsApi.getInstance().getSeason(level)` —— 当前季节(春/夏/秋/冬)
- `EclipticSeasonsApi.getInstance().getLastingDaysOfEachTerm(level)` —— 每个节气持续的天数
- `EclipticSeasonsApi.getInstance().isSeasonEnabled(level)` —— 季节系统是否启用

Ecliptic Seasons 采用二十四节气历法：1 节气 = 7 天，1 季 = 6 节气 = 42 天，1 年 = 4 季 = 24 节气 = 168 天。当 Ecliptic Seasons 不存在或被禁用时，季节默认为 `Season.NONE`，所有作物始终适宜。

---

## NBT 持久化

作物数据通过 `ChunkDataEvent` 按区块存储：

- **保存**：将 `(pos, plantedDay)` 对序列化到键名为 `pastoralcraft_crop_data` 的 `ListTag`
- **加载**：反序列化条目，并将该区块注册到周期性补涨追踪中

键名与字段名固定，保证存档兼容。

---

## 兼容性

- **Quark**(右键收获)：通过 `LevelMixin` 中的年龄比较检测——同一作物方块 age 7→0 且未经过空气时，视为收获+补种，使用全新 `plantedDay`。
- **Ecliptic Seasons**：以 `EventPriority.NORMAL` 运行，PastoralCraft 以 `LOWEST` 运行以覆写 ES 的生长结果。
- **自动补种模组、科技模组、世界生成**：均通过 `Level.setBlock()` 拦截捕获。
- **Farmers Delight**：水稻伴生(`rice_panicles`)、番茄爬绳(`climbBlock`/`climbSupport`/`maxClimbHeight`)通过数据驱动覆写支持，无编译期依赖(字符串 target Mixin)。

---

## 项目结构

```
src/main/java/com/crispyraccoon/pastoralcraft/
├── PastoralCraft.java              # @Mod 主类
├── PastoralCraftClient.java        # 客户端入口
├── Config.java                     # 配置加载/重载事件
├── crop/
│   ├── CropProgressEntry.java      # 数据条目(仅 plantedDay)
│   ├── CropGrowthConfig.java       # 配置定义 + CropOverride 10 键解析 + 内置覆写
│   ├── CropGrowthTracker.java      # 核心引擎:纯函数 + 条目管理 + 补涨 + 茎/副作用
│   ├── CropKind.java               # 四类行为枚举(NONE/AGE/HEIGHT/REGROW)
│   ├── CropKindResolver.java       # 四类行为解析(Block 级缓存)
│   ├── AgeCrop.java                # AGE 描述符
│   ├── HeightCrop.java             # HEIGHT 描述符
│   ├── RegrowCrop.java             # REGROW 描述符
│   ├── SeasonSource.java           # ES 源 / 标签源接口
│   ├── SeasonTagResolver.java      # 季节解析链 4 步 + Block 级缓存
│   ├── FlaxDiagnostics.java        # flax 状态跟随诊断(debugFlaxAll 门控,[FlaxDiag] 日志)
│   ├── InternalGrowthFlag.java     # 重入守卫 ThreadLocal
│   └── ChunkCropData.java          # 接口:每区块数据访问
├── event/
│   └── CropGrowthHandler.java      # 事件处理器(Pre/Load/Save/Load/ServerTick/Unload/TagsUpdated)
└── mixin/
    ├── ChunkAccessMixin.java       # 向区块注入作物数据映射字段
    ├── LevelChunkMixin.java        # ProtoChunk → LevelChunk 数据迁移
    ├── LevelMixin.java             # setBlock @WrapMethod 拦截 + REGROW 回退栈
    ├── SugarCaneBlockMixin.java    # 取消甘蔗 randomTick
    ├── KelpBlockMixin.java         # 取消海带 randomTick(instanceof 守卫)
    ├── TomatoBlockMixin.java       # 取消 FD 番茄 randomTick(字符串 target)
    └── FlaxBlockMixin.java         # 中和 flax growCropBy flag-3 + 内部 updateShape 自我破坏(字符串 target,@WrapOperation/@Inject)

src/main/resources/
├── pastoralcraft.mixins.json       # Mixin 配置(7 个 mixin,JAVA_21)
└── assets/pastoralcraft/lang/
    ├── en_us.json                  # 22 配置键 + 标题键
    └── zh_cn.json                  # 与 en_us 键数一致
```

---

## 环境要求

- **Minecraft** 1.21.1
- **NeoForge** 21.1.248+
- **Java** 21
- **Ecliptic Seasons**(唯一硬依赖，`libs/` 直接引用)

## 许可证

本项目按现状提供，仅用于教育与模组开发目的。
