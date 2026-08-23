# 已知问题 / 潜在 Bug 登记

> 状态:持续更新。记录「当前无碍,但后续可能成为 Bug」的小问题、既有取舍与边界行为,避免重复踩坑或误改已确认的决策。
> 关联:`AGENTS.md` §10 引用本文件;改 `StemStrategy` / `CropGrowthHandler` / `CropGrowthTracker` 的补涨链路前先查本表。
> 编号前 `[决策]` = 用户已拍板,勿擅改;`[既有]` = 修复前即存在的行为;`[观察]` = 暂无碍、后续需留意。

---

## 1. `[既有]` 非变异路径 chunk-load 结瓜延迟

- **现象**:卸载期间「已结瓜但未变异」的普通茎,chunk-load 时果实仍延后到下一个 solarDay(它不走变异分支,不标 `stemSettlementPending`),与变异路径的「同日强扫」不一致。
- **影响**:仅延迟(果实最终会补放),非丢失。最长 ~20 分钟(到下一 solarDay 或下一次 randomTick)。
- **后续动作**:若要求「三路径结瓜时序完全一致」,可让非变异路径在 chunk-load 时同样标 pending。当前不改。

## 2. `[决策]` 果实位被占时茎照常变异(果实可接受丢失)

- **现象**:`tryPlaceStemFruit` 返回 false(果实位被占,如贴墙)时,`processStem` 不检查返回值,茎照常变异 → 果实丢失。
- **影响**:果实位永久被占时果实无法保留;这是用户拍板的取舍(避免「成熟茎常驻」软性卡死)。
- **后续动作**:若将来要「放果失败则保留茎重试」,需重新评估软性卡死风险并征询用户。

## 3. `[观察]` `clearStemSettlementPending` 的「消费语义」

- **现象**:`CropGrowthHandler.onServerTick` 在扫描**之前**调用 `clearStemSettlementPending(level)`(消费标志)。
- **影响**:当前安全——扫描走 `duringChunkLoad=false`,`processStem` 不会再 `mark`,故「扫描中新标被误清」不可能发生。但若未来有人在扫描路径内新增 `mark`,会被本次 clear 吞掉。
- **后续动作**:新增扫描内 mark 逻辑时,把 clear 移到扫描之后(或改为「扫描后清」)。

## 4. `[观察]` `stemSettlementPending` 弱引用 GC 时序

- **现象**:该集合为 WeakHashMap 键控 `Level`;若 level 在 `mark` 与下一次 `onServerTick` 之间被卸载/GC,标志会随 GC 消失。
- **影响**:无正确性问题——此时 chunk 也已卸载,重新加载会再走 `onChunkLoad` 重新 `mark`。仅「强扫」被推迟,不影响果实最终结算。
- **后续动作**:无需改。

## 5. `[观察]` `tryPlaceStemFruit` 不校验茎 age

- **现象**:`tryPlaceStemFruit` 只校验 `instanceof StemBlock`,不校验 `AGE == MAX_AGE`;只要 `sim.fruited()==true` 就放果并转 attached。
- **影响**:正确——`fruited==true` 已蕴含 `stage >= maxAge`,世界 age 因卸载陈旧(如 age 0)时以 sim 为准放果是对的。
- **后续动作**:无需改;不要「顺手」加 age 校验,会破坏卸载补长语义。

## 6. `[观察]` 果实位被占 + 茎转换失败 → 可能双果(极边缘)

- **现象**:若 `tryPlaceStemFruit` 放果成功但茎→attached 转换失败(极罕见),茎仍为 StemBlock 且 `sim.fruited()` 恒真,下轮会再放一颗果(换方向)。
- **影响**:需「放果成功 + 同 tick 茎转换失败」同时发生,几乎不可能;且为既有行为(非 N1 引入)。
- **后续动作**:暂不改。

## 7. `[既有]` 同日强扫为全 level 扫描(非定向单 chunk)

- **现象**:`stemSettlementPending` 触发的是对整个 level 的 trackedChunks 全量再扫,而非仅重扫有延后茎的 chunk。
- **影响**:幂等(非延后条目 no-op),频率极低(跨季 + 卸载 + 已结瓜 + 已变异四条件同帧),可接受。
- **后续动作**:若将来延后茎变多,可改「待结算 chunk 集合」做定向重扫。
