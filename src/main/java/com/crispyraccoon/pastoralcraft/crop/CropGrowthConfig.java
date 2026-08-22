package com.crispyraccoon.pastoralcraft.crop;

import java.util.*;

import com.crispyraccoon.pastoralcraft.PastoralCraft;
import com.teamtea.eclipticseasons.api.constant.solar.Season;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

/**
 * Configuration for the PastoralCraft crop growth system.
 * Uses NeoForge's ModConfigSpec for server/common configuration.
 */
public class CropGrowthConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** Default number of solar days required per growth stage. */
    public static final ModConfigSpec.IntValue DEFAULT_DAYS_PER_STAGE = BUILDER
            .comment("Default number of solar days required for each crop growth stage.",
                    "Lower values = faster growth. Set to 0 to disable the growth delay.")
            .defineInRange("defaultDaysPerStage", 3, 0, 365);

    /** Number of solar days between StemBlock fruiting cycles (pumpkin/melon spawning). */
    public static final ModConfigSpec.IntValue DAYS_PER_FRUIT = BUILDER
            .comment("Number of solar days required between StemBlock fruiting cycles.",
                    "After a stem reaches MAX_AGE, it will attempt to spawn fruit every",
                    "this many days while in a suitable season.")
            .defineInRange("daysPerFruit", 3, 1, 365);

    /** Allowed horizontal directions for melon/pumpkin stems to grow fruit. */
    public static final ModConfigSpec.ConfigValue<String> STEM_FRUIT_DIRECTIONS = BUILDER
            .comment("Comma-separated horizontal directions that melon/pumpkin stems may fruit toward.",
                    "Valid values: north, south, east, west (e.g. \"east,north\").",
                    "The order defines the deterministic direction check order.",
                    "Default: east,north (fruit only toward east or north).")
            .define("stemFruitDirections", "east,north", CropGrowthConfig::validateDirections);

    /** Chance that a crop in an unsuitable season mutates into short grass per growth attempt. */
    public static final ModConfigSpec.DoubleValue UNSUITABLE_MUTATE_CHANCE = BUILDER
            .comment("Chance (0.0 - 1.0) that a crop in an unsuitable season will mutate",
                    "into short grass on each growth attempt.",
                    "Deterministic per position + plantedDay + attempt index, so results",
                    "are consistent between real-time and chunk-load catch-up.",
                    "Constraint: mutateChance + unsuitableGrowChance must be <= 1.0.")
            .defineInRange("unsuitableMutateChance", 0.20, 0.0, 1.0);

    /** Chance that a crop in an unsuitable season grows one stage per growth attempt. */
    public static final ModConfigSpec.DoubleValue UNSUITABLE_GROW_CHANCE = BUILDER
            .comment("Chance (0.0 - 1.0) that a crop in an unsuitable season will grow",
                    "one stage on each growth attempt.",
                    "The remaining probability (1 - mutateChance - growChance) results in no growth.",
                    "Constraint: unsuitableMutateChance + this must be <= 1.0; the sum is",
                    "clamped at runtime so the 'no growth' branch stays reachable.")
            .defineInRange("unsuitableGrowChance", 0.40, 0.0, 1.0);

    /** Chance that a mature stem mutates into short grass per fruiting cycle in unsuitable season. */
    public static final ModConfigSpec.DoubleValue STEM_UNSUITABLE_MUTATE_CHANCE = BUILDER
            .comment("Chance (0.0 - 1.0) that a mature stem (MAX_AGE) in an unsuitable season",
                    "mutates into short grass on each fruiting cycle.")
            .defineInRange("stemUnsuitableMutateChance", 0.20, 0.0, 1.0);

    /** Chance that a mature stem fruits per fruiting cycle in unsuitable season. */
    public static final ModConfigSpec.DoubleValue STEM_UNSUITABLE_FRUIT_CHANCE = BUILDER
            .comment("Chance (0.0 - 1.0) that a mature stem (MAX_AGE) in an unsuitable season",
                    "produces a fruit on each fruiting cycle.",
                    "The remaining probability (1 - mutateChance - fruitChance) results in no change.")
            .defineInRange("stemUnsuitableFruitChance", 0.20, 0.0, 1.0);

    /**
     * Number of solar days per Solar Term (节气).
     * Ecliptic Seasons structure: 1 Year = 4 Seasons, 1 Season = 6 Solar Terms.
     * This must match the Ecliptic Seasons "LastingDaysOfEachTerm" setting (default 7 days).
     * One full season is therefore {@code 6 * catchUpSeasonLength} solar days.
     */
    public static final ModConfigSpec.IntValue CATCH_UP_SEASON_LENGTH = BUILDER
            .comment("Number of solar days per Solar Term (节气).",
                    "Ecliptic Seasons: 1 Year = 4 Seasons, 1 Season = 6 Solar Terms.",
                    "Must match the Ecliptic Seasons 'LastingDaysOfEachTerm' setting (default 7 days).")
            .defineInRange("catchUpSeasonLength", 7, 1, 365);

    /** Number of Solar Terms per Season (fixed structure of Ecliptic Seasons). */
    public static final int SOLAR_TERMS_PER_SEASON = 6;

    /**
     * Get the number of solar days in one full season.
     * One season = {@code SOLAR_TERMS_PER_SEASON} solar terms.
     */
    public static int getSeasonLength() {
        return CATCH_UP_SEASON_LENGTH.get() * SOLAR_TERMS_PER_SEASON;
    }

    /** Enable debug logging for crop growth decisions. */
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Enable debug logging for crop growth decisions.",
                    "Logs when crops grow, mutate, or are blocked by season.")
            .define("debugLogging", false);

    /**
     * Enable detailed per-block state tracing for every {@code supplementaries:flax}
     * (both halves) to the debug log.
     *
     * <p>Requires {@link #DEBUG_LOGGING} to also be {@code true}. When enabled,
     * every {@code setBlock} write touching flax, the external
     * {@code growCropBy} call site, and a post-growth integrity snapshot
     * (lower/upper half age sync, HALF property, breakage) are logged with the
     * {@code [FlaxDiag]} prefix at DEBUG level (visible in debug.log).</p>
     */
    public static final ModConfigSpec.BooleanValue DEBUG_FLAX_ALL = BUILDER
            .comment("Enable detailed flax state tracing to the debug log.",
                    "When true (and debugLogging is also true), PastoralCraft logs every",
                    "block-state change of supplementaries:flax (both halves), the growth",
                    "decision context around the 4->5 stage transition, and a post-growth",
                    "integrity snapshot (lower/upper half age sync, HALF property, breakage).",
                    "Logs use the [FlaxDiag] prefix and go to debug.log.",
                    "Default: false")
            .define("debugFlaxAll", false);

    /**
     * Default seasons for crops that have no season information at all — neither
     * in the Ecliptic Seasons runtime registry ({@code CropInfoManager}) nor in
     * any crop season block tag (e.g. most mod-added crops).
     *
     * <p>Format: a comma-separated list of {@code spring}/{@code summer}/
     * {@code autumn}/{@code winter}, or the special value {@code year_round} for
     * all four seasons. The default {@code spring,autumn} (SP_AU) means untagged
     * crops freeze during summer and winter.</p>
     */
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_UNTAGGED_SEASONS = BUILDER
            .comment("Default seasons for crops with no Ecliptic Seasons season info.",
                    "Applies to blocks that are neither in the ES runtime registry nor in any",
                    "crop season block tag (e.g. most mod-added crops).",
                    "Format: comma-separated list of spring/summer/autumn/winter, or 'year_round'.",
                    "Example: \"spring,autumn\" (SP_AU), \"year_round\"",
                    "Default: spring,autumn")
            .define("defaultUntaggedSeasons", "spring,autumn", CropGrowthConfig::validateSeasonString);

    /**
     * Per-crop override configuration.
     * Format: "modid:crop_id=daysPerStage=3"
     * Example: "minecraft:wheat=daysPerStage=4"
     *
     * Suitable seasons are NOT configured here. They are read directly from
     * the Ecliptic Seasons crop season block tags. If a crop is not listed
     * here, it uses defaultDaysPerStage.
     */
    private static final ModConfigSpec.ConfigValue<List<? extends String>> CROP_OVERRIDE_STRINGS = BUILDER
            .comment("Per-crop growth overrides.",
                    "Format: \"modid:crop_id=key=value,key2=value2,...\"",
                    "Supported keys: daysPerStage, seasons, topBlock, transformBlock, water, doubleAge,",
                    "              freeze, climbBlock, climbSupport, maxClimbHeight.",
                    "  daysPerStage=N         - solar days per growth stage",
                    "  seasons=SP_SU_AU       - override suitable seasons ('_'/space separated,",
                    "                          or 'year_round'; omitting uses the resolution chain)",
                    "  topBlock=modid:block  - place this block above when mature (companion)",
                    "  transformBlock=modid:block - replace the block when mature (transform)",
                    "  water=true|false      - require water above before placing topBlock",
                    "  doubleAge=N           - two-block crop: grow upper half once age >= N (-1 = not)",
                    "  freeze=true|false     - non-arable: freeze entirely in unsuitable seasons",
                    "                          (no mutation/growth attempts; default: false)",
                    "  climbBlock=modid:block - vine family counted for climbing height (e.g. farmersdelight:tomatoes_on_rope)",
                    "  climbSupport=modid:block - the support the vine climbs (e.g. farmersdelight:rope)",
                    "  maxClimbHeight=N      - max vine stack segments the vine can climb (default: 0 = none)",
                    "Example: \"farmersdelight:rice=daysPerStage=3,topBlock=farmersdelight:rice_panicles,water=true\"",
                    "Old format \"modid:crop_id=daysPerStage=N\" remains fully compatible.",
                    "Crops not listed here use defaultDaysPerStage.")
            .defineListAllowEmpty("cropOverrides", List.of(), () -> "", CropGrowthConfig::validateOverrideString);

    public static final ModConfigSpec SPEC = BUILDER.build();

    // Cached parsed overrides for fast lookup
    private static Map<ResourceLocation, CropOverride> cachedOverrides = Map.of();

    /**
     * Built-in crop overrides shipped with the mod. These act as sensible
     * defaults for crops that need data-driven side effects; user-configured
     * entries in {@link #CROP_OVERRIDE_STRINGS} take precedence.
     *
     * <p>Keyed by block id, values use the same {@code key=value,...} syntax as
     * the user-facing config. See section 6 of plans/crop-support-redesign.md.</p>
     */
    private static final List<String> BUILT_IN_OVERRIDES = List.of(
            // Kelp: height-based growth is auto-detected, no topBlock needed.
            "minecraft:kelp=daysPerStage=3",
            // Farmers Delight rice: spawn rice_panicles above when mature. FD native advances only
            // when the position above is AIR (the rice has emerged above the water surface), so no
            // water=true here — it would require water above and block the panicles forever.
            "farmersdelight:rice=daysPerStage=3,topBlock=farmersdelight:rice_panicles",
            // Farmers Delight rice panicles: CropBlock (AGE 0-3). Already auto-non-arable via the
            // below-block water-crop rule (rice is a LiquidBlockContainer + recognized crop);
            // freeze=true documents the intent explicitly.
            "farmersdelight:rice_panicles=daysPerStage=3,freeze=true",
            // Farmers Delight tomato stage 1 (budding_tomatoes): one stage per suitable day, freezes
            // in unsuitable seasons, and transforms into the full tomato crop when mature.
            "farmersdelight:budding_tomatoes=daysPerStage=1,transformBlock=farmersdelight:tomatoes,freeze=true",
            // Farmers Delight tomato stage 2 (tomatoes): 3-day fruit rhythm, freezes in unsuitable
            // seasons, climbs the rope family (tomatoes_on_rope) one segment per suitable day, capped
            // at maxClimbHeight (max 2 blocks up, per the user's expected life cycle).
            "farmersdelight:tomatoes=daysPerStage=3,freeze=true,climbBlock=farmersdelight:tomatoes_on_rope,climbSupport=farmersdelight:rope,maxClimbHeight=2",
            "farmersdelight:tomatoes_on_rope=daysPerStage=3,freeze=true,climbBlock=farmersdelight:tomatoes_on_rope,climbSupport=farmersdelight:rope,maxClimbHeight=2",
            // Supplementaries flax: two-block crop; upper half (HALF=UPPER) syncs its age with the lower.
            "supplementaries:flax=daysPerStage=3,doubleAge=4,freeze=true",
            // Vanilla pitcher crop: two-block crop; the upper half appears once the lower reaches age 3.
            "minecraft:pitcher_crop=daysPerStage=3,doubleAge=3",
            // AHP sunflower: regrows every 3 suitable days. Explicit spring+autumn so it freezes in
            // summer and winter like other untagged crops (the AHP block carries no season tag).
            // Note: the seasons value uses underscores because the override string itself is
            // comma-separated (a comma here would be parsed as the next parameter key).
            "adorablehamsterpets:sunflower_block=daysPerStage=3,seasons=spring_autumn");

    /**
     * A per-crop override configuration entry.
     *
     * <p>All fields are data-driven. Suitable seasons are normally resolved from
     * the Ecliptic Seasons runtime registry / block tags, but a per-crop
     * {@code seasons=} value here takes precedence over both (and over the
     * configurable default for untagged crops).</p>
     */
    public static class CropOverride {
        /** Solar days required per growth stage. */
        public final int daysPerStage;
        /** Override suitable seasons; {@code null} = use the normal resolution chain. */
        @Nullable public final Set<Season> seasons;
        /** COMPANION: place this block above when the crop matures; {@code null} = none. */
        @Nullable public final ResourceLocation topBlock;
        /** TRANSFORM: replace the block with this one when it matures; {@code null} = none. */
        @Nullable public final ResourceLocation transformBlock;
        /** COMPANION: require the position above to be water before placing {@link #topBlock}. */
        public final boolean waterCompanion;
        /** DOUBLE: grow the upper half once age reaches this threshold; {@code -1} = not a two-block crop. */
        public final int doubleAge;
        /** FREEZE: treat the crop as non-arable so it freezes entirely in unsuitable seasons. */
        public final boolean freeze;
        /** CLIMB: the vine-family block counted toward the climb stack; {@code null} = no climbing. */
        @Nullable public final ResourceLocation climbBlock;
        /** CLIMB: the support block the vine climbs up; {@code null} = none. */
        @Nullable public final ResourceLocation climbSupport;
        /** CLIMB: maximum number of vine segments the vine can climb (0 = none). */
        public final int maxClimbHeight;

        public CropOverride(int daysPerStage,
                            @Nullable Set<Season> seasons,
                            @Nullable ResourceLocation topBlock,
                            @Nullable ResourceLocation transformBlock,
                            boolean waterCompanion,
                            int doubleAge,
                            boolean freeze,
                            @Nullable ResourceLocation climbBlock,
                            @Nullable ResourceLocation climbSupport,
                            int maxClimbHeight) {
            this.daysPerStage = daysPerStage;
            this.seasons = seasons;
            this.topBlock = topBlock;
            this.transformBlock = transformBlock;
            this.waterCompanion = waterCompanion;
            this.doubleAge = doubleAge;
            this.freeze = freeze;
            this.climbBlock = climbBlock;
            this.climbSupport = climbSupport;
            this.maxClimbHeight = maxClimbHeight;
        }
    }

    private static boolean validateOverrideString(final Object obj) {
        if (!(obj instanceof String s)) return false;
        // Format: "modid:crop_id=daysPerStage=N"
        if (!s.contains("=")) return false;
        String[] parts = s.split("=", 2);
        if (parts.length < 2) return false;
        if (ResourceLocation.tryParse(parts[0]) == null) {
            return false;
        }
        return true;
    }

    private static boolean validateSeasonString(final Object obj) {
        if (!(obj instanceof String s)) return false;
        String trimmed = s.trim().toLowerCase(Locale.ROOT);
        if (trimmed.equals("year_round") || trimmed.equals("all")) return true;
        for (String part : trimmed.split("[,_ ]+")) {
            if (part.isEmpty()) continue;
            switch (part) {
                case "spring", "summer", "autumn", "fall", "winter" -> { /* valid */ }
                default -> { return false; }
            }
        }
        return true;
    }

    private static boolean validateDirections(final Object obj) {
        if (!(obj instanceof String s)) return false;
        for (String part : s.trim().toLowerCase(Locale.ROOT).split("[, ]+")) {
            if (part.isEmpty()) continue;
            if (!part.equals("north") && !part.equals("south")
                    && !part.equals("east") && !part.equals("west")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parse the override strings into a map for fast lookup.
     * Called after config is loaded/reloaded.
     */
    public static void refreshOverrides() {
        Map<ResourceLocation, CropOverride> map = new HashMap<>();
        // Built-in defaults first (lowest priority); user entries take precedence.
        for (String entry : BUILT_IN_OVERRIDES) {
            try {
                ParsedOverride parsed = parseOverride(entry);
                if (parsed != null) map.put(parsed.cropId(), parsed.override());
            } catch (Exception e) {
                PastoralCraft.LOGGER.warn("Failed to parse built-in crop override: {}", entry, e);
            }
        }
        for (String entry : CROP_OVERRIDE_STRINGS.get()) {
            try {
                ParsedOverride parsed = parseOverride(entry);
                if (parsed != null) map.put(parsed.cropId(), parsed.override());
            } catch (Exception e) {
                // Skip malformed entries
                PastoralCraft.LOGGER.warn("Failed to parse crop override: {}", entry, e);
            }
        }
        cachedOverrides = Map.copyOf(map);
        CropGrowthTracker.clearFreezeCache();

        // Cross-validate the unsuitable-season three-way roll: mutate + grow must
        // not exceed 1.0, otherwise the "no growth" branch can never be reached.
        double mutateChance = UNSUITABLE_MUTATE_CHANCE.get();
        double growChance = UNSUITABLE_GROW_CHANCE.get();
        if (mutateChance + growChance > 1.0) {
            PastoralCraft.LOGGER.warn(
                    "[CropGrowthConfig] unsuitableMutateChance ({}) + unsuitableGrowChance ({}) = {} exceeds 1.0; "
                    + "the grow chance will be clamped to {} at runtime so the 'no growth' branch remains reachable.",
                    mutateChance, growChance, mutateChance + growChance, Math.max(0.0, 1.0 - mutateChance));
        }
    }

    /**
     * A parsed override entry plus the block id it applies to.
     *
     * @param cropId   the block resource location
     * @param override the parsed override values
     */
    record ParsedOverride(ResourceLocation cropId, CropOverride override) {}

    /**
     * Parse a single override entry of the form
     * {@code "modid:crop_id=key=value,key2=value2,..."} into a
     * {@link ParsedOverride}. Unknown keys are ignored; malformed values make
     * the whole entry fail (caught by the caller).
     *
     * @param entry the raw override string
     * @return the parsed result, or {@code null} if the entry has no {@code '='}
     */
    @Nullable
    private static ParsedOverride parseOverride(String entry) {
        return parseOverride(entry, DEFAULT_DAYS_PER_STAGE.get());
    }

    /**
     * Parse a single override entry with an explicit default days-per-stage.
     *
     * <p>Package-private overload so unit tests can exercise the parsing logic
     * without touching the {@code ModConfigSpec} (whose {@code ConfigValue.get()}
     * throws before the config is loaded). The production entry point
     * {@link #parseOverride(String)} delegates here with the configured default.</p>
     *
     * @param entry the raw override string
     * @param defaultDaysPerStage the default days-per-stage applied when the entry
     *        does not specify {@code daysPerStage}
     * @return the parsed result, or {@code null} if the entry has no {@code '='}
     */
    @Nullable
    static ParsedOverride parseOverride(String entry, int defaultDaysPerStage) {
        int eqIdx = entry.indexOf('=');
        if (eqIdx < 0) return null;

        String cropId = entry.substring(0, eqIdx);
        String params = entry.substring(eqIdx + 1);

        ResourceLocation rl = ResourceLocation.parse(cropId);

        int daysPerStage = defaultDaysPerStage;
        Set<Season> seasons = null;
        ResourceLocation topBlock = null;
        ResourceLocation transformBlock = null;
        boolean waterCompanion = false;
        int doubleAge = -1;
        boolean freeze = false;
        ResourceLocation climbBlock = null;
        ResourceLocation climbSupport = null;
        int maxClimbHeight = 0;

        String[] paramParts = params.split(",");
        for (String part : paramParts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int kv = p.indexOf('=');
            if (kv < 0) continue;
            String key = p.substring(0, kv);
            String value = p.substring(kv + 1);
            switch (key) {
                case "daysPerStage" -> daysPerStage = Integer.parseInt(value);
                case "seasons" -> seasons = SeasonTagResolver.parseDefaultUntaggedSeasons(value);
                case "topBlock" -> topBlock = ResourceLocation.parse(value);
                case "transformBlock" -> transformBlock = ResourceLocation.parse(value);
                case "water" -> waterCompanion = Boolean.parseBoolean(value);
                case "doubleAge" -> doubleAge = Integer.parseInt(value);
                case "freeze" -> freeze = Boolean.parseBoolean(value);
                case "climbBlock" -> climbBlock = ResourceLocation.parse(value);
                case "climbSupport" -> climbSupport = ResourceLocation.parse(value);
                case "maxClimbHeight" -> maxClimbHeight = Integer.parseInt(value);
                default -> { /* ignore unknown keys */ }
            }
        }

        return new ParsedOverride(rl,
                new CropOverride(daysPerStage, seasons, topBlock, transformBlock, waterCompanion, doubleAge,
                        freeze, climbBlock, climbSupport, maxClimbHeight));
    }

    /**
     * Get the crop override for a given crop block, or null if none is configured.
     */
    public static CropOverride getOverride(ResourceLocation cropId) {
        return cachedOverrides.get(cropId);
    }

    /**
     * Get the per-crop {@code seasons=} override for a block, or null if none is
     * configured.
     *
     * <p>This feeds step 1 of the season resolution chain in
     * {@link SeasonTagResolver}: per-crop {@code seasons=} override → ES runtime
     * registry → block tags → configurable default for untagged crops.</p>
     *
     * @param block the crop block to resolve
     * @return an immutable set of seasons, or null when no override applies
     */
    @Nullable
    public static Set<Season> getOverrideSeasons(Block block) {
        ResourceLocation rl = BuiltInRegistries.BLOCK.getKey(block);
        if (rl == null) return null;
        CropOverride override = cachedOverrides.get(rl);
        return override != null ? override.seasons : null;
    }

    /**
     * Get the days-per-stage for a given crop, respecting overrides.
     */
    public static int getDaysPerStage(ResourceLocation cropId) {
        CropOverride override = cachedOverrides.get(cropId);
        return override != null ? override.daysPerStage : DEFAULT_DAYS_PER_STAGE.get();
    }

}