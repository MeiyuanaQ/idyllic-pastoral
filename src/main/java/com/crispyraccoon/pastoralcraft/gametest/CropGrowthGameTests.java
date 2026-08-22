package com.crispyraccoon.pastoralcraft.gametest;

import java.lang.reflect.Method;

import com.crispyraccoon.pastoralcraft.crop.CropGrowthTracker;
import com.crispyraccoon.pastoralcraft.crop.SeasonTagResolver;
import com.teamtea.eclipticseasons.api.constant.solar.Season;
import com.teamtea.eclipticseasons.common.core.SolarHolders;
import com.teamtea.eclipticseasons.common.core.solar.SolarDataManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * NeoForge GameTests that drive PastoralCraft's deterministic solar-day crop growth
 * end-to-end in a real server environment (runGameTestServer).
 *
 * <p>These tests assume the dev runtime classpath mounts the full modpack (see
 * {@code localRuntime fileTree(...)} in build.gradle) so third-party crops
 * (Farmers Delight, Supplementaries, Adorable Hamster Pets, Kaleidoscope Cookery)
 * are present in the block registry.</p>
 */
@GameTestHolder("pastoralcraft")
@PrefixGameTestTemplate(false)
public class CropGrowthGameTests {

    /**
     * Sets the Ecliptic Seasons solar day for the level, equivalent to the
     * {@code /eclipticseasons solar set <day>} command. This reaches into ES's
     * internal {@link SolarDataManager} because GameTestHelper has no command API.
     */
    private static void setSolarDay(ServerLevel level, int day) {
        SolarDataManager data = SolarHolders.getSaveData(level);
        if (data == null) {
            throw new IllegalStateException(
                    "Ecliptic Seasons SolarDataManager not initialized for this level");
        }
        data.setSolarTermsDay(day);
        data.sendAndUpdate(level);
    }

    /**
     * Vanilla wheat (AGE path): planted at solar day 0 (spring), advanced 3 suitable
     * days later it must have grown at least one stage (age 0 → 1). This is the
     * minimum end-to-end smoke test: modpack loaded, ES clock driven, LevelMixin
     * tracking active, and the 200-tick periodic catch-up applies growth.
     */
    @GameTest(template = "empty_8x8", timeoutTicks = 600, batch = "spring")
    public static void wheatGrowsOneStageInSpring(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setSolarDay(level, 0); // spring (solar term 0)

        // Place farmland + wheat with flag 2 (UPDATE_CLIENTS only) so no neighbor
        // update drops the crop before the catch-up runs.
        BlockPos farmland = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos wheat = helper.absolutePos(new BlockPos(0, 3, 0));
        level.setBlock(farmland, Blocks.FARMLAND.defaultBlockState(), 2);
        level.setBlock(wheat, Blocks.WHEAT.defaultBlockState(), 2);

        // Advance 3 suitable days (default daysPerStage = 3).
        setSolarDay(level, 3);

        helper.runAfterDelay(400, () -> {
            int age = level.getBlockState(wheat).getValue(BlockStateProperties.AGE_7);
            if (age >= 1) {
                helper.succeed();
            } else {
                helper.fail("wheat age=" + age + ", expected >= 1 after 3 suitable days");
            }
        });
    }

    /**
     * Farmers Delight tomato TRANSFORM: budding_tomatoes (daysPerStage=1) must
     * mature after 3 suitable days and be replaced by the full tomatoes crop.
     * This also proves the string-targeted {@code TomatoBlockMixin} applies in a
     * real runtime with Farmers Delight on the classpath.
     *
     * <p>NOTE: Ecliptic Seasons registers FD tomatoes as a SUMMER-only crop, so
     * this test runs in summer (solar days 45-48), NOT spring. Placing it in
     * spring is a legitimate no-grow scenario (age stays 0), not a bug.</p>
     */
    @GameTest(template = "empty_8x8", timeoutTicks = 600, batch = "summer")
    public static void buddingTomatoTransformsToTomatoes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Summer: termLength=7, summer = solar days 42-83. Use 45 to stay clear of
        // the spring/summer boundary.
        setSolarDay(level, 45);

