package com.crispyraccoon.pastoralcraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin cancelling vanilla random-tick kelp growth, restricted to kelp only.
 *
 * <p><b>Mapping note:</b> In Mojang mappings 1.21.1, {@link KelpBlock} does
 * <b>not</b> override {@code randomTick} — the method is declared on the abstract
 * parent {@link GrowingPlantHeadBlock} and inherited. Injecting {@code randomTick}
 * on {@code @Mixin(KelpBlock.class)} would therefore land in the parent's method
 * body and cancel random ticks for <em>every</em> head-growing plant
 * (weeping/twisting vines, cave vines, ...).</p>
 *
 * <p>To cancel vanilla growth for kelp <em>only</em> without affecting the other
 * head plants, this mixin targets {@link GrowingPlantHeadBlock} and guards the
 * cancellation with an {@code instanceof KelpBlock} check — the cancel applies
 * exclusively to {@link KelpBlock} instances, leaving all other head plants'
 * vanilla random-tick growth untouched.</p>
 */
@Mixin(GrowingPlantHeadBlock.class)
public abstract class KelpBlockMixin {

    /**
     * Cancels the vanilla {@code randomTick} growth for {@link KelpBlock} only.
     *
     * <p>PastoralCraft handles kelp growth deterministically via the calendar
     * system in {@link com.crispyraccoon.pastoralcraft.crop.CropGrowthTracker}
     * (height-based, water-crop freeze, never mutates). Vanilla random ticks would
     * interfere with this deterministic schedule, so kelp's random-tick growth is
     * cancelled entirely. All other {@link GrowingPlantHeadBlock} subclasses are
     * unaffected and keep their vanilla behavior.</p>
     *
     * @param state  the block state of the head block
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
        if (!((Object) this instanceof KelpBlock)) return;
        ci.cancel();
    }
}
