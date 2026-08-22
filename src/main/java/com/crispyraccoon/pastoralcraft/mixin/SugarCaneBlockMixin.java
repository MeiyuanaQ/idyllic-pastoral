package com.crispyraccoon.pastoralcraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin on {@link SugarCaneBlock#randomTick} to block vanilla random-tick-based
 * sugar cane growth.
 *
 * <p>PastoralCraft handles sugar cane growth deterministically via the calendar
 * system in {@link com.crispyraccoon.pastoralcraft.crop.CropGrowthTracker}.
 * Vanilla random ticks would interfere with this deterministic schedule, so
 * this mixin cancels the vanilla randomTick method entirely.</p>
 */
@Mixin(SugarCaneBlock.class)
public abstract class SugarCaneBlockMixin {

    /**
     * Cancels the vanilla {@code randomTick} method so that sugar cane growth
     * is exclusively controlled by the PastoralCraft calendar system.
     *
     * @param state  the block state of the sugar cane
     * @param level  the server level
     * @param pos    the block position
     * @param random the random source
     * @param ci     callback info (cancellable)
     */
    @Inject(
            method = "randomTick",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onRandomTick(BlockState state, ServerLevel level, BlockPos pos,
                               RandomSource random, CallbackInfo ci) {
        ci.cancel();
    }
}