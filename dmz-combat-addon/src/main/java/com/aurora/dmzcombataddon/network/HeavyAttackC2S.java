package com.aurora.dmzcombataddon.network;

import com.aurora.dmzcombataddon.combat.HeavyAttackHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public final class HeavyAttackC2S {
    public enum Action {
        START,
        CANCEL,
        RELEASE
    }

    private final Action action;

    public HeavyAttackC2S(Action action) {
        this.action = action;
    }

    public static void encode(HeavyAttackC2S message, FriendlyByteBuf buffer) {
        buffer.writeEnum(message.action);
    }

    public static HeavyAttackC2S decode(FriendlyByteBuf buffer) {
        return new HeavyAttackC2S(buffer.readEnum(Action.class));
    }

    public static void handle(HeavyAttackC2S message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            HeavyAttackHandler.handleAction(sender, message.action);
        }
        context.setPacketHandled(true);
    }
}
