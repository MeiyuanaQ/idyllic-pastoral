package com.crispyraccoon.pastoralcraft.crop;

import java.util.Set;

import com.teamtea.eclipticseasons.api.constant.solar.Season;

import net.minecraft.world.level.block.Block;

/**
 * Ecliptic Seasons solar-term calendar math and crop season resolution — pure
 * functions with no Level access.
 *
 * <p>Extracted from {@link CropGrowthTracker} (mechanical move, no logic change).
 */
public final class CropCalendar {

    private CropCalendar() {
        // Utility class — prevent instantiation.
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
}
