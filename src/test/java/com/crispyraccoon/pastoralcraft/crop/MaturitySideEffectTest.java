package com.crispyraccoon.pastoralcraft.crop;

import com.crispyraccoon.pastoralcraft.crop.CropGrowthConfig.CropOverride;
import com.crispyraccoon.pastoralcraft.crop.MaturitySideEffects.MaturitySideEffect;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure maturity side-effect decision.
 *
 * <p>{@link CropGrowthTracker#decideSideEffect(StructureDescriptor, int, boolean)}
 * selects the {@link MaturitySideEffect} strategy purely from the structural
 * descriptor fields and the block's bonemeal capability, mirroring the TRANSFORM →
 * COMPANION → DOUBLE → BONEMEAL → NONE priority used by
 * {@link CropGrowthTracker#applyMaturitySideEffects}. No Minecraft level or
 * registry is touched; {@link ResourceLocation} and {@link StructureDescriptor}
 * are plain value types.</p>
 */
class MaturitySideEffectTest {

    private static final ResourceLocation SOME_BLOCK = ResourceLocation.parse("minecraft:air");

    private static StructureDescriptor descriptor(ResourceLocation topBlock,
                                                  ResourceLocation transformBlock,
                                                  int doubleAge) {
        return new StructureDescriptor(doubleAge, topBlock, transformBlock, null, null, 0, null, false, false);
    }

    @Test
    void decideSideEffect_emptyDescriptor_cannotBonemeal_none() {
        assertEquals(MaturitySideEffect.NONE,
                CropGrowthTracker.decideSideEffect(CropStructureRegistry.EMPTY, 0, false));
    }

    @Test
    void decideSideEffect_emptyDescriptor_canBonemeal_bonemeal() {
        assertEquals(MaturitySideEffect.BONEMEAL,
                CropGrowthTracker.decideSideEffect(CropStructureRegistry.EMPTY, 0, true));
    }

    @Test
    void decideSideEffect_transformBlock_winsRegardlessOfOthers() {
        StructureDescriptor d = descriptor(null, SOME_BLOCK, 2);
        assertEquals(MaturitySideEffect.TRANSFORM,
                CropGrowthTracker.decideSideEffect(d, 5, false));
    }

    @Test
    void decideSideEffect_topBlock_companion() {
        StructureDescriptor d = descriptor(SOME_BLOCK, null, -1);
        assertEquals(MaturitySideEffect.COMPANION,
                CropGrowthTracker.decideSideEffect(d, 7, false));
    }

    @Test
    void decideSideEffect_doubleAge_nonNegative_double() {
        StructureDescriptor d = descriptor(null, null, 4);
        assertEquals(MaturitySideEffect.DOUBLE,
                CropGrowthTracker.decideSideEffect(d, 4, false));
    }

    @Test
    void decideSideEffect_bareDescriptor_canBonemeal_bonemeal() {
        // A descriptor with no transform/top/double side effects still falls back
        // to the native bonemeal path when possible.
        StructureDescriptor d = descriptor(null, null, -1);
        assertEquals(MaturitySideEffect.BONEMEAL,
                CropGrowthTracker.decideSideEffect(d, 7, true));
    }

    @Test
    void decideSideEffect_bareDescriptor_cannotBonemeal_none() {
        StructureDescriptor d = descriptor(null, null, -1);
        assertEquals(MaturitySideEffect.NONE,
                CropGrowthTracker.decideSideEffect(d, 7, false));
    }

    @Test
    void override_freezeFlagCarries() {
        // freeze is a behavior flag carried on the config override; it does not
        // alter the maturity side-effect decision but must round-trip through the holder.
        CropOverride override = new CropOverride(3, null, null, null, false, -1,
                true, null, null, 0);
        assertTrue(override.freeze);
    }

    @Test
    void override_climbFieldsCarry() {
        // The config override still carries climb keys for user-facing overrides;
        // they round-trip independently of the structural data map.
        CropOverride override = new CropOverride(3, null, null, null, false, -1,
                false, SOME_BLOCK, SOME_BLOCK, 4);
        assertNotNull(override.climbBlock);
        assertEquals(SOME_BLOCK, override.climbBlock);
        assertEquals(SOME_BLOCK, override.climbSupport);
        assertEquals(4, override.maxClimbHeight);
    }
}
