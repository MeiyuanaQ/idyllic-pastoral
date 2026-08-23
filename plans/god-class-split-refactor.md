# PastoralCraft `CropGrowthTracker` 上帝类拆分重构方案(不实现)

> 状态:方案待 review。本文件只描述「怎么改」,不含代码改动;红线以 `AGENTS.md` §5 为准。
> 目标:把 3385 行的 `CropGrowthTracker` 拆成「纯函数 / 分类 / 世界写 / 按作物策略 / 编排」五层,
> 同时收口方块写、补集成测试,全程**行为不变、测试全绿后再动逻辑**。

---

## 0. 结论速览

| 问题 | 一句话答案 |
|---|---|
| 上帝类如何拆 | 按「纯函数 vs 世界副作用」分层,再按作物 kind 拆 5 个 `*Strategy`;`CropGrowthTracker` 只当门面 + 编排 |
| 方块写如何收口 | 新增 `BlockWriter.internalSetBlock`,统一 `INTERNAL_GROWTH` 守卫 + 具名 flag 常量 + 「禁止跨区块」契约,替换约 18 处 flag 字面量 |
| 集成测试如何补 | 单测只盖纯函数:用 Mockito `Level` + `ChunkCropData` 假实现盖「策略级 dispatch」;用 gametest 盖「区块加载追赶 + 邻居未就绪」与三路径收敛 |
| 哪些行为必须保留 | 见 §6 逐条清单(flax 自毁、水稻去水、茎收获重结果、骨粉回溯、segmented-rice 同步、三选一支撑、REGROW 栈、重入守卫、三路径一致、逐株隔离…),每条约定到具体代码锚点 |

最大的结构性收益:**两条几乎相同的 800 行 catch-up 循环(`onChunkLoadInternal` / `periodicCatchUpCheckInternal`)合并为一条参数化循环**,这是重复代码与「三路径可能漂移」的根因。

---

## 1. 现状病灶(拆分依据)

`CropGrowthTracker.java` 实际混合了至少 5 种职责(行号见 §2):

1. **区块注册表**(`trackedChunks` WeakHashMap set / register / unregister / rebase)——L74–119
2. **纯日历/模拟数学**(`simulateGrowth`/`simulateStem`/`seasonOfDay`/`countSuitableDays`/hash/回推 plantedDay)——L324–774、L908–1063
3. **作物分类 + 缓存**(`isGrowableCrop`/`isNonArable*`/`isSegmentedRice`/`getRiceSegment`/`getCropAge/MaxAge/StateForAge`/`isClimbCrop` + `FREEZE_CACHE`/`SEGMENTED_RICE_LOCATION`)——L121–321、L2695–2853
4. **世界副作用**(`getOrCreate`/`removePosition`/`placeAndTrack`/`applyMaturitySideEffects`/`placeDoubleUpperHalf`/`mutateToShortGrass`/茎/甘蔗/海带/爬藤的 setBlock)——遍布全文件
5. **编排**(两条 catch-up 循环 + ES 时钟 + debug 日志)——L1881–2693、L3269–3384

拆分的铁律:**先做「机械搬运」(方法体原样移动、只改可见性与签名),每步 `test` 全绿后才允许改逻辑**。

---

## 2. 目标分层(五层)

所有新类都放进现有 `crop` 包(平铺,避免 import 抖动;子包化是可选后续,不进本方案)。
`CropGrowthTracker` 保留全部**对外 public static 门面方法**,签名不变,内部改为委托——这样
`LevelMixin` / `CropGrowthHandler` / `CropGrowthConfig` / `CropGrowthGameTests` 的约 99 处调用点**零改动**。

### L1 纯函数层(离线可测,无 Level/BlockState 写)

| 新类 | 从 tracker 搬入的方法(原样) | 备注 |
|---|---|---|
| `CropCalendar` | `seasonOfDay`、`countSuitableDays`、`isSeasonSuitable`、`resolveSuitableSeasons`、`getTermLength/getSeasonLength` 的纯部分 | 依赖 `SeasonTagResolver.ORDERED_SEASONS`;`resolveSuitableSeasons` 里 `Season.NONE → ALL_SEASONS` 分支保留 |
| `CropSimulation` | `simulateGrowth`(两个重载)、`simulateStem`(两个重载)、`GrowthSimulation`/`StemSimulation` record、`clampSimDay`、`HARD_MAX_ELAPSED_DAYS`、`hashLong`/`attemptHash` | `clampSimDay`/`HARD_MAX` 是 bug#1 教训,单测已有,搬移后测试名与断言不动 |
| `PlantedDayMath` | `backCalculatedPlantedDay`、`backCalculatePlantedDaySuitable`、`heightCropPlantedDayAfterBonemeal`、`clampPlantedDay`、`stemPlantedDayAfterHarvest`、`stemPlantedDayAfterBonemeal` | 骨粉回溯 / 茎收获重结果 / COMPANION 传播的纯判定,全部 package-private,测试直接可触 |

