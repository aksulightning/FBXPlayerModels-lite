package me.onethecrazy.util.objects;

import me.onethecrazy.util.ModelNormalizer;
import me.onethecrazy.util.model.animation.CustomModelPose;
import me.onethecrazy.util.model.animation.LogicalRigAnimator;
import me.onethecrazy.util.model.rig.LogicalBodyPart;
import me.onethecrazy.util.model.rig.LogicalRigBinding;
import me.onethecrazy.util.parsing.ParsingFormat;
import me.onethecrazy.util.render.BetaPlayerPoseAdapter;
import net.minecraft.client.input.Input;
import net.minecraft.entity.Entity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Standalone Java 17 regression; uses the Beta development runtime classpath. */
public final class SkinnedModelHeadPoseRegression {
    private static final Vector3f NECK = new Vector3f(0.2f, 1.4f, 0f);

    private static SkinnedVertex vertex(float x, float y, float z, int[] ids, float[] weights) {
        return new SkinnedVertex(new Vertex(new Float3(x, y, z), new Float3(0, 0, 1),
                new Float2(.2f, .7f), 7, 0xFF345678), ids, weights);
    }

    private static SkinnedModel model(Vector3f imported, float tail, boolean stray) {
        List<SkinnedModel.Bone> bones = List.of(
                new SkinnedModel.Bone("Body", -1, new Matrix4f(), new Matrix4f()),
                new SkinnedModel.Bone("Head", 0, new Matrix4f().translation(imported),
                        new Matrix4f().translation(new Vector3f(imported).negate())),
                new SkinnedModel.Bone("Eye", 1, new Matrix4f().translation(0, .1f, .1f),
                        new Matrix4f().translation(new Vector3f(imported).add(0, .1f, .1f).negate()))
        );
        List<SkinnedVertex> vertices = new ArrayList<>();
        vertices.add(vertex(NECK.x, NECK.y, NECK.z, new int[]{1}, new float[]{1}));
        for (float x : new float[]{.1f, .3f}) {
            for (float z : new float[]{-.1f, .1f}) {
                vertices.add(vertex(x, 1.4f, z, new int[]{1}, new float[]{1}));
                vertices.add(vertex(x + .3f, 1.8f, z + .3f, new int[]{1}, new float[]{1}));
            }
        }
        vertices.add(vertex(.2f, 1.7f, .2f, new int[]{2}, new float[]{1}));
        vertices.add(vertex(-.3f, 0, -.2f, new int[]{0}, new float[]{1}));
        vertices.add(vertex(.3f, 1, tail, new int[]{0}, new float[]{1}));
        if (stray) {
            vertices.add(vertex(-.2f, .1f, -.2f, new int[]{0, 1}, new float[]{.999f, .001f}));
        }
        // Duplicating one neck face must not pull the joint toward that surface.
        Vertex duplicate = vertices.get(1).vertex;
        vertices.add(vertex(duplicate.position.x, duplicate.position.y, duplicate.position.z, new int[]{1}, new float[]{1}));
        return new SkinnedModel(bones, vertices, Map.of());
    }

    private static Vector3f position(Vertex vertex) {
        return new Vector3f(vertex.position.x, vertex.position.y, vertex.position.z);
    }

    private static void close(Vector3f actual, Vector3f expected, String reason) {
        if (actual.distance(expected) > .001f) {
            throw new AssertionError(reason + ": " + actual + " != " + expected);
        }
    }

    private static void checkMaterials(SkinnedModel model, List<Vertex> rendered) {
        if (rendered.size() != model.vertices.size()) throw new AssertionError("Missing vertices");
        for (int i = 0; i < rendered.size(); i++) {
            Vertex before = model.vertices.get(i).vertex;
            Vertex after = rendered.get(i);
            if (!position(after).isFinite() || after.texture != before.texture || after.color != before.color
                    || after.textureUV.u != before.textureUV.u || after.textureUV.v != before.textureUV.v) {
                throw new AssertionError("Changed vertex/material semantics");
            }
        }
    }

