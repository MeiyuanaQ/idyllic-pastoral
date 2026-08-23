package com.crispyraccoon.pastoralcraft.crop;

import java.util.Set;

import com.teamtea.eclipticseasons.api.constant.solar.Season;

import net.minecraft.core.BlockPos;

/**
 * Pure calendar-driven growth/stem simulation. All functions are pure — no
 * Level / BlockState reads or writes — so they can be unit-tested offline and
 * reused identically by the real-time, chunk-load and periodic catch-up paths.
 *
 * <p>Extracted from {@link CropGrowthTracker} (mechanical move, no logic change).
 */
public final class CropSimulation {

    private CropSimulation() {
        // Utility class — prevent instantiation.
    }

    /**
     * Hard fail-fast cap on the simulated elapsed window, independent of the
     * configurable {@code maxCatchUpElapsedDays} horizon. Guards direct callers
     * (and unit tests) that bypass the production clamp: even a corrupted or
     * extreme {@code plantedDay} can never make the loops below hang the server
     * thread. Well above the config range (1..3650) so it never interferes with
     * the configured horizon.
     */
    public static final int HARD_MAX_ELAPSED_DAYS = 100_000;

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
     * The result of simulating a melon/pumpkin stem's lifecycle between two
     * solar days.
     *
     * @param stage   the final growth stage reached (0 to {@code StemBlock.MAX_AGE})
     * @param mutated true if the stem mutated into short grass during an
     *                unsuitable-season fruiting cycle
     * @param fruited true if the stem produced a fruit (reached MAX_AGE and a
     *                full daysPerFruit window elapsed in a suitable season, or
     *                won the unsuitable-season fruit roll)
     */
    public record StemSimulation(int stage, boolean mutated, boolean fruited) {
    }