> 搬移时保留 `DebugProfiler.startSection/endSection` 包裹(`simulateGrowth`/`simulateStem` 生产重载里),
> 不要因为「纯函数」就把埋点删掉——埋点属于可观测性,不在「副作用」之列。

### L2 分类层(只读,缓存)

| 新类 | 内容 |
|---|---|
| `CropClassifier` | `isGrowableCrop`、`isRegrow`、`isNonArableBlock`、`isFreezeOverride`、`isNonArableAt`、`isSegmentedRice`、`getRiceSegment`、`syncRiceSegments`(两个重载)、`stemAnchor`、`getCropAge`、`getCropMaxAge`、`getCropStateForAge`、`getCropStateForAgePreserving`、`isClimbCrop`、`isDoubleCropUpperHalf`、`isUpperHalfOf`、`needsUpperHalfPlacement`、`isKelp` + `FREEZE_CACHE`/`SEGMENTED_RICE_LOCATION` 缓存 + `clearFreezeCache` |
| `CropKindResolver`(已存在) | 不动;`AgeCrop`/`HeightCrop`/`RegrowCrop` 描述子已就绪,直接复用 |

> `getCropAge/MaxAge/StateForAge` 与 `isGrowableCrop` 都在 `LevelMixin` 热路径上(instanceof 链 + 缓存),
> 搬到 `CropClassifier` 后**仍是 static 方法 + 相同判定顺序**,不得引入反射/正则/分配(§5.2-5)。

### L3 世界写层(副作用集中)

| 新类 | 内容 | 关键约束 |
|---|---|---|
| `BlockWriter` | `internalSetBlock(Level, BlockPos, BlockState, int flags)` + 具名 flag 常量 | 见 §4,唯一 setBlock 入口 |
| `EntryStore` | `getOrCreate`(两个重载)、`isTracked`、`getPlantedDay`、`removePosition`、`resetPlantedDay`、`placeAndTrack`(两个重载)、chunk 数据访问 helper | `getOrCreate` 里的甘蔗/海带走根、REGROW 上半块跳过、KC 中上段跳过、DOUBLE 上半块跳过——**顺序与早退原样保留** |
| `MaturitySideEffects` | `MaturitySideEffect` 枚举、`decideSideEffect`、`applyMaturitySideEffects`、`placeDoubleUpperHalf`、`mutateToShortGrass` | TRANSFORM→COMPANION→DOUBLE→BONEMEAL 优先级、`keepEntry`、`climbCrop` 阻断 BONEMEAL 原样 |

### L4 策略层(每 kind 处理一个条目)

统一接口(起始形态,实现期可微调字段):

```java
interface GrowthStrategy {
    /** 该策略是否负责此 block(dispatch 用)。 */
    boolean appliesTo(Block block);
    /** 处理一个已追踪条目;返回该条目的去留。 */
    Disposition process(CatchUpContext ctx, BlockPos pos, BlockState state, CropProgressEntry progress);
}
enum Disposition { KEEP, REMOVE }
```

`CatchUpContext` 打包:`{Level level, LevelChunk chunk, ChunkCropData chunkData,
Map<BlockPos,CropProgressEntry> cropData, int currentDay, Season currentSeason,
int seasonLength, boolean duringChunkLoad, int[] grown, int[] removed}`
(计数器用 `int[]` 或可变小对象,避免策略间共享不可变返回值带来的额外封装)。

