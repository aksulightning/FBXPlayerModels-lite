package me.onethecrazy.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import me.onethecrazy.util.objects.Float2;
import me.onethecrazy.util.objects.Float3;
import me.onethecrazy.util.objects.SkinnedModel;
import me.onethecrazy.util.objects.SkinnedVertex;
import me.onethecrazy.util.objects.Vertex;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class ModelNormalizer {
    public static List<Vertex> normalize(List<Vertex> vertices){

        // Get the height in pixel
        Float2 pxMinMax = getModelHeight(vertices);

        float minY = pxMinMax.v, maxY = pxMinMax.u;
        float heightPx = maxY - minY;

        // Get Scale Factor
        float pixelsPerBlock = 1f;
        // Model should be 2 Block height
        if (!Float.isFinite(heightPx) || heightPx <= 0f) {
            return vertices;
        }
        float scaleFactor = 2f * pixelsPerBlock / heightPx;  // = 32 / heightPx

        Matrix4f xf = new Matrix4f()
                .translate(0f, -minY * scaleFactor, 0f)
                .scale(scaleFactor);

        // Scale vertices
        for (Vertex v : vertices) {
            Vector3f scaledVertex = xf.transformPosition(new Vector3f(v.position.x, v.position.y, v.position.z));

            v.position = new Float3(scaledVertex.x, scaledVertex.y, scaledVertex.z);
        }

        return vertices;
    }

    public static List<Vertex> normalize(Optional<List<Vertex>> vertices){
        return vertices.map(ModelNormalizer::normalize).orElse(List.of());
    }

    public static SkinnedModel normalize(SkinnedModel model){
        if (model.isNormalized()) {
            return model;
        }
        List<Vertex> vertices = model.staticVertices();
        Float2 pxMinMax = getModelHeight(vertices);

        float minY = pxMinMax.v, maxY = pxMinMax.u;
        float heightPx = maxY - minY;
        if (!Float.isFinite(heightPx) || heightPx <= 0f) {
            return model;
        }
        float scaleFactor = 2f / heightPx;

        Matrix4f xf = new Matrix4f()
                .translate(0f, -minY * scaleFactor, 0f)
                .scale(scaleFactor);

        List<SkinnedVertex> normalizedVertices = new ArrayList<>(model.vertices.size());
        for (SkinnedVertex skinned : model.vertices) {
            Vertex v = skinned.vertex;
            Vector3f position = xf.transformPosition(new Vector3f(v.position.x, v.position.y, v.position.z));
            Vector3f normal = new Vector3f(v.normals.x, v.normals.y, v.normals.z);
            if (normal.lengthSquared() > 0f) normal.normalize();
            Vertex normalized = new Vertex(new Float3(position.x, position.y, position.z),
                    new Float3(normal.x, normal.y, normal.z), new Float2(v.textureUV.u, v.textureUV.v), v.texture, v.color);
            normalizedVertices.add(new SkinnedVertex(normalized, skinned.boneIds, skinned.weights));
        }

        List<SkinnedModel.Bone> bones = new ArrayList<>(model.bones.size());
        List<Matrix4f> localBinds = new ArrayList<>(model.bones.size());
        for (SkinnedModel.Bone bone : model.bones) {
            localBinds.add(bone.parentIndex() < 0
                    ? new Matrix4f(xf).mul(bone.localBind())
                    : new Matrix4f(bone.localBind()));
        }

        Matrix4f[] globalBinds = new Matrix4f[model.bones.size()];
        for (int i = 0; i < model.bones.size(); i++) {
            Matrix4f localBind = localBinds.get(i);
            Matrix4f inverseGlobalBind = new Matrix4f(globalBind(i, model.bones, localBinds, globalBinds)).invert();
            bones.add(new SkinnedModel.Bone(model.bones.get(i).name(), model.bones.get(i).parentIndex(), localBind, inverseGlobalBind));
        }

        return model.withNormalizedGeometry(bones, normalizedVertices, xf);
    }

    private static Matrix4f globalBind(int boneIndex, List<SkinnedModel.Bone> bones, List<Matrix4f> localBinds, Matrix4f[] globalBinds) {
        if (globalBinds[boneIndex] != null) {
            return globalBinds[boneIndex];
        }

        SkinnedModel.Bone bone = bones.get(boneIndex);
        globalBinds[boneIndex] = bone.parentIndex() >= 0
                ? new Matrix4f(globalBind(bone.parentIndex(), bones, localBinds, globalBinds)).mul(localBinds.get(boneIndex))
                : new Matrix4f(localBinds.get(boneIndex));
        return globalBinds[boneIndex];
    }

    public static Float2 getModelHeight(List<Vertex> vertices){
        float minY = Float.POSITIVE_INFINITY,
                maxY = Float.NEGATIVE_INFINITY;

        for(Vertex v : vertices){
            if(v.position.y < minY){
                minY = v.position.y;
            }
            if (v.position.y > maxY){
                maxY = v.position.y;
            }
        }

        return new Float2(maxY, minY);
    }
}
