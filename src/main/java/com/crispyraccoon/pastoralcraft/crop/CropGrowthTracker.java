package com.crispyraccoon.pastoralcraft.crop;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.crispyraccoon.pastoralcraft.PastoralCraft;
import com.teamtea.eclipticseasons.api.EclipticSeasonsApi;
import com.teamtea.eclipticseasons.api.constant.solar.Season;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.AttachedStemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.material.Fluids;

/**
 * Tracks crop growth progress using per-chunk data storage via Mixin-injected fields.
 * Each crop position in the world gets a {@link CropProgressEntry} that stores
 * the solar day when the crop was planted.
 *
 * <p><b>Architecture: plantedDay scheme</b></p>
 * The only persistent state is {@link CropProgressEntry#plantedDay}. All other state
 * (growth stage, season transitions) is derived from pure functions of
 * {@code plantedDay + currentDay + config}. This eliminates state consistency issues
 * and automatically adapts to config changes.
 *
 * <p><b>Pure functions:</b></p>
 * <ul>
 *   <li>{@link #simulateGrowth} — simulates growth and mutation from plantedDay</li>
 *   <li>{@link #seasonOfDay} — determines the season for a given solar day</li>
 * </ul>
 *
 * <p>Inspired by the Unloaded-Activity mod's simulated-tick approach, but adapted
 * for PastoralCraft's solar-day-based deterministic growth model.</p>
 */
public class CropGrowthTracker {

    /**
     * Tracks all LevelChunks that currently have crop data entries.
     * Used by the periodic catch-up check to efficiently iterate only chunks with crops,
     * rather than scanning all loaded chunks.
     *
     * <p>Entries are added when crop data is first created for a chunk and removed
     * when the chunk's crop data becomes empty. Since we hold references to LevelChunk
     * objects that are already kept alive by the level's chunk manager while loaded,
     * this does not prevent garbage collection of unloaded chunks.</p>
     */
    private static final Set<LevelChunk> trackedChunks = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Register a chunk as having crop data to track.
     */
    public static void registerTrackedChunk(LevelChunk chunk) {
        trackedChunks.add(chunk);
    }

    /**
     * Unregister a chunk from crop tracking (e.g. on chunk unload).
     */
    public static void unregisterTrackedChunk(LevelChunk chunk) {
        trackedChunks.remove(chunk);
    }

    /**
     * Get an unmodifiable view of all tracked chunks.
     */
    public static Set<LevelChunk> getTrackedChunks() {
        return Collections.unmodifiableSet(trackedChunks);
    }

    /**
     * Levels with a melon/pumpkin stem whose fruit settlement was deferred during a
     * {@code ChunkEvent.Load} catch-up (the cross-chunk fruit write is unsafe while the
     * chunk is still loading). The periodic catch-up consults this set to force one
     * same-day re-scan instead of skipping the whole level via the
     * {@code lastProcessedSolarDay} early-out.
     *
     * <p>Weak keys (like {@link #trackedChunks}) so an unloaded server level can be
     * GC'd; single-threaded (main thread) access only.</p>
     */
    private static final Set<Level> stemSettlementPending =
            Collections.newSetFromMap(new WeakHashMap<>());

    /** Mark a level as having a deferred stem-fruit settlement to re-scan. */
    public static void markStemSettlementPending(Level level) {
        stemSettlementPending.add(level);
    }

    /** Whether the level has a deferred stem-fruit settlement pending a re-scan. */
    public static boolean hasStemSettlementPending(Level level) {
        return stemSettlementPending.contains(level);
    }

    /** Consume the deferred stem-fruit settlement flag for the level. */
    public static void clearStemSettlementPending(Level level) {
        stemSettlementPending.remove(level);
    }

    /**
     * Rebase all tracked crop {@code plantedDay}s for a level after Ecliptic
     * Seasons rewinds the solar calendar (its {@code set} command). Shifts every
     * plantedDay back by {@code delta} so {@code currentDay - plantedDay} stays
     * unchanged — the crop keeps its current stage and resumes growing without
     * the frozen window caused by {@code simulateGrowth}'s
     * {@code currentDay <= plantedDay} early-out.
     *
     * @param level the level whose calendar was rewound
     * @param delta the number of solar days the calendar moved backwards (positive)
     */
    public static void rebasePlantedDays(Level level, int delta) {
        if (delta <= 0) return;
        for (LevelChunk chunk : trackedChunks) {
            if (chunk.getLevel() != level) continue;
            ChunkCropData chunkData = (ChunkCropData) chunk;
            Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
            if (cropData.isEmpty()) continue;
            for (Map.Entry<BlockPos, CropProgressEntry> entry : new ArrayList<>(cropData.entrySet())) {
                entry.setValue(new CropProgressEntry(entry.getValue().plantedDay - delta));
            }
        }
    }

    /** Invalidate the non-arable freeze cache (delegated to {@link CropClassifier#clearFreezeCache}). */
    public static void clearFreezeCache() {
        CropClassifier.clearFreezeCache();
    }

    public static boolean isNonArableBlock(Block block) {
        return CropClassifier.isNonArableBlock(block);
    }

    public static boolean isNonArableAt(Level level, BlockPos pos, Block block) {
        return CropClassifier.isNonArableAt(level, pos, block);
    }

    public static boolean isSegmentedRice(Block block) {
        return CropClassifier.isSegmentedRice(block);
    }

    public static int getRiceSegment(BlockState state) {
        return CropClassifier.getRiceSegment(state);
    }

    /**
     * Synchronize the MIDDLE/UP segments of a segmented rice plant to {@code age}.
     * Only the DOWN (root) segment is tracked by the calendar; the upper segments
     * simply mirror its age. The mod advances the DOWN segment with
     * {@code UPDATE_CLIENTS} (no neighbor propagation), so {@code updateShape}
     * never fires and the upper segments would go stale — this method updates
     * them explicitly.
     *
     * <p>This may be called from within the INTERNAL_GROWTH-guarded catch-up
     * loops, so the flag is saved/restored (never unconditionally cleared) to
     * avoid breaking the enclosing guard.</p>
     *
     * @param level   the level
     * @param downPos the DOWN segment position
     * @param age     the age to apply to the MIDDLE/UP segments
     */
    public static void syncRiceSegments(Level level, BlockPos downPos, int age) {
        IntegerProperty location = CropClassifier.segmentedLocationProperty(level.getBlockState(downPos).getBlock());
        if (location != CropClassifier.NO_SEGMENT_PROPERTY) {
            syncRiceSegments(level, downPos, age, location);
        }
    }