| 策略 | 搬入方法 | 兜住的行为 |
|---|---|---|
| `StemStrategy` | `processStem`、`tryPlaceStemFruit`、`isFruitSupport`、`shouldPlaceStemFruitBeforeMutate`、`allowedStemFruitDirections`、`backCalculateStemPlantedDay`、`onStemFruitHarvest`、`onStemBonemeal` | `duringChunkLoad` 跳过结瓜(死锁 B)、三选一支撑、茎收获重结果、已结瓜先补放果实再变异 |
| `HeightStrategy` | 甘蔗:`getSugarCaneHeight`/`growSugarCane`/`onSugarCaneHarvest`/`onSugarCaneBonemeal`;海带:`getKelpHeight`/`growKelp`/`onKelpHarvest`/`onKelpBonemeal`、`KELP_MAX_HEIGHT` | 只追踪根、非底部条目移除、不适季冻结、满高保留、砍中段反推 plantedDay、海带头→茎转换不被「crop A→crop B」误删 |
| `RegrowStrategy` | regrow 分支(product 属性读取 + `simulateGrowth(...,maxAge=1,nonArable=true)` + set product true) | 只追踪上半块、LOWER 半条目移除、满产保留 |
| `ClimbStrategy` | `tryClimbVine`、`isSameClimbFamily`、`isClimbFamilyBlock` | 每适季日爬 1 段封顶、TRANSFORM 当天爬 0 段 + 从 `cropData` 重读 live plantedDay、climb 必须 freeze |
| `AgeStrategy` | 「normal crops」分支:rice 段同步、DOUBLE 上半块、maturity 副作用、age 推进、`getCropStateForAgePreserving` 的使用 | 水稻去水、segmented-rice 同步、成熟分支三路径一致、climb 保留条目 |

> `StemBlock` 在 `CropKindResolver.kindOf` 里会被归入 AGE,但现有 loop 用 `instanceof StemBlock` 提前分派。
> 因此 dispatch 顺序**必须**与现状一致:**Stem → SugarCane → Kelp → Regrow → Age(默认)**,
> 不能改用 `kindOf` 的 HEIGHT>REGROW>AGE 顺序(那会把 Stem 漏进 Age)。这是「行为不变」的一个易错点,已在 §6.15 单列。

### L5 编排层(门面)

`CropGrowthTracker` 保留:

- 区块注册表:`trackedChunks` + `registerTrackedChunk`/`unregisterTrackedChunk`/`getTrackedChunks`/`rebasePlantedDays`
- ES 时钟:`getSolarDays`(主世界兜底)/`getSeason`/`getTermLength`/`getSeasonLength`
- debug:`logDebug`(两个重载)/`scanCropDataHealth`
- **合并后的一条 catch-up 循环** + `onChunkLoad`/`periodicCatchUpCheck` 两个守卫包裹
- 门面委托:把 §5 对外签名逐一 delegate 到上述 L1–L4 类

---

## 3. 两条循环合并(最大收益点)

现状 `onChunkLoadInternal`(L1911–2295)与 `periodicCatchUpCheckInternal`(L2341–2693)是 ~800 行近乎复制的 switch 链,
唯一行为差异:

| 差异 | onChunkLoad | periodic |
|---|---|---|
| `duringChunkLoad` | true | false |
| 回卷守卫(plantedDay>currentDay → 重设为 currentDay) | 有 | 无(依赖 ServerTick 先 rebase) |
| 末尾幂等 `registerTrackedChunk` | 有 | 仅空时 `unregister` |

合并方案:抽出单一 `catchUpInternal(chunk, level, currentDay, currentSeason, seasonLength, duringChunkLoad)`:
- **总是**执行回卷守卫与幂等注册(两者对 periodic 都是安全 no-op/幂等,行为不变);
- 快照/预算/watchdog/逐株 try-catch 只写一份;
- 逐株体替换为 `dispatchEntry(ctx, pos, state, progress)` → `Disposition`。

保留两个薄入口:

```java
public static void onChunkLoad(chunk, level)      { guard(() -> catchUpInternal(chunk, level, getSolarDays, getSeason, getSeasonLength, /*duringChunkLoad*/true)); }
public static void periodicCatchUpCheck(...)      { guard(() -> catchUpInternal(chunk, level, currentDay, currentSeason, seasonLength, /*duringChunkLoad*/false)); }
```

> 「回卷守卫对 periodic 是 no-op」的前提是 `ServerTickEvent.Post` 先 rebase;该顺序在 `CropGrowthHandler.onServerTick`
> 中已成立,合并后不改动该调用时序即可。此前提要在实现注释里写明,防止后人误删 rebase。

---

## 4. 方块写收口(`BlockWriter`)

### 4.1 唯一入口

```java
public final class BlockWriter {
    // 具名 flag:语义与 NeoForge 一致,禁止再散落裸 2/3
    static final int FLAG_UPDATE_CLIENTS   = Block.UPDATE_CLIENTS;                       // 2  生长阶段变更(默认)
    static final int FLAG_UPDATE_NEIGHBORS = Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS; // 3  需通知邻居(果实放置/变异)
    // 内部写统一入口:守卫 + 边界约束
    public static boolean internalSetBlock(Level level, BlockPos pos, BlockState state, int updateFlags) {
        boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
        try {
            return level.setBlock(pos, state, updateFlags);
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
    }
}
```

