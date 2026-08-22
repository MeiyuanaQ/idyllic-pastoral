package com.crispyraccoon.pastoralcraft.crop;

import net.minecraft.world.level.block.Block;

/**
 * Descriptor for a height-based crop that grows upward and is tracked by its
 * root block (e.g. sugar cane, kelp).
 *
 * <p>Only the bottom block is tracked; growth places new blocks on top up to
 * {@link #maxHeight}. Water-grown crops ({@link #growsInWater}) additionally
 * require the position above to be water before growing.</p>
 *
 * @param maxHeight    the maximum total stalk height (including the root)
 * @param plantBlock   the block used for the growing head (root)
 * @param growsInWater whether growth requires the position above to be water
 */
public record HeightCrop(int maxHeight, Block plantBlock, boolean growsInWater) {
}
