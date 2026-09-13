package me.onethecrazy.util.render;

import me.onethecrazy.util.model.animation.CustomModelPose;
import me.onethecrazy.util.objects.SkinnedModel;
import me.onethecrazy.util.objects.Vertex;
import net.fabricmc.fabric.api.client.rendering.v1.RenderStateDataKey;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public record CustomSkinRenderData(
        List<Vertex> vertices,
        @Nullable SkinnedModel skinnedModel,
        String animation,
        float animationSeconds,
        CustomModelPose.LimbPose limbPose,
        CustomModelPose.HeadLookRotation headLookRotation,
        float interpolatedBodyYaw,
        float extractedBodyRot,
        float extractedHeadYaw,
        float extractedHeadPitch,
        Pose pose,
        @Nullable Direction bedOrientation,
        float eyeHeight,
        int light
) {
    public CustomSkinRenderData(
            List<Vertex> vertices,
            float extractedBodyRot,
            Pose pose,
            @Nullable Direction bedOrientation,
            float eyeHeight,
            int light
    ) {
        this(
                vertices,
                null,
                "",
                0f,
                CustomModelPose.LimbPose.NONE,
                CustomModelPose.HeadLookRotation.NONE,
                extractedBodyRot,
                extractedBodyRot,
                0f,
                0f,
                pose,
                bedOrientation,
                eyeHeight,
                light
        );
    }

    public static final RenderStateDataKey<CustomSkinRenderData> KEY =
            RenderStateDataKey.create(() -> "fbx_player_models:custom_skin_render_data");
}
