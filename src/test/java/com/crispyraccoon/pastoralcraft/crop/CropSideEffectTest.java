package com.crispyraccoon.pastoralcraft.crop;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.storage.DataVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the round-4 "five promised test families" that were deferred:
 * the level-interaction / side-effect decisions are now exercised through the
 * extracted package-private pure helpers plus real Minecraft blocks (scheme A —
 * Mockito is used to mock {@link Level} for the fruit-support face-sturdy branch).
 *
 * <ol>
 *   <li>{@code tryPlaceStemFruit} fruit support (FarmBlock / DIRT / face-sturdy) —
 *       {@link CropGrowthTracker#isFruitSupport(BlockState, BlockPos, Level)}.</li>
 *   <li>Bonemeal no-backtrack — {@link CropGrowthTracker#backCalculatedPlantedDay(int, int, int)}.</li>
 *   <li>COMPANION plantedDay propagation — {@link CropGrowthTracker#clampPlantedDay(int, int)}.</li>
 *   <li>DOUBLE upper-half idempotency — {@link CropGrowthTracker#needsUpperHalfPlacement(BlockState, BlockState)}.</li>
 *   <li>Mutation upper-half cleanup — {@link CropGrowthTracker#isUpperHalfOf(BlockState, net.minecraft.world.level.block.Block)}.</li>
 * </ol>
 */
class CropSideEffectTest {

    private static final BlockPos POS = new BlockPos(0, 64, 0);

    @BeforeAll
    static void ensureMinecraftBootstrapped() throws Exception {
        // Bootstrap once per JVM. CropKindResolverTest performs the same bootstrap;
        // SharedConstants.getCurrentVersion() THROWS "Game version not set" when unset,
        // so a bare != null probe cannot be used — catch it to decide whether another
        // test class has already bootstrapped vanilla registries.
        try {
            SharedConstants.getCurrentVersion();
            return;
        } catch (IllegalStateException unset) {
            // Version not set yet — bootstrap below.
        }
        injectEmptyLoadingModList();
        setFakeGameVersion();
        Bootstrap.bootStrap();
    }

    /**
     * Replaces the FML {@code LoadingModList.INSTANCE} singleton with an empty list so
     * {@link Bootstrap#bootStrap()} can read mod-defined feature flags (there are none)
     * without a real FML boot. Mirrors CropKindResolverTest.
     */
    private static void injectEmptyLoadingModList() throws Exception {
        Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
        Constructor<?> ctor = loadingModList.getDeclaredConstructor(List.class, List.class, List.class, Map.class);
        ctor.setAccessible(true);
        Object emptyList = ctor.newInstance(List.of(), List.of(), List.of(), Map.of());
        Field instance = loadingModList.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, emptyList);
    }

    /**
     * Installs a minimal {@link WorldVersion} into {@link SharedConstants} because
     * the registry bootstrap requires {@link SharedConstants#getCurrentVersion()}.
     * Mirrors CropKindResolverTest.
     */
    private static void setFakeGameVersion() {
        SharedConstants.setVersion(new WorldVersion() {
            @Override
            public DataVersion getDataVersion() {
                return new DataVersion(4186); // 1.21.1
            }

            @Override
            public String getId() {
                return "1.21.1";
            }

            @Override
            public String getName() {
                return "1.21.1";
            }

            @Override
            public int getProtocolVersion() {
                return 0;
            }

            @Override
            public int getPackVersion(PackType type) {
                return 0;
            }

            @Override
            public Date getBuildTime() {
                return new Date(0);
            }

            @Override
            public boolean isStable() {
                return true;
            }
        });
    }

    // ---- 1. tryPlaceStemFruit fruit support (FarmBlock / DIRT / face-sturdy) ----

    @Test
    void fruitSupport_farmland_true() {
        // FarmBlock is a 15px-tall half-cube whose top is NOT face-sturdy; the
        // explicit FarmBlock branch is what makes stems on farmland fruit (8a).
        assertTrue(CropGrowthTracker.isFruitSupport(Blocks.FARMLAND.defaultBlockState(), POS, mock(Level.class)));
    }

    @Test
    void fruitSupport_dirtTag_true() {
        assertTrue(CropGrowthTracker.isFruitSupport(Blocks.DIRT.defaultBlockState(), POS, mock(Level.class)));
    }

    @Test
    void fruitSupport_faceSturdy_true() {
        // A full cube is not farmland and not in the DIRT tag, so it relies on the
        // face-sturdy branch — the legacy non-farmland fruiting behavior.
        assertTrue(CropGrowthTracker.isFruitSupport(Blocks.STONE.defaultBlockState(), POS, mock(Level.class)));
    }

    @Test
    void fruitSupport_air_false() {
        assertFalse(CropGrowthTracker.isFruitSupport(Blocks.AIR.defaultBlockState(), POS, mock(Level.class)));
    }

    // ---- 2. Bonemeal no-backtrack (back-calculated plantedDay) --------------

    @Test
    void backCalculatedPlantedDay_backCalculatesAgeStages() {
        // age=3 crop with daysPerStage=3: plantedDay = 100 - 9 (existing progress preserved).
        assertEquals(100 - 3 * 3, CropGrowthTracker.backCalculatedPlantedDay(100, 3, 3));
    }

    @Test
    void backCalculatedPlantedDay_zeroAge_isCurrentDay() {
        // A freshly planted crop (age 0) must not back-calculate.
        assertEquals(100, CropGrowthTracker.backCalculatedPlantedDay(100, 0, 3));
    }

    @Test
    void backCalculatedPlantedDay_negativeDaysPerStage_clamps() {
        // Defensive clamp: a negative daysPerStage must never push plantedDay into
        // the future (which would freeze growth forever).
        assertEquals(100, CropGrowthTracker.backCalculatedPlantedDay(100, 3, -1));
    }

    // ---- 3. COMPANION plantedDay propagation --------------------------------

    @Test
    void clampPlantedDay_preservesBasePlantedDay() {
        // Companion crops share their base crop's plantedDay (rice → rice_panicles),
        // so the companion's calendar phase stays in sync instead of restarting.
        assertEquals(10, CropGrowthTracker.clampPlantedDay(10, 20));
    }

    @Test
    void clampPlantedDay_clampsFutureDay() {
        assertEquals(20, CropGrowthTracker.clampPlantedDay(30, 20));
    }

    // ---- 4. DOUBLE upper-half idempotency -----------------------------------

    @Test
    void needsUpperHalfPlacement_alreadyEqual_false() {
        BlockState upper = Blocks.PITCHER_CROP.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        assertFalse(CropGrowthTracker.needsUpperHalfPlacement(upper, upper));
    }

    @Test
    void needsUpperHalfPlacement_different_true() {
        BlockState lower = Blocks.PITCHER_CROP.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState upper = Blocks.PITCHER_CROP.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        assertTrue(CropGrowthTracker.needsUpperHalfPlacement(lower, upper));
    }

    // ---- 5. Mutation upper-half cleanup -------------------------------------

    @Test
    void isUpperHalfOf_upperHalf_true() {
        BlockState upper = Blocks.PITCHER_CROP.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        assertTrue(CropGrowthTracker.isUpperHalfOf(upper, Blocks.PITCHER_CROP));
    }

    @Test
    void isUpperHalfOf_lowerHalf_false() {
        BlockState lower = Blocks.PITCHER_CROP.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        assertFalse(CropGrowthTracker.isUpperHalfOf(lower, Blocks.PITCHER_CROP));
    }

    @Test
    void isUpperHalfOf_differentBlock_false() {
        BlockState upper = Blocks.PITCHER_CROP.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        assertFalse(CropGrowthTracker.isUpperHalfOf(upper, Blocks.POPPY));
    }

    // ---- 6. DOUBLE upper-half tracking skip --------------------------------

    @Test
    void isDoubleCropUpperHalf_upperHalfWithDoubleDescriptor_true() {
        // A DOUBLE crop's UPPER half must be skipped by the tracker (only LOWER
        // is followed); the doubleAge descriptor marks it as a two-block crop.
        BlockState upper = Blocks.PITCHER_CROP.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        StructureDescriptor doubleDescriptor = new StructureDescriptor(4, null, null, null, null, 0, null, false, false);
        assertTrue(CropGrowthTracker.isDoubleCropUpperHalf(upper, doubleDescriptor));
    }

    @Test
    void isDoubleCropUpperHalf_lowerHalf_false() {
        // The LOWER half of a DOUBLE crop is the tracked half, never skipped.
        BlockState lower = Blocks.PITCHER_CROP.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        StructureDescriptor doubleDescriptor = new StructureDescriptor(4, null, null, null, null, 0, null, false, false);
        assertFalse(CropGrowthTracker.isDoubleCropUpperHalf(lower, doubleDescriptor));
    }

    @Test
    void isDoubleCropUpperHalf_emptyDescriptor_false() {
        // No structural declaration means no DOUBLE marker — an UPPER half is not skipped.
        BlockState upper = Blocks.PITCHER_CROP.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        assertFalse(CropGrowthTracker.isDoubleCropUpperHalf(upper, CropStructureRegistry.EMPTY));
    }

    @Test
    void isDoubleCropUpperHalf_nonDoubleDescriptor_false() {
        // doubleAge < 0 marks a non-DOUBLE crop, so its UPPER half is not skipped.
        BlockState upper = Blocks.PITCHER_CROP.defaultBlockState()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        StructureDescriptor nonDoubleDescriptor = new StructureDescriptor(-1, null, null, null, null, 0, null, false, false);
        assertFalse(CropGrowthTracker.isDoubleCropUpperHalf(upper, nonDoubleDescriptor));
    }

    // ---- 7. Stem fruit re-placement before mutation -------------------------

    @Test
    void shouldPlaceStemFruitBeforeMutate_fruitedStemBlock_true() {
        // An already-fruited plain StemBlock must re-place its fruit before the
        // mutation wipes the stem, so the fruit (an independent block) is kept.
        assertTrue(CropGrowthTracker.shouldPlaceStemFruitBeforeMutate(true, Blocks.MELON_STEM));
    }

    @Test
    void shouldPlaceStemFruitBeforeMutate_notFruited_false() {
        // An immature stem has nothing to re-place; mutation turns it to grass.
        assertFalse(CropGrowthTracker.shouldPlaceStemFruitBeforeMutate(false, Blocks.MELON_STEM));
    }

    @Test
    void shouldPlaceStemFruitBeforeMutate_attachedStem_false() {
        // An attached stem already has its fruit in the world; the mutation only
        // wipes the stem and the fruit is already present, so no re-placement.
        assertFalse(CropGrowthTracker.shouldPlaceStemFruitBeforeMutate(true, Blocks.ATTACHED_MELON_STEM));
    }

    // ---- 8. Deferred stem settlement pending set (N1) ----------------------

    @Test
    void stemSettlementPending_markHasClear() {
        // A fresh mock level starts unmarked; mark/has/clear round-trips. Each test
        // uses a fresh Level instance, so the static WeakHashMap-backed set never
        // leaks state across tests.
        Level level = mock(Level.class);
        assertFalse(CropGrowthTracker.hasStemSettlementPending(level));
        CropGrowthTracker.markStemSettlementPending(level);
        assertTrue(CropGrowthTracker.hasStemSettlementPending(level));
        CropGrowthTracker.clearStemSettlementPending(level);
        assertFalse(CropGrowthTracker.hasStemSettlementPending(level));
    }
}
