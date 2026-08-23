package com.crispyraccoon.pastoralcraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin on {@link CactusBlock#randomTick} to block vanilla random-tick-based
 * cactus growth.
 *
 * <p>PastoralCraft handles cactus growth deterministically via the calendar
 * system in {@link com.crispyraccoon.pastoralcraft.crop.CropGrowthTracker}
 * (height-based, non-arable freeze, never mutates). Vanilla random ticks would
 * interfere with this deterministic schedule, so this mixin cancels the vanilla
 * randomTick method entirely. Only {@code randomTick} is cancelled — the
 * scheduled {@code tick} that destroys unsupported cactus stays untouched.</p>
 */
@Mixin(CactusBlock.class)
public abstract class CactusBlockMixin {

    /**
     * Cancels the vanilla {@code randomTick} method so that cactus growth
     * is exclusively controlled by the PastoralCraft calendar system.
     *
     * @param state  the block state of the cactus
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
