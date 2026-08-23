package com.crispyraccoon.pastoralcraft.event;

import com.crispyraccoon.pastoralcraft.PastoralCraft;
import com.crispyraccoon.pastoralcraft.crop.BlockWriter;
import com.crispyraccoon.pastoralcraft.crop.ChunkCropData;
import com.crispyraccoon.pastoralcraft.crop.CropGrowthConfig;
import com.crispyraccoon.pastoralcraft.crop.CropGrowthTracker;
import com.crispyraccoon.pastoralcraft.crop.CropProgressEntry;
import com.crispyraccoon.pastoralcraft.crop.CropStructureRegistry;
import com.crispyraccoon.pastoralcraft.crop.StructureDescriptor;
import com.crispyraccoon.pastoralcraft.crop.DebugDataHealth;
import com.crispyraccoon.pastoralcraft.crop.DebugGate;
import com.crispyraccoon.pastoralcraft.crop.DebugProfiler;
import com.crispyraccoon.pastoralcraft.crop.DebugRingBuffer;
import com.crispyraccoon.pastoralcraft.crop.DebugWatchdog;
import com.crispyraccoon.pastoralcraft.crop.FlaxDiagnostics;
import com.crispyraccoon.pastoralcraft.crop.InternalGrowthFlag;
import com.crispyraccoon.pastoralcraft.crop.SeasonTagResolver;
import com.teamtea.eclipticseasons.api.constant.solar.Season;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Handles crop growth events for the PastoralCraft mod.
 *
 * <p>Intercepts {@link net.neoforged.neoforge.event.level.block.CropGrowEvent.Pre}
 * at {@link EventPriority#LOWEST} to override both vanilla and Ecliptic Seasons
 * growth behavior. Growth is determined by the {@link CropGrowthTracker} using
 * a deterministic solar-day-based system instead of random ticks.</p>
 *
 * <p>Ecliptic Seasons uses {@link EventPriority#NORMAL} for its crop growth handler,
 * so running at LOWEST ensures our handler runs after ES and can override its result.</p>
 *
 * <p><b>Chunk Persistence (v2 — plantedDay scheme):</b> Crop progress data is stored
 * per-chunk via {@link ChunkCropData} and persisted to NBT through {@link ChunkDataEvent.Save}
 * and {@link ChunkDataEvent.Load}. Each entry stores only the {@code plantedDay} (solar day
 * when the crop was first detected). All other state (growth stage, season transitions)
 * is derived from pure functions of plantedDay + currentDay + config.</p>
 */
@EventBusSubscriber(modid = PastoralCraft.MODID)
public class CropGrowthHandler {

    private static final String NBT_KEY = "pastoralcraft_crop_data";

    static {
        // 硬崩溃安全网：卡死/原生崩溃不会触发 ServerStopping，
        // 任何 JVM 终止（关闭窗口/taskkill/启动器终止）都尽力写 dump。
        try {
            Runtime.getRuntime().addShutdownHook(
                    new Thread(CropGrowthHandler::writeDumpIfEnabled, "pastoralcraft-crash-dump"));
        } catch (Exception ignored) {
            // JVM 已在关停/钩子被禁用 — 尽力而为。
        }
    }

    /** Counter for periodic catch-up check interval (every 200 ticks = 10 seconds) */
    private static int tickCounter = 0;
    private static final int CATCH_UP_CHECK_INTERVAL = 200;

    /** Last processed solar day per level, used to skip periodic scans when the day is unchanged. */
    private static final Map<Level, Integer> lastProcessedSolarDay = new WeakHashMap<>();

    /**
     * Intercepts crop growth before it happens. Always denies vanilla/ES growth
     * and instead computes the target growth stage from the crop's plantedDay
     * via pure functions. If the target stage is ahead of the current age,
     * the crop is advanced in one shot.
     *
     * @param event the crop grow pre-event
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onCropGrowPre(CropGrowEvent.Pre event) {
        BlockState state = event.getState();
        BlockPos pos = event.getPos();
        Level level = (Level) event.getLevel();

        // Only handle growable crop blocks
        Block block = state.getBlock();
        if (!CropGrowthTracker.isGrowableCrop(block)) return;

        // Sugar cane & kelp growth are height-based (handled by the periodic/chunk
        // catch-up in CropGrowthTracker) and have no AGE property. Skip the
        // age-based logic here to avoid mis-processing (getCropAge returns -1).
        // Kelp in particular must never reach the arable mutate-to-short-grass
        // branch below — it is a water crop and freezes in unsuitable seasons.
        if (block instanceof SugarCaneBlock) return;
        if (block instanceof CactusBlock) return;
        if (block instanceof KelpBlock || block instanceof KelpPlantBlock) return;

        // REGROW crops (boolean product, e.g. sunflower has_seeds) have no AGE
        // property and are driven entirely by the tracker's calendar regrowth —
        // never by CropGrowEvent. Skip them here to avoid mis-processing
        // (getCropAge returns -1 and getCropStateForAge returns null).
        if (CropGrowthTracker.isRegrow(block)) return;

        // Always deny vanilla/ES probability-based growth
        event.setResult(CropGrowEvent.Pre.Result.DO_NOT_GROW);

        // StemBlock (melon/pumpkin): the full lifecycle (growth → fruiting →
        // unsuitable-season mutation) is driven by the deterministic stem
        // simulator, mirroring the chunk-load and periodic catch-up paths.
        if (block instanceof StemBlock) {
            int currentDay = CropGrowthTracker.getSolarDays(level);
            CropProgressEntry entry = CropGrowthTracker.getOrCreate(pos, level, state, currentDay);
            if (entry == null) return;
            int seasonLength = CropGrowthTracker.getSeasonLength(level);
            Season currentSeason = CropGrowthTracker.getSeason(level);
            if (CropGrowthTracker.processStem(level, pos, state, entry, currentDay, currentSeason, seasonLength, false)) {
                CropGrowthTracker.removePosition(pos, level);
            }
            return;
        }

        // Check if the crop is already mature (non-StemBlock crops only —
        // StemBlock at MAX_AGE was handled above and stays tracked for fruiting)
        int currentAge = CropGrowthTracker.getCropAge(state);
        int maxAge = CropGrowthTracker.getCropMaxAge(block);
        if (currentAge >= maxAge) {
            // Segmented water rice (KC rice_crop): DOWN at max age still needs
            // the upper segments synced (e.g. after a bonemeal jump left them
            // stale) before the entry is dropped.
            if (CropGrowthTracker.isSegmentedRice(block)) {
                CropGrowthTracker.syncRiceSegments(level, pos, maxAge);
            }
            // Climb crops (Farmers Delight tomatoes) stay tracked past maturity so
            // the catch-up loops can keep driving rope climbing.
            if (!CropGrowthTracker.isClimbCrop(block)) {
                // Override-bearing crops may need a maturity side effect even when
                // they arrive at maxAge without a tracker growth step (bonemeal jump).
                // Run it so TRANSFORM (budding → tomatoes) and COMPANION (rice →
                // panicles) fire instead of silently dropping the entry.
                StructureDescriptor descriptor = CropStructureRegistry.resolve(block);
                boolean hasSideEffect = descriptor.hasTransform() || descriptor.hasCompanion() || descriptor.isDouble();
                if (hasSideEffect && CropGrowthTracker.applyMaturitySideEffects(level, pos, state, block, maxAge)) {
                    return; // TRANSFORM/COMPANION re-registered the crop; keep tracking
                }
                CropGrowthTracker.removePosition(pos, level);
            }
            return;
        }

        // Compute current solar day once and reuse it for both entry creation
        // and target-stage computation (avoids repeated ES API calls).
        int currentDay = CropGrowthTracker.getSolarDays(level);

        // Get or create the progress entry (new entries get plantedDay = currentDay)
        CropProgressEntry entry = CropGrowthTracker.getOrCreate(pos, level, state, currentDay);
        if (entry == null) return;

        // Simulate growth purely from plantedDay and current time
        ResourceLocation cropId = BuiltInRegistries.BLOCK.getKey(block);
        int seasonLength = CropGrowthTracker.getSeasonLength(level);
        Season currentSeason = CropGrowthTracker.getSeason(level);

        int daysPerStage = CropGrowthConfig.getDaysPerStage(cropId);
        Set<Season> suitableSeasons = CropGrowthTracker.resolveSuitableSeasons(currentSeason, block);
        boolean nonArable = CropGrowthTracker.isNonArableAt(level, pos, block);

        var sim = CropGrowthTracker.simulateGrowth(pos, entry.plantedDay, currentDay,
                daysPerStage, maxAge, seasonLength, suitableSeasons, nonArable,
                CropGrowthConfig.getUnsuitableMutateChance(cropId),
                CropGrowthConfig.getUnsuitableGrowChance(cropId));

        // Mutated into short grass during an unsuitable-season growth attempt.
        if (sim.mutated()) {
            CropGrowthTracker.mutateToShortGrass(level, pos, block, BlockWriter.FLAG_UPDATE_NEIGHBORS);
            CropGrowthTracker.removePosition(pos, level);
            if (DebugGate.enabled(DebugGate.DebugModule.GROWTH)) {
                PastoralCraft.LOGGER.debug("[CropHandler] {} at {} mutated to short grass (plantedDay={}, currentDay={})",
                        cropId, pos, entry.plantedDay, currentDay);
            }
            return;
        }

        int targetStage = sim.stage();
        if (targetStage > currentAge) {
            int newAge = Math.min(targetStage, maxAge);
            BlockState newState = CropGrowthTracker.getCropStateForAgePreserving(state, newAge);
            boolean wasInternal = InternalGrowthFlag.INTERNAL_GROWTH.get();
            if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(true);
            try {
                // Double crops (flax, pitcher): place/refresh the UPPER half
                // BEFORE lowering the main block, mirroring Supplementaries'
                // FlaxBlock.growCropBy ordering.
                //
                // The lower block is set with flag 2 (UPDATE_CLIENTS only, no
                // neighbor notification), matching the catch-up loops and vanilla
                // CropBlock.growCrops. This prevents FlaxBlock.updateShape from
                // firing during growth — its UPPER half canSurvive requires the
                // upper and lower ages to match, and a flag-3 neighbor update
                // could transiently break the freshly refreshed upper half,
                // which was observed as flax being destroyed when it kept
                // growing past its double-crop stage.
                CropGrowthTracker.placeDoubleUpperHalf(level, pos, newState, block, newAge);
                BlockWriter.internalSetBlock(level, pos, newState, BlockWriter.FLAG_UPDATE_CLIENTS);
            } finally {
                if (!wasInternal) InternalGrowthFlag.INTERNAL_GROWTH.set(false);
            }

            if (FlaxDiagnostics.enabled() && FlaxDiagnostics.isFlax(block)) {
                FlaxDiagnostics.logDecision("event-path {}->{} pos={} target={} plantedDay={} currentDay={}",
                        currentAge, newAge, pos, targetStage, entry.plantedDay, currentDay);
                FlaxDiagnostics.logSnapshot(level, pos, "event-path post-growth");
            }

            if (DebugGate.enabled(DebugGate.DebugModule.GROWTH)) {
                PastoralCraft.LOGGER.debug("[CropHandler] Advanced {} at {} from age {} to {} (target={}, plantedDay={}, currentDay={})",
                        cropId, pos, currentAge, newAge, targetStage, entry.plantedDay, currentDay);
            }

            // Segmented water rice (KC rice_crop): explicitly sync the MIDDLE/UP
            // segments to the new age — the mod's setCropState uses
            // UPDATE_CLIENTS (no neighbor propagation), so updateShape won't sync.
            if (CropGrowthTracker.isSegmentedRice(block)) {
                CropGrowthTracker.syncRiceSegments(level, pos, newAge);
            }

            // If the crop is now mature, remove from tracker
            // (StemBlock stays tracked even at MAX_AGE for deterministic fruiting)
            if (newAge >= maxAge && !(block instanceof StemBlock)) {
                // Apply data-driven maturity side effects (transform/companion/double/bonemeal).
                // Returns true when a new growable crop was placed and re-registered by
                // placeAndTrack (e.g. tomato_budding -> tomato_crop), in which case the
                // entry is kept so the new phase follows its own calendar rhythm.
                boolean keep = CropGrowthTracker.applyMaturitySideEffects(level, pos, newState, block, newAge);
                if (!keep) {
                    CropGrowthTracker.removePosition(pos, level);
                }
            }
        }
    }

    /**
     * Clear the per-block season cache when data-pack tags are (re)loaded.
     *
     * <p>Runs at {@link EventPriority#LOWEST} so it executes after Ecliptic
     * Seasons' NORMAL handler ({@code AllListener.onTagsUpdatedEvent}) has rebuilt
     * its {@code CropInfoManager} registry from the new tags — only then can the
     * cache be safely invalidated for the fresh season data.</p>
     *
     * @param event the tags updated event
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        SeasonTagResolver.clearCache();
    }

    /**
     * Clear the merged structure-descriptor cache when the block data maps are
     * reloaded (data-pack reload). {@link CropStructureRegistry} caches the merged
     * {@code DataMap + config} result per block, so a server reload that changes
     * {@code crop_structure.json} must invalidate it.
     */
    @SubscribeEvent
    public static void onDataMapsUpdated(DataMapsUpdatedEvent event) {
        event.ifRegistry(Registries.BLOCK, registry -> CropStructureRegistry.clearCache());
    }

    // Crop placement and destruction tracking is now handled exclusively by
    // LevelMixin#onSetBlock, which injects into Level.setBlock() and catches
    // all placement/destruction mechanisms (player actions, villager farming,
    // auto-replant, tech mod automation, water/piston/trampling/explosions, etc.).
    // No separate EntityPlaceEvent or BreakEvent handlers are needed.
    // =======================================================================

    /**
     * Handle chunk load events to process catch-up growth for crops
     * that were unloaded while the chunk was not in memory.
     * With the plantedDay scheme, the pure function computes the target
     * growth stage from first principles — no unload state needed.
     *
     * @param event the chunk load event
     */
    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof Level level && event.getChunk() instanceof LevelChunk chunk) {
            CropGrowthTracker.onChunkLoad(chunk, level);
        }
    }

    /**
     * Periodic server tick handler that proactively checks all loaded chunks for
     * crops that need catch-up growth. This ensures crops grow in unified multi-stage
     * jumps rather than waiting for infrequent random ticks.
     *
     * <p>Without this, crops in loaded chunks only grow via random ticks, which fire
     * ~3 times per second across all 65536 block positions in a chunk — meaning each
     * individual crop waits ~6 minutes between growth attempts. This periodic check
     * runs every 10 seconds and applies all accumulated growth stages at once.</p>
     *
     * <p><b>Performance:</b> Current day and season are computed once per level per
     * cycle (not per chunk or per crop). Uses {@link CropGrowthTracker#getTrackedChunks()}
     * which returns the live WeakHashMap-backed set without copying.</p>
     *
     * @param event the server tick post-event
     */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        tickCounter++;
        DebugWatchdog.heartbeat(event.getServer().getTickCount());
        if (tickCounter < CATCH_UP_CHECK_INTERVAL) return;
        tickCounter = 0;

        long t0 = DebugProfiler.startSection();

        // Compute day/season once per level — same for all chunks in that level
        for (var level : event.getServer().getAllLevels()) {
            int currentDay = CropGrowthTracker.getSolarDays(level);
            DebugWatchdog.levelState(level.dimension().location() + " solarDay=" + currentDay
                    + " chunks=" + CropGrowthTracker.getTrackedChunks().size());

            // Early-out: if the solar day has not advanced since the last check,
            // no crop can have progressed (growth/mutation are pure functions of
            // plantedDay + currentDay), so skip scanning this level entirely.
            Integer lastDay = lastProcessedSolarDay.get(level);
            if (lastDay != null) {
                // Early-out only when the solar day is unchanged AND no stem has a
                // deferred fruit settlement pending (deferral happens in the
                // chunk-load path, which must be re-settled even on the same day).
                if (lastDay == currentDay && !CropGrowthTracker.hasStemSettlementPending(level)) {
                    continue;
                }
                if (currentDay < lastDay) {
                    // Ecliptic Seasons "set" command rewound the solar calendar.
                    // Rebase every tracked plantedDay so crops keep their stage
                    // and resume growing instead of freezing until currentDay
                    // catches back up to the old plantedDay.
                    CropGrowthTracker.rebasePlantedDays(level, lastDay - currentDay);
                }
            }
            lastProcessedSolarDay.put(level, currentDay);
            // Consume the deferred-settlement flag before the scan below: the scan
            // runs with duringChunkLoad=false, so it cannot re-mark this level.
            CropGrowthTracker.clearStemSettlementPending(level);

            Season currentSeason = CropGrowthTracker.getSeason(level);
            int seasonLength = CropGrowthTracker.getSeasonLength(level);

            // Snapshot to avoid ConcurrentModificationException when
            // periodicCatchUpCheck removes chunks from the tracked set.
            var chunksSnapshot = new java.util.ArrayList<>(CropGrowthTracker.getTrackedChunks());
            for (LevelChunk chunk : chunksSnapshot) {
                if (chunk.getLevel() == level) {
                    CropGrowthTracker.periodicCatchUpCheck(chunk, level, currentDay, currentSeason, seasonLength);
                }
            }
        }

        if (t0 != 0L) {
            DebugProfiler.endSection(t0, "onServerTick");
        }
    }

    /**
     * Handle chunk unload events to remove the chunk from the active tracking set.
     * This prevents stale references from accumulating in the trackedChunks
     * WeakHashMap-backed set and avoids wasted iteration during periodic checks.
     *
     * @param event the chunk unload event
     */
    @SubscribeEvent
    public static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof Level && event.getChunk() instanceof LevelChunk chunk) {
            CropGrowthTracker.unregisterTrackedChunk(chunk);
        }
    }

    /**
     * Save crop progress data to chunk NBT for persistence across server restarts.
     * With the plantedDay scheme, each entry stores only the position and the
     * {@code plantedDay} (solar day when the crop was first detected).
     * All other state is derived from pure functions.
     *
     * @param event the chunk data save event
     */
    @SubscribeEvent
    public static void onChunkDataSave(ChunkDataEvent.Save event) {
        ChunkAccess chunk = event.getChunk();
        if (!(chunk instanceof ChunkCropData chunkData)) return;

        Map<BlockPos, CropProgressEntry> cropData = chunkData.pastoralcraft$getCropData();
        if (cropData.isEmpty()) return;

        CompoundTag data = event.getData();
        ListTag cropList = new ListTag();

        for (var entry : cropData.entrySet()) {
            CompoundTag tag = new CompoundTag();
            tag.putLong("pos", entry.getKey().asLong());
            tag.putInt("plantedDay", entry.getValue().plantedDay);
            cropList.add(tag);
        }

        data.put(NBT_KEY, cropList);

        if (DebugGate.enabled(DebugGate.DebugModule.CATCH_UP)) {
            PastoralCraft.LOGGER.debug("[CropHandler] Saved {} crop entries to chunk NBT at {}",
                    cropList.size(), chunk.getPos());
        }
    }

    /**
     * Load crop progress data from chunk NBT after a server restart or chunk load.
     * With the plantedDay scheme, only the {@code plantedDay} is deserialized.
     * The entry is reconstructed with the single-field constructor.
     *
     * @param event the chunk data load event
     */
    @SubscribeEvent
    public static void onChunkDataLoad(ChunkDataEvent.Load event) {
        ChunkAccess chunk = event.getChunk();
        if (!(chunk instanceof ChunkCropData chunkData)) return;

        CompoundTag data = event.getData();
        if (!data.contains(NBT_KEY, Tag.TAG_LIST)) return;

        ListTag cropList = data.getList(NBT_KEY, Tag.TAG_COMPOUND);
        if (cropList.isEmpty()) return;

        Map<BlockPos, CropProgressEntry> cropData = new HashMap<>();

        for (int i = 0; i < cropList.size(); i++) {
            CompoundTag tag = cropList.getCompound(i);
            BlockPos pos = BlockPos.of(tag.getLong("pos"));
            int plantedDay = tag.getInt("plantedDay");
            cropData.put(pos, new CropProgressEntry(plantedDay));
        }

        // Only replace the in-memory map when it is empty; otherwise merge the NBT
        // entries into the existing map so abnormal load timing never drops in-memory data.
        Map<BlockPos, CropProgressEntry> existing = chunkData.pastoralcraft$getCropData();
        if (existing.isEmpty()) {
            chunkData.pastoralcraft$setCropData(cropData);
        } else {
            for (var entry : cropData.entrySet()) {
                existing.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        // Registration for periodic catch-up tracking is deferred to the
        // main-thread ChunkEvent.Load path (onChunkLoadInternal), so no
        // global shared-set write happens here. See
        // plans/catchup-mechanism-fix-impl.md §2 (R2).

        if (DebugGate.enabled(DebugGate.DebugModule.CATCH_UP)) {
            PastoralCraft.LOGGER.debug("[CropHandler] Loaded {} crop entries from chunk NBT at {}",
                    cropList.size(), chunk.getPos());
        }
    }

    // =======================================================================
    // Debug system — shutdown dump (M5) + runtime commands (M6)
    // =======================================================================

    /**
     * Write the in-memory ring buffer / perf counters / data-health summary to a
     * timestamped file under the server's {@code logs/} directory when the server
     * stops. This is the crash-site backtrace: a freeze that suppresses logging is
     * still recoverable from this dump.
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        writeDumpIfEnabled();
    }

    private static void writeDumpIfEnabled() {
        try {
            if (!CropGrowthConfig.DEBUG_DUMP_ON_STOP.get()) return;
        } catch (Exception ignored) {
            // 配置未加载仍尽力写。
        }
        writeDump();
    }

    private static void writeDump() {
        try {
            String dump = "=== PastoralCraft Debug Dump ===\n"
                    + "--- RingBuffer (" + DebugRingBuffer.size() + " events) ---\n"
                    + DebugRingBuffer.dump()
                    + "--- Perf ---\n" + DebugProfiler.summary() + "\n"
                    + "--- DataHealth ---\n" + DebugDataHealth.summary() + "\n"
                    + "--- Watchdog ---\n" + DebugWatchdog.summary() + "\n";
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs");
            Files.createDirectories(dir);
            Path file = dir.resolve("pastoralcraft-debug-dump-" + System.currentTimeMillis() + ".txt");
            Files.writeString(file, dump);
            PastoralCraft.LOGGER.info("[CropHandler] Wrote debug dump to {}", file);
        } catch (Exception e) {
            PastoralCraft.LOGGER.warn("[CropHandler] Failed to write debug dump", e);
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        if (!CropGrowthConfig.DEBUG_COMMANDS.get()) return;
        event.getDispatcher().register(
                Commands.literal("pastoralcraft")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                        .then(Commands.literal("dump").executes(ctx -> dump(ctx.getSource())))
                        .then(Commands.literal("reset").executes(ctx -> reset(ctx.getSource())))
        );
    }

    private static int status(CommandSourceStack source) {
        StringBuilder sb = new StringBuilder("PastoralCraft debug status: ");
        sb.append("growth=").append(DebugGate.growth())
                .append(" stem=").append(DebugGate.stem())
                .append(" sideEffect=").append(DebugGate.sideEffect())
                .append(" catchUp=").append(DebugGate.catchUp())
                .append(" data=").append(DebugGate.data())
                .append(" perf=").append(DebugGate.perf())
                .append(" ring=").append(DebugGate.ring())
                .append(" commands=").append(DebugGate.commands());
        Level level = source.getLevel();
        if (level != null) {
            sb.append(" | solarDay=").append(CropGrowthTracker.getSolarDays(level))
                    .append(" season=").append(CropGrowthTracker.getSeason(level))
                    .append(" termLength=").append(CropGrowthTracker.getTermLength(level));
        }
        int chunks = CropGrowthTracker.getTrackedChunks().size();
        int entries = 0;
        for (LevelChunk chunk : new ArrayList<>(CropGrowthTracker.getTrackedChunks())) {
            if (chunk instanceof ChunkCropData cd) {
                entries += cd.pastoralcraft$getCropData().size();
            }
        }
        sb.append(" | trackedChunks=").append(chunks).append(" entries=").append(entries);
        sb.append(" | setBlockCount=").append(DebugProfiler.getSetBlockCount());
        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int dump(CommandSourceStack source) {
        String dump = DebugRingBuffer.dump() + DebugDataHealth.summary() + "\n";
        PastoralCraft.LOGGER.info("[CropHandler] Debug dump ({} ring events):\n{}", DebugRingBuffer.size(), dump);
        source.sendSuccess(() -> Component.literal("Dump written to log (" + DebugRingBuffer.size() + " ring events)"), false);
        return 1;
    }

    private static int reset(CommandSourceStack source) {
        DebugProfiler.reset();
        DebugRingBuffer.clear();
        source.sendSuccess(() -> Component.literal("Perf counters and ring buffer cleared"), false);
        return 1;
    }
}