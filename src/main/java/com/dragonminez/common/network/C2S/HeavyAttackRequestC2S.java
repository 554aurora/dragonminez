package com.dragonminez.common.network.C2S;

import com.dragonminez.common.combat.player.HeavyAttackConstants;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsProvider;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Heavy counterpart to CombatAttackRequestC2S, kept as a new packet for wire compatibility. */
public class HeavyAttackRequestC2S {
	private final CombatAttackRequestC2S attackRequest;

	public HeavyAttackRequestC2S(int comboCount, boolean isSneaking, int selectedSlot, int[] entityIds) {
		this.attackRequest = new CombatAttackRequestC2S(comboCount, isSneaking, selectedSlot, entityIds);
	}

	public HeavyAttackRequestC2S(FriendlyByteBuf buffer) {
		this.attackRequest = new CombatAttackRequestC2S(buffer);
	}

	public void encode(FriendlyByteBuf buffer) {
		attackRequest.encode(buffer);
	}

	public void handle(Supplier<NetworkEvent.Context> ctx) {
		ctx.get().enqueueWork(() -> {
			ServerPlayer player = ctx.get().getSender();
			if (player == null) return;

			boolean hasChargeStart = player.getPersistentData().contains(HeavyAttackConstants.CHARGE_START_TAG);
			long start = player.getPersistentData().getLong(HeavyAttackConstants.CHARGE_START_TAG);
			player.getPersistentData().remove(HeavyAttackConstants.CHARGE_START_TAG);
			long heldTicks = player.level().getGameTime() - start;
			if (!hasChargeStart || heldTicks < HeavyAttackConstants.CHARGE_TICKS
					|| heldTicks > HeavyAttackConstants.MAX_SERVER_CHARGE_TICKS) return;

			StatsProvider.get(StatsCapability.INSTANCE, player).ifPresent(stats -> {
				if (!stats.getStatus().isStunned()) {
					CombatAttackRequestC2S.processAttackRequest(player, attackRequest, true);
				}
			});
		});
		ctx.get().setPacketHandled(true);
	}
}
