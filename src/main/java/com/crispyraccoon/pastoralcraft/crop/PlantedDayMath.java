package com.crispyraccoon.pastoralcraft.crop;

import java.util.Set;

import com.teamtea.eclipticseasons.api.constant.solar.Season;

/**
 * Pure {@code plantedDay} back-calculation helpers (bonemeal back-shift, stem
 * harvest re-maturity, companion/height alignment). No Level access.
 *
 * <p>Extracted from {@link CropGrowthTracker} (mechanical move, no logic change).
 */
public final class PlantedDayMath {

    private PlantedDayMath() {
        // Utility class — prevent instantiation.
    }

    /**
     * Back-calculate the plantedDay for a newly tracked crop that already has
     * growth stages (e.g. world-gen farms, or an age&gt;0 crop placed by another
     * mod). Clamped so a negative daysPerStage never pushes plantedDay into
     * the future (which would freeze growth forever).
     *
     * @param currentDay   the current solar day
     * @param currentAge   the crop's current age
     * @param daysPerStage solar days per growth stage
     * @return the back-calculated plantedDay
     */
    public static int backCalculatedPlantedDay(int currentDay, int currentAge, int daysPerStage) {
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
     * season boundary — {@link CropSimulation#simulateGrowth} then sees only
     * suitable end-days, grows deterministically (no mutation roll), and reports
     * exactly the number of aligned stages. When the suitable span is shorter than
     * {@code newAge} the acceleration is only partial, which is strictly safer than
     * mutating.</p>
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
    public static int backCalculatePlantedDaySuitable(int currentDay, int newAge, int daysPerStage,
                                                       Set<Season> suitableSeasons, int seasonLength) {
        if (newAge <= 0 || daysPerStage <= 0) return currentDay;
        if (suitableSeasons.contains(Season.NONE) || suitableSeasons.size() >= 4) {
            return currentDay - newAge * daysPerStage;
        }
        int termLength = Math.max(1, seasonLength / CropGrowthConfig.SOLAR_TERMS_PER_SEASON);
        int aligned = 0;
        for (int k = 0; k < newAge; k++) {
            int endDay = currentDay - k * daysPerStage;
            if (CropCalendar.isSeasonSuitable(CropCalendar.seasonOfDay(endDay, termLength), suitableSeasons)) {
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
     * the calendar target lags and catch-up (while currentHeight &lt; targetHeight)
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
    public static int heightCropPlantedDayAfterBonemeal(int currentDay, int newHeight, int maxHeight,
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
    public static int clampPlantedDay(int plantedDay, int currentDay) {
        return Math.min(plantedDay, currentDay);
    }

    /**
     * Back-calculate the plantedDay for a melon/pumpkin stem whose fruit was
     * just harvested (the {@link net.minecraft.world.level.block.AttachedStemBlock}
     * reverted to a mature {@link net.minecraft.world.level.block.StemBlock}).
     * Pure function so it can be unit-tested.
     *
     * @param currentDay   the current solar day
     * @param age          the reverted stem's current age (MAX_AGE)
     * @param maxAge       the stem's maximum age
     * @param daysPerStage solar days per growth stage
     * @return a plantedDay that makes the stem "just matured" now
     */
    public static int stemPlantedDayAfterHarvest(int currentDay, int age, int maxAge, int daysPerStage) {
        int effectiveAge = Math.max(0, Math.min(age, maxAge));
        int plantedDay = currentDay - effectiveAge * daysPerStage;
        return plantedDay > currentDay ? currentDay : plantedDay;
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
    public static int stemPlantedDayAfterBonemeal(int plantedDay, int currentDay, int oldAge, int newAge, int daysPerStage) {
        int shifted = plantedDay - (newAge - oldAge) * daysPerStage;
        return shifted > currentDay ? currentDay : shifted;
    }
}