    static void syncRiceSegments(Level level, BlockPos downPos, int age, IntegerProperty location) {
        for (int segment = 1; segment <= 2; segment++) {
            BlockPos segmentPos = downPos.above(segment);
            BlockState segmentState = level.getBlockState(segmentPos);
            if (!segmentState.hasProperty(location) || segmentState.getValue(location) != segment) continue;
            if (!segmentState.hasProperty(CropBlock.AGE)) continue;
            BlockState synced = segmentState.setValue(CropBlock.AGE, age);
            if (synced.equals(segmentState)) continue;
            boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
            try {
                BlockWriter.internalSetBlock(level, segmentPos, synced, BlockWriter.FLAG_UPDATE_CLIENTS);
            } finally {
                if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
            }
        }
    }

    /**
     * Simulate the crop's growth from {@code plantedDay} to {@code currentDay}.
     *
     * <p>This is the central pure function: given when the crop was planted and
     * the current time, it determines the final growth stage and whether the
     * crop mutated into short grass.</p>
     *
     * <p>In suitable seasons the crop grows deterministically: one stage per
     * {@code daysPerStage} days. In unsuitable seasons:</p>
     * <ul>
     *   <li>Non-arable crops freeze (no growth, no mutation).</li>
     *   <li>Arable crops keep attempting growth; each growth attempt rolls a
     *       deterministic three-way outcome:
     *       20% mutate into short grass, 40% grow one stage, 40% no growth.</li>
     * </ul>
     *
     * <p><b>Time rollback protection:</b> If {@code currentDay <= plantedDay},
     * returns stage 0 with no mutation.</p>
     *
     * @param pos             the crop position (seeds deterministic mutation rolls)
     * @param plantedDay      the solar day when the crop was planted
     * @param currentDay      the current solar day
     * @param daysPerStage    how many solar days per growth stage
     * @param maxAge          the crop's maximum age (max stage)
     * @param seasonLength    how many solar days per season
     * @param suitableSeasons seasons in which the crop can grow
     * @param nonArable       whether the crop freezes in unsuitable seasons
     * @return the growth simulation result
     */
    /**
     * Hard fail-fast cap on the simulated elapsed window (delegated to
     * {@link CropSimulation#HARD_MAX_ELAPSED_DAYS}).
     */
    static final int HARD_MAX_ELAPSED_DAYS = CropSimulation.HARD_MAX_ELAPSED_DAYS;

    /**
     * Clamp the simulation's {@code currentDay} (delegated to {@link CropSimulation#clampSimDay}).
     */
    static int clampSimDay(int plantedDay, int currentDay, int maxElapsedDays) {
        return CropSimulation.clampSimDay(plantedDay, currentDay, maxElapsedDays);
    }

    public static CropSimulation.GrowthSimulation simulateGrowth(BlockPos pos, int plantedDay, int currentDay,
                                                   int daysPerStage, int maxAge,
                                                   int seasonLength, Set<Season> suitableSeasons,
                                                   boolean nonArable) {
        return CropSimulation.simulateGrowth(pos, plantedDay, currentDay, daysPerStage, maxAge,
                seasonLength, suitableSeasons, nonArable);
    }

    public static CropSimulation.GrowthSimulation simulateGrowth(long posKey, int plantedDay, int currentDay,
                                                   int daysPerStage, int maxAge,
                                                   int seasonLength, Set<Season> suitableSeasons,
                                                   boolean nonArable,
                                                   double mutateChance, double growChance) {
        return CropSimulation.simulateGrowth(posKey, plantedDay, currentDay, daysPerStage, maxAge,
                seasonLength, suitableSeasons, nonArable, mutateChance, growChance);
    }

    /**
     * Simulate a stem's (melon/pumpkin) lifecycle from {@code plantedDay} to
     * {@code currentDay}, reading the unsuitable-season roll chances from config.
     *
     * <p>See the long-key overload for the full semantics. This overload is the
     * production entry point used by {@link #processStem}.</p>
     */
    public static CropSimulation.StemSimulation simulateStem(BlockPos pos, int plantedDay, int currentDay,
                                              int daysPerStage, int daysPerFruit, int maxAge,
                                              int seasonLength, Set<Season> suitableSeasons) {
        return CropSimulation.simulateStem(pos, plantedDay, currentDay, daysPerStage, daysPerFruit, maxAge,
                seasonLength, suitableSeasons);
    }

    public static CropSimulation.StemSimulation simulateStem(long posKey, int plantedDay, int currentDay,
                                              int daysPerStage, int daysPerFruit, int maxAge,
                                              int seasonLength, Set<Season> suitableSeasons,
                                              double mutateChance, double fruitChance) {
        return CropSimulation.simulateStem(posKey, plantedDay, currentDay, daysPerStage, daysPerFruit, maxAge,
                seasonLength, suitableSeasons, mutateChance, fruitChance);
    }

    public static Season seasonOfDay(int solarDay, int termLength) {
        return CropCalendar.seasonOfDay(solarDay, termLength);
    }

    public static int countSuitableDays(int startDay, int endDay, Set<Season> suitableSeasons, int termLength) {
        return CropCalendar.countSuitableDays(startDay, endDay, suitableSeasons, termLength);
    }

    public static boolean isSeasonSuitable(Season season, Set<Season> suitableSeasons) {
        return CropCalendar.isSeasonSuitable(season, suitableSeasons);
    }

    public static Set<Season> resolveSuitableSeasons(Season currentSeason, Block block) {
        return CropCalendar.resolveSuitableSeasons(currentSeason, block);
    }

    /**
     * Resolve the allowed horizontal fruiting directions for melon/pumpkin
     * stems from config, preserving the configured order (which also defines
     * the deterministic check order). Falls back to EAST then NORTH when the
     * configured list is empty or unparsable.
     */
    public static boolean tryPlaceStemFruit(Level level, BlockPos pos, BlockState state) {
        return StemStrategy.tryPlaceStemFruit(level, pos, state);
    }

    static boolean isFruitSupport(BlockState belowFruit, BlockPos belowPos, Level level) {
        return StemStrategy.isFruitSupport(belowFruit, belowPos, level);
    }

    static boolean shouldPlaceStemFruitBeforeMutate(boolean fruited, Block block) {
        return StemStrategy.shouldPlaceStemFruitBeforeMutate(fruited, block);
    }

    static int backCalculatedPlantedDay(int currentDay, int currentAge, int daysPerStage) {
        return PlantedDayMath.backCalculatedPlantedDay(currentDay, currentAge, daysPerStage);
    }

