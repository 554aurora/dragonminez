package com.aurora.dmzcombataddon.mixin.common;

import com.dragonminez.common.stats.techniques.PredefinedTechniques;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(value = PredefinedTechniques.class, remap = false)
public abstract class PredefinedTechniquesMixin {
    @Shadow @Final @Mutable
    public static List<String> STRIKE_IDS;

    @Inject(method = "<clinit>", at = @At("TAIL"), remap = false)
    private static void dmzcombataddon$registerGrabId(CallbackInfo ci) {
        if (STRIKE_IDS.contains("grab")) return;
        ArrayList<String> ids = new ArrayList<>(STRIKE_IDS);
        ids.add(0, "grab");
        STRIKE_IDS = List.copyOf(ids);
    }
}
