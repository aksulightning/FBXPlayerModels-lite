package me.onethecrazy.screens.rendering;

import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.SkinManager;
import me.onethecrazy.util.model.animation.CustomModelPose;
import me.onethecrazy.util.objects.CacheSkin;
import me.onethecrazy.util.objects.Vertex;
import me.onethecrazy.util.render.LegacyModelRenderer;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Objects;

/** Beta fixed-function counterpart of the modern model preview renderer. */
public final class SkinPreviewRenderer {
    private final int x;
    private final int y;
    private final int dimensions;
    private final float scale;
    private float yaw;
    private float pitch;
    private String selectedPreviewHash = "";
    private CacheSkin selectedPreviewCache;

    public SkinPreviewRenderer(int x, int y, int dimensions, float scale) {
        this.x = x;
        this.y = y;
        this.dimensions = dimensions;
        this.scale = scale;
    }

    public boolean renderPreview(float tickDelta, int guiWidth, int guiHeight) {
        if (!FBXPlayerModelsClient.options().isEnabled) {
            return false;
        }

        CacheSkin cacheSkin = SkinManager.getSelfSkin();
        if (!hasRenderablePreview(cacheSkin)) {
            cacheSkin = selectedPreviewCache();
        }
        if (!hasRenderablePreview(cacheSkin)) {
            return false;
        }

        List<Vertex> vertices = cacheSkin.vertices;
        if (cacheSkin.skinnedModel != null) {
            float seconds = (System.currentTimeMillis() % 600_000L) / 1000f;
            vertices = cacheSkin.skinnedModel.render(
                    "Idle",
                    seconds + tickDelta / 20f,
                    CustomModelPose.HeadLookRotation.NONE,
                    CustomModelPose.LimbPose.NONE
            );
        }
        if (vertices == null || vertices.isEmpty()) {
            return false;
        }

        int displayWidth = FBXPlayerModelsClient.minecraft().displayWidth;
        int displayHeight = FBXPlayerModelsClient.minecraft().displayHeight;
        int scaleX = Math.max(1, displayWidth / Math.max(1, guiWidth));
        int scaleY = Math.max(1, displayHeight / Math.max(1, guiHeight));
        GL11.glPushAttrib(GL11.GL_SCISSOR_BIT);
        try {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(x * scaleX, (guiHeight - y - dimensions) * scaleY,
                    dimensions * scaleX, dimensions * scaleY);
            List<Vertex> renderedVertices = vertices;
            LegacyModelRenderer.render(renderedVertices, () -> {
                GL11.glTranslatef(x + dimensions / 2f, y + dimensions - 7f, 100f);
                GL11.glScalef(scale, -scale, scale);
                GL11.glRotatef(yaw, 0f, 1f, 0f);
                GL11.glRotatef(pitch, 1f, 0f, 0f);
            });
        } finally {
            GL11.glPopAttrib();
        }
        return true;
    }

    public void addRotation(float yawDelta, float pitchDelta) {
        yaw += yawDelta;
        pitch = Math.max(-80f, Math.min(80f, pitch + pitchDelta));
    }

    private CacheSkin selectedPreviewCache() {
        String selectedHash = FBXPlayerModelsClient.options().selectedSkin.hash;
        if (!Objects.equals(selectedPreviewHash, selectedHash)) {
            selectedPreviewHash = selectedHash;
            selectedPreviewCache = SkinManager.loadSelectedSkinPreview();
        }
        return selectedPreviewCache;
    }

    private static boolean hasRenderablePreview(CacheSkin cacheSkin) {
        return cacheSkin != null
                && (cacheSkin.skinnedModel != null
                || cacheSkin.vertices != null && !cacheSkin.vertices.isEmpty());
    }
}
