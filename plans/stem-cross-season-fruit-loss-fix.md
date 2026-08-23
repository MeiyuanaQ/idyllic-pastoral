# 瓜茎跨季(适季→非适季)果实丢失修复计划

> 状态:已实施(第三版)。范围 = 修「chunk-load 果实丢失」时序问题,完整覆盖 N1–N7。
> **决策记录**:
> 1. 不采纳「成熟门控」(保留 §7 D1「茎类非适季机制维持现状」——未成熟茎仍可变异)。
> 2. **N2 决策:果实位被占时茎正常变异**(不保留重试,果实位永久被占属可接受损失),据此 N2 无需代码改动。

---

## 0. 结论速览

| 项 | 结论 |
|---|---|
| 修什么 | `StemStrategy.processStem` 变异分支:chunk-load 时先变异丢果(N1) |
| 根因 | 变异分支把「补放果实」与「变异」绑定;chunk-load 时 `!duringChunkLoad` 门控跳过放果但仍变异,「推迟到周期补涨」因茎已变草被 remove 而落空 |
| 修法 | 变异分支内:有果待放且 chunk-load 时延后(N1)+ 待结算集合强制同日再扫(N1) |
| 不改 | `CropSimulation.simulateStem` 纯函数;成熟门控;N2(放果失败仍变异,保留现状);非变异路径 |
| 新风险 | N4 条件写错致 attached 茎被误延后;N6 守卫泄漏(均以测试/review 约束) |

---

## 1. 决策边界

1. **不采纳成熟门控**:不在 `simulateStem` 给变异骰加 `stage >= maxAge`。
2. **不动纯函数**:`simulateStem` 的 `(stage, mutated, fruited)` 语义与 oracle 都不改。
3. **N2 决策**:`tryPlaceStemFruit` 返回 false(果实位被占)时**不保留重试、照常变异**;果实损失在该边缘情形下可接受,避免「成熟茎常驻」软性卡死。
4. **只动世界结算层**:`StemStrategy.processStem`(N1)+ `CropGrowthTracker`(待结算集合)+ `CropGrowthHandler.onServerTick`(同日再扫);N3/N5/N7 以验证 + 测试覆盖。

---

## 2. 精确定位(三条路径对 `mutated=true` 的现状)

| 路径 | duringChunkLoad | 现状 | 结论 |
|---|---|---|---|
| `CropGrowEvent.Pre` | false | 先 `tryPlaceStemFruit` 再变异(放果失败仍变异) | 正确 |
| `ServerTick` 周期补涨 | false | 先 `tryPlaceStemFruit` 再变异(放果失败仍变异) | 正确 |
| `ChunkEvent.Load` | true | 跳过 `tryPlaceStemFruit` 直接变异 | **N1 果实丢失** |

丢失序列:`sim.mutated()==true && sim.fruited()==true && state 是 StemBlock` → chunk-load 时
`if (!duringChunkLoad && shouldPlaceStemFruitBeforeMutate(...))` 为假 → 直接 `setBlock(SHORT_GRASS)` → 条目被 remove。
跨 chunk 写(`tryPlaceStemFruit` 读相邻 chunk)在 `ChunkEvent.Load` 内会死锁,故不能改为「加载时直接放果」。

---

## 3. 实现步骤(已落地)

### 3.1 `StemStrategy.processStem` — 变异分支加 N1 延后早退

```java
if (sim.mutated()) {
    // 已结瓜但果实尚未落地的普通茎(StemBlock):chunk-load 补涨不得先变异 ——
    // tryPlaceStemFruit 是跨 chunk 写(ChunkEvent.Load 内读相邻 chunk 会死锁),
    // 故整体推迟到周期补涨(其以 duringChunkLoad=false 重跑同一确定性 simulateStem,
    // 先补放果实再变异,果实不丢)。attached 茎果实已独立存在、未结瓜茎无果可补,直接变异。
    if (duringChunkLoad && shouldPlaceStemFruitBeforeMutate(sim.fruited(), state.getBlock())) {
        CropGrowthTracker.markStemSettlementPending(level);
        return false; // 早退在守卫之前,不触碰 INTERNAL_GROWTH
    }
    boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
    if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
    try {
        // 放果失败(果实位被占)仍继续变异 —— 决策:不保留重试,茎正常变异(N2)。
        if (!duringChunkLoad && shouldPlaceStemFruitBeforeMutate(sim.fruited(), state.getBlock())) {
            tryPlaceStemFruit(level, pos, state);
        }
        BlockWriter.internalSetBlock(level, pos, Blocks.SHORT_GRASS.defaultBlockState(), BlockWriter.FLAG_UPDATE_CLIENTS);
    } finally {
        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
    }
    CropGrowthTracker.logDebug(DebugGate.DebugModule.STEM, "...", pos, progress.plantedDay, currentDay);
    return true;
}
```

### 3.2 `CropGrowthTracker` — 待结算集合(已落地)

`stemSettlementPending`(弱引用 Set)+ `markStemSettlementPending` / `hasStemSettlementPending` / `clearStemSettlementPending`(均 public static)。

### 3.3 `CropGrowthHandler.onServerTick` — 待结算时跳过同日早退(已落地)

同日早退条件改为 `lastDay == currentDay && !hasStemSettlementPending(level)`;扫描前 `clearStemSettlementPending(level)` 消费标志。

---

## 4. N1–N7 逐项修复 + 严格评估

### N1 — chunk-load 变异丢果 + 同日早退卡死

