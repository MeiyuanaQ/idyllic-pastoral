package com.crispyraccoon.pastoralcraft.crop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.teamtea.eclipticseasons.api.constant.solar.Season;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Melon/pumpkin stem lifecycle: deterministic growth, fruiting, unsuitable-season
 * mutation, and the harvest/bonemeal {@code plantedDay} back-calculation.
 *
 * <p>Extracted from {@link CropGrowthTracker} (mechanical move, no logic change).
 * All internal writes go through {@link BlockWriter}.</p>
 */
public final class StemStrategy {

    private StemStrategy() {
        // Utility class — prevent instantiation.
    }

    /**
     * Resolve the allowed horizontal fruiting directions for melon/pumpkin
     * stems from config, preserving the configured order (which also defines
     * the deterministic check order). Falls back to EAST then NORTH when the
     * configured list is empty or unparsable.
     */
    private static Direction[] allowedStemFruitDirections() {
        String cfg = CropGrowthConfig.STEM_FRUIT_DIRECTIONS.get();
        List<Direction> dirs = new ArrayList<>();
        for (String part : cfg.split("[, ]+")) {
            switch (part.trim().toLowerCase(Locale.ROOT)) {
                case "north" -> dirs.add(Direction.NORTH);
                case "south" -> dirs.add(Direction.SOUTH);
                case "east" -> dirs.add(Direction.EAST);
                case "west" -> dirs.add(Direction.WEST);
                default -> { /* ignore unknown directions */ }
            }
        }
        if (dirs.isEmpty()) {
            return new Direction[]{Direction.EAST, Direction.NORTH};
        }
        return dirs.toArray(new Direction[0]);
    }