### 4.2 两条硬约束(写进类 Javadoc 与实现)

1. **重入守卫**:一律用 save/restore(不是无条件 clear),`ThreadLocal` 只在 `InternalGrowthFlag`(不进 Mixin)——§5.1-6。
2. **禁止跨区块**:`internalSetBlock` 自身只写同区块。真正的跨区块操作只有 `StemStrategy.tryPlaceStemFruit`
   (`fruitPos = pos.relative(dir)` 可能落在邻区块)。规则:该操作**只允许在 `!duringChunkLoad` 时被调用**,由
   `StemStrategy.process` 依据 `ctx.duringChunkLoad` 决定是否跳过结瓜(死锁 B 的修法保持原样)。
   - 可选加固(dev-only,不进生产热路径):`assert level.getChunkAt(pos) == level.getChunkAt(target)` 仅当跨区块调用点传入一个显式 `allowCrossChunk` 时豁免。

### 4.3 替换清单(约 18 处,flag 语义逐一映射)

| 现状调用点 | 现状 flags | 收口后 |
|---|---|---|
| `syncRiceSegments` setBlock | 2 | `internalSetBlock(..., FLAG_UPDATE_CLIENTS)` |
| `tryPlaceStemFruit` 果实 / 附茎两处 | 3 | `internalSetBlock(..., FLAG_UPDATE_NEIGHBORS)`(仍跨区块,受 4.2-2 门控) |
| `processStem` 变异 / 推进两处 | 2 | `internalSetBlock(..., FLAG_UPDATE_CLIENTS)` |
| `growSugarCane` | 2 | 同上 |
| `growKelp` 头→茎 + 新头两处 | 2 | 同上 |
| catch-up regrow setBlock | 2 | 同上 |
| catch-up age 推进(load + periodic 两处) | 2 | 同上 |
| `placeAndTrack`(两个重载) | 2 | 同上 |
| `placeDoubleUpperHalf` | 2 | 同上 |
| `mutateToShortGrass` 主体 / 清上半块 | 传入 2 或 3 | 保留入参 `flags` 但用具名常量调用 |
| `CropGrowthHandler.onCropGrowPre` 手动守卫 + setBlock | 2 | `internalSetBlock(..., FLAG_UPDATE_CLIENTS)`(事件路径的 `placeDoubleUpperHalf` **先于** lower 写入,顺序不动) |
| `CropGrowthHandler.onCropGrowPre` 变异 | 3 | `internalSetBlock(..., FLAG_UPDATE_NEIGHBORS)` |
| `LevelMixin` 内部 revert + `flags \| UPDATE_KNOWN_SHAPE` | 2 / 16 | 保留在 Mixin 内(§5.1-6 ThreadLocal 不进 Mixin;这里不碰,只保证 `BlockWriter` 写出的都被 `LevelMixin` 追加 16) |

> `UPDATE_KNOWN_SHAPE`(16)仍然**只在 `LevelMixin`** 追加(死锁 A 修法),`BlockWriter` 不重复加,
> 避免「双加」导致 flag 语义漂移。`BlockWriter` 的 Javadoc 明确:它负责守卫与语义命名,`LevelMixin` 负责补 16。

---

## 5. 对外门面(必须保留、签名不变)

以下方法被 `LevelMixin`/`CropGrowthHandler`/`CropGrowthConfig`/`CropGrowthGameTests` 直接调用,拆分后
`CropGrowthTracker` 必须保留同签名 static 委托,否则红线文件被牵动:

`isGrowableCrop` `isRegrow` `isSegmentedRice` `getRiceSegment` `syncRiceSegments` `isClimbCrop`
`getCropAge` `getCropMaxAge` `getCropStateForAge` `getCropStateForAgePreserving`
`resolveSuitableSeasons` `isNonArableAt` `simulateGrowth` `simulateStem` `mutateToShortGrass`
`applyMaturitySideEffects` `placeDoubleUpperHalf` `getOrCreate`(两个) `processStem` `removePosition`
`isTracked` `resetPlantedDay` `onStemFruitHarvest` `onSugarCaneHarvest` `onKelpHarvest`
`onKelpBonemeal` `onSugarCaneBonemeal` `onStemBonemeal` `onCropBonemeal`
`getSolarDays` `getSeason` `getTermLength` `getSeasonLength`
`getTrackedChunks` `rebasePlantedDays` `registerTrackedChunk` `unregisterTrackedChunk`
`onChunkLoad` `periodicCatchUpCheck` `clearFreezeCache`

