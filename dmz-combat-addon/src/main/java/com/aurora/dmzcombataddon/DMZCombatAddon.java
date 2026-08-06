package com.aurora.dmzcombataddon;

import com.aurora.dmzcombataddon.network.AddonNetwork;
import com.aurora.dmzcombataddon.technique.AddonTechniqueRegistry;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(DMZCombatAddon.MOD_ID)
public final class DMZCombatAddon {
    public static final String MOD_ID = "dmzcombataddon";

    public DMZCombatAddon() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(AddonTechniqueRegistry::onCommonSetup);
        AddonNetwork.register();
    }
}
