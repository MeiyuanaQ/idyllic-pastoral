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

    // Block-level non-arable cache: blocks do not change identity, so the
    // freeze/water-crop classification is stable until a config reload. Keeps
    // the hot isNonArableBlock path off the built-in registry (no repeated
    // ResourceLocation lookups per growth attempt).
    private static final Map<Block, Boolean> FREEZE_CACHE = new ConcurrentHashMap<>();

    /** Invalidate {@link #FREEZE_CACHE} (called on config override reload). */
    public static void clearFreezeCache() {
        FREEZE_CACHE.clear();
    }

    // =======================================================================
    // Pure Functions — the core of the plantedDay scheme
    // =======================================================================

    /**
     * The result of simulating a crop's growth between two solar days.
     *
     * @param stage   the final growth stage reached (0 to maxAge)
     * @param mutated true if the crop mutated into short grass during an
     *                unsuitable-season growth attempt
     */
    public record GrowthSimulation(int stage, boolean mutated) {
    }

    /**
     * Classify whether a crop is a "non-arable" crop that freezes (rather than
     * degrading) during unsuitable seasons. Sugar cane, nether wart, cocoa and
     * any {@link LiquidBlockContainer} (water crops such as kelp, Farmers
     * Delight rice, Kaleidoscope rice) simply stop growing in unsuitable
     * seasons; all other supported crops (field crops, berry bushes, stems)
     * continue attempting growth with the three-way unsuitable-season outcome.
     *
     * @param block the crop block to classify
     * @return true if the crop freezes during unsuitable seasons
     */
    public static boolean isNonArableBlock(Block block) {
        Boolean cached = FREEZE_CACHE.get(block);
        if (cached != null) return cached;
        boolean nonArable = block instanceof SugarCaneBlock
                || block instanceof NetherWartBlock
                || block instanceof CocoaBlock
                || block instanceof LiquidBlockContainer
                // KaleidoscopeCookery rice_crop implements SimpleWaterloggedBlock
                // (a DIFFERENT interface from LiquidBlockContainer), so it would
                // otherwise be classified arable and mutate to short grass in water.
                // Detect it structurally and freeze it like the other water crops.
                || isSegmentedRice(block)
                // freeze=true override (e.g. Farmers Delight tomato crops that must
                // not turn into short grass during unsuitable seasons).
                || isFreezeOverride(block);
        FREEZE_CACHE.put(block, nonArable);
        return nonArable;
    }

    private static boolean isFreezeOverride(Block block) {
        CropGrowthConfig.CropOverride override =
                CropGrowthConfig.getOverride(BuiltInRegistries.BLOCK.getKey(block));
        return override != null && override.freeze;
    }

    /**
     * Position-level non-arable check. In addition to {@link #isNonArableBlock},
     * a crop also freezes when the block directly below it is itself a water
     * crop (a {@link LiquidBlockContainer} that is a recognized crop). This
     * covers Farmers Delight {@code rice_panicles}, which is a plain
     * {@link CropBlock} whose supporting block is the waterlogged
     * {@code rice} block.
     *
     * <p>The below-block test is deliberately tightened to "water crop" (not
     * merely any {@link LiquidBlockContainer}) to avoid false freezes on top of
     * waterlogged slabs, buckets and similar structures.</p>
     *
     * @param level the level
     * @param pos   the crop position
     * @param block the crop block at {@code pos}
     * @return true if the crop freezes during unsuitable seasons
     */
    public static boolean isNonArableAt(Level level, BlockPos pos, Block block) {
        if (isNonArableBlock(block)) return true;
        Block below = level.getBlockState(pos.below()).getBlock();
        return below instanceof LiquidBlockContainer
                && CropKindResolver.kindOf(below) != CropKind.NONE;
    }

    // =======================================================================
    // Segmented Water Rice (KaleidoscopeCookery rice_crop)
    // =======================================================================

    /**
     * Sentinel for "no segmented-rice location property". {@link ConcurrentHashMap}
     * forbids null values in {@code computeIfAbsent}, so a non-null sentinel is
     * used to represent "not segmented rice".
     *
     * <p>All uses are reference-equality checks ({@code ==}/{@code !=}), so the
     * property's value range is irrelevant; it only needs to be a valid
     * {@link IntegerProperty} (max strictly greater than min).</p>
     */
    private static final IntegerProperty NO_SEGMENT_PROPERTY =
            IntegerProperty.create("pastoralcraft_no_segment", 0, 1);

    private static final Map<Block, IntegerProperty> SEGMENTED_RICE_LOCATION =
            new ConcurrentHashMap<>();

    private static IntegerProperty segmentedLocationProperty(Block block) {
        return SEGMENTED_RICE_LOCATION.computeIfAbsent(block, CropGrowthTracker::findSegmentProperty);
    }

    private static IntegerProperty findSegmentProperty(Block block) {
        for (Property<?> property : block.getStateDefinition().getProperties()) {
            if (!(property instanceof IntegerProperty intProperty)
                    || !"location".equals(intProperty.getName())) {
                continue;
            }
            // Require exactly three values {0, 1, 2} (DOWN/MIDDLE/UP).
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            int count = 0;
            boolean hasZero = false;
            boolean hasTwo = false;
            for (int value : intProperty.getPossibleValues()) {
                count++;
                if (value < min) min = value;
                if (value > max) max = value;
                if (value == 0) hasZero = true;
                if (value == 2) hasTwo = true;
            }
            if (count == 3 && min == 0 && max == 2 && hasZero && hasTwo) {
                return intProperty;
            }
        }
        return NO_SEGMENT_PROPERTY;
    }

    /**
     * Detect segmented water rice (e.g. KaleidoscopeCookery's {@code rice_crop},
     * a {@link CropBlock} with an {@code AGE} property and a three-value
     * {@code LOCATION} property DOWN=0/MIDDLE=1/UP=2). Detection is structural
     * (per-block cached property scan) so no mod-id or classpath dependency is
     * required, and the hot path is O(1).
     *
     * @param block the block to classify
     * @return true if the block exposes a segmented-rice location property
     */
    public static boolean isSegmentedRice(Block block) {
        return segmentedLocationProperty(block) != NO_SEGMENT_PROPERTY;
    }

    /**
     * Get the segment index of a segmented rice state: DOWN=0, MIDDLE=1, UP=2.
     * Returns -1 if the block is not segmented rice.
     *
     * @param state the block state to query
     * @return the segment index, or -1 if not segmented rice
     */
    public static int getRiceSegment(BlockState state) {
        IntegerProperty location = segmentedLocationProperty(state.getBlock());
        if (location == NO_SEGMENT_PROPERTY || !state.hasProperty(location)) return -1;
        return state.getValue(location);
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
        IntegerProperty location = segmentedLocationProperty(level.getBlockState(downPos).getBlock());
        if (location != NO_SEGMENT_PROPERTY) {
            syncRiceSegments(level, downPos, age, location);
        }
    }

    private static void syncRiceSegments(Level level, BlockPos downPos, int age, IntegerProperty location) {
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
                level.setBlock(segmentPos, synced, 2);
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
    public static GrowthSimulation simulateGrowth(BlockPos pos, int plantedDay, int currentDay,
                                                   int daysPerStage, int maxAge,
                                                   int seasonLength, Set<Season> suitableSeasons,
                                                   boolean nonArable) {
        return simulateGrowth(pos.asLong(), plantedDay, currentDay, daysPerStage, maxAge,
                seasonLength, suitableSeasons, nonArable,
                CropGrowthConfig.UNSUITABLE_MUTATE_CHANCE.get(),
                CropGrowthConfig.UNSUITABLE_GROW_CHANCE.get());
    }

    /**
     * Long-key overload of {@link #simulateGrowth(BlockPos, int, int, int, int, int, Set, boolean)}
     * for callers that only hold a packed position key (and for unit tests). The mutation and
     * growth chances are passed explicitly so this overload stays a pure function.
     */
    public static GrowthSimulation simulateGrowth(long posKey, int plantedDay, int currentDay,
                                                   int daysPerStage, int maxAge,
                                                   int seasonLength, Set<Season> suitableSeasons,
                                                   boolean nonArable,
                                                   double mutateChance, double growChance) {
        if (currentDay <= plantedDay) return new GrowthSimulation(0, false);
        if (daysPerStage <= 0) return new GrowthSimulation(maxAge, false);
        if (maxAge <= 0) return new GrowthSimulation(0, false);

        // Clamp the roll chances so the three-way outcome stays well-formed:
        // mutate + grow must not exceed 1.0, otherwise the "no growth" branch
        // can never be reached. growChance is clamped (not mutateChance) so a
        // configured mutate setting keeps its exact value.
        double mut = Math.max(0.0, Math.min(1.0, mutateChance));
        double grow = Math.max(0.0, Math.min(1.0, growChance));
        if (mut + grow > 1.0) {
            grow = 1.0 - mut;
        }

        // If all seasons are suitable, simple division — no unsuitable-season
        // growth attempts ever occur, so no mutation is possible.
        if (suitableSeasons.contains(Season.NONE) || suitableSeasons.size() >= 4) {
            int stage = Math.min((currentDay - plantedDay) / daysPerStage, maxAge);
            return new GrowthSimulation(stage, false);
        }

        // One season = SOLAR_TERMS_PER_SEASON solar terms; seasonOfDay expects
        // the per-term length.
        int termLength = Math.max(1, seasonLength / CropGrowthConfig.SOLAR_TERMS_PER_SEASON);

        // Non-arable crops freeze in unsuitable seasons: their growth is exactly
        // the number of suitable days elapsed (countSuitableDays), independent of
        // season order. This replaces the old attempt-loop outcome (which could
        // gain +1 stage on the first suitable day after a long unsuitable run,
        // e.g. cocoa instantly jumping a stage when summer arrives) and smooths
        // sugar cane / kelp / nether wart across season boundaries.
        if (nonArable) {
            int suitableDays = countSuitableDays(plantedDay, currentDay, suitableSeasons, termLength);
            int stage = Math.min(suitableDays / daysPerStage, maxAge);
            return new GrowthSimulation(stage, false);
        }

        // Growth is organized into daysPerStage-long attempts. Each attempt is
        // classified by the season at its end day, so we iterate per attempt
        // (not per day) — keeping this an O(elapsedDays / daysPerStage) function.
        int totalAttempts = (currentDay - plantedDay) / daysPerStage;
        int stage = 0;
        for (int attempt = 0; attempt < totalAttempts; attempt++) {
            int endDay = plantedDay + (attempt + 1) * daysPerStage;
            Season season = seasonOfDay(endDay, termLength);
            if (isSeasonSuitable(season, suitableSeasons)) {
                // Suitable season: 100% growth per attempt.
                stage++;
                if (stage >= maxAge) return new GrowthSimulation(maxAge, false);
            } else {
                // Unsuitable season, arable crop: deterministic three-way roll.
                // (non-arable crops were handled by the countSuitableDays
                // early-return above, so this branch is always arable.)
                double roll = attemptHash(posKey, plantedDay, attempt);
                if (roll < mut) {
                    return new GrowthSimulation(stage, true);
                } else if (roll < mut + grow) {
                    stage++;
                    if (stage >= maxAge) return new GrowthSimulation(maxAge, false);
                }
                // else: no growth.
            }
        }
        return new GrowthSimulation(stage, false);
    }

    /**
     * The result of simulating a melon/pumpkin stem's lifecycle between two
     * solar days.
     *
     * @param stage   the final growth stage reached (0 to {@link StemBlock#MAX_AGE})
     * @param mutated true if the stem mutated into short grass during an
     *                unsuitable-season fruiting cycle
     * @param fruited true if the stem produced a fruit (reached MAX_AGE and a
     *                full daysPerFruit window elapsed in a suitable season, or
     *                won the unsuitable-season fruit roll)
     */
    public record StemSimulation(int stage, boolean mutated, boolean fruited) {
    }

    /**
     * Simulate a stem's (melon/pumpkin) lifecycle from {@code plantedDay} to
     * {@code currentDay}, reading the unsuitable-season roll chances from config.
     *
     * <p>See the long-key overload for the full semantics. This overload is the
     * production entry point used by {@link #processStem}.</p>
     */
    public static StemSimulation simulateStem(BlockPos pos, int plantedDay, int currentDay,
                                              int daysPerStage, int daysPerFruit, int maxAge,
                                              int seasonLength, Set<Season> suitableSeasons) {
        return simulateStem(pos.asLong(), plantedDay, currentDay, daysPerStage, daysPerFruit, maxAge,
                seasonLength, suitableSeasons,
                CropGrowthConfig.STEM_UNSUITABLE_MUTATE_CHANCE.get(),
                CropGrowthConfig.STEM_UNSUITABLE_FRUIT_CHANCE.get());
    }

    /**
     * Pure-function overload of {@link #simulateStem(BlockPos, int, int, int, int, int, int, Set)}
     * for callers that only hold a packed position key (and for unit tests).
     *
     * <p><b>Semantics:</b></p>
     * <ul>
     *   <li><b>Suitable days</b> advance growth one stage per {@code daysPerStage}
     *       until {@code maxAge}; once mature, they advance fruiting one fruit per
     *       {@code daysPerFruit}. A stem fruits at most once (a fruit stays attached
     *       until harvested), so {@code fruited} latches to {@code true}.</li>
     *   <li><b>Unsuitable days</b> never grow the stem. Every {@code daysPerFruit}
     *       unsuitable days a deterministic roll decides: mutate to short grass
     *       ({@code mutateChance}), or — for a mature stem that has not yet fruited —
     *       fruit anyway ({@code fruitChance}), or no change. The per-day
     *       accumulation means the very first unsuitable day never mutates, so a
     *       stem freshly entering an unsuitable season is not instantly destroyed.</li>
     * </ul>
     *
     * <p>Growth/fruit/unsuitable counters each accumulate independently across
     * season boundaries, so time-skip catch-up and real-time growth agree.</p>
     */
    public static StemSimulation simulateStem(long posKey, int plantedDay, int currentDay,
                                              int daysPerStage, int daysPerFruit, int maxAge,
                                              int seasonLength, Set<Season> suitableSeasons,
                                              double mutateChance, double fruitChance) {
        if (currentDay <= plantedDay) return new StemSimulation(0, false, false);
        if (daysPerStage <= 0 || daysPerFruit <= 0 || maxAge <= 0) return new StemSimulation(0, false, false);

        // Clamp the roll chances so the two-way outcome stays well-formed
        // (mutate + fruit must not exceed 1.0).
        double mut = Math.max(0.0, Math.min(1.0, mutateChance));
        double fruit = Math.max(0.0, Math.min(1.0, fruitChance));
        if (mut + fruit > 1.0) {
            fruit = Math.max(0.0, 1.0 - mut);
        }

        // Year-round (or ES disabled): pure calendar growth + fruiting, no mutation.
        if (suitableSeasons.contains(Season.NONE) || suitableSeasons.size() >= 4) {
            int elapsed = currentDay - plantedDay;
            int stage = Math.min(elapsed / daysPerStage, maxAge);
            boolean fruited = stage >= maxAge && (elapsed - maxAge * daysPerStage) >= daysPerFruit;
            return new StemSimulation(stage, false, fruited);
        }

        int termLength = Math.max(1, seasonLength / CropGrowthConfig.SOLAR_TERMS_PER_SEASON);

        int stage = 0;
        boolean fruited = false;
        int growAccum = 0;
        int fruitAccum = 0;
        int unsuitableAccum = 0;
        int attempt = 0;

        for (int day = plantedDay + 1; day <= currentDay; day++) {
            Season season = seasonOfDay(day, termLength);
            if (isSeasonSuitable(season, suitableSeasons)) {
                if (stage < maxAge) {
                    growAccum++;
                    if (growAccum >= daysPerStage) {
                        growAccum = 0;
                        stage++;
                    }
                } else if (!fruited) {
                    fruitAccum++;
                    if (fruitAccum >= daysPerFruit) {
                        fruitAccum = 0;
                        fruited = true;
                    }
                }
                // else: already fruited — suitable days are inert (fruit still attached).
            } else {
                unsuitableAccum++;
                if (unsuitableAccum >= daysPerFruit) {
                    unsuitableAccum = 0;
                    double roll = attemptHash(posKey, plantedDay, attempt);
                    attempt++;
                    if (roll < mut) {
                        return new StemSimulation(stage, true, fruited);
                    }
                    if (stage >= maxAge && !fruited && roll < mut + fruit) {
                        fruited = true;
                    }
                }
            }
        }
        return new StemSimulation(stage, false, fruited);
    }

    /**
     * Determine the season for a given solar day using the Ecliptic Seasons
     * solar-term calendar: 1 Year = 24 Solar Terms, 1 Season = 6 Solar Terms.
     *
     * @param solarDay   the absolute solar day number
     * @param termLength how many solar days per Solar Term (节气)
     * @return the Season at the given solar day
     */
    public static Season seasonOfDay(int solarDay, int termLength) {
        if (termLength <= 0) return Season.NONE;
        int termIndex = Math.floorMod(Math.floorDiv(solarDay, termLength), 24);
        return SeasonTagResolver.ORDERED_SEASONS[termIndex / 6];
    }

    /**
     * Count how many days in {@code [startDay, endDay)} fall in a suitable
     * season, using the Ecliptic Seasons solar-term calendar.
     *
     * <p><b>O(1):</b> the full year (24 solar terms = {@code termLength * 24}
     * days) is skipped with integer division, and only the remainder window is
     * walked term-by-term (at most 24 terms). No per-day iteration, so this is
     * safe to call in hot growth paths.</p>
     *
     * @param startDay        exclusive lower bound (the planted day)
     * @param endDay          exclusive upper bound (the current day)
     * @param suitableSeasons the set of suitable seasons
     * @param termLength      solar days per Solar Term (节气)
     * @return the number of suitable days in the interval
     */
    public static int countSuitableDays(int startDay, int endDay, Set<Season> suitableSeasons, int termLength) {
        if (endDay <= startDay) return 0;
        if (suitableSeasons.contains(Season.NONE) || suitableSeasons.size() >= 4) return endDay - startDay;
        if (termLength <= 0) return endDay - startDay;

        int period = termLength * 24;
        // How many suitable terms (each termLength days long) in one full year.
        int suitableTerms = 0;
        for (int t = 0; t < 24; t++) {
            if (isSeasonSuitable(SeasonTagResolver.ORDERED_SEASONS[t / 6], suitableSeasons)) {
                suitableTerms++;
            }
        }
        int suitableDaysPerPeriod = suitableTerms * termLength;

        int n = endDay - startDay;
        int fullPeriods = n / period;
        int suitableDays = fullPeriods * suitableDaysPerPeriod;

        // Walk the leftover window term-by-term (at most 24 iterations).
        int remainder = n % period;
        if (remainder > 0) {
            int windowStart = endDay - remainder;
            int covered = 0;
            int term = Math.floorDiv(windowStart, termLength);
            while (covered < remainder) {
                int lo = Math.max(term * termLength, windowStart);
                int hi = Math.min(term * termLength + termLength, endDay);
                if (lo < hi && isSeasonSuitable(SeasonTagResolver.ORDERED_SEASONS[Math.floorMod(term, 24) / 6], suitableSeasons)) {
                    suitableDays += hi - lo;
                }
                covered += hi - lo;
                term++;
            }
        }
        return suitableDays;
    }

    /**
     * Check if the given season is suitable for a crop.
     * A season is suitable if it's in the suitableSeasons set, or if the set contains all seasons.
     * Season.NONE (ES disabled) means all seasons are suitable.
     */
    public static boolean isSeasonSuitable(Season season, Set<Season> suitableSeasons) {
        if (season == null || season == Season.NONE) return true;
        if (suitableSeasons.contains(Season.NONE)) return true;
        return suitableSeasons.contains(season);
    }

    /**
     * Resolve the seasons in which a crop block can grow.
     *
     * <p>If Ecliptic Seasons is not enabled ({@code currentSeason} is
     * {@link Season#NONE}), the crop is treated as suitable year-round.
     * Otherwise the suitable seasons are derived from the Ecliptic Seasons
     * crop season block tags via {@link SeasonTagResolver}.</p>
     *
     * @param currentSeason the current season for the level, or {@link Season#NONE}
     *                      when seasons are disabled or unavailable
     * @param block         the crop block to resolve
     * @return an immutable set of suitable seasons
     */
    public static Set<Season> resolveSuitableSeasons(Season currentSeason, Block block) {
        if (currentSeason == Season.NONE) {
            return SeasonTagResolver.ALL_SEASONS;
        }
        return SeasonTagResolver.resolve(block);
    }

    // =======================================================================
    // Deterministic Hash Utility — for weed mutation pseudo-randomness
    // =======================================================================

    /**
     * MurmurHash3-inspired 64-bit finalizer for deterministic pseudo-random values.
     * Given the same input, always produces the same output — essential for
     * consistent mutation results between real-time and chunk-load catch-up.
     *
     * @param value the input value to hash
     * @return a uniformly distributed 64-bit hash
     */
    private static long hashLong(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    /**
     * Deterministic double in [0.0, 1.0) derived from the crop position, its
     * planted day, and the 0-based index of the growth attempt. Mixing the
     * attempt index ensures each unsuitable-season growth attempt gets an
     * independent pseudo-random roll while remaining fully deterministic, so
     * real-time processing and chunk-load catch-up always agree.
     */
    private static double attemptHash(long posKey, int plantedDay, int attemptIndex) {
        long seed = posKey
                ^ (Integer.toUnsignedLong(plantedDay) * 0x9E3779B97F4A7C15L)
                ^ hashLong((long) attemptIndex + 0x517CC1B727220A95L);
        long hash = hashLong(seed);
        return (hash & Long.MAX_VALUE) / (double) Long.MAX_VALUE;
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

    // =======================================================================
    // StemBlock Fruiting — deterministic pumpkin/melon spawning
    // =======================================================================

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
            level.setBlock(fruitPos, fruitBlock.defaultBlockState(), 3);
            // Convert stem to AttachedStemBlock facing the fruit
            BlockState attachedState = attachedStemBlock.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, dir);
            level.setBlock(pos, attachedState, 3);

            logDebug("StemBlock fruiting: placed {} at {} facing {}",
                    stemId, fruitPos, dir);
            return true;
        }
        return false;
    }

    // =======================================================================
    // Pure Support / Decision Helpers (extracted for unit testing)
    // =======================================================================

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
     * Back-calculate the plantedDay for a newly tracked crop that already has
     * growth stages (e.g. world-gen farms, or an age>0 crop placed by another
     * mod). Clamped so a negative daysPerStage never pushes plantedDay into
     * the future (which would freeze growth forever).
     *
     * @param currentDay   the current solar day
     * @param currentAge   the crop's current age
     * @param daysPerStage solar days per growth stage
     * @return the back-calculated plantedDay
     */
    static int backCalculatedPlantedDay(int currentDay, int currentAge, int daysPerStage) {
        int solarDay = currentDay - (currentAge * daysPerStage);
        return solarDay > currentDay ? currentDay : solarDay;
    }

    /**
     * Back-calculate the plantedDay for a bonemeal-accelerated non-stem crop so
     * the calendar-derived stage stays aligned with the accelerated world age,
     * without crossing into unsuitable seasons.
     *
     * <p>Unlike {@link #backCalculatedPlantedDay} (simple calendar subtraction),
     * this walks backward from {@code currentDay} over the {@code daysPerStage}
     * attempt end-days and only counts those whose season is suitable. The first
     * unsuitable end-day stops the scan, so the returned plantedDay never spans a
     * season boundary — {@link #simulateGrowth} then sees only suitable end-days,
     * grows deterministically (no mutation roll), and reports exactly the number
     * of aligned stages. When the suitable span is shorter than {@code newAge}
     * the acceleration is only partial, which is strictly safer than mutating.</p>
     *
     * @param currentDay      the current solar day
     * @param newAge          the crop's age after acceleration
     * @param daysPerStage    solar days per growth stage
     * @param suitableSeasons the crop's suitable seasons
     * @param seasonLength    solar days in one full season (divided by
     *                        {@code SOLAR_TERMS_PER_SEASON} to get the term length)
     * @return the back-calculated plantedDay (never later than {@code currentDay}
     *         for a positive {@code daysPerStage})
     */
    static int backCalculatePlantedDaySuitable(int currentDay, int newAge, int daysPerStage,
                                               Set<Season> suitableSeasons, int seasonLength) {
        if (newAge <= 0 || daysPerStage <= 0) return currentDay;
        if (suitableSeasons.contains(Season.NONE) || suitableSeasons.size() >= 4) {
            return currentDay - newAge * daysPerStage;
        }
        int termLength = Math.max(1, seasonLength / CropGrowthConfig.SOLAR_TERMS_PER_SEASON);
        int aligned = 0;
        for (int k = 0; k < newAge; k++) {
            int endDay = currentDay - k * daysPerStage;
            if (isSeasonSuitable(seasonOfDay(endDay, termLength), suitableSeasons)) {
                aligned++;
            } else {
                break;
            }
        }
        return currentDay - aligned * daysPerStage;
    }

    /**
     * Back-calculate the root plantedDay for a bonemeal-accelerated HEIGHT crop
     * (kelp / sugar cane) so the calendar target height stays aligned with the
     * accelerated world height, without crossing into unsuitable seasons.
     *
     * <p>HEIGHT crops track only the root; target height = simulateGrowth().stage + 1.
     * Bonemeal adds one world block above the head but leaves plantedDay fixed, so
     * the calendar target lags and catch-up (while currentHeight < targetHeight)
     * stalls N x daysPerStage days. This maps the new 1-based height to a 0-based
     * age (newHeight - 1, clamped to maxHeight - 1) and reuses
     * {@link #backCalculatePlantedDaySuitable} for the conservative back-shift.</p>
     *
     * @param currentDay      current solar day
     * @param newHeight       stalk height after acceleration (1-based block count)
     * @param maxHeight       stalk max height (kelp 26, sugar cane 3)
     * @param daysPerStage    solar days per growth stage
     * @param suitableSeasons the crop suitable seasons
     * @param seasonLength    solar days in one full season
     * @return back-calculated plantedDay (never later than currentDay)
     */
    static int heightCropPlantedDayAfterBonemeal(int currentDay, int newHeight, int maxHeight,
                                                 int daysPerStage, Set<Season> suitableSeasons,
                                                 int seasonLength) {
        int newAge = Math.max(0, Math.min(newHeight - 1, maxHeight - 1));
        return backCalculatePlantedDaySuitable(currentDay, newAge, daysPerStage,
                suitableSeasons, seasonLength);
    }

    /**
     * Clamp a companion crop's plantedDay to the current day. Companion crops
     * (e.g. Farmers Delight rice_panicles) share their base crop's plantedDay
     * so their calendar phases stay in sync; the clamp keeps a stale/future
     * base plantedDay from deadlocking growth.
     *
     * @param plantedDay the base crop's plantedDay
     * @param currentDay the current solar day
     * @return the clamped plantedDay
     */
    static int clampPlantedDay(int plantedDay, int currentDay) {
        return Math.min(plantedDay, currentDay);
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
        return !aboveState.equals(upperState);
    }

    /**
     * Whether the state above a crop is that crop's UPPER half (used when a
     * mutated two-block crop must clear its detached upper half so it does not
     * float).
     *
     * @param aboveState the state directly above the crop
     * @param cropBlock  the crop block
     * @return true when aboveState is the UPPER half of cropBlock
     */
    static boolean isUpperHalfOf(BlockState aboveState, Block cropBlock) {
        return aboveState.getBlock() == cropBlock
                && aboveState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && aboveState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
    }

    /**
     * Whether a block state is the UPPER half of a two-block (DOUBLE) crop such
     * as flax or pitcher_crop, for which the tracker only follows the LOWER
     * half. An UPPER-half state that is independently tracked would advance its
     * own age, and {@link #getCropStateForAge} resets {@code HALF} to LOWER —
     * corrupting the two-block structure.
     *
     * @param state    the block state to test
     * @param override the crop override (null = not configured as a DOUBLE crop)
     * @return true when the state is an UPPER half of a DOUBLE crop
     */
    static boolean isDoubleCropUpperHalf(BlockState state, @Nullable CropGrowthConfig.CropOverride override) {
        return override != null && override.doubleAge >= 0
                && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
    }

    /**
     * Resolve the stem whose calendar/season config anchors a stem-family block.
     * {@link AttachedStemBlock}s carry no season tag of their own, so they must
     * reuse their base stem's ({@code melon_stem}/{@code pumpkin_stem}) config.
     */
    private static Block stemAnchor(Block block) {
        if (block == Blocks.ATTACHED_MELON_STEM) return Blocks.MELON_STEM;
        if (block == Blocks.ATTACHED_PUMPKIN_STEM) return Blocks.PUMPKIN_STEM;
        return block;
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
     * @param level         the level
     * @param pos           the stem position
     * @param state         the stem's current block state
     * @param progress      the tracked progress entry (plantedDay)
     * @param currentDay    the current solar day
     * @param currentSeason the current season
     * @param seasonLength  solar days per full season
     * @return {@code true} when the stem mutated to short grass and its tracking
     *         entry should be removed
     */
    public static boolean processStem(Level level, BlockPos pos, BlockState state,
                                      CropProgressEntry progress, int currentDay,
                                      Season currentSeason, int seasonLength) {
        Block anchor = stemAnchor(state.getBlock());
        ResourceLocation anchorId = BuiltInRegistries.BLOCK.getKey(anchor);
        int daysPerStage = CropGrowthConfig.getDaysPerStage(anchorId);
        int daysPerFruit = CropGrowthConfig.DAYS_PER_FRUIT.get();
        Set<Season> suitableSeasons = resolveSuitableSeasons(currentSeason, anchor);

        StemSimulation sim = simulateStem(pos.asLong(), progress.plantedDay, currentDay,
                daysPerStage, daysPerFruit, StemBlock.MAX_AGE, seasonLength, suitableSeasons,
                CropGrowthConfig.STEM_UNSUITABLE_MUTATE_CHANCE.get(),
                CropGrowthConfig.STEM_UNSUITABLE_FRUIT_CHANCE.get());

        if (sim.mutated()) {
            boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
            try {
                level.setBlock(pos, Blocks.SHORT_GRASS.defaultBlockState(), 2);
            } finally {
                if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
            }
            logDebug("Stem at {} mutated to short grass (plantedDay={}, currentDay={})",
                    pos, progress.plantedDay, currentDay);
            return true;
        }

        if (state.getBlock() instanceof StemBlock) {
            boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
            try {
                int stemAge = getCropAge(state);
                if (sim.stage() > stemAge) {
                    int newAge = Math.min(sim.stage(), StemBlock.MAX_AGE);
                    level.setBlock(pos, getCropStateForAge(state, newAge), 2);
                    stemAge = newAge;
                    logDebug("Stem {} at {} advanced to age {} (plantedDay={}, currentDay={})",
                            anchorId, pos, newAge, progress.plantedDay, currentDay);
                }
                if (sim.fruited() && stemAge >= StemBlock.MAX_AGE) {
                    tryPlaceStemFruit(level, pos, level.getBlockState(pos));
                }
            } finally {
                if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
            }
        }
        return false;
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
    private static int getSugarCaneHeight(Level level, BlockPos pos) {
        if (!(level.getBlockState(pos).getBlock() instanceof SugarCaneBlock)) return 0;
        int height = 0;
        BlockPos checkPos = pos;
        while (level.getBlockState(checkPos).getBlock() instanceof SugarCaneBlock && height < 5) {
            height++;
            checkPos = checkPos.above();
        }
        return height;
    }

    /**
     * Grow a sugar cane stalk by placing a new block on top.
     * Only grows if the current height is less than the maximum (3).
     *
     * @param level the world level
     * @param pos   the bottom sugar cane block position
     * @param currentHeight the current stalk height
     * @return true if sugar cane was grown (a new block was placed)
     */
    private static boolean growSugarCane(Level level, BlockPos pos, int currentHeight) {
        if (currentHeight >= 3) return false;
        BlockPos topPos = pos.above(currentHeight);
        if (level.getBlockState(topPos).isAir()) {
            level.setBlock(topPos, Blocks.SUGAR_CANE.defaultBlockState(), 2);
            logDebug("Sugar cane grew: bottom={} new height={}", pos, currentHeight + 1);
            return true;
        }
        return false;
    }

    /**
     * Handle sugar cane harvest: a non-bottom sugar cane block at
     * {@code harvestedPos} is being removed (e.g. the player broke the second
     * or third block of the stalk).
     *
     * <p>The blocks below {@code harvestedPos} survive and should continue
     * growing from their current height. The root block's
     * {@link CropProgressEntry#plantedDay} is therefore back-calculated to
     * preserve the progress already accumulated in the surviving blocks:</p>
     *
     * <pre>
     * plantedDay = currentDay - (remainingHeight - 1) * daysPerStage
     * </pre>
     *
     * <p>With a single surviving block ({@code remainingHeight == 1}) this
     * reduces to {@code plantedDay = currentDay}, so the remaining stalk starts
     * a fresh growth cycle after harvest.</p>
     *
     * <p>The entry is created if missing, which also heals legacy data where
     * the entry was removed when the stalk previously reached max height.</p>
     *
     * @param level        the world level
     * @param harvestedPos the sugar cane block being removed (non-bottom)
     */
    public static void onSugarCaneHarvest(Level level, BlockPos harvestedPos) {
        // Walk down to the root (bottom) block, bounded to guard against
        // pathological over-tall stalks from other mods or data corruption.
        BlockPos root = harvestedPos;
        int depth = 0;
        while (level.getBlockState(root.below()).getBlock() instanceof SugarCaneBlock && depth < 8) {
            root = root.below();
            depth++;
        }

        int currentDay = getSolarDays(level);
        int daysPerStage = CropGrowthConfig.getDaysPerStage(
                BuiltInRegistries.BLOCK.getKey(Blocks.SUGAR_CANE));

        // Everything below the harvested block survives the harvest.
        int remainingHeight = Math.max(1, harvestedPos.getY() - root.getY());

        // Back-calculate plantedDay so the surviving height maps to the
        // progress already accumulated (1 block = 0 stages, 2 blocks = 1 stage).
        int plantedDay = currentDay - (remainingHeight - 1) * daysPerStage;
        // Defensive clamp: an invalid (negative) daysPerStage must never push
        // plantedDay into the future, which would freeze growth forever.
        if (plantedDay > currentDay) {
            plantedDay = currentDay;
        }

        LevelChunk chunk = level.getChunkAt(root);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        cropData.put(root, new CropProgressEntry(plantedDay));
        registerTrackedChunk(chunk);

        logDebug("Sugar cane harvested: harvested={} root={} remainingHeight={} plantedDay={}",
                harvestedPos, root, remainingHeight, plantedDay);
    }

    /**
     * Account for a sugar cane stalk being accelerated by bonemeal (or an
     * external mod placing sugar cane above the stalk): one sugar cane block was
     * placed above the head while the root plantedDay stayed fixed. Back-shift the
     * root plantedDay by the accelerated stages so the calendar target height stays
     * aligned; only a backward (earlier) shift is ever applied.
     *
     * <p>Vanilla sugar cane is not bonemeal-able, so this is a defensive path that
     * normally never runs; it only fires when another mod performs a
     * {@code water → sugar cane} setBlock above an existing stalk.</p>
     *
     * @param level   the world level
     * @param headPos the freshly placed head position (pos.above() of the old head)
     */
    public static void onSugarCaneBonemeal(Level level, BlockPos headPos) {
        // Walk down to the root (bottom) block, bounded like onSugarCaneHarvest.
        BlockPos root = headPos;
        int depth = 0;
        while (level.getBlockState(root.below()).getBlock() instanceof SugarCaneBlock && depth < 8) {
            root = root.below();
            depth++;
        }

        int currentDay = getSolarDays(level);
        int daysPerStage = CropGrowthConfig.getDaysPerStage(
                BuiltInRegistries.BLOCK.getKey(Blocks.SUGAR_CANE));
        Set<Season> suitableSeasons = resolveSuitableSeasons(getSeason(level), Blocks.SUGAR_CANE);
        int seasonLength = getSeasonLength(level);
        int newHeight = headPos.getY() - root.getY() + 1;

        int plantedDay = heightCropPlantedDayAfterBonemeal(currentDay, newHeight,
                3, daysPerStage, suitableSeasons, seasonLength);

        LevelChunk chunk = level.getChunkAt(root);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        CropProgressEntry entry = cropData.get(root);
        // Conservative: only allow shifting backward (earlier), never forward.
        if (entry != null && plantedDay >= entry.plantedDay) return;
        // NOTE: entry==null is intentionally created here (unlike round5
        // onCropBonemeal's early-out): a world-gen / externally-placed stalk may
        // never have been tracked, and catch-up needs an entry to stay aligned.
        cropData.put(root, new CropProgressEntry(plantedDay));
        registerTrackedChunk(chunk);
        if (entry != null) {
            logDebug("Sugar cane bonemeal: root={} newHeight={} plantedDay={} (was {})",
                    root, newHeight, plantedDay, entry.plantedDay);
        } else {
            logDebug("Sugar cane bonemeal (new track): root={} newHeight={} plantedDay={}",
                    root, newHeight, plantedDay);
        }
    }

    // =======================================================================
    // Kelp Growth — deterministic height-based growth in water
    // =======================================================================

    /** Maximum height of a kelp stalk (vanilla GrowingPlantHeadBlock max age 25 → 26 blocks). */
    private static final int KELP_MAX_HEIGHT = 26;

    /**
     * Check if the given block is a kelp block (head or stem/plant).
     *
     * @param block the block to check
     * @return true if the block is a kelp head ({@link KelpBlock}) or stem ({@link KelpPlantBlock})
     */
    private static boolean isKelp(Block block) {
        return block instanceof KelpBlock || block instanceof KelpPlantBlock;
    }

    /**
     * Count the height of a kelp stalk at the given position.
     * Counts upward from the given position (which should be the root block)
     * and includes all consecutive kelp blocks.
     *
     * @param level the world level
     * @param pos   the root kelp block position
     * @return the stalk height (1-26), or 0 if the block is not kelp
     */
    private static int getKelpHeight(Level level, BlockPos pos) {
        if (!isKelp(level.getBlockState(pos).getBlock())) return 0;
        int height = 0;
        BlockPos checkPos = pos;
        while (isKelp(level.getBlockState(checkPos).getBlock()) && height < KELP_MAX_HEIGHT + 1) {
            height++;
            checkPos = checkPos.above();
        }
        return height;
    }

    /**
     * Grow a kelp stalk by placing a new head block on top.
     * Only grows if the target position is water — kelp never grows above the
     * surface (matching vanilla {@code GrowingPlantHeadBlock} behavior).
     *
     * <p>The current top head ({@link KelpBlock}) is converted into a stem
     * ({@link KelpPlantBlock}) and a fresh head is placed above it, matching
     * vanilla {@code getBodyBlock}/{@code getHeadBlock} semantics.</p>
     *
     * @param level         the world level
     * @param pos           the root kelp block position
     * @param currentHeight the current stalk height
     * @return true if kelp was grown (a new block was placed)
     */
    private static boolean growKelp(Level level, BlockPos pos, int currentHeight) {
        if (currentHeight >= KELP_MAX_HEIGHT) return false;
        BlockPos targetPos = pos.above(currentHeight);
        // Water precondition: kelp never grows above the water surface.
        if (!level.getFluidState(targetPos).is(Fluids.WATER)) return false;
        if (currentHeight > 0) {
            // Convert the current top head into a stem block.
            BlockPos oldHeadPos = pos.above(currentHeight - 1);
            level.setBlock(oldHeadPos, Blocks.KELP_PLANT.defaultBlockState(), 2);
        }
        level.setBlock(targetPos, Blocks.KELP.defaultBlockState(), 2);
        logDebug("Kelp grew: bottom={} new height={}", pos, currentHeight + 1);
        return true;
    }

    /**
     * Handle kelp harvest: a non-root kelp block at {@code harvestedPos} is being
     * removed (e.g. the player broke the second block of the stalk).
     *
     * <p>The blocks below {@code harvestedPos} survive and should continue
     * growing from their current height. The root block's
     * {@link CropProgressEntry#plantedDay} is therefore back-calculated to
     * preserve the progress already accumulated in the surviving blocks,
     * mirroring {@link #onSugarCaneHarvest}.</p>
     *
     * @param level        the world level
     * @param harvestedPos the kelp block being removed (non-root)
     */
    public static void onKelpHarvest(Level level, BlockPos harvestedPos) {
        // Walk down to the root (bottom) block, bounded to guard against
        // pathological over-tall stalks from other mods or data corruption.
        BlockPos root = harvestedPos;
        int depth = 0;
        while (isKelp(level.getBlockState(root.below()).getBlock()) && depth < 8) {
            root = root.below();
            depth++;
        }

        int currentDay = getSolarDays(level);
        // Anchor to the kelp config regardless of whether the root is currently
        // a head (KelpBlock) or stem (KelpPlantBlock).
        int daysPerStage = CropGrowthConfig.getDaysPerStage(
                BuiltInRegistries.BLOCK.getKey(Blocks.KELP));

        // Everything below the harvested block survives the harvest.
        int remainingHeight = Math.max(1, harvestedPos.getY() - root.getY());

        // Back-calculate plantedDay so the surviving height maps to the
        // progress already accumulated (1 block = 0 stages, 2 blocks = 1 stage).
        int plantedDay = currentDay - (remainingHeight - 1) * daysPerStage;
        // Defensive clamp: an invalid (negative) daysPerStage must never push
        // plantedDay into the future, which would freeze growth forever.
        if (plantedDay > currentDay) {
            plantedDay = currentDay;
        }

        LevelChunk chunk = level.getChunkAt(root);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        cropData.put(root, new CropProgressEntry(plantedDay));
        registerTrackedChunk(chunk);

        logDebug("Kelp harvested: harvested={} root={} remainingHeight={} plantedDay={}",
                harvestedPos, root, remainingHeight, plantedDay);
    }

    /**
     * Account for a kelp stalk being accelerated by bonemeal: one KelpBlock was
     * placed above the head (vanilla GrowingPlantHeadBlock.performBonemeal, no
     * head-to-stem conversion) while the root plantedDay stayed fixed. Back-shift
     * the root plantedDay by the accelerated stages so the calendar target height
     * stays aligned; only a backward (earlier) shift is ever applied.
     *
     * @param level   the world level
     * @param headPos the freshly placed head position (pos.above() of the old head)
     */
    public static void onKelpBonemeal(Level level, BlockPos headPos) {
        // Walk down to the root (bottom) block, bounded like onKelpHarvest.
        BlockPos root = headPos;
        int depth = 0;
        while (isKelp(level.getBlockState(root.below()).getBlock()) && depth < 8) {
            root = root.below();
            depth++;
        }

        int currentDay = getSolarDays(level);
        int daysPerStage = CropGrowthConfig.getDaysPerStage(
                BuiltInRegistries.BLOCK.getKey(Blocks.KELP));
        Set<Season> suitableSeasons = resolveSuitableSeasons(getSeason(level), Blocks.KELP);
        int seasonLength = getSeasonLength(level);
        int newHeight = headPos.getY() - root.getY() + 1;

        int plantedDay = heightCropPlantedDayAfterBonemeal(currentDay, newHeight,
                KELP_MAX_HEIGHT, daysPerStage, suitableSeasons, seasonLength);

        LevelChunk chunk = level.getChunkAt(root);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        CropProgressEntry entry = cropData.get(root);
        // Conservative: only allow shifting backward (earlier), never forward.
        if (entry != null && plantedDay >= entry.plantedDay) return;
        // NOTE: entry==null is intentionally created here (unlike round5
        // onCropBonemeal's early-out): a world-gen / externally-placed stalk may
        // never have been tracked, and catch-up needs an entry to stay aligned.
        cropData.put(root, new CropProgressEntry(plantedDay));
        registerTrackedChunk(chunk);
        if (entry != null) {
            logDebug("Kelp bonemeal: root={} newHeight={} plantedDay={} (was {})",
                    root, newHeight, plantedDay, entry.plantedDay);
        } else {
            logDebug("Kelp bonemeal (new track): root={} newHeight={} plantedDay={}",
                    root, newHeight, plantedDay);
        }
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
        return getOrCreate(pos, level, state, getSolarDays(level));
    }

    public static CropProgressEntry getOrCreate(BlockPos pos, Level level, BlockState state, int currentDay) {
        Block block = state.getBlock();
        if (!isGrowableCrop(block)) return null;

        // Sugar cane: walk down to the bottom block and track only the root.
        // Depth-bounded to guard against pathological over-tall stalks.
        if (block instanceof SugarCaneBlock) {
            int depth = 0;
            while (level.getBlockState(pos.below()).getBlock() instanceof SugarCaneBlock && depth < 8) {
                pos = pos.below();
                depth++;
            }
            state = level.getBlockState(pos);
            block = state.getBlock();
        }

        // Kelp: walk down to the root block and track only the root.
        // Same bottom-only rule as sugar cane.
        if (isKelp(block)) {
            int depth = 0;
            while (isKelp(level.getBlockState(pos.below()).getBlock()) && depth < 8) {
                pos = pos.below();
                depth++;
            }
            state = level.getBlockState(pos);
            block = state.getBlock();
        }

        // REGROW two-block plants: only the product-bearing UPPER half is tracked.
        // The LOWER half carries the same state properties (including has_seeds)
        // but is not the product — skip it so only one entry exists per plant.
        // Single-block REGROW crops (no DOUBLE_BLOCK_HALF property) are tracked
        // normally, so only plants exposing the half property are filtered here.
        if (CropKindResolver.regrowOf(block) != null
                && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
            return null;
        }

        // Segmented water rice (KC rice_crop): only the DOWN (root) segment is
        // tracked. MIDDLE/UP segments mirror the DOWN segment's age and must not
        // get independent entries — skip them (mirrors the REGROW upper-half rule).
        if (getRiceSegment(state) > 0) {
            return null;
        }

        // DOUBLE two-block crops (flax, pitcher_crop): only the LOWER half is
        // tracked. The UPPER half mirrors the LOWER age via placeDoubleUpperHalf
        // and must never get an independent entry — advancing it independently
        // corrupts the two-block structure (getCropStateForAge resets HALF to
        // LOWER, and a tracked UPPER reaching maxAge places a third UPPER block).
        CropGrowthConfig.CropOverride override =
                CropGrowthConfig.getOverride(BuiltInRegistries.BLOCK.getKey(block));
        if (isDoubleCropUpperHalf(state, override)) {
            return null;
        }

        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();

        CropProgressEntry existing = cropData.get(pos);
        if (existing != null) return existing;

        int solarDay = currentDay;
        int currentAge = getCropAge(state);
        // If the crop already has growth stages (e.g. world-gen farms),
        // back-calculate plantedDay so existing progress is preserved.
        if (currentAge > 0) {
            ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
            int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
            solarDay = backCalculatedPlantedDay(currentDay, currentAge, daysPerStage);
        } else if (isKelp(block)) {
            // HEIGHT crop: root-only tracking, getCropAge()==-1. If the stalk is
            // taller than 1 (world-gen / externally placed), back-calculate
            // plantedDay so the calendar target height aligns with actual height.
            int height = getKelpHeight(level, pos);
            if (height > 1) {
                int daysPerStage = CropGrowthConfig.getDaysPerStage(
                        BuiltInRegistries.BLOCK.getKey(Blocks.KELP));
                Set<Season> suitableSeasons = resolveSuitableSeasons(getSeason(level), Blocks.KELP);
                solarDay = heightCropPlantedDayAfterBonemeal(currentDay, height, KELP_MAX_HEIGHT,
                        daysPerStage, suitableSeasons, getSeasonLength(level));
            }
        } else if (block instanceof SugarCaneBlock) {
            int height = getSugarCaneHeight(level, pos);
            if (height > 1) {
                int daysPerStage = CropGrowthConfig.getDaysPerStage(
                        BuiltInRegistries.BLOCK.getKey(Blocks.SUGAR_CANE));
                Set<Season> suitableSeasons = resolveSuitableSeasons(getSeason(level), Blocks.SUGAR_CANE);
                solarDay = heightCropPlantedDayAfterBonemeal(currentDay, height, 3,
                        daysPerStage, suitableSeasons, getSeasonLength(level));
            }
        }
        CropProgressEntry entry = new CropProgressEntry(solarDay);
        cropData.put(pos, entry);
        registerTrackedChunk(chunk);

        logDebug("Created new crop entry at {} dim={} plantedDay={}",
                pos, level.dimension().location(), solarDay);
        return entry;
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
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        return chunkData.pastoralcraft$getCropData().containsKey(pos);
    }

    /**
     * Read the {@code plantedDay} of an existing tracked entry, falling back to
     * the current solar day when the position is not yet tracked.
     */
    private static int getPlantedDay(Level level, BlockPos pos) {
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        CropProgressEntry entry = chunkData.pastoralcraft$getCropData().get(pos);
        return entry != null ? entry.plantedDay : getSolarDays(level);
    }

    /**
     * Remove a crop position from tracking (e.g. when broken or harvested).
     * If the chunk's crop data map becomes empty after removal, the chunk is
     * unregistered from the tracked set to prevent wasted iteration during
     * periodic catch-up checks.
     */
    public static void removePosition(BlockPos pos, Level level) {
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        cropData.remove(pos);
        logDebug("Removed crop tracking at {} dim={}", pos, level.dimension().location());

        // If this was the last crop in the chunk, unregister from tracked set
        // to avoid wasted iteration in periodic catch-up checks.
        if (cropData.isEmpty()) {
            unregisterTrackedChunk(chunk);
        }
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
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        CropProgressEntry entry = cropData.get(pos);
        if (entry != null) {
            cropData.put(pos, new CropProgressEntry(getSolarDays(level)));
            logDebug("Reset plantedDay for stem at {} dim={}", pos, level.dimension().location());
            return;
        }
        // No entry exists (e.g. a world-gen sunflower harvested before ever being
        // tracked): create one now so the calendar can drive the regrowth cycle.
        getOrCreate(pos, level, level.getBlockState(pos));
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
        int effectiveAge = Math.max(0, Math.min(age, maxAge));
        int plantedDay = currentDay - effectiveAge * daysPerStage;
        return plantedDay > currentDay ? currentDay : plantedDay;
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
    private static int backCalculateStemPlantedDay(Level level, Block anchor, int currentDay,
                                                   int age, int maxAge, int daysPerStage) {
        int effectiveAge = Math.max(0, Math.min(age, maxAge));
        int neededSuitable = effectiveAge * daysPerStage;
        if (daysPerStage <= 0 || neededSuitable <= 0) return currentDay;

        Season currentSeason = getSeason(level);
        Set<Season> suitableSeasons = resolveSuitableSeasons(currentSeason, anchor);
        if (currentSeason == Season.NONE || suitableSeasons.contains(Season.NONE)
                || suitableSeasons.size() >= 4) {
            return stemPlantedDayAfterHarvest(currentDay, effectiveAge, maxAge, daysPerStage);
        }

        int termLength = Math.max(1, getTermLength(level));
        int plantedDay = currentDay;
        int suitableCount = 0;
        // One full year of slack: never more than 24 terms to walk back.
        int maxSteps = neededSuitable + termLength * 24;
        for (int steps = 0; steps < maxSteps && suitableCount < neededSuitable; steps++) {
            plantedDay--;
            if (isSeasonSuitable(seasonOfDay(plantedDay, termLength), suitableSeasons)) {
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
     * make {@link #simulateStem} treat the already-mature stem as freshly
     * planted, so it would need {@code maxAge * daysPerStage + daysPerFruit}
     * more days to fruit again — and during that window the immature-stem
     * unsuitable-season roll could mutate it to short grass. Instead this
     * back-calculates plantedDay from the reverted stem's age so the stem is
     * treated as "just matured" now and fruits again after {@code daysPerFruit}
     * suitable days.</p>
     *
     * @param level             the level
     * @param pos               the stem position
     * @param revertedStemState the reverted StemBlock state (age = MAX_AGE)
     */
    public static void onStemFruitHarvest(Level level, BlockPos pos, BlockState revertedStemState) {
        int currentDay = getSolarDays(level);
        int age = getCropAge(revertedStemState);
        int maxAge = getCropMaxAge(revertedStemState.getBlock());
        if (age < 0) {
            age = maxAge;
        }
        Block anchor = stemAnchor(revertedStemState.getBlock());
        int daysPerStage = CropGrowthConfig.getDaysPerStage(BuiltInRegistries.BLOCK.getKey(anchor));
        int plantedDay = backCalculateStemPlantedDay(level, anchor, currentDay, age, maxAge, daysPerStage);

        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        cropData.put(pos, new CropProgressEntry(plantedDay));
        registerTrackedChunk(chunk);
        logDebug("Stem fruit harvested: reset plantedDay for stem at {} to {} (age={})",
                pos, plantedDay, age);
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
        if (newAge <= oldAge) return;
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        CropProgressEntry entry = cropData.get(pos);
        if (entry == null) return;

        Block anchor = stemAnchor(level.getBlockState(pos).getBlock());
        int daysPerStage = CropGrowthConfig.getDaysPerStage(BuiltInRegistries.BLOCK.getKey(anchor));
        int shift = (newAge - oldAge) * daysPerStage;
        int currentDay = getSolarDays(level);
        int plantedDay = stemPlantedDayAfterBonemeal(entry.plantedDay, currentDay, oldAge, newAge, daysPerStage);
        cropData.put(pos, new CropProgressEntry(plantedDay));
        logDebug("Stem bonemeal: {} at {} shifted plantedDay back {} days ({} -> {})",
                BuiltInRegistries.BLOCK.getKey(anchor), pos, shift, entry.plantedDay, plantedDay);
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
        int shifted = plantedDay - (newAge - oldAge) * daysPerStage;
        return shifted > currentDay ? currentDay : shifted;
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
            onChunkLoadInternal(chunk, level);
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
    }

    private static void onChunkLoadInternal(LevelChunk chunk, Level level) {
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();

        if (cropData.isEmpty()) return;

        int currentDay = getSolarDays(level);
        Season currentSeason = getSeason(level);
        int seasonLength = getSeasonLength(level);

        int processed = 0;
        int grown = 0;
        int removed = 0;

        // Snapshot the entry set to avoid ConcurrentModificationException.
        // setBlock calls below trigger LevelMixin.onSetBlock, which may call
        // removePosition on the live map — iterating a snapshot keeps us safe.
        List<Map.Entry<BlockPos, CropProgressEntry>> entries =
                new ArrayList<>(cropData.entrySet());

        for (Map.Entry<BlockPos, CropProgressEntry> mapEntry : entries) {
            BlockPos pos = mapEntry.getKey();
            CropProgressEntry progress = mapEntry.getValue();
            processed++;

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
                    if (processStem(level, pos, state, progress, currentDay, currentSeason, seasonLength)) {
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
                if (processStem(level, pos, state, progress, currentDay, currentSeason, seasonLength)) {
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

                int currentHeight = getSugarCaneHeight(level, pos);
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
                GrowthSimulation sim = simulateGrowth(pos, progress.plantedDay, currentDay,
                        daysPerStage, sugarMaxAge, seasonLength, suitableSeasons, true);

                int targetHeight = Math.min(sim.stage() + 1, maxHeight);

                // Grow one block at a time up to target height
                while (currentHeight < targetHeight) {
                    if (growSugarCane(level, pos, currentHeight)) {
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
            if (isKelp(block)) {
                // Defensive: only the root block may be tracked. A non-root
                // entry (legacy/corrupt data) would grow upward from the wrong
                // position and exceed the 26-block height limit.
                if (isKelp(level.getBlockState(pos.below()).getBlock())) {
                    cropData.remove(pos);
                    removed++;
                    continue;
                }

                int currentHeight = getKelpHeight(level, pos);
                int kelpMaxAge = KELP_MAX_HEIGHT - 1; // 0-indexed: 25

                // Keep the entry when fully grown: kelp is re-harvestable and
                // onKelpHarvest resets plantedDay on harvest.
                if (currentHeight >= KELP_MAX_HEIGHT) {
                    continue;
                }

                // Anchor config/season to the kelp head regardless of whether the
                // root is currently a head (KelpBlock) or stem (KelpPlantBlock).
                ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(Blocks.KELP);
                int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
                Set<Season> suitableSeasons = resolveSuitableSeasons(currentSeason, Blocks.KELP);

                // Kelp is non-arable: it freezes in unsuitable seasons and never
                // mutates, so only suitable-season days count toward growth.
                GrowthSimulation sim = simulateGrowth(pos, progress.plantedDay, currentDay,
                        daysPerStage, kelpMaxAge, seasonLength, suitableSeasons, true);

                int targetHeight = Math.min(sim.stage() + 1, KELP_MAX_HEIGHT);

                // Grow one block at a time up to target height
                while (currentHeight < targetHeight) {
                    if (growKelp(level, pos, currentHeight)) {
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
                // Defensive: only the product-bearing UPPER half may be tracked.
                // A LOWER-half entry (legacy/corrupt data) has no product and
                // would never regrow — remove it.
                if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                    cropData.remove(pos);
                    removed++;
                    continue;
                }

                BooleanProperty product = CropKindResolver.regrowOf(block).productProperty();
                // Product already present — fully regrown; keep the entry (re-harvestable).
                if (state.getValue(product)) {
                    continue;
                }

                // Calendar-driven regrowth: only suitable-season days advance the
                // countdown. Non-arable (freeze, never mutate to short grass) so
                // unsuitable seasons halt regrowth.
                ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
                int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
                Set<Season> suitableSeasons = resolveSuitableSeasons(currentSeason, block);

                GrowthSimulation sim = simulateGrowth(pos, progress.plantedDay, currentDay,
                        daysPerStage, 1, seasonLength, suitableSeasons, true);

                if (sim.stage() >= 1) {
                    level.setBlock(pos, state.setValue(product, true), 2);
                    grown++;
                    logDebug("Catch-up (load): {} at {} regrew seeds (plantedDay={}, currentDay={})",
                            cropId, pos, progress.plantedDay, currentDay);
                }
                continue;
            }

            // --- Normal crops ---
            int currentAge = getCropAge(state);
            int maxAge = getCropMaxAge(block);

            // Segmented water rice: only the DOWN (root) segment is tracked. A
            // MIDDLE/UP entry (legacy/corrupt data) mirrors the DOWN age and
            // must be removed — it would otherwise advance independently.
            int riceSegment = getRiceSegment(state);
            if (riceSegment > 0) {
                cropData.remove(pos);
                removed++;
                continue;
            }
            IntegerProperty riceLocation = riceSegment == 0 ? segmentedLocationProperty(block) : null;

            ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
            CropGrowthConfig.CropOverride override = CropGrowthConfig.getOverride(cropId);

            // DOUBLE two-block crops (flax, pitcher_crop): an UPPER half entry is
            // legacy/corrupt data (pre-fix save) and must be removed — advancing
            // it independently corrupts the two-block structure. The LOWER half
            // entry refreshes the UPPER block via placeDoubleUpperHalf.
            if (isDoubleCropUpperHalf(state, override)) {
                cropData.remove(pos);
                removed++;
                continue;
            }

            if (currentAge >= maxAge) {
                // Segmented rice DOWN at max age still needs the upper segments
                // synced (e.g. after a bonemeal jump left them stale) before the
                // entry is dropped.
                if (riceLocation != null) {
                    syncRiceSegments(level, pos, maxAge, riceLocation);
                }
                // Climb crops (Farmers Delight tomatoes) stay tracked past maturity
                // so tryClimbVine below keeps driving rope climbing; fall through
                // (simulateGrowth no-ops at maxAge for non-arable crops).
                if (!isClimbCrop(block)) {
                    // Override-bearing crops may need a maturity side effect even
                    // when they arrive at maxAge without a tracker growth step
                    // (bonemeal jump, chunk-load). Run it so TRANSFORM (budding →
                    // tomatoes) and COMPANION (rice → panicles) fire instead of
                    // silently dropping the entry.
                    boolean hasSideEffect = override != null
                            && (override.transformBlock != null || override.topBlock != null || override.doubleAge >= 0);
                    if (hasSideEffect && applyMaturitySideEffects(level, pos, state, block, maxAge)) {
                        // TRANSFORM/COMPANION re-registered a growable crop at this
                        // position; skip the stale-state growth path below and let
                        // the next cycle process the fresh entry.
                        continue;
                    }
                    cropData.remove(pos);
                    removed++;
                    continue;
                }
            }

            int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
            Set<Season> suitableSeasons = resolveSuitableSeasons(currentSeason, block);
            boolean nonArable = isNonArableAt(level, pos, block);

            GrowthSimulation sim = simulateGrowth(pos, progress.plantedDay, currentDay,
                    daysPerStage, maxAge, seasonLength, suitableSeasons, nonArable);

            if (sim.mutated()) {
                mutateToShortGrass(level, pos, block, 2);
                cropData.remove(pos);
                removed++;
                logDebug("Catch-up (load): {} at {} mutated to short grass (plantedDay={}, currentDay={})",
                        cropId, pos, progress.plantedDay, currentDay);
                continue;
            }

            if (sim.stage() > currentAge) {
                int newAge = Math.min(sim.stage(), maxAge);
                // Segmented rice: preserve WATERLOGGED/LOCATION on the DOWN segment
                // (getStateForAge would reset both and de-waterlog the crop in water).
                BlockState newState = riceLocation != null
                        ? state.setValue(CropBlock.AGE, newAge)
                        : getCropStateForAge(state, newAge);
                level.setBlock(pos, newState, 2);

                grown++;
                logDebug("Catch-up (load): {} at {} advanced from age {} to {} (target={}, plantedDay={}, currentDay={})",
                        cropId, pos, currentAge, newAge, sim.stage(), progress.plantedDay, currentDay);

                // Segmented rice: sync MIDDLE/UP to the new age (the mod advances
                // the DOWN segment with UPDATE_CLIENTS, so updateShape never fires).
                if (riceLocation != null) {
                    syncRiceSegments(level, pos, newAge, riceLocation);
                }

                // Double-crop (e.g. flax): place/refresh the upper half as soon as
                // the age crosses doubleAge, not only at full maturity.
                placeDoubleUpperHalf(level, pos, newState, block, newAge);

                if (FlaxDiagnostics.enabled() && FlaxDiagnostics.isFlax(block)) {
                    FlaxDiagnostics.logDecision("chunk-load catch-up {}->{} pos={} target={} plantedDay={} currentDay={}",
                            currentAge, newAge, pos, sim.stage(), progress.plantedDay, currentDay);
                    FlaxDiagnostics.logSnapshot(level, pos, "chunk-load catch-up post-growth");
                }

                if (newAge >= maxAge) {
                    boolean keep = applyMaturitySideEffects(level, pos, newState, block, newAge);
                    // TRANSFORM to a new growable crop (e.g. tomato_budding →
                    // tomato_crop) keeps the entry: placeAndTrack already
                    // re-registered it with a fresh plantedDay.
                    if (!keep) {
                        cropData.remove(pos);
                        removed++;
                    }
                }
            }

            // Calendar-driven vine climbing (Farmers Delight tomato): grow one
            // rope segment per suitable day, every cycle, regardless of whether
            // the fruit age advanced this pass. Idempotent via countSuitableDays.
            //
            // Re-read the live entry: a TRANSFORM side effect (budding_tomatoes →
            // tomatoes) may have just replaced the tracked entry with a fresh
            // plantedDay (current day). Using the snapshot's stale plantedDay here
            // would make countSuitableDays count the whole budding phase as
            // suitable climb days and instantly place multiple rope segments
            // (e.g. 2) instead of the intended 1 segment per suitable day.
            CropProgressEntry climbEntry = cropData.get(pos);
            int climbPlantedDay = climbEntry != null ? climbEntry.plantedDay : progress.plantedDay;
            tryClimbVine(level, pos, climbPlantedDay, currentDay, suitableSeasons,
                    Math.max(1, seasonLength / CropGrowthConfig.SOLAR_TERMS_PER_SEASON));
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
            logDebug("Chunk load catch-up: processed={} grown={} removed={} in chunk {} dim={}",
                    processed, grown, removed, chunk.getPos(), level.dimension().location());
        }

        if (cropData.isEmpty()) {
            unregisterTrackedChunk(chunk);
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
            periodicCatchUpCheckInternal(chunk, level, currentDay, currentSeason, seasonLength);
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
    }

    private static void periodicCatchUpCheckInternal(LevelChunk chunk, Level level,
                                                       int currentDay, Season currentSeason,
                                                       int seasonLength) {
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();

        if (cropData.isEmpty()) return;
        int grown = 0;
        int removed = 0;

        // Snapshot the entry set to avoid ConcurrentModificationException.
        // setBlock calls below trigger LevelMixin.onSetBlock, which may call
        // removePosition on the live map — iterating a snapshot keeps us safe.
        List<Map.Entry<BlockPos, CropProgressEntry>> entries =
                new ArrayList<>(cropData.entrySet());

        for (Map.Entry<BlockPos, CropProgressEntry> mapEntry : entries) {
            BlockPos pos = mapEntry.getKey();
            CropProgressEntry progress = mapEntry.getValue();

            // Per-crop guard: a single problem crop (corrupt state, mod-mixed-in
            // properties, etc.) must be skipped and logged instead of crashing
            // the server tick loop.
            try {
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();
            if (!isGrowableCrop(block)) {
                // Attached stem: keep tracking so it can still mutate to short
                // grass in unsuitable seasons. processStem handles the roll.
                if (block instanceof AttachedStemBlock) {
                    if (processStem(level, pos, state, progress, currentDay, currentSeason, seasonLength)) {
                        cropData.remove(pos);
                        removed++;
                    }
                    continue;
                }
                cropData.remove(pos);
                removed++;
                continue;
            }

            // --- StemBlock: growth below MAX_AGE, then fruiting at MAX_AGE ---
            if (block instanceof StemBlock) {
                if (processStem(level, pos, state, progress, currentDay, currentSeason, seasonLength)) {
                    cropData.remove(pos);
                    removed++;
                }
                continue;
            }

            // --- SugarCane: height-based growth ---
            if (block instanceof SugarCaneBlock) {
                // Defensive: only the bottom (root) block may be tracked.
                if (level.getBlockState(pos.below()).getBlock() instanceof SugarCaneBlock) {
                    cropData.remove(pos);
                    removed++;
                    continue;
                }

                int currentHeight = getSugarCaneHeight(level, pos);
                int maxHeight = 3;
                int sugarMaxAge = maxHeight - 1;

                // Keep the entry when fully grown — re-harvestable crop.
                if (currentHeight >= maxHeight) {
                    continue;
                }

                ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
                int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
                Set<Season> suitableSeasons = resolveSuitableSeasons(currentSeason, block);

                // Sugar cane is non-arable: it freezes in unsuitable seasons and
                // never mutates, so only suitable-season days count toward growth.
                GrowthSimulation sim = simulateGrowth(pos, progress.plantedDay, currentDay,
                        daysPerStage, sugarMaxAge, seasonLength, suitableSeasons, true);

                int targetHeight = Math.min(sim.stage() + 1, maxHeight);

                while (currentHeight < targetHeight) {
                    if (growSugarCane(level, pos, currentHeight)) {
                        currentHeight++;
                        grown++;
                        logDebug("Periodic catch-up: sugar cane at {} grew to height {} (target={})",
                                pos, currentHeight, targetHeight);
                    } else {
                        break;
                    }
                }

                continue;
            }

            // --- Kelp: height-based growth in water ---
            if (isKelp(block)) {
                // Defensive: only the root block may be tracked.
                if (isKelp(level.getBlockState(pos.below()).getBlock())) {
                    cropData.remove(pos);
                    removed++;
                    continue;
                }

                int currentHeight = getKelpHeight(level, pos);
                int kelpMaxAge = KELP_MAX_HEIGHT - 1; // 0-indexed: 25

                // Keep the entry when fully grown — re-harvestable crop.
                if (currentHeight >= KELP_MAX_HEIGHT) {
                    continue;
                }

                // Anchor config/season to the kelp head regardless of whether the
                // root is currently a head (KelpBlock) or stem (KelpPlantBlock).
                ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(Blocks.KELP);
                int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
                Set<Season> suitableSeasons = resolveSuitableSeasons(currentSeason, Blocks.KELP);

                // Kelp is non-arable: it freezes in unsuitable seasons and never
                // mutates, so only suitable-season days count toward growth.
                GrowthSimulation sim = simulateGrowth(pos, progress.plantedDay, currentDay,
                        daysPerStage, kelpMaxAge, seasonLength, suitableSeasons, true);

                int targetHeight = Math.min(sim.stage() + 1, KELP_MAX_HEIGHT);

                while (currentHeight < targetHeight) {
                    if (growKelp(level, pos, currentHeight)) {
                        currentHeight++;
                        grown++;
                        logDebug("Periodic catch-up: kelp at {} grew to height {} (target={})",
                                pos, currentHeight, targetHeight);
                    } else {
                        break;
                    }
                }

                continue;
            }

            // --- REGROW: boolean-product calendar regrowth (e.g. sunflower has_seeds) ---
            if (CropKindResolver.regrowOf(block) != null) {
                // Defensive: only the product-bearing UPPER half may be tracked.
                // A LOWER-half entry (legacy/corrupt data) has no product and
                // would never regrow — remove it.
                if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                    cropData.remove(pos);
                    removed++;
                    continue;
                }

                BooleanProperty product = CropKindResolver.regrowOf(block).productProperty();
                // Product already present — fully regrown; keep the entry (re-harvestable).
                if (state.getValue(product)) {
                    continue;
                }

                // Calendar-driven regrowth: only suitable-season days advance the
                // countdown. Non-arable (freeze, never mutate to short grass) so
                // unsuitable seasons halt regrowth.
                ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
                int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
                Set<Season> suitableSeasons = resolveSuitableSeasons(currentSeason, block);

                GrowthSimulation sim = simulateGrowth(pos, progress.plantedDay, currentDay,
                        daysPerStage, 1, seasonLength, suitableSeasons, true);

                if (sim.stage() >= 1) {
                    level.setBlock(pos, state.setValue(product, true), 2);
                    grown++;
                    logDebug("Periodic catch-up: {} at {} regrew seeds (plantedDay={}, currentDay={})",
                            cropId, pos, progress.plantedDay, currentDay);
                }
                continue;
            }

            // --- Normal crops ---
            int currentAge = getCropAge(state);
            int maxAge = getCropMaxAge(block);

            // Segmented water rice: only the DOWN (root) segment is tracked. A
            // MIDDLE/UP entry (legacy/corrupt data) mirrors the DOWN age and
            // must be removed — it would otherwise advance independently.
            int riceSegment = getRiceSegment(state);
            if (riceSegment > 0) {
                cropData.remove(pos);
                removed++;
                continue;
            }
            IntegerProperty riceLocation = riceSegment == 0 ? segmentedLocationProperty(block) : null;

            ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
            CropGrowthConfig.CropOverride override = CropGrowthConfig.getOverride(cropId);

            // DOUBLE two-block crops (flax, pitcher_crop): an UPPER half entry is
            // legacy/corrupt data (pre-fix save) and must be removed — advancing
            // it independently corrupts the two-block structure. The LOWER half
            // entry refreshes the UPPER block via placeDoubleUpperHalf.
            if (isDoubleCropUpperHalf(state, override)) {
                cropData.remove(pos);
                removed++;
                continue;
            }

            if (currentAge >= maxAge) {
                // Segmented rice DOWN at max age still needs the upper segments
                // synced (e.g. after a bonemeal jump left them stale) before the
                // entry is dropped.
                if (riceLocation != null) {
                    syncRiceSegments(level, pos, maxAge, riceLocation);
                }
                // Climb crops (Farmers Delight tomatoes) stay tracked past maturity
                // so tryClimbVine below keeps driving rope climbing; fall through
                // (simulateGrowth no-ops at maxAge for non-arable crops).
                if (!isClimbCrop(block)) {
                    // Override-bearing crops may need a maturity side effect even
                    // when they arrive at maxAge without a tracker growth step
                    // (bonemeal jump, chunk-load). Run it so TRANSFORM (budding →
                    // tomatoes) and COMPANION (rice → panicles) fire instead of
                    // silently dropping the entry.
                    boolean hasSideEffect = override != null
                            && (override.transformBlock != null || override.topBlock != null || override.doubleAge >= 0);
                    if (hasSideEffect && applyMaturitySideEffects(level, pos, state, block, maxAge)) {
                        // TRANSFORM/COMPANION re-registered a growable crop at this
                        // position; skip the stale-state growth path below and let
                        // the next cycle process the fresh entry.
                        continue;
                    }
                    cropData.remove(pos);
                    removed++;
                    continue;
                }
            }

            int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
            Set<Season> suitableSeasons = resolveSuitableSeasons(currentSeason, block);
            boolean nonArable = isNonArableAt(level, pos, block);

            GrowthSimulation sim = simulateGrowth(pos, progress.plantedDay, currentDay,
                    daysPerStage, maxAge, seasonLength, suitableSeasons, nonArable);

            if (sim.mutated()) {
                mutateToShortGrass(level, pos, block, 2);
                cropData.remove(pos);
                removed++;
                logDebug("Periodic catch-up: {} at {} mutated to short grass (plantedDay={}, currentDay={})",
                        cropId, pos, progress.plantedDay, currentDay);
                continue;
            }

            if (sim.stage() > currentAge) {
                int newAge = Math.min(sim.stage(), maxAge);
                // Segmented rice: preserve WATERLOGGED/LOCATION on the DOWN segment
                // (getStateForAge would reset both and de-waterlog the crop in water).
                BlockState newState = riceLocation != null
                        ? state.setValue(CropBlock.AGE, newAge)
                        : getCropStateForAge(state, newAge);
                // Flag 2: sync to client but skip neighbor updates.
                level.setBlock(pos, newState, 2);

                grown++;
                logDebug("Periodic catch-up: {} at {} advanced from age {} to {} (target={}, plantedDay={}, currentDay={})",
                        cropId, pos, currentAge, newAge, sim.stage(), progress.plantedDay, currentDay);

                // Segmented rice: sync MIDDLE/UP to the new age (the mod advances
                // the DOWN segment with UPDATE_CLIENTS, so updateShape never fires).
                if (riceLocation != null) {
                    syncRiceSegments(level, pos, newAge, riceLocation);
                }

                // Double-crop (e.g. flax): place/refresh the upper half as soon as
                // the age crosses doubleAge, not only at full maturity.
                placeDoubleUpperHalf(level, pos, newState, block, newAge);

                if (FlaxDiagnostics.enabled() && FlaxDiagnostics.isFlax(block)) {
                    FlaxDiagnostics.logDecision("periodic catch-up {}->{} pos={} target={} plantedDay={} currentDay={}",
                            currentAge, newAge, pos, sim.stage(), progress.plantedDay, currentDay);
                    FlaxDiagnostics.logSnapshot(level, pos, "periodic catch-up post-growth");
                }

                if (newAge >= maxAge) {
                    boolean keep = applyMaturitySideEffects(level, pos, newState, block, newAge);
                    // TRANSFORM to a new growable crop (e.g. tomato_budding →
                    // tomato_crop) keeps the entry: placeAndTrack already
                    // re-registered it with a fresh plantedDay.
                    if (!keep) {
                        cropData.remove(pos);
                        removed++;
                    }
                }
            }

            // Calendar-driven vine climbing (Farmers Delight tomato): grow one
            // rope segment per suitable day, every cycle, regardless of whether
            // the fruit age advanced this pass. Idempotent via countSuitableDays.
            //
            // Re-read the live entry: a TRANSFORM side effect (budding_tomatoes →
            // tomatoes) may have just replaced the tracked entry with a fresh
            // plantedDay (current day). Using the snapshot's stale plantedDay here
            // would make countSuitableDays count the whole budding phase as
            // suitable climb days and instantly place multiple rope segments
            // (e.g. 2) instead of the intended 1 segment per suitable day.
            CropProgressEntry climbEntry = cropData.get(pos);
            int climbPlantedDay = climbEntry != null ? climbEntry.plantedDay : progress.plantedDay;
            tryClimbVine(level, pos, climbPlantedDay, currentDay, suitableSeasons,
                    Math.max(1, seasonLength / CropGrowthConfig.SOLAR_TERMS_PER_SEASON));
            } catch (Exception e) {
                // Defensive: skip this crop only, never crash the tick loop.
                ResourceLocation cropId;
                try {
                    cropId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
                } catch (Exception ignored) {
                    cropId = null;
                }
                PastoralCraft.LOGGER.warn("Periodic catch-up: error processing {} at {}: {}",
                        cropId, pos, e.toString());
            }
        }

        if (grown > 0) {
            logDebug("Periodic catch-up: grown={} removed={} in chunk {} dim={}",
                    grown, removed, chunk.getPos(), level.dimension().location());
        }

        if (cropData.isEmpty()) {
            unregisterTrackedChunk(chunk);
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
        return block instanceof CropBlock
                || block instanceof StemBlock
                || block instanceof NetherWartBlock
                || block instanceof CocoaBlock
                || block instanceof SweetBerryBushBlock
                || block instanceof SugarCaneBlock
                || block instanceof KelpBlock
                || block instanceof KelpPlantBlock
                || CropKindResolver.regrowOf(block) != null
                // Generic AGE recognition: any block exposing an "age" property via
                // CropKindResolver (BushBlock subclasses, Farmers Delight
                // BuddingTomatoBlock / RiceBlock, vanilla PitcherCropBlock, ...).
                || CropKindResolver.ageOf(block) != null;
    }

    /**
     * Check if a block is a REGROW crop (boolean product, e.g. the sunflower's
     * {@code has_seeds}). These are classified by {@link CropKindResolver} and
     * driven entirely by the calendar — regrowth is never triggered by random ticks.
     *
     * @param block the block to check
     * @return true if the block is a REGROW crop
     */
    public static boolean isRegrow(Block block) {
        return CropKindResolver.regrowOf(block) != null;
    }

    /**
     * Extract the current age from a recognized growable crop block state.
     * Returns -1 if the block is not a recognized growable crop.
     *
     * @param state the block state to read from
     * @return the current age (0-based), or -1 if not a recognized crop
     */
    public static int getCropAge(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) return crop.getAge(state);
        // AttachedStemBlock has no AGE property — defense in depth:
        // isGrowableCrop already excludes it, but a stray call could still reach here
        if (block instanceof AttachedStemBlock) return -1;
        if (block instanceof StemBlock) return state.getValue(StemBlock.AGE);
        if (block instanceof NetherWartBlock) return state.getValue(NetherWartBlock.AGE);
        if (block instanceof CocoaBlock) return state.getValue(CocoaBlock.AGE);
        if (block instanceof SweetBerryBushBlock) return state.getValue(SweetBerryBushBlock.AGE);
        if (block instanceof SugarCaneBlock) return -1; // Age is height-based, not property-based
        if (block instanceof KelpBlock || block instanceof KelpPlantBlock) return -1; // height-based
        // Generic AGE fallback: BuddingTomatoBlock / PitcherCropBlock / RiceBlock
        // (BushBlock-style) expose a plain "age" property.
        AgeCrop ageCrop = CropKindResolver.ageOf(block);
        if (ageCrop != null && state.hasProperty(ageCrop.ageProperty())) {
            return state.getValue(ageCrop.ageProperty());
        }
        return -1;
    }

    /**
     * Get the maximum age for a recognized growable crop block.
     * Returns -1 if the block is not a recognized growable crop.
     *
     * @param block the block to query
     * @return the maximum age, or -1 if not a recognized crop
     */
    public static int getCropMaxAge(Block block) {
        if (block instanceof CropBlock crop) return crop.getMaxAge();
        if (block instanceof StemBlock) return StemBlock.MAX_AGE;
        if (block instanceof NetherWartBlock) return NetherWartBlock.MAX_AGE;
        if (block instanceof CocoaBlock) return CocoaBlock.MAX_AGE;
        if (block instanceof SweetBerryBushBlock) return SweetBerryBushBlock.MAX_AGE;
        if (block instanceof SugarCaneBlock) return 2; // Max 3 blocks tall (0-indexed age = 2)
        if (block instanceof KelpBlock || block instanceof KelpPlantBlock) return KELP_MAX_HEIGHT - 1; // 25
        if (CropKindResolver.regrowOf(block) != null) return 1; // binary: product absent (false) = 0, present (true) = 1
        // Generic AGE fallback (see getCropAge).
        AgeCrop ageCrop = CropKindResolver.ageOf(block);
        if (ageCrop != null) return ageCrop.maxAge();
        return -1;
    }

    /**
     * Get the {@link BlockState} for a recognized growable crop at the given age.
     * The provided state is used as a base, preserving other properties (e.g.
     * {@code CocoaBlock}'s {@code FACING}) via {@code setValue}.
     * The incoming {@code age} is clamped to {@code [0, maxAge]} to prevent
     * {@link IllegalArgumentException} from out-of-bounds property values.
     *
     * <p><b>Defense:</b> {@link AttachedStemBlock} is not a {@link StemBlock}
     * and has no {@code AGE} property — it will fall through to {@code return null}.
     * Callers should guard with {@link #isGrowableCrop} before calling this method.</p>
     *
     * @param state the current block state to use as a base
     * @param age   the desired age (will be clamped to valid range)
     * @return the block state at the given age, or null if not a recognized crop
     */
    public static BlockState getCropStateForAge(BlockState state, int age) {
        Block block = state.getBlock();
        // Clamp age to valid range — prevents IllegalArgumentException from
        // setValue/set when catch-up logic produces an out-of-range age
        int maxAge = getCropMaxAge(block);
        if (maxAge < 0) return null; // not a recognized crop (includes AttachedStemBlock)
        int clampedAge = Math.clamp(age, 0, maxAge);
        if (block instanceof CropBlock crop) return crop.getStateForAge(clampedAge);
        if (block instanceof StemBlock) return state.setValue(StemBlock.AGE, clampedAge);
        if (block instanceof NetherWartBlock) return state.setValue(NetherWartBlock.AGE, clampedAge);
        if (block instanceof CocoaBlock) return state.setValue(CocoaBlock.AGE, clampedAge);
        if (block instanceof SweetBerryBushBlock) return state.setValue(SweetBerryBushBlock.AGE, clampedAge);
        // Sugar cane & kelp growth are handled via block placement, not state changes
        if (block instanceof SugarCaneBlock) return state;
        if (block instanceof KelpBlock || block instanceof KelpPlantBlock) return state;
        // Generic AGE fallback (see getCropAge). clampedAge was derived from
        // getCropMaxAge, which already reflects the AGE max, so it is in range.
        AgeCrop ageCrop = CropKindResolver.ageOf(block);
        if (ageCrop != null) return ageCrop.stateForAge(state, clampedAge);
        return null;
    }

    /**
     * Get the block state for a crop at the given age, preserving all current
     * state properties for segmented water rice.
     *
     * <p>{@link CropBlock#getStateForAge} builds a fresh default state, which for
     * segmented rice (KaleidoscopeCookery {@code rice_crop}) resets
     * {@code WATERLOGGED} to false and {@code LOCATION} to DOWN — de-waterlogging
     * the DOWN segment so it fails {@code canSurvive} and breaks. For segmented
     * rice this method instead sets only the {@code AGE} property on the current
     * state, preserving {@code WATERLOGGED}/{@code LOCATION}.</p>
     *
     * @param state the current block state to use as a base
     * @param age   the desired age
     * @return the block state at the given age
     */
    public static BlockState getCropStateForAgePreserving(BlockState state, int age) {
        if (isSegmentedRice(state.getBlock())) {
            return state.setValue(CropBlock.AGE, age);
        }
        return getCropStateForAge(state, age);
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
    enum MaturitySideEffect {
        /** TRANSFORM — replace the crop block itself with {@code transformBlock}. */
        TRANSFORM,
        /** COMPANION — place {@code topBlock} above (water required when {@code waterCompanion}). */
        COMPANION,
        /** DOUBLE — place the upper half (HALF=UPPER) once {@code newAge >= doubleAge}. */
        DOUBLE,
        /** BONEMEAL — experimental fallback: a single native {@code performBonemeal}
         *  attempt; for fully grown vanilla crops this usually no-ops because
         *  {@code isValidBonemealTarget} returns false. */
        BONEMEAL,
        /** NONE — no side effect configured or applicable. */
        NONE
    }

    /**
     * Pure decision logic for {@link #applyMaturitySideEffects}.
     *
     * @param override    the crop override, or {@code null}
     * @param newAge      the new (mature) age
     * @param canBonemeal whether a {@code performBonemeal} fallback is possible
     *                    (level is a {@link ServerLevel} and the block is a
     *                    {@link BonemealableBlock})
     * @return the applicable side-effect strategy
     */
    static MaturitySideEffect decideSideEffect(@Nullable CropGrowthConfig.CropOverride override,
                                               int newAge,
                                               boolean canBonemeal) {
        if (override == null) {
            return canBonemeal ? MaturitySideEffect.BONEMEAL : MaturitySideEffect.NONE;
        }
        if (override.transformBlock != null) {
            return MaturitySideEffect.TRANSFORM;
        }
        if (override.topBlock != null) {
            return MaturitySideEffect.COMPANION;
        }
        if (override.doubleAge >= 0) {
            return MaturitySideEffect.DOUBLE;
        }
        return canBonemeal ? MaturitySideEffect.BONEMEAL : MaturitySideEffect.NONE;
    }

    public static boolean applyMaturitySideEffects(Level level, BlockPos pos, BlockState matureState, Block block, int newAge) {
        // Stems use deterministic fruiting; never apply side effects to them
        if (block instanceof StemBlock) return false;

        ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
        CropGrowthConfig.CropOverride override = CropGrowthConfig.getOverride(cropId);
        boolean keepEntry = false;

        // Climb crops (Farmers Delight tomatoes) stay tracked past maturity so the
        // catch-up loops can keep driving tryClimbVine; they must never fall into the
        // BONEMEAL fallback (TomatoBlock.isValidBonemealTarget returns true for a mature
        // vine with rope above, which would trigger the native performBonemeal).
        boolean climbCrop = override != null && override.climbBlock != null && override.maxClimbHeight > 0;
        boolean canBonemeal = level instanceof ServerLevel && block instanceof BonemealableBlock && !climbCrop;
        switch (decideSideEffect(override, newAge, canBonemeal)) {
            case TRANSFORM -> {
                Block transform = BuiltInRegistries.BLOCK.get(override.transformBlock);
                if (transform != Blocks.AIR) {
                    placeAndTrack(level, pos, transform.defaultBlockState());
                    // A transform may produce a new growable crop at the same position
                    // (e.g. Farmers Delight budding_tomatoes → tomatoes). Keep the
                    // entry so the caller does not delete the freshly re-registered
                    // tracking for the transformed crop, and restart its plantedDay so
                    // the new phase (fruiting/climbing) follows a fresh calendar rhythm.
                    keepEntry = isGrowableCrop(transform);
                    if (keepEntry) {
                        resetPlantedDay(pos, level);
                    }
                    logDebug("Maturity side-effect: {} at {} transformed into {}", cropId, pos, override.transformBlock);
                }
            }
            case COMPANION -> {
                BlockPos above = pos.above();
                boolean aboveOk = override.waterCompanion
                        ? level.getFluidState(above).is(Fluids.WATER)
                        : level.getBlockState(above).isAir();
                if (aboveOk) {
                    Block companion = BuiltInRegistries.BLOCK.get(override.topBlock);
                    if (companion != Blocks.AIR) {
                        // Companion crops (rice_panicles) share the base crop's
                        // plantedDay so their calendar phases stay in sync —
                        // otherwise they restart from the current day on every
                        // placement and never catch up to the base crop's rhythm.
                        int basePlantedDay = getPlantedDay(level, pos);
                        placeAndTrack(level, above, companion.defaultBlockState(), basePlantedDay);
                        logDebug("Maturity side-effect: {} at {} placed companion {} above", cropId, pos, override.topBlock);
                    }
                }
                // Re-harvestable companion (e.g. Farmers Delight rice → rice_panicles):
                // keep the mature base crop tracked so the calendar re-places the
                // companion on the next cycle after it is harvested.
                keepEntry = true;
            }
            case DOUBLE -> placeDoubleUpperHalf(level, pos, matureState, block, newAge);
            case BONEMEAL -> {
                // Experimental fallback for crops without a configured override:
                // forward the mature state to the block's native performBonemeal.
                // In practice this usually no-ops for fully grown vanilla crops
                // (isValidBonemealTarget returns false), so treat it only as a
                // last-resort attempt rather than a guaranteed companion spawn.
                if (level instanceof ServerLevel serverLevel && block instanceof BonemealableBlock bonemealable) {
                    boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
                    if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
                    try {
                        // Fully grown crops report isValidBonemealTarget == false, so
                        // skip the native bonemeal path for them. The check itself is
                        // wrapped too: a corrupted/mixed-in age property must not
                        // crash the server tick loop (see torchflower crash report).
                        if (bonemealable.isValidBonemealTarget(serverLevel, pos, matureState)) {
                            bonemealable.performBonemeal(serverLevel, level.getRandom(), pos, matureState);
                        }
                    } catch (Exception e) {
                        // Defensive: a single crop's native bonemeal path must never
                        // crash the server tick loop. Log and move on.
                        PastoralCraft.LOGGER.warn("Maturity side-effect: bonemeal fallback failed for {} at {}: {}",
                                cropId, pos, e.toString());
                    } finally {
                        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
                    }
                    logDebug("Maturity side-effect: {} at {} attempted native performBonemeal fallback (may no-op for fully grown vanilla crops)", cropId, pos);
                }
            }
            case NONE -> { /* no side effect */ }
        }
        return keepEntry || climbCrop;
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
        boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
        try {
            level.setBlock(pos, state, 2);
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
        // Register the placed block for calendar tracking if it is a crop
        // (non-crops such as rice_panicles are ignored by getOrCreate).
        getOrCreate(pos, level, level.getBlockState(pos));
    }

    /**
     * Place a block state and register it for calendar tracking with an explicit
     * {@code plantedDay}. Companion crops (e.g. Farmers Delight rice_panicles)
     * share their base crop's plantedDay so their calendar phases stay in sync
     * instead of restarting from the current day on every placement.
     *
     * @param level      the level
     * @param pos        the position to place at
     * @param state      the block state to place
     * @param plantedDay the plantedDay to track (clamped to the current day)
     */
    public static void placeAndTrack(Level level, BlockPos pos, BlockState state, int plantedDay) {
        boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
        try {
            level.setBlock(pos, state, 2);
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
        Block placed = level.getBlockState(pos).getBlock();
        if (!isGrowableCrop(placed)) return;
        LevelChunk chunk = level.getChunkAt(pos);
        ChunkCropData chunkData = (ChunkCropData) chunk;
        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        int solarDay = clampPlantedDay(plantedDay, getSolarDays(level));
        cropData.put(pos, new CropProgressEntry(solarDay));
        registerTrackedChunk(chunk);
        logDebug("Tracked placed crop at {} dim={} plantedDay={}",
                pos, level.dimension().location(), solarDay);
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
        CropGrowthConfig.CropOverride override = CropGrowthConfig.getOverride(BuiltInRegistries.BLOCK.getKey(block));
        return override != null && override.climbBlock != null && override.maxClimbHeight > 0;
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
        if (termLength <= 0) return;
        Block block = level.getBlockState(pos).getBlock();
        CropGrowthConfig.CropOverride override = CropGrowthConfig.getOverride(BuiltInRegistries.BLOCK.getKey(block));
        if (override == null || override.climbBlock == null || override.maxClimbHeight <= 0) return;
        if (!isSameClimbFamily(block, override)) return;

        Block climb = BuiltInRegistries.BLOCK.get(override.climbBlock);
        Block support = override.climbSupport != null ? BuiltInRegistries.BLOCK.get(override.climbSupport) : Blocks.AIR;
        if (climb == Blocks.AIR || support == Blocks.AIR) return;

        // Find the base of the climb-family stack (lowest consecutive family block).
        BlockPos base = pos;
        while (isClimbFamilyBlock(level.getBlockState(base.below()).getBlock(), override)) {
            base = base.below();
        }
        // Count existing family segments above the base.
        int segments = 0;
        BlockPos probe = base.above();
        while (isClimbFamilyBlock(level.getBlockState(probe).getBlock(), override) && segments < override.maxClimbHeight) {
            segments++;
            probe = probe.above();
        }
        int suitableDays = countSuitableDays(plantedDay, currentDay, suitableSeasons, termLength);
        int desired = Math.min(suitableDays, override.maxClimbHeight);
        int toAdd = desired - segments;
        if (toAdd <= 0) return;

        BlockPos target = probe;
        for (int i = 0; i < toAdd; i++) {
            // FD native climbRopeAbove replaces the rope directly above the vine with
            // tomatoes_on_rope (no air gap), so the target block itself must be the
            // support (rope) and gets replaced in place.
            if (level.getBlockState(target).getBlock() != support) break;
            placeAndTrack(level, target, climb.defaultBlockState());
            target = target.above();
        }
    }

    /**
     * Whether {@code block} belongs to the same climb family as the override
     * (i.e. shares the same {@code climbBlock}). The base vine and any hanging
     * segments all carry the same climbBlock, so this identifies the whole stack.
     */
    private static boolean isSameClimbFamily(Block block, CropGrowthConfig.CropOverride override) {
        if (override == null || override.climbBlock == null) return false;
        CropGrowthConfig.CropOverride other = CropGrowthConfig.getOverride(BuiltInRegistries.BLOCK.getKey(block));
        return other != null && override.climbBlock.equals(other.climbBlock);
    }

    /**
     * Whether {@code block} is a climb-family block for the given override.
     */
    private static boolean isClimbFamilyBlock(Block block, CropGrowthConfig.CropOverride override) {
        return isSameClimbFamily(block, override);
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
        CropGrowthConfig.CropOverride override = CropGrowthConfig.getOverride(BuiltInRegistries.BLOCK.getKey(block));
        if (override == null || override.doubleAge < 0) return;
        if (newAge < override.doubleAge) return;

        BlockPos above = pos.above();
        BlockState aboveState = level.getBlockState(above);
        // Only place/refresh when the slot above is free or already this crop's UPPER half.
        if (!aboveState.isAir()
                && !(aboveState.getBlock() == block
                     && aboveState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                     && aboveState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)) {
            return;
        }

        BlockState upperState = getCropStateForAge(lowerState, newAge);
        if (upperState == null || !upperState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) return;
        upperState = upperState.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);

        // Idempotency: when the upper half is already in the target state, skip
        // both the setBlock and the log line. Without this, maxAge triggers a
        // direct placement AND the DOUBLE side effect on the same cycle, logging
        // two "placed upper half" lines and writing the block twice.
        if (!needsUpperHalfPlacement(aboveState, upperState)) return;

        boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
        try {
            level.setBlock(above, upperState, 2);
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
        logDebug("Maturity side-effect: {} at {} placed upper half (age={})", BuiltInRegistries.BLOCK.getKey(block), pos, newAge);
        if (FlaxDiagnostics.enabled() && FlaxDiagnostics.isFlax(block)) {
            FlaxDiagnostics.logDecision("placeDoubleUpperHalf pos={} upper={} age={}", pos, upperState, newAge);
        }
    }

    /**
     * Replace a mutated crop with short grass, cleaning up its upper half for
     * two-block crops (flax, pitcher_crop) so no floating upper block remains.
     * All internal setBlock calls are guarded by {@link InternalGrowthFlag} so
     * {@code LevelMixin} does not re-track the mutation.
     *
     * @param level the level
     * @param pos   the crop position (lower half)
     * @param block the crop block
     * @param flags block update flags (2 = UPDATE_CLIENTS, 3 = UPDATE_NEIGHBORS)
     */
    public static void mutateToShortGrass(Level level, BlockPos pos, Block block, int flags) {
        boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
        if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
        try {
            level.setBlock(pos, Blocks.SHORT_GRASS.defaultBlockState(), flags);
            // Two-block crops: clear the detached upper half before it floats.
            CropGrowthConfig.CropOverride override = CropGrowthConfig.getOverride(BuiltInRegistries.BLOCK.getKey(block));
            if (override != null && override.doubleAge >= 0) {
                BlockPos above = pos.above();
                BlockState aboveState = level.getBlockState(above);
                if (isUpperHalfOf(aboveState, block)) {
                    level.setBlock(above, Blocks.AIR.defaultBlockState(), flags);
                }
            }
        } finally {
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
        }
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

    private static void logDebug(String message, Object... args) {
        if (CropGrowthConfig.DEBUG_LOGGING.get()) {
            PastoralCraft.LOGGER.debug("[CropTracker] " + message, args);
        }
    }
}