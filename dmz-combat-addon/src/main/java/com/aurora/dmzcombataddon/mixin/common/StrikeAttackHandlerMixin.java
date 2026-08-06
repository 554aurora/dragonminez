package com.aurora.dmzcombataddon.mixin.common;

import com.aurora.dmzcombataddon.combat.GrabTechniqueHandler;
import com.dragonminez.server.events.players.combat.StrikeAttackHandler;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = StrikeAttackHandler.class, remap = false)
public abstract class StrikeAttackHandlerMixin {
    @Inject(method = "requestStrike", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dmzcombataddon$handleGrab(
            ServerPlayer player,
            int preferredTargetId,
            CallbackInfo ci
    ) {
        if (GrabTechniqueHandler.tryStart(player, preferredTargetId)) {
            ci.cancel();
        }
    }
}
