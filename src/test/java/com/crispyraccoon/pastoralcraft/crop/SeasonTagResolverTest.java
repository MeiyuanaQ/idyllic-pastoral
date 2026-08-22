package com.crispyraccoon.pastoralcraft.crop;

import com.teamtea.eclipticseasons.api.constant.crop.CropSeasonInfo;
import com.teamtea.eclipticseasons.api.constant.solar.Season;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the season bitmask conversion and the pure config-string parser.
 *
 * <p>{@link SeasonTagResolver#seasonsFrom(CropSeasonInfo)} and
 * {@link SeasonTagResolver#parseDefaultUntaggedSeasons(String)} are pure
 * functions that need no Minecraft bootstrap: {@link CropSeasonInfo} is a plain
 * value class and {@link Season} is a plain enum.</p>
 */
class SeasonTagResolverTest {

    private static final Set<Season> ALL =
            EnumSet.of(Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.WINTER);

    private static void assertSeasons(String raw, Season... expected) {
        assertEquals(Set.of(expected), SeasonTagResolver.parseDefaultUntaggedSeasons(raw));
    }

    // ---- parseDefaultUntaggedSeasons --------------------------------------

    @Test
    void parse_nullDefaultsToAllSeasons() {
        assertEquals(ALL, SeasonTagResolver.parseDefaultUntaggedSeasons(null));
    }

    @Test
    void parse_yearRoundDefaultsToAllSeasons() {
        assertSeasons("year_round", Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.WINTER);
    }

    @Test
    void parse_allDefaultsToAllSeasons() {
        assertSeasons("all", Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.WINTER);
    }

    @Test
    void parse_emptyOrWhitespaceDefaultsToAllSeasons() {
        assertEquals(ALL, SeasonTagResolver.parseDefaultUntaggedSeasons(""));
        assertEquals(ALL, SeasonTagResolver.parseDefaultUntaggedSeasons("   "));
    }

    @Test
    void parse_springOnly() {
        assertSeasons("spring", Season.SPRING);
    }

    @Test
    void parse_springSummerAutumn_default() {
        assertSeasons("spring_summer_autumn", Season.SPRING, Season.SUMMER, Season.AUTUMN);
    }

    @Test
    void parse_winterAndFallAlias() {
        assertSeasons("winter", Season.WINTER);
        assertSeasons("fall", Season.AUTUMN);
    }

    @Test
    void parse_commaAndWhitespaceSeparators() {
        assertSeasons("  spring , summer  ", Season.SPRING, Season.SUMMER);
    }

    @Test
    void parse_isCaseInsensitive() {
        assertSeasons("SPRING,Summer,Autumn", Season.SPRING, Season.SUMMER, Season.AUTUMN);
    }

    @Test
    void parse_unknownTokensAreIgnored() {
        assertSeasons("spring,monsoon", Season.SPRING);
    }

    // ---- seasonsFrom (bitmask conversion) ---------------------------------

    @Test
    void seasonsFrom_bit0to2_springSummerAutumn() {
        // 0b0111 = SPRING(bit0) | SUMMER(bit1) | AUTUMN(bit2)
        Set<Season> seasons = SeasonTagResolver.seasonsFrom(new CropSeasonInfo(0b0111));
        assertEquals(Set.of(Season.SPRING, Season.SUMMER, Season.AUTUMN), seasons);
    }

    @Test
    void seasonsFrom_bit3_winterOnly() {
        // 0b1000 = WINTER(bit3)
        Set<Season> seasons = SeasonTagResolver.seasonsFrom(new CropSeasonInfo(0b1000));
        assertEquals(Set.of(Season.WINTER), seasons);
    }

    @Test
    void seasonsFrom_fullMask_allSeasons() {
        // 0b1111 = all four seasons
        Set<Season> seasons = SeasonTagResolver.seasonsFrom(new CropSeasonInfo(0b1111));
        assertEquals(ALL, seasons);
    }

    @Test
    void seasonsFrom_zeroMask_emptySet() {
        Set<Season> seasons = SeasonTagResolver.seasonsFrom(new CropSeasonInfo(0));
        assertTrue(seasons.isEmpty());
    }

    @Test
    void seasonsFrom_staggeredMask_springAndAutumn() {
        // 0b0101 = SPRING(bit0) | AUTUMN(bit2)
        Set<Season> seasons = SeasonTagResolver.seasonsFrom(new CropSeasonInfo(0b0101));
        assertEquals(Set.of(Season.SPRING, Season.AUTUMN), seasons);
        assertFalse(seasons.contains(Season.SUMMER));
        assertFalse(seasons.contains(Season.WINTER));
    }

    // ---- resolveSuitableSeasons short-circuit -----------------------------

    @Test
    void resolveSuitableSeasons_noneShortCircuitsWithoutBlock() {
        // Season.NONE (seasons disabled) must return ALL_SEASONS without touching
        // the (null) block — the method short-circuits before any Block lookup.
        assertEquals(ALL, CropGrowthTracker.resolveSuitableSeasons(Season.NONE, null));
    }
}
