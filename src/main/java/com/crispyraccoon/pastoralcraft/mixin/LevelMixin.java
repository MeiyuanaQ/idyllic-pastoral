package com.crispyraccoon.pastoralcraft.mixin;

import com.crispyraccoon.pastoralcraft.crop.CropGrowthTracker;
import com.crispyraccoon.pastoralcraft.crop.CropKindResolver;
import com.crispyraccoon.pastoralcraft.crop.FlaxDiagnostics;
import com.crispyraccoon.pastoralcraft.crop.InternalGrowthFlag;
import com.crispyraccoon.pastoralcraft.crop.RegrowCrop;
import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Mixin on {@link Level#setBlock} to systematically track all crop block changes.
 *
 * <p>This is the single entry point for crop tracking, covering <b>all</b> block
 * placement mechanisms — player planting, villager farming, auto-replant mods,
 * tech mod automation, world generation, and more. It replaces the need for
 * separate event handlers on {@code EntityPlaceEvent} and {@code BreakEvent}.</p>
 *
 * <h3>Defensive logic</h3>
 * <ul>
 *   <li><b>Client-side filter:</b> Immediately returns on client to avoid
 *       unnecessary overhead and data corruption.</li>
 *   <li><b>Unified crop detection:</b> Uses {@link CropGrowthTracker#isGrowableCrop(Block)}
 *       which covers {@link net.minecraft.world.level.block.CropBlock},
 *       {@link net.minecraft.world.level.block.StemBlock},
 *       {@link net.minecraft.world.level.block.NetherWartBlock},
 *       {@link net.minecraft.world.level.block.CocoaBlock}, and
 *       {@link net.minecraft.world.level.block.SweetBerryBushBlock}.</li>
 *   <li><b>Crop replacement detection:</b> When {@code isOldCrop && isNewCrop}
 *       but the block types differ (e.g. wheat → carrot), it's treated as a
 *       new planting — the old entry is removed and a new one is created.</li>
 *   <li><b>Age-aware same-crop handling:</b> When old and new are the same crop
 *       type, ages are compared:
 *       <ul>
 *         <li>{@code newAge > oldAge}: natural growth — no action.</li>
 *         <li>{@code newAge < oldAge}: harvest + auto-replant (e.g. Quark
 *             right-click harvest sets age 7 → 0 without going through air) —
 *             reset tracking with fresh {@code plantedDay}.</li>
 *       </ul></li>
 *   <li><b>Untrack on destruction:</b> When {@code isOldCrop && !isNewCrop}
 *       (crop → non-crop), the position is removed from tracking to prevent
 *       memory leaks from automated farms on multiplayer servers.</li>
 * </ul>
 *
 * <p>Wrapped around {@code setBlock(BlockPos, BlockState, int, int)} — the
 * deepest setBlock variant — with {@link WrapMethod}, so the decision tree runs
 * before the original method and the REGROW revert-stack cleanup runs in a
 * {@code finally} block. At the point the decision tree runs,
 * {@code level.getBlockState(pos)} still returns the old state, so we can
 * compare old vs new.</p>
 */
@Mixin(Level.class)
public abstract class LevelMixin {

    /**
     * LIFO stack pairing every non-internal {@code setBlock} call with its cleanup.
     * Pushes a {@link #NO_REVERT} sentinel frame for every call; the same-block
     * REGROW branch replaces the top frame with the pre-regrowth state when a
     * non-internal regrowth (has_seeds false→true) is detected, so the {@code finally}
     * cleanup reverts it. INTERNAL_GROWTH-guarded calls skip both push and pop,
     * keeping nested calls correctly paired.
     */
    @Unique
    private static final ThreadLocal<ArrayDeque<BlockState>> REGROW_REVERT_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);

    /**
     * Non-null sentinel for revert-stack frames that hold nothing to revert.
     * {@link ArrayDeque} rejects null elements, so every non-internal setBlock
     * pushes this sentinel instead of null; the {@code finally} cleanup pops it and
     * treats it as a no-op. {@code defaultBlockState()} returns the cached
     * {@code any()} singleton, so reference equality is safe.
     */
    @Unique
    private static final BlockState NO_REVERT = Blocks.AIR.defaultBlockState();

    /**
     * Wraps the 4-parameter {@code setBlock} to track crop placement and removal,
     * and to manage the REGROW revert stack with exception-safe cleanup.
     *
     * <p>The 4-parameter variant is the deepest implementation; the 3-parameter
     * convenience method delegates to it, so all setBlock calls are intercepted
     * exactly once. The whole decision tree plus the revert-stack pop live inside
     * a single {@code try/finally} around {@code original.call(...)}, so an
     * exception thrown by the original {@code setBlock} still pops the pushed
     * frame — fixing the previous HEAD/RETURN inject pairing, where a throwing
     * {@code setBlock} skipped the RETURN inject and leaked a frame, corrupting
     * the LIFO alignment of later calls.</p>
     *
     * @param pos            the block position being changed
     * @param newState       the block state that will be set
     * @param flags          block update flags
     * @param recursionLimit recursion limit for block updates
     * @param original       the original {@code setBlock} method body
     * @return the result of the original {@code setBlock}
     */
    @WrapMethod(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z"
    )
    private boolean pastoralcraft$wrapSetBlock(BlockPos pos, BlockState newState, int flags, int recursionLimit,
                                               Operation<Boolean> original) {
        Level self = (Level) (Object) this;

        // --- Flax diagnostics hook (server-only) — placed BEFORE the internal
        // early-out so both internal tracker writes and external writers
        // (Supplementaries growCropBy, bees, bonemeal, players) are captured. ---
        // When diagnostics are disabled, this costs one boolean check plus a
        // couple of reference compares — no log string is ever built.
        if (!self.isClientSide() && FlaxDiagnostics.enabled()
                && (FlaxDiagnostics.isFlax(newState.getBlock())
                    || FlaxDiagnostics.isFlax(self.getBlockState(pos).getBlock()))) {
            BlockState oldForDiag = self.getBlockState(pos);
            boolean internal = InternalGrowthFlag.INTERNAL_GROWTH.get();
            FlaxDiagnostics.logSetBlock(self, pos, oldForDiag, newState, flags,
                    internal ? "internal" : "external");
        }

        // Client-side filter — avoid unnecessary overhead and data corruption.
        // Re-entrancy guard: skip when internal growth operations are performing
        // setBlock calls to avoid duplicate recording. Both early-outs must run
        // before the revert frame is pushed so internal/nested calls stay paired.
        if (self.isClientSide() || InternalGrowthFlag.INTERNAL_GROWTH.get()) {
            return original.call(pos, newState, flags, recursionLimit);
        }

        // Every non-internal setBlock pushes a frame (NO_REVERT sentinel default)
        // onto the revert stack; the finally block below pops it. A REGROW
        // regrowth (has_seeds false→true) replaces the frame so it can be reverted.
        ArrayDeque<BlockState> revertStack = REGROW_REVERT_STACK.get();
        revertStack.push(NO_REVERT);

        try {
            // Read old state here — the block hasn't been changed yet, so
            // getBlockState(pos) returns the state that is about to be replaced.
            //
            // Note: this read is intentional and necessary even though the vanilla
            // setBlock implementation also reads the old state internally — without
            // it we cannot tell whether the replaced block was a crop, which is
            // required to untrack crops on destruction. Do not "optimize" this away.
            BlockState oldState = self.getBlockState(pos);

            Block oldBlock = oldState.getBlock();
            Block newBlock = newState.getBlock();
            boolean isOldCrop = CropGrowthTracker.isGrowableCrop(oldBlock);
            boolean isNewCrop = CropGrowthTracker.isGrowableCrop(newBlock);

            // --- AttachedStemBlock → StemBlock: fruit harvested ---
            // When a player breaks the pumpkin/melon, the AttachedStemBlock reverts
            // to StemBlock at age 0 (or whatever age it was before fruiting). This
            // is a vanilla behavior — AttachedStemBlock → StemBlock transition.
            if (oldBlock instanceof net.minecraft.world.level.block.AttachedStemBlock
                    && newBlock instanceof net.minecraft.world.level.block.StemBlock) {
                // Fruit was harvested, stem reverted to a mature StemBlock. Back-calculate
                // plantedDay from the reverted stem's age (MAX_AGE) so the stem is treated
                // as "just matured" and fruits again after daysPerFruit — instead of
                // resetPlantedDay(currentDay) which would make the mature stem wait a full
                // maxAge * daysPerStage growth cycle before fruiting again.
                CropGrowthTracker.onStemFruitHarvest(self, pos, newState);
                return original.call(pos, newState, flags, recursionLimit);
            }

            // --- SugarCane bottom-only tracking ---
            // Only the bottom (root) block is tracked. When a non-bottom block is
            // replaced:
            //   - sugar cane -> non-crop (harvest): reset the root's plantedDay so
            //     the surviving stalk continues growth from its current height.
            //   - sugar cane -> crop: treat as a new planting here and let
            //     getOrCreate handle it; the root entry is left intact.
            if (isOldCrop && oldBlock instanceof net.minecraft.world.level.block.SugarCaneBlock) {
                BlockState below = self.getBlockState(pos.below());
                if (below.getBlock() instanceof net.minecraft.world.level.block.SugarCaneBlock) {
                    isOldCrop = false;
                    if (!isNewCrop) {
                        CropGrowthTracker.onSugarCaneHarvest(self, pos);
                    }
                }
            }

            // --- Kelp bottom-only tracking + internal head/stem conversion ---
            // Kelp grows by converting its top KelpBlock into a KelpPlantBlock and
            // placing a fresh KelpBlock above (see CropGrowthTracker.growKelp). That
            // internal conversion (kelp → kelp) must NOT reach the "crop A → crop B"
            // branch below, which would remove the root entry and recreate it with
            // plantedDay = currentDay — resetting growth progress for a single-block
            // kelp whose root IS its head. So kelp → kelp transitions are skipped.
            if ((oldBlock instanceof KelpBlock || oldBlock instanceof KelpPlantBlock)
                    && (newBlock instanceof KelpBlock || newBlock instanceof KelpPlantBlock)) {
                return original.call(pos, newState, flags, recursionLimit);
            }
            // Kelp → non-kelp with kelp below: mid-stalk break/harvest. Reset the
            // root's plantedDay so the surviving stalk continues from its height.
            if (isOldCrop && (oldBlock instanceof KelpBlock || oldBlock instanceof KelpPlantBlock)) {
                BlockState below = self.getBlockState(pos.below());
                if (below.getBlock() instanceof KelpBlock || below.getBlock() instanceof KelpPlantBlock) {
                    isOldCrop = false;
                    if (!isNewCrop) {
                        CropGrowthTracker.onKelpHarvest(self, pos);
                    }
                }
            }

            if (!isOldCrop && isNewCrop) {
                // Non-crop → crop: a new crop was planted.
                // HEIGHT crops (kelp / sugar cane): a new head placed ABOVE an
                // existing stalk is bonemeal or an external mod placement — internal
                // growth is already filtered by the re-entrancy guard above. Back-
                // shift the root plantedDay so the calendar target stays aligned. A
                // fresh planting (nothing of the same family below) falls through to
                // getOrCreate.
                Block belowBlock = self.getBlockState(pos.below()).getBlock();
                if (newBlock instanceof KelpBlock
                        && (belowBlock instanceof KelpBlock || belowBlock instanceof KelpPlantBlock)) {
                    CropGrowthTracker.onKelpBonemeal(self, pos);
                } else if (newBlock instanceof net.minecraft.world.level.block.SugarCaneBlock
                        && belowBlock instanceof net.minecraft.world.level.block.SugarCaneBlock) {
                    CropGrowthTracker.onSugarCaneBonemeal(self, pos);
                } else {
                    CropGrowthTracker.getOrCreate(pos, self, newState);
                }
            } else if (isOldCrop && isNewCrop && oldBlock != newBlock) {
                // Crop A → Crop B: replacement (e.g. wheat replaced by carrots).
                // Treat as a new planting — remove the old entry and create a new one.
                CropGrowthTracker.removePosition(pos, self);
                CropGrowthTracker.getOrCreate(pos, self, newState);
            } else if (isOldCrop && isNewCrop /* oldBlock == newBlock */) {
                // Same crop type — either a boolean-product REGROW crop (e.g.
                // sunflower has_seeds) or an age-based crop.
                RegrowCrop regrow = CropKindResolver.regrowOf(oldBlock);
                if (regrow != null) {
                    BooleanProperty product = regrow.productProperty();
                    boolean oldHas = oldState.getValue(product);
                    boolean newHas = newState.getValue(product);
                    if (oldHas && !newHas) {
                        // Harvested: has_seeds true → false. Restart the regrowth
                        // countdown from the current day so re-harvesting requires
                        // the full calendar delay again.
                        CropGrowthTracker.resetPlantedDay(pos, self);
                    } else if (!oldHas && newHas) {
                        // Non-internal regrowth (e.g. the mod's randomTick setValue):
                        // replace this call's placeholder frame so the finally block
                        // reverts it — regrowth must be driven purely by the calendar.
                        revertStack.pop();
                        revertStack.push(oldState);
                    }
                    // else: product unchanged — no-op
                } else {
                    // Same age-based crop type — compare ages to distinguish natural
                    // growth from harvest+replant (e.g. Quark right-click harvest
                    // replaces age=7 → age=0 without going through air, so
                    // oldBlock == newBlock).
                    int oldAge = CropGrowthTracker.getCropAge(oldState);
                    int newAge = CropGrowthTracker.getCropAge(newState);
                    if (oldAge >= 0 && newAge >= 0 && newAge < oldAge) {
                        // Age decreased: harvest + auto-replant. Reset tracking with a
                        // fresh plantedDay so the new crop starts from the current day.
                        CropGrowthTracker.removePosition(pos, self);
                        CropGrowthTracker.getOrCreate(pos, self, newState);
                    } else if (oldAge >= 0 && newAge > oldAge) {
                        // Age increased: bonemeal or rapid growth.
                        if (!CropGrowthTracker.isTracked(self, pos)) {
                            // No existing tracking (e.g. an age>0 crop placed by
                            // another mod): back-calculate plantedDay from the age.
                            CropGrowthTracker.getOrCreate(pos, self, newState);
                        } else if (oldBlock instanceof net.minecraft.world.level.block.StemBlock) {
                            // Melon/pumpkin stem: shift plantedDay back by the
                            // accelerated stages so the calendar stage stays in
                            // lock-step with the world age instead of stalling an
                            // extra daysPerStage per bonemeal stage.
                            CropGrowthTracker.onStemBonemeal(self, pos, oldAge, newAge);
                        } else {
                            // Tracked non-stem crop: conservatively back-shift
                            // plantedDay so the calendar stage stays aligned with
                            // the accelerated world age without crossing into
                            // unsuitable seasons (never mutates).
                            CropGrowthTracker.onCropBonemeal(self, pos, oldAge, newAge, newState);
                        }
                    }
                    // else: ages equal (no-op) — skip
                }
            } else if (isOldCrop && !isNewCrop) {
                // Crop → non-crop: the crop was destroyed (broken, washed away,
                // trampled, pushed by piston, etc.). Remove from tracking to
                // prevent memory leaks in automated farms.
                CropGrowthTracker.removePosition(pos, self);
            }
            // else: both non-crops — skip

            return original.call(pos, newState, flags, recursionLimit);
        } finally {
            // Pairs with the frame pushed above. Pops the frame; when it holds a
            // pre-regrowth state (non-internal has_seeds false→true), re-sets it —
            // the original setBlock already ran, so this restores the pre-regrowth
            // state. Running in finally guarantees cleanup even when the original
            // setBlock throws, so the LIFO stack never leaks a frame.
            BlockState toRevert = revertStack.isEmpty() ? NO_REVERT : revertStack.pop();
            if (toRevert != NO_REVERT) {
                // Revert the non-internal regrowth (has_seeds false→true). Wrap in
                // INTERNAL_GROWTH so this setBlock itself is not tracked or re-pushed.
                InternalGrowthFlag.INTERNAL_GROWTH.set(true);
                try {
                    self.setBlock(pos, toRevert, 2);
                } finally {
                    InternalGrowthFlag.INTERNAL_GROWTH.set(false);
                }
            }
        }
    }
}
