package com.dragonminez.common.network.C2S;

import com.dragonminez.common.combat.player.HeavyAttackConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Records the start/cancel of a heavy attack charge for server-side validation. */
public class HeavyAttackChargeC2S {
	private final boolean charging;

	public HeavyAttackChargeC2S(boolean charging) {
		this.charging = charging;
	}

	public HeavyAttackChargeC2S(FriendlyByteBuf buffer) {
		this.charging = buffer.readBoolean();
	}

	public void encode(FriendlyByteBuf buffer) {
		buffer.writeBoolean(charging);
	}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(() -> {
			ServerPlayer player = ctx.get().getSender();
			if (player == null) return;
			if (charging) {
				player.getPersistentData().putLong(HeavyAttackConstants.CHARGE_START_TAG, player.level().getGameTime());
			} else {
				player.getPersistentData().remove(HeavyAttackConstants.CHARGE_START_TAG);
			}
		});
		ctx.get().setPacketHandled(true);
	}
}
