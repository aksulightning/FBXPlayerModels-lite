package me.onethecrazy.util.parsing;

import me.onethecrazy.FBXPlayerModelsMod;
import com.aksulightning.fbxplayermodels.model.FbxCoordinateSpace;
import me.onethecrazy.FBXPlayerModels;
import me.onethecrazy.util.objects.Float2;
import me.onethecrazy.util.objects.Float3;
import me.onethecrazy.util.objects.SkinnedModel;
import me.onethecrazy.util.objects.SkinnedVertex;
import me.onethecrazy.util.objects.Vertex;
import me.onethecrazy.util.model.animation.LogicalRigAnimator;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.assimp.*;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class AssimpFBXParser {
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath(FBXPlayerModels.MOD_ID, "textures/white_pixel.png");
    private static String lastStatus = "not checked";
    private static String lastMaterialStatus = "not checked";
    private static List<String> lastMaterialDiagnostics = List.of();

    public static String lastStatus() {
        return lastStatus;
    }

    public static String lastMaterialStatus() {
        return lastMaterialStatus;
    }

    public static List<String> lastMaterialDiagnostics() {
        return lastMaterialDiagnostics;
    }

    public Optional<List<Vertex>> parse(Path path) {
        AIScene scene = importScene(path);
        if (scene == null) {
            return Optional.empty();
        }

        try {
            MaterialResolver materials = new MaterialResolver(scene, path);
            List<Vertex> out = new ArrayList<>();
            PointerBuffer meshes = scene.mMeshes();
            if (meshes == null) {
                return Optional.empty();
            }

            for (MeshInstance instance : meshInstances(scene.mRootNode(), sceneToModel(scene))) {
                AIMesh mesh = AIMesh.create(meshes.get(instance.meshIndex));
                MeshAppearance appearance = materials.appearance(mesh.mMaterialIndex());
                List<Vertex> meshVertices = staticVertices(mesh, appearance);
                transformMesh(meshVertices, instance.globalTransform);
                out.addAll(meshVertices);
            }

            lastStatus = "assimp static meshes=" + scene.mNumMeshes() + " vertices=" + out.size();
            return out.isEmpty() ? Optional.empty() : Optional.of(out);
        } finally {
            Assimp.aiReleaseImport(scene);
        }
    }

    public Optional<SkinnedModel> parseSkinned(Path path) {
        AIScene scene = importScene(path);
        if (scene == null) {
            return Optional.empty();
        }

        try {
            PointerBuffer meshes = scene.mMeshes();
            if (meshes == null) {
                lastStatus = "assimp: no meshes";
                return Optional.empty();
            }

            MaterialResolver materials = new MaterialResolver(scene, path);
            if (!hasMeshBones(scene)) {
                lastStatus = "assimp: no mesh bones";
                return Optional.empty();
            }

            Map<String, Integer> boneIndex = new HashMap<>();
            Map<Long, Integer> nodeIndices = new HashMap<>();
            List<SkinnedModel.Bone> bones = new ArrayList<>();
            Matrix4f sceneToModel = sceneToModel(scene);
            // A single wrapper keeps every imported node and animation key in its authored local axes.
            bones.add(new SkinnedModel.Bone(FbxCoordinateSpace.ROOT_NAME, -1, sceneToModel, new Matrix4f(sceneToModel).invert()));
            buildBoneHierarchy(scene.mRootNode(), 0, sceneToModel, boneIndex, nodeIndices, bones);

            if (bones.isEmpty()) {
                lastStatus = "assimp: no skeleton nodes";
                return Optional.empty();
            }

            List<SkinnedVertex> out = new ArrayList<>();
            for (MeshInstance instance : meshInstances(scene.mRootNode(), sceneToModel(scene))) {
                AIMesh mesh = AIMesh.create(meshes.get(instance.meshIndex));
                MeshAppearance appearance = materials.appearance(mesh.mMaterialIndex());
                List<SkinnedVertex> meshVertices = skinnedVertices(mesh, appearance, boneIndex, nodeIndices);
                transformMesh(meshVertices.stream().map(vertex -> vertex.vertex).toList(), instance.globalTransform);
                out.addAll(meshVertices);
            }

            if (out.isEmpty()) {
                lastStatus = "assimp: no skinned vertices";
                return Optional.empty();
            }

            Map<String, SkinnedModel.Animation> animations = collectAnimations(scene, boneIndex);
            if (animations.isEmpty()) {
                animations = LogicalRigAnimator.proceduralAnimations(bones, null);
            } else {
                LogicalRigAnimator.proceduralAnimations(bones, null).forEach(animations::putIfAbsent);
            }

            SkinnedModel model = new SkinnedModel(bones, out, animations);
            lastStatus = "assimp skinned bones=" + bones.size()
                    + " weighted=" + model.weightedVertexCount() + "/" + model.vertices.size()
                    + " animations=" + animations.keySet();
            return Optional.of(model);
        } finally {
            Assimp.aiReleaseImport(scene);
        }
    }

    private AIScene importScene(Path path) {
        int flags = Assimp.aiProcess_Triangulate
                | Assimp.aiProcess_GenSmoothNormals
                | Assimp.aiProcess_PopulateArmatureData
                | Assimp.aiProcess_FlipUVs;

        AIScene scene = Assimp.aiImportFile(path.toAbsolutePath().toString(), flags);
        if (scene == null) {
            lastStatus = "assimp import failed";
            FBXPlayerModelsMod.LOGGER.warn("Assimp failed to import FBX {}: {}", path, Assimp.aiGetErrorString());
            return null;
        }

        return scene;
    }

    private static List<Vertex> staticVertices(AIMesh mesh, MeshAppearance appearance) {
        List<Vertex> out = new ArrayList<>();
        AIFace.Buffer faces = mesh.mFaces();

        for (int faceIndex = 0; faceIndex < mesh.mNumFaces(); faceIndex++) {
            AIFace face = faces.get(faceIndex);
            IntBuffer indices = face.mIndices();
            if (face.mNumIndices() != 3) {
                continue;
            }

            Vertex a = vertex(mesh, indices.get(0), appearance);
            Vertex b = vertex(mesh, indices.get(1), appearance);
            Vertex c = vertex(mesh, indices.get(2), appearance);
            out.add(a);
            out.add(b);
            out.add(c);
            out.add(copyVertex(c));
        }

        return out;
    }

    private static List<SkinnedVertex> skinnedVertices(AIMesh mesh, MeshAppearance appearance, Map<String, Integer> boneIndex, Map<Long, Integer> nodeIndices) {
        List<BoneWeights> weights = new ArrayList<>(mesh.mNumVertices());
        for (int i = 0; i < mesh.mNumVertices(); i++) {
            weights.add(new BoneWeights());
        }

        PointerBuffer bones = mesh.mBones();
        if (bones != null) {
            for (int i = 0; i < mesh.mNumBones(); i++) {
                AIBone bone = AIBone.create(bones.get(i));
                AINode joint = bone.mNode();
                Integer index = joint == null ? null : nodeIndices.get(joint.address());
                if (index == null) index = boneIndex.get(cleanName(bone.mName().dataString()));
                if (index == null) {
                    continue;
                }

                AIVertexWeight.Buffer boneWeights = bone.mWeights();
                for (int w = 0; w < bone.mNumWeights(); w++) {
                    AIVertexWeight weight = boneWeights.get(w);
                    if (weight.mVertexId() >= 0 && weight.mVertexId() < weights.size()) {
                        weights.get(weight.mVertexId()).add(index, weight.mWeight());
                    }
                }
            }
        }

        List<SkinnedVertex> out = new ArrayList<>();
        AIFace.Buffer faces = mesh.mFaces();
        for (int faceIndex = 0; faceIndex < mesh.mNumFaces(); faceIndex++) {
            AIFace face = faces.get(faceIndex);
            IntBuffer indices = face.mIndices();
            if (face.mNumIndices() != 3) {
                continue;
            }

            SkinnedVertex a = skinnedVertex(mesh, indices.get(0), appearance, weights);
            SkinnedVertex b = skinnedVertex(mesh, indices.get(1), appearance, weights);
            SkinnedVertex c = skinnedVertex(mesh, indices.get(2), appearance, weights);
            out.add(a);
            out.add(b);
            out.add(c);
            out.add(new SkinnedVertex(copyVertex(c.vertex), c.boneIds.clone(), c.weights.clone()));
        }

        return out;
    }

    private static Vertex vertex(AIMesh mesh, int index, MeshAppearance appearance) {
        AIVector3D position = mesh.mVertices().get(index);
        AIVector3D normals = mesh.mNormals() != null ? mesh.mNormals().get(index) : null;
        AIVector3D.Buffer uvs = mesh.mTextureCoords(0);
        AIVector3D uv = uvs != null ? uvs.get(index) : null;

        return new Vertex(
                convert(position),
                normals != null ? convertNormal(normals) : Float3.empty(),
                uv != null ? new Float2(uv.x(), uv.y()) : Float2.empty(),
                appearance.texture,
                appearance.color
        );
    }

    private static SkinnedVertex skinnedVertex(AIMesh mesh, int index, MeshAppearance appearance, List<BoneWeights> weights) {
        BoneWeights weight = index >= 0 && index < weights.size() ? weights.get(index) : BoneWeights.EMPTY;
        return new SkinnedVertex(vertex(mesh, index, appearance), weight.boneIds(), weight.weights());
    }

    private static boolean hasMeshBones(AIScene scene) {
        PointerBuffer meshes = scene.mMeshes();
        if (meshes != null) {
            for (int i = 0; i < scene.mNumMeshes(); i++) {
                if (AIMesh.create(meshes.get(i)).mNumBones() > 0) return true;
            }
        }
        return false;
    }

    private static void buildBoneHierarchy(AINode node, int parentIndex, Matrix4f parentGlobal, Map<String, Integer> boneIndex, Map<Long, Integer> nodeIndices, List<SkinnedModel.Bone> bones) {
        String name = cleanName(node.mName().dataString());
        Matrix4f local = assimpMatrix(node.mTransformation());
        Matrix4f global = new Matrix4f(parentGlobal).mul(local);
        int index = bones.size();
        boneIndex.putIfAbsent(name, index);
        nodeIndices.put(node.address(), index);
        // Helpers, armature roots and unweighted joints retain their authored local transforms.
        bones.add(new SkinnedModel.Bone(name, parentIndex, local, new Matrix4f(global).invert()));
        PointerBuffer children = node.mChildren();
        if (children != null) {
            for (int i = 0; i < node.mNumChildren(); i++) {
                buildBoneHierarchy(AINode.create(children.get(i)), index, global, boneIndex, nodeIndices, bones);
            }
        }
    }

    private record MeshInstance(int meshIndex, Matrix4f globalTransform) {}

    private static List<MeshInstance> meshInstances(AINode root, Matrix4f sceneToModel) {
        List<MeshInstance> instances = new ArrayList<>();
        collectMeshInstances(root, sceneToModel, instances);
        return instances;
    }

    private static void collectMeshInstances(AINode node, Matrix4f parent, List<MeshInstance> instances) {
        Matrix4f global = new Matrix4f(parent).mul(assimpMatrix(node.mTransformation()));
        IntBuffer meshes = node.mMeshes();
        if (meshes != null) {
            for (int i = 0; i < node.mNumMeshes(); i++) {
                instances.add(new MeshInstance(meshes.get(i), new Matrix4f(global)));
            }
        }
        PointerBuffer children = node.mChildren();
        if (children != null) {
            for (int i = 0; i < node.mNumChildren(); i++) {
                collectMeshInstances(AINode.create(children.get(i)), global, instances);
            }
        }
    }

    private static void transformMesh(List<Vertex> vertices, Matrix4f transform) {
        Matrix3f normalTransform = transform.normal(new Matrix3f());
        for (Vertex vertex : vertices) {
            Vector3f position = transform.transformPosition(new Vector3f(vertex.position.x, vertex.position.y, vertex.position.z));
            Vector3f normal = normalTransform.transform(new Vector3f(vertex.normals.x, vertex.normals.y, vertex.normals.z));
            if (normal.lengthSquared() > 0f) normal.normalize();
            vertex.position = new Float3(position.x, position.y, position.z);
            vertex.normals = new Float3(normal.x, normal.y, normal.z);
        }
    }

    private static Map<String, SkinnedModel.Animation> collectAnimations(AIScene scene, Map<String, Integer> boneIndex) {
        Map<String, SkinnedModel.Animation> animations = new HashMap<>();
        PointerBuffer sceneAnimations = scene.mAnimations();
        if (sceneAnimations == null) {
            return animations;
        }

        for (int i = 0; i < scene.mNumAnimations(); i++) {
            AIAnimation animation = AIAnimation.create(sceneAnimations.get(i));
            double ticksPerSecond = animation.mTicksPerSecond() == 0 ? 25.0 : animation.mTicksPerSecond();
            float durationSeconds = (float) (animation.mDuration() / ticksPerSecond);
            Map<Integer, SkinnedModel.BoneTrack> tracks = new HashMap<>();

            PointerBuffer channels = animation.mChannels();
            if (channels != null) {
                for (int c = 0; c < animation.mNumChannels(); c++) {
                    AINodeAnim channel = AINodeAnim.create(channels.get(c));
                    Integer index = boneIndex.get(cleanName(channel.mNodeName().dataString()));
                    if (index == null) {
                        continue;
                    }

                    tracks.put(index, new SkinnedModel.BoneTrack(
                            positions(channel, ticksPerSecond),
                            rotations(channel, ticksPerSecond),
                            scales(channel, ticksPerSecond),
                            false
                    ));
                }
            }

            if (!tracks.isEmpty()) {
                String name = cleanName(animation.mName().dataString());
                if (name.isBlank()) {
                    name = i == 0 ? "Idle" : "Walk";
                }
                SkinnedModel.Animation parsed = new SkinnedModel.Animation(durationSeconds > 0 ? durationSeconds : 1f, tracks);
                animations.put(name, parsed);
                if (i == 0) {
                    animations.putIfAbsent("Idle", parsed);
                } else if (i == 1) {
                    animations.putIfAbsent("Walk", parsed);
                }
                if (name.toLowerCase().contains("idle")) {
                    animations.put("Idle", parsed);
                }
                if (name.toLowerCase().contains("walk")) {
                    animations.put("Walk", parsed);
                }
            }
        }

        return animations;
    }

    private static List<SkinnedModel.KeyVec3> positions(AINodeAnim channel, double ticksPerSecond) {
        List<SkinnedModel.KeyVec3> keys = new ArrayList<>();
        AIVectorKey.Buffer values = channel.mPositionKeys();
        for (int i = 0; i < channel.mNumPositionKeys(); i++) {
            AIVectorKey key = values.get(i);
            AIVector3D value = key.mValue();
            keys.add(new SkinnedModel.KeyVec3((float) (key.mTime() / ticksPerSecond), vector(convert(value))));
        }
        return keys;
    }

    private static List<SkinnedModel.KeyVec3> rotations(AINodeAnim channel, double ticksPerSecond) {
        List<SkinnedModel.KeyVec3> keys = new ArrayList<>();
        AIQuatKey.Buffer values = channel.mRotationKeys();
        for (int i = 0; i < channel.mNumRotationKeys(); i++) {
            AIQuatKey key = values.get(i);
            AIQuaternion value = key.mValue();
            Quaternionf q = new Quaternionf(value.x(), value.y(), value.z(), value.w());
            Vector3f euler = q.getEulerAnglesXYZ(new Vector3f());
            keys.add(new SkinnedModel.KeyVec3(
                    (float) (key.mTime() / ticksPerSecond),
                    new Vector3f((float) Math.toDegrees(euler.x), (float) Math.toDegrees(euler.y), (float) Math.toDegrees(euler.z))
            ));
        }
        return keys;
    }

    private static List<SkinnedModel.KeyVec3> scales(AINodeAnim channel, double ticksPerSecond) {
        List<SkinnedModel.KeyVec3> keys = new ArrayList<>();
        AIVectorKey.Buffer values = channel.mScalingKeys();
        for (int i = 0; i < channel.mNumScalingKeys(); i++) {
            AIVectorKey key = values.get(i);
            AIVector3D value = key.mValue();
            keys.add(new SkinnedModel.KeyVec3((float) (key.mTime() / ticksPerSecond), new Vector3f(value.x(), value.y(), value.z())));
        }
        return keys;
    }

    private static Float3 convert(AIVector3D vector) {
        return new Float3(vector.x(), vector.y(), vector.z());
    }

    private static Float3 convertNormal(AIVector3D vector) {
        Float3 converted = convert(vector);
        float length = (float) Math.sqrt(converted.x * converted.x + converted.y * converted.y + converted.z * converted.z);
        return length == 0 ? Float3.empty() : new Float3(converted.x / length, converted.y / length, converted.z / length);
    }

    private static Vector3f vector(Float3 vector) {
        return new Vector3f(vector.x, vector.y, vector.z);
    }

    private static Matrix4f assimpMatrix(AIMatrix4x4 matrix) {
        Matrix4f result = new Matrix4f(
                matrix.a1(), matrix.b1(), matrix.c1(), matrix.d1(),
                matrix.a2(), matrix.b2(), matrix.c2(), matrix.d2(),
                matrix.a3(), matrix.b3(), matrix.c3(), matrix.d3(),
                matrix.a4(), matrix.b4(), matrix.c4(), matrix.d4()
        );

        return result;
    }

    private static Matrix4f sceneToModel(AIScene scene) {
        AIMetaData metadata = scene.mMetaData();
        // UpAxis describes file-world space, after all authored node transforms are composed.
        return FbxCoordinateSpace.fromUpAxis(metadataInt(metadata, "UpAxis", 1), metadataInt(metadata, "UpAxisSign", 1));
    }

    private static int metadataInt(AIMetaData metadata, String key, int fallback) {
        if (metadata == null) return fallback;
        AIString.Buffer keys = metadata.mKeys();
        AIMetaDataEntry.Buffer values = metadata.mValues();
        if (keys == null || values == null) return fallback;
        for (int i = 0; i < metadata.mNumProperties(); i++) {
            AIMetaDataEntry entry = values.get(i);
            if (key.equals(keys.get(i).dataString()) && entry.mType() == Assimp.AI_INT32) {
                return entry.mData(Integer.BYTES).order(ByteOrder.nativeOrder()).getInt(0);
            }
        }
        return fallback;
    }

    private static String cleanName(String name) {
        int split = name.indexOf('\u0000');
        if (split >= 0) {
            name = name.substring(0, split);
        }
        int modelPrefix = name.indexOf("Model::");
        if (modelPrefix >= 0) {
            name = name.substring(modelPrefix + "Model::".length());
        }
        return name;
    }

    private static Vertex copyVertex(Vertex vertex) {
        return new Vertex(
                new Float3(vertex.position.x, vertex.position.y, vertex.position.z),
                new Float3(vertex.normals.x, vertex.normals.y, vertex.normals.z),
                new Float2(vertex.textureUV.u, vertex.textureUV.v),
                vertex.texture,
                vertex.color
        );
    }

    private record MeshAppearance(Identifier texture, int color) {}

    private static final class BoneWeights {
        static final BoneWeights EMPTY = new BoneWeights();
        final List<Integer> boneIds = new ArrayList<>();
        final List<Float> weights = new ArrayList<>();

        void add(int boneId, float weight) {
            if (weight > 0f) {
                boneIds.add(boneId);
                weights.add(weight);
            }
        }

        int[] boneIds() {
            int count = boneIds.size();
            int[] result = new int[count];
            for (int i = 0; i < count; i++) result[i] = boneIds.get(i);
            return result;
        }

        float[] weights() {
            int count = weights.size();
            float[] result = new float[count];
            for (int i = 0; i < count; i++) {
                result[i] = weights.get(i);
            }
            return result;
        }
    }

    private static final class MaterialResolver {
        private final AIScene scene;
        private final Path sourcePath;
        private final String modelKey;
        private final Map<Integer, MeshAppearance> cache = new HashMap<>();

        MaterialResolver(AIScene scene, Path sourcePath) {
            this.scene = scene;
            this.sourcePath = sourcePath;
            this.modelKey = Integer.toHexString(sourcePath.toAbsolutePath().normalize().toString().hashCode());
            FBXPlayerModelsMod.LOGGER.info("Assimp FBX materials={} textures={} embeddedMedia={}", scene.mNumMaterials(), scene.mNumTextures(), scene.mNumTextures());
            lastMaterialStatus = "FBX materials=" + scene.mNumMaterials() + ", textures=" + scene.mNumTextures() + ", embedded=" + scene.mNumTextures();
            lastMaterialDiagnostics = new ArrayList<>(List.of(
                    "Material count: " + scene.mNumMaterials(),
                    "Texture count: " + scene.mNumTextures(),
                    "Embedded texture count: " + scene.mNumTextures(),
                    "Selected material: " + (scene.mNumMaterials() > 0 ? "Assimp material 0" : "None"),
                    "Selected texture source: fallback"
            ));
        }

        MeshAppearance appearance(int index) {
            return cache.computeIfAbsent(index, this::load);
        }

        private MeshAppearance load(int index) {
            PointerBuffer materials = scene.mMaterials();
            if (materials == null || index < 0 || index >= scene.mNumMaterials()) {
                return new MeshAppearance(WHITE, 0xFFFFFFFF);
            }

            AIMaterial material = AIMaterial.create(materials.get(index));
            Identifier texture = loadTexture(material);
            int color = loadColor(material);
            if (texture == null) {
                FBXPlayerModelsMod.LOGGER.info("Assimp FBX material {} uses fallback diffuse/base color", index);
            }
            return new MeshAppearance(texture != null ? texture : WHITE, color);
        }

        private int loadColor(AIMaterial material) {
            AIColor4D color = AIColor4D.create();
            if (Assimp.aiGetMaterialColor(material, Assimp.AI_MATKEY_COLOR_DIFFUSE, Assimp.aiTextureType_NONE, 0, color) == Assimp.aiReturn_SUCCESS) {
                return ((int) (clamp01(color.a()) * 255) << 24)
                        | ((int) (clamp01(color.r()) * 255) << 16)
                        | ((int) (clamp01(color.g()) * 255) << 8)
                        | (int) (clamp01(color.b()) * 255);
            }
            return 0xFFFFFFFF;
        }

        private Identifier loadTexture(AIMaterial material) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                AIString path = AIString.calloc(stack);
                if (getMaterialTexture(material, Assimp.aiTextureType_DIFFUSE, path) != Assimp.aiReturn_SUCCESS
                        && getMaterialTexture(material, Assimp.aiTextureType_BASE_COLOR, path) != Assimp.aiReturn_SUCCESS) {
                    return null;
                }

                String value = path.dataString();
                if (value.isBlank()) {
                    return null;
                }
                if (value.startsWith("*")) {
                    Identifier id = loadEmbeddedTexture(value);
                    if (id != null) {
                        updateTextureSource("embedded");
                    }
                    return id;
                }

                Path texturePath = resolveTexturePath(value);
                if (texturePath == null) {
                    Identifier embedded = loadEmbeddedTextureForPath(value);
                    if (embedded != null) {
                        updateTextureSource("embedded");
                        return embedded;
                    }
                    FBXPlayerModelsMod.LOGGER.warn("Missing Assimp FBX texture file {} and no embedded match was found", value);
                    appendDiagnostic("Missing texture: " + value);
                    return null;
                }

                Identifier id = DynamicTextureLoader.load(texturePath, "fbx/assimp/file/" + modelKey + "/" + texturePath.getFileName());
                updateTextureSource(Path.of(value.replace('\\', '/')).isAbsolute() ? "absolute file" : "relative file");
                FBXPlayerModelsMod.LOGGER.info("Assimp FBX file texture {}", texturePath);
                return id;
            } catch (Exception e) {
                FBXPlayerModelsMod.LOGGER.warn("Failed to load Assimp FBX texture", e);
                appendDiagnostic("Failed texture load: " + e.getClass().getSimpleName());
                return null;
            }
        }

        private Identifier loadEmbeddedTexture(String value) {
            PointerBuffer textures = scene.mTextures();
            if (textures == null || scene.mNumTextures() == 0) {
                return null;
            }

            int index;
            try {
                index = Integer.parseInt(value.substring(1));
            } catch (NumberFormatException e) {
                return null;
            }
            if (index < 0 || index >= scene.mNumTextures()) {
                return null;
            }

            AITexture texture = AITexture.create(textures.get(index));
            byte[] bytes = compressedTextureBytes(texture);
            if (bytes == null) {
                return null;
            }

            try {
                Identifier id = DynamicTextureLoader.load(bytes, "fbx/assimp/embedded/" + modelKey + "/" + value);
                FBXPlayerModelsMod.LOGGER.info("Assimp FBX embedded texture {}", value);
                return id;
            } catch (Exception e) {
                FBXPlayerModelsMod.LOGGER.warn("Failed to load embedded Assimp FBX texture {}", value, e);
                appendDiagnostic("Failed embedded texture decode: " + e.getClass().getSimpleName());
                return null;
            }
        }

        private Identifier loadEmbeddedTextureForPath(String value) {
            PointerBuffer sceneTextures = scene.mTextures();
            if (sceneTextures == null || scene.mNumTextures() == 0) {
                return null;
            }

            String requested = fileKey(value);
            int fallbackIndex = scene.mNumTextures() == 1 ? 0 : -1;
            for (int i = 0; i < scene.mNumTextures(); i++) {
                AITexture texture = AITexture.create(sceneTextures.get(i));
                String embeddedName = fileKey(texture.mFilename().dataString());
                if (!requested.isBlank() && !embeddedName.isBlank() && requested.equals(embeddedName)) {
                    return loadEmbeddedTextureIndex(i, value);
                }
            }

            if (fallbackIndex >= 0) {
                FBXPlayerModelsMod.LOGGER.info("Assimp FBX external texture {} missing; using sole embedded texture", value);
                return loadEmbeddedTextureIndex(fallbackIndex, value);
            }
            return null;
        }

        private Identifier loadEmbeddedTextureIndex(int index, String sourceName) {
            PointerBuffer sceneTextures = scene.mTextures();
            if (sceneTextures == null || index < 0 || index >= scene.mNumTextures()) {
                return null;
            }

            AITexture texture = AITexture.create(sceneTextures.get(index));
            byte[] bytes = compressedTextureBytes(texture);
            if (bytes == null) {
                return null;
            }

            try {
                Identifier id = DynamicTextureLoader.load(bytes, "fbx/assimp/embedded/" + modelKey + "/" + sourceName);
                FBXPlayerModelsMod.LOGGER.info("Assimp FBX embedded texture {} matched {}", texture.mFilename().dataString(), sourceName);
                return id;
            } catch (Exception e) {
                FBXPlayerModelsMod.LOGGER.warn("Failed to load embedded Assimp FBX texture matched from {}", sourceName, e);
                appendDiagnostic("Failed embedded texture decode: " + e.getClass().getSimpleName());
                return null;
            }
        }

        private static byte[] compressedTextureBytes(AITexture texture) {
            if (texture.mHeight() != 0) {
                return null;
            }

            ByteBuffer data = texture.pcDataCompressed().duplicate();
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            return bytes.length == 0 ? null : bytes;
        }

        private static String fileKey(String value) {
            if (value == null || value.isBlank()) {
                return "";
            }
            String normalized = value.replace('\\', '/');
            int slash = normalized.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < normalized.length()) {
                normalized = normalized.substring(slash + 1);
            }
            return normalized.toLowerCase(Locale.ROOT);
        }

        private void updateTextureSource(String source) {
            List<String> diagnostics = new ArrayList<>(lastMaterialDiagnostics);
            for (int i = 0; i < diagnostics.size(); i++) {
                if (diagnostics.get(i).startsWith("Selected texture source:")) {
                    diagnostics.set(i, "Selected texture source: " + source);
                    lastMaterialDiagnostics = diagnostics;
                    return;
                }
            }
            diagnostics.add("Selected texture source: " + source);
            lastMaterialDiagnostics = diagnostics;
        }

        private void appendDiagnostic(String message) {
            List<String> diagnostics = new ArrayList<>(lastMaterialDiagnostics);
            diagnostics.add(message);
            lastMaterialDiagnostics = diagnostics;
        }

        private Path resolveTexturePath(String filename) {
            String normalized = filename.replace('\\', '/');
            Path candidate = Path.of(normalized);
            if (candidate.isAbsolute() && Files.isRegularFile(candidate)) {
                return candidate.normalize();
            }

            Path parent = sourcePath.getParent();
            if (parent == null) {
                return null;
            }

            List<Path> attempts = new ArrayList<>();
            attempts.add(parent.resolve(normalized).normalize());
            Path fileNameOnly = candidate.getFileName();
            if (fileNameOnly != null) {
                for (String sibling : List.of("textures", "Textures", "texture", "Texture")) {
                    attempts.add(parent.resolve(sibling).resolve(fileNameOnly).normalize());
                }
            }

            for (Path path : attempts) {
                if (Files.isRegularFile(path)) {
                    return path;
                }
            }
            return null;
        }

        private static int getMaterialTexture(AIMaterial material, int textureType, AIString path) {
            return Assimp.aiGetMaterialTexture(material, textureType, 0, path, (int[]) null, (int[]) null, (float[]) null, (int[]) null, (int[]) null, (int[]) null);
        }

        private static float clamp01(float value) {
            return value < 0f ? 0f : Math.min(value, 1f);
        }
    }
}
