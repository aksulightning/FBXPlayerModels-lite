package me.onethecrazy.util.objects;

import me.onethecrazy.FBXPlayerModelsMod;
import me.onethecrazy.util.model.animation.CustomModelPose;
import me.onethecrazy.util.model.rig.LogicalBodyPart;
import me.onethecrazy.util.model.rig.LogicalRigBinding;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SkinnedModel {
    private static final boolean DEBUG_SKINNING = Boolean.getBoolean("fbxplayermodels.debugSkinning");
    private static final Set<String> MISSING_HEAD_WARNING_KEYS = ConcurrentHashMap.newKeySet();
    private static final Set<String> INFERRED_PIVOT_WARNING_KEYS = ConcurrentHashMap.newKeySet();
    private static boolean skinningDebugLogged;

    public final List<Bone> bones;
    public final List<SkinnedVertex> vertices;
    public final Map<String, Animation> animations;
    private final boolean animationsEnabled;
    private final LogicalRigBinding logicalRigBinding;
    private final int headBoneIndex;
    private final int rightArmBoneIndex;
    private final int leftArmBoneIndex;
    private final int rightLegBoneIndex;
    private final int leftLegBoneIndex;
    private final Vector3f headPosePivot;
    private final Vector3f rightArmPosePivot;
    private final Vector3f leftArmPosePivot;
    private final Vector3f rightLegPosePivot;
    private final Vector3f leftLegPosePivot;
    private final Vector3f[] logicalTrackLocalPivots;

    public SkinnedModel(List<Bone> bones, List<SkinnedVertex> vertices, Map<String, Animation> animations) {
        this(bones, vertices, animations, LogicalRigBinding.autoBind(bones.stream().map(Bone::name).toList()), true);
    }

    private SkinnedModel(List<Bone> bones, List<SkinnedVertex> vertices, Map<String, Animation> animations, LogicalRigBinding logicalRigBinding, boolean animationsEnabled) {
        this.bones = bones;
        this.vertices = vertices;
        this.animations = animations;
        this.animationsEnabled = animationsEnabled;
        this.logicalRigBinding = logicalRigBinding == null
                ? LogicalRigBinding.autoBind(bones.stream().map(Bone::name).toList())
                : logicalRigBinding;
        this.headBoneIndex = resolveHeadBoneIndex();
        this.rightArmBoneIndex = resolveBodyPartBoneIndex(LogicalBodyPart.RIGHT_ARM);
        this.leftArmBoneIndex = resolveBodyPartBoneIndex(LogicalBodyPart.LEFT_ARM);
        this.rightLegBoneIndex = resolveBodyPartBoneIndex(LogicalBodyPart.RIGHT_LEG);
        this.leftLegBoneIndex = resolveBodyPartBoneIndex(LogicalBodyPart.LEFT_LEG);
        this.headPosePivot = resolvePosePivot(headBoneIndex, LogicalBodyPart.HEAD);
        this.rightArmPosePivot = resolvePosePivot(rightArmBoneIndex, LogicalBodyPart.RIGHT_ARM);
        this.leftArmPosePivot = resolvePosePivot(leftArmBoneIndex, LogicalBodyPart.LEFT_ARM);
        this.rightLegPosePivot = resolvePosePivot(rightLegBoneIndex, LogicalBodyPart.RIGHT_LEG);
        this.leftLegPosePivot = resolvePosePivot(leftLegBoneIndex, LogicalBodyPart.LEFT_LEG);
        this.logicalTrackLocalPivots = createLogicalTrackLocalPivots();
        warnOnceIfMissingHeadBone();
    }

    public SkinnedModel withAnimations(Map<String, Animation> animations) {
        return new SkinnedModel(bones, vertices, animations, logicalRigBinding, animationsEnabled);
    }

    public SkinnedModel withLogicalRigBinding(LogicalRigBinding logicalRigBinding) {
        return new SkinnedModel(bones, vertices, animations, logicalRigBinding, animationsEnabled);
    }

    public SkinnedModel withAnimationsEnabled(boolean animationsEnabled) {
        return new SkinnedModel(bones, vertices, animations, logicalRigBinding, animationsEnabled);
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
        return render(animationName, seconds, CustomModelPose.HeadLookRotation.NONE);
    }

    public List<Vertex> render(String animationName, float seconds, CustomModelPose.HeadLookRotation headLookRotation) {
        return render(animationName, seconds, headLookRotation, CustomModelPose.LimbPose.NONE);
    }

    public List<Vertex> render(String animationName, float seconds, CustomModelPose.HeadLookRotation headLookRotation, CustomModelPose.LimbPose limbPose) {
        return render(animationName, seconds, headLookRotation, limbPose, false, false);
    }

    public List<Vertex> renderWithForcedHeadLook(String animationName, float seconds, CustomModelPose.HeadLookRotation headLookRotation, CustomModelPose.LimbPose limbPose) {
        return render(animationName, seconds, headLookRotation, limbPose, false, true);
    }

    public List<Vertex> renderWithHiddenHead(String animationName, float seconds, CustomModelPose.HeadLookRotation headLookRotation, CustomModelPose.LimbPose limbPose) {
        return render(animationName, seconds, headLookRotation, limbPose, true, false);
    }

    private List<Vertex> render(String animationName, float seconds, CustomModelPose.HeadLookRotation headLookRotation, CustomModelPose.LimbPose limbPose, boolean hideHead, boolean forceHeadLook) {
        if (!animationsEnabled) {
            return staticVertices(hideHead);
        }

        Animation animation = animations.get(animationName);
        if (animation == null) {
            if ("Idle".equals(animationName) || forceHeadLook) {
                return renderPose(null, seconds, headLookRotation, true, limbPose, hideHead);
            }
            return staticVertices(hideHead);
        }

        boolean logicalRigDriven = animation.logicalRigDriven();
        return renderPose(animation, seconds, headLookRotation, forceHeadLook || (logicalRigDriven && "Idle".equals(animationName)), logicalRigDriven ? limbPose : CustomModelPose.LimbPose.NONE, hideHead);
    }

    private List<Vertex> renderPose(Animation animation, float seconds, CustomModelPose.HeadLookRotation headLookRotation, boolean applyIdleHeadLook, CustomModelPose.LimbPose limbPose, boolean hideHead) {
        Matrix4f[] globals = new Matrix4f[bones.size()];
        Matrix4f[] skin = new Matrix4f[bones.size()];
        for (int i = 0; i < bones.size(); i++) {
            globalTransform(i, animation, seconds, limbPose, globals);
        }

        if (applyIdleHeadLook && headBoneIndex >= 0) {
            applyHeadLookToSubtree(globals, headLookRotation);
        }
        applyWalkLimbPose(globals, limbPose);

        for (int i = 0; i < bones.size(); i++) {
            skin[i] = new Matrix4f(globals[i]).mul(bones.get(i).inverseBind);
        }

        if (animation != null && DEBUG_SKINNING && !skinningDebugLogged) {
            logSkinningDebug(seconds, animation, skin);
            skinningDebugLogged = true;
        }

        return renderSkinnedVertices(skin, hideHead);
    }

    private Matrix4f globalTransform(int boneIndex, Animation animation, float seconds, CustomModelPose.LimbPose limbPose, Matrix4f[] globals) {
        if (globals[boneIndex] != null) {
            return globals[boneIndex];
        }

        Bone bone = bones.get(boneIndex);
        Matrix4f local = animation == null || isOverriddenPartBone(boneIndex, limbPose)
                ? new Matrix4f(bone.localBind)
                : animation.localTransform(boneIndex, seconds, bone.localBind,
                        animation.logicalRigDriven() ? logicalTrackLocalPivots[boneIndex] : null);
        globals[boneIndex] = bone.parentIndex >= 0
                ? new Matrix4f(globalTransform(bone.parentIndex, animation, seconds, limbPose, globals)).mul(local)
                : local;
        return globals[boneIndex];
    }

    private boolean isOverriddenPartBone(int boneIndex, CustomModelPose.LimbPose limbPose) {
        return boneIndex == headBoneIndex
                || boneIndex == rightArmBoneIndex && !limbPose.rightArm().isNone()
                || boneIndex == leftArmBoneIndex && !limbPose.leftArm().isNone()
                || boneIndex == rightLegBoneIndex && !limbPose.rightLeg().isNone()
                || boneIndex == leftLegBoneIndex && !limbPose.leftLeg().isNone();
    }

    private void applyHeadLookToSubtree(Matrix4f[] globals, CustomModelPose.HeadLookRotation headLookRotation) {
        // Change the joint's rotation, not its position. Express model-space
        // yaw/pitch in the authored bind basis, then postmultiply the posed
        // joint so its parent posture is inherited and its translation stays
        // fixed. Only genuinely malformed origins need the existing local
        // pivot fallback; correctly imported FBX joints rotate at local zero.
        Matrix4f bindBasis = new Matrix4f(bones.get(headBoneIndex).inverseBind)
                .invert().setTranslation(0f, 0f, 0f);
        Matrix4f localRotation = new Matrix4f(bindBasis).invert()
                .mul(headLookRotation.toMatrix()).mul(bindBasis);
        Vector3f localPivot = logicalTrackLocalPivots[headBoneIndex];
        Matrix4f localLook = localPivot == null ? localRotation : new Matrix4f()
                .translation(localPivot).mul(localRotation)
                .translate(-localPivot.x, -localPivot.y, -localPivot.z);
        Matrix4f before = globals[headBoneIndex];
        Matrix4f after = new Matrix4f(before).mul(localLook);
        Matrix4f delta = new Matrix4f(after).mul(new Matrix4f(before).invert());
        for (int i = 0; i < bones.size(); i++) {
            if (isDescendantOf(i, headBoneIndex)) {
                globals[i] = new Matrix4f(delta).mul(globals[i]);
            }
        }
        globals[headBoneIndex] = after;
    }

    private void applyWalkLimbPose(Matrix4f[] globals, CustomModelPose.LimbPose limbPose) {
        applyBodyPartRotation(globals, rightArmBoneIndex, rightArmPosePivot, limbPose.rightArm());
        applyBodyPartRotation(globals, leftArmBoneIndex, leftArmPosePivot, limbPose.leftArm());
        applyBodyPartRotation(globals, rightLegBoneIndex, rightLegPosePivot, limbPose.rightLeg());
        applyBodyPartRotation(globals, leftLegBoneIndex, leftLegPosePivot, limbPose.leftLeg());
    }

    private void applyBodyPartRotation(Matrix4f[] globals, int boneIndex, Vector3f bindPosePivot, CustomModelPose.BodyPartRotation rotation) {
        if (boneIndex < 0 || rotation.isNone()) {
            return;
        }

        Vector3f pivot = currentPosePivot(globals, boneIndex, bindPosePivot);
        Matrix4f delta = rotation.globalPivotDelta(pivot);
        for (int i = 0; i < bones.size(); i++) {
            if (i == boneIndex || isDescendantOf(i, boneIndex)) {
                globals[i] = new Matrix4f(delta).mul(globals[i]);
            }
        }
    }

    /**
     * Maps a logical joint selected in bind/model space through any pose already
     * contributed by its ancestors. For an authored bone origin this is the same
     * point as globals[boneIndex].getTranslation(); it also lets the internal FBX
     * backend use a safe weighted-mesh joint when an exporter supplied a broken
     * TransformLink origin.
     */
    private Vector3f currentPosePivot(Matrix4f[] globals, int boneIndex, Vector3f bindPosePivot) {
        Vector3f pivot = bindPosePivot == null
                ? new Matrix4f(bones.get(boneIndex).inverseBind).invert().getTranslation(new Vector3f())
                : new Vector3f(bindPosePivot);
        return new Matrix4f(globals[boneIndex])
                .mul(bones.get(boneIndex).inverseBind)
                .transformPosition(pivot);
    }

    private Vector3f resolvePosePivot(int boneIndex, LogicalBodyPart part) {
        if (boneIndex < 0) {
            return null;
        }

        Vector3f imported = new Matrix4f(bones.get(boneIndex).inverseBind)
                .invert()
                .getTranslation(new Vector3f());
        Bounds affected = part == LogicalBodyPart.HEAD ? headAttachmentBounds(boneIndex) : affectedBounds(boneIndex);
        if (affected == null) {
            return imported;
        }

        Bounds model = modelBounds();
        float modelExtent = model == null ? affected.maximumExtent() : model.maximumExtent();
        float allowedDistance = Math.max(0.05f, Math.max(affected.maximumExtent() * 0.5f, modelExtent * 0.15f));
        if (finite(imported) && affected.distanceSquared(imported) <= allowedDistance * allowedDistance) {
            return imported;
        }

        Vector3f inferred = inferAttachmentPoint(boneIndex, part, affected, model == null ? affected : model);
        String warningKey = bones.get(boneIndex).name + "|" + part + "|" + format(imported) + "|" + format(inferred);
        if (INFERRED_PIVOT_WARNING_KEYS.add(warningKey)) {
            FBXPlayerModelsMod.LOGGER.warn(
                    "FBX logical rig: {} bone '{}' has bind pivot {} outside weighted bounds {}; using inferred joint {}",
                    part,
                    bones.get(boneIndex).name,
                    format(imported),
                    affected,
                    format(inferred)
            );
        }
        return inferred;
    }

    /**
     * Generated clips (in particular the Sneak chest bend) are sampled in bone
     * local space, unlike the direct head/limb deltas. Rebase only malformed
     * origins into that space so both paths use the same validated joint. Null
     * retains the original sampling path for a valid authored pivot.
     */
    private Vector3f[] createLogicalTrackLocalPivots() {
        Vector3f[] pivots = new Vector3f[bones.size()];
        for (LogicalBodyPart part : LogicalBodyPart.values()) {
            for (String boundName : logicalRigBinding.namesFor(part)) {
                int boneIndex = findBoneIndex(boundName);
                if (boneIndex >= 0) {
                    setLogicalTrackLocalPivot(pivots, boneIndex, resolvePosePivot(boneIndex, part));
                }
            }
        }
        setLogicalTrackLocalPivot(pivots, headBoneIndex, headPosePivot);
        setLogicalTrackLocalPivot(pivots, rightArmBoneIndex, rightArmPosePivot);
        setLogicalTrackLocalPivot(pivots, leftArmBoneIndex, leftArmPosePivot);
        setLogicalTrackLocalPivot(pivots, rightLegBoneIndex, rightLegPosePivot);
        setLogicalTrackLocalPivot(pivots, leftLegBoneIndex, leftLegPosePivot);
        int chestBoneIndex = resolveBodyPartBoneIndex(LogicalBodyPart.CHEST);
        setLogicalTrackLocalPivot(pivots, chestBoneIndex, resolvePosePivot(chestBoneIndex, LogicalBodyPart.CHEST));
        return pivots;
    }

    private void setLogicalTrackLocalPivot(Vector3f[] pivots, int boneIndex, Vector3f modelPivot) {
        if (boneIndex < 0 || modelPivot == null) {
            return;
        }
        Vector3f imported = new Matrix4f(bones.get(boneIndex).inverseBind)
                .invert().getTranslation(new Vector3f());
        if (modelPivot.distanceSquared(imported) > 0.0000000001f) {
            pivots[boneIndex] = bones.get(boneIndex).inverseBind.transformPosition(new Vector3f(modelPivot));
        }
    }

    private Bounds affectedBounds(int ancestorIndex) {
        Bounds bounds = new Bounds();
        for (SkinnedVertex vertex : vertices) {
            if (influencedBySubtree(vertex, ancestorIndex)) {
                bounds.include(vertex.vertex.position);
            }
        }
        return bounds.empty() ? null : bounds;
    }

    private Bounds modelBounds() {
        Bounds bounds = new Bounds();
        for (SkinnedVertex vertex : vertices) {
            bounds.include(vertex.vertex.position);
        }
        return bounds.empty() ? null : bounds;
    }

    private boolean influencedBySubtree(SkinnedVertex vertex, int ancestorIndex) {
        for (int i = 0; i < vertex.boneIds.length; i++) {
            int boneId = vertex.boneIds[i];
            if (vertex.weights[i] > 0f && boneId >= 0 && boneId < bones.size()
                    && (boneId == ancestorIndex || isDescendantOf(boneId, ancestorIndex))) {
                return true;
            }
        }
        return false;
    }

    private float subtreeInfluence(SkinnedVertex vertex, int ancestorIndex) {
        float influence = 0f;
        for (int i = 0; i < vertex.boneIds.length; i++) {
            int boneId = vertex.boneIds[i];
            if (vertex.weights[i] > 0f && boneId >= 0 && boneId < bones.size()
                    && (boneId == ancestorIndex || isDescendantOf(boneId, ancestorIndex))) {
                influence += vertex.weights[i];
            }
        }
        return influence;
    }

    private float headInfluence(SkinnedVertex vertex, int boneIndex) {
        float total = 0f;
        for (int i = 0; i < vertex.boneIds.length; i++) {
            int boneId = vertex.boneIds[i];
            if (vertex.weights[i] > 0f && boneId >= 0 && boneId < bones.size()) {
                total += vertex.weights[i];
            }
        }
        return total > 0f ? subtreeInfluence(vertex, boneIndex) / total : 0f;
    }

    private float minimumHeadAttachmentInfluence(int boneIndex) {
        float strongest = 0f;
        for (SkinnedVertex vertex : vertices) {
            strongest = Math.max(strongest, headInfluence(vertex, boneIndex));
        }
        return strongest * 0.5f;
    }

    private Bounds headAttachmentBounds(int boneIndex) {
        float minimumInfluence = minimumHeadAttachmentInfluence(boneIndex);
        Bounds bounds = new Bounds();
        for (SkinnedVertex vertex : vertices) {
            float influence = headInfluence(vertex, boneIndex);
            if (influence > 0f && influence >= minimumInfluence) {
                bounds.include(vertex.vertex.position);
            }
        }
        return bounds.empty() ? null : bounds;
    }

    /**
     * A broken head origin must be reconstructed from the neck cross-section,
     * not from the full model's center (which can include a long tail). Use the
     * lower band of substantially head-weighted vertices so tiny stray weights
     * on the torso cannot put the joint at the waist. Bounds rather than a
     * vertex average keep duplicate/tessellated faces from biasing the center.
     * This is only the malformed-origin fallback; authored pivots are retained.
     */
    private Vector3f inferHeadAttachmentPoint(int boneIndex, Bounds affected) {
        float minimumInfluence = minimumHeadAttachmentInfluence(boneIndex);
        float bandTop = affected.min.y + Math.max(0.0001f, (affected.max.y - affected.min.y) * 0.1f);
        Bounds neck = new Bounds();
        for (SkinnedVertex vertex : vertices) {
            float influence = headInfluence(vertex, boneIndex);
            if (influence > 0f && influence >= minimumInfluence && vertex.vertex.position.y <= bandTop) {
                neck.include(vertex.vertex.position);
            }
        }
        Vector3f pivot = neck.empty() ? affected.center() : neck.center();
        pivot.y = affected.min.y;
        return pivot;
    }

    private Vector3f inferAttachmentPoint(int boneIndex, LogicalBodyPart part, Bounds affected, Bounds model) {
        if (part == LogicalBodyPart.HEAD) {
            return inferHeadAttachmentPoint(boneIndex, affected);
        }
        boolean chestOwnWeights = false;
        if (part == LogicalBodyPart.CHEST) {
            Bounds torso = new Bounds();
            for (SkinnedVertex vertex : vertices) {
                if (boneInfluence(vertex, boneIndex) > 0f) {
                    torso.include(vertex.vertex.position);
                }
            }
            if (!torso.empty()) {
                affected = torso;
                chestOwnWeights = true;
            }
        }
        Vector3f target = new Vector3f(
                (model.min.x + model.max.x) * 0.5f,
                part == LogicalBodyPart.CHEST ? affected.min.y : affected.max.y,
                (model.min.z + model.max.z) * 0.5f
        );
        float width = Math.max(model.max.x - model.min.x, 0.0001f);
        float height = Math.max(model.max.y - model.min.y, 0.0001f);
        float depth = Math.max(model.max.z - model.min.z, 0.0001f);
        float bestScore = Float.POSITIVE_INFINITY;

        for (SkinnedVertex vertex : vertices) {
            float influence = chestOwnWeights ? boneInfluence(vertex, boneIndex) : subtreeInfluence(vertex, boneIndex);
            if (influence <= 0f) {
                continue;
            }
            Float3 position = vertex.vertex.position;
            float score = normalizedDistanceSquared(position, target, width, height, depth);
            bestScore = Math.min(bestScore, score);
        }

        Vector3f sum = new Vector3f();
        float total = 0f;
        float selectionLimit = bestScore + 0.01f;
        for (SkinnedVertex vertex : vertices) {
            float influence = chestOwnWeights ? boneInfluence(vertex, boneIndex) : subtreeInfluence(vertex, boneIndex);
            if (influence <= 0f) {
                continue;
            }
            Float3 position = vertex.vertex.position;
            if (normalizedDistanceSquared(position, target, width, height, depth) <= selectionLimit) {
                sum.add(position.x * influence, position.y * influence, position.z * influence);
                total += influence;
            }
        }

        if (total > 0f) {
            return sum.div(total);
        }
        return affected.center();
    }

    private float boneInfluence(SkinnedVertex vertex, int boneIndex) {
        float influence = 0f;
        for (int i = 0; i < vertex.boneIds.length; i++) {
            if (vertex.boneIds[i] == boneIndex && vertex.weights[i] > 0f) {
                influence += vertex.weights[i];
            }
        }
        return influence;
    }

    private static float normalizedDistanceSquared(Float3 position, Vector3f target, float width, float height, float depth) {
        float x = (position.x - target.x) / width;
        float y = (position.y - target.y) / height;
        float z = (position.z - target.z) / depth;
        return x * x + y * y + z * z;
    }

    private static boolean finite(Vector3f value) {
        return Float.isFinite(value.x) && Float.isFinite(value.y) && Float.isFinite(value.z);
    }

    private static final class Bounds {
        final Vector3f min = new Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        final Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);

        void include(Float3 position) {
            min.set(
                    Math.min(min.x, position.x),
                    Math.min(min.y, position.y),
                    Math.min(min.z, position.z)
            );
            max.set(
                    Math.max(max.x, position.x),
                    Math.max(max.y, position.y),
                    Math.max(max.z, position.z)
            );
        }

        boolean empty() {
            return !finite(min) || !finite(max);
        }

        float maximumExtent() {
            return Math.max(max.x - min.x, Math.max(max.y - min.y, max.z - min.z));
        }

        float distanceSquared(Vector3f point) {
            float dx = Math.max(min.x - point.x, Math.max(0f, point.x - max.x));
            float dy = Math.max(min.y - point.y, Math.max(0f, point.y - max.y));
            float dz = Math.max(min.z - point.z, Math.max(0f, point.z - max.z));
            return dx * dx + dy * dy + dz * dz;
        }

        Vector3f center() {
            return new Vector3f(min).add(max).mul(0.5f);
        }

        @Override
        public String toString() {
            return format(min) + " -> " + format(max);
        }
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
                Vector3f tn = skin[boneId].transformDirection(new Vector3f(baseNormal)).mul(weight);
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

    private int resolveHeadBoneIndex() {
        int boundHead = resolveBoundHeadBoneIndex();
        if (boundHead >= 0) {
            return boundHead;
        }

        return resolveNamedHeadBoneIndex();
    }

    private int resolveBoundHeadBoneIndex() {
        for (String boundName : logicalRigBinding.namesFor(LogicalBodyPart.HEAD)) {
            int index = findBoneIndex(boundName);
            if (index >= 0) {
                return index;
            }
        }
        return -1;
    }

    private int resolveNamedHeadBoneIndex() {
        Set<String> candidates = new LinkedHashSet<>(List.of(
                "head",
                "Head",
                "HEAD",
                "mixamorig:Head",
                "Bip001 Head"
        ));

        for (String candidate : candidates) {
            int index = findBoneIndex(candidate);
            if (index >= 0) {
                return index;
            }
        }

        for (int i = 0; i < bones.size(); i++) {
            if (isHeadBoneName(bones.get(i).name())) {
                return i;
            }
        }

        return -1;
    }

    private int resolveBodyPartBoneIndex(LogicalBodyPart part) {
        for (String boundName : logicalRigBinding.namesFor(part)) {
            int index = findBoneIndex(boundName);
            if (index >= 0) {
                return index;
            }
        }

        for (int i = 0; i < bones.size(); i++) {
            if (LogicalRigBinding.suggestPart(bones.get(i).name()) == part) {
                return i;
            }
        }

        return -1;
    }

    private int findBoneIndex(String boneName) {
        String normalized = LogicalRigBinding.normalize(boneName);
        for (int i = 0; i < bones.size(); i++) {
            if (LogicalRigBinding.normalize(bones.get(i).name()).equals(normalized)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isHeadBoneName(String boneName) {
        String normalized = LogicalRigBinding.normalize(boneName);
        return normalized.equals("head") || normalized.endsWith("head");
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
            return localTransform(boneIndex, seconds, fallback, null);
        }

        Matrix4f localTransform(int boneIndex, float seconds, Matrix4f fallback, Vector3f localPivot) {
            BoneTrack track = tracks.get(boneIndex);
            if (track == null || durationSeconds <= 0f) {
                return new Matrix4f(fallback);
            }

            return track.sample(seconds % durationSeconds, fallback, localPivot);
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
            return sample(seconds, fallback, null);
        }

        Matrix4f sample(float seconds, Matrix4f fallback, Vector3f localPivot) {
            Vector3f r = sampleVec(rotation, seconds, null);

            if (additive) {
                if (r == null) {
                    return new Matrix4f(fallback);
                }

                Matrix4f transform = new Matrix4f(fallback);
                if (localPivot != null) {
                    transform.translate(localPivot);
                }
                transform
                        .rotateXYZ((float) Math.toRadians(r.x), (float) Math.toRadians(r.y), (float) Math.toRadians(r.z));
                if (localPivot != null) {
                    transform.translate(-localPivot.x, -localPivot.y, -localPivot.z);
                }
                return transform;
            }

            Vector3f fallbackTranslation = fallback.getTranslation(new Vector3f());
            Vector3f fallbackScale = fallback.getScale(new Vector3f());
            Vector3f t = sampleVec(translation, seconds, fallbackTranslation);
            Vector3f s = sampleVec(scale, seconds, fallbackScale);

            if (r == null) {
                r = new Vector3f();
            }

            return new Matrix4f()
                    .translation(t)
                    .rotateXYZ((float) Math.toRadians(r.x), (float) Math.toRadians(r.y), (float) Math.toRadians(r.z))
                    .scale(s);
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
            if (keys.size() == 1 || seconds <= keys.get(0).seconds) {
                return new Vector3f(keys.get(0).value);
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

            return new Vector3f(keys.get(keys.size() - 1).value);
        }
    }

    public record KeyVec3(float seconds, Vector3f value) {}
}