    private static void checkHead(SkinnedModel model, Vector3f expectedPivot) {
        for (float yaw : new float[]{-85, 0, 85}) {
            for (float pitch : new float[]{-90, 0, 90}) {
                var rotation = CustomModelPose.createMinecraftHeadLookRotation(yaw, pitch);
                if (Math.abs(rotation.pitchRadians() - Math.toRadians(pitch)) > .0001f) {
                    throw new AssertionError("Head look discarded pitch");
                }
                List<Vertex> rendered = model.render("Idle", 0, rotation);
                Matrix4f delta = new Matrix4f().translation(expectedPivot)
                        .rotateY((float) Math.toRadians(-yaw)).rotateX((float) Math.toRadians(pitch))
                        .translate(new Vector3f(expectedPivot).negate());
                for (int i = 0; i < rendered.size(); i++) {
                    SkinnedVertex base = model.vertices.get(i);
                    float headWeight = 0f;
                    for (int j = 0; j < base.boneIds.length; j++) {
                        if (base.boneIds[j] == 1 || base.boneIds[j] == 2) headWeight += base.weights[j];
                    }
                    Vector3f expected = position(base.vertex);
                    Vector3f rotated = delta.transformPosition(new Vector3f(expected));
                    expected.lerp(rotated, headWeight);
                    close(position(rendered.get(i)), expected, "Head/descendant rotation or unrelated-body isolation");
                }
                checkMaterials(model, rendered);
            }
        }
    }

    private static void checkAncestorMotion() {
        for (Vector3f imported : new Vector3f[]{NECK, new Vector3f(0, 140, 0)}) {
            SkinnedModel model = model(imported, -3, true);
            model = model.withAnimations(LogicalRigAnimator.proceduralAnimations(model.bones, null));
            var look = CustomModelPose.createMinecraftHeadLookRotation(35, 45);
            List<Vertex> rendered = model.renderWithForcedHeadLook("Sneak", 0, look, CustomModelPose.LimbPose.NONE);
            Matrix4f torso = new Matrix4f().rotateX((float) Math.toRadians(12));
            Vector3f posedJoint = torso.transformPosition(new Vector3f(NECK));
            Matrix4f headPose = new Matrix4f(torso).translate(NECK)
                    .rotateY((float) Math.toRadians(-35)).rotateX((float) Math.toRadians(45))
                    .translate(new Vector3f(NECK).negate());
            close(position(rendered.get(0)), posedJoint, "Neck failed to inherit chest motion");
            for (int i = 1; i < 10; i++) {
                close(position(rendered.get(i)), headPose.transformPosition(position(model.vertices.get(i).vertex)),
                        "Head lost parent-local rotation/ancestor motion");
            }
            checkMaterials(model, rendered);
        }
    }

