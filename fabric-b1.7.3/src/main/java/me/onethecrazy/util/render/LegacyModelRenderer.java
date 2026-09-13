package me.onethecrazy.util.render;

import me.onethecrazy.FBXPlayerModelsClient;
import me.onethecrazy.util.objects.Vertex;
import net.minecraft.client.render.Tessellator;
import org.lwjgl.opengl.GL11;

import java.util.List;

public final class LegacyModelRenderer {
    private static final int GL_CLIENT_ALL_ATTRIB_BITS = 0xFFFFFFFF;

    private LegacyModelRenderer() {
    }

    public static void render(List<Vertex> vertices, Runnable transform) {
        if (vertices == null || vertices.isEmpty()) {
            return;
        }

        int previousMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glPushClientAttrib(GL_CLIENT_ALL_ATTRIB_BITS);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        try {
            transform.run();
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1f);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glEnable(GL11.GL_NORMALIZE);
            GL11.glDisable(GL11.GL_CULL_FACE);

            int offset = 0;
            while (offset < vertices.size()) {
                int texture = vertices.get(offset).texture;
                int end = offset + 1;
                while (end < vertices.size() && vertices.get(end).texture == texture) {
                    end++;
                }
                renderBatch(vertices, offset, end, texture);
                offset = end;
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glMatrixMode(previousMatrixMode);
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
        }
    }

    private static void renderBatch(List<Vertex> vertices, int start, int end, int texture) {
        FBXPlayerModelsClient.minecraft().textureManager.bindTexture(texture);
        Tessellator tessellator = Tessellator.INSTANCE;
        tessellator.startQuads();
        for (int index = start; index < end; index++) {
            Vertex vertex = vertices.get(index);
            int color = vertex.color;
            tessellator.color(
                    color >>> 16 & 255,
                    color >>> 8 & 255,
                    color & 255,
                    color >>> 24 & 255
            );
            tessellator.normal(vertex.normals.x, vertex.normals.y, vertex.normals.z);
            tessellator.vertex(
                    vertex.position.x,
                    vertex.position.y,
                    vertex.position.z,
                    vertex.textureUV.u,
                    vertex.textureUV.v
            );
        }
        tessellator.draw();
    }
}