    /**
     * Clamp the simulation's {@code currentDay} so the elapsed window never
     * exceeds {@code maxElapsedDays}. Overflow-safe (uses a long horizon) and
     * package-private so unit tests can exercise it directly.
     *
     * @param plantedDay     the crop's planted solar day
     * @param currentDay     the current solar day
     * @param maxElapsedDays the maximum simulated elapsed window (0 = no clamp)
     * @return {@code min(currentDay, plantedDay + maxElapsedDays)}
     */
    static int clampSimDay(int plantedDay, int currentDay, int maxElapsedDays) {
        if (maxElapsedDays <= 0) return currentDay;
        long horizon = (long) plantedDay + maxElapsedDays;
        return currentDay <= horizon ? currentDay : (int) horizon;
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
     */
    public static GrowthSimulation simulateGrowth(BlockPos pos, int plantedDay, int currentDay,
                                                   int daysPerStage, int maxAge,
                                                   int seasonLength, Set<Season> suitableSeasons,
                                                   boolean nonArable) {
        return simulateGrowth(pos, plantedDay, currentDay, daysPerStage, maxAge,
                seasonLength, suitableSeasons, nonArable,
                CropGrowthConfig.UNSUITABLE_MUTATE_CHANCE.get(),
                CropGrowthConfig.UNSUITABLE_GROW_CHANCE.get());
    }

    /**
     * Production overload that accepts explicit per-crop roll chances (resolved by
     * {@link CropGrowthConfig#getUnsuitableMutateChance} /
     * {@link CropGrowthConfig#getUnsuitableGrowChance}). Delegates to the pure
     * long-key overload after clamping the simulation window.
     */
    public static GrowthSimulation simulateGrowth(BlockPos pos, int plantedDay, int currentDay,
                                                   int daysPerStage, int maxAge,
                                                   int seasonLength, Set<Season> suitableSeasons,
                                                   boolean nonArable,
                                                   double mutateChance, double growChance) {
        long t0 = DebugProfiler.startSection();
        // Clamp the simulated window so a large calendar jump can't make the
        // per-crop loop unboundedly long. The debug "elapsed" below still reports
        // the true (unclamped) gap for diagnostics.
        int simDay = clampSimDay(plantedDay, currentDay, CropGrowthConfig.MAX_CATCH_UP_ELAPSED_DAYS.get());
        GrowthSimulation sim = simulateGrowth(pos.asLong(), plantedDay, simDay, daysPerStage, maxAge,
                seasonLength, suitableSeasons, nonArable, mutateChance, growChance);
        if (t0 != 0L) {
            DebugProfiler.endSection(t0, "simulateGrowth", "pos=" + pos,
                    "elapsed=" + (currentDay - plantedDay), "stage=" + sim.stage(), "mutated=" + sim.mutated());
        }
        return sim;
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

        // Fail-fast backstop: bound the elapsed window so the attempt loop below
        // can never hang, even when a caller bypasses the config clamp.
        currentDay = clampSimDay(plantedDay, currentDay, HARD_MAX_ELAPSED_DAYS);

        // Clamp the roll chances so the three-way outcome stays well-formed:
        // mut and grow are clamped so their sum is ≤ 1.0, leaving the
        // remaining probability as the "no growth" branch. growChance is
        // clamped (not mutateChance) so a configured mutate setting keeps
        // its exact value.
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
            int suitableDays = CropCalendar.countSuitableDays(plantedDay, currentDay, suitableSeasons, termLength);
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
            Season season = CropCalendar.seasonOfDay(endDay, termLength);
            if (CropCalendar.isSeasonSuitable(season, suitableSeasons)) {
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
     * Simulate a stem's (melon/pumpkin) lifecycle from {@code plantedDay} to
     * {@code currentDay}, reading the unsuitable-season roll chances from config.
     *
     * <p>See the long-key overload for the full semantics. This overload is the
     * production entry point used by the stem strategy.</p>
     */
    public static StemSimulation simulateStem(BlockPos pos, int plantedDay, int currentDay,
                                              int daysPerStage, int daysPerFruit, int maxAge,
                                              int seasonLength, Set<Season> suitableSeasons) {
        long t0 = DebugProfiler.startSection();
        // Clamp the simulated window (same rationale as simulateGrowth).
        int simDay = clampSimDay(plantedDay, currentDay, CropGrowthConfig.MAX_CATCH_UP_ELAPSED_DAYS.get());
        StemSimulation sim = simulateStem(pos.asLong(), plantedDay, simDay, daysPerStage, daysPerFruit, maxAge,
                seasonLength, suitableSeasons,
                CropGrowthConfig.STEM_UNSUITABLE_MUTATE_CHANCE.get(),
                CropGrowthConfig.STEM_UNSUITABLE_FRUIT_CHANCE.get(),
                CropGrowthConfig.STEM_UNSUITABLE_GROW_CHANCE.get());
        if (t0 != 0L) {
            DebugProfiler.endSection(t0, "simulateStem", "pos=" + pos,
                    "elapsed=" + (currentDay - plantedDay), "stage=" + sim.stage(),
                    "fruited=" + sim.fruited(), "mutated=" + sim.mutated());
        }
        return sim;
    }

    /**
     * Pure-function overload (see the 10-arg overload) with the immature-stem grow
     * chance fixed at 0.0, preserving the classic stem behavior for callers and
     * unit tests that only pass the mutate/fruit chances.
     */
    public static StemSimulation simulateStem(long posKey, int plantedDay, int currentDay,
                                              int daysPerStage, int daysPerFruit, int maxAge,
                                              int seasonLength, Set<Season> suitableSeasons,
                                              double mutateChance, double fruitChance) {
        return simulateStem(posKey, plantedDay, currentDay, daysPerStage, daysPerFruit, maxAge,
                seasonLength, suitableSeasons, mutateChance, fruitChance, 0.0);
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
     *       unsuitable days a deterministic roll decides the outcome: mutate to short
     *       grass ({@code mutateChance}); for a MATURE stem that has not yet fruited,
     *       fruit anyway ({@code fruitChance}); for an IMMATURE stem, grow one stage
     *       ({@code growChance}); otherwise no change. The per-day accumulation means
     *       the very first unsuitable day never rolls, so a stem freshly entering an
     *       unsuitable season is not instantly destroyed.</li>
     * </ul>
     *
     * <p>Growth/fruit/unsuitable counters each accumulate independently across
     * season boundaries, so time-skip catch-up and real-time growth agree.</p>
     */
    public static StemSimulation simulateStem(long posKey, int plantedDay, int currentDay,
                                              int daysPerStage, int daysPerFruit, int maxAge,
                                              int seasonLength, Set<Season> suitableSeasons,
                                              double mutateChance, double fruitChance,
                                              double growChance) {
        if (currentDay <= plantedDay) return new StemSimulation(0, false, false);
        if (daysPerStage <= 0 || daysPerFruit <= 0 || maxAge <= 0) return new StemSimulation(0, false, false);

        // Fail-fast backstop: bound the elapsed window so the segment-scan loop
        // below can never hang, even when a caller bypasses the config clamp.
        currentDay = clampSimDay(plantedDay, currentDay, HARD_MAX_ELAPSED_DAYS);

        // Clamp the roll chances so the mature (mutate + fruit) and immature
        // (mutate + grow) rolls each stay well-formed (sum <= 1.0).
        double mut = Math.max(0.0, Math.min(1.0, mutateChance));
        double fruit = Math.max(0.0, Math.min(1.0, fruitChance));
        double grow = Math.max(0.0, Math.min(1.0, growChance));
        if (mut + fruit > 1.0) {
            fruit = Math.max(0.0, 1.0 - mut);
        }
        if (mut + grow > 1.0) {
            grow = Math.max(0.0, 1.0 - mut);
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

        // Segment scan: instead of iterating day-by-day (O(elapsed)), walk
        // [plantedDay+1, currentDay] in same-season segments and aggregate each
        // segment with integer division. Strictly equivalent to the day-by-day
        // loop — growth/fruit/unsuitable counters accumulate continuously across
        // season boundaries, and unsuitable rolls are order-dependent (attempt is
        // a local, calendar-bound counter), so rolls still execute in segment order.
        int day = plantedDay + 1;
        while (day <= currentDay) {
            int term = Math.floorDiv(day, termLength);
            int termInYear = Math.floorMod(term, 24);
            int termsToSeasonEnd = 6 - (termInYear % 6);
            long seasonEndDay = ((long) term + termsToSeasonEnd) * termLength;
            int segEnd = (int) Math.min(seasonEndDay, (long) currentDay + 1);
            int segLen = segEnd - day;
            Season season = CropCalendar.seasonOfDay(day, termLength);

            if (CropCalendar.isSeasonSuitable(season, suitableSeasons)) {
                // Suitable segment: aggregate growth (and, past maturity, fruiting).
                // A single segment may cross the maturity point, so split it into
                // "still growing" days and "mature" days.
                int fruitLen = 0;
                if (stage < maxAge) {
                    long growDays = (long) stage * daysPerStage + growAccum;
                    long need = (long) maxAge * daysPerStage - growDays;
                    if (segLen <= need) {
                        growDays += segLen;
                    } else {
                        growDays = (long) maxAge * daysPerStage;
                        fruitLen = segLen - (int) need;
                    }
                    stage = (int) Math.min(growDays / daysPerStage, maxAge);
                    growAccum = (stage >= maxAge) ? 0 : (int) (growDays % daysPerStage);
                } else {
                    fruitLen = segLen;
                }
                if (!fruited && fruitLen > 0) {
                    fruitAccum += fruitLen;
                    if (fruitAccum >= daysPerFruit) {
                        fruited = true;
                    }
                }
            } else {
                // Unsuitable segment: exactly floor((unsuitableAccum+segLen)/daysPerFruit)
                // rolls, each order-dependent on the stage reached so far.
                unsuitableAccum += segLen;
                int rolls = unsuitableAccum / daysPerFruit;
                unsuitableAccum %= daysPerFruit;
                for (int k = 0; k < rolls; k++) {
                    double roll = attemptHash(posKey, plantedDay, attempt);
                    attempt++;
                    if (roll < mut) {
                        return new StemSimulation(stage, true, fruited);
                    }
                    if (stage >= maxAge) {
                        if (!fruited && roll < mut + fruit) {
                            fruited = true;
                        }
                    } else if (roll < mut + grow) {
                        stage++;
                        growAccum = 0;
                    }
                }
            }
            day = segEnd;
        }
        return new StemSimulation(stage, false, fruited);
    }

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
}
