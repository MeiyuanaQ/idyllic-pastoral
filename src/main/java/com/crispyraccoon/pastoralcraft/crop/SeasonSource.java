package com.crispyraccoon.pastoralcraft.crop;

import java.util.EnumSet;
import java.util.Set;

import com.teamtea.eclipticseasons.api.constant.crop.CropSeasonInfo;
import com.teamtea.eclipticseasons.api.constant.crop.CropSeasonType;
import com.teamtea.eclipticseasons.api.constant.solar.Season;
import com.teamtea.eclipticseasons.common.core.crop.CropInfoManager;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.block.Block;

/**
 * A source of season-suitability information for crop blocks.
 *
 * <p>{@link SeasonTagResolver} chains several sources — the first one that
 * returns a non-null, non-empty set wins. Each source returns {@code null}
 * when it holds no information about the given block, letting the caller fall
 * back to the next source in the chain.</p>
 *
 * <p>Resolution chain: {@link EsCropInfoSeasonSource} → {@link BlockTagSeasonSource}
 * → per-crop {@code seasons=} override → {@code defaultUntaggedSeasons}.</p>
 */
public interface SeasonSource {

    /**
     * @param block the crop block to resolve
     * @return the set of suitable seasons, or {@code null} when this source
     *         holds no information for the block
     */
    @Nullable
    Set<Season> resolve(Block block);

    /**
     * Primary source backed by the Ecliptic Seasons runtime crop registry
     * ({@link CropInfoManager#getSeasonInfo(Block)}).
     *
     * <p>This is the authoritative source because the registry aggregates both
     * data-pack tag entries and programmatic registrations from other mods
     * (via {@code registerCropSeasonInfo}), and it is rebuilt on every
     * {@code TagsUpdatedEvent} at NORMAL priority.</p>
     */
    final class EsCropInfoSeasonSource implements SeasonSource {

        public static final EsCropInfoSeasonSource INSTANCE = new EsCropInfoSeasonSource();

        private EsCropInfoSeasonSource() {
        }

        @Nullable
        @Override
        public Set<Season> resolve(Block block) {
            CropSeasonInfo info = CropInfoManager.getSeasonInfo(block);
            if (info == null) {
                return null;
            }
            return SeasonTagResolver.seasonsFrom(info);
        }
    }

    /**
     * Fallback source that reads the Ecliptic Seasons crop season block tags
     * ({@code eclipticseasons:crops/*}) directly from the block's default state.
     *
     * <p>Used when the runtime registry has no entry for a block (e.g. before
     * tags have been initialized, or for blocks that a data pack tags but the
     * registry init skipped).</p>
     */
    final class BlockTagSeasonSource implements SeasonSource {

        public static final BlockTagSeasonSource INSTANCE = new BlockTagSeasonSource();

        private BlockTagSeasonSource() {
        }

        @Nullable
        @Override
        public Set<Season> resolve(Block block) {
            EnumSet<Season> seasons = EnumSet.noneOf(Season.class);
            for (CropSeasonType type : CropSeasonType.collectValues()) {
                if (!block.defaultBlockState().is(type.getBlockTag())) {
                    continue;
                }
                CropSeasonInfo info = type.getInfo();
                if (info == null) {
                    continue;
                }
                seasons.addAll(SeasonTagResolver.seasonsFrom(info));
                // Do NOT break: a crop may be listed in multiple single-season tags
                // (e.g. both spring and summer), so merge every matching tag.
            }
            return seasons.isEmpty() ? null : seasons;
        }
    }
}