        BlockPos farmland = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos tomato = helper.absolutePos(new BlockPos(0, 3, 0));
        level.setBlock(farmland, Blocks.FARMLAND.defaultBlockState(), 2);
        Block buddingTomato = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("farmersdelight:budding_tomatoes"));
        level.setBlock(tomato, buddingTomato.defaultBlockState(), 2);

        // Advance 3 suitable days (daysPerStage=1 → age 3 → TRANSFORM to tomatoes).
        setSolarDay(level, 48);

        helper.runAfterDelay(400, () -> {
            BlockState state = level.getBlockState(tomato);
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (ResourceLocation.parse("farmersdelight:tomatoes").equals(id)) {
                helper.succeed();
            } else {
                // Diagnostic: age tells us whether growth ran (0 = not grown / unsuitable,
                // 3 = mature but TRANSFORM failed); tracked tells us whether LevelMixin
                // created a CropProgressEntry for this placement; seasons tells us the
                // resolved suitable set for the block (source of the spring exclusion).
                // solarDay is what PastoralCraft's getSolarDays sees; managerDay is the
                // raw SolarDataManager field we wrote via setSolarDay.
                Season currentSeason = CropGrowthTracker.getSeason(level);
                SolarDataManager manager = SolarHolders.getSaveData(level);
                int managerDay = manager == null ? -1 : manager.getSolarTermsDay();
                helper.fail("expected farmersdelight:tomatoes, got " + id
                        + " age=" + CropGrowthTracker.getCropAge(state)
                        + " tracked=" + CropGrowthTracker.isTracked(level, tomato)
                        + " seasons=" + SeasonTagResolver.resolve(state.getBlock())
                        + " currentSeason=" + currentSeason
                        + " solarDay=" + CropGrowthTracker.getSolarDays(level)
                        + " termLength=" + CropGrowthTracker.getTermLength(level)
                        + " managerDay=" + managerDay);
            }
        });
    }

    // =======================================================================
    // Vanilla sugar cane (HEIGHT): root-only tracking, spring (ES tag SP_SU).
    // =======================================================================

    /**
     * Vanilla sugar cane (HEIGHT path): planted at the start of spring, after 3
     * suitable days the stalk must have grown from 1 to 2 blocks tall.
     */
    @GameTest(template = "empty_8x8", timeoutTicks = 600, batch = "sugar_cane")
    public static void sugarCaneGrowsInSpring(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setSolarDay(level, 0); // spring

        BlockPos dirt = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos cane = helper.absolutePos(new BlockPos(0, 3, 0));
        BlockPos water = helper.absolutePos(new BlockPos(1, 3, 0));
        BlockPos top = helper.absolutePos(new BlockPos(0, 4, 0));
        level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), 2);
        level.setBlock(water, Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(cane, Blocks.SUGAR_CANE.defaultBlockState(), 2);

        // 3 suitable spring days -> 1 stage -> target height 2.
        setSolarDay(level, 3);

        helper.runAfterDelay(400, () -> {
            BlockState topState = level.getBlockState(top);
            if (topState.is(Blocks.SUGAR_CANE)) {
                helper.succeed();
            } else {
                helper.fail("sugar cane failed: top=" + topState
                        + " bottom=" + level.getBlockState(cane)
                        + " tracked=" + CropGrowthTracker.isTracked(level, cane)
                        + " seasons=" + SeasonTagResolver.resolve(Blocks.SUGAR_CANE)
                        + " currentSeason=" + CropGrowthTracker.getSeason(level)
                        + " solarDay=" + CropGrowthTracker.getSolarDays(level));
            }
        });
    }

    // =======================================================================
    // Vanilla kelp (HEIGHT): root-only tracking in water, spring (ES SP_SU_AU).
    // =======================================================================

    /**
     * Vanilla kelp (HEIGHT path): planted in a water column at the start of
     * spring, after 3 suitable days it must have grown to 2 blocks tall.
     */
    @GameTest(template = "empty_8x8", timeoutTicks = 600, batch = "kelp")
    public static void kelpGrowsInSpring(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setSolarDay(level, 0); // spring

        BlockPos dirt = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos kelp = helper.absolutePos(new BlockPos(0, 3, 0));
        BlockPos water1 = helper.absolutePos(new BlockPos(0, 4, 0));
        BlockPos water2 = helper.absolutePos(new BlockPos(0, 5, 0));
        BlockPos water3 = helper.absolutePos(new BlockPos(0, 6, 0));
        level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), 2);
        level.setBlock(water1, Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(water2, Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(water3, Blocks.WATER.defaultBlockState(), 2);
        level.setBlock(kelp, Blocks.KELP.defaultBlockState(), 2);

        // 3 suitable days -> 1 stage -> height 2.
        setSolarDay(level, 3);

        helper.runAfterDelay(400, () -> {
            BlockState topState = level.getBlockState(water1);
            boolean isKelp = topState.is(Blocks.KELP) || topState.is(Blocks.KELP_PLANT);
            if (isKelp) {
                helper.succeed();
            } else {
                helper.fail("kelp failed: top=" + topState
                        + " root=" + level.getBlockState(kelp)
                        + " tracked=" + CropGrowthTracker.isTracked(level, kelp)
                        + " seasons=" + SeasonTagResolver.resolve(Blocks.KELP)
                        + " currentSeason=" + CropGrowthTracker.getSeason(level)
                        + " solarDay=" + CropGrowthTracker.getSolarDays(level));
            }
        });
    }

    // =======================================================================
    // Vanilla melon stem (STEM): summer (ES tag SUMMER), fruits east on DIRT.
    // =======================================================================

    /**
     * Vanilla melon stem (STEM path): planted in summer with a DIRT block to the
     * east as fruit support. After 27 suitable days the stem must have reached
     * max age and produced a melon at the fruit position.
     */
    @GameTest(template = "empty_8x8", timeoutTicks = 600, batch = "melon")
    public static void melonStemFruitsInSummer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setSolarDay(level, 45); // summer

        BlockPos farmland = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos stem = helper.absolutePos(new BlockPos(0, 3, 0));
        BlockPos fruitSupport = helper.absolutePos(new BlockPos(1, 2, 0));
        BlockPos fruit = helper.absolutePos(new BlockPos(1, 3, 0));
        level.setBlock(farmland, Blocks.FARMLAND.defaultBlockState(), 2);
        level.setBlock(stem, Blocks.MELON_STEM.defaultBlockState(), 2);
        level.setBlock(fruitSupport, Blocks.DIRT.defaultBlockState(), 2);

        // 27 suitable summer days -> stem age 7 + fruit rolls.
        setSolarDay(level, 72);

        helper.runAfterDelay(400, () -> {
            BlockState fruitState = level.getBlockState(fruit);
            if (fruitState.is(Blocks.MELON)) {
                helper.succeed();
            } else {
                helper.fail("melon stem failed: fruit=" + fruitState
                        + " stem=" + level.getBlockState(stem)
                        + " tracked=" + CropGrowthTracker.isTracked(level, stem)
                        + " seasons=" + SeasonTagResolver.resolve(Blocks.MELON_STEM)
                        + " currentSeason=" + CropGrowthTracker.getSeason(level)
                        + " solarDay=" + CropGrowthTracker.getSolarDays(level)
                        + " managerDay=" + SolarHolders.getSaveData(level).getSolarTermsDay());
            }
        });
    }

    // =======================================================================
    // Farmers Delight rice (AGE + COMPANION): summer (SS tags summer+autumn).
    // =======================================================================

    /**
     * Farmers Delight rice: planted waterlogged in summer, matures after 21
     * suitable days and the COMPANION side effect places rice panicles above.
     * The top block must stay air for the panicles (waterCompanion=false).
     */
    @GameTest(template = "empty_8x8", timeoutTicks = 600, batch = "rice_fd")
    public static void fdRiceMaturesWithPaniclesInSummer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setSolarDay(level, 45); // summer

        BlockPos dirt = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos rice = helper.absolutePos(new BlockPos(0, 3, 0));
        BlockPos panicles = helper.absolutePos(new BlockPos(0, 4, 0));
        level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), 2);

        Block riceBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("farmersdelight:rice"));
        BlockState riceState = riceBlock.defaultBlockState();
        if (riceState.hasProperty(BlockStateProperties.WATERLOGGED)) {
            riceState = riceState.setValue(BlockStateProperties.WATERLOGGED, true);
        }
        level.setBlock(rice, riceState, 2);

        // 21 suitable days -> age 7 (daysPerStage=3) -> COMPANION places panicles.
        setSolarDay(level, 66);

        helper.runAfterDelay(400, () -> {
            ResourceLocation topId = BuiltInRegistries.BLOCK.getKey(level.getBlockState(panicles).getBlock());
            if (ResourceLocation.parse("farmersdelight:rice_panicles").equals(topId)) {
                helper.succeed();
            } else {
                BlockState riceStateNow = level.getBlockState(rice);
                helper.fail("FD rice failed: top=" + topId
                        + " rice=" + level.getBlockState(rice)
                        + " riceAge=" + CropGrowthTracker.getCropAge(riceStateNow)
                        + " tracked=" + CropGrowthTracker.isTracked(level, rice)
                        + " seasons=" + SeasonTagResolver.resolve(riceBlock)
                        + " currentSeason=" + CropGrowthTracker.getSeason(level)
                        + " solarDay=" + CropGrowthTracker.getSolarDays(level)
                        + " managerDay=" + SolarHolders.getSaveData(level).getSolarTermsDay());
            }
        });
    }

    // =======================================================================
    // Supplementaries flax (AGE + DOUBLE): spring (SS tag spring).
    // =======================================================================

    /**
     * Supplementaries flax: planted on farmland in spring, reaches doubleAge=4
     * after 12 suitable days and grows a second (upper) block above.
     */
    @GameTest(template = "empty_8x8", timeoutTicks = 600, batch = "flax")
    public static void flaxGrowsDoubleInSpring(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setSolarDay(level, 0); // spring

        BlockPos farmland = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos flax = helper.absolutePos(new BlockPos(0, 3, 0));
        BlockPos upper = helper.absolutePos(new BlockPos(0, 4, 0));
        level.setBlock(farmland, Blocks.FARMLAND.defaultBlockState(), 2);

        Block flaxBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("supplementaries:flax"));
        level.setBlock(flax, flaxBlock.defaultBlockState(), 2);

        // 12 suitable spring days -> age 4 = doubleAge -> upper half placed.
        setSolarDay(level, 12);

        helper.runAfterDelay(400, () -> {
            BlockState upperState = level.getBlockState(upper);
            boolean isUpper = upperState.is(flaxBlock)
                    && upperState.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && upperState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        == DoubleBlockHalf.UPPER;
            if (isUpper) {
                helper.succeed();
            } else {
                helper.fail("flax failed: upper=" + upperState
                        + " lower=" + level.getBlockState(flax)
                        + " tracked=" + CropGrowthTracker.isTracked(level, flax)
                        + " seasons=" + SeasonTagResolver.resolve(flaxBlock)
                        + " currentSeason=" + CropGrowthTracker.getSeason(level)
                        + " solarDay=" + CropGrowthTracker.getSolarDays(level));
            }
        });
    }

    /**
     * Event path double-stage transition: drives flax through 3→4 and 4→5 via
     * {@code CropGrowEvent.Pre} (the deterministic growth path used in real
     * gameplay) and asserts the whole plant survives — LOWER and UPPER halves
     * alive, HALF correct, ages in sync, and the tracker entry retained.
     *
     * <p>This is the path the previous command tests never exercised at the
     * double-stage transition, which is exactly where real gameplay shattered
     * flax (4→5).</p>
     */
    @GameTest(template = "empty_8x8", timeoutTicks = 600, batch = "flax")
    public static void flaxEventPathDoubleTransitionDoesNotShatter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setSolarDay(level, 0); // spring

        BlockPos farmland = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos flax = helper.absolutePos(new BlockPos(0, 3, 0));
        BlockPos upper = helper.absolutePos(new BlockPos(0, 4, 0));
        level.setBlock(farmland, Blocks.FARMLAND.defaultBlockState(), 2);

        Block flaxBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("supplementaries:flax"));
        level.setBlock(flax, flaxBlock.defaultBlockState(), 2);

        // 0→3 (single stage), 3→4 (double transition), 4→5 (double growth).
        setSolarDay(level, 9);
        postCropGrowPre(level, flax, level.getBlockState(flax));
        setSolarDay(level, 12);
        postCropGrowPre(level, flax, level.getBlockState(flax));
        setSolarDay(level, 15);
        postCropGrowPre(level, flax, level.getBlockState(flax));

        assertFlaxIntact(helper, level, flax, upper, flaxBlock, 5);
    }

    /**
     * External grower regression: reflectively invokes Supplementaries'
     * {@code FlaxBlock.growCropBy} (the flag-3 writer reachable via bee
     * pollination, bonemeal and randomTick) against a double-age LOWER whose
     * upper half is MISSING, and asserts the plant does not shatter.
     */
    @GameTest(template = "empty_8x8", timeoutTicks = 600, batch = "flax")
    public static void flaxExternalGrowCropByDoesNotShatter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setSolarDay(level, 0);

        BlockPos farmland = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos flax = helper.absolutePos(new BlockPos(0, 3, 0));
        BlockPos upper = helper.absolutePos(new BlockPos(0, 4, 0));
        level.setBlock(farmland, Blocks.FARMLAND.defaultBlockState(), 2);

        Block flaxBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("supplementaries:flax"));
        BlockState lower4 = flaxBlock.defaultBlockState()
                .setValue(BlockStateProperties.AGE_7, 4)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        level.setBlock(flax, lower4, 2);

        try {
            Class<?> flaxClass = Class.forName("net.mehvahdjukaar.supplementaries.common.block.blocks.FlaxBlock");
            Method growCropBy = flaxClass.getMethod("growCropBy",
                    Level.class, BlockPos.class, BlockState.class, int.class);
            growCropBy.invoke(flaxBlock, level, flax, level.getBlockState(flax), 1);
        } catch (ReflectiveOperationException e) {
            helper.fail("growCropBy reflection failed: " + e);
            return;
        }

        assertFlaxIntact(helper, level, flax, upper, flaxBlock, 5);
    }

    /**
     * Bonemeal regression: reflectively invokes {@code FlaxBlock.growCrops} on a
     * single-stage (age 3) flax, forcing the age past {@code DOUBLE_AGE} through
     * the external flag-3 writer, and asserts the plant does not shatter.
     */
    @GameTest(template = "empty_8x8", timeoutTicks = 600, batch = "flax")
    public static void flaxBonemealDoesNotShatter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setSolarDay(level, 0);

        BlockPos farmland = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos flax = helper.absolutePos(new BlockPos(0, 3, 0));
        BlockPos upper = helper.absolutePos(new BlockPos(0, 4, 0));
        level.setBlock(farmland, Blocks.FARMLAND.defaultBlockState(), 2);

        Block flaxBlock = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("supplementaries:flax"));
        BlockState lower3 = flaxBlock.defaultBlockState()
                .setValue(BlockStateProperties.AGE_7, 3)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        level.setBlock(flax, lower3, 2);

        try {
            Class<?> flaxClass = Class.forName("net.mehvahdjukaar.supplementaries.common.block.blocks.FlaxBlock");
            Method growCrops = flaxClass.getMethod("growCrops",
                    Level.class, BlockPos.class, BlockState.class);
            growCrops.invoke(flaxBlock, level, flax, level.getBlockState(flax));
        } catch (ReflectiveOperationException e) {
            helper.fail("growCrops reflection failed: " + e);
            return;
        }

        BlockState lower = level.getBlockState(flax);
        BlockState upperState = level.getBlockState(upper);
        boolean lowerOk = lower.is(flaxBlock)
                && lower.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                && lower.getValue(BlockStateProperties.AGE_7) >= 4;
        boolean upperOk = upperState.is(flaxBlock)
                && upperState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
        if (lowerOk && upperOk) {
            helper.succeed();
        } else {
            helper.fail("flax bonemeal shattered: lower=" + lower + " upper=" + upperState);
        }
    }

    /** Posts a {@code CropGrowEvent.Pre} to drive the event growth path synchronously. */
    private static void postCropGrowPre(Level level, BlockPos pos, BlockState state) {
        NeoForge.EVENT_BUS.post(new CropGrowEvent.Pre(level, pos, state));
    }

    /** Asserts flax LOWER+UPPER are intact, HALF correct, age synced, and tracker retained. */
    private static void assertFlaxIntact(GameTestHelper helper, ServerLevel level,
                                         BlockPos lowerPos, BlockPos upperPos,
                                         Block flaxBlock, int expectedAge) {
        BlockState lower = level.getBlockState(lowerPos);
        BlockState upperState = level.getBlockState(upperPos);
        boolean lowerOk = lower.is(flaxBlock)
                && lower.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                && lower.getValue(BlockStateProperties.AGE_7) == expectedAge;
        boolean upperOk = upperState.is(flaxBlock)
                && upperState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                && upperState.getValue(BlockStateProperties.AGE_7) == expectedAge;
        boolean tracked = CropGrowthTracker.isTracked(level, lowerPos);
        if (lowerOk && upperOk && tracked) {
            helper.succeed();
        } else {
            helper.fail("flax shattered: lower=" + lower
                    + " upper=" + upperState
                    + " tracked=" + tracked
                    + " seasons=" + SeasonTagResolver.resolve(flaxBlock)
                    + " solarDay=" + CropGrowthTracker.getSolarDays(level));
        }
    }

    // =======================================================================
    // AHP sunflower (REGROW): spring (override seasons=spring_autumn).
    // =======================================================================

    /**
     * AHP sunflower (REGROW path): only the upper half is tracked; after 3
     * suitable spring days the has_seeds product boolean must be set to true.
     * The property is read dynamically because AHP is not a compile-time
     * dependency (the modpack is mounted via localRuntime only).
     */
    @GameTest(template = "empty_8x8", timeoutTicks = 600, batch = "sunflower")
    public static void sunflowerRegrowsSeedsInSpring(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setSolarDay(level, 0); // spring

        BlockPos dirt = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos lower = helper.absolutePos(new BlockPos(0, 3, 0));
        BlockPos upper = helper.absolutePos(new BlockPos(0, 4, 0));
        level.setBlock(dirt, Blocks.DIRT.defaultBlockState(), 2);

        Block sunflowerBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("adorablehamsterpets:sunflower_block"));
        level.setBlock(lower, sunflowerBlock.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER), 2);
        level.setBlock(upper, sunflowerBlock.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 2);

        // 3 suitable days -> 1 stage -> has_seeds = true.
        setSolarDay(level, 3);

        helper.runAfterDelay(400, () -> {
            BlockState upperState = level.getBlockState(upper);
            Property<Boolean> seedsProp =
                    (Property<Boolean>) upperState.getBlock().getStateDefinition().getProperty("has_seeds");
            boolean upperHasSeeds = seedsProp != null
                    && Boolean.TRUE.equals(upperState.getValue(seedsProp));
            if (upperHasSeeds) {
                helper.succeed();
            } else {
                helper.fail("sunflower failed: upper=" + upperState
                        + " upperTracked=" + CropGrowthTracker.isTracked(level, upper)
                        + " lowerTracked=" + CropGrowthTracker.isTracked(level, lower)
                        + " seasons=" + SeasonTagResolver.resolve(sunflowerBlock)
                        + " currentSeason=" + CropGrowthTracker.getSeason(level)
                        + " solarDay=" + CropGrowthTracker.getSolarDays(level));
            }
        });
    }

    // =======================================================================
    // KC rice (AGE, 3-segment): summer (SS tags summer+autumn).
    // =======================================================================

    /**
     * Kaleidoscope Cookery rice: a 3-segment waterlogged crop (DOWN/MIDDLE/UP).
     * Only the DOWN segment is tracked; after 3 suitable summer days its age
     * must be >= 1.
     */
    @GameTest(template = "empty_8x8", timeoutTicks = 600, batch = "rice_kc")
    public static void kcRiceGrowsInSummer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setSolarDay(level, 45); // summer

        BlockPos farmland = helper.absolutePos(new BlockPos(0, 2, 0));
        BlockPos down = helper.absolutePos(new BlockPos(0, 3, 0));
        BlockPos middle = helper.absolutePos(new BlockPos(0, 4, 0));
        BlockPos up = helper.absolutePos(new BlockPos(0, 5, 0));
        level.setBlock(farmland, Blocks.FARMLAND.defaultBlockState(), 2);

        Block riceBlock = BuiltInRegistries.BLOCK.get(
                ResourceLocation.parse("kaleidoscope_cookery:rice_crop"));
        IntegerProperty location =
                (IntegerProperty) riceBlock.getStateDefinition().getProperty("location");
        if (location == null) {
            helper.fail("KC rice has no 'location' property");
            return;
        }
        BlockState downState = riceBlock.defaultBlockState()
                .setValue(location, 0)
                .setValue(BlockStateProperties.WATERLOGGED, true);
        BlockState middleState = riceBlock.defaultBlockState().setValue(location, 1);
        BlockState upState = riceBlock.defaultBlockState().setValue(location, 2);
        level.setBlock(down, downState, 2);
        level.setBlock(middle, middleState, 2);
        level.setBlock(up, upState, 2);

        // 3 suitable summer days -> age 1 (daysPerStage=3).
        setSolarDay(level, 48);

        helper.runAfterDelay(400, () -> {
            int age = CropGrowthTracker.getCropAge(level.getBlockState(down));
            if (age >= 1) {
                helper.succeed();
            } else {
                helper.fail("KC rice failed: age=" + age
                        + " down=" + level.getBlockState(down)
                        + " middle=" + level.getBlockState(middle)
                        + " up=" + level.getBlockState(up)
                        + " tracked=" + CropGrowthTracker.isTracked(level, down)
                        + " seasons=" + SeasonTagResolver.resolve(riceBlock)
                        + " currentSeason=" + CropGrowthTracker.getSeason(level)
                        + " solarDay=" + CropGrowthTracker.getSolarDays(level));
            }
        });
    }
}
