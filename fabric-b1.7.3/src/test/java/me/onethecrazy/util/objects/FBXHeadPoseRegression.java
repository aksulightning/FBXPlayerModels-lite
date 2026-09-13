package me.onethecrazy.util.objects;

import me.onethecrazy.util.ModelNormalizer;
import me.onethecrazy.util.model.animation.CustomModelPose;
import me.onethecrazy.util.parsing.FBXParser;
import me.onethecrazy.util.render.LegacyModelRenderer;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.texture.TextureManager;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.PixelFormat;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Imports an actual FBX with Beta's real texture manager and LWJGL 2 renderer. */
public final class FBXHeadPoseRegression {
    private static Vector3f position(Vertex vertex) {
        return new Vector3f(vertex.position.x, vertex.position.y, vertex.position.z);
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static Object call(Object instance, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = instance.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(instance, args);
    }

    private static Matrix4f matrix(Object node, String name, Class<?> nodeClass) throws Exception {
        Object child = call(node, "child", new Class<?>[]{String.class}, name);
        Method method = FBXParser.class.getDeclaredMethod("matrixProperty", nodeClass);
        method.setAccessible(true);
        return new Matrix4f((Matrix4f) ((Optional<?>) method.invoke(null, child)).orElseThrow());
    }

    /** Independent bind calculation from serialized matrices, not the importer's reference helper. */
    private static Map<String, Vector3f> authoredJoints(Path path, Matrix4f normalization) throws Exception {
        Class<?> readerClass = Class.forName(FBXParser.class.getName() + "$BinaryFbxReader");
        Class<?> nodeClass = Class.forName(FBXParser.class.getName() + "$BinaryNode");
        Class<?> indexClass = Class.forName(FBXParser.class.getName() + "$SceneIndex");
        Constructor<?> readerConstructor = readerClass.getDeclaredConstructor(byte[].class);
        readerConstructor.setAccessible(true);
        Object root = call(readerConstructor.newInstance((Object) Files.readAllBytes(path)), "readRoot", new Class<?>[]{});
        Constructor<?> indexConstructor = indexClass.getDeclaredConstructor(nodeClass);
        indexConstructor.setAccessible(true);
        Object index = indexConstructor.newInstance(root);
        Map<?, ?> nodes = (Map<?, ?>) field(index, "nodesById");
        Object objects = call(root, "child", new Class<?>[]{String.class}, "Objects");
        Matrix4f sceneToMesh = null;
        Map<String, Matrix4f> links = new LinkedHashMap<>();
        for (Object geometry : (List<?>) field(objects, "children")) {
            if (!"Geometry".equals(field(geometry, "name"))) continue;
            long geometryId = (long) call(geometry, "longProperty", new Class<?>[]{int.class}, 0);
            long skinId = (long) call(index, "firstConnectedOfType", new Class<?>[]{long.class, String.class, String.class},
                    geometryId, "Deformer", "Skin");
            List<?> clusters = (List<?>) call(index, "connectedOfType", new Class<?>[]{long.class, String.class, String.class},
                    skinId, "Deformer", "Cluster");
            for (Object cluster : clusters) {
                Matrix4f link = matrix(cluster, "TransformLink", nodeClass);
                Matrix4f meshWorld = new Matrix4f(link).mul(matrix(cluster, "Transform", nodeClass));
                if (sceneToMesh == null) sceneToMesh = new Matrix4f(meshWorld).invert();
                // All clusters of this supplied model recover the same mesh-to-world,
                // despite distinct joint bases, positions, and a scene scale of 100.
                if (!new Matrix4f(sceneToMesh).mul(meshWorld).equals(new Matrix4f(), .0001f)) {
                    throw new AssertionError("Regression FBX meshes have different bind spaces");
                }
                long clusterId = (long) call(cluster, "longProperty", new Class<?>[]{int.class}, 0);
                long boneId = (long) call(index, "firstConnectedModel", new Class<?>[]{long.class}, clusterId);
                String name = (String) call(nodes.get(boneId), "stringProperty", new Class<?>[]{int.class}, 1);
                links.putIfAbsent(name.split("\u0000", 2)[0], link);
            }
        }
        Map<String, Vector3f> joints = new LinkedHashMap<>();
        for (var entry : links.entrySet()) {
            Vector3f source = new Matrix4f(sceneToMesh).mul(entry.getValue()).getTranslation(new Vector3f());
            // The existing mesh conversion is (x, z, -y).
            joints.put(entry.getKey(), normalization.transformPosition(new Vector3f(source.x, source.z, -source.y)));
        }
        return joints;
    }

    private static void close(Vector3f actual, Vector3f expected, String reason) {
        if (actual.distance(expected) > .0001f) throw new AssertionError(reason + ": " + actual + " != " + expected);
    }

    private static void checkJoints(SkinnedModel model, Map<String, Vector3f> joints) throws Exception {
        for (SkinnedModel.Bone bone : model.bones) {
            Vector3f imported = new Matrix4f(bone.inverseBind()).invert().getTranslation(new Vector3f());
            close(imported, joints.get(bone.name()), "Authored joint was imported in the wrong coordinate space");
            System.out.println("PASS: authored " + bone.name() + " joint " + imported);
        }
        int head = (int) field(model, "headBoneIndex");
        Vector3f[] localPivots = (Vector3f[]) field(model, "logicalTrackLocalPivots");
        if (localPivots[head] != null) throw new AssertionError("Actual authored neck was replaced by inferred geometry");
        Method applyLook = SkinnedModel.class.getDeclaredMethod("applyHeadLookToSubtree", Matrix4f[].class,
                CustomModelPose.HeadLookRotation.class);
        applyLook.setAccessible(true);
        for (Matrix4f parent : List.of(new Matrix4f(), new Matrix4f().translate(0, -.1f, .05f)
                .rotateY(.3f).rotateX(.2f))) {
            for (float yaw : new float[]{-85, 0, 85}) {
                for (float pitch : new float[]{-90, 0, 90}) {
                    Matrix4f[] globals = new Matrix4f[model.bones.size()];
                    for (int i = 0; i < globals.length; i++) {
                        globals[i] = new Matrix4f(parent).mul(new Matrix4f(model.bones.get(i).inverseBind()).invert());
                    }
                    Matrix4f before = new Matrix4f(globals[head]);
                    Vector3f neck = before.getTranslation(new Vector3f());
                    applyLook.invoke(model, globals, CustomModelPose.createMinecraftHeadLookRotation(yaw, pitch));
                    close(globals[head].getTranslation(new Vector3f()), neck, "Head look moved the bone joint");
                    Matrix4f relative = new Matrix4f(before).invert().mul(globals[head]);
                    close(relative.getTranslation(new Vector3f()), new Vector3f(), "Head look added translation");
                    close(relative.getScale(new Vector3f()), new Vector3f(1), "Head look changed scale");
                }
            }
        }
        System.out.println("PASS: actual head joint stays fixed; yaw/pitch add rotation only, including bent parent poses");
    }

    private static void checkHeadLook(SkinnedModel model, Vector3f neck) throws Exception {
        List<Vertex> rest = model.render("Idle", 0);
        float maximumYawMovement = 0f;
        float maximumPitchMovement = 0f;
        int head = (int) field(model, "headBoneIndex");
        for (float yaw : new float[]{-85, -30, 0, 30, 85}) {
            List<Vertex> yawOnly = model.render("Idle", 0, CustomModelPose.createMinecraftHeadLookRotation(yaw, 0));
            for (float pitch : new float[]{-90, -45, 0, 45, 90}) {
                List<Vertex> posed = model.render("Idle", 0, CustomModelPose.createMinecraftHeadLookRotation(yaw, pitch));
                if (posed.size() != rest.size()) throw new AssertionError("Missing model vertices");
                Matrix4f expectedRotation = new Matrix4f().translation(neck).rotateY((float) Math.toRadians(-yaw))
                        .rotateX((float) Math.toRadians(pitch)).translate(new Vector3f(neck).negate());
                for (int i = 0; i < posed.size(); i++) {
                    Vertex base = rest.get(i);
                    Vertex vertex = posed.get(i);
                    if (!position(vertex).isFinite()) throw new AssertionError("Non-finite pose");
                    SkinnedVertex weighted = model.vertices.get(i);
                    float headWeight = 0f, totalWeight = 0f;
                    for (int j = 0; j < weighted.boneIds.length; j++) {
                        totalWeight += weighted.weights[j];
                        int ancestor = weighted.boneIds[j];
                        while (ancestor >= 0 && ancestor != head) ancestor = model.bones.get(ancestor).parentIndex();
                        if (ancestor == head) headWeight += weighted.weights[j];
                    }
                    float influence = totalWeight > 0f ? headWeight / totalWeight : 0f;
                    Vector3f expected = position(base).lerp(expectedRotation.transformPosition(position(base)), influence);
                    close(position(vertex), expected, "Actual head did not rotate about authored neck; body isolation/weights changed");
                    Vector3f normal = new Vector3f(base.normals.x, base.normals.y, base.normals.z);
                    Vector3f expectedNormal = new Vector3f(normal)
                            .lerp(expectedRotation.transformDirection(new Vector3f(normal)), influence).normalize();
                    close(new Vector3f(vertex.normals.x, vertex.normals.y, vertex.normals.z), expectedNormal, "Normal rotation changed");
                    if (vertex.texture != base.texture || vertex.color != base.color
                            || vertex.textureUV.u != base.textureUV.u || vertex.textureUV.v != base.textureUV.v) {
                        throw new AssertionError("Head look changed material/UV data");
                    }
                    maximumYawMovement = Math.max(maximumYawMovement, position(vertex).distance(position(base)));
                    maximumPitchMovement = Math.max(maximumPitchMovement, position(vertex).distance(position(yawOnly.get(i))));
                }
            }
        }
        if (maximumYawMovement < .01f) throw new AssertionError("No head geometry responded to yaw");
        if (maximumPitchMovement < .01f) throw new AssertionError("No head geometry responded to pitch");
        System.out.println("PASS: actual FBX yaw/pitch rotate about authored neck; weights, body, normals, materials/UVs preserved");
    }

    private static void checkTextures(SkinnedModel model) {
        Set<Integer> textures = new HashSet<>();
        for (SkinnedVertex vertex : model.vertices) textures.add(vertex.vertex.texture);
        for (int texture : textures) {
            if (!GL11.glIsTexture(texture)) throw new AssertionError("FBX texture not uploaded: " + texture);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0) throw new AssertionError("Empty FBX texture");
            System.out.println("PASS: texture " + texture + " uploaded as " + width + "x" + height);
        }
    }

