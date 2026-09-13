package me.onethecrazy.mixin.client;

import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.util.objects.save.FBXPlayerModelsSave;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.player.ClientPlayerEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class CameraMixin {
    @Shadow private Minecraft client;

    @Inject(method = "applyCameraTransform", at = @At("TAIL"))
    private void fbx_player_models$applyFirstPersonCameraOffset(float tickDelta, CallbackInfo ci) {
        FBXPlayerModelsSave options = FBXPlayerModelsClient.options();
        ClientPlayerEntity player = client.player;
        if (!options.isEnabled
                || !options.renderSelfModelInFirstPerson
                || player == null
                || client.camera != player
                || client.options.thirdPerson
                || client.options.debugCamera
                || player.isSleeping()) {
            return;
        }

        if (options.firstPersonCameraOffsetX == 0f
                && options.firstPersonCameraOffsetY == 0f
                && options.firstPersonCameraOffsetZ == 0f) {
            return;
        }

        // Match vanilla's camera interpolation and 1.21.1 Camera.moveBy(-z, y, x):
        // the saved X/Y/Z vector is rotated from view space into world space.
        float yaw = player.prevYaw + (player.yaw - player.prevYaw) * tickDelta;
        float pitch = player.prevPitch + (player.pitch - player.prevPitch) * tickDelta;
        float radians = (float) (Math.PI / 180.0);
        Vector3f offset = new Vector3f(
                options.firstPersonCameraOffsetX,
                options.firstPersonCameraOffsetY,
                options.firstPersonCameraOffsetZ
        ).rotate(new Quaternionf().rotationYXZ((float) Math.PI - yaw * radians, -pitch * radians, 0f));

        // Beta has already applied its view rotation. Moving the camera requires
        // translating the world by the inverse displacement in that matrix.
        GL11.glTranslatef(-offset.x, -offset.y, -offset.z);
    }
}
