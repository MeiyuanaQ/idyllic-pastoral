package com.crispyraccoon.pastoralcraft.compat;

import com.crispyraccoon.pastoralcraft.PastoralCraft;
import com.teamtea.eclipticseasons.compat.CompatModule;
import com.teamtea.eclipticseasons.config.CommonConfig;

import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * Disables Ecliptic Seasons' crop systems when PastoralCraft is installed.
 *
 * <p>PastoralCraft fully owns crop growth ({@code CropGrowEvent.Pre} at
 * {@code LOWEST}) and provides its own crop-season tooltip / Jade display, so
 * ES's season / humidity / greenhouse / bone-meal handlers only conflict with it
 * (e.g. ES kills an out-of-season crop at {@code NORMAL} before PastoralCraft's
 * handler sees the event, and duplicates the season tooltip). This class forces
 * off, in memory, the four ES toggles that drive those systems.</p>
 *
 * <p><b>Why {@link FMLCommonSetupEvent} and not {@code ModConfigEvent}:</b>
 * NeoForge posts {@code ModConfigEvent} on the owning mod's own event bus, so a
 * listener on PastoralCraft's bus only ever sees PastoralCraft's config file and
 * can never match ES's spec. {@code FMLCommonSetupEvent} instead fires on
 * PastoralCraft's bus after every mod's config file has been loaded, so the
 * values can be set directly and reliably here.</p>
 *
 * <p><b>In-memory only:</b> {@code ConfigValue.set} mutates the live value
 * without writing {@code eclipticseasons-common.toml}; the override reverses
 * simply by uninstalling PastoralCraft. It re-asserts on every game start (this
 * hook runs each launch). A manual re-enable via the ES config screen would
 * reload the file and win until the next launch — acceptable for an
 * "auto-disable on install" contract.</p>
 */
public final class EsGrowthDisabler {

    private EsGrowthDisabler() {
        // Utility class — prevent instantiation.
    }

    /**
     * Force ES's crop-system toggles off, in memory.
     *
     * @param event the common-setup lifecycle event (after all configs loaded)
     */
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        CommonConfig.Crop.enableCrop.set(false);
        CommonConfig.Crop.enableCropHumidityControl.set(false);
        CommonConfig.Crop.restrictBoneMeal.set(false);
        CompatModule.CommonConfig.showCropGrowthInfoInProbe.set(false);

        PastoralCraft.LOGGER.info(
                "PastoralCraft: Ecliptic Seasons crop season/humidity/greenhouse systems disabled");
    }
}