- **修复**:3.1 延后早退 + 3.2/3.3 待结算集合强制同日再扫。
- **可行性**:高。新增弱引用 Set + 一处 early-out 分支,无状态机。
- **潜在 Bug**:消费时序——`clear` 在扫描前,扫描走 `duringChunkLoad=false` 不会再 `mark`,无「扫描中新标被清」;强扫幂等(事件路径已结算则 no-op)。
- **性能**:仅出现延后茎时对该 level 多一次幂等强扫,频率极低。

### N2 — 放果失败仍变异(果实位被占)

- **决策(不修)**:`tryPlaceStemFruit` 返回 false 时照常变异,果实损失可接受。避免「果实位永久被占 → 茎永不变异」的软性卡死。
- **可行性**:无代码改动,行为与现状一致。
- **潜在 Bug**:无新增;果实位被占时果实丢失为既有行为,已确认接受。
- **性能**:零。

### N3 — 延后窗口内玩家干预(破坏/骨粉/收获)

- **修复**:无代码改动,既有机制覆盖(破坏→remove;骨粉→plantedDay 回移→收敛;收获→无果可采)。
- **可行性**:验证 + 测试锁定即可。
- **潜在 Bug**:无。
- **性能**:零。

### N4 — attached 茎不可被误延后

- **修复**:复用 `shouldPlaceStemFruitBeforeMutate(fruited, block) == (fruited && block instanceof StemBlock)`,补测试。
- **可行性**:高,谓词已存在且正确。
- **潜在 Bug**:条件误写成 `duringChunkLoad && sim.fruited()`(漏 `instanceof StemBlock`)会误延后 attached 茎 → 浮空果实 + 茎残留。以测试锁定。
- **性能**:零。

### N5 — 延后重跑收敛(幂等)

- **修复**:无代码改动。`attemptHash` 的 `attempt` 为日历界定本地计数器,首个 roll 输入与 `currentDay` 无关 → 收敛。
- **可行性**:纯函数已保证,补测。
- **潜在 Bug**:无;`rebasePlantedDays` 同步平移不变。
- **性能**:零。

### N6 — 重入/守卫泄漏

- **修复**:3.1 两处 `return false` 均在守卫**之前**,不 save/set 无 restore 责任。
- **可行性**:纯纪律。
- **潜在 Bug**:误写进 try 块会泄漏 `INTERNAL_GROWTH`;以 review 清单约束。
- **性能**:零。

### N7 — 与 `catchUpTimeBudgetMs` 交互

- **修复**:无代码改动。早退不耗预算;强扫复用原预算截断。
- **可行性**:既有机制无需改。
- **潜在 Bug**:无。
- **性能**:可忽略(见 N1)。

---

## 5. 测试与构建

1. **纯函数**:`shouldPlaceStemFruitBeforeMutate` 三分支(N4);`mark/has/clearStemSettlementPending` 集合行为(N1);`simulateStem` currentDay+1..+3 收敛(N5)。
2. **Mockito/gametest**:chunk-load `mutated && fruited && StemBlock` 不变异且标 pending(N1);chunk-load `AttachedStemBlock` 立即变异(N4);破坏干预(N3)。
3. **构建纪律**:`./gradlew test` + `./gradlew compileJava`;真实 setBlock 走 `runGameTestServer`。

---

## 6. 回填状态

- [x] N2 决策确认(果实位被占仍变异)
- [x] 3.1–3.3 落地
- [x] §5 纯函数测试全绿(`shouldPlaceStemFruitBeforeMutate` 三分支 + `stemSettlementPending` 集合 + `simulateStem_deferredRescan_converges`)
- [x] `compileJava` + `test` 通过
- [x] 实机测试 1/2/3/4/5 全部通过(跨季卸载果实保留 / attached 茎 / 果实位被占 / 延后窗口破坏 / 回归)
- [x] §7 澄清:果实下方为耕地的「变泥土」是**原版预期行为**,此前误改已全部回退(见 §7)

---

## 7. 果实下方耕地被瓜覆盖后变泥土 = 原版预期(最终修复:显式 turnToDirt)

**结论**:果实位下方是耕地(FarmBlock/FD 沃土)时,结瓜后耕地应被瓜覆盖并按原版逻辑变泥土。
原版链路是 `果实放置 → 耕地 updateShape(UP) → canSurvive 为假 → scheduleTick → tick → turnToDirt`。

**问题**:内部 `setBlock` 的邻居通知(flag 1)在 1.21.1 的 `NeighborUpdater` 下**并未触发** `FarmBlock.updateShape(UP)`
的 decay 链(实机复现:恢复 `FLAG_UPDATE_NEIGHBORS` 后耕地仍不变),故不能依赖原版邻居通知路径。

**最终修法**:`StemStrategy.tryPlaceStemFruit` 放果后,若支撑仍是 `FarmBlock`,显式调用
`FarmBlock.turnToDirt(null, supportNow, level, fruitPos.below())`(原版同款方法,含实体上推 + BLOCK_CHANGE 事件)。
幂等:先重读 `supportNow`,若邻居通知已把它变泥则不重复转换。

**评估**:可行性高(单处 if + 原版方法);潜在 Bug 无(幂等、仅耕地方案);性能零(每果一次 O(1) 判断 + 至多一次 setBlock)。
`turnToDirt` 在 `tryPlaceStemFruit` 内调用,处于 `processStem` 的 `INTERNAL_GROWTH` 守卫覆盖下,不会触发 LevelMixin 误追踪。

**回退记录**(此前基于误读所做的两处改动已撤销):
1. `tryPlaceStemFruit` 恢复为果实一律 `FLAG_UPDATE_NEIGHBORS` 放置。
2. 删除 `data/minecraft/tags/blocks/maintains_farmland.json`。
