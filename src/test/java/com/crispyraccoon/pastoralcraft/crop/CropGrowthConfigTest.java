package com.crispyraccoon.pastoralcraft.crop;

import com.teamtea.eclipticseasons.api.constant.solar.Season;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for data-driven override parsing, including the round-2/round-3 additions:
 * {@code freeze}, {@code transformBlock}, {@code climbBlock}/{@code climbSupport}/
 * {@code maxClimbHeight}, {@code doubleAge} and {@code seasons}.
 *
 * <p>The tests exercise {@link CropGrowthConfig#parseOverride(String, int)} — the
 * package-private overload that takes an explicit default days-per-stage — because
 * {@link CropGrowthConfig#refreshOverrides()} reads {@code ModConfigSpec.ConfigValue}s
 * whose {@code get()} throws before the config is loaded in a headless JVM. The parsing
 * itself is a pure function over the override string, so these tests still cover the
 * real built-in entries verbatim.</p>
 */
class CropGrowthConfigTest {

    private static CropGrowthConfig.CropOverride parseOverrideOf(String entry) {
        CropGrowthConfig.ParsedOverride parsed = CropGrowthConfig.parseOverride(entry, 3);
        assertNotNull(parsed);
        return parsed.override();
    }

    @Test
    void builtInBuddingTomatoes_freezeAndTransform() {
        CropGrowthConfig.CropOverride override = parseOverrideOf(
                "farmersdelight:budding_tomatoes=daysPerStage=1,transformBlock=farmersdelight:tomatoes,freeze=true");
        // Stage 1: one stage per suitable day, frozen in unsuitable seasons,
        // and transforms into the full tomato crop when mature.
        assertTrue(override.freeze);
        assertEquals(1, override.daysPerStage);
        assertNotNull(override.transformBlock);
        assertEquals("farmersdelight:tomatoes", override.transformBlock.toString());
    }

    @Test
    void builtInTomatoes_climbFields() {
        CropGrowthConfig.CropOverride override = parseOverrideOf(
                "farmersdelight:tomatoes=daysPerStage=3,freeze=true,climbBlock=farmersdelight:tomatoes_on_rope,climbSupport=farmersdelight:rope,maxClimbHeight=2");
        assertTrue(override.freeze);
        assertNotNull(override.climbBlock);
        assertEquals("farmersdelight:tomatoes_on_rope", override.climbBlock.toString());
        assertNotNull(override.climbSupport);
        assertEquals("farmersdelight:rope", override.climbSupport.toString());
        assertEquals(2, override.maxClimbHeight);
    }

    @Test
    void builtInTomatoesOnRope_sharedClimbConfig() {
        CropGrowthConfig.CropOverride override = parseOverrideOf(
                "farmersdelight:tomatoes_on_rope=daysPerStage=3,freeze=true,climbBlock=farmersdelight:tomatoes_on_rope,climbSupport=farmersdelight:rope,maxClimbHeight=2");
        assertNotNull(override.climbBlock);
        assertEquals("farmersdelight:tomatoes_on_rope", override.climbBlock.toString());
        assertEquals("farmersdelight:rope", override.climbSupport.toString());
        assertEquals(2, override.maxClimbHeight);
    }

    @Test
    void builtInPitcherCrop_doubleAge() {
        CropGrowthConfig.CropOverride override = parseOverrideOf(
                "minecraft:pitcher_crop=daysPerStage=3,doubleAge=3");
        // Two-block crop: the upper half appears once the lower reaches age 3.
        assertEquals(3, override.doubleAge);
        assertNull(override.transformBlock);
        assertNull(override.climbBlock);
    }

    @Test
    void builtInFlax_freezeAndDoubleAge() {
        CropGrowthConfig.CropOverride override = parseOverrideOf(
                "supplementaries:flax=daysPerStage=3,doubleAge=4,freeze=true");
        // D3: flax freezes in unsuitable seasons instead of mutating to short
        // grass, while still growing its upper half once age reaches 4.
        assertTrue(override.freeze);
        assertEquals(4, override.doubleAge);
        assertEquals(3, override.daysPerStage);
    }

    @Test
    void builtInRice_companionRequiresAir() {
        // FD native RiceBlock.advanceAge places rice_panicles only when the position
        // above is AIR (isEmptyBlock), so waterCompanion must be false (no water=true).
        CropGrowthConfig.CropOverride override = parseOverrideOf(
                "farmersdelight:rice=daysPerStage=3,topBlock=farmersdelight:rice_panicles");
        assertFalse(override.waterCompanion);
        assertNotNull(override.topBlock);
        assertEquals("farmersdelight:rice_panicles", override.topBlock.toString());
    }

    @Test
    void builtInRicePanicles_freeze() {
        CropGrowthConfig.CropOverride override = parseOverrideOf(
                "farmersdelight:rice_panicles=daysPerStage=3,freeze=true");
        assertTrue(override.freeze);
        assertEquals(3, override.daysPerStage);
    }

    @Test
    void builtInSunflower_seasonsOverride() {
        CropGrowthConfig.CropOverride override = parseOverrideOf(
                "adorablehamsterpets:sunflower_block=daysPerStage=3,seasons=spring_autumn");
        assertEquals(3, override.daysPerStage);
        assertNotNull(override.seasons);
        assertEquals(Set.of(Season.SPRING, Season.AUTUMN), override.seasons);
    }

    @Test
    void debugFlaxAllConfig_existsAndDefaultsFalse() {
        // The flax tracing switch must exist and default to off so normal play
        // is unaffected until the user explicitly opts in alongside debugLogging.
        assertNotNull(CropGrowthConfig.DEBUG_FLAX_ALL);
        assertFalse(CropGrowthConfig.DEBUG_FLAX_ALL.getDefault());
    }

    @Test
    void unknownCrop_hasNoOverride() {
        // The override cache starts empty and no built-in covers vanilla wheat.
        assertNull(CropGrowthConfig.getOverride(ResourceLocation.parse("minecraft:wheat")));
    }
}
