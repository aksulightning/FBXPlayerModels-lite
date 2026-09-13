package me.onethecrazy.mixin.client;

import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.screens.ConfigScreen;
import me.onethecrazy.screens.rendering.SkinPreviewRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class MainMenuMixin extends Screen {
    @Unique
    private static final int FBX_BUTTON_ID = 19_730;
    @Unique
    private SkinPreviewRenderer fbx_player_models$preview;

    @Inject(method = "init", at = @At("TAIL"))
    private void fbx_player_models$addConfigAccess(CallbackInfo ci) {
        buttons.add(new ButtonWidget(FBX_BUTTON_ID, 6, height - 26, 86, 20, "FBX Model"));
        int size = Math.max(64, Math.min(116, Math.min(width / 3, height - 58)));
        fbx_player_models$preview = new SkinPreviewRenderer(width - size - 8, 28, size, size * 0.42f);
    }

    @Inject(method = "buttonClicked", at = @At("HEAD"), cancellable = true)
    private void fbx_player_models$openConfig(ButtonWidget button, CallbackInfo ci) {
        if (button.id == FBX_BUTTON_ID) {
            FBXPlayerModelsClient.minecraft().setScreen(new ConfigScreen((Screen) (Object) this));
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void fbx_player_models$renderPreview(int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
        if (fbx_player_models$preview != null) {
            fbx_player_models$preview.renderPreview(tickDelta, width, height);
        }
    }
}