package-private 供测试直接触达的:`clampSimDay` `simulateGrowth(long,…)` `simulateStem(long,…)`
`backCalculatedPlantedDay` `backCalculatePlantedDaySuitable` `heightCropPlantedDayAfterBonemeal`
`clampPlantedDay` `stemPlantedDayAfterHarvest` `stemPlantedDayAfterBonemeal`
`isFruitSupport` `shouldPlaceStemFruitBeforeMutate` `needsUpperHalfPlacement` `isUpperHalfOf`
`isDoubleCropUpperHalf` `decideSideEffect` `HARD_MAX_ELAPSED_DAYS`——搬移后这些类各自 package-private 即可,
单测 import 路径随之调整(见 §7 测试搬迁)。

---

## 6. 必须保留的行为 / 边界(逐条锚点)

> 这些是「重构不得回归」的清单,实现期作为 checklist 逐条打勾;每条都标注了它防住的回归。

1. **flax 自毁中和(双保险)** — `FlaxBlockMixin`:外部 `growCropBy` 的 LOWER 写 flag 3→2 降级 + 内部 `updateShape`
   在 `INTERNAL_GROWTH` 窗口返回原 state。拆 `placeDoubleUpperHalf`/`mutateToShortGrass` 时不得改变:
   (a) 事件路径 `placeDoubleUpperHalf` 先于 lower setBlock;(b) `FlaxDiagnostics` hook 仍位于 `LevelMixin` 两道早退**之前**。
2. **水稻去水** — `getCropStateForAgePreserving`/`riceLocation != null ? state.setValue(AGE,newAge) : getCropStateForAge(...)`
   分支保留 WATERLOGGED/LOCATION;segmented rice **绝不**走 `CropBlock.getStateForAge`(会重建 default state 丢 WATERLOGGED)。
3. **茎收获重结果** — `LevelMixin` 的 `AttachedStemBlock→StemBlock` 分支仍走 `onStemFruitHarvest`(经 `backCalculateStemPlantedDay`
   的适季日回推,「刚成熟」而非整轮重来),**不得**改成 `resetPlantedDay`。
4. **骨粉回溯(三态)** — `onCropBonemeal`(非茎,保守适季回推,只许更早、不许跨不适季)、`onStemBonemeal`(日历捷径)、
   `onKelpBonemeal`/`onSugarCaneBonemeal`(高度)。规则:已有条目 → 回推;无条目 → `getOrCreate` 按 `currentAge` 回推;
   **绝不**把已追踪条目 plantedDay 前移。
5. **segmented-rice 同步** — `syncRiceSegments` 用 `setValue(AGE)` flag 2 + 守卫 save/restore;只追踪 DOWN;MIDDLE/UP 条目移除;
   成熟/推进后都要显式同步(因为 `UPDATE_CLIENTS` 不触发 `updateShape`)。
6. **茎果实支撑三选一** — `isFruitSupport` = `FarmBlock(含 FD 沃土) || BlockTags.DIRT || isFaceSturdy(UP)`;FarmBlock 分支不可省
   (15px 半格顶面非 face-sturdy)。
7. **REGROW 回退栈** — `LevelMixin` 的 `REGROW_REVERT_STACK` LIFO、非 null 哨兵 `NO_REVERT`、每帧压/弹、revert 在 `INTERNAL_GROWTH` 内。
   拆分不触碰 Mixin,但要保证 `RegrowStrategy`/`EntryStore` 不改 has_seeds 的非内部写入语义。
8. **重入守卫** — 所有内部 setBlock 走 `BlockWriter.internalSetBlock`(save/restore);`ThreadLocal` 只在 `InternalGrowthFlag`。
9. **热路径两道早退顺序** — `LevelMixin`:`isClientSide()` → `INTERNAL_GROWTH`,二者不可颠倒且都在 revert 帧压栈之前(不碰)。
10. **三路径收敛** — 事件 / chunk-load / periodic 共享同一套「推进 + 成熟处理」逻辑(合并后的 `catchUpInternal` + 事件路径复用
    `AgeStrategy` 的同一推进/成熟方法),禁止三处各自实现。