    private static void checkAuthoredBindBasisAndNormalization() {
        // Nontrivial authored rotations/scales must not turn gaze into joint
        // translation, and normalization must not alter bone-local coordinates.
        SkinnedModel source = model(NECK, -3, true);
        Matrix4f root = new Matrix4f().translation(.1f, -.3f, .2f).rotateXYZ(.3f, -.5f, .2f).scale(3);
        Matrix4f head = new Matrix4f().translation(NECK).rotateXYZ(.4f, -.6f, .7f).scale(1.2f, .8f, 1.1f);
        Matrix4f eyeLocal = new Matrix4f().translation(0, .1f, .1f);
        Matrix4f headLocal = new Matrix4f(root).invert().mul(head);
        var bones = List.of(new SkinnedModel.Bone("Body", -1, root, new Matrix4f(root).invert()),
                new SkinnedModel.Bone("Head", 0, headLocal, new Matrix4f(head).invert()),
                new SkinnedModel.Bone("Eye", 1, eyeLocal, new Matrix4f(head).mul(eyeLocal).invert()));
        SkinnedModel authored = new SkinnedModel(bones, source.vertices, Map.of());
        checkHead(authored, NECK);
        List<Vector3f> originalPositions = authored.vertices.stream().map(v -> position(v.vertex)).toList();
        var height = ModelNormalizer.getModelHeight(authored.staticVertices());
        float scale = 2f / (height.u - height.v);
        Matrix4f normalization = new Matrix4f().translation(0, -height.v * scale, 0).scale(scale);
        SkinnedModel normalized = ModelNormalizer.normalize(authored);
        if (!normalized.bones.get(0).localBind().equals(new Matrix4f(normalization).mul(root), .0001f)
                || !normalized.bones.get(1).localBind().equals(headLocal, .0001f)
                || !normalized.bones.get(2).localBind().equals(eyeLocal, .0001f)) {
            throw new AssertionError("Normalization changed joint-local coordinates");
        }
        for (int i = 0; i < bones.size(); i++) {
            Matrix4f expected = new Matrix4f(normalization).mul(new Matrix4f(bones.get(i).inverseBind()).invert());
            Matrix4f actual = new Matrix4f(normalized.bones.get(i).inverseBind()).invert();
            if (!actual.equals(expected, .0001f)) throw new AssertionError("Normalization moved an authored joint/basis");
        }
        for (int i = 0; i < originalPositions.size(); i++) {
            close(position(normalized.staticVertices().get(i)), normalization.transformPosition(new Vector3f(originalPositions.get(i))),
                    "Normalized bind mesh changed");
        }
        checkHead(normalized, normalization.transformPosition(new Vector3f(NECK)));
        System.out.println("PASS: rotated/scaled bind bases and mesh-only normalization keep authored joints anchored");
    }

    private static void checkBindings() {
        SkinnedModel base = model(NECK, -.2f, false);
        Vector3f customJoint = new Vector3f(.2f, 1.2f, 0);
        var bones = List.of(base.bones.get(0), base.bones.get(1),
                new SkinnedModel.Bone("CustomNeck", 0, new Matrix4f().translation(customJoint),
                        new Matrix4f().translation(new Vector3f(customJoint).negate())));
        var vertices = List.of(vertex(.3f, 1.3f, .1f, new int[]{2}, new float[]{1}),
                vertex(.3f, 1.5f, .1f, new int[]{1}, new float[]{1}),
                vertex(customJoint.x, customJoint.y, customJoint.z, new int[]{2}, new float[]{1}));
        LogicalRigBinding binding = new LogicalRigBinding();
        binding.setSingle(LogicalBodyPart.HEAD, "CustomNeck");
        var model = new SkinnedModel(bones, vertices, Map.of()).withLogicalRigBinding(binding)
                .withAnimations(Map.of()).withAnimationsEnabled(true);
        var look = CustomModelPose.createMinecraftHeadLookRotation(60, 40);
        List<Vertex> rendered = model.render("Idle", 0, look);
        close(position(rendered.get(0)), look.globalPivotDelta(customJoint).transformPosition(position(vertices.get(0).vertex)),
                "Manual HEAD binding was ignored");
        close(position(rendered.get(1)), position(vertices.get(1).vertex), "Unselected named head rotated");
        close(position(rendered.get(2)), customJoint, "Selected joint moved during rotation");
        for (String name : new String[]{"", "MissingBone"}) {
            LogicalRigBinding fallback = new LogicalRigBinding();
            fallback.setSingle(LogicalBodyPart.HEAD, name);
            checkHead(base.withLogicalRigBinding(fallback), NECK);
        }
        List<Vertex> disabled = model.withAnimationsEnabled(false).render("Idle", 0, look);
        for (int i = 0; i < vertices.size(); i++) {
            close(position(disabled.get(i)), position(vertices.get(i).vertex), "Animation disable changed");
        }
    }

