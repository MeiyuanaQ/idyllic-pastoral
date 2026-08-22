package com.crispyraccoon.pastoralcraft.mixin;

import com.crispyraccoon.pastoralcraft.PastoralCraft;
import com.crispyraccoon.pastoralcraft.crop.CropGrowthConfig;
import com.crispyraccoon.pastoralcraft.crop.FlaxDiagnostics;
import com.crispyraccoon.pastoralcraft.crop.InternalGrowthFlag;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Neutralizes the {@code FlaxBlock} self-destruction that shatters the plant on
 * the 4→5 (single → double) transition. The shatter comes from two entry points,
 * both of which this mixin covers:
 *
 * <ul>
 *   <li><b>(a) external {@code growCropBy}</b> — {@code growCropBy} writes the
 *   upper half with flag 2 but the lower half with flag 3
 *   ({@code UPDATE_NEIGHBORS}), reachable via bee pollination
 *   ({@code getPollinated}), bonemeal ({@code growCrops}) and (possibly)
 *   {@code randomTick}. The flag-3 write is downgraded to flag 2.</li>
 *   <li><b>(b) internal {@code updateShape} self-destruct</b> — PastoralCraft's
 *   own growth paths write with flag 2, but the vanilla/NeoForge update pipeline
 *   still fires a neighbor {@code updateShape} on the transiently-mismatched
 *   UPPER half when the LOWER half advances 4→5, so the internal catch-up path
 *   ALSO shatters flax. Inside the {@code InternalGrowthFlag} window this
 *   {@code updateShape} self-destruct is short-circuited by returning the
 *   unchanged state.</li>
 * </ul>
 *
 * <p>A string target is used so this mod compiles without a compile-time
 * dependency on Supplementaries; Mixin resolves the target at runtime and skips
 * it silently if Supplementaries is absent.</p>
 */
@Mixin(targets = "net.mehvahdjukaar.supplementaries.common.block.blocks.FlaxBlock")
public abstract class FlaxBlockMixin {

    /**
     * Logs the external {@code growCropBy} call site (bee pollination, bonemeal,
     * Supplementaries randomTick) before it writes either half. Combined with the
     * {@code LevelMixin} setBlock hook this closes the "call site + actual write"
     * loop for flax growth.
     *
     * @param level     the level being grown in
     * @param pos       the block position (LOWER half as called by growCropBy)
     * @param state     the block state being grown
     * @param increment the requested age increase
     * @param ci        the injection callback info (unused)
     */
    @Inject(
            method = "growCropBy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V",
            at = @At("HEAD")
    )
    private void pastoralcraft$traceGrowCropBy(Level level, BlockPos pos, BlockState state, int increment, CallbackInfo ci) {
        if (FlaxDiagnostics.enabled()) {
            int newAge = Math.min(FlaxDiagnostics.getAge(state) + increment, FlaxDiagnostics.getMaxAge());
            FlaxDiagnostics.logDecision("external growCropBy pos={} state={} increment={} -> newAge={}",
                    pos, state, increment, newAge);
        }
    }

    /**
     * Wraps the two {@code Level.setBlock(BlockPos, BlockState, int)} calls inside
     * {@code growCropBy} and downgrades the flag-3 (LOWER) write to flag 2. The
     * flag-2 (UPPER) write is passed through unchanged.
     *
     * <p>The full method descriptor is used so the injection cannot accidentally
     * match a same-named inherited method.</p>
     *
     * @param level    the level being written to (the {@code setBlock} receiver)
     * @param pos      the block position being written
     * @param state    the block state being written
     * @param flags    the original setBlock flags (2 for UPPER, 3 for LOWER)
     * @param original the original {@code setBlock} invocation
     * @return the result of the (possibly flag-downgraded) {@code setBlock} call
     */
    @WrapOperation(
            method = "growCropBy(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"
            )
    )
    private boolean pastoralcraft$downgradeFlaxSetBlockFlags(Level level, BlockPos pos, BlockState state,
                                                             int flags, Operation<Boolean> original) {
        if (flags == 3) {
            // Instrumentation: log the downgrade site so in-game verification can
            // confirm this mixin is active. After the fix this must never appear
            // with flag-3 for flax; if it does, some other flag-3 writer is at play.
            if (CropGrowthConfig.DEBUG_LOGGING.get()) {
                PastoralCraft.LOGGER.debug(
                        "[FlaxBlock] external growCropBy flag-3 -> downgraded to flag-2 at {}", pos);
            }
            return original.call(level, pos, state, 2);
        }
        return original.call(level, pos, state, flags);
    }

    /**
     * Neutralizes {@code FlaxBlock.updateShape} self-destruction during PastoralCraft's
     * internal growth window (periodic/chunk-load catch-up, maturity side-effects,
     * {@code placeDoubleUpperHalf}).
     *
     * <p>When the tracker advances the flax LOWER half (e.g. 4→5) with flag 2, the
     * vanilla/NeoForge block-update pipeline still fires a neighbor {@code updateShape}
     * on the pre-existing UPPER half. At that instant the halves are transiently
     * mismatched (lower age changed, upper half not yet refreshed), so
     * {@code FlaxBlock.updateShape} returns {@code Blocks.AIR} for both halves and the
     * plant shatters. Because this happens inside {@code InternalGrowthFlag}'s guard,
     * we short-circuit the self-destruct to keep the transient state intact; the tracker
     * guarantees a consistent final state via {@code placeDoubleUpperHalf}.</p>
     *
     * <p>External writers ({@code growCropBy}, player breakage) run with the guard
     * {@code false} and keep the original integrity checks.</p>
     *
     * @param state       the flax block state being shape-updated
     * @param facing      the direction of the neighbor that triggered the update
     * @param facingState the neighbor's block state
     * @param level       the level accessor
     * @param pos         the flax block position
     * @param facingPos   the neighbor's position
     * @param cir         the injectable return value
     */
    @Inject(
            method = "updateShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"),
            cancellable = true
    )
    private void pastoralcraft$suppressInternalUpdateShapeSelfDestruct(
            BlockState state, Direction facing, BlockState facingState,
            LevelAccessor level, BlockPos pos, BlockPos facingPos,
            CallbackInfoReturnable<BlockState> cir) {
        if (InternalGrowthFlag.INTERNAL_GROWTH.get()) {
            if (CropGrowthConfig.DEBUG_LOGGING.get()) {
                PastoralCraft.LOGGER.debug(
                        "[FlaxBlock] internal updateShape self-destruct suppressed at {} facing={}",
                        pos, facing);
            }
            // Return the unchanged state: equivalent to BlockBehaviour.updateShape's
            // default (no-destruct) behaviour. Keeps LOWER/UPPER halves in place so
            // placeDoubleUpperHalf can refresh the UPPER half afterwards.
            cir.setReturnValue(state);
        }
    }
}
