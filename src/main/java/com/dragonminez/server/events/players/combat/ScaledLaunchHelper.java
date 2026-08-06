package com.dragonminez.server.events.players.combat;

import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsProvider;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Converts attack power versus defense into a capped, predictable launch. */
public final class ScaledLaunchHelper {
	public static final double MAX_DISTANCE_BLOCKS = 10.0D;
	private static final double MIN_DISTANCE_BLOCKS = 1.5D;
	private static final double VELOCITY_PER_BLOCK = 0.085D;

	private ScaledLaunchHelper() {}

	public static double calculateDistance(double attackPower, LivingEntity target) {
		double offense = sanitizePositive(attackPower);
		double defense = resolveDefense(target);
		double ratio = offense <= 0.0D ? 0.0D : offense / (offense + defense);
		double distance = MIN_DISTANCE_BLOCKS
				+ (MAX_DISTANCE_BLOCKS - MIN_DISTANCE_BLOCKS) * ratio;

		double knockbackResistance = Mth.clamp(
				target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0.0D, 1.0D);
		distance *= 1.0D - (knockbackResistance * 0.75D);
		return Mth.clamp(distance, MIN_DISTANCE_BLOCKS, MAX_DISTANCE_BLOCKS);
	}

	public static double launch(Player attacker, LivingEntity target, double attackPower) {
		double distance = calculateDistance(attackPower, target);
		Vec3 direction = target.position().subtract(attacker.position());
		direction = new Vec3(direction.x, 0.0D, direction.z);
		if (direction.lengthSqr() < 1.0E-6D) {
			Vec3 look = attacker.getLookAngle();
			direction = new Vec3(look.x, 0.0D, look.z);
		}
		direction = direction.normalize();

		double horizontalSpeed = distance * VELOCITY_PER_BLOCK;
		double verticalSpeed = Math.min(0.42D, 0.20D + distance * 0.02D);
		KnockbackHelper.apply(target, new Vec3(
				direction.x * horizontalSpeed,
				verticalSpeed,
				direction.z * horizontalSpeed));
		return distance;
	}

	private static double resolveDefense(LivingEntity target) {
		if (target instanceof Player player) {
			double dmzDefense = StatsProvider.get(StatsCapability.INSTANCE, player)
					.map(data -> data.getStatus().isHasCreatedCharacter() ? data.getDefense() : 0.0D)
					.orElse(0.0D);
			if (dmzDefense > 0.0D) return sanitizePositive(dmzDefense);
		}

		double armor = sanitizePositive(target.getAttributeValue(Attributes.ARMOR));
		double toughness = sanitizePositive(target.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
		return (armor * 5.0D) + (toughness * 5.0D);
	}

	private static double sanitizePositive(double value) {
		return Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
	}
}