    static int backCalculatePlantedDaySuitable(int currentDay, int newAge, int daysPerStage,
                                               Set<Season> suitableSeasons, int seasonLength) {
        return PlantedDayMath.backCalculatePlantedDaySuitable(currentDay, newAge, daysPerStage,
                suitableSeasons, seasonLength);
    }

    static int heightCropPlantedDayAfterBonemeal(int currentDay, int newHeight, int maxHeight,
                                                 int daysPerStage, Set<Season> suitableSeasons,
                                                 int seasonLength) {
        return PlantedDayMath.heightCropPlantedDayAfterBonemeal(currentDay, newHeight, maxHeight,
                daysPerStage, suitableSeasons, seasonLength);
    }

    static int clampPlantedDay(int plantedDay, int currentDay) {
        return PlantedDayMath.clampPlantedDay(plantedDay, currentDay);
    }

    /**
     * Whether the upper half of a two-block (DOUBLE) crop needs to be placed or
     * refreshed. Idempotency guard for {@link #placeDoubleUpperHalf}: when the
     * slot above already holds the target state, no setBlock or log line is
     * needed (prevents double placement/logging at maxAge).
     *
     * @param aboveState the current state above the lower half
     * @param upperState the target upper-half state
     * @return true when the upper half must be (re)placed
     */
    static boolean needsUpperHalfPlacement(BlockState aboveState, BlockState upperState) {
        return CropClassifier.needsUpperHalfPlacement(aboveState, upperState);
    }

    static boolean isUpperHalfOf(BlockState aboveState, Block cropBlock) {
        return CropClassifier.isUpperHalfOf(aboveState, cropBlock);
    }

    static boolean isDoubleCropUpperHalf(BlockState state, StructureDescriptor descriptor) {
        return CropClassifier.isDoubleCropUpperHalf(state, descriptor);
    }

    /**
     * Process a single melon/pumpkin stem (a {@link StemBlock} or an
     * {@link AttachedStemBlock}) for one growth pass, simulating its full
     * lifecycle from {@code plantedDay} to {@code currentDay} via
     * {@link #simulateStem}.
     *
     * <p>For a {@link StemBlock}: suitable-season days advance growth (one stage
     * per {@code daysPerStage}) and then fruiting (one fruit per
     * {@code daysPerFruit}). Unsuitable-season days roll for mutation every
     * {@code daysPerFruit} days. For an {@link AttachedStemBlock} only the
     * mutation roll applies — the fruit is already present and no further growth
     * or fruiting occurs.</p>
     *
     * <p>All internal {@code setBlock} calls are guarded by
     * {@link InternalGrowthFlag} so {@code LevelMixin} does not untrack the stem
     * when it becomes an {@link AttachedStemBlock} — keeping the entry alive so
     * the attached stem can still mutate in unsuitable seasons.</p>
     *
     * @param level           the level
     * @param pos             the stem position
     * @param state           the stem's current block state
     * @param progress        the tracked progress entry (plantedDay)
     * @param currentDay      the current solar day
     * @param currentSeason   the current season
     * @param seasonLength    solar days per full season
     * @param duringChunkLoad true when running inside {@code ChunkEvent.Load}
     *                        catch-up — fruit placement (a horizontal cross-chunk
     *                        read/write) is skipped and deferred to the periodic
     *                        catch-up, because {@code getBlockState} on a neighbour
     *                        chunk that is still loading deadlocks the server thread.
     * @return {@code true} when the stem mutated to short grass and its tracking
     *         entry should be removed
     */
    public static boolean processStem(Level level, BlockPos pos, BlockState state,
                                      CropProgressEntry progress, int currentDay,
                                      Season currentSeason, int seasonLength,
                                      boolean duringChunkLoad) {
        return StemStrategy.processStem(level, pos, state, progress, currentDay, currentSeason, seasonLength, duringChunkLoad);
    }

    // =======================================================================
    // Sugar Cane Growth — deterministic height-based growth
    // =======================================================================

    /**
     * Count the height of a sugar cane stalk at the given position.
     * Counts upward from the given position (which should be the bottom block)
     * and includes all consecutive sugar cane blocks.
     *
     * @param level the world level
     * @param pos   the bottom sugar cane block position
     * @return the stalk height (1-3), or 0 if the block is not sugar cane
     */
    public static void onSugarCaneHarvest(Level level, BlockPos harvestedPos) {
        HeightStrategy.onSugarCaneHarvest(level, harvestedPos);
    }

    public static void onSugarCaneBonemeal(Level level, BlockPos headPos) {
        HeightStrategy.onSugarCaneBonemeal(level, headPos);
    }

    // =======================================================================
    // Kelp Growth — deterministic height-based growth in water
    // =======================================================================

    /**
     * Count the height of a kelp stalk at the given position.
     * Counts upward from the given position (which should be the root block)
     * and includes all consecutive kelp blocks.
     *
     * @param level the world level
     * @param pos   the root kelp block position
     * @return the stalk height (1-26), or 0 if the block is not kelp
     */
    public static void onKelpHarvest(Level level, BlockPos harvestedPos) {
        HeightStrategy.onKelpHarvest(level, harvestedPos);
    }

    public static void onKelpBonemeal(Level level, BlockPos headPos) {
        HeightStrategy.onKelpBonemeal(level, headPos);
    }

    // =======================================================================
    // Entry Management
    // =======================================================================

    /**
     * Get or create a progress entry for the crop at the given position.
     * Returns null if the block is not a recognized crop.
     *
     * <p>New entries are created with {@code plantedDay = current solar day},
     * representing the day the crop was first detected. For crops with existing
     * growth stages (e.g. world-gen farms), the plantedDay is back-calculated.</p>
     *
     * <p>For {@link SugarCaneBlock}, this method verifies the block is the
     * <b>bottom</b> block of the stalk (the block below is not sugar cane).
     * Only the bottom block is tracked — upper blocks are ignored.</p>
     *
     * <p><b>Time rollback protection:</b> Back-calculated plantedDay is clamped
     * to not exceed currentDay, preventing deadlock when time is set backward.</p>
     */
    public static CropProgressEntry getOrCreate(BlockPos pos, Level level, BlockState state) {
        return EntryStore.getOrCreate(pos, level, state);
    }

    public static CropProgressEntry getOrCreate(BlockPos pos, Level level, BlockState state, int currentDay) {
        return EntryStore.getOrCreate(pos, level, state, currentDay);
    }

    /**
     * Whether the position currently has a tracked crop entry.
     * Used to avoid back-calculating plantedDay for an already-tracked crop:
     * bonemeal/rapid growth must not shift plantedDay backward.
     *
     * @param level the level
     * @param pos   the block position
     * @return true if a tracking entry exists at {@code pos}
     */
    public static boolean isTracked(Level level, BlockPos pos) {
        return EntryStore.isTracked(level, pos);
    }

