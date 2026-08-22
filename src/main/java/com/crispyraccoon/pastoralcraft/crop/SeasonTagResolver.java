package com.crispyraccoon.pastoralcraft.crop;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.teamtea.eclipticseasons.api.constant.crop.CropSeasonInfo;
import com.teamtea.eclipticseasons.api.constant.solar.Season;

import net.minecraft.world.level.block.Block;

/**
 * Resolves a crop block's suitable seasons using a chained set of
 * {@link SeasonSource}s.
 *
 * <p>Resolution chain (first non-empty source wins):</p>
 * <ol>
 *   <li>{@link SeasonSource.EsCropInfoSeasonSource} — the Ecliptic Seasons runtime
 *       crop registry ({@code CropInfoManager.getSeasonInfo(Block)}).</li>
 *   <li>{@link SeasonSource.BlockTagSeasonSource} — direct reading of the Ecliptic
 *       Seasons crop season block tags ({@code eclipticseasons:crops/*}).</li>
 *   <li>Per-crop {@code seasons=} override (see {@link CropGrowthConfig#getOverrideSeasons}).</li>
 *   <li>{@link #getDefaultUntaggedSeasons()} — configurable default
 *       (default SP_SU_AU, can be set to year_round).</li>
 * </ol>
 *
 * <p><b>Thread safety:</b> Block tags are immutable once the server has finished
 * loading data packs, so results are safe to cache. The cache uses a
 * {@link ConcurrentHashMap} keyed by {@link Block}, giving O(1) amortized lookups
 * with no per-crop allocation on cache hits. It is cleared on {@code TagsUpdatedEvent}
 * (at LOWEST priority, after Ecliptic Seasons rebuilds its registry at NORMAL) and on
 * config reload.</p>
 */
public final class SeasonTagResolver {

    /** The year-round default used when a block defines no season info. */
    public static final Set<Season> ALL_SEASONS = Collections.unmodifiableSet(
            EnumSet.of(Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.WINTER));

    /**
     * The four seasons in calendar order (index == season ordinal).
     * This is the single source of truth for season ordering, shared with
     * {@link CropGrowthTracker} to avoid hard-coding {@code Season.values()}
     * ordering assumptions (Season also declares NONE at index 4).
     */
    public static final Season[] ORDERED_SEASONS = {
            Season.SPRING, Season.SUMMER, Season.AUTUMN, Season.WINTER
    };

    private static final ConcurrentHashMap<Block, Set<Season>> CACHE = new ConcurrentHashMap<>();

    private SeasonTagResolver() {
        // Utility class — prevent instantiation
    }

    /**
     * Resolve the seasons in which the given crop block can grow, using the
     * chained sources described in the class javadoc.
     *
     * @param block the crop block to resolve
     * @return an immutable set of suitable seasons (never null)
     */
    public static Set<Season> resolve(Block block) {
        Set<Season> cached = CACHE.get(block);
        if (cached != null) {
            return cached;
        }
        Set<Season> result = normalize(compute(block));
        CACHE.putIfAbsent(block, result);
        return CACHE.get(block);
    }

    /**
     * Run the resolution chain without touching the cache.
     * Package-private so unit tests can verify the chain directly.
     */
    static Set<Season> compute(Block block) {
        // 1. Per-crop seasons= override — explicit configuration takes precedence
        //    over the Ecliptic Seasons registry and block tags (matching the
        //    config docs, which promise "takes precedence over both").
        Set<Season> override = CropGrowthConfig.getOverrideSeasons(block);
        if (override != null && !override.isEmpty()) {
            return override;
        }
        // 2. Ecliptic Seasons runtime registry.
        Set<Season> es = SeasonSource.EsCropInfoSeasonSource.INSTANCE.resolve(block);
        if (es != null && !es.isEmpty()) {
            return es;
        }
        // 3. Direct block-tag reading (fallback when the registry has no entry).
        Set<Season> tag = SeasonSource.BlockTagSeasonSource.INSTANCE.resolve(block);
        if (tag != null && !tag.isEmpty()) {
            return tag;
        }
        // 4. Configurable default for completely untagged crops.
        return getDefaultUntaggedSeasons();
    }

    /**
     * Normalize a resolved set: an empty set or a full set is converted to
     * {@link #ALL_SEASONS} so callers can rely on reference equality for the
     * common year-round case.
     */
    private static Set<Season> normalize(Set<Season> seasons) {
        if (seasons == null || seasons.isEmpty() || seasons.size() == ORDERED_SEASONS.length) {
            return ALL_SEASONS;
        }
        return Collections.unmodifiableSet(seasons);
    }

    /**
     * Convert the {@code defaultUntaggedSeasons} config string into an immutable
     * set of seasons.
     *
     * <p>Accepted formats:</p>
     * <ul>
     *   <li>{@code year_round} (or {@code all}) — all four seasons.</li>
     *   <li>A comma/underscore/space separated list of
     *       {@code spring}/{@code summer}/{@code autumn|fall}/{@code winter}.</li>
     * </ul>
     *
     * <p>The default value is {@code spring,summer,autumn} (SP_SU_AU), meaning
     * crops with no season info freeze during winter.</p>
     */
    public static Set<Season> getDefaultUntaggedSeasons() {
        return parseDefaultUntaggedSeasons(CropGrowthConfig.DEFAULT_UNTAGGED_SEASONS.get());
    }

    /**
     * Pure parser for the {@code defaultUntaggedSeasons} config value.
     * Returns {@link #ALL_SEASONS} for null/unknown/empty input as a safety net.
     * Public so unit tests can exercise the parsing without touching config.
     */
    public static Set<Season> parseDefaultUntaggedSeasons(String raw) {
        if (raw == null) {
            return ALL_SEASONS;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.equals("year_round") || trimmed.equals("all")) {
            return ALL_SEASONS;
        }
        EnumSet<Season> seasons = EnumSet.noneOf(Season.class);
        for (String part : trimmed.split("[,_ ]+")) {
            if (part.isEmpty()) {
                continue;
            }
            switch (part) {
                case "spring" -> seasons.add(Season.SPRING);
                case "summer" -> seasons.add(Season.SUMMER);
                case "autumn", "fall" -> seasons.add(Season.AUTUMN);
                case "winter" -> seasons.add(Season.WINTER);
                default -> { /* ignore unknown tokens */ }
            }
        }
        if (seasons.isEmpty()) {
            return ALL_SEASONS;
        }
        return Collections.unmodifiableSet(seasons);
    }

    /**
     * Convert an Ecliptic Seasons {@link CropSeasonInfo} bitmask into the set of
     * suitable {@link Season}s, iterating in calendar order (SPRING → SUMMER →
     * AUTUMN → WINTER). Package-private so both {@link SeasonSource}
     * implementations and unit tests can reuse the same conversion logic.
     *
     * @param info the season bitmask info (never {@code null})
     * @return the set of suitable seasons
     */
    static Set<Season> seasonsFrom(CropSeasonInfo info) {
        EnumSet<Season> seasons = EnumSet.noneOf(Season.class);
        for (Season season : ORDERED_SEASONS) {
            if (info.isSuitable(season)) {
                seasons.add(season);
            }
        }
        return seasons;
    }

    /**
     * Clear the per-block cache.
     *
     * <p>Called on {@code TagsUpdatedEvent} at LOWEST priority (after Ecliptic
     * Seasons' NORMAL handler has rebuilt its registry) so newly loaded data-pack
     * tags take effect, and on config reload so changed {@code defaultUntaggedSeasons}
     * values are re-applied.</p>
     */
    public static void clearCache() {
        CACHE.clear();
    }
}
