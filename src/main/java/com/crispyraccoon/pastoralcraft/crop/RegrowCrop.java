package com.crispyraccoon.pastoralcraft.crop;

import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * Descriptor for a boolean-product regrow crop (e.g. the AdorableHamsterPets
 * sunflower whose {@code has_seeds} property indicates whether seeds can be
 * harvested).
 *
 * <p>The product is present when {@link #productProperty()} is {@code true}.
 * Regrowth (setting it back to {@code true} after harvest) is driven purely by
 * the calendar, never by random ticks.</p>
 *
 * @param productProperty the boolean property indicating the product's presence
 */
public record RegrowCrop(BooleanProperty productProperty) {
}