11. **成熟分支三路径一致** — climb 保留条目;带 transform/topBlock/doubleAge 覆写先 `applyMaturitySideEffects` 按 keepEntry 去留;
    其余 `removePosition`;`currentAge >= maxAge` 的提前路径仍要补发 rice 同步 + 覆写副作用。
12. **BONEMEAL 回退防护** — `isValidBonemealTarget` 守卫 + 整体 try-catch(torchflower 教训);climb 作物阻断回退。
13. **逐株异常隔离** — 合并后的循环仍逐条目 try-catch,单株异常只 `warn(cropId, pos, 摘要)` 并跳过。
14. **性能红线** — HashMap 存储、WeakHashMap trackedChunks、ArrayList 快照、预算/watchdog、`countSuitableDays` O(1)+≤24 段、
    禁逐天循环、日志短路。合并循环后这些保持一份。
15. **dispatch 顺序** — `Stem → SugarCane → Kelp → Regrow → Age`,不得用 `kindOf` 的 HEIGHT>REGROW>AGE 直接替换
    (否则 `StemBlock` 会漏进 Age;且 `SugarCane`/`Kelp` 虽同为 HEIGHT 但有各自常数 3/26 与 grow/harvest)。
16. **NBT 与存档** — 键 `pastoralcraft_crop_data`、元素 `{pos:long, plantedDay:int}` 不变(不碰 `CropGrowthHandler` NBT 段)。
17. **bug 修复教训** — `clampSimDay`/`HARD_MAX_ELAPSED_DAYS`(无界循环)、`UPDATE_KNOWN_SHAPE`(死锁 A)、
    `duringChunkLoad` 跳过结瓜(死锁 B)——搬移时三者一字不差。

---

## 7. 集成测试补法

### 7.1 现状
- 单测 9 个文件,只覆盖纯函数 + 少量 Mockito(`isFruitSupport` 用 `mock(Level.class)`)。
- gametest `CropGrowthGameTests`(602 行)已盖行为烟测:wheat、番茄 TRANSFORM、甘蔗、海带、瓜茎结果、FD 稻 COMPANION、
  flax 三路自毁(event-path / growCropBy / bonemeal)、向日葵 REGROW、KC 三段稻。
- **缺口**:没有 `ChunkEvent.Load` 追赶路径、没有死锁 A/B 回归、没有三路径收敛断言、catch-up 循环无单测。

### 7.2 补什么

**(A) 策略级集成测试(Mockito `Level` + `ChunkCropData` 假实现)** —— 不碰真服务器,最高性价比:

- 新增 `TestChunk`(实现 `ChunkCropData`,`HashMap` 存条目)解耦 `LevelChunk`(难 mock/final)。
- 用 `mock(Level.class)` 桩住 `getBlockState`/`getChunkAt`/`dimension().location()`,让
  `AgeStrategy.process` / `StemStrategy.process` / `HeightStrategy.process` / `RegrowStrategy.process` 可直接驱动单条目。
- 用 Mockito `verify(level, never()).getBlockState(neighborPos)` 断言:**`duringChunkLoad=true` 时 `StemStrategy` 不读邻区块**
  (死锁 B 的决策层回归,真实阻塞行为无法在单测复现,但「不触发跨区块读」这个决策可测)。
- 断言内部写都用 `FLAG_UPDATE_CLIENTS`(Mockito 捕获 `setBlock` 的 flags 实参)。

**(B) gametest 增补**(真实 server,补 `ChunkEvent.Load` 路径 + 三路径收敛):

1. **区块加载追赶**:放置作物 → `setChunkForced(false)` 卸载 → 推进 solarDay → 强制重载 → 断言阶段跳变。
   覆盖「邻居区块未 FULL 时 onChunkLoad 不卡死、阶段仍正确」。
2. **跨区块茎结果推迟**:把成熟瓜茎放在区块边缘(x=15),`duringChunkLoad=true` 时跳过结瓜、周期补涨后再结果;
   断言无死锁 + 果实最终出现。
3. **三路径收敛**:三株同参作物,分别走「事件 postCropGrowPre / 卸载重载 / 纯周期」,断言最终 age 完全一致。
4. (已有 flax/rice/segmented-rice 测试保留为回归锚点,不重复新增)

**(C) 测试搬迁**:现有 `CropGrowthTrackerTest`/`CropStemSimulationTest` 按 §2 拆分后,把断言**原样**搬到
`CropCalendarTest`/`CropSimulationTest`/`PlantedDayMathTest`/`CropClassifierTest` 等对应新类测试;
`CropSideEffectTest`/`MaturitySideEffectTest` 的 `ensureMinecraftBootstrapped`(反射注入空 LoadingModList + 假 WorldVersion)
抽成共享 `MinecraftBootstrapRule` 复用。

