package com.aurora.dmzcombataddon.technique;

import com.aurora.dmzcombataddon.DMZCombatAddon;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.techniques.PredefinedTechniques;
import com.dragonminez.common.stats.techniques.StrikeAttackData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod.EventBusSubscriber(modid = DMZCombatAddon.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AddonTechniqueRegistry {
    public static final String GRAB_ID = "grab";

    private AddonTechniqueRegistry() {}

    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(AddonTechniqueRegistry::registerGrab);
    }

    public static void registerGrab() {
        if (PredefinedTechniques.STRIKE_REGISTRY.containsKey(GRAB_ID)) return;

        StrikeAttackData grab = new StrikeAttackData();
        grab.setId(GRAB_ID);
        grab.setName("technique.dmzcombataddon.grab");
        grab.setAuthor("554aurora");
        grab.setDamageMultiplier(1.25F);
        grab.setAnimationId("skp.grab");
        grab.setDurationTicks(35);
        grab.applyConfigDefaults();
        PredefinedTechniques.STRIKE_REGISTRY.put(GRAB_ID, grab);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide) return;
        if (!(event.player instanceof ServerPlayer player) || player.tickCount % 20 != 0) return;

        StrikeAttackData template = PredefinedTechniques.STRIKE_REGISTRY.get(GRAB_ID);
        if (template == null) {
            registerGrab();
            template = PredefinedTechniques.STRIKE_REGISTRY.get(GRAB_ID);
        }
        if (template == null) return;

        StrikeAttackData finalTemplate = template;
        StatsProvider.get(StatsCapability.INSTANCE, player).ifPresent(stats -> {
            if (!stats.getStatus().isHasCreatedCharacter()) return;
            if (stats.getTechniques().getUnlockedTechniques().containsKey(GRAB_ID)) return;

            StrikeAttackData copy = new StrikeAttackData();
            copy.load(finalTemplate.save());
            stats.getTechniques().unlockTechnique(copy);
            NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(player), player);
        });
    }
}
