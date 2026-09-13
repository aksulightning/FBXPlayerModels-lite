package me.onethecrazy.mixin.client;

import me.onethecrazy.FBXPlayerModelsClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void fbx_player_models$runClientTasks(CallbackInfo ci) {
        FBXPlayerModelsClient.runPendingTasks();
    }
}
