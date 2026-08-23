package com.crispyraccoon.pastoralcraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.PitcherCropBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin on {@link PitcherCropBlock#randomTick} to block vanilla random-tick-based
 * pitcher crop growth.
 *
 * <p>Unlike {@link net.minecraft.world.level.block.CropBlock}, whose
 * {@code randomTick} routes through {@code growCrops} (which fires NeoForge's
 * {@code CropGrowEvent.Pre}, where PastoralCraft denies vanilla growth),
 * {@code PitcherCropBlock.randomTick} calls its private {@code grow(...)} method
 * directly and writes via {@code Level.setBlock}. That bypasses the event, so
 * pitcher crops kept growing on vanilla random ticks alongside the calendar
 * system. This mixin cancels {@code randomTick} entirely so growth is exclusively
 * driven by the deterministic calendar (AGE + DOUBLE via
 * {@code crop_structure} {@code doubleAge: 3}).</p>
 *
 * <p>Only {@code randomTick} is cancelled — the scheduled {@code tick} survival
 * checks (light / support) stay untouched, and bonemeal growth is still handled
 * by the {@code LevelMixin} age-increase interception.</p>
 */
@Mixin(PitcherCropBlock.class)
public abstract class PitcherCropBlockMixin {

    /**
     * Cancels the vanilla {@code randomTick} method so that pitcher crop growth
     * is exclusively controlled by the PastoralCraft calendar system.
     *
     * @param state  the block state of the pitcher crop
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
    private void pastoralcraft$cancelVanillaGrowth(BlockState state, ServerLevel level,
                                                   BlockPos pos, RandomSource random, CallbackInfo ci) {
        ci.cancel();
    }
}
