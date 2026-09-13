package me.onethecrazy.mixin.client;

import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.screens.ConfigScreen;
import net.minecraft.entity.player.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientChatMixin {
    @Inject(method = "sendChatMessage", at = @At("HEAD"), cancellable = true)
    private void fbx_player_models$openConfigLocally(String message, CallbackInfo ci) {
        if (message != null && "/skin".equalsIgnoreCase(message.trim())) {
            FBXPlayerModelsClient.minecraft().setScreen(new ConfigScreen(null));
            ci.cancel();
        }
    }
}