    private static void checkRenderer(SkinnedModel model) {
        GL11.glViewport(0, 0, 128, 128);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(-1.4, 1.4, -.25, 2.3, -10, 10);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glLoadIdentity();
        GL11.glClearColor(1, 0, 1, 1);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glEnable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(false);
        GL11.glColor4f(.2f, .3f, .4f, .5f);
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        int matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        int modelDepth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        int projectionDepth = GL11.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH);
        int texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        List<Vertex> vertices = model.render("Idle", 0, CustomModelPose.createMinecraftHeadLookRotation(30, 90));
        LegacyModelRenderer.render(vertices, () -> GL11.glRotatef(180, 0, 1, 0));
        if (GL11.glGetInteger(GL11.GL_MATRIX_MODE) != matrixMode
                || GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH) != modelDepth
                || GL11.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH) != projectionDepth
                || GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) != texture
                || GL11.glIsEnabled(GL11.GL_BLEND) || GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
                || GL11.glIsEnabled(GL11.GL_ALPHA_TEST) || GL11.glIsEnabled(GL11.GL_TEXTURE_2D)
                || !GL11.glIsEnabled(GL11.GL_CULL_FACE) || GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK)) {
            throw new AssertionError("Legacy renderer leaked OpenGL state");
        }
        // LWJGL 2 requires capacity for the largest glGetFloat result (16),
        // even though GL_CURRENT_COLOR itself returns only four components.
        var color = BufferUtils.createFloatBuffer(16);
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, color);
        float[] expectedColor = {.2f, .3f, .4f, .5f};
        for (int i = 0; i < 4; i++) {
            if (Math.abs(color.get(i) - expectedColor[i]) > .0001f) throw new AssertionError("Renderer leaked color state");
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(128 * 128 * 4);
        GL11.glReadPixels(0, 0, 128, 128, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
        int renderedPixels = 0;
        for (int i = 0; i < pixels.capacity(); i += 4) {
            if ((pixels.get(i) & 255) != 255 || (pixels.get(i + 1) & 255) != 0 || (pixels.get(i + 2) & 255) != 255) {
                renderedPixels++;
            }
        }
        if (GL11.glGetError() != GL11.GL_NO_ERROR) throw new AssertionError("OpenGL render error");
        if (renderedPixels == 0) throw new AssertionError("Renderer produced no model pixels");
        System.out.println("PASS: textured FBX drawn (" + renderedPixels + " pixels); scoped OpenGL state restored");
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply the path to the FBX regression model");
        Path path = Path.of(args[0]);
        Display.setDisplayMode(new DisplayMode(128, 128));
        Display.setTitle("FBX anchored head-pose regression");
        Display.setLocation(-10000, -10000);
        Display.create(new PixelFormat());
        Field gameInstance = FabricLoaderImpl.class.getDeclaredField("gameInstance");
        gameInstance.setAccessible(true);
        Object previousInstance = gameInstance.get(FabricLoaderImpl.INSTANCE);
        try {
            // Standalone fixture only: use the actual concrete Beta client and
            // texture manager without starting a world or changing any config.
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);
            Minecraft minecraft = (Minecraft) unsafe.allocateInstance(Class.forName("net.minecraft.client.Minecraft$RunnableMinecraft"));
            GameOptions options = (GameOptions) unsafe.allocateInstance(GameOptions.class);
            minecraft.textureManager = new TextureManager(null, options);
            gameInstance.set(FabricLoaderImpl.INSTANCE, minecraft);
            SkinnedModel imported = new FBXParser().parseSkinned(path)
                    .orElseThrow(() -> new AssertionError(FBXParser.lastRigStatus()));
            var height = ModelNormalizer.getModelHeight(imported.staticVertices());
            float scale = 2f / (height.u - height.v);
            Matrix4f normalization = new Matrix4f().translate(0, -height.v * scale, 0).scale(scale);
            Map<String, Vector3f> joints = authoredJoints(path, normalization);
            SkinnedModel model = ModelNormalizer.normalize(imported);
            System.out.println("GL: " + GL11.glGetString(GL11.GL_VERSION));
            System.out.println(FBXParser.lastRigStatus());
            System.out.println(FBXParser.lastMaterialStatus());
            checkJoints(model, joints);
            checkTextures(model);
            checkHeadLook(model, joints.get("Head"));
            checkRenderer(model);
        } finally {
            gameInstance.set(FabricLoaderImpl.INSTANCE, previousInstance);
            Display.destroy();
        }
    }
}