    /**
     * Remove a crop position from tracking (e.g. when broken or harvested).
     * If the chunk's crop data map becomes empty after removal, the chunk is
     * unregistered from the tracked set to prevent wasted iteration during
     * periodic catch-up checks.
     */
    public static void removePosition(BlockPos pos, Level level) {
        EntryStore.removePosition(pos, level);
    }

    /**
     * Reset the {@code plantedDay} of an existing tracked crop to the current
     * solar day. Used when a StemBlock reverts from AttachedStemBlock after
     * fruit harvest — the stem should start a fresh fruiting cycle from the
     * current day rather than continuing from the original plantedDay.
     *
     * <p>If no entry exists at the given position (e.g. a world-generated
     * sunflower harvested before it was ever tracked), a new entry is created
     * so the calendar can still drive its regrowth cycle.</p>
     *
     * @param pos   the crop position
     * @param level the world level
     */
    public static void resetPlantedDay(BlockPos pos, Level level) {
        EntryStore.resetPlantedDay(pos, level);
    }

    /**
     * Back-calculate the plantedDay for a melon/pumpkin stem whose fruit was
     * just harvested (the {@link AttachedStemBlock} reverted to a mature
     * {@link StemBlock}). Pure function so it can be unit-tested.
     *
     * @param currentDay   the current solar day
     * @param age          the reverted stem's current age (MAX_AGE)
     * @param maxAge       the stem's maximum age
     * @param daysPerStage solar days per growth stage
     * @return a plantedDay that makes the stem "just matured" now
     */
    static int stemPlantedDayAfterHarvest(int currentDay, int age, int maxAge, int daysPerStage) {
        return PlantedDayMath.stemPlantedDayAfterHarvest(currentDay, age, maxAge, daysPerStage);
    }

    /**
     * Back-calculate the plantedDay for a harvested stem so {@link #simulateStem}
     * treats it as mature at {@code currentDay}.
     *
     * <p>When seasons are disabled or the stem is suitable year-round, the
     * calendar shortcut {@link #stemPlantedDayAfterHarvest} is exact. Otherwise
     * stems only mature on <em>suitable</em> days, so a naive calendar
     * back-calculation could cross a season boundary and under-count the stem's
     * maturity (reporting a young stage, which would delay re-fruiting and
     * trigger a spurious immature-stem mutation roll). This method walks backward
     * through suitable days only, so the stem is always treated as "just matured"
     * now regardless of when the fruit is harvested.</p>
     */
    public static void onStemFruitHarvest(Level level, BlockPos pos, BlockState revertedStemState) {
        StemStrategy.onStemFruitHarvest(level, pos, revertedStemState);
    }

    /**
     * Account for a melon/pumpkin stem being accelerated by bonemeal (or any
     * direct age increase).
     *
     * <p>The calendar derives a stem's stage purely from {@code plantedDay}, so a
     * bonemeal jump that moves the block's AGE ahead without touching the entry
     * makes the simulated stage lag behind the world age — the stem then stalls
     * one extra {@code daysPerStage} per accelerated stage before the calendar
     * "catches up". Shifting {@code plantedDay} backward by the accelerated
     * stages ({@code (newAge - oldAge) * daysPerStage}) keeps the simulation in
     * lock-step with the world age, exactly as if the stem had been planted that
     * much earlier.</p>
     *
     * <p>Only the calendar-day shortcut is applied here (no suitable-day walk):
     * bonemeal shifts are small, and walking through suitable days across a
     * season boundary could overshoot by an entire unsuitable season and cause a
     * spurious mutation. A boundary crossing therefore adds at most the shifted
     * few days to the unsuitable-day counter, matching the cost of genuinely
     * planting that much earlier.</p>
     *
     * @param level  the level
     * @param pos    the stem position
     * @param oldAge the stem's age before acceleration
     * @param newAge the stem's age after acceleration
     */
    public static void onStemBonemeal(Level level, BlockPos pos, int oldAge, int newAge) {
        StemStrategy.onStemBonemeal(level, pos, oldAge, newAge);
    }

    /**
     * Pure back-calculation for a bonemeal-accelerated stem: shift
     * {@code plantedDay} backward by the accelerated stages
     * ({@code (newAge - oldAge) * daysPerStage}) so the calendar-derived stage
     * stays in lock-step with the world age. Clamped so a negative
     * {@code daysPerStage} never pushes {@code plantedDay} into the future.
     *
     * @param plantedDay  the stem's current plantedDay
     * @param currentDay  the current solar day
     * @param oldAge      the stem's age before acceleration
     * @param newAge      the stem's age after acceleration
     * @param daysPerStage solar days per growth stage
     * @return the adjusted plantedDay
     */
    static int stemPlantedDayAfterBonemeal(int plantedDay, int currentDay, int oldAge, int newAge, int daysPerStage) {
        return PlantedDayMath.stemPlantedDayAfterBonemeal(plantedDay, currentDay, oldAge, newAge, daysPerStage);
    }

    /**
     * Account for a non-stem crop being accelerated by bonemeal (or any direct
     * age increase that leaves the block tracked).
     *
     * <p>Round4 §8b kept {@code plantedDay} fixed on bonemeal so unsuitable days
     * were never back-calculated (avoiding spurious mutation), but that made the
     * calendar stage lag behind the accelerated world age — the crop then stalls
     * one {@code daysPerStage} per accelerated stage before catch-up advances it
     * again. This method back-shifts {@code plantedDay} conservatively via
     * {@link #backCalculatePlantedDaySuitable}: the shift never crosses into an
     * unsuitable season, so {@link #simulateGrowth} sees only suitable end-days,
     * never rolls for mutation, and aligns the calendar stage with the world age.</p>
     *
     * <p>Only a backward (earlier) shift is ever applied; a result that would move
     * {@code plantedDay} forward is discarded, so the calendar stage can never
     * regress and reintroduce a stall.</p>
     *
     * @param level    the level
     * @param pos      the crop position
     * @param oldAge   the crop's age before acceleration
     * @param newAge   the crop's age after acceleration
     * @param newState the crop's new block state (source of the block/season info)
     */
    public static void onCropBonemeal(Level level, BlockPos pos, int oldAge, int newAge, BlockState newState) {
        if (newAge <= oldAge) return;
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        CropProgressEntry entry = cropData.get(pos);
        if (entry == null) return;

        Block block = newState.getBlock();
        ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
        int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
        int currentDay = getSolarDays(level);
        Set<Season> suitableSeasons = resolveSuitableSeasons(getSeason(level), block);
        int seasonLength = getSeasonLength(level);
        int plantedDay = backCalculatePlantedDaySuitable(currentDay, newAge, daysPerStage,
                suitableSeasons, seasonLength);
        // Conservative: only allow shifting backward (earlier), never forward —
        // moving plantedDay forward would drop the calendar stage and reintroduce
        // the stall/regression this fix removes.
        if (plantedDay >= entry.plantedDay) return;
        cropData.put(pos, new CropProgressEntry(plantedDay));
        logDebug("Crop bonemeal: {} at {} shifted plantedDay back {} days ({} -> {})",
                cropId, pos, entry.plantedDay - plantedDay,
                entry.plantedDay, plantedDay);
    }