    private static void checkTwoAxisRotation() {
        for (float forward : new float[]{1, -1}) {
            for (boolean useLegs : new boolean[]{false, true}) {
                String left = useLegs ? "LeftLeg" : "LeftArm";
                String right = useLegs ? "RightLeg" : "RightArm";
                var bones = List.of(
                        new SkinnedModel.Bone("Body", -1, new Matrix4f(), new Matrix4f()),
                        new SkinnedModel.Bone("Head", 0, new Matrix4f().translation(NECK),
                                new Matrix4f().translation(new Vector3f(NECK).negate())),
                        new SkinnedModel.Bone(left, 0, new Matrix4f().translation(NECK.x + forward * .4f, 1.2f, 0),
                                new Matrix4f().translation(-NECK.x - forward * .4f, -1.2f, 0)),
                        new SkinnedModel.Bone(right, 0, new Matrix4f().translation(NECK.x - forward * .4f, 1.2f, 0),
                                new Matrix4f().translation(-NECK.x + forward * .4f, -1.2f, 0)));
                var vertices = List.of(
                        vertex(NECK.x, NECK.y, NECK.z, new int[]{1}, new float[]{1}),
                        vertex(NECK.x, NECK.y + .2f, forward * .2f, new int[]{1}, new float[]{1}),
                        vertex(NECK.x + forward * .4f, 1.2f, 0, new int[]{2}, new float[]{1}),
                        vertex(NECK.x - forward * .4f, 1.2f, 0, new int[]{3}, new float[]{1}));
                var model = new SkinnedModel(bones, vertices, Map.of());
                vertices.get(1).vertex.normals = new Float3(0, 0, forward);
                for (float pitch : new float[]{-80, 0, 80}) {
                    var look = CustomModelPose.createMinecraftHeadLookRotation(60, pitch);
                    List<Vertex> rendered = model.render("Idle", 0, look);
                    Matrix4f expected = new Matrix4f().translation(NECK)
                            .rotateY((float) Math.toRadians(-60)).rotateX((float) Math.toRadians(pitch))
                            .translate(new Vector3f(NECK).negate());
                    close(position(rendered.get(0)), NECK, "Two-axis look moved neck");
                    close(position(rendered.get(1)), expected.transformPosition(position(vertices.get(1).vertex)),
                            "Two-axis head rotation/bind coordinates changed for forward=" + forward);
                    Vector3f normal = new Vector3f(rendered.get(1).normals.x, rendered.get(1).normals.y, rendered.get(1).normals.z);
                    close(normal, expected.transformDirection(new Vector3f(0, 0, forward)), "Head normal rotated incorrectly");
                    close(position(rendered.get(2)), position(vertices.get(2).vertex), "Look rotated left limb");
                    close(position(rendered.get(3)), position(vertices.get(3).vertex), "Look rotated right limb");
                    checkMaterials(model, rendered);
                }
                System.out.println("PASS: anchored two-axis head/normals for forward=" + forward + " legBindings=" + useLegs);
            }
        }
    }

