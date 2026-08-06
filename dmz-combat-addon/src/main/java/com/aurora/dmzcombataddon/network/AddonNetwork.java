package com.aurora.dmzcombataddon.network;

import com.aurora.dmzcombataddon.DMZCombatAddon;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class AddonNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(DMZCombatAddon.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private AddonNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(HeavyAttackC2S.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(HeavyAttackC2S::encode)
                .decoder(HeavyAttackC2S::decode)
                .consumerMainThread(HeavyAttackC2S::handle)
                .add();
    }

    public static void sendToServer(Object message) {
        CHANNEL.sendToServer(message);
    }
}
