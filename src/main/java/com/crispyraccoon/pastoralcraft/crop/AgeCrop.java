package com.crispyraccoon.pastoralcraft.crop;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Descriptor for a single-block AGE crop.
 *
 * <p>Any crop that grows through an {@code age} integer property is classified
 * as an {@link CropKind#AGE} crop. The calendar system advances the age one
 * stage per configured number of solar days.</p>
 *
 * @param ageProperty the integer {@code age} property
 * @param maxAge      the maximum allowed age value
 */
public record AgeCrop(IntegerProperty ageProperty, int maxAge) {

    /**
     * Build the block state for the given age, clamped to the valid range.
     *
     * @param base the current (or default) block state
     * @param age  the desired age
     * @return the state with the age property set
     */
    public BlockState stateForAge(BlockState base, int age) {
        int clamped = Math.min(Math.max(age, 0), maxAge);
        return base.setValue(ageProperty, clamped);
    }
}