    /**
     * Attempt to place a fruit (pumpkin or melon) adjacent to a fully-grown stem.
     * Checks the configured horizontal directions (default east/north) for a valid
     * placement: the target block must be air, and the block below it must be
     * sturdy (supporting the fruit).
     *
     * <p>On success, the fruit block is placed at the adjacent position and the
     * stem is converted to {@link AttachedStemBlock} facing the fruit.</p>
     *
     * <p>This is deterministic: the same stem always tries the same directions
     * in the same configured order, so results are consistent between real-time
     * and chunk-load catch-up.</p>
     *
     * @param level the world level
     * @param pos   the stem block position
     * @param state the stem block state (must be StemBlock at MAX_AGE)
     * @return true if a fruit was successfully placed
     */
    public static boolean tryPlaceStemFruit(Level level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof StemBlock)) return false;
        long t0 = DebugProfiler.startSection();

        // Derive fruit and attached stem from the stem's registry name
        ResourceLocation stemId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        Block stemBlock = state.getBlock();
        final Block fruitBlock;
        final Block attachedStemBlock;
        if (stemBlock == Blocks.MELON_STEM) {
            fruitBlock = Blocks.MELON;
            attachedStemBlock = Blocks.ATTACHED_MELON_STEM;
        } else if (stemBlock == Blocks.PUMPKIN_STEM) {
            fruitBlock = Blocks.PUMPKIN;
            attachedStemBlock = Blocks.ATTACHED_PUMPKIN_STEM;
        } else {
            // Unknown stem block (e.g. another mod's stem) — skip fruiting.
            return false;
        }

        // Check the configured horizontal directions in deterministic order.
        Direction[] directions = allowedStemFruitDirections();
        for (Direction dir : directions) {
            BlockPos fruitPos = pos.relative(dir);
            BlockState fruitTarget = level.getBlockState(fruitPos);
            BlockState belowFruit = level.getBlockState(fruitPos.below());

            // Must be air and have sturdy support below. Vanilla StemBlock checks
            // FARMLAND or the DIRT tag explicitly (never isFaceSturdy) because
            // FarmBlock is a 15px-tall half-cube whose top is NOT face-sturdy —
            // a plain isFaceSturdy check would make stems on farmland never fruit.
            if (!fruitTarget.isAir()) continue;
            if (!isFruitSupport(belowFruit, fruitPos.below(), level)) continue;

            // Place the fruit
            BlockWriter.internalSetBlock(level, fruitPos, fruitBlock.defaultBlockState(), BlockWriter.FLAG_UPDATE_NEIGHBORS);
            // 果实下方若是耕地,按原版逻辑压毁为泥土(瓜在耕地上方,耕地应变为泥土)。
            // 显式调用 turnToDirt 兜底:内部 setBlock 的邻居通知在 1.21.1 的 NeighborUpdater 下
            // 未必触发 FarmBlock.updateShape(UP)→scheduleTick→tick 的 decay 链。
            BlockState supportNow = level.getBlockState(fruitPos.below());
            if (supportNow.getBlock() instanceof FarmBlock) {
                FarmBlock.turnToDirt(null, supportNow, level, fruitPos.below());
            }
            // Convert stem to AttachedStemBlock facing the fruit
            BlockState attachedState = attachedStemBlock.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, dir);
            BlockWriter.internalSetBlock(level, pos, attachedState, BlockWriter.FLAG_UPDATE_NEIGHBORS);

            CropGrowthTracker.logDebug(DebugGate.DebugModule.STEM, "StemBlock fruiting: placed {} at {} facing {}",
                    stemId, fruitPos, dir);
            if (t0 != 0L) DebugProfiler.endSection(t0, "tryPlaceStemFruit", "placed=true", "stem=" + stemId, "pos=" + pos);
            return true;
        }
        if (t0 != 0L) DebugProfiler.endSection(t0, "tryPlaceStemFruit", "placed=false", "stem=" + stemId, "pos=" + pos);
        return false;
    }

    /**
     * Whether a melon/pumpkin fruit can rest on the given support block.
     * Vanilla StemBlock checks FARMLAND or the DIRT tag explicitly (never
     * isFaceSturdy) because FarmBlock is a 15px-tall half-cube whose top is
     * NOT face-sturdy — a plain isFaceSturdy check would make stems on
     * farmland never fruit. The three-way OR preserves both behaviors.
     *
     * @param belowFruit the block directly below the candidate fruit position
     * @param belowPos   the position directly below the candidate fruit
     * @param level      the level (only consulted by the isFaceSturdy branch)
     * @return true when the fruit has sturdy support
     */
    static boolean isFruitSupport(BlockState belowFruit, BlockPos belowPos, Level level) {
        return belowFruit.getBlock() instanceof FarmBlock
                || belowFruit.is(BlockTags.DIRT)
                || belowFruit.isFaceSturdy(level, belowPos, Direction.UP);
    }

    /**
     * Whether a stem that is about to mutate to short grass must first place its
     * fruit. Only an already-fruited stem that is still a plain {@link StemBlock}
     * (not yet an {@link AttachedStemBlock}) needs the fruit re-placed before the
     * mutation wipes the stem — the fruit is an independent block and is kept.
     *
     * @param fruited whether the stem has already produced its fruit
     * @param block   the stem's current block
     * @return {@code true} when the fruit must be placed before mutating
     */
    static boolean shouldPlaceStemFruitBeforeMutate(boolean fruited, Block block) {
        return fruited && block instanceof StemBlock;
    }

    /**
     * Process a single melon/pumpkin stem (a {@link StemBlock} or an
     * {@link AttachedStemBlock}) for one growth pass, simulating its full
     * lifecycle from {@code plantedDay} to {@code currentDay} via
     * {@link CropSimulation#simulateStem}.
     *
     * <p>All internal {@code setBlock} calls are guarded by
     * {@link InternalGrowthFlag} so {@code LevelMixin} does not untrack the stem
     * when it becomes an {@link AttachedStemBlock}.</p>
     *
     * @return {@code true} when the stem mutated to short grass and its tracking
     *         entry should be removed
     */
    public static boolean processStem(Level level, BlockPos pos, BlockState state,
                                      CropProgressEntry progress, int currentDay,
                                      Season currentSeason, int seasonLength,
                                      boolean duringChunkLoad) {
        Block anchor = CropClassifier.stemAnchor(state.getBlock());
        ResourceLocation anchorId = BuiltInRegistries.BLOCK.getKey(anchor);
        int daysPerStage = CropGrowthConfig.getDaysPerStage(anchorId);
        int daysPerFruit = CropGrowthConfig.DAYS_PER_FRUIT.get();
        Set<Season> suitableSeasons = CropCalendar.resolveSuitableSeasons(currentSeason, anchor);

        CropSimulation.StemSimulation sim = CropSimulation.simulateStem(pos.asLong(), progress.plantedDay, currentDay,
                daysPerStage, daysPerFruit, StemBlock.MAX_AGE, seasonLength, suitableSeasons,
                CropGrowthConfig.STEM_UNSUITABLE_MUTATE_CHANCE.get(),
                CropGrowthConfig.STEM_UNSUITABLE_FRUIT_CHANCE.get());

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
                // 已结瓜的成熟茎先补放果实,再变草(果实为独立方块,保留);
                // 放果失败(果实位被占)仍继续变异 —— 决策:不保留重试,茎正常变异。
                if (!duringChunkLoad && shouldPlaceStemFruitBeforeMutate(sim.fruited(), state.getBlock())) {
                    tryPlaceStemFruit(level, pos, state);
                }
                BlockWriter.internalSetBlock(level, pos, Blocks.SHORT_GRASS.defaultBlockState(), BlockWriter.FLAG_UPDATE_CLIENTS);
            } finally {
                if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
            }
            CropGrowthTracker.logDebug(DebugGate.DebugModule.STEM, "Stem at {} mutated to short grass (plantedDay={}, currentDay={})",
                    pos, progress.plantedDay, currentDay);
            return true;
        }

        if (state.getBlock() instanceof StemBlock) {
            boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
            try {
                int stemAge = CropClassifier.getCropAge(state);
                if (sim.stage() > stemAge) {
                    int newAge = Math.min(sim.stage(), StemBlock.MAX_AGE);
                    BlockWriter.internalSetBlock(level, pos, CropClassifier.getCropStateForAge(state, newAge), BlockWriter.FLAG_UPDATE_CLIENTS);
                    stemAge = newAge;
                    CropGrowthTracker.logDebug(DebugGate.DebugModule.STEM, "Stem {} at {} advanced to age {} (plantedDay={}, currentDay={})",
                            anchorId, pos, newAge, progress.plantedDay, currentDay);
                }
                if (!duringChunkLoad && sim.fruited() && stemAge >= StemBlock.MAX_AGE) {
                    tryPlaceStemFruit(level, pos, level.getBlockState(pos));
                }
            } finally {
                if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
            }
        }
        return false;
    }

    /**
     * Back-calculate the plantedDay for a harvested stem so {@link CropSimulation#simulateStem}
     * treats it as mature at {@code currentDay}.
     *
     * <p>When seasons are disabled or the stem is suitable year-round, the
     * calendar shortcut {@link PlantedDayMath#stemPlantedDayAfterHarvest} is exact.
     * Otherwise stems only mature on <em>suitable</em> days, so a naive calendar
     * back-calculation could cross a season boundary and under-count the stem's
     * maturity (reporting a young stage, which would delay re-fruiting and
     * trigger a spurious immature-stem mutation roll). This method walks backward
     * through suitable days only, so the stem is always treated as "just matured"
     * now regardless of when the fruit is harvested.</p>
     */
    private static int backCalculateStemPlantedDay(Level level, Block anchor, int currentDay,
                                                   int age, int maxAge, int daysPerStage) {
        int effectiveAge = Math.max(0, Math.min(age, maxAge));
        int neededSuitable = effectiveAge * daysPerStage;
        if (daysPerStage <= 0 || neededSuitable <= 0) return currentDay;

        Season currentSeason = CropGrowthTracker.getSeason(level);
        Set<Season> suitableSeasons = CropCalendar.resolveSuitableSeasons(currentSeason, anchor);
        if (currentSeason == Season.NONE || suitableSeasons.contains(Season.NONE)
                || suitableSeasons.size() >= 4) {
            return PlantedDayMath.stemPlantedDayAfterHarvest(currentDay, effectiveAge, maxAge, daysPerStage);
        }

        int termLength = Math.max(1, CropGrowthTracker.getTermLength(level));
        int plantedDay = currentDay;
        int suitableCount = 0;
        // One full year of slack: never more than 24 terms to walk back.
        int maxSteps = neededSuitable + termLength * 24;
        for (int steps = 0; steps < maxSteps && suitableCount < neededSuitable; steps++) {
            plantedDay--;
            if (CropCalendar.isSeasonSuitable(CropCalendar.seasonOfDay(plantedDay, termLength), suitableSeasons)) {
                suitableCount++;
            }
        }
        return plantedDay;
    }

    /**
     * Reset the {@code plantedDay} of a melon/pumpkin stem after its fruit was
     * harvested and the {@link AttachedStemBlock} reverted to a {@link StemBlock}.
     *
     * <p>Vanilla reverts the attached stem to a {@link StemBlock} at
     * {@link StemBlock#MAX_AGE}. Resetting plantedDay to the current day would
     * make {@link CropSimulation#simulateStem} treat the already-mature stem as
     * freshly planted. Instead this back-calculates plantedDay from the reverted
     * stem's age so the stem is treated as "just matured" now and fruits again
     * after {@code daysPerFruit} suitable days.</p>
     */
    public static void onStemFruitHarvest(Level level, BlockPos pos, BlockState revertedStemState) {
        int currentDay = CropGrowthTracker.getSolarDays(level);
        int age = CropClassifier.getCropAge(revertedStemState);
        int maxAge = CropClassifier.getCropMaxAge(revertedStemState.getBlock());
        if (age < 0) {
            age = maxAge;
        }
        Block anchor = CropClassifier.stemAnchor(revertedStemState.getBlock());
        int daysPerStage = CropGrowthConfig.getDaysPerStage(BuiltInRegistries.BLOCK.getKey(anchor));
        int plantedDay = backCalculateStemPlantedDay(level, anchor, currentDay, age, maxAge, daysPerStage);

        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        cropData.put(pos, new CropProgressEntry(plantedDay));
        CropGrowthTracker.registerTrackedChunk(chunk);
        CropGrowthTracker.logDebug(DebugGate.DebugModule.STEM, "Stem fruit harvested: reset plantedDay for stem at {} to {} (age={})",
                pos, plantedDay, age);
    }

    /**
     * Account for a melon/pumpkin stem being accelerated by bonemeal (or any
     * direct age increase). Shifts {@code plantedDay} backward by the accelerated
     * stages so the calendar-derived stage stays in lock-step with the world age.
     */
    public static void onStemBonemeal(Level level, BlockPos pos, int oldAge, int newAge) {
        if (newAge <= oldAge) return;
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        CropProgressEntry entry = cropData.get(pos);
        if (entry == null) return;

        Block anchor = CropClassifier.stemAnchor(level.getBlockState(pos).getBlock());
        int daysPerStage = CropGrowthConfig.getDaysPerStage(BuiltInRegistries.BLOCK.getKey(anchor));
        int shift = (newAge - oldAge) * daysPerStage;
        int currentDay = CropGrowthTracker.getSolarDays(level);
        int plantedDay = PlantedDayMath.stemPlantedDayAfterBonemeal(entry.plantedDay, currentDay, oldAge, newAge, daysPerStage);
        cropData.put(pos, new CropProgressEntry(plantedDay));
        CropGrowthTracker.logDebug(DebugGate.DebugModule.STEM, "Stem bonemeal: {} at {} shifted plantedDay back {} days ({} -> {})",
                BuiltInRegistries.BLOCK.getKey(anchor), pos, shift, entry.plantedDay, plantedDay);
    }
}
