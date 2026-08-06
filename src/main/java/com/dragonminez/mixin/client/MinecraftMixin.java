package com.dragonminez.mixin.client;

import com.dragonminez.client.animation.IPlayerAnimatable;
import com.dragonminez.client.collision.CollisionHelper;
import com.dragonminez.client.collision.TargetFinder;
import com.dragonminez.client.events.DMZClientEvent;
import com.dragonminez.client.util.KeyBinds;
import com.dragonminez.common.combat.logic.player.PlayerAttackHelper;
import com.dragonminez.common.combat.logic.player.PlayerAttackProperties;
import com.dragonminez.common.combat.player.AttackHand;
import com.dragonminez.common.combat.player.HeavyAttackConstants;
import com.dragonminez.common.combat.util.Minecraft_DMZ;
import com.dragonminez.common.combat.util.SoundHelper;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.C2S.CombatAttackRequestC2S;
import com.dragonminez.common.network.C2S.HeavyAttackChargeC2S;
import com.dragonminez.common.network.C2S.HeavyAttackRequestC2S;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin implements Minecraft_DMZ {

	@Shadow public LocalPlayer player;
	@Shadow public Screen screen;
	@Shadow public HitResult hitResult;
	@Shadow protected abstract boolean startAttack();

	@Unique private AttackHand upswingStack = null;
	@Unique private int upswingTicks = 0;
	@Unique private int lastAttacked = 0;
	@Unique private int lastSwingDuration = 0;
	@Unique private List<Entity> targetsInReach = null;
	@Unique private int itemUseCooldown = 0;
	@Unique private boolean isAttacking = false;
	@Unique private boolean isAwaitingUpswing = false;
	@Unique private boolean queuedAttack = false;
	@Unique private int queuedAttackTicks = 0;
	@Unique private boolean queuedAttackHeavy = false;
	@Unique private boolean attackHoldPending = false;
	@Unique private int attackHoldTicks = 0;
	@Unique private boolean attackMustBeReleased = false;
	@Unique private boolean activeAttackHeavy = false;

	@Unique private static final float ATTACK_QUEUE_WINDOW_TICKS = 1.0F;
	@Unique private static final int ATTACK_QUEUE_EXPIRY_TICKS = 4;

	@Unique private static final float UPSWING_IMPACT_BIAS = 0.4F;
	@Unique private static final int BLOCK_MINE_ATTACK_GRACE = 5;
	@Unique private int lastBlockMineTick = -100;

	@Unique private static final double BLOCK_MINE_TARGET_BIAS = 0.25D;

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void dragonminez$startAttack(CallbackInfoReturnable<Boolean> cir) {
		if (player == null || screen != null) return;

		boolean[] combatRestricted = {false};
		StatsProvider.get(StatsCapability.INSTANCE, player).ifPresent(data ->
				combatRestricted[0] = data.getStatus().isBlocking() || data.getStatus().isStunned());
		if (player.isBlocking() || combatRestricted[0] || PlayerAttackHelper.isChargingTechnique(player)) {
			cir.cancel();
			cir.setReturnValue(false);
			return;
		}

		var mcDMZ = (Minecraft_DMZ) this;
		var comboCount = mcDMZ.getComboCount();
		var hand = PlayerAttackHelper.getCurrentAttack(player, comboCount);

		if (hand == null || !PlayerAttackHelper.canAttack(player)) return;
		if (!shouldUseCombatAttack(hand)) return;

		cir.cancel();
		cir.setReturnValue(false);

		if (attackMustBeReleased || attackHoldPending || itemUseCooldown > 0 || isAttacking || isAwaitingUpswing) return;

		attackHoldPending = true;
		attackHoldTicks = 0;
		NetworkHandler.sendToServer(new HeavyAttackChargeC2S(true));
	}

	@Unique
	private boolean beginCombatAttack(boolean heavyAttack) {
		var comboCount = getComboCount();
		var hand = heavyAttack
				? PlayerAttackHelper.getHeavyAttack(player)
				: PlayerAttackHelper.getCurrentAttack(player, comboCount);
		if (hand == null || !PlayerAttackHelper.canAttack(player) || !shouldUseCombatAttack(hand)) return false;
		if (itemUseCooldown > 0 || isAttacking || isAwaitingUpswing) return false;

		float cooldownProgress = player.getAttackStrengthScale(0.5F);
		if (cooldownProgress < 1.0F) {
			float remainingTicks = (1.0F - cooldownProgress) * player.getCurrentItemAttackStrengthDelay();
			if (remainingTicks <= ATTACK_QUEUE_WINDOW_TICKS) {
				queuedAttack = true;
				queuedAttackHeavy = heavyAttack;
				queuedAttackTicks = 0;
				return true;
			}
			return false;
		}

		queuedAttack = false;
		queuedAttackHeavy = false;
		isAttacking = true;
		isAwaitingUpswing = true;
		activeAttackHeavy = heavyAttack;
		upswingStack = hand;
		((PlayerAttackProperties) player).setHeavyAttack(heavyAttack);

		float cooldownTicks = PlayerAttackHelper.getAttackCooldownTicksCapped(player);
		float animSpeed = meleeAnimSpeed(cooldownTicks);
		if (heavyAttack) animSpeed *= HeavyAttackConstants.ANIMATION_SPEED_MULTIPLIER;
		int swingAnimTicks = meleeAnimTicks(animSpeed);
		upswingTicks = Math.max(1, Math.round(swingAnimTicks * (float) hand.upswingRate() * UPSWING_IMPACT_BIAS));
		lastSwingDuration = swingAnimTicks;
		lastAttacked = 0;

		((MinecraftAccessor) this).setAttackCooldown(10000);

		MinecraftForge.EVENT_BUS.post(new DMZClientEvent.PlayerAttackStart(player, hand));
		playLocalAttackFeedback(hand, heavyAttack);
		return true;
	}

	@Unique
	private float meleeAnimSpeed(float cooldownTicks) {
		float speed = 12.0F / Math.max(cooldownTicks, 0.001F);
		return Math.max(0.55F, Math.min(1.35F, speed));
	}

	@Unique
	private int meleeAnimTicks(float animSpeed) {
		return Math.max(8, Math.round(12.0F / Math.max(animSpeed, 0.1F)));
	}

	@Unique
	private void playLocalAttackFeedback(AttackHand hand, boolean heavyAttack) {
		if (hand.attack() == null) return;

		float animSpeedMultiplier = meleeAnimSpeed(PlayerAttackHelper.getAttackCooldownTicksCapped(player));
		if (heavyAttack) animSpeedMultiplier *= HeavyAttackConstants.ANIMATION_SPEED_MULTIPLIER;

		((IPlayerAnimatable) player).dragonminez$playMeleeAnimation(hand.attack().animation(), hand.isOffHand(), animSpeedMultiplier);

		var swingSound = hand.attack().swingSound();
		SoundEvent soundEvent = SoundHelper.resolveSoundEvent(swingSound);
		if (soundEvent != null) {
			player.level().playLocalSound(player.getX(), player.getY(), player.getZ(), soundEvent, SoundSource.PLAYERS, swingSound.volume(), SoundHelper.computePitch(swingSound), false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void dragonminez$continueAttack(boolean leftClick, CallbackInfo ci) {
		if (player == null) return;

		if (attackHoldPending) {
			if (isAttackRestricted()) {
				cancelAttackCharge();
				if (leftClick) ci.cancel();
				return;
			}

			if (!leftClick) {
				attackHoldPending = false;
				attackHoldTicks = 0;
				NetworkHandler.sendToServer(new HeavyAttackChargeC2S(false));
				beginCombatAttack(false);
				return;
			}

			ci.cancel();
			attackHoldTicks++;
			if (attackHoldTicks >= HeavyAttackConstants.CHARGE_TICKS) {
				attackHoldPending = false;
				attackMustBeReleased = true;
				if (!beginCombatAttack(true)) {
					NetworkHandler.sendToServer(new HeavyAttackChargeC2S(false));
				}
			}
			return;
		}

		if (attackMustBeReleased) {
			if (!leftClick) attackMustBeReleased = false;
			else ci.cancel();
			return;
		}

		if (!leftClick) return;

		if (PlayerAttackHelper.isChargingTechnique(player)) {
			ci.cancel();
			return;
		}

		var mcDMZ = (Minecraft_DMZ) this;
		var comboCount = mcDMZ.getComboCount();
		var hand = PlayerAttackHelper.getCurrentAttack(player, comboCount);
		boolean canUseCombat = hand != null && PlayerAttackHelper.canAttack(player) && shouldUseCombatAttack(hand);

		if (!canUseCombat) {
			if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) lastBlockMineTick = player.tickCount;
			return;
		}
		ci.cancel();

		if (player.tickCount - lastBlockMineTick <= BLOCK_MINE_ATTACK_GRACE) return;

		// A new attack begins only from startAttack's physical press edge. Holding
		// the mouse no longer repeats light attacks.
	}

	@Unique
	private boolean isAttackRestricted() {
		boolean[] restrictedByStats = {false};
		StatsProvider.get(StatsCapability.INSTANCE, player).ifPresent(data ->
				restrictedByStats[0] = data.getStatus().isBlocking() || data.getStatus().isStunned());
		return KeyBinds.BLOCK_KEY.isDown() || player.isBlocking() || restrictedByStats[0]
				|| PlayerAttackHelper.isChargingTechnique(player) || screen != null;
	}

	@Unique
	private void cancelAttackCharge() {
		if (attackHoldPending || activeAttackHeavy || queuedAttackHeavy) {
			NetworkHandler.sendToServer(new HeavyAttackChargeC2S(false));
		}
		attackHoldPending = false;
		attackHoldTicks = 0;
		attackMustBeReleased = false;
	}

	@Inject(method = "tick", at = @At("HEAD"))
	private void dragonminez$tick(CallbackInfo info) {
		if (itemUseCooldown > 0) itemUseCooldown--;
		lastAttacked++;

		if (player == null) return;

		resetComboIfNeeded();

		if (upswingStack != null) {
			if (upswingTicks > 0) upswingTicks--;
			else {
				executeAttack();
				upswingStack = null;
				isAwaitingUpswing = false;
			}
		} else isAttacking = false;

		fireQueuedAttackIfReady();

		if (player.tickCount % 2 == 0) evaluateTargetsInReach();
	}

	@Unique
	private void fireQueuedAttackIfReady() {
		if (!queuedAttack) return;
		if (isAttacking || isAwaitingUpswing) {
			if (queuedAttackHeavy) NetworkHandler.sendToServer(new HeavyAttackChargeC2S(false));
			queuedAttack = false;
			queuedAttackHeavy = false;
			queuedAttackTicks = 0;
			return;
		}
		if (++queuedAttackTicks > ATTACK_QUEUE_EXPIRY_TICKS) {
			if (queuedAttackHeavy) NetworkHandler.sendToServer(new HeavyAttackChargeC2S(false));
			queuedAttack = false;
			queuedAttackHeavy = false;
			queuedAttackTicks = 0;
			return;
		}
		if (screen != null || player.getAttackStrengthScale(0.5F) < 1.0F) return;
		boolean heavyAttack = queuedAttackHeavy;
		queuedAttack = false;
		queuedAttackHeavy = false;
		queuedAttackTicks = 0;
		if (!beginCombatAttack(heavyAttack) && heavyAttack) {
			NetworkHandler.sendToServer(new HeavyAttackChargeC2S(false));
		}
	}

	@Unique
	private void resetComboIfNeeded() {
		int comboCount = getComboCount();
		if (comboCount <= 0) return;

		if (isAttacking || isAwaitingUpswing) return;

		int cooldownTicks = (int) Math.ceil(PlayerAttackHelper.getAttackCooldownTicksCapped(player));
		int comboResetWindow = cooldownTicks + 30;
		if (lastAttacked > comboResetWindow) ((PlayerAttackProperties) player).setComboCount(0);
	}

	@Unique
	private void executeAttack() {
		var mcDMZ = (Minecraft_DMZ) this;
		var cursorTarget = mcDMZ.getCursorTarget();
		var attackRange = PlayerAttackHelper.getEffectiveAttackRange(player, upswingStack.attributes().attackRange());

		TargetFinder.TargetResult targetResult = TargetFinder.findAttackTargetResult(player, cursorTarget, upswingStack.attack(), attackRange);

		var event = new DMZClientEvent.PlayerAttackHit(player, upswingStack, targetResult.entities, cursorTarget);
		MinecraftForge.EVENT_BUS.post(event);

		int[] entityIds = targetResult.entities.stream().mapToInt(Entity::getId).toArray();

		int comboCount = mcDMZ.getComboCount();
		boolean sneaking = player.hasPose(Pose.CROUCHING);
		int slot = player.getInventory().selected;

		if (activeAttackHeavy) {
			NetworkHandler.sendToServer(new HeavyAttackRequestC2S(comboCount, sneaking, slot, entityIds));
			((PlayerAttackProperties) player).setComboCount(0);
		} else {
			NetworkHandler.sendToServer(new CombatAttackRequestC2S(comboCount, sneaking, slot, entityIds));
			((PlayerAttackProperties) player).setComboCount(comboCount + 1);
		}

		player.resetAttackStrengthTicker();
		float cooldownMultiplier = activeAttackHeavy ? (float) HeavyAttackConstants.COOLDOWN_MULTIPLIER : 1.0F;
		setMiningCooldown(Math.max(2, Math.round(PlayerAttackHelper.getAttackCooldownTicksCapped(player) * cooldownMultiplier)));
		((PlayerAttackProperties) player).setHeavyAttack(false);
		activeAttackHeavy = false;
	}

	@Unique
	private void evaluateTargetsInReach() {
		var mcDMZ = (Minecraft_DMZ) this;
		var comboCount = mcDMZ.getComboCount();
		var hand = upswingStack != null ? upswingStack : PlayerAttackHelper.getCurrentAttack(player, comboCount);
		if (hand == null) {
			targetsInReach = null;
			return;
		}
		var cursorTarget = mcDMZ.getCursorTarget();
		var attackRange = PlayerAttackHelper.getEffectiveAttackRange(player, hand.attributes().attackRange());
		TargetFinder.TargetResult targetResult = TargetFinder.findAttackTargetResult(player, cursorTarget, hand.attack(), attackRange);
		targetsInReach = targetResult.entities;
	}

	@Unique
	private void setMiningCooldown(int ticks) {
		((MinecraftAccessor) this).setAttackCooldown(ticks);
	}

	@Unique
	private List<Entity> collectAttackTargets(AttackHand hand) {
		if (hasTargetsInReach()) return targetsInReach;
		var mcDMZ = (Minecraft_DMZ) this;
		var cursorTarget = mcDMZ.getCursorTarget();
		var attackRange = PlayerAttackHelper.getEffectiveAttackRange(player, hand.attributes().attackRange());
		return TargetFinder.findAttackTargetResult(player, cursorTarget, hand.attack(), attackRange).entities;
	}

	@Unique
	private boolean hasTargetsForAttack(AttackHand hand) {
		return !collectAttackTargets(hand).isEmpty();
	}

	@Unique
	private boolean hasTargetInFrontOfBlock(AttackHand hand) {
		List<Entity> targets = collectAttackTargets(hand);
		if (targets.isEmpty()) return false;

		Vec3 eye = player.getEyePosition();
		double blockDistance = hitResult.getLocation().distanceTo(eye);
		for (Entity target : targets) {
			if (CollisionHelper.distance(eye, target.getBoundingBox()) + BLOCK_MINE_TARGET_BIAS < blockDistance) return true;
		}
		return false;
	}

	@Unique
	private boolean shouldUseCombatAttack(AttackHand hand) {
		if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) return true;
		return hasTargetInFrontOfBlock(hand);
	}

	@Override
	public int getComboCount() {
		return player != null ? ((PlayerAttackProperties) player).getComboCount() : 0;
	}

	@Override
	public boolean hasTargetsInReach() {
		return targetsInReach != null && !targetsInReach.isEmpty();
	}

	@Override
	public float getSwingProgress() {
		if (lastAttacked > lastSwingDuration || lastSwingDuration <= 0) return 1F;
		return (float) lastAttacked / lastSwingDuration;
	}

	@Override
	public int getUpswingTicks() {
		return upswingTicks;
	}

	@Override
	public void cancelUpswing() {
		cancelAttackCharge();
		upswingStack = null;
		queuedAttack = false;
		queuedAttackHeavy = false;
		itemUseCooldown = 0;
		setMiningCooldown(0);
		isAwaitingUpswing = false;
		isAttacking = false;
		activeAttackHeavy = false;
		if (player != null) ((PlayerAttackProperties) player).setHeavyAttack(false);
	}
}
