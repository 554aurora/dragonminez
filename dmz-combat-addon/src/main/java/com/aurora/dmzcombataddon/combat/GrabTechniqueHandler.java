package com.aurora.dmzcombataddon.combat;

import com.aurora.dmzcombataddon.DMZCombatAddon;
import com.aurora.dmzcombataddon.technique.AddonTechniqueRegistry;
import com.dragonminez.common.combat.logic.player.TargetHelper;
import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.events.DMZEvent;
import com.dragonminez.common.init.MainDamageTypes;
import com.dragonminez.common.init.MainSounds;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.network.S2C.TriggerAnimationS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.techniques.StrikeAttackData;
import com.dragonminez.common.stats.techniques.TechniqueData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = DMZCombatAddon.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GrabTechniqueHandler {
    private static final double RANGE = 6.0D;
    private static final double CONE_COS = 0.5D;
    private static final int THROW_TICK = 28;
    private static final int DURATION_TICKS = 35;
    private static final String GRABBED_ANIMATION = "skp.grabbed";
    private static final Map<UUID, GrabState> ACTIVE = new HashMap<>();

    private GrabTechniqueHandler() {}

    /**
     * @return true when the selected technique is Grapple Throw. Returning true
     * cancels DragonMineZ's generic strike implementation even when validation fails.
     */
    public static boolean tryStart(ServerPlayer player, int preferredTargetId) {
        TechniqueData selected = StatsProvider.get(StatsCapability.INSTANCE, player)
                .map(stats -> stats.getTechniques().getSelectedTechnique())
                .orElse(null);
        if (!(selected instanceof StrikeAttackData strike)
                || !AddonTechniqueRegistry.GRAB_ID.equals(strike.getId())) {
            return false;
        }

        StatsProvider.get(StatsCapability.INSTANCE, player).ifPresent(stats -> {
            if (!stats.getStatus().isHasCreatedCharacter() || stats.getStatus().isStunned()) return;
            if (ACTIVE.containsKey(player.getUUID())) return;
            if (stats.getSkills().getSkillLevel("kicontrol") <= 0) return;
            if (stats.getResources().getPowerRelease() < 5 || !player.getMainHandItem().isEmpty()) return;

            String cooldownKey = cooldownKey(strike.getId());
            if (stats.getCooldowns().hasCooldown(cooldownKey)) return;

            LivingEntity target = findTarget(player, preferredTargetId);
            if (target == null) return;

            double cost = strike.getCalculatedCost(stats);
            if (stats.getResources().getCurrentEnergy() < cost) return;

            double damage = stats.getStrikeDamage()
                    * strike.getActualDamageMultiplier()
                    * Math.max(0.0D, ConfigManager.getTechniqueConfig()
                    .getStrikeConfig(strike.getId()).getDamageMultiplier());
            DMZEvent.DamageModifyEvent modify = new DMZEvent.DamageModifyEvent(
                    player, target, damage, 0.0D, DMZEvent.DamageSourceType.STRIKE);
            damage = MinecraftForge.EVENT_BUS.post(modify) ? 0.0D : Math.max(0.0D, modify.getAmount());

            stats.getResources().removeEnergy((int) Math.ceil(cost));
            TargetHelper.onSuccessfulAttack(player, target, TargetHelper.getRelation(player, target));
            ACTIVE.put(player.getUUID(), new GrabState(
                    target.getUUID(), damage, strike.getActualCooldown(), 0));

            setStrikeLocked(player, true);
            setStrikeLocked(target, true);
            playAnimation(player, strike.getAnimationId());
            playVictimAnimation(target);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    MainSounds.TP_SHORT.get(), net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 1.0F);
            NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(player), player);
        });
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        GrabState state = ACTIVE.get(player.getUUID());
        if (state == null) return;
        LivingEntity target = resolveTarget(player, state.targetId());
        if (target == null || !target.isAlive() || !player.isAlive()) {
            end(player, target, state);
            return;
        }

        int nextTick = state.ticksElapsed() + 1;
        player.invulnerableTime = Math.max(player.invulnerableTime, 5);

        if (nextTick < THROW_TICK) {
            freeze(player);
            target.invulnerableTime = Math.max(target.invulnerableTime, 5);
            holdByHead(player, target);
        } else if (nextTick == THROW_TICK) {
            target.invulnerableTime = 0;
            boolean damaged = target.hurt(
                    MainDamageTypes.strikeAttack(player.level(), player, AddonTechniqueRegistry.GRAB_ID),
                    (float) state.totalDamage());
            if (damaged) {
                LaunchHelper.launch(player, target, state.totalDamage());
                awardExperience(player, target);
            }
            player.level().playSound(null, target.getX(), target.getY(), target.getZ(),
                    MainSounds.CRITICO2.get(), net.minecraft.sounds.SoundSource.PLAYERS, 2.0F, 0.8F);
        } else {
            freeze(player);
        }

        if (nextTick >= DURATION_TICKS) {
            end(player, target, state);
        } else {
            ACTIVE.put(player.getUUID(), state.withTicksElapsed(nextTick));
        }
    }

    private static LivingEntity findTarget(ServerPlayer player, int preferredTargetId) {
        if (preferredTargetId > 0) {
            Entity preferred = TargetHelper.resolveHittable(
                    TargetHelper.getEntityOrPart(player.level(), preferredTargetId));
            if (preferred instanceof LivingEntity living && validTarget(player, living)) return living;
        }

        AABB box = player.getBoundingBox().inflate(RANGE);
        return player.level().getEntitiesOfClass(LivingEntity.class, box,
                        target -> target != player && validTarget(player, target))
                .stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    private static boolean validTarget(ServerPlayer player, LivingEntity target) {
        if (!target.isAlive() || !target.isPickable()) return false;
        if (player.distanceTo(target) > RANGE || !player.hasLineOfSight(target)) return false;
        Vec3 toTarget = target.getEyePosition().subtract(player.getEyePosition()).normalize();
        return player.getLookAngle().normalize().dot(toTarget) >= CONE_COS
                && TargetHelper.canAttack(player, target, RANGE);
    }

    private static LivingEntity resolveTarget(ServerPlayer player, UUID targetId) {
        Entity entity = player.serverLevel().getEntity(targetId);
        return entity instanceof LivingEntity living ? living : null;
    }

    private static void holdByHead(ServerPlayer player, LivingEntity target) {
        Vec3 look = Vec3.directionFromRotation(0.0F, player.getYRot()).normalize();
        Vec3 right = new Vec3(-look.z, 0.0D, look.x);
        Vec3 head = player.getEyePosition()
                .add(look.scale(0.72D))
                .add(right.scale(0.10D))
                .add(0.0D, 0.12D, 0.0D);
        target.teleportTo(head.x, head.y - target.getEyeHeight(), head.z);
        target.setYRot(player.getYRot() + 180.0F);
        target.setYHeadRot(target.getYRot());
        freeze(target);
    }

    private static void freeze(LivingEntity entity) {
        entity.setDeltaMovement(Vec3.ZERO);
        entity.fallDistance = 0.0F;
        entity.hasImpulse = true;
        entity.hurtMarked = true;
    }

    private static void awardExperience(ServerPlayer player, LivingEntity target) {
        StatsProvider.get(StatsCapability.INSTANCE, player).ifPresent(stats -> {
            TechniqueData data = stats.getTechniques().getUnlockedTechniques()
                    .get(AddonTechniqueRegistry.GRAB_ID);
            if (!(data instanceof StrikeAttackData strike)) return;
            int xp = strike.getXpGainPerHit();
            if (!target.isAlive()) xp += strike.getXpGainPerKill();
            if (xp > 0) stats.getTechniques().addExperienceToTechnique(AddonTechniqueRegistry.GRAB_ID, xp);
        });
    }

    private static void end(ServerPlayer player, LivingEntity target, GrabState state) {
        ACTIVE.remove(player.getUUID());
        setStrikeLocked(player, false);
        if (target != null) setStrikeLocked(target, false);
        stopAnimation(player);
        stopVictimAnimation(target);

        StatsProvider.get(StatsCapability.INSTANCE, player).ifPresent(stats -> {
            stats.getCooldowns().setCooldown(cooldownKey(AddonTechniqueRegistry.GRAB_ID), state.cooldownTicks());
            NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(player), player);
        });
    }

    private static void setStrikeLocked(LivingEntity entity, boolean locked) {
        if (!(entity instanceof ServerPlayer player)) return;
        StatsProvider.get(StatsCapability.INSTANCE, player).ifPresent(stats -> {
            stats.getStatus().setStrikeLocked(locked);
            NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(player), player);
        });
    }

    private static void playAnimation(ServerPlayer player, String animation) {
        NetworkHandler.sendToTrackingEntityAndSelf(new TriggerAnimationS2C(
                player.getUUID(), TriggerAnimationS2C.AnimationType.KI_ANIMATION,
                0, -1, animation), player);
    }

    private static void playVictimAnimation(LivingEntity target) {
        if (target instanceof ServerPlayer player) {
            NetworkHandler.sendToTrackingEntityAndSelf(new TriggerAnimationS2C(
                    player.getUUID(), TriggerAnimationS2C.AnimationType.KI_ANIMATION,
                    0, -1, GRABBED_ANIMATION), player);
        }
    }

    private static void stopAnimation(ServerPlayer player) {
        NetworkHandler.sendToTrackingEntityAndSelf(new TriggerAnimationS2C(
                player.getUUID(), TriggerAnimationS2C.AnimationType.KI_ANIMATION_STOP,
                0, -1, ""), player);
    }

    private static void stopVictimAnimation(LivingEntity target) {
        if (target instanceof ServerPlayer player) {
            NetworkHandler.sendToTrackingEntityAndSelf(new TriggerAnimationS2C(
                    player.getUUID(), TriggerAnimationS2C.AnimationType.KI_ANIMATION_STOP,
                    0, -1, ""), player);
        }
    }

    private static String cooldownKey(String techniqueId) {
        return "TechniqueCooldown_" + techniqueId;
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID leaving = event.getEntity().getUUID();
        ArrayList<UUID> attackers = new ArrayList<>();
        for (Map.Entry<UUID, GrabState> entry : ACTIVE.entrySet()) {
            if (entry.getKey().equals(leaving) || entry.getValue().targetId().equals(leaving)) {
                attackers.add(entry.getKey());
            }
        }
        for (UUID attackerId : attackers) {
            ServerPlayer attacker = event.getEntity().getServer() != null
                    ? event.getEntity().getServer().getPlayerList().getPlayer(attackerId) : null;
            GrabState state = ACTIVE.remove(attackerId);
            if (attacker != null && state != null) end(attacker, null, state);
        }
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        UUID dead = event.getEntity().getUUID();
        ArrayList<UUID> attackers = new ArrayList<>();
        for (Map.Entry<UUID, GrabState> entry : ACTIVE.entrySet()) {
            if (entry.getKey().equals(dead) || entry.getValue().targetId().equals(dead)) {
                attackers.add(entry.getKey());
            }
        }
        for (UUID attackerId : attackers) {
            ServerPlayer attacker = event.getEntity().getServer() != null
                    ? event.getEntity().getServer().getPlayerList().getPlayer(attackerId) : null;
            GrabState state = ACTIVE.remove(attackerId);
            if (attacker != null && state != null) end(attacker, null, state);
        }
    }

    private record GrabState(UUID targetId, double totalDamage, int cooldownTicks, int ticksElapsed) {
        private GrabState withTicksElapsed(int ticks) {
            return new GrabState(targetId, totalDamage, cooldownTicks, ticks);
        }
    }
}
