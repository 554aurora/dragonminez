package com.aurora.dmzcombataddon.combat;

import com.aurora.dmzcombataddon.DMZCombatAddon;
import com.aurora.dmzcombataddon.network.HeavyAttackC2S;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = DMZCombatAddon.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class HeavyAttackHandler {
    public static final int CHARGE_TICKS = 10;
    public static final float DAMAGE_MULTIPLIER = 1.5F;

    private static final int SERVER_MIN_CHARGE_TICKS = CHARGE_TICKS - 2;
    private static final int MAX_CHARGE_TICKS = 200;
    private static final int READY_WINDOW_TICKS = 15;
    private static final String DMZ_RAW_DAMAGE_TAG = "dmz_raw_damage";

    private static final Map<UUID, Long> CHARGE_START = new HashMap<>();
    private static final Map<UUID, Long> READY_UNTIL = new HashMap<>();
    private static final Map<UUID, Long> LAST_LAUNCH_TICK = new HashMap<>();

    private HeavyAttackHandler() {}

    public static void handleAction(ServerPlayer player, HeavyAttackC2S.Action action) {
        long now = player.level().getGameTime();
        switch (action) {
            case START -> {
                READY_UNTIL.remove(player.getUUID());
                if (isEligible(player)) CHARGE_START.put(player.getUUID(), now);
            }
            case CANCEL -> {
                CHARGE_START.remove(player.getUUID());
                READY_UNTIL.remove(player.getUUID());
            }
            case RELEASE -> {
                Long start = CHARGE_START.remove(player.getUUID());
                if (start == null || !isEligible(player)) return;
                long held = now - start;
                // Two ticks of tolerance cover client/server scheduling without trusting
                // an instant release. The client still requires the full 10-tick hold.
                if (held >= SERVER_MIN_CHARGE_TICKS && held <= MAX_CHARGE_TICKS) {
                    READY_UNTIL.put(player.getUUID(), now + READY_WINDOW_TICKS);
                }
            }
        }
    }

    private static boolean isEligible(ServerPlayer player) {
        return StatsProvider.get(StatsCapability.INSTANCE, player)
                .map(data -> data.getStatus().isHasCreatedCharacter()
                        && !data.getStatus().isStunned()
                        && !data.getStatus().isBlocking())
                .orElse(false);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onLivingHurt(LivingHurtEvent event) {
        if (event.isCanceled() || event.getAmount() <= 0.0F) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) return;
        if (!"player".equals(event.getSource().getMsgId())) return;

        long now = attacker.level().getGameTime();
        Long readyUntil = READY_UNTIL.get(attacker.getUUID());
        if (readyUntil == null || now > readyUntil) return;

        event.setAmount(event.getAmount() * DAMAGE_MULTIPLIER);

        LivingEntity target = event.getEntity();
        if (target.getPersistentData().contains(DMZ_RAW_DAMAGE_TAG)) {
            double raw = target.getPersistentData().getDouble(DMZ_RAW_DAMAGE_TAG);
            target.getPersistentData().putDouble(DMZ_RAW_DAMAGE_TAG, raw * DAMAGE_MULTIPLIER);
        }

        READY_UNTIL.put(attacker.getUUID(), now);
        Long lastLaunch = LAST_LAUNCH_TICK.get(target.getUUID());
        if (lastLaunch != null && lastLaunch == now) return;
        LAST_LAUNCH_TICK.put(target.getUUID(), now);

        double attackPower = StatsProvider.get(StatsCapability.INSTANCE, attacker)
                .map(data -> data.getMeleeDamage() * DAMAGE_MULTIPLIER)
                .orElse(attacker.getAttributeValue(Attributes.ATTACK_DAMAGE) * DAMAGE_MULTIPLIER);

        attacker.server.execute(() -> {
            if (target.isAlive() && !target.isRemoved()) {
                LaunchHelper.launch(attacker, target, attackPower);
            }
        });
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide) return;
        long now = event.player.level().getGameTime();
        UUID id = event.player.getUUID();
        CHARGE_START.computeIfPresent(id, (ignored, start) -> now - start > MAX_CHARGE_TICKS ? null : start);
        READY_UNTIL.computeIfPresent(id, (ignored, expiry) -> now > expiry ? null : expiry);
        if ((now & 31L) == 0L) {
            LAST_LAUNCH_TICK.entrySet().removeIf(entry -> now - entry.getValue() > 2L);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        CHARGE_START.remove(id);
        READY_UNTIL.remove(id);
    }
}
