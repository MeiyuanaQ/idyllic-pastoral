package com.crispyraccoon.pastoralcraft.crop;

import com.teamtea.eclipticseasons.api.constant.solar.Season;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure-function crop growth model.
 * These tests do not require a Minecraft server or the NeoForge config system,
 * because {@link CropGrowthTracker#simulateGrowth} and
 * {@link CropGrowthTracker#seasonOfDay} are pure functions of their arguments.
 *
 * <p>The unsuitable-season three-way outcome (20% mutate / 40% grow / 40% no growth)
 * is deterministic per position key + plantedDay + attempt index, so tests assert
 * determinism and bounds rather than exact hash-derived roll values.</p>
 */
class CropGrowthTrackerTest {

    /** One season = 6 solar terms × 7 days. */
    private static final int SEASON_LENGTH = 42;

    /** The default three-way roll chances used by the unsuitable-season logic. */
    private static final double MUTATE_CHANCE = 0.20;
    private static final double GROW_CHANCE = 0.40;

    private static final Set<Season> ALL_SEASONS =
            EnumSet.of(Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.WINTER);
    private static final Set<Season> SPRING_ONLY = EnumSet.of(Season.SPRING);

    private static final long POS_KEY = 1L;

    @Test
    void orderedSeasonsUseCalendarOrder() {
        Season[] ordered = SeasonTagResolver.ORDERED_SEASONS;
        assertEquals(4, ordered.length);
        assertEquals(Season.SPRING, ordered[0]);
        assertEquals(Season.SUMMER, ordered[1]);
        assertEquals(Season.AUTUMN, ordered[2]);
        assertEquals(Season.WINTER, ordered[3]);
    }

    @Test
    void seasonOfDayMapsToCalendarOrder() {
        assertEquals(Season.SPRING, CropGrowthTracker.seasonOfDay(0, 7));
        assertEquals(Season.SUMMER, CropGrowthTracker.seasonOfDay(42, 7));
        assertEquals(Season.AUTUMN, CropGrowthTracker.seasonOfDay(84, 7));
        assertEquals(Season.WINTER, CropGrowthTracker.seasonOfDay(126, 7));
    }

    @Test
    void simulateGrowth_allSeasonsSuitable_simpleDivision() {
        CropSimulation.GrowthSimulation sim = CropGrowthTracker.simulateGrowth(
                POS_KEY, 0, 9, 3, 7, SEASON_LENGTH, ALL_SEASONS, false, MUTATE_CHANCE, GROW_CHANCE);
        assertEquals(3, sim.stage());
        assertFalse(sim.mutated());
    }

    @Test
    void simulateGrowth_springOnly_suitableDaysCounted() {
        // Days 0..8 all fall within spring (suitable): 9 / 3 = 3 stages.
        CropSimulation.GrowthSimulation sim = CropGrowthTracker.simulateGrowth(
                POS_KEY, 0, 9, 3, 7, SEASON_LENGTH, SPRING_ONLY, false, MUTATE_CHANCE, GROW_CHANCE);
        assertEquals(3, sim.stage());
        assertFalse(sim.mutated());
    }

    @Test
    void simulateGrowth_nonArable_freezesInUnsuitableSeason() {
        // Spring (days 0..41) is suitable = 42 days; summer (42..49) is unsuitable.
        // With daysPerStage=5, suitable days yield 42 / 5 = 8 stages, and the
        // 8 unsuitable days must contribute nothing (frozen), and never mutate.
        CropSimulation.GrowthSimulation sim = CropGrowthTracker.simulateGrowth(
                POS_KEY, 0, 50, 5, 100, SEASON_LENGTH, SPRING_ONLY, true, MUTATE_CHANCE, GROW_CHANCE);
        assertEquals(8, sim.stage());
        assertFalse(sim.mutated());
    }

    @Test
    void simulateGrowth_arable_deterministicAcrossCalls() {
        // For arable crops in unsuitable seasons the outcome depends on a
        // deterministic hash; identical inputs must always yield the same result.
        CropSimulation.GrowthSimulation first = CropGrowthTracker.simulateGrowth(
                POS_KEY, 0, 50, 5, 100, SEASON_LENGTH, SPRING_ONLY, false, MUTATE_CHANCE, GROW_CHANCE);
        CropSimulation.GrowthSimulation second = CropGrowthTracker.simulateGrowth(
                POS_KEY, 0, 50, 5, 100, SEASON_LENGTH, SPRING_ONLY, false, MUTATE_CHANCE, GROW_CHANCE);
        assertEquals(first.stage(), second.stage());
        assertEquals(first.mutated(), second.mutated());
    }

    @Test
    void simulateGrowth_arable_unsuitableRollStaysWithinBounds() {
        // Arable crops crossing into unsuitable seasons must always terminate
        // with a valid stage, be deterministic, and produce only legal outcomes.
        for (long posKey = 0; posKey < 50; posKey++) {
            CropSimulation.GrowthSimulation sim = CropGrowthTracker.simulateGrowth(
                    posKey, 0, 130, 5, 100, SEASON_LENGTH, SPRING_ONLY, false, MUTATE_CHANCE, GROW_CHANCE);
            assertTrue(sim.stage() >= 0 && sim.stage() <= 100,
                    "stage out of bounds: " + sim.stage());

            CropSimulation.GrowthSimulation again = CropGrowthTracker.simulateGrowth(
                    posKey, 0, 130, 5, 100, SEASON_LENGTH, SPRING_ONLY, false, MUTATE_CHANCE, GROW_CHANCE);
            assertEquals(sim.stage(), again.stage());
            assertEquals(sim.mutated(), again.mutated());
        }
    }

    @Test
    void simulateGrowth_timeRollback_returnsZero() {
        CropSimulation.GrowthSimulation sim = CropGrowthTracker.simulateGrowth(
                POS_KEY, 50, 40, 5, 100, SEASON_LENGTH, SPRING_ONLY, false, MUTATE_CHANCE, GROW_CHANCE);
        assertEquals(0, sim.stage());
        assertFalse(sim.mutated());
    }

    @Test
    void simulateGrowth_chanceSumOverOne_growClamped() {
        // Invalid combo mutate=0.8 + grow=0.8 = 1.6 > 1.0 must be clamped so grow
        // becomes 1.0 - 0.8 = 0.2, keeping the "no growth" branch reachable and the
        // result deterministic (identical to passing the clamped values directly).
        CropSimulation.GrowthSimulation clamped = CropGrowthTracker.simulateGrowth(
                POS_KEY, 0, 130, 5, 100, SEASON_LENGTH, SPRING_ONLY, false, 0.8, 0.8);
        CropSimulation.GrowthSimulation expected = CropGrowthTracker.simulateGrowth(
                POS_KEY, 0, 130, 5, 100, SEASON_LENGTH, SPRING_ONLY, false, 0.8, 0.2);
        assertEquals(expected.stage(), clamped.stage());
        assertEquals(expected.mutated(), clamped.mutated());
    }

    @Test
    void simulateGrowth_chanceBoundaryOne_legalAndDeterministic() {
        // Boundary mutate=1.0 + grow=1.0 clamps grow to 0.0, matching the legal
        // mutate=1.0 + grow=0.0 combination; both must stay stage-bounded.
        CropSimulation.GrowthSimulation clamped = CropGrowthTracker.simulateGrowth(
                POS_KEY, 0, 130, 5, 100, SEASON_LENGTH, SPRING_ONLY, false, 1.0, 1.0);
        CropSimulation.GrowthSimulation legal = CropGrowthTracker.simulateGrowth(
                POS_KEY, 0, 130, 5, 100, SEASON_LENGTH, SPRING_ONLY, false, 1.0, 0.0);
        assertTrue(clamped.stage() >= 0 && clamped.stage() <= 100, "stage out of bounds: " + clamped.stage());
        assertEquals(legal.stage(), clamped.stage());
        assertEquals(legal.mutated(), clamped.mutated());
    }

    // ---- countSuitableDays (non-arable calendar day counter) ---------------

    @Test
    void countSuitableDays_zeroOrNegativeWindow_returnsZero() {
        // Guard clauses: endDay <= startDay and negative (clock rollback) windows.
        assertEquals(0, CropGrowthTracker.countSuitableDays(10, 10, SPRING_ONLY, 7));
        assertEquals(0, CropGrowthTracker.countSuitableDays(20, 10, SPRING_ONLY, 7));
        assertEquals(0, CropGrowthTracker.countSuitableDays(-50, -60, SPRING_ONLY, 7));
    }

    @Test
    void countSuitableDays_allSeasons_returnsElapsedDays() {
        assertEquals(130, CropGrowthTracker.countSuitableDays(0, 130, ALL_SEASONS, 7));
    }

    @Test
    void countSuitableDays_noneSeason_returnsElapsedDays() {
        Set<Season> none = EnumSet.of(Season.NONE);
        assertEquals(130, CropGrowthTracker.countSuitableDays(0, 130, none, 7));
    }

    @Test
    void countSuitableDays_springOnly_wholeSpring() {
        // Spring = days 0..41 (6 terms x 7 days) are all suitable.
        assertEquals(42, CropGrowthTracker.countSuitableDays(0, 42, SPRING_ONLY, 7));
    }

    @Test
    void countSuitableDays_springOnly_unsuitableSeason_frozen() {
        // Summer = days 42..83 are entirely unsuitable for a spring-only crop.
        assertEquals(0, CropGrowthTracker.countSuitableDays(42, 84, SPRING_ONLY, 7));
    }

    @Test
    void countSuitableDays_springOnly_partialWindow() {
        // Days 41..48: day 41 is still spring (suitable), days 42..48 summer.
        assertEquals(1, CropGrowthTracker.countSuitableDays(41, 49, SPRING_ONLY, 7));
        // First 7 days all fall inside spring.
        assertEquals(7, CropGrowthTracker.countSuitableDays(0, 7, SPRING_ONLY, 7));
    }

    @Test
    void countSuitableDays_springOnly_wholeYearPeriods() {
        // One year = 168 days, of which spring accounts for 42. Two years => 84.
        assertEquals(84, CropGrowthTracker.countSuitableDays(0, 336, SPRING_ONLY, 7));
    }

    @Test
    void countSuitableDays_springOnly_midAutumnWindow() {
        // Autumn starts at day 84; a 10-day window days 84..93 is all unsuitable.
        assertEquals(0, CropGrowthTracker.countSuitableDays(84, 94, SPRING_ONLY, 7));
    }

    // ---- tomato climb segments (one segment per suitable day, capped) ------

    /** Mirrors tryClimbVine's desired = min(suitableDays, maxClimbHeight) math. */
    private static int climbSegments(int plantedDay, int currentDay, Set<Season> suitable,
                                     int termLength, int maxClimbHeight) {
        return Math.min(
                CropGrowthTracker.countSuitableDays(plantedDay, currentDay, suitable, termLength),
                maxClimbHeight);
    }

    @Test
    void tomatoClimb_oneSegmentPerSuitableDay() {
        // 7 suitable spring days => 7 segments when the cap is high enough.
        assertEquals(7, climbSegments(0, 7, SPRING_ONLY, 7, 10));
        // Unsuitable summer days contribute nothing.
        assertEquals(0, climbSegments(42, 49, SPRING_ONLY, 7, 10));
    }

    @Test
    void tomatoClimb_cappedByMaxClimbHeight() {
        assertEquals(4, climbSegments(0, 7, SPRING_ONLY, 7, 4));
        assertEquals(7, climbSegments(0, 7, SPRING_ONLY, 7, 7));
    }

    @Test
    void tomatoClimb_afterTransform_resetRhythm() {
        // Regression for the budding_tomatoes → tomatoes TRANSFORM bug: the
        // transform resets plantedDay to the transform day. On that day the vine
        // must not climb (0 suitable days elapsed), and each subsequent suitable
        // day adds exactly 1 segment — it must NOT instantly jump to the
        // maxClimbHeight as it did when tryClimbVine used the stale budding
        // plantedDay (which counted the whole budding phase as climb days).
        int transformDay = 10; // mid-spring, every day suitable
        assertEquals(0, climbSegments(transformDay, transformDay, SPRING_ONLY, 7, 2));
        assertEquals(1, climbSegments(transformDay, transformDay + 1, SPRING_ONLY, 7, 2));
        assertEquals(2, climbSegments(transformDay, transformDay + 2, SPRING_ONLY, 7, 2));
        // Cap still holds once the stack reaches maxClimbHeight.
        assertEquals(2, climbSegments(transformDay, transformDay + 10, SPRING_ONLY, 7, 2));
    }

    // ---- simulateStem (melon/pumpkin stem lifecycle) -----------------------

    @Test
    void simulateStem_freshUnsuitableDay_doesNotMutateImmediately() {
        // Planted near the end of spring (day 36). Days 37..41 are spring,
        // day 42 is the first summer (unsuitable) day. A single unsuitable day
        // must NOT roll for mutation (needs daysPerFruit=3 accumulated).
        CropSimulation.StemSimulation sim = CropGrowthTracker.simulateStem(
                POS_KEY, 36, 43, 3, 3, 7, SEASON_LENGTH, SPRING_ONLY, 1.0, 0.0);
        assertEquals(1, sim.stage()); // 5 suitable days: day 39 crosses stage 1
        assertFalse(sim.mutated());
        assertFalse(sim.fruited());
    }

    @Test
    void simulateStem_mutationRequiresFullUnsuitableWindow() {
        // Planted day 39: days 40-41 spring (suitable), day 42+ summer. With
        // mutateChance=1.0, mutation must only fire once 3 unsuitable days have
        // accumulated — never on the first or second unsuitable day.
        assertFalse(CropGrowthTracker.simulateStem(
                POS_KEY, 39, 42, 3, 3, 7, SEASON_LENGTH, SPRING_ONLY, 1.0, 0.0).mutated()); // 1 unsuitable day
        assertFalse(CropGrowthTracker.simulateStem(
                POS_KEY, 39, 43, 3, 3, 7, SEASON_LENGTH, SPRING_ONLY, 1.0, 0.0).mutated()); // 2 unsuitable days
        assertTrue(CropGrowthTracker.simulateStem(
                POS_KEY, 39, 44, 3, 3, 7, SEASON_LENGTH, SPRING_ONLY, 1.0, 0.0).mutated()); // 3 unsuitable days
    }

    @Test
    void simulateStem_catchesUpSuitableFruitingAcrossSeasonBoundary() {
        // Planted at the start of spring (day 0). Matures after 21 days and
        // fruits after another daysPerFruit window; crossing into summer must
        // preserve the already-earned fruiting result and not mutate on day 1.
        CropSimulation.StemSimulation sim = CropGrowthTracker.simulateStem(
                POS_KEY, 0, 43, 3, 3, 7, SEASON_LENGTH, SPRING_ONLY, 0.0, 0.0);
        assertEquals(7, sim.stage());
        assertTrue(sim.fruited());
        assertFalse(sim.mutated());
    }

    @Test
    void simulateStem_matureStemMutatesInUnsuitableSeason() {
        // Planted at spring start, simulate deep into autumn. With mutateChance=1.0
        // the first full unsuitable (summer) window mutates the stem.
        CropSimulation.StemSimulation sim = CropGrowthTracker.simulateStem(
                POS_KEY, 0, 130, 3, 3, 7, SEASON_LENGTH, SPRING_ONLY, 1.0, 0.0);
        assertTrue(sim.mutated());
    }

    @Test
    void simulateStem_fruitedThenMutates_keepsFruitedFlag() {
        // Planted at spring start; matures (21 days) and fruits (3 more suitable
        // days) inside spring, then crosses into summer where the first full
        // unsuitable window (mutateChance=1.0) mutates it. The simulation must
        // report both mutated AND fruited so processStem can re-place the fruit
        // before the stem becomes short grass.
        CropSimulation.StemSimulation sim = CropGrowthTracker.simulateStem(
                POS_KEY, 0, 44, 3, 3, 7, SEASON_LENGTH, SPRING_ONLY, 1.0, 0.0);
        assertEquals(7, sim.stage());
        assertTrue(sim.mutated());
        assertTrue(sim.fruited());
    }

    @Test
    void simulateStem_yearRound_fruitsWithoutMutation() {
        CropSimulation.StemSimulation sim = CropGrowthTracker.simulateStem(
                POS_KEY, 0, 30, 3, 3, 7, SEASON_LENGTH, ALL_SEASONS, 1.0, 1.0);
        assertEquals(7, sim.stage());
        assertTrue(sim.fruited());
        assertFalse(sim.mutated());
    }

    @Test
    void simulateStem_deterministicAcrossCalls() {
        CropSimulation.StemSimulation first = CropGrowthTracker.simulateStem(
                POS_KEY, 0, 130, 3, 3, 7, SEASON_LENGTH, SPRING_ONLY, 0.2, 0.2);
        CropSimulation.StemSimulation second = CropGrowthTracker.simulateStem(
                POS_KEY, 0, 130, 3, 3, 7, SEASON_LENGTH, SPRING_ONLY, 0.2, 0.2);
        assertEquals(first.stage(), second.stage());
        assertEquals(first.mutated(), second.mutated());
        assertEquals(first.fruited(), second.fruited());
    }

    // ---- stem fruit harvest (plantedDay back-calculation) -------------------

    @Test
    void stemPlantedDayAfterHarvest_treatsMatureStemAsJustMatured() {
        // Vanilla reverts a harvested attached stem to StemBlock at MAX_AGE (7).
        // With daysPerStage=3, back-calculation must yield currentDay - 21 so the
        // stem is "just matured" now — not currentDay (which would make the
        // mature stem wait another 21 days to reach MAX_AGE in the simulation).
        assertEquals(234 - 21, CropGrowthTracker.stemPlantedDayAfterHarvest(234, 7, 7, 3));
    }

    @Test
    void stemPlantedDayAfterHarvest_fruitsAgainAfterDaysPerFruit() {
        int harvestDay = 234;
        int plantedDay = CropGrowthTracker.stemPlantedDayAfterHarvest(harvestDay, 7, 7, 3);
        // On the harvest day the stem is mature but has not yet fruited again.
        CropSimulation.StemSimulation atHarvest = CropGrowthTracker.simulateStem(
                POS_KEY, plantedDay, harvestDay, 3, 3, 7, SEASON_LENGTH, ALL_SEASONS, 1.0, 1.0);
        assertEquals(7, atHarvest.stage());
        assertFalse(atHarvest.fruited());
        // daysPerFruit (3) days later it fruits again.
        CropSimulation.StemSimulation threeDaysLater = CropGrowthTracker.simulateStem(
                POS_KEY, plantedDay, harvestDay + 3, 3, 3, 7, SEASON_LENGTH, ALL_SEASONS, 1.0, 1.0);
        assertEquals(7, threeDaysLater.stage());
        assertTrue(threeDaysLater.fruited());
        assertFalse(threeDaysLater.mutated());
    }

    @Test
    void stemPlantedDayAfterHarvest_clampsAgainstNegativeDaysPerStage() {
        assertEquals(234, CropGrowthTracker.stemPlantedDayAfterHarvest(234, 7, 7, -1));
    }

    // ---- stem bonemeal acceleration (plantedDay shift) ---------------------

    @Test
    void stemPlantedDayAfterBonemeal_shiftsBackByAcceleratedStages() {
        // Planted at day 0, bonemealed +1 stage: plantedDay shifts back 3 days
        // so the calendar reports stage 1 immediately (no stall).
        assertEquals(-3, CropGrowthTracker.stemPlantedDayAfterBonemeal(0, 0, 0, 1, 3));
    }

    @Test
    void stemPlantedDayAfterBonemeal_noStallAfterAcceleration() {
        // Planted day 0, bonemealed 0->1 at day 0 (plantedDay shifted to -3).
        int plantedDay = CropGrowthTracker.stemPlantedDayAfterBonemeal(0, 0, 0, 1, 3);
        // Three days later the stem must be at stage 2 — not stalled at stage 1.
        CropSimulation.StemSimulation sim = CropGrowthTracker.simulateStem(
                POS_KEY, plantedDay, 3, 3, 3, 7, SEASON_LENGTH, ALL_SEASONS, 1.0, 1.0);
        assertEquals(2, sim.stage());
    }

    @Test
    void stemPlantedDayAfterBonemeal_clampsNegativeDaysPerStage() {
        assertEquals(10, CropGrowthTracker.stemPlantedDayAfterBonemeal(10, 10, 0, 1, -1));
    }

    // ---- crop bonemeal back-calculation (conservative suitable-day alignment) --

    @Test
    void backCalculatePlantedDaySuitable_allSeasons_simpleDivision() {
        assertEquals(91, CropGrowthTracker.backCalculatePlantedDaySuitable(
                100, 3, 3, ALL_SEASONS, SEASON_LENGTH));
    }

    @Test
    void backCalculatePlantedDaySuitable_springOnly_fullAcceleration() {
        assertEquals(21, CropGrowthTracker.backCalculatePlantedDaySuitable(
                30, 3, 3, SPRING_ONLY, SEASON_LENGTH));
    }

    @Test
    void backCalculatePlantedDaySuitable_currentUnsuitable_noAcceleration() {
        assertEquals(45, CropGrowthTracker.backCalculatePlantedDaySuitable(
                45, 3, 3, SPRING_ONLY, SEASON_LENGTH));
    }

    @Test
    void backCalculatePlantedDaySuitable_crossesIntoSummer_partial() {
        Set<Season> springAutumn = EnumSet.of(Season.SPRING, Season.AUTUMN);
        assertEquals(78, CropGrowthTracker.backCalculatePlantedDaySuitable(
                120, 8, 7, springAutumn, SEASON_LENGTH));
        // Round-trip: simulating from the back-calculated plantedDay to currentDay
        // must reproduce exactly the aligned accelerated stage (6), never mutate.
        CropSimulation.GrowthSimulation sim = CropGrowthTracker.simulateGrowth(
                POS_KEY, 78, 120, 7, 8, SEASON_LENGTH, springAutumn, false, MUTATE_CHANCE, GROW_CHANCE);
        assertEquals(6, sim.stage());
        assertFalse(sim.mutated());
    }

    @Test
    void backCalculatePlantedDaySuitable_roundTrip_alignsWithSimulateGrowth() {
        // Generic assertion: within a continuous suitable span, simulating from the
        // back-calculated plantedDay must reproduce the accelerated stage exactly.
        int currentDay = 30;
        int newAge = 3;
        int daysPerStage = 3;
        int plantedDay = CropGrowthTracker.backCalculatePlantedDaySuitable(
                currentDay, newAge, daysPerStage, SPRING_ONLY, SEASON_LENGTH);
        CropSimulation.GrowthSimulation sim = CropGrowthTracker.simulateGrowth(
                POS_KEY, plantedDay, currentDay, daysPerStage, newAge, SEASON_LENGTH,
                SPRING_ONLY, false, MUTATE_CHANCE, GROW_CHANCE);
        assertEquals(newAge, sim.stage());
        assertFalse(sim.mutated());
    }

    // ---- HEIGHT crop (kelp / sugar cane) bonemeal back-calculation ---------

    @Test
    void heightCropPlantedDayAfterBonemeal_allSeasons_simpleDivision() {
        assertEquals(91, CropGrowthTracker.heightCropPlantedDayAfterBonemeal(
                100, 4, 26, 3, ALL_SEASONS, SEASON_LENGTH));
    }

    @Test
    void heightCropPlantedDayAfterBonemeal_springOnly_fullAcceleration() {
        assertEquals(21, CropGrowthTracker.heightCropPlantedDayAfterBonemeal(
                30, 4, 26, 3, SPRING_ONLY, SEASON_LENGTH));
    }

    @Test
    void heightCropPlantedDayAfterBonemeal_currentUnsuitable_noAcceleration() {
        assertEquals(45, CropGrowthTracker.heightCropPlantedDayAfterBonemeal(
                45, 4, 26, 3, SPRING_ONLY, SEASON_LENGTH));
    }

    @Test
    void heightCropPlantedDayAfterBonemeal_maxHeightClamp() {
        // newHeight 30 clamps newAge to maxHeight-1 = 25 → 150 - 125 = 25.
        assertEquals(25, CropGrowthTracker.heightCropPlantedDayAfterBonemeal(
                150, 30, 26, 5, ALL_SEASONS, SEASON_LENGTH));
        // newHeight 1 clamps newAge to 0 → returns currentDay (no back-shift).
        assertEquals(150, CropGrowthTracker.heightCropPlantedDayAfterBonemeal(
                150, 1, 26, 5, ALL_SEASONS, SEASON_LENGTH));
    }

    @Test
    void heightCropPlantedDayAfterBonemeal_roundTrip_alignsWithSimulateGrowth() {
        int plantedDay = CropGrowthTracker.heightCropPlantedDayAfterBonemeal(
                30, 4, 26, 3, SPRING_ONLY, SEASON_LENGTH);
        assertEquals(21, plantedDay);
        // HEIGHT target height = stage + 1; kelp is non-arable (freeze semantics).
        CropSimulation.GrowthSimulation sim = CropGrowthTracker.simulateGrowth(
                POS_KEY, plantedDay, 30, 3, 25, SEASON_LENGTH,
                SPRING_ONLY, true, MUTATE_CHANCE, GROW_CHANCE);
        assertEquals(3, sim.stage());
        assertEquals(4, sim.stage() + 1);
    }

    @Test
    void heightCropPlantedDayAfterBonemeal_height2_singleStage() {
        // newHeight 2 → newAge 1 → D - 1*3 = D-3 (single kelp bonemeal core case).
        assertEquals(97, CropGrowthTracker.heightCropPlantedDayAfterBonemeal(
                100, 2, 26, 3, ALL_SEASONS, SEASON_LENGTH));
    }

    @Test
    void heightCropPlantedDayAfterBonemeal_height3_twoStages() {
        // newHeight 3 → newAge 2 → D - 2*3 = D-6 (manual [plant,plant,head] case).
        assertEquals(94, CropGrowthTracker.heightCropPlantedDayAfterBonemeal(
                100, 3, 26, 3, ALL_SEASONS, SEASON_LENGTH));
    }

    @Test
    void heightCropPlantedDayAfterBonemeal_height2_roundTrip_targetMatches() {
        // newHeight 2 in spring-only: back-shift then simulateGrowth must yield
        // stage 1 → target height 2, aligned with the actual height 2 (no stall).
        int plantedDay = CropGrowthTracker.heightCropPlantedDayAfterBonemeal(
                30, 2, 26, 3, SPRING_ONLY, SEASON_LENGTH);
        CropSimulation.GrowthSimulation sim = CropGrowthTracker.simulateGrowth(
                POS_KEY, plantedDay, 30, 3, 25, SEASON_LENGTH,
                SPRING_ONLY, true, MUTATE_CHANCE, GROW_CHANCE);
        assertEquals(1, sim.stage());
        assertEquals(2, sim.stage() + 1);
    }

    @Test
    void heightCropPlantedDayAfterBonemeal_height1_noShift() {
        // newHeight 1 → newAge 0 → no back-shift (plantedDay stays currentDay).
        assertEquals(100, CropGrowthTracker.heightCropPlantedDayAfterBonemeal(
                100, 1, 26, 3, ALL_SEASONS, SEASON_LENGTH));
    }

    // ---- catch-up elapsed clamp (freeze fix) ------------------------------

    @Test
    void clampSimDay_zeroMaxElapsed_noClamp() {
        assertEquals(100, CropGrowthTracker.clampSimDay(0, 100, 0));
    }

    @Test
    void clampSimDay_withinHorizon_unchanged() {
        assertEquals(100, CropGrowthTracker.clampSimDay(0, 100, 336));
        assertEquals(-50, CropGrowthTracker.clampSimDay(-100, -50, 336));
    }

    @Test
    void clampSimDay_beyondHorizon_clampedToHorizon() {
        assertEquals(336, CropGrowthTracker.clampSimDay(0, 10_000, 336));
        // horizon = -250 + 336 = 86
        assertEquals(86, CropGrowthTracker.clampSimDay(-250, 10_000, 336));
    }

    @Test
    void clampSimDay_overflowSafe() {
        // plantedDay near Integer.MAX_VALUE: the long horizon exceeds int range but
        // currentDay must stay unchanged rather than clamping to a bogus wrapped value.
        assertEquals(Integer.MAX_VALUE - 5,
                CropGrowthTracker.clampSimDay(Integer.MAX_VALUE - 10, Integer.MAX_VALUE - 5, 336));
    }

    @Test
    void simulateGrowth_hugeElapsed_terminatesQuicklyAndBounded() {
        // A massive calendar jump must not hang: the fail-fast clamp bounds the
        // attempt loop. assertTimeoutPreemptively enforces a hard wall-clock bound.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            CropSimulation.GrowthSimulation sim = CropGrowthTracker.simulateGrowth(
                    POS_KEY, 0, Integer.MAX_VALUE, 3, 7, SEASON_LENGTH, SPRING_ONLY, false, MUTATE_CHANCE, GROW_CHANCE);
            assertTrue(sim.stage() >= 0 && sim.stage() <= 7, "stage out of bounds: " + sim.stage());
        });
    }

    @Test
    void simulateGrowth_hugeElapsed_equalsClampedWindow() {
        // The fail-fast clamp collapses currentDay to plantedDay + HARD_MAX_ELAPSED_DAYS,
        // so simulating the huge value must equal simulating the clamped window.
        int plantedDay = 0;
        int clampedDay = plantedDay + CropGrowthTracker.HARD_MAX_ELAPSED_DAYS;
        CropSimulation.GrowthSimulation huge = CropGrowthTracker.simulateGrowth(
                POS_KEY, plantedDay, Integer.MAX_VALUE, 3, 7, SEASON_LENGTH, SPRING_ONLY, false, MUTATE_CHANCE, GROW_CHANCE);
        CropSimulation.GrowthSimulation clamped = CropGrowthTracker.simulateGrowth(
                POS_KEY, plantedDay, clampedDay, 3, 7, SEASON_LENGTH, SPRING_ONLY, false, MUTATE_CHANCE, GROW_CHANCE);
        assertEquals(clamped.stage(), huge.stage());
        assertEquals(clamped.mutated(), huge.mutated());
    }

    @Test
    void simulateGrowth_negativePlantedDay_terminatesAndBounded() {
        // A corrupted negative plantedDay must not hang; the clamp keeps the window
        // bounded even when currentDay - plantedDay would otherwise overflow/hugely.
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            CropSimulation.GrowthSimulation sim = CropGrowthTracker.simulateGrowth(
                    POS_KEY, Integer.MIN_VALUE + 5, Integer.MAX_VALUE, 3, 7, SEASON_LENGTH,
                    SPRING_ONLY, false, MUTATE_CHANCE, GROW_CHANCE);
            assertTrue(sim.stage() >= 0 && sim.stage() <= 7, "stage out of bounds: " + sim.stage());
        });
    }

    @Test
    void simulateStem_hugeElapsed_terminatesQuicklyAndBounded() {
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            CropSimulation.StemSimulation sim = CropGrowthTracker.simulateStem(
                    POS_KEY, 0, Integer.MAX_VALUE, 3, 3, 7, SEASON_LENGTH, SPRING_ONLY, MUTATE_CHANCE, 0.2);
            assertTrue(sim.stage() >= 0 && sim.stage() <= 7, "stage out of bounds: " + sim.stage());
        });
    }

    @Test
    void simulateStem_hugeElapsed_equalsClampedWindow() {
        int plantedDay = 0;
        int clampedDay = plantedDay + CropGrowthTracker.HARD_MAX_ELAPSED_DAYS;
        CropSimulation.StemSimulation huge = CropGrowthTracker.simulateStem(
                POS_KEY, plantedDay, Integer.MAX_VALUE, 3, 3, 7, SEASON_LENGTH, SPRING_ONLY, MUTATE_CHANCE, 0.2);
        CropSimulation.StemSimulation clamped = CropGrowthTracker.simulateStem(
                POS_KEY, plantedDay, clampedDay, 3, 3, 7, SEASON_LENGTH, SPRING_ONLY, MUTATE_CHANCE, 0.2);
        assertEquals(clamped.stage(), huge.stage());
        assertEquals(clamped.mutated(), huge.mutated());
        assertEquals(clamped.fruited(), huge.fruited());
    }

    @Test
    void simulateStem_futurePlantedDay_returnsFreshStem() {
        // plantedDay in the future (rollback guard): stage 0, no mutate, no fruit.
        CropSimulation.StemSimulation sim = CropGrowthTracker.simulateStem(
                POS_KEY, 1000, 10, 3, 3, 7, SEASON_LENGTH, SPRING_ONLY, 1.0, 1.0);
        assertEquals(0, sim.stage());
        assertFalse(sim.mutated());
        assertFalse(sim.fruited());
    }
}