    private static void checkPlayerStateTracking() throws Exception {
        // Use the real mapped client-player class and its actual state methods.
        // Allocate without starting a world/client; only render-state fields are
        // needed here. Unsafe is confined to this standalone test fixture.
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        ClientPlayerEntity player = (ClientPlayerEntity) unsafe.allocateInstance(ClientPlayerEntity.class);
        player.input = new Input();
        DataTracker tracker = new DataTracker();
        tracker.startTracking(0, (byte) 0);
        Field trackerField = Entity.class.getDeclaredField("dataTracker");
        trackerField.setAccessible(true);
        trackerField.set(player, tracker);
        Field sleepingField = PlayerEntity.class.getDeclaredField("sleeping");
        sleepingField.setAccessible(true);
        SkinnedModel base = model(NECK, -3, true);
        SkinnedModel model = base.withAnimations(LogicalRigAnimator.proceduralAnimations(base.bones, null));
        CacheSkin cache = new CacheSkin(model, ParsingFormat.FBX);
        for (String state : new String[]{"Idle", "Walk", "Sneak", "Sit", "Sleep"}) {
            sleepingField.setBoolean(player, "Sleep".equals(state));
            player.walkAnimationSpeed = "Walk".equals(state) ? .08f : 0f;
            player.input.sneaking = "Sneak".equals(state);
            player.vehicle = "Sit".equals(state) ? (Entity) unsafe.allocateInstance(ClientPlayerEntity.class) : null;
            player.lastBodyYaw = 179;
            player.bodyYaw = -179;
            player.prevYaw = -175;
            player.yaw = 175;
            if (!state.equals(BetaPlayerPoseAdapter.currentAnimation(player))) throw new AssertionError("Wrong player state");
            for (float pitch : new float[]{-80, 0, 80}) {
                player.prevPitch = pitch * .5f;
                player.pitch = pitch;
                for (float delta : new float[]{0, .5f, 1}) {
                    float bodyYaw = CustomModelPose.lerpAngle(delta, player.lastBodyYaw, player.bodyYaw);
                    float headYaw = CustomModelPose.lerpAngle(delta, player.prevYaw, player.yaw);
                    var look = CustomModelPose.createMinecraftHeadLookRotation(
                            CustomModelPose.wrapDegrees(headYaw - bodyYaw), CustomModelPose.lerp(delta, player.prevPitch, pitch));
                    List<Vertex> rendered = BetaPlayerPoseAdapter.vertices(cache, player, delta, false);
                    List<Vertex> expected = "Sleep".equals(state)
                            ? model.render(state, (player.age + delta) / 20f)
                            : model.renderWithForcedHeadLook(state, (player.age + delta) / 20f, look, CustomModelPose.LimbPose.NONE);
                    for (int i = 0; i < rendered.size(); i++) {
                        close(position(rendered.get(i)), position(expected.get(i)), "Player look lost in " + state);
                    }
                    checkMaterials(model, rendered);
                    List<Vertex> headless = BetaPlayerPoseAdapter.vertices(cache, player, delta, true);
                    List<Vertex> hiddenExpected = model.renderWithHiddenHead(state, (player.age + delta) / 20f,
                            "Sleep".equals(state) ? CustomModelPose.HeadLookRotation.NONE : look, CustomModelPose.LimbPose.NONE);
                    if (headless.size() != hiddenExpected.size()) throw new AssertionError("First-person head hiding changed");
                }
            }
            System.out.println("PASS: Beta player yaw/pitch interpolation and gaze in " + state);
        }
        CacheSkin staticCache = new CacheSkin(List.of(model.vertices.get(0).vertex), ParsingFormat.FBX);
        if (BetaPlayerPoseAdapter.vertices(staticCache, player, 1, false) != staticCache.vertices) {
            throw new AssertionError("Static model fallback changed");
        }
    }

    public static void main(String[] args) throws Exception {
        for (Vector3f imported : new Vector3f[]{NECK, new Vector3f(0, 140, 0)}) {
            for (float tail : new float[]{-.2f, -3}) {
                for (boolean stray : new boolean[]{false, true}) {
                    checkHead(model(imported, tail, stray), NECK);
                }
            }
        }
        Vector3f authoredOffset = new Vector3f(.22f, 1.35f, -.02f);
        checkHead(model(authoredOffset, -3, true), authoredOffset);
        checkAncestorMotion();
        checkAuthoredBindBasisAndNormalization();
        checkBindings();
        checkPlayerStateTracking();
        checkTwoAxisRotation();
        close(CustomModelPose.createMinecraftHeadLookRotation(0, 90).toMatrix().transformDirection(new Vector3f(0, 0, 1)),
                new Vector3f(0, -1, 0), "Minecraft head pitch sign changed");
        close(new CustomModelPose.HeadLookRotation(0, (float) Math.PI / 2).toMatrix().transformDirection(new Vector3f(0, 1, 0)),
                new Vector3f(0, 0, 1), "Directly constructed head pose discarded pitch");
        float yaw = (float) Math.toRadians(85);
        close(CustomModelPose.createMinecraftHeadLookRotation(120, 0).toMatrix().transformDirection(new Vector3f(0, 0, 1)),
                new Vector3f(-(float) Math.sin(yaw), 0, (float) Math.cos(yaw)), "Minecraft yaw sign/clamp changed");
        System.out.println("PASS: neck pivots, yaw/pitch, descendants, ancestor motion, bindings, authored origins, materials");
    }
}
