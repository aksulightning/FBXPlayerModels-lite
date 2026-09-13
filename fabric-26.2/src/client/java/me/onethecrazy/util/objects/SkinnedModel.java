package me.onethecrazy.util.objects;

import me.onethecrazy.FBXPlayerModelsMod;
import me.onethecrazy.util.model.animation.CustomModelPose;
import me.onethecrazy.util.model.rig.LogicalBodyPart;
import me.onethecrazy.util.model.rig.LogicalRigBinding;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SkinnedModel {
    private static final boolean DEBUG_SKINNING = Boolean.getBoolean("fbxplayermodels.debugSkinning");
    private static final Set<String> MISSING_HEAD_WARNING_KEYS = ConcurrentHashMap.newKeySet();
    private static boolean skinningDebugLogged;

    public final List<Bone> bones;
    public final List<SkinnedVertex> vertices;
    public final Map<String, Animation> animations;
    private final boolean animationsEnabled;
    private final LogicalRigBinding logicalRigBinding;
    private final Matrix4f rootNormalization;
    private final Matrix4f headBindBasis;
    private final Vector3f chestPosturePivot;
    private final int chestBoneIndex;
    private final int headBoneIndex;
    private final int rightArmBoneIndex;
    private final int leftArmBoneIndex;
    private final int rightLegBoneIndex;
    private final int leftLegBoneIndex;

    public SkinnedModel(List<Bone> bones, List<SkinnedVertex> vertices, Map<String, Animation> animations) {
        this(bones, vertices, animations, LogicalRigBinding.autoBind(bones.stream().map(Bone::name).toList()), true, null);
    }

    private SkinnedModel(List<Bone> bones, List<SkinnedVertex> vertices, Map<String, Animation> animations, LogicalRigBinding logicalRigBinding, boolean animationsEnabled, Matrix4f rootNormalization) {
        this.bones = bones;
        this.vertices = vertices;
        this.animations = animations;
        this.animationsEnabled = animationsEnabled;
        this.rootNormalization = rootNormalization;
        this.logicalRigBinding = logicalRigBinding == null
                ? LogicalRigBinding.autoBind(bones.stream().map(Bone::name).toList())
                : logicalRigBinding;
        this.headBoneIndex = resolveBodyPartBoneIndex(LogicalBodyPart.HEAD);
        this.chestBoneIndex = resolveBodyPartBoneIndex(LogicalBodyPart.CHEST);
        this.rightArmBoneIndex = resolveBodyPartBoneIndex(LogicalBodyPart.RIGHT_ARM);
        this.leftArmBoneIndex = resolveBodyPartBoneIndex(LogicalBodyPart.LEFT_ARM);
        this.rightLegBoneIndex = resolveBodyPartBoneIndex(LogicalBodyPart.RIGHT_LEG);
        this.leftLegBoneIndex = resolveBodyPartBoneIndex(LogicalBodyPart.LEFT_LEG);
        this.headBindBasis = headBoneIndex < 0 ? new Matrix4f()
                : new Matrix4f(bones.get(headBoneIndex).inverseBind).invert().setTranslation(0f, 0f, 0f);
        this.chestPosturePivot = inferMalformedChestPivot();
        warnOnceIfMissingHeadBone();
    }

    public SkinnedModel withAnimations(Map<String, Animation> animations) {
        return new SkinnedModel(bones, vertices, animations, logicalRigBinding, animationsEnabled, rootNormalization);
    }

    public SkinnedModel withLogicalRigBinding(LogicalRigBinding logicalRigBinding) {
        return new SkinnedModel(bones, vertices, animations, logicalRigBinding, animationsEnabled, rootNormalization);
    }

    public SkinnedModel withAnimationsEnabled(boolean animationsEnabled) {
        return new SkinnedModel(bones, vertices, animations, logicalRigBinding, animationsEnabled, rootNormalization);
    }

    public boolean isNormalized() {
        return rootNormalization != null;
    }

    public SkinnedModel withNormalizedGeometry(List<Bone> normalizedBones, List<SkinnedVertex> normalizedVertices, Matrix4f normalization) {
        return new SkinnedModel(normalizedBones, normalizedVertices, animations, logicalRigBinding, animationsEnabled, new Matrix4f(normalization));
    }

    public SkinnedModel withVertices(List<SkinnedVertex> replacementVertices) {
        return new SkinnedModel(bones, replacementVertices, animations, logicalRigBinding, animationsEnabled, rootNormalization);
    }

    public boolean hasAnimations() {
        return !bones.isEmpty() && !animations.isEmpty();
    }

    public int weightedVertexCount() {
        int count = 0;
        for (SkinnedVertex vertex : vertices) {
            if (vertex.boneIds.length > 0) {
                count++;
            }
        }
        return count;
    }

    public int trackCount(String animationName) {
        Animation animation = animations.get(animationName);
        return animation == null ? 0 : animation.tracks.size();
    }

    public List<Vertex> render(String animationName, float seconds) {
        return render(animationName, seconds, CustomModelPose.HeadLookRotation.NONE, CustomModelPose.LimbPose.NONE, false, false);
    }

    public List<Vertex> render(String animationName, float seconds, CustomModelPose.HeadLookRotation headLookRotation) {
        return render(animationName, seconds, headLookRotation, CustomModelPose.LimbPose.NONE, false, false);
    }

    public List<Vertex> render(String animationName, float seconds, CustomModelPose.HeadLookRotation headLookRotation, CustomModelPose.LimbPose limbPose) {
        return render(animationName, seconds, headLookRotation, limbPose, false, true);
    }

    public List<Vertex> renderWithHiddenHead(String animationName, float seconds, CustomModelPose.HeadLookRotation headLookRotation, CustomModelPose.LimbPose limbPose) {
        return render(animationName, seconds, headLookRotation, limbPose, true, true);
    }

    private List<Vertex> render(String animationName, float seconds, CustomModelPose.HeadLookRotation headLookRotation, CustomModelPose.LimbPose limbPose, boolean hideHead, boolean liveLimbPose) {
        if (!animationsEnabled) {
            return staticVertices(hideHead);
        }
        boolean sleeping = "Sleep".equals(animationName);
        return renderPose(animations.get(animationName), seconds,
                sleeping ? CustomModelPose.HeadLookRotation.NONE : headLookRotation,
                sleeping ? CustomModelPose.LimbPose.NONE : limbPose, hideHead, liveLimbPose || sleeping);
    }

    private List<Vertex> renderPose(Animation animation, float seconds, CustomModelPose.HeadLookRotation headLookRotation, CustomModelPose.LimbPose limbPose, boolean hideHead, boolean liveLimbPose) {
        Matrix4f[] globals = new Matrix4f[bones.size()];
        Matrix4f[] skin = new Matrix4f[bones.size()];
        Matrix4f localLook = headLookRotation.yawRadians() == 0f && headLookRotation.pitchRadians() == 0f
                ? new Matrix4f()
                : new Matrix4f(headBindBasis).invert().mul(headLookRotation.toMatrix()).mul(headBindBasis);
        for (int i = 0; i < bones.size(); i++) {
            globalTransform(i, animation, seconds, limbPose, liveLimbPose, localLook, globals);
            skin[i] = new Matrix4f(globals[i]).mul(bones.get(i).inverseBind);
        }

        if (animation != null && DEBUG_SKINNING && !skinningDebugLogged) {
            logSkinningDebug(seconds, animation, skin);
            skinningDebugLogged = true;
        }
        return renderSkinnedVertices(skin, hideHead);
    }

    private Matrix4f globalTransform(int boneIndex, Animation animation, float seconds, CustomModelPose.LimbPose limbPose, boolean liveLimbPose, Matrix4f localLook, Matrix4f[] globals) {
        if (globals[boneIndex] != null) {
            return globals[boneIndex];
        }
        Bone bone = bones.get(boneIndex);
        boolean generatedLimb = animation != null && animation.logicalRigDriven && isLimbBone(boneIndex);
        Matrix4f local = new Matrix4f(bone.localBind);
        if (animation != null && !generatedLimb) {
            if (bone.parentIndex < 0 && rootNormalization != null && !animation.logicalRigDriven) {
                // Absolute root keys are still in imported units; apply normalization exactly once.
                Matrix4f importedBind = new Matrix4f(rootNormalization).invert().mul(local);
                local = new Matrix4f(rootNormalization).mul(animation.localTransform(boneIndex, seconds, importedBind));
            } else if (boneIndex == chestBoneIndex && animation.logicalRigDriven) {
                Vector3f r = animation.rotationFor(boneIndex, seconds);
                local.translate(chestPosturePivot)
                        .rotateY((float) Math.toRadians(r.y))
                        .rotateX((float) Math.toRadians(r.x))
                        .rotateZ((float) Math.toRadians(r.z))
                        .translate(-chestPosturePivot.x, -chestPosturePivot.y, -chestPosturePivot.z);
            } else {
                local = animation.localTransform(boneIndex, seconds, local);
            }
        }
        if (boneIndex == headBoneIndex) {
            // Gposed * inverse(B) * R * B preserves the joint and inherits parent posture.
            local.mul(localLook);
        }
        Matrix4f global = bone.parentIndex >= 0
                ? new Matrix4f(globalTransform(bone.parentIndex, animation, seconds, limbPose, liveLimbPose, localLook, globals)).mul(local)
                : local;

        CustomModelPose.BodyPartRotation rotation = liveLimbPose ? limbRotation(boneIndex, limbPose) : CustomModelPose.BodyPartRotation.NONE;
        if (generatedLimb && !liveLimbPose) {
            Vector3f r = animation.rotationFor(boneIndex, seconds);
            rotation = new CustomModelPose.BodyPartRotation((float) Math.toRadians(r.x), (float) Math.toRadians(r.y), (float) Math.toRadians(r.z));
        }
        if (!rotation.isNone()) {
            Vector3f joint = global.getTranslation(new Vector3f());
            global = rotation.globalPivotDelta(joint).mul(global);
        }
        // Children compose with the posed global, so every delta propagates in hierarchy order.
        globals[boneIndex] = global;
        return global;
    }

    private boolean isLimbBone(int index) {
        return index == rightArmBoneIndex || index == leftArmBoneIndex || index == rightLegBoneIndex || index == leftLegBoneIndex;
    }

    private CustomModelPose.BodyPartRotation limbRotation(int index, CustomModelPose.LimbPose pose) {
        if (index == rightArmBoneIndex) return pose.rightArm();
        if (index == leftArmBoneIndex) return pose.leftArm();
        if (index == rightLegBoneIndex) return pose.rightLeg();
        if (index == leftLegBoneIndex) return pose.leftLeg();
        return CustomModelPose.BodyPartRotation.NONE;
    }

    private Vector3f inferMalformedChestPivot() {
        Vector3f localPivot = new Vector3f();
        if (chestBoneIndex < 0 || vertices.isEmpty()) {
            return localPivot;
        }
        Vector3f modelMin = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f modelMax = new Vector3f(Float.NEGATIVE_INFINITY);
        Vector3f chestMin = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f chestMax = new Vector3f(Float.NEGATIVE_INFINITY);
        boolean weighted = false;
        for (SkinnedVertex vertex : vertices) {
            Vector3f p = new Vector3f(vertex.vertex.position.x, vertex.vertex.position.y, vertex.vertex.position.z);
            modelMin.min(p);
            modelMax.max(p);
            for (int i = 0; i < vertex.boneIds.length; i++) {
                if (vertex.boneIds[i] == chestBoneIndex && vertex.weights[i] > 0f) {
                    chestMin.min(p);
                    chestMax.max(p);
                    weighted = true;
                    break;
                }
            }
        }
        if (!weighted) {
            return localPivot;
        }
        Vector3f authoredJoint = new Matrix4f(bones.get(chestBoneIndex).inverseBind).invert().getTranslation(new Vector3f());
        float margin = Math.max(0.001f, modelMax.distance(modelMin)) * 2f;
        boolean farOutside = authoredJoint.x < modelMin.x - margin || authoredJoint.x > modelMax.x + margin
                || authoredJoint.y < modelMin.y - margin || authoredJoint.y > modelMax.y + margin
                || authoredJoint.z < modelMin.z - margin || authoredJoint.z > modelMax.z + margin;
        if (farOutside) {
            Vector3f attachment = new Vector3f((chestMin.x + chestMax.x) * 0.5f, chestMin.y, (chestMin.z + chestMax.z) * 0.5f);
            bones.get(chestBoneIndex).inverseBind.transformPosition(attachment, localPivot);
        }
        return localPivot;
    }

    private boolean isDescendantOf(int boneIndex, int ancestorIndex) {
        int parent = bones.get(boneIndex).parentIndex;
        while (parent >= 0) {
            if (parent == ancestorIndex) {
                return true;
            }
            parent = bones.get(parent).parentIndex;
        }
        return false;
    }

    private List<Vertex> renderSkinnedVertices(Matrix4f[] skin, boolean hideHead) {
        Matrix3f[] normalSkin = new Matrix3f[skin.length];
        for (int i = 0; i < skin.length; i++) {
            normalSkin[i] = skin[i].normal(new Matrix3f());
        }
        List<Vertex> out = new ArrayList<>(vertices.size());
        for (int vertexIndex = 0; vertexIndex < vertices.size(); vertexIndex++) {
            if (hideHead && isHeadTriangleVertex(vertexIndex)) {
                continue;
            }

            SkinnedVertex skinned = vertices.get(vertexIndex);
            Vector3f p = new Vector3f();
            Vector3f n = new Vector3f();
            Vector3f basePos = new Vector3f(skinned.vertex.position.x, skinned.vertex.position.y, skinned.vertex.position.z);
            Vector3f baseNormal = new Vector3f(skinned.vertex.normals.x, skinned.vertex.normals.y, skinned.vertex.normals.z);

            float totalWeight = 0f;
            for (int weightIndex = 0; weightIndex < skinned.boneIds.length; weightIndex++) {
                int boneId = skinned.boneIds[weightIndex];
                float weight = skinned.weights[weightIndex];
                if (boneId < 0 || boneId >= skin.length || weight <= 0f) {
                    continue;
                }

                Vector3f tp = skin[boneId].transformPosition(new Vector3f(basePos)).mul(weight);
                Vector3f tn = normalSkin[boneId].transform(new Vector3f(baseNormal)).mul(weight);
                p.add(tp);
                n.add(tn);
                totalWeight += weight;
            }

            if (totalWeight == 0f) {
                p.set(basePos);
                n.set(baseNormal);
            } else if (totalWeight != 1f) {
                p.div(totalWeight);
                n.div(totalWeight);
            }

            if (n.lengthSquared() == 0f) {
                n.set(baseNormal);
            }
            if (n.lengthSquared() > 0f) {
                n.normalize();
            }

            Vertex v = new Vertex(
                    new Float3(p.x, p.y, p.z),
                    new Float3(n.x, n.y, n.z),
                    new Float2(skinned.vertex.textureUV.u, skinned.vertex.textureUV.v),
                    skinned.vertex.texture,
                    skinned.vertex.color
            );
            out.add(v);
        }

        return out;
    }

    private boolean isHeadTriangleVertex(int vertexIndex) {
        if (headBoneIndex < 0) {
            return false;
        }

        int faceStart = vertexIndex - vertexIndex % 4;
        int faceEnd = Math.min(faceStart + 4, vertices.size());
        for (int i = faceStart; i < faceEnd; i++) {
            if (isWeightedToHead(vertices.get(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean isWeightedToHead(SkinnedVertex skinned) {
        if (headBoneIndex < 0) {
            return false;
        }

        for (int i = 0; i < skinned.boneIds.length; i++) {
            int boneId = skinned.boneIds[i];
            if (boneId >= 0 && boneId < bones.size() && skinned.weights[i] > 0f && (boneId == headBoneIndex || isDescendantOf(boneId, headBoneIndex))) {
                return true;
            }
        }
        return false;
    }

    private int resolveBodyPartBoneIndex(LogicalBodyPart part) {
        return LogicalRigBinding.resolveBoneIndex(bones.stream().map(Bone::name).toList(), logicalRigBinding, part);
    }

    private void warnOnceIfMissingHeadBone() {
        if (headBoneIndex >= 0 || bones.isEmpty()) {
            return;
        }

        String key = String.join("|", bones.stream().map(Bone::name).toList());
        if (MISSING_HEAD_WARNING_KEYS.add(key)) {
            FBXPlayerModelsMod.LOGGER.warn("No FBX head bone found or bound; Minecraft head look rotation cannot be applied.");
        }
    }

    private void logSkinningDebug(float seconds, Animation animation, Matrix4f[] skin) {
        FBXPlayerModelsMod.LOGGER.info("Skinning debug seconds={}", seconds);
        for (int i = 0; i < bones.size(); i++) {
            Bone bone = bones.get(i);
            Vector3f bindPosition = bone.localBind.getTranslation(new Vector3f());
            Vector3f pivot = new Matrix4f(bone.inverseBind).invert().getTranslation(new Vector3f());
            Vector3f rotation = animation.rotationFor(i, seconds);
            String bounds = transformedBounds(i, skin[i]);
            FBXPlayerModelsMod.LOGGER.info(
                    "part={} bind={} pivot={} rotation={} bounds={}",
                    bone.name,
                    format(bindPosition),
                    format(pivot),
                    format(rotation),
                    bounds
            );
        }
    }

    private String transformedBounds(int boneIndex, Matrix4f transform) {
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
        boolean found = false;

        for (SkinnedVertex skinned : vertices) {
            for (int i = 0; i < skinned.boneIds.length; i++) {
                if (skinned.boneIds[i] != boneIndex || skinned.weights[i] <= 0f) {
                    continue;
                }

                Vector3f p = transform.transformPosition(new Vector3f(
                        skinned.vertex.position.x,
                        skinned.vertex.position.y,
                        skinned.vertex.position.z
                ));
                min.min(p);
                max.max(p);
                found = true;
            }
        }

        return found ? format(min) + " -> " + format(max) : "no weighted vertices";
    }

    private static String format(Vector3f value) {
        return String.format(Locale.ROOT, "%.4f,%.4f,%.4f", value.x, value.y, value.z);
    }

    public List<Vertex> staticVertices() {
        return staticVertices(false);
    }

    private List<Vertex> staticVertices(boolean hideHead) {
        List<Vertex> out = new ArrayList<>(vertices.size());
        for (int vertexIndex = 0; vertexIndex < vertices.size(); vertexIndex++) {
            if (hideHead && isHeadTriangleVertex(vertexIndex)) {
                continue;
            }
            out.add(vertices.get(vertexIndex).vertex);
        }
        return out;
    }

    public record Bone(String name, int parentIndex, Matrix4f localBind, Matrix4f inverseBind) {}

    public record Animation(float durationSeconds, Map<Integer, BoneTrack> tracks, boolean logicalRigDriven) {
        public Animation(float durationSeconds, Map<Integer, BoneTrack> tracks) {
            this(durationSeconds, tracks, false);
        }

        public static Animation logicalRigDriven(float durationSeconds, Map<Integer, BoneTrack> tracks) {
            return new Animation(durationSeconds, tracks, true);
        }

        Matrix4f localTransform(int boneIndex, float seconds, Matrix4f fallback) {
            BoneTrack track = tracks.get(boneIndex);
            if (track == null || durationSeconds <= 0f) {
                return new Matrix4f(fallback);
            }

            return track.sample(seconds % durationSeconds, fallback);
        }

        Vector3f rotationFor(int boneIndex, float seconds) {
            BoneTrack track = tracks.get(boneIndex);
            if (track == null || durationSeconds <= 0f) {
                return new Vector3f();
            }

            return track.sampleRotation(seconds % durationSeconds);
        }

        public boolean hasTranslationKeys() {
            for (BoneTrack track : tracks.values()) {
                if (track.hasTranslationKeys()) {
                    return true;
                }
            }
            return false;
        }

        public Animation rotationOnly() {
            return new Animation(durationSeconds, tracks.entrySet().stream().collect(
                    java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> entry.getValue().rotationOnly(),
                            (left, right) -> left,
                            java.util.LinkedHashMap::new
                    )
            ), logicalRigDriven);
        }
    }

    public record BoneTrack(List<KeyVec3> translation, List<KeyVec3> rotation, List<KeyVec3> scale, boolean additive) {
        public BoneTrack(List<KeyVec3> translation, List<KeyVec3> rotation, List<KeyVec3> scale) {
            this(translation, rotation, scale, true);
        }

        Matrix4f sample(float seconds, Matrix4f fallback) {
            Vector3f r = sampleVec(rotation, seconds, null);

            if (additive) {
                if (r == null) {
                    return new Matrix4f(fallback);
                }

                return new Matrix4f(fallback)
                        .rotateXYZ((float) Math.toRadians(r.x), (float) Math.toRadians(r.y), (float) Math.toRadians(r.z));
            }

            Vector3f fallbackTranslation = fallback.getTranslation(new Vector3f());
            Vector3f fallbackScale = fallback.getScale(new Vector3f());
            Vector3f t = sampleVec(translation, seconds, fallbackTranslation);
            Vector3f s = sampleVec(scale, seconds, fallbackScale);

            Matrix4f result = new Matrix4f().translation(t);
            if (r == null) {
                result.rotate(fallback.getUnnormalizedRotation(new Quaternionf()).normalize());
            } else {
                result.rotateXYZ((float) Math.toRadians(r.x), (float) Math.toRadians(r.y), (float) Math.toRadians(r.z));
            }
            return result.scale(s);
        }

        public boolean hasTranslationKeys() {
            return translation != null && !translation.isEmpty();
        }

        public BoneTrack rotationOnly() {
            return new BoneTrack(List.of(), rotation == null ? List.of() : rotation, List.of(), true);
        }

        Vector3f sampleRotation(float seconds) {
            return sampleVec(rotation, seconds, new Vector3f());
        }

        private static Vector3f sampleVec(List<KeyVec3> keys, float seconds, Vector3f fallback) {
            if (keys == null || keys.isEmpty()) {
                return fallback;
            }
            if (keys.size() == 1 || seconds <= keys.getFirst().seconds) {
                return new Vector3f(keys.getFirst().value);
            }

            for (int i = 0; i + 1 < keys.size(); i++) {
                KeyVec3 a = keys.get(i);
                KeyVec3 b = keys.get(i + 1);
                if (seconds <= b.seconds) {
                    float span = b.seconds - a.seconds;
                    float alpha = span <= 0f ? 0f : (seconds - a.seconds) / span;
                    return new Vector3f(a.value).lerp(b.value, alpha);
                }
            }

            return new Vector3f(keys.getLast().value);
        }
    }

    public record KeyVec3(float seconds, Vector3f value) {}
}