> 约束:纯函数新增逻辑必须补单测并跑 `test`;Level/chunk 交互优先「抽决策成 package-private 纯函数 + Mockito」,真实 setBlock 走实机/gametest(§6 测试纪律)。

---

## 8. 第三方作物结构探测收口(方向 4,可选、放最后)

现状脆弱点:`findSegmentProperty` 扫 `location` 0..2(KC 稻)、`FlaxDiagnostics.isFlax`(字符串)、FD 番茄 `TomatoBlockMixin` 字符串 target。

方案(低优先级,Phase 4 再做):
- 新增 `CropRegistry`:显式 `Map<ResourceLocation, StructureDescriptor>`,`StructureDescriptor` 描述
  `{segmentedRiceLocation, doubleCrop, climbFamily, ...}`。
- 迁移策略:**现有结构扫描降级为「默认提供者」**(行为不变的桥),注册表成为唯一查询点;
  已有扫描结果作为 fallback,后续对已知 mod-id/block-id 显式登记即可绕过脆弱扫描。
- 收益:显式、可配置、可测试;风险:涉及热路径查询点重路由,必须等 §1–§7 全绿后单独一轮。

> 落地蓝本见 [`crop-structure-registry-blueprint.md`](crop-structure-registry-blueprint.md):
> 载体用 NeoForge `DataMapType<Block, StructureDescriptor>`,JSON 字段语义照抄 Unloaded Activity 的 `simulate_info`,
> 泛化扫描降级为默认提供者。

---

## 9. 分期与验收

| 阶段 | 内容 | 验收 |
|---|---|---|
| P0 | 基线:`./gradlew test` 全绿 + 记录 gametest 通过清单 | 无改动 |
| P1 | 机械拆分 L1–L3(纯函数/分类/世界写),tracker 改委托,测试按 §7C 搬迁 | `compileJava` + `test` 全绿;gametest 与 P0 一致 |
| P2 | 合并两条 catch-up 循环为 `catchUpInternal` + 引入 `GrowthStrategy` dispatch | 同上;逐条打勾 §6 清单 |
| P3 | 方块写收口 `BlockWriter` + 具名 flag,替换 §4.3 全部调用点 | 同上;`git diff` 显示 flag 语义未变 |
| P4 | 补集成测试(§7A Mockito + §7B gametest) | 新增测试全绿,含死锁 B 决策回归 |
| P5(可选) | 第三方结构注册表(§8) | 单独一轮,过 test + gametest |
| P6(候选) | 「门控/委托生长」可行性试点(§11) | 独立评估,不并入 P0–P4 门禁 |

每阶段结束「行为不变」是硬门禁;只有 P0–P4 全绿后才允许进入 P5 / P6 或任何逻辑改动。

---

## 10. 风险与对策

| 风险 | 对策 |
|---|---|
| 拆分破坏 dispatch 顺序 / 早退顺序 | §6.15 单列;dispatch 顺序写进 `GrowthStrategies` 单元测试 |
| 门面签名遗漏导致红线文件被改 | §5 列出全部对外方法,实现期 grep `CropGrowthTracker\.` 核对 99 处调用点 |
| 循环合并不等价(回卷守卫/幂等注册差异) | §3 明确「总是执行回卷守卫 + 幂等注册」;P2 用同参双路径对拍断言 |
| Mockito `Level` 桩过深、脆弱 | 只桩策略真正读的方法;跨区块断言用 `never()` 而非模拟真实阻塞 |
| 埋点(Profiler/Diagnostics)被当副作用误删 | §2 L1 备注:埋点保留 |
| 改动面过大 | 严格机械搬运优先,禁止在 P1–P3 顺手改逻辑/格式;每步可独立 revert |

---

## 11. Phase 6 候选:「门控随机刻 / 委托生长」可行性评估

> 来源:用户提出「只限制作物何时接受随机刻,让原版自己长」的想法,本文记录结论与官方源码锚点。
> 结论:**纯门控不可行,但「日历当调度器 + 委托方块自己的生长方法改状态」是一个值得拆完后试点的中间形态。**

### 11.1 官方机制事实(NeoForge 1.21.1 源码,本地 `reference/NeoForge-1.21.1` 已查证)

