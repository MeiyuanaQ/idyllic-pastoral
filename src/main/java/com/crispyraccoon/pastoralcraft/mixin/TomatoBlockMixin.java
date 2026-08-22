package com.crispyraccoon.pastoralcraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels FarmersDelight's vanilla randomTick growth for {@code TomatoBlock}
 * (the rope-climbing crop, i.e. the {@code tomato_crop} / {@code tomatoes_on_rope}
 * block that grows {@code VINE_AGE} 0..3).
 *
 * <p>PastoralCraft's deterministic catch-up loops
 * ({@code CropGrowthTracker.periodicCatchUpCheckInternal} /
 * {@code onChunkLoadInternal}) drive tomato vine climbing instead, via
 * {@code CropGrowthTracker.tryClimbVine}, which counts only suitable-season days
 * (one vine segment per suitable day, capped by {@code maxClimbHeight}).</p>
 *
 * <p>A string target is used so this mod compiles without a compile-time dependency
 * on FarmersDelight; Mixin resolves the target at runtime and skips it silently if
 * FarmersDelight is absent. The block's {@code tick} survival check (scheduled by
 * {@code updateShape} when support is removed) is intentionally left untouched, so
 * vine segments still pop off when the rope is broken.</p>
 */
@Mixin(targets = "vectorwing.farmersdelight.common.block.TomatoBlock")
public abstract class TomatoBlockMixin {

    @Inject(
            method = "randomTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void pastoralcraft$cancelVanillaGrowth(BlockState state, ServerLevel level,
                                                   BlockPos pos, RandomSource random, CallbackInfo ci) {
        ci.cancel();
    }
}
