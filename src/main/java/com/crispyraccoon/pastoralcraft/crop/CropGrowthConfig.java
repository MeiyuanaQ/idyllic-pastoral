package com.crispyraccoon.pastoralcraft.crop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.crispyraccoon.pastoralcraft.PastoralCraft;
import com.teamtea.eclipticseasons.api.constant.solar.Season;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jetbrains.annotations.Nullable;

/**
 * Configuration for the PastoralCraft crop growth system.
 *
 * <p>The spec is grouped into four sections so the NeoForge config UI renders a
 * clean hierarchy instead of one flat list:</p>
 * <ul>
 *   <li>{@code [general]} — core growth / season / catch-up settings.</li>
 *   <li>{@code [stem]} — global defaults for melon/pumpkin stem fruiting.</li>
 *   <li>{@code [debug]} — the modular debug switches, aggregated in one place.</li>
 *   <li>{@code [crops]} — unified per-crop management: crops grouped by owning
 *       mod (vanilla = {@code minecraft}, each third-party mod its own category),
 *       plus a fallback {@code customOverrides} list for arbitrary crops.</li>
 * </ul>
 *
 * <p>Per-crop probability fields carry the global default they fall back to, both
 * in their TOML comment and their UI tooltip.</p>
 */
public class CropGrowthConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // =======================================================================
    // [general] — core growth, season and catch-up settings
    // =======================================================================

    static {
        BUILDER.comment("Core growth, season and catch-up settings.")
                .translation(translationKey("general"))
                .push("general");
    }

    /** Default number of solar days required per growth stage. */
    public static final ModConfigSpec.IntValue DEFAULT_DAYS_PER_STAGE = intCfg(
            "general.daysPerStage", 3, 0, 365,
            "Default number of solar days required for each crop growth stage.",
            "Lower values = faster growth. Set to 0 to disable the growth delay.");

    /** Chance that an arable crop in an unsuitable season mutates into short grass per growth attempt. */
    public static final ModConfigSpec.DoubleValue UNSUITABLE_MUTATE_CHANCE = doubleCfg(
            "general.unsuitableMutateChance", 0.20, 0.0, 1.0,
            "Global default: chance (0.0 - 1.0) that an arable crop in an unsuitable season",
            "mutates into short grass on each growth attempt. Deterministic per position +",
            "plantedDay + attempt index. Constraint: mutate + grow must be <= 1.0.");

    /** Chance that an arable crop in an unsuitable season grows one stage per growth attempt. */
    public static final ModConfigSpec.DoubleValue UNSUITABLE_GROW_CHANCE = doubleCfg(
            "general.unsuitableGrowChance", 0.40, 0.0, 1.0,
            "Global default: chance (0.0 - 1.0) that an arable crop in an unsuitable season grows",
            "one stage on each growth attempt. The remaining probability (1 - mutate - grow)",
            "results in no growth. The sum is clamped at runtime so 'no growth' stays reachable.");

    /**
     * Number of solar days per Solar Term (节气).
     * Ecliptic Seasons structure: 1 Year = 4 Seasons, 1 Season = 6 Solar Terms.
     * This must match the Ecliptic Seasons "LastingDaysOfEachTerm" setting (default 7 days).
     */
    public static final ModConfigSpec.IntValue CATCH_UP_SEASON_LENGTH = intCfg(
            "general.catchUpSeasonLength", 7, 1, 365,
            "Number of solar days per Solar Term (节气).",
            "Ecliptic Seasons: 1 Year = 4 Seasons, 1 Season = 6 Solar Terms.",
            "Must match the Ecliptic Seasons 'LastingDaysOfEachTerm' setting (default 7 days).");

    /**
     * Per-invocation time budget (milliseconds) for a single chunk-load or periodic
     * catch-up pass. When exceeded, remaining entries are deferred to the next cycle.
     */
    public static final ModConfigSpec.IntValue CATCH_UP_TIME_BUDGET_MS = intCfg(
            "general.catchUpTimeBudgetMs", 8, 0, 60000,
            "Per-invocation time budget (milliseconds) for a single chunk-load or periodic",
            "catch-up pass. When exceeded, remaining entries are deferred to the next cycle",
            "instead of blocking the server thread. Set to 0 to disable the budget.");

    /** Upper bound (solar days) on the elapsed time a single catch-up simulation may span. */
    public static final ModConfigSpec.IntValue MAX_CATCH_UP_ELAPSED_DAYS = intCfg(
            "general.maxCatchUpElapsedDays", 336, 1, 3650,
            "Upper bound (solar days) on the elapsed time a single catch-up simulation may span.",
            "Prevents the per-crop simulation loop from running unboundedly after a large",
            "Ecliptic Seasons day jump (the freeze bug). Default 336 = 2 ES years (168 days each).");

    /** Default seasons for crops with no season information at all. */
    public static final ModConfigSpec.ConfigValue<String> DEFAULT_UNTAGGED_SEASONS = stringCfg(
            "general.defaultUntaggedSeasons", "spring,autumn", CropGrowthConfig::validateSeasonString,
            "Default seasons for crops with no Ecliptic Seasons season info.",
            "Applies to blocks neither in the ES runtime registry nor in any crop season",
            "block tag (e.g. most mod-added crops).",
            "Format: comma-separated list of spring/summer/autumn/winter, or 'year_round'.");

    static {
        BUILDER.pop();
    }

    // =======================================================================
    // [stem] — global defaults for melon/pumpkin stem fruiting
    // =======================================================================

    static {
        BUILDER.comment("Global defaults for melon/pumpkin stem fruiting. Individual stems can",
                        "override these in their [crops] sub-section.")
                .translation(translationKey("stem"))
                .push("stem");
    }

    /** Number of solar days between StemBlock fruiting cycles (pumpkin/melon spawning). */
    public static final ModConfigSpec.IntValue DAYS_PER_FRUIT = intCfg(
            "stem.daysPerFruit", 3, 1, 365,
            "Global default: solar days between each fruiting cycle of a mature stem.",
            "After a stem reaches MAX_AGE, it attempts to spawn fruit every this many",
            "days while in a suitable season.");

    /** Allowed horizontal directions for melon/pumpkin stems to grow fruit. */
    public static final ModConfigSpec.ConfigValue<String> STEM_FRUIT_DIRECTIONS = stringCfg(
            "stem.fruitDirections", "north,east", CropGrowthConfig::validateDirections,
            "Global default: comma-separated horizontal directions a mature stem may fruit toward.",
            "Valid values: north, south, east, west (e.g. \"north,east\").",
            "The order defines the deterministic direction check order.");

    /** Chance that a mature stem mutates into short grass per fruiting cycle in unsuitable season. */
    public static final ModConfigSpec.DoubleValue STEM_UNSUITABLE_MUTATE_CHANCE = doubleCfg(
            "stem.unsuitableMutateChance", 0.20, 0.0, 1.0,
            "Global default: chance (0.0 - 1.0) that a stem (any maturity) in an unsuitable",
            "season mutates into short grass on each fruiting cycle.");

    /** Chance that a mature stem fruits per fruiting cycle in unsuitable season. */
    public static final ModConfigSpec.DoubleValue STEM_UNSUITABLE_FRUIT_CHANCE = doubleCfg(
            "stem.unsuitableFruitChance", 0.20, 0.0, 1.0,
            "Global default: chance (0.0 - 1.0) that a MATURE stem in an unsuitable season produces",
            "a fruit on each fruiting cycle. Mutate, fruit, and idle form the mature-stem roll.");

    /** Chance that an immature stem grows one stage per fruiting cycle in unsuitable season. */
    public static final ModConfigSpec.DoubleValue STEM_UNSUITABLE_GROW_CHANCE = doubleCfg(
            "stem.unsuitableGrowChance", 0.0, 0.0, 1.0,
            "Global default: chance (0.0 - 1.0) that an IMMATURE stem in an unsuitable season",
            "grows one stage on each fruiting cycle. Mutate, grow, and idle form the immature-stem",
            "roll. Default 0.0 preserves the classic behavior (immature stems never grow off-season).");

    static {
        BUILDER.pop();
    }

    // =======================================================================
    // [debug] — modular debug switches (all default-off; see DebugGate)
    // =======================================================================

    static {
        BUILDER.comment("Modular debug switches. Every subsystem is independently switchable;",
                        "the module switches additionally require 'logging' to be enabled (see DebugGate).")
                .translation(translationKey("debug"))
                .push("debug");
    }

    /** Master debug logging switch. */
    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = boolCfg(
            "debug.logging", false,
            "Master switch for crop growth debug logging.");

    /** Detailed per-block state tracing for supplementaries:flax. */
    public static final ModConfigSpec.BooleanValue DEBUG_FLAX_ALL = boolCfg(
            "debug.flaxAll", false,
            "Log every supplementaries:flax state change (both halves) to debug.log.",
            "Requires 'logging' to also be true.");

    /** Enable growth-path debug logging. */
    public static final ModConfigSpec.BooleanValue DEBUG_GROWTH = boolCfg(
            "debug.growth", false,
            "Log crop/height-crop growth, harvest, bonemeal, and entry add/remove.");

    /** Enable stem debug logging. */
    public static final ModConfigSpec.BooleanValue DEBUG_STEM = boolCfg(
            "debug.stem", false,
            "Log stem fruiting, mutation, bonemeal, and harvest.");

    /** Enable maturity side-effect debug logging. */
    public static final ModConfigSpec.BooleanValue DEBUG_SIDE_EFFECT = boolCfg(
            "debug.sideEffect", false,
            "Log maturity side effects (transform/companion/bonemeal fallback/double upper half).");

    /** Enable catch-up summary debug logging. */
    public static final ModConfigSpec.BooleanValue DEBUG_CATCH_UP = boolCfg(
            "debug.catchUp", false,
            "Log chunk-load/periodic catch-up summaries and NBT save/load counts.");

    /** Enable crop-data health scans. */
    public static final ModConfigSpec.BooleanValue DEBUG_DATA = boolCfg(
            "debug.data", false,
            "Scan plantedDay anomalies (negative/over-horizon) on catch-up entry.");

    /** Enable performance profiling. */
    public static final ModConfigSpec.BooleanValue DEBUG_PERF = boolCfg(
            "debug.perf", false,
            "Enable timed sections and a setBlock counter to locate slow paths.");

    /** Enable the in-memory ring event buffer. */
    public static final ModConfigSpec.BooleanValue DEBUG_RING = boolCfg(
            "debug.ring", false,
            "Record events into an in-memory ring buffer for crash/freeze backtrace.");

    /** Register the /pastoralcraft debug commands. */
    public static final ModConfigSpec.BooleanValue DEBUG_COMMANDS = boolCfg(
            "debug.commands", false,
            "Register the /pastoralcraft debug commands (status/dump/reset).");

    /** Slow-path warning threshold in milliseconds (debugPerf). */
    public static final ModConfigSpec.IntValue DEBUG_PERF_WARN_MS = intCfg(
            "debug.perfWarnMs", 10, 1, 60000,
            "Slow-path warning threshold in milliseconds (used by debug.perf).");

    /** Capacity of the debug ring buffer. */
    public static final ModConfigSpec.IntValue DEBUG_RING_SIZE = intCfg(
            "debug.ringSize", 2048, 256, 65536,
            "Capacity of the debug ring buffer.");

    /** Write a debug dump file on shutdown. */
    public static final ModConfigSpec.BooleanValue DEBUG_DUMP_ON_STOP = boolCfg(
            "debug.dumpOnStop", true,
            "Write the ring buffer/profiler/data-health to the logs/ directory on server stop.");

    static {
        BUILDER.pop();
    }

    // =======================================================================
    // [crops] — unified per-crop management, grouped by owning mod
    // =======================================================================

    static {
        BUILDER.comment("Unified per-crop management. Crops are grouped by owning mod (vanilla",
                        "is 'minecraft'); each crop has a dedicated sub-section with only its",
                        "mechanism-relevant fields. Structural fields default to 'unset' and defer",
                        "to the crop_structure data map. The 'customOverrides' list is a fallback",
                        "for arbitrary crops not covered by a dedicated sub-section.")
                .translation(translationKey("crops"))
                .push("crops");
    }

    /**
     * Fallback per-crop override configuration for arbitrary crops (not covered by a
     * dedicated curated sub-section above).
     *
     * <p>Format: {@code "modid:crop_id=key=value,key2=value2,..."}. Supported keys:
     * {@code daysPerStage}, {@code seasons}, {@code topBlock}, {@code transformBlock},
     * {@code water}, {@code doubleAge}, {@code freeze}, {@code climbBlock},
     * {@code climbSupport}, {@code maxClimbHeight}, {@code daysPerFruit},
     * {@code fruitDirections}, {@code stemMutateChance}, {@code stemFruitChance},
     * {@code stemGrowChance}, {@code unsuitableMutateChance}, {@code unsuitableGrowChance}.</p>
     */
    private static final ModConfigSpec.ConfigValue<List<? extends String>> CROP_OVERRIDE_STRINGS = BUILDER
            .comment("Per-crop growth overrides for arbitrary crops.",
                    "Format: \"modid:crop_id=key=value,key2=value2,...\"",
                    "Supported keys: daysPerStage, seasons, topBlock, transformBlock, water, doubleAge,",
                    "              freeze, climbBlock, climbSupport, maxClimbHeight,",
                    "              daysPerFruit, fruitDirections, stemMutateChance, stemFruitChance,",
                    "              stemGrowChance, unsuitableMutateChance, unsuitableGrowChance.",
                    "Entries targeting a crop that has a dedicated sub-section above are ignored.")
            .translation(translationKey("crops.customOverrides"))
            .defineListAllowEmpty("customOverrides", List.of(), () -> "", CropGrowthConfig::validateOverrideString);

    /** The curated crop pages with dedicated config sub-sections. */
    private static final List<CropPage> CURATED_PAGES = defineCuratedCrops();

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Number of Solar Terms per Season (fixed structure of Ecliptic Seasons). */
    public static final int SOLAR_TERMS_PER_SEASON = 6;

    /**
     * Get the number of solar days in one full season.
     * One season = {@code SOLAR_TERMS_PER_SEASON} solar terms.
     */
    public static int getSeasonLength() {
        return CATCH_UP_SEASON_LENGTH.get() * SOLAR_TERMS_PER_SEASON;
    }

    // =======================================================================
    // Curated crop sections (mechanism-specific fields)
    // =======================================================================

    /** A crop's off-season behavior kind, which decides which chance fields it exposes. */
    private enum Kind {
        /** Arable: unsuitable-season roll can mutate to grass and/or grow one stage. */
        ARABLE,
        /** Non-arable: freezes entirely in unsuitable seasons (no mutation, no growth). */
        FREEZE,
        /** Stem: fruiting/mutation/growth driven by the stem-specific roll fields. */
        STEM
    }

    /** A curated crop page produces the runtime overrides for one or more blocks. */
    private interface CropPage {
        Map<ResourceLocation, CropOverride> buildOverrides();
    }

    /**
     * A standard curated crop page: one or more {@code ResourceLocation} block ids
     * sharing the same behavior fields (e.g. FD rice + rice_panicles merged).
     */
    private static final class CuratedCrop implements CropPage {
        final List<ResourceLocation> cropIds;
        final ModConfigSpec.IntValue daysPerStage;
        final ModConfigSpec.ConfigValue<String> seasons;
        final ModConfigSpec.DoubleValue unsuitableMutateChance;
        final ModConfigSpec.DoubleValue unsuitableGrowChance;
        final ModConfigSpec.IntValue daysPerFruit;
        final ModConfigSpec.ConfigValue<String> fruitDirections;
        final ModConfigSpec.DoubleValue stemMutateChance;
        final ModConfigSpec.DoubleValue stemFruitChance;
        final ModConfigSpec.DoubleValue stemGrowChance;

        CuratedCrop(List<ResourceLocation> cropIds,
                    ModConfigSpec.IntValue daysPerStage,
                    ModConfigSpec.ConfigValue<String> seasons,
                    ModConfigSpec.DoubleValue unsuitableMutateChance,
                    ModConfigSpec.DoubleValue unsuitableGrowChance,
                    ModConfigSpec.IntValue daysPerFruit,
                    ModConfigSpec.ConfigValue<String> fruitDirections,
                    ModConfigSpec.DoubleValue stemMutateChance,
                    ModConfigSpec.DoubleValue stemFruitChance,
                    ModConfigSpec.DoubleValue stemGrowChance) {
            this.cropIds = cropIds;
            this.daysPerStage = daysPerStage;
            this.seasons = seasons;
            this.unsuitableMutateChance = unsuitableMutateChance;
            this.unsuitableGrowChance = unsuitableGrowChance;
            this.daysPerFruit = daysPerFruit;
            this.fruitDirections = fruitDirections;
            this.stemMutateChance = stemMutateChance;
            this.stemFruitChance = stemFruitChance;
            this.stemGrowChance = stemGrowChance;
        }

        @Override
        public Map<ResourceLocation, CropOverride> buildOverrides() {
            int d = daysPerStage.get();
            Set<Season> s = parseSeasons(seasons.get());
            Map<ResourceLocation, CropOverride> map = new HashMap<>();
            for (ResourceLocation id : cropIds) {
                CropOverride.Builder b = CropOverride.builder(d).seasons(s);
                if (daysPerFruit != null) {
                    b.daysPerFruit(daysPerFruit.get())
                            .fruitDirections(blankToNull(fruitDirections.get()))
                            .stemChances(stemMutateChance.get(), stemFruitChance.get(), stemGrowChance.get());
                } else if (unsuitableMutateChance != null) {
                    b.unsuitableChances(unsuitableMutateChance.get(), unsuitableGrowChance.get());
                }
                map.put(id, b.build());
            }
            return map;
        }
    }

    /**
     * The Farmers Delight tomato lifecycle consolidated into a single page covering
     * {@code budding_tomatoes} → {@code tomatoes} → {@code tomatoes_on_rope}.
     * Structure (transform/climb/freeze) comes from the crop_structure data map.
     */
    private static final class TomatoCrop implements CropPage {
        final ModConfigSpec.IntValue buddingDays;
        final ModConfigSpec.IntValue tomatoDays;
        final ModConfigSpec.IntValue onRopeDays;
        final ModConfigSpec.ConfigValue<String> seasons;

        TomatoCrop(ModConfigSpec.IntValue buddingDays,
                   ModConfigSpec.IntValue tomatoDays,
                   ModConfigSpec.IntValue onRopeDays,
                   ModConfigSpec.ConfigValue<String> seasons) {
            this.buddingDays = buddingDays;
            this.tomatoDays = tomatoDays;
            this.onRopeDays = onRopeDays;
            this.seasons = seasons;
        }

        @Override
        public Map<ResourceLocation, CropOverride> buildOverrides() {
            Map<ResourceLocation, CropOverride> map = new HashMap<>();
            Set<Season> s = parseSeasons(seasons.get());
            map.put(ResourceLocation.parse("farmersdelight:budding_tomatoes"),
                    CropOverride.builder(buddingDays.get()).seasons(s).build());
            map.put(ResourceLocation.parse("farmersdelight:tomatoes"),
                    CropOverride.builder(tomatoDays.get()).seasons(s).build());
            map.put(ResourceLocation.parse("farmersdelight:tomatoes_on_rope"),
                    CropOverride.builder(onRopeDays.get()).seasons(s).build());
            return map;
        }
    }

    /**
     * Define the curated crop sub-sections (inside the open {@code [crops]} push),
     * grouped by owning mod, and return their holders. Called once during static
     * init, before {@link #SPEC} is built.
     */
    private static List<CropPage> defineCuratedCrops() {
        List<CropPage> pages = new ArrayList<>();

        // ---- vanilla (minecraft) ----
        pushMod("minecraft", "Vanilla crops.");
        pages.add(crop("minecraft", "wheat", Kind.ARABLE, 3, "", "minecraft:wheat"));
        pages.add(crop("minecraft", "carrots", Kind.ARABLE, 3, "", "minecraft:carrots"));
        pages.add(crop("minecraft", "potatoes", Kind.ARABLE, 3, "", "minecraft:potatoes"));
        pages.add(crop("minecraft", "beetroots", Kind.ARABLE, 3, "", "minecraft:beetroots"));
        pages.add(crop("minecraft", "torchflower", Kind.ARABLE, 3, "", "minecraft:torchflower"));
        pages.add(crop("minecraft", "pitcher_crop", Kind.ARABLE, 3, "", "minecraft:pitcher_crop"));
        pages.add(crop("minecraft", "nether_wart", Kind.FREEZE, 3, "", "minecraft:nether_wart"));
        pages.add(crop("minecraft", "cocoa", Kind.FREEZE, 3, "", "minecraft:cocoa"));
        pages.add(crop("minecraft", "sweet_berry_bush", Kind.ARABLE, 3, "", "minecraft:sweet_berry_bush"));
        pages.add(crop("minecraft", "sugar_cane", Kind.FREEZE, 3, "", "minecraft:sugar_cane"));
        pages.add(crop("minecraft", "cactus", Kind.FREEZE, 3, "", "minecraft:cactus"));
        pages.add(crop("minecraft", "kelp", Kind.FREEZE, 3, "", "minecraft:kelp"));
        pages.add(crop("minecraft", "melon_stem", Kind.STEM, 3, "", "minecraft:melon_stem"));
        pages.add(crop("minecraft", "pumpkin_stem", Kind.STEM, 3, "", "minecraft:pumpkin_stem"));
        popMod();

        // ---- Farmers Delight ----
        pushMod("farmersdelight", "Farmers Delight crops.");
        pages.add(crop("farmersdelight", "cabbages", Kind.ARABLE, 3, "", "farmersdelight:cabbages"));
        pages.add(crop("farmersdelight", "onions", Kind.ARABLE, 3, "", "farmersdelight:onions"));
        pages.add(tomato());
        pages.add(crop("farmersdelight", "rice", Kind.FREEZE, 3, "",
                "farmersdelight:rice", "farmersdelight:rice_panicles"));
        popMod();

        // ---- Supplementaries ----
        pushMod("supplementaries", "Supplementaries crops.");
        pages.add(crop("supplementaries", "flax", Kind.FREEZE, 3, "", "supplementaries:flax"));
        popMod();

        // ---- Adorable Hamster Pets ----
        pushMod("adorablehamsterpets", "Adorable Hamster Pets crops.");
        pages.add(crop("adorablehamsterpets", "sunflower_block", Kind.FREEZE, 3, "spring_autumn",
                "adorablehamsterpets:sunflower_block"));
        pages.add(crop("adorablehamsterpets", "cucumber_crop", Kind.ARABLE, 3, "", "adorablehamsterpets:cucumber_crop"));
        pages.add(crop("adorablehamsterpets", "green_beans_crop", Kind.ARABLE, 3, "", "adorablehamsterpets:green_beans_crop"));
        popMod();

        // ---- Kaleidoscope Cookery ----
        pushMod("kaleidoscope_cookery", "Kaleidoscope Cookery crops.");
        pages.add(crop("kaleidoscope_cookery", "tomato_crop", Kind.ARABLE, 3, "", "kaleidoscope_cookery:tomato_crop"));
        pages.add(crop("kaleidoscope_cookery", "chili_crop", Kind.ARABLE, 3, "", "kaleidoscope_cookery:chili_crop"));
        pages.add(crop("kaleidoscope_cookery", "lettuce_crop", Kind.ARABLE, 3, "", "kaleidoscope_cookery:lettuce_crop"));
        pages.add(crop("kaleidoscope_cookery", "rice_crop", Kind.FREEZE, 3, "", "kaleidoscope_cookery:rice_crop"));
        popMod();

        return pages;
    }

    private static void pushMod(String modId, String comment) {
        BUILDER.comment(comment)
                .translation(translationKey("crops." + modId))
                .push(modId);
    }

    private static void popMod() {
        BUILDER.pop();
    }

    /**
     * Push a crop sub-section, define its behavior fields (mechanism-dependent), pop
     * back to the mod-category level, and return the holder. One page may cover
     * multiple block ids sharing the same fields (e.g. FD rice + rice_panicles).
     */
    private static CuratedCrop crop(String modId, String cropKey, Kind kind, int daysPerStage,
                                    String seasons, String... cropIds) {
        String path = "crops." + modId + "." + cropKey;
        BUILDER.comment("Dedicated config for " + String.join(", ", cropIds) + ".")
                .translation(translationKey(path))
                .push(cropKey);

        ModConfigSpec.IntValue d = leafInt("daysPerStage", daysPerStage, 0, 365,
                "Solar days per growth stage for this crop.");
        ModConfigSpec.ConfigValue<String> s = leafString("seasons", seasons,
                CropGrowthConfig::validateSeasonString,
                "Override suitable seasons ('spring,autumn' / 'year_round');",
                "empty = use the normal resolution chain.");

        ModConfigSpec.DoubleValue mutate = null;
        ModConfigSpec.DoubleValue grow = null;
        ModConfigSpec.IntValue daysPerFruit = null;
        ModConfigSpec.ConfigValue<String> fruitDirections = null;
        ModConfigSpec.DoubleValue stemMutateChance = null;
        ModConfigSpec.DoubleValue stemFruitChance = null;
        ModConfigSpec.DoubleValue stemGrowChance = null;

        switch (kind) {
            case ARABLE -> {
                mutate = leafDouble("unsuitableMutateChance", -1.0, -1.0, 1.0,
                        "Chance this crop mutates to short grass per unsuitable-season attempt.",
                        "-1 = use the global default (0.20).");
                grow = leafDouble("unsuitableGrowChance", -1.0, -1.0, 1.0,
                        "Chance this crop grows one stage per unsuitable-season attempt.",
                        "-1 = use the global default (0.40).");
            }
            case FREEZE -> { /* freezes entirely; no chance fields (annotated in the section tooltip) */ }
            case STEM -> {
                daysPerFruit = leafInt("daysPerFruit", 0, 0, 365,
                        "Days between fruiting cycles (0 = use the global stem default, 3).");
                fruitDirections = leafString("fruitDirections", "",
                        CropGrowthConfig::validateDirections,
                        "Fruiting directions (empty = use the global stem default, north,east).");
                stemMutateChance = leafDouble("stemMutateChance", -1.0, -1.0, 1.0,
                        "Stem unsuitable-season mutate chance (-1 = use the global stem default, 0.20).");
                stemFruitChance = leafDouble("stemFruitChance", -1.0, -1.0, 1.0,
                        "Mature-stem unsuitable-season fruit chance (-1 = use the global stem default, 0.20).");
                stemGrowChance = leafDouble("stemGrowChance", -1.0, -1.0, 1.0,
                        "Immature-stem unsuitable-season grow chance (-1 = use the global stem default, 0.00).");
            }
        }

        BUILDER.pop();
        List<ResourceLocation> ids = new ArrayList<>(cropIds.length);
        for (String id : cropIds) ids.add(ResourceLocation.parse(id));
        return new CuratedCrop(ids, d, s, mutate, grow, daysPerFruit, fruitDirections,
                stemMutateChance, stemFruitChance, stemGrowChance);
    }

    /** Push the consolidated Farmers Delight tomato page (3 blocks in one). */
    private static TomatoCrop tomato() {
        String path = "crops.farmersdelight.tomato";
        BUILDER.comment("Farmers Delight tomato lifecycle: budding_tomatoes -> tomatoes -> tomatoes_on_rope.",
                        "Freezes entirely in unsuitable seasons (no mutation or growth).")
                .translation(translationKey(path))
                .push("tomato");

        ModConfigSpec.IntValue buddingDays = leafInt("buddingDaysPerStage", 1, 0, 365,
                "Days per stage for budding_tomatoes (default 1).");
        ModConfigSpec.IntValue tomatoDays = leafInt("tomatoDaysPerStage", 3, 0, 365,
                "Days per stage for tomatoes (default 3).");
        ModConfigSpec.IntValue onRopeDays = leafInt("tomatoOnRopeDaysPerStage", 3, 0, 365,
                "Days per stage for tomatoes_on_rope (default 3).");
        ModConfigSpec.ConfigValue<String> seasons = leafString("seasons", "",
                CropGrowthConfig::validateSeasonString,
                "Override suitable seasons ('spring,autumn' / 'year_round');",
                "empty = use the normal resolution chain.");

        BUILDER.pop();
        return new TomatoCrop(buddingDays, tomatoDays, onRopeDays, seasons);
    }

    // =======================================================================
    // Builder helpers
    // =======================================================================

    private static String translationKey(String path) {
        return "pastoralcraft.configuration." + path;
    }

    private static String shortKey(String path) {
        int i = path.lastIndexOf('.');
        return i < 0 ? path : path.substring(i + 1);
    }

    private static ModConfigSpec.IntValue intCfg(String path, int def, int min, int max, String... comment) {
        return BUILDER.comment(comment).translation(translationKey(path)).defineInRange(shortKey(path), def, min, max);
    }

    private static ModConfigSpec.DoubleValue doubleCfg(String path, double def, double min, double max, String... comment) {
        return BUILDER.comment(comment).translation(translationKey(path)).defineInRange(shortKey(path), def, min, max);
    }

    private static ModConfigSpec.BooleanValue boolCfg(String path, boolean def, String... comment) {
        return BUILDER.comment(comment).translation(translationKey(path)).define(shortKey(path), def);
    }

    private static ModConfigSpec.ConfigValue<String> stringCfg(String path, String def,
                                                               Predicate<Object> validator, String... comment) {
        return BUILDER.comment(comment).translation(translationKey(path)).define(shortKey(path), def, validator);
    }

    // Leaf helpers used inside a crop sub-section. Their short key is globally unique
    // among crop leaves, so the generic config UI derives one shared translation key
    // per field name instead of one per crop.

    private static ModConfigSpec.IntValue leafInt(String name, int def, int min, int max, String... comment) {
        return BUILDER.comment(comment).defineInRange(name, def, min, max);
    }

    private static ModConfigSpec.DoubleValue leafDouble(String name, double def, double min, double max, String... comment) {
        return BUILDER.comment(comment).defineInRange(name, def, min, max);
    }

    private static ModConfigSpec.ConfigValue<String> leafString(String name, String def,
                                                                Predicate<Object> validator, String... comment) {
        return BUILDER.comment(comment).define(name, def, validator);
    }

    // =======================================================================
    // Runtime cache + resolution
    // =======================================================================

    // Cached parsed overrides for fast lookup
    private static Map<ResourceLocation, CropOverride> cachedOverrides = Map.of();

    /**
     * A per-crop override configuration entry.
     *
     * <p>Behavior keys ({@code daysPerStage}, {@code seasons}, {@code freeze}),
     * structural keys ({@code doubleAge}, {@code topBlock}, {@code transformBlock},
     * {@code climbBlock}, {@code climbSupport}, {@code maxClimbHeight}), arable
     * chance keys ({@code unsuitableMutateChance}, {@code unsuitableGrowChance}) and
     * stem keys ({@code daysPerFruit}, {@code fruitDirections},
     * {@code stemUnsuitableMutateChance}, {@code stemUnsuitableFruitChance},
     * {@code stemUnsuitableGrowChance}).</p>
     *
     * <p>Chance keys use {@code -1.0}/{@code null}/{@code 0} sentinels meaning "unset —
     * fall back to the global default", resolved via the {@code get*Chance} accessors.</p>
     */
    public static final class CropOverride {
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
        /** STEM: days between fruiting cycles; {@code 0} = unset (use global default). */
        public final int daysPerFruit;
        /** STEM: fruiting directions string; {@code null} = unset (use global default). */
        @Nullable public final String fruitDirections;
        /** STEM: unsuitable-season mutate chance; {@code -1.0} = unset (use global default). */
        public final double stemUnsuitableMutateChance;
        /** STEM: mature-stem unsuitable-season fruit chance; {@code -1.0} = unset (use global default). */
        public final double stemUnsuitableFruitChance;
        /** STEM: immature-stem unsuitable-season grow chance; {@code -1.0} = unset (use global default). */
        public final double stemUnsuitableGrowChance;
        /** Arable: unsuitable-season mutate chance; {@code -1.0} = unset (use global default). */
        public final double unsuitableMutateChance;
        /** Arable: unsuitable-season grow chance; {@code -1.0} = unset (use global default). */
        public final double unsuitableGrowChance;

        private CropOverride(Builder b) {
            this.daysPerStage = b.daysPerStage;
            this.seasons = b.seasons;
            this.topBlock = b.topBlock;
            this.transformBlock = b.transformBlock;
            this.waterCompanion = b.waterCompanion;
            this.doubleAge = b.doubleAge;
            this.freeze = b.freeze;
            this.climbBlock = b.climbBlock;
            this.climbSupport = b.climbSupport;
            this.maxClimbHeight = b.maxClimbHeight;
            this.daysPerFruit = b.daysPerFruit;
            this.fruitDirections = b.fruitDirections;
            this.stemUnsuitableMutateChance = b.stemUnsuitableMutateChance;
            this.stemUnsuitableFruitChance = b.stemUnsuitableFruitChance;
            this.stemUnsuitableGrowChance = b.stemUnsuitableGrowChance;
            this.unsuitableMutateChance = b.unsuitableMutateChance;
            this.unsuitableGrowChance = b.unsuitableGrowChance;
        }

        /** Convenience constructor for non-stem crops; chance/stem keys default to "unset". */
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
            this(builder(daysPerStage).seasons(seasons).topBlock(topBlock).transformBlock(transformBlock)
                    .waterCompanion(waterCompanion).doubleAge(doubleAge).freeze(freeze)
                    .climbBlock(climbBlock).climbSupport(climbSupport).maxClimbHeight(maxClimbHeight));
        }

        public static Builder builder(int daysPerStage) {
            return new Builder(daysPerStage);
        }

        /** Fluent builder for the 17-field override. */
        public static final class Builder {
            int daysPerStage;
            @Nullable Set<Season> seasons;
            @Nullable ResourceLocation topBlock;
            @Nullable ResourceLocation transformBlock;
            boolean waterCompanion;
            int doubleAge = -1;
            boolean freeze;
            @Nullable ResourceLocation climbBlock;
            @Nullable ResourceLocation climbSupport;
            int maxClimbHeight;
            int daysPerFruit;
            @Nullable String fruitDirections;
            double stemUnsuitableMutateChance = -1.0;
            double stemUnsuitableFruitChance = -1.0;
            double stemUnsuitableGrowChance = -1.0;
            double unsuitableMutateChance = -1.0;
            double unsuitableGrowChance = -1.0;

            Builder(int daysPerStage) {
                this.daysPerStage = daysPerStage;
            }

            public Builder daysPerStage(int v) { this.daysPerStage = v; return this; }
            public Builder seasons(@Nullable Set<Season> v) { this.seasons = v; return this; }
            public Builder topBlock(@Nullable ResourceLocation v) { this.topBlock = v; return this; }
            public Builder transformBlock(@Nullable ResourceLocation v) { this.transformBlock = v; return this; }
            public Builder waterCompanion(boolean v) { this.waterCompanion = v; return this; }
            public Builder doubleAge(int v) { this.doubleAge = v; return this; }
            public Builder freeze(boolean v) { this.freeze = v; return this; }
            public Builder climbBlock(@Nullable ResourceLocation v) { this.climbBlock = v; return this; }
            public Builder climbSupport(@Nullable ResourceLocation v) { this.climbSupport = v; return this; }
            public Builder maxClimbHeight(int v) { this.maxClimbHeight = v; return this; }
            public Builder daysPerFruit(int v) { this.daysPerFruit = v; return this; }
            public Builder fruitDirections(@Nullable String v) { this.fruitDirections = v; return this; }
            public Builder stemMutateChance(double v) { this.stemUnsuitableMutateChance = v; return this; }
            public Builder stemFruitChance(double v) { this.stemUnsuitableFruitChance = v; return this; }
            public Builder stemGrowChance(double v) { this.stemUnsuitableGrowChance = v; return this; }
            public Builder unsuitableMutateChance(double v) { this.unsuitableMutateChance = v; return this; }
            public Builder unsuitableGrowChance(double v) { this.unsuitableGrowChance = v; return this; }
            public Builder stemChances(double mutate, double fruit, double grow) {
                this.stemUnsuitableMutateChance = mutate;
                this.stemUnsuitableFruitChance = fruit;
                this.stemUnsuitableGrowChance = grow;
                return this;
            }
            public Builder unsuitableChances(double mutate, double grow) {
                this.unsuitableMutateChance = mutate;
                this.unsuitableGrowChance = grow;
                return this;
            }

            public CropOverride build() {
                return new CropOverride(this);
            }
        }
    }

    private static boolean validateOverrideString(final Object obj) {
        if (!(obj instanceof String s)) return false;
        // Format: "modid:crop_id=key=value,..."
        if (!s.contains("=")) return false;
        String[] parts = s.split("=", 2);
        if (parts.length < 2) return false;
        return ResourceLocation.tryParse(parts[0]) != null;
    }

    private static boolean validateSeasonString(final Object obj) {
        if (!(obj instanceof String s)) return false;
        String trimmed = s.trim().toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.equals("year_round") || trimmed.equals("all")) return true;
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

    @Nullable
    private static Set<Season> parseSeasons(String s) {
        String trimmed = s == null ? "" : s.trim();
        return trimmed.isEmpty() ? null : SeasonTagResolver.parseDefaultUntaggedSeasons(trimmed);
    }

    @Nullable
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Rebuild the cached override map from the curated crop pages plus the fallback
     * custom override strings. Called after config is loaded/reloaded.
     */
    public static void refreshOverrides() {
        Map<ResourceLocation, CropOverride> map = new HashMap<>();

        // 1. Curated crop pages (typed per-crop sub-sections).
        for (CropPage page : CURATED_PAGES) {
            try {
                map.putAll(page.buildOverrides());
            } catch (Exception e) {
                PastoralCraft.LOGGER.warn("Failed to build a curated crop config page: {}", e.toString());
            }
        }

        // 2. Custom string overrides (fallback for arbitrary crops); skip curated crops.
        for (String entry : CROP_OVERRIDE_STRINGS.get()) {
            try {
                ParsedOverride parsed = parseOverride(entry);
                if (parsed == null) continue;
                if (map.containsKey(parsed.cropId())) {
                    PastoralCraft.LOGGER.warn("Custom crop override '{}' ignored: {} has a dedicated config section.",
                            entry, parsed.cropId());
                    continue;
                }
                map.put(parsed.cropId(), parsed.override());
            } catch (Exception e) {
                PastoralCraft.LOGGER.warn("Failed to parse crop override: {}", entry, e);
            }
        }

        cachedOverrides = Map.copyOf(map);
        CropGrowthTracker.clearFreezeCache();
        // Structural descriptors merge config structure keys, so a config reload
        // must invalidate the merged-structure cache too.
        CropStructureRegistry.clearCache();

        // Republish the cached debug gate bitmask so hot config reloads are
        // reflected without a restart.
        DebugGate.refreshCache();

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
        CropOverride.Builder b = CropOverride.builder(defaultDaysPerStage);

        String[] paramParts = params.split(",");
        for (String part : paramParts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int kv = p.indexOf('=');
            if (kv < 0) continue;
            String key = p.substring(0, kv);
            String value = p.substring(kv + 1);
            switch (key) {
                case "daysPerStage" -> b.daysPerStage(Integer.parseInt(value));
                case "seasons" -> b.seasons(SeasonTagResolver.parseDefaultUntaggedSeasons(value));
                case "topBlock" -> b.topBlock(ResourceLocation.parse(value));
                case "transformBlock" -> b.transformBlock(ResourceLocation.parse(value));
                case "water" -> b.waterCompanion(Boolean.parseBoolean(value));
                case "doubleAge" -> b.doubleAge(Integer.parseInt(value));
                case "freeze" -> b.freeze(Boolean.parseBoolean(value));
                case "climbBlock" -> b.climbBlock(ResourceLocation.parse(value));
                case "climbSupport" -> b.climbSupport(ResourceLocation.parse(value));
                case "maxClimbHeight" -> b.maxClimbHeight(Integer.parseInt(value));
                case "daysPerFruit" -> b.daysPerFruit(Integer.parseInt(value));
                case "fruitDirections" -> b.fruitDirections(value);
                case "stemMutateChance" -> b.stemMutateChance(Double.parseDouble(value));
                case "stemFruitChance" -> b.stemFruitChance(Double.parseDouble(value));
                case "stemGrowChance" -> b.stemGrowChance(Double.parseDouble(value));
                case "unsuitableMutateChance" -> b.unsuitableMutateChance(Double.parseDouble(value));
                case "unsuitableGrowChance" -> b.unsuitableGrowChance(Double.parseDouble(value));
                default -> { /* ignore unknown keys */ }
            }
        }

        return new ParsedOverride(rl, b.build());
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

    /**
     * Get the unsuitable-season mutate chance for an arable crop, falling back to the
     * global {@code [general]} default when unset.
     */
    public static double getUnsuitableMutateChance(ResourceLocation cropId) {
        CropOverride override = cachedOverrides.get(cropId);
        return (override != null && override.unsuitableMutateChance >= 0.0)
                ? override.unsuitableMutateChance : UNSUITABLE_MUTATE_CHANCE.get();
    }

    /**
     * Get the unsuitable-season grow chance for an arable crop, falling back to the
     * global {@code [general]} default when unset.
     */
    public static double getUnsuitableGrowChance(ResourceLocation cropId) {
        CropOverride override = cachedOverrides.get(cropId);
        return (override != null && override.unsuitableGrowChance >= 0.0)
                ? override.unsuitableGrowChance : UNSUITABLE_GROW_CHANCE.get();
    }

    /**
     * Get the days-per-fruit for a stem crop, falling back to the global
     * {@code [stem]} default when the crop has no per-stem override.
     */
    public static int getDaysPerFruit(ResourceLocation cropId) {
        CropOverride override = cachedOverrides.get(cropId);
        return (override != null && override.daysPerFruit > 0)
                ? override.daysPerFruit : DAYS_PER_FRUIT.get();
    }

    /**
     * Get the fruiting-directions string for a stem crop, falling back to the global
     * {@code [stem]} default when the crop has no per-stem override.
     */
    public static String getFruitDirections(ResourceLocation cropId) {
        CropOverride override = cachedOverrides.get(cropId);
        return (override != null && override.fruitDirections != null && !override.fruitDirections.isBlank())
                ? override.fruitDirections : STEM_FRUIT_DIRECTIONS.get();
    }

    /**
     * Get the unsuitable-season mutate chance for a stem crop, falling back to the
     * global {@code [stem]} default when unset.
     */
    public static double getStemUnsuitableMutateChance(ResourceLocation cropId) {
        CropOverride override = cachedOverrides.get(cropId);
        return (override != null && override.stemUnsuitableMutateChance >= 0.0)
                ? override.stemUnsuitableMutateChance : STEM_UNSUITABLE_MUTATE_CHANCE.get();
    }

    /**
     * Get the mature-stem unsuitable-season fruit chance, falling back to the global
     * {@code [stem]} default when unset.
     */
    public static double getStemUnsuitableFruitChance(ResourceLocation cropId) {
        CropOverride override = cachedOverrides.get(cropId);
        return (override != null && override.stemUnsuitableFruitChance >= 0.0)
                ? override.stemUnsuitableFruitChance : STEM_UNSUITABLE_FRUIT_CHANCE.get();
    }

    /**
     * Get the immature-stem unsuitable-season grow chance, falling back to the global
     * {@code [stem]} default when unset.
     */
    public static double getStemUnsuitableGrowChance(ResourceLocation cropId) {
        CropOverride override = cachedOverrides.get(cropId);
        return (override != null && override.stemUnsuitableGrowChance >= 0.0)
                ? override.stemUnsuitableGrowChance : STEM_UNSUITABLE_GROW_CHANCE.get();
    }

}