1. **`CropGrowEvent.Pre` 就是作物随机刻的「门」**,且只在随机刻触发的生长尝试里发。
   官方 javadoc:*"Fired when any 'growing age' blocks … attempt to advance to the next growth age state **during a random tick**"*
   (`src/main/java/net/neoforged/neoforge/event/level/block/CropGrowEvent.java` L25-29)。
2. 由 `CommonHooks.canCropGrow(level, pos, state, def)` 触发,`Result ∈ {GROW, DEFAULT, DO_NOT_GROW}`
   (`src/main/java/net/neoforged/neoforge/common/CommonHooks.java` L919-923)。
   官方 javadoc:[CropGrowEvent.Pre.Result](https://nekoyue.github.io/ForgeJavaDocs-NG/javadoc/1.21.x-neoforge/net/neoforged/neoforge/event/level/block/CropGrowEvent.Pre.Result.html)。
3. **没有「通用随机刻门」**:随机刻在 `ServerLevel.tickChunk` → 随机抽位置 → `blockState.randomTick(...)` 分发,该循环上**没有 NeoForge 事件**。
   「限制何时对作物随机刻」只能 Mixin 进 `ServerLevel`/`LevelChunk` 随机刻循环——最热、最易与 Lithium 等冲突的路径。
   相关官方 issue:[NeoForge#2679](https://github.com/neoforged/NeoForge/issues/2679)、[Documentation#317](https://github.com/neoforged/Documentation/issues/317)。

> 推论:当前 `CropGrowEvent.Pre`(LOWEST,`DO_NOT_GROW`)**已经是「门控随机刻」**;区别只是门控之后我们把「怎么长」自己接管了,而非放回原版。

### 11.2 纯「门控」不可行的三个硬阻塞

1. **丢确定性(核心支柱)** — 原版随机刻生长是概率性的(`CropBlock.growCrops` 过 `canCropGrow` 后仍受光照/肥力 `getGrowthSpeed` 影响,
   部分方块每次增量还是随机的)。门控只能决定「tick 能否触发」,决定不了「触发后是否真的 +1 阶段」。
   「一个 stage / N 天」无法由门控保证 → 破坏 `plantedDay` 的「同 plantedDay → 同 stage」不变量 → 卸载追赶、改配置、三路径收敛全崩。
2. **卸载区块仍长不了** — 随机刻只在已加载区块发生;`plantedDay + ChunkEvent.Load 追赶` 的存在理由就是卸载期间也要按日历长。
   门控不解决卸载增长 → 「不用考虑作物怎么长」落空。
3. **自定义行为省不掉** — 不适季三段掷骰、茎结果三选一支撑、甘蔗/海带高度、向日葵 `has_seeds`、番茄爬绳、COMPANION/TRANSFORM/DOUBLE
   都不是「原版随机刻生长」,门控放给原版也长不出来,仍需自研。

### 11.3 可试点的中间形态(拆完后评估)

保留 `plantedDay` 日历当**调度器**(决定哪天允许长一步,保住确定性/卸载追赶/季节逻辑),
把「改状态」从「自算 `getCropStateForAge` + `setBlock`」换成「调用方块自己的单步生长方法(`growCrops` / 受控 `performBonemeal`)」:

- 收益:让原版/第三方方块自己做 `canSurvive`、邻居更新、掉落、跨模联动;兼容性收益集中在 AGE 普通作物;
  对甘蔗/海带/茎/爬藤/伴生**无帮助**,那部分维持自研。
- 风险:`growCrops` 概率 + 用 `level.random`,要确定性须做「受控单步」,许多模组方块不暴露干净的确定性入口(又退回结构探测);
  会与「纯函数推导阶段」解耦,三路径收敛从「状态可重算」变「副作用可重放」,风险不低。

### 11.4 试点路径(仅 AGE 快路径,作为独立 Phase 6)

1. 先完成 §1–§8 拆分,露出「调度器(纯函数日历)」与「状态变更(现 = 自 setBlock)」的边界缝——这正是本想法要替换的那条缝。
2. 仅对原版五类快路径(`CropBlock`)试点「委托生长」,甘蔗/海带/茎/爬藤/伴生不动。
3. 验收门槛对齐 §11.2 三条硬阻塞:`CropGrowthTrackerTest`/`CropSimulationTest` 全绿(确定性)、gametest 三路径收敛(卸载追赶一致)、
   以及「门控 = `CropGrowEvent.Pre`」已存在这一事实不变。
4. 试点不达标即回滚,不影响 P0–P4 主计划。
