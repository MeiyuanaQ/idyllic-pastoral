package com.crispyraccoon.pastoralcraft;

import com.crispyraccoon.pastoralcraft.crop.CropGrowthConfig;
import com.crispyraccoon.pastoralcraft.crop.SeasonTagResolver;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;

/**
 * Central config class for PastoralCraft.
 * Handles config load/reload events for {@link CropGrowthConfig}.
 */
@EventBusSubscriber(modid = PastoralCraft.MODID)
public class Config {

    /**
     * Refresh parsed config caches when the config is loaded or reloaded.
     */
    @SubscribeEvent
    public static void onConfigLoad(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == CropGrowthConfig.SPEC) {
            CropGrowthConfig.refreshOverrides();
            // defaultUntaggedSeasons feeds the per-block season cache.
            SeasonTagResolver.clearCache();
        }
    }

    @SubscribeEvent
    public static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == CropGrowthConfig.SPEC) {
            CropGrowthConfig.refreshOverrides();
            // defaultUntaggedSeasons feeds the per-block season cache.
            SeasonTagResolver.clearCache();
        }
    }
}