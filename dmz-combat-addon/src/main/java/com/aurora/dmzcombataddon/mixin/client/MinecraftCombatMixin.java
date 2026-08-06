package com.aurora.dmzcombataddon.mixin.client;

import com.aurora.dmzcombataddon.combat.HeavyAttackHandler;
import com.aurora.dmzcombataddon.network.AddonNetwork;
import com.aurora.dmzcombataddon.network.HeavyAttackC2S;
import com.dragonminez.common.combat.logic.player.PlayerAttackHelper;
import com.dragonminez.common.combat.util.Minecraft_DMZ;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Minecraft.class, priority = 2000)
public abstract class MinecraftCombatMixin {
    @Shadow public LocalPlayer player;
    @Shadow public Screen screen;
    @Shadow public HitResult hitResult;
    @Shadow @Final public Options options;
    @Shadow protected abstract boolean startAttack();

    @Unique private boolean dmzcombataddon$charging;
    @Unique private boolean dmzcombataddon$dispatching;
    @Unique private boolean dmzcombataddon$suppressUntilRelease;
    @Unique private int dmzcombataddon$heldTicks;

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void dmzcombataddon$startCharge(CallbackInfoReturnable<Boolean> cir) {
        if (dmzcombataddon$dispatching) return;

        if (dmzcombataddon$suppressUntilRelease || dmzcombataddon$charging) {
            cir.setReturnValue(false);
            return;
        }

        if (!dmzcombataddon$shouldHandleCombatInput()) return;

        dmzcombataddon$charging = true;
        dmzcombataddon$heldTicks = 0;
        AddonNetwork.sendToServer(new HeavyAttackC2S(HeavyAttackC2S.Action.START));
        cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void dmzcombataddon$suppressHeldVanillaAttack(boolean leftClick, CallbackInfo ci) {
        if (leftClick && (dmzcombataddon$charging || dmzcombataddon$suppressUntilRelease)) {
            ci.cancel();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void dmzcombataddon$tickCharge(CallbackInfo ci) {
        if (player == null) {
            dmzcombataddon$resetLocalState(false);
            return;
        }

        if (dmzcombataddon$suppressUntilRelease && !options.keyAttack.isDown()) {
            dmzcombataddon$suppressUntilRelease = false;
        }

        if (!dmzcombataddon$charging) return;

        if (screen != null || !dmzcombataddon$stillEligible()) {
            dmzcombataddon$resetLocalState(true);
            return;
        }

        if (!options.keyAttack.isDown()) {
            dmzcombataddon$dispatchAttack(false);
            return;
        }

        dmzcombataddon$heldTicks++;
        if (dmzcombataddon$heldTicks >= HeavyAttackHandler.CHARGE_TICKS) {
            dmzcombataddon$dispatchAttack(true);
        }
    }

    @Unique
    private boolean dmzcombataddon$shouldHandleCombatInput() {
        if (player == null || screen != null) return false;
        if (hitResult != null && hitResult.getType() == HitResult.Type.BLOCK) return false;
        if (player.getAttackStrengthScale(0.5F) < 1.0F) return false;
        if (!dmzcombataddon$stillEligible()) return false;

        int combo = ((Minecraft_DMZ) (Object) this).getComboCount();
        return PlayerAttackHelper.getCurrentAttack(player, combo) != null
                && PlayerAttackHelper.canAttack(player);
    }

    @Unique
    private boolean dmzcombataddon$stillEligible() {
        if (player == null || player.isBlocking() || PlayerAttackHelper.isChargingTechnique(player)) return false;
        return StatsProvider.get(StatsCapability.INSTANCE, player)
                .map(data -> data.getStatus().isHasCreatedCharacter()
                        && !data.getStatus().isStunned()
                        && !data.getStatus().isBlocking())
                .orElse(false);
    }

    @Unique
    private void dmzcombataddon$dispatchAttack(boolean heavy) {
        dmzcombataddon$charging = false;
        dmzcombataddon$heldTicks = 0;
        AddonNetwork.sendToServer(new HeavyAttackC2S(
                heavy ? HeavyAttackC2S.Action.RELEASE : HeavyAttackC2S.Action.CANCEL));

        if (heavy) dmzcombataddon$suppressUntilRelease = true;
        dmzcombataddon$dispatching = true;
        try {
            this.startAttack();
        } finally {
            dmzcombataddon$dispatching = false;
        }
    }

    @Unique
    private void dmzcombataddon$resetLocalState(boolean notifyServer) {
        if (notifyServer && dmzcombataddon$charging && player != null) {
            AddonNetwork.sendToServer(new HeavyAttackC2S(HeavyAttackC2S.Action.CANCEL));
        }
        dmzcombataddon$charging = false;
        dmzcombataddon$heldTicks = 0;
        dmzcombataddon$suppressUntilRelease = false;
        dmzcombataddon$dispatching = false;
    }
}
