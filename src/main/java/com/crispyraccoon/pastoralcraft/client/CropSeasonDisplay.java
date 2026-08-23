package com.crispyraccoon.pastoralcraft.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.crispyraccoon.pastoralcraft.crop.CropGrowthConfig;
import com.crispyraccoon.pastoralcraft.crop.SeasonTagResolver;
import com.teamtea.eclipticseasons.api.constant.solar.Season;
import com.teamtea.eclipticseasons.api.constant.solar.SolarTerm;
import com.teamtea.eclipticseasons.api.util.SimpleUtil;
import com.teamtea.eclipticseasons.common.core.crop.CropInfoManager;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;

/**
 * Client-side crop season display helper, shared by the item tooltip and the
 * Jade provider.
 *
 * <p>Resolves a block's suitable seasons through {@link SeasonTagResolver}, so
 * PastoralCraft's {@code seasons=} config override wins over Ecliptic Seasons
 * data. Renders in ES's style: a gray label line, then one solar-icon line per
 * suitable season (or a single "year-round" line).</p>
 */
public final class CropSeasonDisplay {

    private CropSeasonDisplay() {
        // Utility class — prevent instantiation.
    }

    /**
     * Whether a block has explicit season information — an ES registry entry
     * (which covers every ES-tagged block), or a PastoralCraft {@code seasons=}
     * override. Blocks with neither source render no season tooltip, so untagged
     * decorative blocks stay clean.
     */
    public static boolean hasSeasonInfo(Block block) {
        return CropGrowthConfig.getOverrideSeasons(block) != null
                || CropInfoManager.getSeasonInfo(block) != null;
    }

    /**
     * Build the season tooltip lines (ES-style multi-line with solar icons).
     *
     * @param block the block to display
     * @return the season lines, or empty when the block has no season info
     */
    public static List<Component> seasonTooltip(Block block) {
        if (!hasSeasonInfo(block)) {
            return List.of();
        }
        Set<Season> seasons = SeasonTagResolver.resolve(block);

        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable("pastoralcraft.tooltip.seasons.label").withStyle(ChatFormatting.GRAY));

        if (seasons.contains(Season.NONE) || seasons.size() >= SeasonTagResolver.ORDERED_SEASONS.length) {
            lines.add(Component.translatable("pastoralcraft.tooltip.seasons.year_round"));
        } else {
            for (Season season : SeasonTagResolver.ORDERED_SEASONS) {
                if (seasons.contains(season)) {
                    lines.add(iconLine(season));
                }
            }
        }
        return lines;
    }

    private static Component iconLine(Season season) {
        SolarTerm term = switch (season) {
            case SPRING -> SolarTerm.SPRING_EQUINOX;
            case SUMMER -> SolarTerm.SUMMER_SOLSTICE;
            case AUTUMN -> SolarTerm.AUTUMNAL_EQUINOX;
            case WINTER -> SolarTerm.WINTER_SOLSTICE;
            default -> null;
        };
        if (term == null) {
            return season.getTranslation();
        }
        return SimpleUtil.addSolarIconBefore(term, season.getTranslation());
    }
}