    // =======================================================================
    // Catch-up Growth Processing
    // =======================================================================

    /**
     * Process catch-up growth for all crops in a chunk that was just loaded.
     * Computes the target growth stage from each crop's plantedDay and advances
     * the block state if needed.
     *
     * <p>With the plantedDay scheme, this is simply: for each entry, simulate
     * growth via {@link #simulateGrowth}. No unloaded-tracking state is needed —
     * the pure function handles all elapsed time naturally.</p>
     *
     * @param chunk the chunk being loaded
     * @param level the world level
     */
    public static void onChunkLoad(LevelChunk chunk, Level level) {
        // Set re-entrancy guard to prevent LevelMixin from double-recording
        // internal setBlock calls during catch-up processing. Save/restore the
        // previous value: a nested entry point (e.g. a maturity side-effect that
        // re-enters chunk processing) must never clear an outer guard.
        boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
        try {
            int currentDay = getSolarDays(level);
            Season currentSeason = getSeason(level);
            int seasonLength = getSeasonLength(level);
            catchUpInternal(chunk, level, currentDay, currentSeason, seasonLength, true);
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
    }

    /**
     * Shared catch-up pass for one chunk (chunk-load and periodic paths converge
     * here). Parameterized by {@code duringChunkLoad}: when true, cross-chunk stem
     * fruiting is deferred (see {@link #processStem}) to avoid the chunk-load deadlock.
     */
    private static void catchUpInternal(LevelChunk chunk, Level level, int currentDay,
                                        Season currentSeason, int seasonLength, boolean duringChunkLoad) {
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();

        if (cropData.isEmpty()) return;

        long t0 = DebugProfiler.startSection();
        scanCropDataHealth(chunkData, currentDay, chunk.getPos());

        // Rewind guard: entries whose plantedDay is still in the future (the
        // Ecliptic Seasons calendar was set backwards while this chunk was
        // unloaded, so they missed the ServerTick rebase) would freeze at stage 0
        // until currentDay catches up. Rebase them to currentDay so they resume
        // growing from the rewound point instead of stalling.
        for (Map.Entry<BlockPos, CropProgressEntry> entry : new ArrayList<>(cropData.entrySet())) {
            if (entry.getValue().plantedDay > currentDay) {
                entry.setValue(new CropProgressEntry(currentDay));
            }
        }

        int processed = 0;
        int grown = 0;
        int removed = 0;

        // Snapshot the entry set to avoid ConcurrentModificationException.
        // setBlock calls below trigger LevelMixin.onSetBlock, which may call
        // removePosition on the live map — iterating a snapshot keeps us safe.
        List<Map.Entry<BlockPos, CropProgressEntry>> entries =
                new ArrayList<>(cropData.entrySet());

        // P1: per-invocation time budget. Defer any remainder to the next periodic
        // cycle instead of blocking the server thread (worst case: huge elapsedDays
        // across a chunk with hundreds of entries after teleport / world load).
        int budgetMs = CropGrowthConfig.CATCH_UP_TIME_BUDGET_MS.get();
        long budgetDeadline = budgetMs <= 0 ? Long.MAX_VALUE
                : System.nanoTime() + (long) budgetMs * 1_000_000L;

        CatchUpContext ctx = new CatchUpContext(level, cropData, currentDay, currentSeason, seasonLength, duringChunkLoad);

        for (Map.Entry<BlockPos, CropProgressEntry> mapEntry : entries) {
            BlockPos pos = mapEntry.getKey();
            CropProgressEntry progress = mapEntry.getValue();
            processed++;

            // P2: watchdog progress (gate-independent, always on) — shows the last
            // chunk/entry being processed when a freeze fires the shutdown dump.
            if ((processed & 255) == 0) {
                DebugWatchdog.catchUpProgress("chunk-load chunk=" + chunk.getPos()
                        + " i=" + processed + "/" + entries.size());
            }
            // P1: check the budget every 64 entries (nanoTime is cheap but not free).
            if ((processed & 63) == 0 && System.nanoTime() > budgetDeadline) {
                DebugWatchdog.catchUpProgress("budget-exhausted chunk-load chunk=" + chunk.getPos()
                        + " i=" + processed + "/" + entries.size());
                break;
            }

            // Per-crop guard: a single problem crop (corrupt state, mod-mixed-in
            // properties, etc.) must be skipped and logged instead of crashing
            // the server tick loop.
            try {
            // Check if the block is still a crop
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            if (!isGrowableCrop(block)) {
                // Attached stem: keep tracking so it can still mutate to short
                // grass in unsuitable seasons. processStem handles the roll.
                if (block instanceof AttachedStemBlock) {
                    if (processStem(level, pos, state, progress, currentDay, currentSeason, seasonLength, duringChunkLoad)) {
                        cropData.remove(pos);
                        removed++;
                    }
                    continue;
                }
                cropData.remove(pos);
                removed++;
                logDebug("Removed stale crop entry at {} dim={} (block changed while unloaded)",
                        pos, level.dimension().location());
                continue;
            }

            // --- StemBlock: growth below MAX_AGE, then fruiting at MAX_AGE ---
            if (block instanceof StemBlock) {
                if (processStem(level, pos, state, progress, currentDay, currentSeason, seasonLength, duringChunkLoad)) {
                    cropData.remove(pos);
                    removed++;
                }
                continue;
            }

            // --- SugarCane: height-based growth ---
            if (block instanceof SugarCaneBlock) {
                // Defensive: only the bottom (root) block may be tracked. A
                // non-bottom entry (legacy/corrupt data) would grow upward from
                // the wrong position and exceed the 3-block height limit.
                if (level.getBlockState(pos.below()).getBlock() instanceof SugarCaneBlock) {
                    cropData.remove(pos);
                    removed++;
                    continue;
                }

                int currentHeight = HeightStrategy.getSugarCaneHeight(level, pos);
                int maxHeight = 3; // Sugar cane max height
                int sugarMaxAge = maxHeight - 1; // 0-indexed: 2

                // Keep the entry when fully grown: sugar cane is re-harvestable
                // and onSugarCaneHarvest resets plantedDay on harvest.
                if (currentHeight >= maxHeight) {
                    continue;
                }

                ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
                int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
                Set<Season> suitableSeasons = resolveSuitableSeasons(currentSeason, block);

                // Sugar cane is non-arable: it freezes in unsuitable seasons and
                // never mutates, so only suitable-season days count toward growth.
                CropSimulation.GrowthSimulation sim = simulateGrowth(pos, progress.plantedDay, currentDay,
                        daysPerStage, sugarMaxAge, seasonLength, suitableSeasons, true);

                int targetHeight = Math.min(sim.stage() + 1, maxHeight);

                // Grow one block at a time up to target height
                while (currentHeight < targetHeight) {
                    if (HeightStrategy.growSugarCane(level, pos, currentHeight)) {
                        currentHeight++;
                        grown++;
                        logDebug("Catch-up (load): sugar cane at {} grew to height {} (target={})",
                                pos, currentHeight, targetHeight);
                    } else {
                        break; // Can't grow further (blocked above)
                    }
                }

                continue;
            }

            // --- Kelp: height-based growth in water ---
            if (CropClassifier.isKelp(block)) {
                // Defensive: only the root block may be tracked. A non-root
                // entry (legacy/corrupt data) would grow upward from the wrong
                // position and exceed the 26-block height limit.
                if (CropClassifier.isKelp(level.getBlockState(pos.below()).getBlock())) {
                    cropData.remove(pos);
                    removed++;
                    continue;
                }

                int currentHeight = HeightStrategy.getKelpHeight(level, pos);
                int kelpMaxAge = CropClassifier.KELP_MAX_HEIGHT - 1; // 0-indexed: 25

                // Keep the entry when fully grown: kelp is re-harvestable and
                // onKelpHarvest resets plantedDay on harvest.
                if (currentHeight >= CropClassifier.KELP_MAX_HEIGHT) {
                    continue;
                }

                // Anchor config/season to the kelp head regardless of whether the
                // root is currently a head (KelpBlock) or stem (KelpPlantBlock).
                ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(Blocks.KELP);
                int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
                Set<Season> suitableSeasons = resolveSuitableSeasons(currentSeason, Blocks.KELP);

                // Kelp is non-arable: it freezes in unsuitable seasons and never
                // mutates, so only suitable-season days count toward growth.
                CropSimulation.GrowthSimulation sim = simulateGrowth(pos, progress.plantedDay, currentDay,
                        daysPerStage, kelpMaxAge, seasonLength, suitableSeasons, true);

                int targetHeight = Math.min(sim.stage() + 1, CropClassifier.KELP_MAX_HEIGHT);

                // Grow one block at a time up to target height
                while (currentHeight < targetHeight) {
                    if (HeightStrategy.growKelp(level, pos, currentHeight)) {
                        currentHeight++;
                        grown++;
                        logDebug("Catch-up (load): kelp at {} grew to height {} (target={})",
                                pos, currentHeight, targetHeight);
                    } else {
                        break; // Can't grow further (no water above / cap reached)
                    }
                }

                continue;
            }

            // --- REGROW: boolean-product calendar regrowth (e.g. sunflower has_seeds) ---
            if (CropKindResolver.regrowOf(block) != null) {
                CatchUpContext.Outcome outcome = RegrowStrategy.process(ctx, pos, state, progress);
                if (outcome.grew()) grown++;
                if (outcome.remove()) {
                    cropData.remove(pos);
                    removed++;
                }
                continue;
            }

            // --- Normal crops ---
            CatchUpContext.Outcome outcome = AgeStrategy.process(ctx, pos, state, progress);
            if (outcome.grew()) grown++;
            if (outcome.remove()) {
                cropData.remove(pos);
                removed++;
            }
            } catch (Exception e) {
                // Defensive: skip this crop only, never crash the tick loop.
                ResourceLocation cropId;
                try {
                    cropId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
                } catch (Exception ignored) {
                    cropId = null;
                }
                PastoralCraft.LOGGER.warn("Chunk-load catch-up: error processing {} at {}: {}",
                        cropId, pos, e.toString());
            }
        }

        if (processed > 0) {
            logDebug(DebugGate.DebugModule.CATCH_UP, "Chunk load catch-up: processed={} grown={} removed={} in chunk {} dim={}",
                    processed, grown, removed, chunk.getPos(), level.dimension().location());
        }

        // R4: idempotent registration — covers chunks whose crop data arrived
        // without a prior registerTrackedChunk (e.g. NBT-less load paths), and
        // replaces the onChunkDataLoad registration removed for R2.
        if (cropData.isEmpty()) {
            unregisterTrackedChunk(chunk);
        } else {
            registerTrackedChunk(chunk);
        }

        if (t0 != 0L) {
            DebugProfiler.endSection(t0, "onChunkLoad", "chunk=" + chunk.getPos(), "entries=" + processed);
        }
    }

    /**
     * Periodic catch-up check for all crops in a chunk.
     * Unlike random-tick-based growth (which fires infrequently and one stage at a time),
     * this calculates the full elapsed time and applies multi-stage growth in one shot.
     *
     * <p>Called periodically by the server tick handler to ensure crops catch up
     * promptly when time passes while chunks are loaded (e.g. player is nearby
     * but not constantly watching crops). This makes growth appear as unified
     * stage jumps rather than slow random single-stage advances.</p>
     *
     * <p>With the plantedDay scheme, this is idempotent: computing the target
     * stage from plantedDay gives the same result every time, and if the crop
     * is already at the target stage, nothing happens.</p>
     *
     * <p><b>Performance optimizations:</b></p>
     * <ul>
     *   <li>Day and season are pre-computed by the caller (once per level per cycle)</li>
     *   <li>Suitable seasons are resolved via {@link SeasonTagResolver}, whose
     *       per-block cache avoids repeated tag lookups</li>
     *   <li>Uses {@code setBlock} flag 2 (no neighbor updates) — crops don't need
     *       neighbor notifications for growth stage changes</li>
     * </ul>
     *
     * @param chunk         the chunk to check
     * @param level         the world level
     * @param currentDay    pre-computed solar day for this level
     * @param currentSeason pre-computed season for this level
     */
    public static void periodicCatchUpCheck(LevelChunk chunk, Level level,
                                             int currentDay, Season currentSeason,
                                             int seasonLength) {
        // Set re-entrancy guard to prevent LevelMixin from double-recording
        // internal setBlock calls during catch-up processing. Save/restore the
        // previous value: a nested entry point (e.g. a maturity side-effect that
        // re-enters chunk processing) must never clear an outer guard.
        boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
        try {
            catchUpInternal(chunk, level, currentDay, currentSeason, seasonLength, false);
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
    }

    // =======================================================================
    // Crop Type Detection — Unified Growable Crop Support
    // =======================================================================

    /**
     * Check if a block is a recognized age-based growable crop.
     * Covers {@link CropBlock}, {@link StemBlock}, {@link NetherWartBlock},
     * {@link CocoaBlock}, and {@link SweetBerryBushBlock} — all vanilla blocks
     * that use an age-based growth system with an {@code AGE} property.
     *
     * <p><b>AttachedStemBlock exclusion:</b> When a pumpkin or melon grows,
     * the vanilla {@code StemBlock} is replaced by an {@link AttachedStemBlock},
     * which has no {@code AGE} property (only {@code FACING}). {@code AttachedStemBlock}
     * is intentionally excluded here — it is treated as a non-crop, triggering
     * {@link #removePosition} in {@code LevelMixin#onSetBlock} to clean up tracking.</p>
     *
     * <p>Excludes {@code CaveVinesBlock} and similar blocks that use different
     * growth mechanics (e.g. bonemeal-only, age tied to random ticks without
     * a deterministic stage progression).</p>
     *
     * @param block the block to check
     * @return true if the block is a recognized growable crop
     */
    public static boolean isGrowableCrop(Block block) {
        return CropClassifier.isGrowableCrop(block);
    }

    public static boolean isRegrow(Block block) {
        return CropClassifier.isRegrow(block);
    }

    public static int getCropAge(BlockState state) {
        return CropClassifier.getCropAge(state);
    }

    public static int getCropMaxAge(Block block) {
        return CropClassifier.getCropMaxAge(block);
    }

    public static BlockState getCropStateForAge(BlockState state, int age) {
        return CropClassifier.getCropStateForAge(state, age);
    }

    public static BlockState getCropStateForAgePreserving(BlockState state, int age) {
        return CropClassifier.getCropStateForAgePreserving(state, age);
    }

    /**
     * Apply data-driven maturity side effects when an age-based crop reaches maturity.
     *
     * <p>Priority order, from {@link CropGrowthConfig.CropOverride}:</p>
     * <ol>
     *   <li><b>TRANSFORM</b> — replace the crop block itself with {@code transformBlock}.</li>
     *   <li><b>COMPANION</b> — place {@code topBlock} above the crop; when
     *       {@code waterCompanion} the position above must be water, otherwise it must be air.</li>
     *   <li><b>DOUBLE</b> — place the upper half of a two-block crop (same block with
     *       {@code DOUBLE_BLOCK_HALF=UPPER}) once {@code newAge >= doubleAge}.</li>
     * </ol>
     *
     * <p>If no side effect is configured and the block is a {@link BonemealableBlock},
     * it degrades to a single {@code performBonemeal} call at maturity to trigger the
     * block's native companion placement (experimental; needs in-game verification).</p>
     *
     * <p>All internal {@code setBlock} calls are guarded by {@link InternalGrowthFlag}
     * so {@code LevelMixin} does not re-track the placed companion/transform blocks.</p>
     *
     * <p>StemBlock is excluded — stems use deterministic fruiting
     * ({@link #tryPlaceStemFruit}) and must not run side effects.</p>
     *
     * @param level       the level
     * @param pos         the crop position
     * @param matureState the crop's mature block state
     * @param block       the crop block
     * @param newAge      the new (mature) age
     * @return {@code true} if the caller should keep the tracking entry for
     *         {@code pos} (a TRANSFORM replaced the crop with a new growable
     *         crop at the same position, which {@link #placeAndTrack} already
     *         re-registered); {@code false} to remove the entry
     */
    /**
     * Side-effect decision for a crop that just reached maturity.
     *
     * <p>The enum mirrors the priority order in
     * {@link #applyMaturitySideEffects}: TRANSFORM → COMPANION → DOUBLE →
     * BONEMEAL fallback → NONE. The actual placement logic (which additionally
     * checks the world, e.g. whether the position above is air/water, or whether
     * {@code newAge >= doubleAge}) stays in the caller; this method only decides
     * <em>which</em> strategy applies, as a pure function of the override and the
     * block's capability. Package-private so unit tests can exercise the decision
     * without a Minecraft level.</p>
     */
    static MaturitySideEffects.MaturitySideEffect decideSideEffect(StructureDescriptor descriptor,
                                                                   int newAge,
                                                                   boolean canBonemeal) {
        return MaturitySideEffects.decideSideEffect(descriptor, newAge, canBonemeal);
    }

    public static boolean applyMaturitySideEffects(Level level, BlockPos pos, BlockState matureState, Block block, int newAge) {
        return MaturitySideEffects.applyMaturitySideEffects(level, pos, matureState, block, newAge);
    }

    /**
     * Place a block state and (re)register it for calendar tracking if it is a
     * recognized growable crop. All internal {@code setBlock} calls are guarded
     * by {@link InternalGrowthFlag} so {@code LevelMixin} does not re-track the
     * placed block.
     *
     * @param level the level
     * @param pos   the position to place at
     * @param state the block state to place
     */
    public static void placeAndTrack(Level level, BlockPos pos, BlockState state) {
        EntryStore.placeAndTrack(level, pos, state);
    }

    public static void placeAndTrack(Level level, BlockPos pos, BlockState state, int plantedDay) {
        EntryStore.placeAndTrack(level, pos, state, plantedDay);
    }

    /**
     * Whether a crop block is a climb-family crop (its override carries a
     * {@code climbBlock} and a positive {@code maxClimbHeight}). Climb crops must
     * stay tracked past maturity so the catch-up loops can keep driving
     * {@link #tryClimbVine}.
     *
     * @param block the crop block
     * @return {@code true} if the block participates in calendar-driven climbing
     */
    public static boolean isClimbCrop(Block block) {
        return CropClassifier.isClimbCrop(block);
    }

    /**
     * Calendar-driven vine climbing (Farmers Delight tomato crops). Grows the
     * vine stack up its support (rope) one segment per suitable day, capped at
     * {@code maxClimbHeight}, matching the growth rhythm of {@link #simulateGrowth}.
     *
     * <p>Called from the catch-up loops on every cycle (after maturity handling),
     * regardless of whether the age advanced — the number of suitable days between
     * {@code plantedDay} and {@code currentDay} deterministically determines the
     * target stack height, so catch-up is idempotent.</p>
     *
     * @param level           the level
     * @param pos             a position currently occupied by a climb-family block
     * @param plantedDay      the crop's planted solar day
     * @param currentDay      the current solar day
     * @param suitableSeasons the suitable seasons for the crop
     * @param termLength      solar days per Solar Term
     */
    public static void tryClimbVine(Level level, BlockPos pos, int plantedDay, int currentDay,
                                    Set<Season> suitableSeasons, int termLength) {
        ClimbStrategy.tryClimbVine(level, pos, plantedDay, currentDay, suitableSeasons, termLength);
    }

    /**
     * Place or refresh the upper half of a two-block (DOUBLE) crop as soon as
     * {@code newAge} crosses {@code doubleAge}, not only at full maturity.
     *
     * <p>Used by the catch-up loops (on every stage advance), the real-time
     * {@code CropGrowEvent.Pre} handler (before lowering the main block), and
     * {@link #applyMaturitySideEffects} (at maturity), so a crop like flax keeps
     * its upper half throughout age 4..maxAge instead of appearing single-block
     * until fully grown. Existing UPPER halves of the same block are refreshed
     * to keep their age in sync with the lower half.</p>
     *
     * <p>Placing the UPPER half <b>before</b> the LOWER half is required by
     * {@code FlaxBlock.updateShape}: a LOWER half whose slot above is not its
     * UPPER half breaks itself. All internal {@code setBlock} calls are guarded
     * by {@link InternalGrowthFlag}.</p>
     *
     * @param level      the level
     * @param pos        the lower (tracked) crop position
     * @param lowerState the lower half's state after this growth step
     * @param block      the crop block
     * @param newAge     the crop's new age
     */
    public static void placeDoubleUpperHalf(Level level, BlockPos pos, BlockState lowerState, Block block, int newAge) {
        MaturitySideEffects.placeDoubleUpperHalf(level, pos, lowerState, block, newAge);
    }

    public static void mutateToShortGrass(Level level, BlockPos pos, Block block, int flags) {
        MaturitySideEffects.mutateToShortGrass(level, pos, block, flags);
    }

    // =======================================================================
    // Utility Methods
    // =======================================================================

    /**
     * Get the current solar day from Ecliptic Seasons.
     * Used by event handlers to compute day once per level per tick cycle.
     *
     * <p>Always uses the Overworld's solar calendar as the universal clock.
     * Dimensions without a day-night cycle (Nether, End) defer to the Overworld
     * to ensure all crops across all dimensions share a single time reference.
     * Falls back to the current level only if {@code getServer()} is null.</p>
     *
     * @return the current solar day, or 0 if the API is unavailable
     */
    public static int getSolarDays(Level level) {
        // Always try Overworld first — universal clock across all dimensions
        try {
            if (level.getServer() != null) {
                Level overworld = level.getServer().overworld();
                return EclipticSeasonsApi.getInstance().getSolarDays(overworld);
            }
        } catch (Exception e) {
            // Fall through to current-level fallback
        }
        // Fallback: use current level if Overworld is unavailable
        try {
            return EclipticSeasonsApi.getInstance().getSolarDays(level);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Get the current season from Ecliptic Seasons.
     * Used by event handlers to compute season once per level per tick cycle.
     *
     * @return the current season, or {@link Season#NONE} if the API is unavailable
     *         or seasons are disabled
     */
    public static Season getSeason(Level level) {
        try {
            EclipticSeasonsApi api = EclipticSeasonsApi.getInstance();
            if (!api.isSeasonEnabled(level)) return Season.NONE;
            return api.getSeason(level);
        } catch (Exception e) {
            return Season.NONE;
        }
    }

    /**
     * Get the number of solar days per Solar Term (节气) from Ecliptic Seasons.
     * Falls back to {@link CropGrowthConfig#CATCH_UP_SEASON_LENGTH} when the API is unavailable.
     */
    public static int getTermLength(Level level) {
        try {
            int termLength = EclipticSeasonsApi.getInstance().getLastingDaysOfEachTerm(level);
            if (termLength > 0) return termLength;
        } catch (Exception e) {
            // fall through to config fallback
        }
        return CropGrowthConfig.CATCH_UP_SEASON_LENGTH.get();
    }

    /**
     * Get the number of solar days in one full season.
     * Ecliptic Seasons structure: 1 Season = 6 Solar Terms.
     */
    public static int getSeasonLength(Level level) {
        return getTermLength(level) * CropGrowthConfig.SOLAR_TERMS_PER_SEASON;
    }

    /**
     * Module-aware debug log. Emits to the debug log only when the given module
     * is enabled, and mirrors every event into the ring buffer (self-gated on
     * {@link DebugGate.DebugModule#RING}) so crash-site tracing survives even
     * when the log is suppressed by a freeze.
     */
    static void logDebug(DebugGate.DebugModule module, String message, Object... args) {
        if (DebugGate.enabled(module)) {
            PastoralCraft.LOGGER.debug("[CropTracker] " + message, args);
        }
        DebugRingBuffer.record(module.name(), message, args);
    }

    /** Back-compat entry point: defaults to the {@code GROWTH} module. */
    static void logDebug(String message, Object... args) {
        logDebug(DebugGate.DebugModule.GROWTH, message, args);
    }

    /**
     * Scan a chunk's tracked {@code plantedDay}s for anomalies (negative or
     * over-horizon) when the {@code DATA} module is enabled. Pure in-memory read
     * of {@link ChunkCropData} — no getBlockState/setBlock/chunk loading — so it
     * can never cascade into further chunk work. Never throws.
     */
    private static void scanCropDataHealth(ChunkCropData chunkData, int currentDay, Object chunkPos) {
        if (!DebugGate.enabled(DebugGate.DebugModule.DATA)) return;
        try {
            Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
            int[] plantedDays = new int[cropData.size()];
            int i = 0;
            for (CropProgressEntry entry : cropData.values()) {
                plantedDays[i++] = entry.plantedDay;
            }
            DebugDataHealth.HealthReport report =
                    DebugDataHealth.scan(plantedDays, currentDay, DebugDataHealth.defaultHorizon());
            if (!report.ok()) {
                DebugDataHealth.noteNonOk(report, chunkPos);
                PastoralCraft.LOGGER.warn("[CropData] chunk={} {}", chunkPos, report);
                DebugRingBuffer.record("DATA", "chunk={} {}", chunkPos, report);
            }
        } catch (Exception ignored) {
            // A health scan must never crash the tick loop.
        }
    }
}