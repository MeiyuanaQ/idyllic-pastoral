package com.crispyraccoon.pastoralcraft.crop;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;
import java.util.Map;

import net.minecraft.SharedConstants;
import net.minecraft.WorldVersion;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.DataVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the pure crop-kind priority logic.
 *
 * <p>{@link CropKindResolver#kindFrom(HeightCrop, RegrowCrop, AgeCrop)} decides
 * the {@link CropKind} purely from the three descriptors (HEIGHT > REGROW >
 * AGE > NONE) without touching a Minecraft {@code Block} or the registry. The
 * descriptor records are plain value types; their {@code plantBlock} is
 * {@code null} in these tests.</p>
 */
class CropKindResolverTest {

    @BeforeAll
    static void bootstrapMinecraftRegistries() throws Exception {
        // Constructing a vanilla BlockBehaviour.Properties touches SoundType ->
        // SoundEvents -> BuiltInRegistries, which require the registry bootstrap that
        // normally runs once at game startup.
        //
        // NeoForge's FeatureFlags class initialiser consults
        // LoadingModList.get().getModFiles() (FeatureFlagLoader#loadModdedFlags) for
        // mod-defined feature flags. Under plain JUnit the FML boot never runs, so the
        // singleton is null and bootstrapping vanilla registries NPEs. Stub it with an
        // empty LoadingModList before bootstrapping.
        injectEmptyLoadingModList();
        // DataFixers.<clinit> reads SharedConstants.getCurrentVersion(); under plain
        // JUnit there is no version.json, so the singleton is null and bootstrapping
        // fails with "Game version not set". Provide a fake one before bootstrapping.
        setFakeGameVersion();
        Bootstrap.bootStrap();
    }

    /**
     * Replaces the FML {@code LoadingModList.INSTANCE} singleton with an empty list so
     * {@link Bootstrap#bootStrap()} can read mod-defined feature flags (there are none)
     * without a real FML boot. Reflection is required because the constructor and the
     * field are private, and no {@code EmptyModList} type exists in NeoForge 1.21.1's FML.
     */
    private static void injectEmptyLoadingModList() throws Exception {
        Class<?> loadingModList = Class.forName("net.neoforged.fml.loading.LoadingModList");
        Constructor<?> ctor = loadingModList.getDeclaredConstructor(List.class, List.class, List.class, Map.class);
        ctor.setAccessible(true);
        // plugins / modFiles / sortedList / modDependencies all empty -> getModFiles() is [].
        Object emptyList = ctor.newInstance(List.of(), List.of(), List.of(), Map.of());
        Field instance = loadingModList.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, emptyList);
    }

    /**
     * Installs a minimal {@link WorldVersion} into {@link SharedConstants} because
     * {@link net.minecraft.util.datafix.DataFixers} (pulled in by the registry
     * bootstrap) requires {@link SharedConstants#getCurrentVersion()} to be non-null.
     * In a real game this value is loaded from {@code version.json}; under plain JUnit
     * no such file exists, so the current version stays null and bootstrapping throws
     * {@code IllegalStateException: Game version not set}.
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

    private static final HeightCrop HEIGHT = new HeightCrop(3, null, false);
    private static final RegrowCrop REGROW = new RegrowCrop(BooleanProperty.create("has_seeds"));
    private static final AgeCrop AGE = new AgeCrop(IntegerProperty.create("age", 0, 7), 7);

    @Test
    void kindFrom_allNull_none() {
        assertEquals(CropKind.NONE, CropKindResolver.kindFrom(null, null, null));
    }

    @Test
    void kindFrom_heightOnly_height() {
        assertEquals(CropKind.HEIGHT, CropKindResolver.kindFrom(HEIGHT, null, null));
    }

    @Test
    void kindFrom_regrowOnly_regrow() {
        assertEquals(CropKind.REGROW, CropKindResolver.kindFrom(null, REGROW, null));
    }

    @Test
    void kindFrom_ageOnly_age() {
        assertEquals(CropKind.AGE, CropKindResolver.kindFrom(null, null, AGE));
    }

    @Test
    void kindFrom_heightWinsOverRegrowAndAge() {
        assertEquals(CropKind.HEIGHT, CropKindResolver.kindFrom(HEIGHT, REGROW, AGE));
    }

    @Test
    void kindFrom_regrowWinsOverAge() {
        assertEquals(CropKind.REGROW, CropKindResolver.kindFrom(null, REGROW, AGE));
    }

    // ---- generic AGE detection on BushBlock with an "age" property ---------

    // NOTE: a custom Block cannot be built in plain JUnit. Constructing any Block
    // requires SoundType -> SoundEvents -> BuiltInRegistries, whose initialiser
    // demands Bootstrap.bootStrap() has already run ("Not bootstrapped"); but once
    // bootstrapped, BuiltInRegistries.BLOCK is frozen and `new Block(...)` throws
    // "Registry is already frozen". We therefore reuse real registered vanilla
    // blocks: PitcherCropBlock is exactly the BushBlock-with-age pattern the
    // generic AGE path must catch (BuddingTomatoBlock / PitcherCropBlock /
    // RiceBlock), and Poppy is a plain BushBlock with no age property.

    @Test
    void ageOf_bushWithAgeProperty_resolvesAgeCrop() {
        // PitcherCropBlock (DoublePlantBlock -> BushBlock) carries an
        // IntegerProperty named "age" (0..4), the exact pattern BuddingTomatoBlock /
        // PitcherCropBlock / RiceBlock rely on. It exposes no getMaxAge(), so the
        // resolver falls back to the property maximum (4).
        AgeCrop age = CropKindResolver.ageOf(Blocks.PITCHER_CROP);
        assertNotNull(age);
        assertEquals("age", age.ageProperty().getName());
        assertEquals(4, age.maxAge());
        assertEquals(CropKind.AGE, CropKindResolver.kindOf(Blocks.PITCHER_CROP));
    }

    @Test
    void ageOf_bushWithoutAgeProperty_null() {
        // Poppy is a BushBlock with no age property.
        assertNull(CropKindResolver.ageOf(Blocks.POPPY));
        assertEquals(CropKind.NONE, CropKindResolver.kindOf(Blocks.POPPY));
    }

    @Test
    void ageOf_growingPlantHeadBlocks_null() {
        // Cave vines, twisting vines and weeping vines are GrowingPlantHeadBlock
        // subclasses that expose an "age" property — they must NOT be classified
        // as AGE crops. Treating them as crops would cancel their vanilla
        // random-tick growth and mutate them to short grass in unsuitable
        // seasons; kelp is instead handled by the separate HEIGHT strategy.
        assertNull(CropKindResolver.ageOf(Blocks.CAVE_VINES));
        assertNull(CropKindResolver.ageOf(Blocks.CAVE_VINES_PLANT));
        assertNull(CropKindResolver.ageOf(Blocks.TWISTING_VINES));
        assertNull(CropKindResolver.ageOf(Blocks.WEEPING_VINES));
        assertNull(CropKindResolver.ageOf(Blocks.KELP));
        assertNull(CropKindResolver.ageOf(Blocks.KELP_PLANT));
        assertEquals(CropKind.NONE, CropKindResolver.kindOf(Blocks.CAVE_VINES));
        assertEquals(CropKind.NONE, CropKindResolver.kindOf(Blocks.TWISTING_VINES));
        assertEquals(CropKind.NONE, CropKindResolver.kindOf(Blocks.WEEPING_VINES));
        assertEquals(CropKind.HEIGHT, CropKindResolver.kindOf(Blocks.KELP));
        assertEquals(CropKind.HEIGHT, CropKindResolver.kindOf(Blocks.KELP_PLANT));
    }
}
