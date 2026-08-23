package com.crispyraccoon.pastoralcraft.client;

import com.crispyraccoon.pastoralcraft.PastoralCraft;

import net.minecraft.world.item.BlockItem;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Adds PastoralCraft's crop season tooltip to crop items, replacing Ecliptic
 * Seasons' equivalent (whose tooltip is silenced by {@code EsGrowthDisabler}
 * turning {@code EnableSeasonalCrop} off).
 */
@EventBusSubscriber(modid = PastoralCraft.MODID, value = Dist.CLIENT)
public final class CropSeasonTooltip {

    private CropSeasonTooltip() {
        // Utility class — prevent instantiation.
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().getItem() instanceof BlockItem blockItem) {
            event.getToolTip().addAll(CropSeasonDisplay.seasonTooltip(blockItem.getBlock()));
        }
    }
}
