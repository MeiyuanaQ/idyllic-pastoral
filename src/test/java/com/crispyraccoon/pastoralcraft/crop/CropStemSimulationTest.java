package com.crispyraccoon.pastoralcraft.crop;

import com.teamtea.eclipticseasons.api.constant.solar.Season;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Oracle-based equivalence tests for the segment-scan rewrite of
 * {@link CropGrowthTracker#simulateStem(long, int, int, int, int, int, int, Set, double, double)}.
 *
 * <p>The production implementation now walks {@code [plantedDay+1, currentDay]} in
 * same-season segments (O(elapsed/termLength) segments) instead of day-by-day. This
 * test keeps an independent <b>day-by-day reference implementation</b> (the oracle),
 * including its own copy of the deterministic hash, and asserts the two produce the
 * bit-identical {@code (stage, mutated, fruited)} triple across randomized
 * combinations and hand-derived boundary cases.</p>
 */
class CropStemSimulationTest {

    /** One season = 6 solar terms (matches {@code CropGrowthConfig.SOLAR_TERMS_PER_SEASON}). */
    private static final int SOLAR_TERMS_PER_SEASON = 6;

    private static final Set<Season> SPRING_ONLY = EnumSet.of(Season.SPRING);

    private static final List<Set<Season>> NON_YEAR_ROUND_SETS = List.of(
            EnumSet.of(Season.SPRING),
            EnumSet.of(Season.SPRING, Season.SUMMER),
            EnumSet.of(Season.SPRING, Season.AUTUMN),
            EnumSet.of(Season.SPRING, Season.SUMMER, Season.AUTUMN));

    // ---------------------------------------------------------------------
    // Oracle: the original day-by-day reference implementation.
    // ---------------------------------------------------------------------

    private static CropSimulation.StemSimulation oracle(
            long posKey, int plantedDay, int currentDay, int daysPerStage, int daysPerFruit,
            int maxAge, int seasonLength, Set<Season> suitableSeasons,
            double mutateChance, double fruitChance) {
        if (currentDay <= plantedDay) return new CropSimulation.StemSimulation(0, false, false);
        if (daysPerStage <= 0 || daysPerFruit <= 0 || maxAge <= 0) {
            return new CropSimulation.StemSimulation(0, false, false);
        }

        double mut = Math.max(0.0, Math.min(1.0, mutateChance));
        double fruit = Math.max(0.0, Math.min(1.0, fruitChance));
        if (mut + fruit > 1.0) {
            fruit = Math.max(0.0, 1.0 - mut);
        }

        if (suitableSeasons.contains(Season.NONE) || suitableSeasons.size() >= 4) {
            int elapsed = currentDay - plantedDay;
            int stage = Math.min(elapsed / daysPerStage, maxAge);
            boolean fruited = stage >= maxAge && (elapsed - maxAge * daysPerStage) >= daysPerFruit;
            return new CropSimulation.StemSimulation(stage, false, fruited);
        }

        int termLength = Math.max(1, seasonLength / SOLAR_TERMS_PER_SEASON);

        int stage = 0;
        boolean fruited = false;
        int growAccum = 0;
        int fruitAccum = 0;
        int unsuitableAccum = 0;
        int attempt = 0;

        for (int day = plantedDay + 1; day <= currentDay; day++) {
            Season season = CropGrowthTracker.seasonOfDay(day, termLength);
            if (CropGrowthTracker.isSeasonSuitable(season, suitableSeasons)) {
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
            } else {
                unsuitableAccum++;
                if (unsuitableAccum >= daysPerFruit) {
                    unsuitableAccum = 0;
                    double roll = attemptHash(posKey, plantedDay, attempt);
                    attempt++;
                    if (roll < mut) {
                        return new CropSimulation.StemSimulation(stage, true, fruited);
                    }
                    if (stage >= maxAge && !fruited && roll < mut + fruit) {
                        fruited = true;
                    }
                }
            }
        }
        return new CropSimulation.StemSimulation(stage, false, fruited);
    }

    /** Independent copy of the production deterministic hash (kept separate on purpose). */
    private static double attemptHash(long posKey, int plantedDay, int attemptIndex) {
        long seed = posKey
                ^ (Integer.toUnsignedLong(plantedDay) * 0x9E3779B97F4A7C15L)
                ^ hashLong((long) attemptIndex + 0x517CC1B727220A95L);
        return (hashLong(seed) & Long.MAX_VALUE) / (double) Long.MAX_VALUE;
    }

    private static long hashLong(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }

    // ---------------------------------------------------------------------
    // Randomized equivalence.
    // ---------------------------------------------------------------------

    @Test
    void segmentedMatchesDailyOracleOnRandomCombinations() {
        Random rng = new Random(20260821L);
        int[] termLengths = {3, 7, 14};
        double[] chances = {0.0, 0.1, 0.2, 0.5, 1.0};

        for (int i = 0; i < 600; i++) {
            int termLength = termLengths[rng.nextInt(termLengths.length)];
            int seasonLength = termLength * SOLAR_TERMS_PER_SEASON;
            Set<Season> suitable = NON_YEAR_ROUND_SETS.get(rng.nextInt(NON_YEAR_ROUND_SETS.size()));
            int plantedDay = rng.nextInt(60);
            int currentDay = plantedDay + 1 + rng.nextInt(2000);
            int daysPerStage = 1 + rng.nextInt(5);
            int daysPerFruit = 1 + rng.nextInt(5);
            int maxAge = 3 + rng.nextInt(5);
            double mut = chances[rng.nextInt(chances.length)];
            double fruit = chances[rng.nextInt(chances.length)];
            long posKey = rng.nextLong();

            CropSimulation.StemSimulation expected = oracle(
                    posKey, plantedDay, currentDay, daysPerStage, daysPerFruit,
                    maxAge, seasonLength, suitable, mut, fruit);
            CropSimulation.StemSimulation actual = CropGrowthTracker.simulateStem(
                    posKey, plantedDay, currentDay, daysPerStage, daysPerFruit,
                    maxAge, seasonLength, suitable, mut, fruit);

            assertEquals(expected, actual,
                    "mismatch at i=" + i
                            + " termLength=" + termLength
                            + " plantedDay=" + plantedDay
                            + " currentDay=" + currentDay
                            + " daysPerStage=" + daysPerStage
                            + " daysPerFruit=" + daysPerFruit
                            + " maxAge=" + maxAge
                            + " mut=" + mut + " fruit=" + fruit
                            + " suitable=" + suitable);
        }
    }

    // ---------------------------------------------------------------------
    // Hand-derived boundary cases.
    // ---------------------------------------------------------------------

    @Test
    void currentDayNotAfterPlantedDay_returnsZeroState() {
        assertEquals(new CropSimulation.StemSimulation(0, false, false),
                CropGrowthTracker.simulateStem(1L, 10, 10, 3, 3, 7, 42, SPRING_ONLY, 1.0, 1.0));
    }

    @Test
    void exactMaturityBoundary_doesNotFruit() {
        // days 1..21 are all spring (termLength 7): 21 suitable days → stage 7
        // exactly at maxAge, with no mature-day left to accumulate fruiting.
        assertEquals(new CropSimulation.StemSimulation(7, false, false),
                CropGrowthTracker.simulateStem(1L, 0, 21, 3, 3, 7, 42, SPRING_ONLY, 1.0, 1.0));
    }

    @Test
    void allUnsuitableMutateOne_mutatesOnFirstFullWindow() {
        // Planted day 39: days 40-41 spring (suitable), days 42-44 summer
        // (unsuitable). With mut=1.0 the first full 3-day unsuitable window
        // (day 44) mutates immediately, before any growth.
        assertEquals(new CropSimulation.StemSimulation(0, true, false),
                CropGrowthTracker.simulateStem(1L, 39, 44, 3, 3, 7, 42, SPRING_ONLY, 1.0, 0.0));
    }

    @Test
    void matureUnsuitableFruitRoll_fruitsWithoutMutating() {
        // Planted day 20: days 21..41 spring mature the stem exactly at day 41
        // (stage 7, not yet fruited). Days 42-44 are unsuitable; with mut=0.0
        // and fruit=1.0 the mature stem fruits on its first full unsuitable
        // window rather than mutating.
        assertEquals(new CropSimulation.StemSimulation(7, false, true),
                CropGrowthTracker.simulateStem(1L, 20, 44, 3, 3, 7, 42, SPRING_ONLY, 0.0, 1.0));
    }
}
