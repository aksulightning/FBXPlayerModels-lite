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
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public class FBXParser implements IParser {
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?");
    private static final Identifier WHITE = Identifier.fromNamespaceAndPath(FBXPlayerModels.MOD_ID, "textures/white_pixel.png");
    private static final AssimpFBXParser ASSIMP = new AssimpFBXParser();
    private static String lastRigStatus = "not checked";
    private static String lastMaterialStatus = "not checked";
    private static List<String> lastMaterialDiagnostics = List.of();

    public static String lastRigStatus() {
        return lastRigStatus;
    }

    public static String lastMaterialStatus() {
        return lastMaterialStatus;
    }

    public static List<String> lastMaterialDiagnostics() {
        return lastMaterialDiagnostics;
    }

    @Override
    public Optional<List<Vertex>> parse(Path path) {
        Optional<List<Vertex>> assimp = ASSIMP.parse(path);
        if (assimp.isPresent()) {
            lastRigStatus = AssimpFBXParser.lastStatus();
            lastMaterialStatus = AssimpFBXParser.lastMaterialStatus();
            lastMaterialDiagnostics = AssimpFBXParser.lastMaterialDiagnostics();
            if (hasBoundTexture(assimp.get())) {
                return assimp;
            }

            Optional<List<Vertex>> fallback = parseBinaryFallback(path);
            if (fallback.isPresent() && hasBoundTexture(fallback.get())) {
                FBXPlayerModelsMod.LOGGER.info("Assimp FBX import produced no bound texture; using internal FBX embedded/material parser result");
                return fallback;
            }
            return assimp;
        }

        try {
            byte[] bytes = Files.readAllBytes(path);
            if (isBinaryFbx(bytes)) {
                return parseBinary(bytes, path);
            }

            return parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            FBXPlayerModelsMod.LOGGER.error("Ran into error while reading FBX File: ", e);
            return Optional.empty();
        }
    }

    public Optional<List<Vertex>> parse(String fbx) {
        List<Vertex> vertices = new ArrayList<>();

        try {
            for (String geometry : findBlocks(fbx, "Geometry:")) {
                if (!geometry.contains("\"Mesh\"")) {
                    continue;
                }

                MeshData mesh = MeshData.from(geometry);
                vertices.addAll(mesh.toVertices());
            }
        } catch (Exception e) {
            return Optional.empty();
        }

        return vertices.isEmpty() ? Optional.empty() : Optional.of(vertices);
    }

    public Optional<SkinnedModel> parseSkinned(Path path) {
        lastRigStatus = "checking";
        Optional<SkinnedModel> assimp = ASSIMP.parseSkinned(path);
        if (assimp.isPresent()) {
            lastRigStatus = AssimpFBXParser.lastStatus();
            lastMaterialStatus = AssimpFBXParser.lastMaterialStatus();
            lastMaterialDiagnostics = AssimpFBXParser.lastMaterialDiagnostics();
            if (hasBoundSkinnedTexture(assimp.get())) {
                return assimp;
            }

            Optional<List<Vertex>> materialFallback = parseBinaryFallback(path);
            if (materialFallback.isPresent() && hasBoundTexture(materialFallback.get())) {
                return Optional.of(recoverMaterials(assimp.get(), materialFallback.get()));
            }
            return assimp;
        }

        return parseSkinnedBinaryFallback(path);
    }

    private Optional<SkinnedModel> parseSkinnedBinaryFallback(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (!isBinaryFbx(bytes)) {
                lastRigStatus = "not binary FBX";
                return Optional.empty();
            }

            BinaryFbxReader reader = new BinaryFbxReader(bytes);
            BinaryNode root = reader.readRoot();
            SceneIndex index = new SceneIndex(root);
            MaterialResolver materials = new MaterialResolver(root, path);

            if (index.nodesOfType("Model", "LimbNode").isEmpty() && index.nodesOfType("Deformer", "Cluster").isEmpty()) {
                lastRigStatus = "no imported skeleton";
                return Optional.empty();
            }
            FallbackSkeleton skeleton = fallbackSkeleton(index);
            List<SkinnedVertex> vertices = new ArrayList<>();
            for (BinaryNode geometry : root.findAll("Geometry")) {
                if (geometry.properties.size() < 3 || !"Mesh".equals(geometry.stringProperty(2))) {
                    continue;
                }
                MeshData mesh = MeshData.from(geometry, materials);
                List<BoneWeights> weights = new ArrayList<>(mesh.positions.size());
                for (int i = 0; i < mesh.positions.size(); i++) weights.add(new BoneWeights());
                long skinId = index.firstConnectedOfType(geometry.longProperty(0), "Deformer", "Skin");
                if (skinId != Long.MIN_VALUE) {
                    for (BinaryNode cluster : index.connectedOfType(skinId, "Deformer", "Cluster")) {
                        Integer boneIndex = skeleton.indices.get(clusterBoneId(index, cluster));
                        if (boneIndex == null) continue;
                        List<Integer> ids = intListProperty(cluster.child("Indexes"));
                        List<Float> values = floatListProperty(cluster.child("Weights"));
                        for (int i = 0; i < ids.size() && i < values.size(); i++) {
                            int id = ids.get(i);
                            if (id >= 0 && id < weights.size()) weights.get(id).add(boneIndex, values.get(i));
                        }
                    }
                }
                List<SkinnedVertex> meshVertices = mesh.toSkinnedVertices(weights);
                transformFallbackMesh(meshVertices.stream().map(vertex -> vertex.vertex).toList(), geometryTransform(index, skeleton, geometry));
                vertices.addAll(meshVertices);
            }
            if (vertices.isEmpty() || skeleton.bones.isEmpty()) {
                lastRigStatus = "no skinned geometry found";
                return Optional.empty();
            }
            SkinnedModel model = new SkinnedModel(skeleton.bones, vertices, LogicalRigAnimator.proceduralAnimations(skeleton.bones, null));
            setRigStatus("internal skinned bones=" + skeleton.bones.size() + " weighted=" + model.weightedVertexCount() + "/" + vertices.size());
            return Optional.of(model);
        } catch (Exception e) {
            lastRigStatus = "error: " + e.getClass().getSimpleName();
            FBXPlayerModelsMod.LOGGER.warn("Failed to parse skinned FBX model", e);
            return Optional.empty();
        }
    }

    private Optional<List<Vertex>> parseBinaryFallback(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (!isBinaryFbx(bytes)) {
                return Optional.empty();
            }
            return parseBinary(bytes, path);
        } catch (Exception e) {
            FBXPlayerModelsMod.LOGGER.warn("Failed to parse internal FBX material fallback", e);
            return Optional.empty();
        }
    }

    private static void setRigStatus(String status) {
        lastRigStatus = status;
        FBXPlayerModelsMod.LOGGER.info("FBX rig: {}", status);
    }

    private record FallbackSkeleton(List<SkinnedModel.Bone> bones, Map<Long, Integer> indices, Map<Long, Matrix4f> globals) {}

    private static long clusterBoneId(SceneIndex index, BinaryNode cluster) {
        for (long id : index.objectChildren.getOrDefault(cluster.longProperty(0), List.of())) {
            BinaryNode node = index.nodesById.get(id);
            if (node != null && "Model".equals(node.name)) return id;
        }
        return Long.MIN_VALUE;
    }

    private static FallbackSkeleton fallbackSkeleton(SceneIndex index) {
        List<BinaryNode> models = index.nodesById.values().stream().filter(node -> "Model".equals(node.name)).toList();
        List<Long> ids = orderedBoneIds(index, models);
        Map<Long, Matrix4f> clusterBinds = new HashMap<>();
        for (BinaryNode cluster : index.nodesOfType("Deformer", "Cluster")) {
            long id = clusterBoneId(index, cluster);
            matrixProperty(cluster.child("TransformLink")).ifPresent(bind -> clusterBinds.putIfAbsent(id, bind));
        }
        List<SkinnedModel.Bone> bones = new ArrayList<>(ids.size() + 1);
        bones.add(new SkinnedModel.Bone(FbxCoordinateSpace.ROOT_NAME, -1, new Matrix4f(index.sceneToModel), new Matrix4f(index.sceneToModel).invert()));
        Map<Long, Integer> indices = new HashMap<>();
        Map<Long, Matrix4f> globals = new HashMap<>();
        for (long id : ids) {
            BinaryNode node = index.nodesById.get(id);
            long parentId = index.parentOf.getOrDefault(id, Long.MIN_VALUE);
            int parentIndex = indices.getOrDefault(parentId, 0);
            Matrix4f local = modelLocalBind(node);
            Matrix4f global = parentIndex > 0 ? new Matrix4f(globals.get(parentId)).mul(local) : new Matrix4f(local);
            if (clusterBinds.containsKey(id)) {
                global = new Matrix4f(clusterBinds.get(id));
                local = parentIndex > 0 ? new Matrix4f(globals.get(parentId)).invert().mul(global) : new Matrix4f(global);
            }
            indices.put(id, bones.size());
            globals.put(id, global);
            bones.add(new SkinnedModel.Bone(sanitizeName(node.stringProperty(1)), parentIndex, local, new Matrix4f(index.sceneToModel).mul(global).invert()));
        }
        return new FallbackSkeleton(bones, indices, globals);
    }

    private static Matrix4f geometryTransform(SceneIndex index, FallbackSkeleton skeleton, BinaryNode geometry) {
        for (long id : index.objectParents.getOrDefault(geometry.longProperty(0), List.of())) {
            BinaryNode node = index.nodesById.get(id);
            if (node != null && "Model".equals(node.name) && skeleton.globals.containsKey(id)) {
                Matrix4f geometric = new Matrix4f().translation(propertyVector(node, "GeometricTranslation", new Vector3f()));
                rotateFbx(geometric, propertyVector(node, "GeometricRotation", new Vector3f()), 0);
                geometric.scale(propertyVector(node, "GeometricScaling", new Vector3f(1f)));
                return new Matrix4f(index.sceneToModel).mul(skeleton.globals.get(id)).mul(geometric);
            }
        }
        return new Matrix4f(index.sceneToModel);
    }

    private static void transformFallbackMesh(List<Vertex> vertices, Matrix4f transform) {
        Matrix3f normalTransform = transform.normal(new Matrix3f());
        for (Vertex vertex : vertices) {
            Vector3f p = transform.transformPosition(new Vector3f(vertex.position.x, vertex.position.y, vertex.position.z));
            Vector3f n = normalTransform.transform(new Vector3f(vertex.normals.x, vertex.normals.y, vertex.normals.z));
            if (n.lengthSquared() > 0f) n.normalize();
            vertex.position = new Float3(p.x, p.y, p.z);
            vertex.normals = new Float3(n.x, n.y, n.z);
        }
    }

    private record MaterialVertexKey(long x, long y, long z, long u, long v) {
        static MaterialVertexKey of(Vertex vertex) {
            return new MaterialVertexKey(Math.round(vertex.position.x * 10000d), Math.round(vertex.position.y * 10000d),
                    Math.round(vertex.position.z * 10000d), Math.round(vertex.textureUV.u * 10000d), Math.round(vertex.textureUV.v * 10000d));
        }
    }

    private static SkinnedModel recoverMaterials(SkinnedModel model, List<Vertex> fallback) {
        Map<MaterialVertexKey, Vertex> appearances = new HashMap<>();
        java.util.Set<MaterialVertexKey> ambiguous = new java.util.HashSet<>();
        for (Vertex vertex : fallback) {
            if (WHITE.equals(vertex.texture)) continue;
            MaterialVertexKey key = MaterialVertexKey.of(vertex);
            Vertex previous = appearances.putIfAbsent(key, vertex);
            if (previous != null && (!previous.texture.equals(vertex.texture) || previous.color != vertex.color)) ambiguous.add(key);
        }
        List<SkinnedVertex> vertices = new ArrayList<>(model.vertices.size());
        for (SkinnedVertex skinned : model.vertices) {
            Vertex original = skinned.vertex;
            MaterialVertexKey key = MaterialVertexKey.of(original);
            Vertex appearance = ambiguous.contains(key) ? null : appearances.get(key);
            if (appearance == null || !WHITE.equals(original.texture)) {
                vertices.add(skinned);
            } else {
                vertices.add(new SkinnedVertex(new Vertex(original.position, original.normals, original.textureUV, appearance.texture, appearance.color), skinned.boneIds, skinned.weights));
            }
        }
        return model.withVertices(vertices);
    }

    private static List<Long> orderedBoneIds(SceneIndex index, List<BinaryNode> boneNodes) {
        List<Long> result = new ArrayList<>();
        Map<Long, BinaryNode> remaining = new LinkedHashMap<>();

        for (BinaryNode boneNode : boneNodes) {
            remaining.put(boneNode.longProperty(0), boneNode);
        }

        for (long boneId : List.copyOf(remaining.keySet())) {
            long parentId = index.parentOf.getOrDefault(boneId, Long.MIN_VALUE);
            if (!remaining.containsKey(parentId)) {
                addBoneAndChildren(index, remaining, boneId, result);
            }
        }

        for (long boneId : List.copyOf(remaining.keySet())) {
            addBoneAndChildren(index, remaining, boneId, result);
        }

        return result;
    }

    private static void addBoneAndChildren(SceneIndex index, Map<Long, BinaryNode> remaining, long boneId, List<Long> result) {
        if (!remaining.containsKey(boneId)) {
            return;
        }

        remaining.remove(boneId);
        result.add(boneId);

        for (long childId : index.objectChildren.getOrDefault(boneId, List.of())) {
            if (remaining.containsKey(childId)) {
                addBoneAndChildren(index, remaining, childId, result);
            }
        }
    }

    private static Matrix4f modelLocalBind(BinaryNode model) {
        Vector3f rotationPivot = propertyVector(model, "RotationPivot", new Vector3f());
        Vector3f scalePivot = propertyVector(model, "ScalingPivot", new Vector3f());
        Matrix4f local = new Matrix4f().translation(propertyVector(model, "Lcl Translation", new Vector3f()))
                .translate(propertyVector(model, "RotationOffset", new Vector3f())).translate(rotationPivot);
        rotateFbx(local, propertyVector(model, "PreRotation", new Vector3f()), 0);
        rotateFbx(local, propertyVector(model, "Lcl Rotation", new Vector3f()), (int) propertyNumber(model, "RotationOrder", 0));
        Matrix4f post = rotateFbx(new Matrix4f(), propertyVector(model, "PostRotation", new Vector3f()), 0).invert();
        local.mul(post).translate(-rotationPivot.x, -rotationPivot.y, -rotationPivot.z)
                .translate(propertyVector(model, "ScalingOffset", new Vector3f())).translate(scalePivot)
                .scale(propertyVector(model, "Lcl Scaling", new Vector3f(1f)))
                .translate(-scalePivot.x, -scalePivot.y, -scalePivot.z);
        return local;
    }

    private static Matrix4f rotateFbx(Matrix4f matrix, Vector3f degrees, int order) {
        float x = (float) Math.toRadians(degrees.x), y = (float) Math.toRadians(degrees.y), z = (float) Math.toRadians(degrees.z);
        return switch (order) {
            case 1 -> matrix.rotateX(x).rotateZ(z).rotateY(y);
            case 2 -> matrix.rotateY(y).rotateZ(z).rotateX(x);
            case 3 -> matrix.rotateY(y).rotateX(x).rotateZ(z);
            case 4 -> matrix.rotateZ(z).rotateX(x).rotateY(y);
            case 5 -> matrix.rotateZ(z).rotateY(y).rotateX(x);
            default -> matrix.rotateXYZ(x, y, z);
        };
    }

    private static float propertyNumber(BinaryNode model, String name, float fallback) {
        BinaryNode properties = model == null ? null : model.child("Properties70");
        if (properties != null) {
            for (BinaryNode property : properties.children) {
                if ("P".equals(property.name) && name.equals(property.stringProperty(0))) return floatProperty(property, 4, fallback);
            }
        }
        return fallback;
    }

    private static Vector3f propertyVector(BinaryNode model, String propertyName, Vector3f fallback) {
        if (model == null) {
            return new Vector3f(fallback);
        }

        BinaryNode properties = model.child("Properties70");
        if (properties == null) {
            return new Vector3f(fallback);
        }

        for (BinaryNode property : properties.children) {
            if (!"P".equals(property.name) || property.properties.size() < 7 || !propertyName.equals(property.stringProperty(0))) {
                continue;
            }

            return new Vector3f(
                    floatProperty(property, 4, fallback.x),
                    floatProperty(property, 5, fallback.y),
                    floatProperty(property, 6, fallback.z)
            );
        }

        return new Vector3f(fallback);
    }

    private Optional<List<Vertex>> parseBinary(byte[] bytes, Path sourcePath) {
        try {
            BinaryFbxReader reader = new BinaryFbxReader(bytes);
            BinaryNode root = reader.readRoot();
            MaterialResolver materials = new MaterialResolver(root, sourcePath);
            SceneIndex index = new SceneIndex(root);
            FallbackSkeleton skeleton = fallbackSkeleton(index);
            List<Vertex> vertices = new ArrayList<>();

            for (BinaryNode geometry : root.findAll("Geometry")) {
                if (geometry.properties.size() < 3 || !"Mesh".equals(geometry.stringProperty(2))) {
                    continue;
                }

                MeshData mesh = MeshData.from(geometry, materials);
                List<Vertex> meshVertices = mesh.toVertices();
                transformFallbackMesh(meshVertices, geometryTransform(index, skeleton, geometry));
                vertices.addAll(meshVertices);
            }

            return vertices.isEmpty() ? Optional.empty() : Optional.of(vertices);
        } catch (Exception e) {
            FBXPlayerModelsMod.LOGGER.error("Ran into error while parsing binary FBX File: ", e);
            return Optional.empty();
        }
    }

    private static boolean isBinaryFbx(byte[] bytes) {
        byte[] marker = "Kaydara FBX Binary".getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < marker.length) {
            return false;
        }

        for (int i = 0; i < marker.length; i++) {
            if (bytes[i] != marker[i]) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasBoundTexture(List<Vertex> vertices) {
        for (Vertex vertex : vertices) {
            if (!WHITE.equals(vertex.texture)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBoundSkinnedTexture(SkinnedModel model) {
        for (SkinnedVertex vertex : model.vertices) {
            if (!WHITE.equals(vertex.vertex.texture)) {
                return true;
            }
        }
        return false;
    }

    private static final class MeshData {
        final List<Float3> positions;
        final List<Integer> polygonIndices;
        final LayerData<Float3> normals;
        final LayerData<Float2> uvs;
        final MaterialLayer materials;

        MeshData(List<Float3> positions, List<Integer> polygonIndices, LayerData<Float3> normals, LayerData<Float2> uvs, MaterialLayer materials) {
            this.positions = positions;
            this.polygonIndices = polygonIndices;
            this.normals = normals;
            this.uvs = uvs;
            this.materials = materials;
        }

        static MeshData from(String block) {
            return new MeshData(
                    blenderToGame(vec3List(floatArray(block, "Vertices:"))),
                    intArray(block, "PolygonVertexIndex:"),
                    parseNormals(block),
                    parseUvs(block),
                    MaterialLayer.single(Appearance.DEFAULT)
            );
        }

        static MeshData from(BinaryNode node, MaterialResolver resolver) {
            long geometryId = node.longProperty(0);
            return new MeshData(
                    vec3List(floatListProperty(node.child("Vertices"))),
                    intListProperty(node.child("PolygonVertexIndex")),
                    parseNormals(node),
                    parseUvs(node),
                    resolver.materialLayerFor(geometryId, parseMaterialIndices(node))
            );
        }

        List<Vertex> toVertices() {
            List<Vertex> out = new ArrayList<>();
            List<FaceVertex> face = new ArrayList<>();
            int polygonIndex = 0;

            for (int polygonVertexIndex = 0; polygonVertexIndex < polygonIndices.size(); polygonVertexIndex++) {
                int rawIndex = polygonIndices.get(polygonVertexIndex);
                int vertexIndex = rawIndex < 0 ? -rawIndex - 1 : rawIndex;
                face.add(new FaceVertex(vertexIndex, polygonVertexIndex, polygonIndex));

                if (rawIndex < 0) {
                    appendFace(face, out);
                    face.clear();
                    polygonIndex++;
                }
            }

            return out;
        }

        List<SkinnedVertex> toSkinnedVertices(List<BoneWeights> weightsByControlPoint) {
            List<SkinnedVertex> out = new ArrayList<>();
            List<FaceVertex> face = new ArrayList<>();
            int polygonIndex = 0;

            for (int polygonVertexIndex = 0; polygonVertexIndex < polygonIndices.size(); polygonVertexIndex++) {
                int rawIndex = polygonIndices.get(polygonVertexIndex);
                int vertexIndex = rawIndex < 0 ? -rawIndex - 1 : rawIndex;
                face.add(new FaceVertex(vertexIndex, polygonVertexIndex, polygonIndex));

                if (rawIndex < 0) {
                    appendSkinnedFace(face, weightsByControlPoint, out);
                    face.clear();
                    polygonIndex++;
                }
            }

            return out;
        }

        private void appendSkinnedFace(List<FaceVertex> face, List<BoneWeights> weightsByControlPoint, List<SkinnedVertex> out) {
            if (face.size() < 3) {
                return;
            }

            for (int i = 1; i + 1 < face.size(); i++) {
                addSkinnedTriangle(face.get(0), face.get(i), face.get(i + 1), weightsByControlPoint, out);
            }
        }

        private void addSkinnedTriangle(FaceVertex a, FaceVertex b, FaceVertex c, List<BoneWeights> weightsByControlPoint, List<SkinnedVertex> out) {
            Float3 faceNormal = computeNormal(position(a), position(b), position(c));

            SkinnedVertex v1 = makeSkinnedVertex(a, faceNormal, weightsByControlPoint);
            SkinnedVertex v2 = makeSkinnedVertex(b, faceNormal, weightsByControlPoint);
            SkinnedVertex v3 = makeSkinnedVertex(c, faceNormal, weightsByControlPoint);

            out.add(v1);
            out.add(v2);
            out.add(v3);
            out.add(new SkinnedVertex(copyVertex(v3.vertex), v3.boneIds.clone(), v3.weights.clone()));
        }

        private SkinnedVertex makeSkinnedVertex(FaceVertex faceVertex, Float3 fallbackNormal, List<BoneWeights> weightsByControlPoint) {
            Vertex vertex = makeVertex(faceVertex, fallbackNormal);
            BoneWeights weights = faceVertex.vertexIndex() >= 0 && faceVertex.vertexIndex() < weightsByControlPoint.size()
                    ? weightsByControlPoint.get(faceVertex.vertexIndex())
                    : BoneWeights.EMPTY;
            return new SkinnedVertex(vertex, weights.boneIds(), weights.weights());
        }

        private void appendFace(List<FaceVertex> face, List<Vertex> out) {
            if (face.size() < 3) {
                return;
            }

            for (int i = 1; i + 1 < face.size(); i++) {
                addTriangle(face.get(0), face.get(i), face.get(i + 1), out);
            }
        }

        private void addTriangle(FaceVertex a, FaceVertex b, FaceVertex c, List<Vertex> out) {
            Float3 faceNormal = computeNormal(position(a), position(b), position(c));

            Vertex v1 = makeVertex(a, faceNormal);
            Vertex v2 = makeVertex(b, faceNormal);
            Vertex v3 = makeVertex(c, faceNormal);

            out.add(v1);
            out.add(v2);
            out.add(v3);
            out.add(copyVertex(v3));
        }

        private Vertex makeVertex(FaceVertex faceVertex, Float3 fallbackNormal) {
            Appearance appearance = materials.appearanceFor(faceVertex.polygonIndex());
            return new Vertex(
                    position(faceVertex),
                    normals.valueFor(faceVertex, fallbackNormal),
                    uvs.valueFor(faceVertex, Float2.empty()),
                    appearance.texture,
                    appearance.color
            );
        }

        private Float3 position(FaceVertex faceVertex) {
            if (faceVertex.vertexIndex() < 0 || faceVertex.vertexIndex() >= positions.size()) {
                return Float3.empty();
            }

            return positions.get(faceVertex.vertexIndex());
        }
    }

    private record FaceVertex(int vertexIndex, int polygonVertexIndex, int polygonIndex) {}

    private record Appearance(Identifier texture, int color) {
        static final Appearance DEFAULT = new Appearance(WHITE, 0xFFFFFFFF);
    }

    private record MaterialLayer(List<Appearance> slots, List<Integer> polygonMaterialIndices, boolean allSame) {
        static MaterialLayer single(Appearance appearance) {
            return new MaterialLayer(List.of(appearance), List.of(), true);
        }

        Appearance appearanceFor(int polygonIndex) {
            if (slots.isEmpty()) {
                return Appearance.DEFAULT;
            }
            if (allSame || polygonMaterialIndices.isEmpty()) {
                return slots.getFirst();
            }

            int slot = polygonIndex >= 0 && polygonIndex < polygonMaterialIndices.size() ? polygonMaterialIndices.get(polygonIndex) : 0;
            if (slot < 0 || slot >= slots.size()) {
                return slots.getFirst();
            }
            return slots.get(slot);
        }
    }

    private static MaterialIndexData parseMaterialIndices(BinaryNode geometryNode) {
        BinaryNode block = geometryNode.child("LayerElementMaterial");
        if (block == null) {
            return MaterialIndexData.EMPTY;
        }

        return new MaterialIndexData(
                intListProperty(block.child("Materials")),
                stringProperty(block.child("MappingInformationType"), "AllSame"),
                stringProperty(block.child("ReferenceInformationType"), "IndexToDirect")
        );
    }

    private record MaterialIndexData(List<Integer> indices, String mapping, String reference) {
        static final MaterialIndexData EMPTY = new MaterialIndexData(List.of(), "AllSame", "IndexToDirect");

        boolean isByPolygon() {
            return "ByPolygon".equals(mapping);
        }
    }

    private static final class BoneWeights {
        static final BoneWeights EMPTY = new BoneWeights();

        final List<Integer> boneIds = new ArrayList<>();
        final List<Float> weights = new ArrayList<>();

        void add(int boneId, float weight) {
            if (weight <= 0f) {
                return;
            }

            boneIds.add(boneId);
            weights.add(weight);
        }

        int[] boneIds() {
            int count = boneIds.size();
            int[] result = new int[count];
            for (int i = 0; i < count; i++) {
                result[i] = boneIds.get(i);
            }
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
        private final Path sourcePath;
        private final String modelKey;
        private final Map<Long, BinaryNode> nodesById = new LinkedHashMap<>();
        private final Map<Long, Long> geometryToModel = new HashMap<>();
        private final Map<Long, List<Long>> geometryToMaterials = new HashMap<>();
        private final Map<Long, List<Long>> modelToMaterials = new HashMap<>();
        private final Map<Long, FbxMaterialInfo> materials = new LinkedHashMap<>();
        private final Map<Long, FbxTextureInfo> textures = new LinkedHashMap<>();
        private final Map<Long, FbxEmbeddedMedia> embeddedMedia = new LinkedHashMap<>();
        private final Map<Long, Identifier> textureCache = new HashMap<>();
        private final Map<Long, Identifier> mediaCache = new HashMap<>();
        private final List<String> warnings = new ArrayList<>();

        MaterialResolver(BinaryNode root, Path sourcePath) {
            this.sourcePath = sourcePath;
            this.modelKey = Integer.toHexString(sourcePath.toAbsolutePath().normalize().toString().hashCode());

            for (BinaryNode node : root.allNodes()) {
                long id = node.longProperty(0);
                if (id != Long.MIN_VALUE) {
                    nodesById.put(id, node);
                    if (node.name.equals("Material")) {
                        materials.put(id, readMaterial(node));
                    } else if (node.name.equals("Texture")) {
                        textures.put(id, readTexture(node));
                    } else if (isMediaNode(node)) {
                        embeddedMedia.put(id, readEmbeddedMedia(node));
                    }
                }
            }

            for (BinaryNode connection : root.findAll("C")) {
                if (connection.properties.size() < 3) {
                    continue;
                }

                String relation = connection.stringProperty(0);
                long child = connection.longProperty(1);
                long parent = connection.longProperty(2);
                String property = connection.properties.size() >= 4 ? connection.stringProperty(3) : "";

                if ("OO".equals(relation)) {
                    BinaryNode childNode = nodesById.get(child);
                    BinaryNode parentNode = nodesById.get(parent);

                    if (isNodeType(childNode, "Geometry") && isNodeType(parentNode, "Model")) {
                        geometryToModel.put(child, parent);
                    } else if (isNodeType(childNode, "Material") && isNodeType(parentNode, "Geometry")) {
                        geometryToMaterials.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(child);
                    } else if (isNodeType(childNode, "Material") && isNodeType(parentNode, "Model")) {
                        modelToMaterials.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(child);
                    } else if (isMediaNode(childNode) && isNodeType(parentNode, "Texture")) {
                        connectEmbeddedMediaToTexture(parent, child);
                    } else if (isMediaNode(childNode) && isNodeType(parentNode, "Material")) {
                        connectEmbeddedMediaToMaterial(parent, child, property);
                    } else if (isNodeType(childNode, "Texture") && isNodeType(parentNode, "Material")) {
                        connectTexture(parent, child, property);
                    }
                } else if ("OP".equals(relation) && materialTextureProperty(property)) {
                    BinaryNode childNode = nodesById.get(child);
                    BinaryNode parentNode = nodesById.get(parent);

                    if (isNodeType(childNode, "Texture") && isNodeType(parentNode, "Material")) {
                        connectTexture(parent, child, property);
                    } else if (isMediaNode(childNode) && isNodeType(parentNode, "Texture")) {
                        connectEmbeddedMediaToTexture(parent, child);
                    } else if (isMediaNode(childNode) && isNodeType(parentNode, "Material")) {
                        connectEmbeddedMediaToMaterial(parent, child, property);
                    }
                }
            }

            matchUnconnectedEmbeddedMedia();
            logImportSummary();
        }

        MaterialLayer materialLayerFor(long geometryId, MaterialIndexData indexData) {
            long modelId = geometryToModel.getOrDefault(geometryId, Long.MIN_VALUE);
            List<Long> ids = geometryToMaterials.getOrDefault(geometryId, modelToMaterials.getOrDefault(modelId, List.of()));

            if (ids.isEmpty()) {
                logFallback("geometry " + geometryId + " has no connected material");
                return MaterialLayer.single(Appearance.DEFAULT);
            }

            List<Appearance> appearances = new ArrayList<>();
            for (long materialId : ids) {
                appearances.add(appearanceFor(materialId));
            }

            boolean allSame = !indexData.isByPolygon() || appearances.size() <= 1;
            if (indexData.isByPolygon() && appearances.size() > 1) {
                FBXPlayerModelsMod.LOGGER.info("FBX mesh {} uses {} material slots with per-polygon assignments", geometryId, appearances.size());
            } else if (appearances.size() > 1) {
                FBXPlayerModelsMod.LOGGER.warn("FBX mesh {} has {} material slots but no per-polygon assignments; using the first slot", geometryId, appearances.size());
            }
            FBXPlayerModelsMod.LOGGER.info("FBX mesh {} -> material ids {}", geometryId, ids);

            return new MaterialLayer(appearances, indexData.indices(), allSame);
        }

        private Appearance appearanceFor(long materialId) {
            FbxMaterialInfo material = materials.get(materialId);
            if (material == null) {
                return Appearance.DEFAULT;
            }

            Identifier texture = textureFor(material);
            if (texture == null) {
                logFallback("material " + material.name + " uses diffuse/base color");
            }
            return new Appearance(texture != null ? texture : WHITE, material.argbColor());
        }

        private FbxMaterialInfo readMaterial(BinaryNode material) {
            FbxMaterialInfo info = new FbxMaterialInfo(material.longProperty(0), sanitizeName(material.stringProperty(1)));
            BinaryNode properties = material.child("Properties70");
            if (properties == null) {
                return info;
            }

            for (BinaryNode property : properties.children) {
                if (!"P".equals(property.name) || property.properties.isEmpty()) {
                    continue;
                }

                String name = property.stringProperty(0);
                String normalized = name.toLowerCase(Locale.ROOT);
                if ((normalized.equals("diffusecolor") || normalized.equals("diffuse") || normalized.contains("basecolor")) && property.properties.size() >= 7) {
                    info.diffuseColor = new float[] {
                            clamp01(floatProperty(property, 4, 1f)),
                            clamp01(floatProperty(property, 5, 1f)),
                            clamp01(floatProperty(property, 6, 1f))
                    };
                } else if ((normalized.contains("transparency") || normalized.equals("opacity") || normalized.equals("alpha")) && property.properties.size() >= 5) {
                    float value = clamp01(floatProperty(property, 4, 1f));
                    info.alpha = normalized.contains("transparency") ? 1f - value : value;
                }
            }

            return info;
        }

        private FbxTextureInfo readTexture(BinaryNode texture) {
            FbxTextureInfo info = new FbxTextureInfo(texture.longProperty(0), sanitizeName(texture.stringProperty(1)));
            info.fileName = firstNonBlank(
                    stringProperty(texture.child("FileName"), ""),
                    stringProperty(texture.child("Filename"), ""),
                    stringProperty(texture.child("TextureName"), ""),
                    stringProperties70Value(texture, "FileName"),
                    stringProperties70Value(texture, "Filename"),
                    stringProperties70Value(texture, "TextureName")
            );
            info.relativeFileName = firstNonBlank(
                    stringProperty(texture.child("RelativeFilename"), ""),
                    stringProperty(texture.child("RelativeFileName"), ""),
                    stringProperties70Value(texture, "RelativeFilename"),
                    stringProperties70Value(texture, "RelativeFileName")
            );
            info.mimeOrExtension = extensionOf(firstNonBlank(info.relativeFileName, info.fileName, info.name));
            return info;
        }

        private FbxEmbeddedMedia readEmbeddedMedia(BinaryNode video) {
            FbxEmbeddedMedia media = new FbxEmbeddedMedia(video.longProperty(0), sanitizeName(video.stringProperty(1)));
            media.originalFileName = firstNonBlank(
                    stringProperty(video.child("Filename"), ""),
                    stringProperty(video.child("FileName"), ""),
                    stringProperty(video.child("RelativeFilename"), ""),
                    stringProperty(video.child("RelativeFileName"), ""),
                    stringProperties70Value(video, "Filename"),
                    stringProperties70Value(video, "FileName"),
                    stringProperties70Value(video, "RelativeFilename"),
                    stringProperties70Value(video, "RelativeFileName"),
                    media.name
            );
            media.extension = extensionOf(media.originalFileName);
            BinaryNode content = video.child("Content");
            media.bytes = firstByteProperty(content);
            return media;
        }

        private void connectTexture(long materialId, long textureId, String property) {
            FbxMaterialInfo material = materials.get(materialId);
            if (material == null) {
                return;
            }
            String channel = property == null || property.isBlank() ? "DiffuseColor" : property;
            material.textureIdsByChannel.put(channel, textureId);
            material.fallbackTextureName = textures.containsKey(textureId) ? textures.get(textureId).displayName() : "";
        }

        private void connectEmbeddedMediaToTexture(long textureId, long mediaId) {
            FbxTextureInfo texture = textures.get(textureId);
            if (texture != null) {
                texture.embeddedMediaId = mediaId;
            }
        }

        private void connectEmbeddedMediaToMaterial(long materialId, long mediaId, String property) {
            FbxMaterialInfo material = materials.get(materialId);
            if (material == null) {
                return;
            }
            String channel = property == null || property.isBlank() ? "DiffuseColor" : property;
            material.mediaIdsByChannel.put(channel, mediaId);
            FbxEmbeddedMedia media = embeddedMedia.get(mediaId);
            material.fallbackTextureName = media == null ? "" : media.originalFileName;
        }

        private void matchUnconnectedEmbeddedMedia() {
            for (FbxTextureInfo texture : textures.values()) {
                if (texture.embeddedMediaId != Long.MIN_VALUE) {
                    continue;
                }

                String textureName = normalizeFileKey(texture.displayName());
                if (textureName.isBlank()) {
                    continue;
                }

                for (FbxEmbeddedMedia media : embeddedMedia.values()) {
                    if (media.bytes == null || media.bytes.length == 0) {
                        continue;
                    }
                    if (textureName.equals(normalizeFileKey(media.originalFileName)) || textureName.equals(normalizeFileKey(media.name))) {
                        texture.embeddedMediaId = media.id;
                        FBXPlayerModelsMod.LOGGER.info("FBX texture {} matched embedded media {}", texture.displayName(), media.originalFileName);
                        break;
                    }
                }
            }
        }

        private Identifier textureFor(FbxMaterialInfo material) {
            long textureId = material.preferredTextureId();
            if (textureId != Long.MIN_VALUE) {
                Identifier id = textureFor(textureId, material);
                if (id != null) {
                    return id;
                }
            }

            long mediaId = material.preferredMediaId();
            if (mediaId != Long.MIN_VALUE) {
                return embeddedMediaFor(mediaId, material, material.fallbackTextureName);
            }

            return null;
        }

        private Identifier textureFor(long textureId, FbxMaterialInfo material) {
            FbxTextureInfo texture = textures.get(textureId);
            if (texture == null) {
                return null;
            }

            Identifier cached = textureCache.get(textureId);
            if (cached != null) {
                return cached;
            }

            Identifier id = tryLoadEmbeddedTexture(texture, material);
            if (id == null) {
                id = tryLoadFileTexture(texture, material);
            }

            if (id != null) {
                textureCache.put(textureId, id);
            }

            return id;
        }

        private Identifier tryLoadEmbeddedTexture(FbxTextureInfo texture, FbxMaterialInfo material) {
            return embeddedMediaFor(texture.embeddedMediaId, material, texture.displayName());
        }

        private Identifier embeddedMediaFor(long mediaId, FbxMaterialInfo material, String textureName) {
            FbxEmbeddedMedia media = embeddedMedia.get(mediaId);
            if (media == null || media.bytes == null || media.bytes.length == 0) {
                return null;
            }

            Identifier cached = mediaCache.get(media.id);
            if (cached != null) {
                material.textureSource = "embedded";
                return cached;
            }

            try {
                Identifier id = DynamicTextureLoader.load(media.bytes, stableTextureId("fbx/embedded", material.name, textureName, media.originalFileName));
                mediaCache.put(media.id, id);
                material.textureSource = "embedded";
                FBXPlayerModelsMod.LOGGER.info("FBX material {} -> embedded texture {}", material.name, media.originalFileName);
                return id;
            } catch (Exception e) {
                String warning = "Failed to decode embedded FBX texture " + media.originalFileName + ": " + e.getClass().getSimpleName();
                warnings.add(warning);
                FBXPlayerModelsMod.LOGGER.warn(warning, e);
                return null;
            }
        }

        private Identifier tryLoadFileTexture(FbxTextureInfo texture, FbxMaterialInfo material) {
            List<String> candidates = List.of(texture.relativeFileName, texture.fileName, texture.name);
            for (String filename : candidates) {
                if (filename.isBlank()) {
                    continue;
                }

                Path imagePath = resolveTexturePath(filename);
                if (imagePath == null) {
                    warnings.add("Missing FBX texture file " + filename + " for material " + material.name);
                    FBXPlayerModelsMod.LOGGER.warn("Missing FBX texture file {} for material {}", filename, material.name);
                    continue;
                }

                try {
                    Identifier id = DynamicTextureLoader.load(imagePath, stableTextureId("fbx/file", material.name, texture.displayName(), imagePath.getFileName().toString()));
                    material.textureSource = Path.of(filename.replace('\\', '/')).isAbsolute() ? "absolute file" : "relative file";
                    FBXPlayerModelsMod.LOGGER.info("FBX material {} -> file texture {}", material.name, imagePath);
                    return id;
                } catch (Exception e) {
                    String warning = "Failed to decode FBX texture " + imagePath + ": " + e.getClass().getSimpleName();
                    warnings.add(warning);
                    FBXPlayerModelsMod.LOGGER.warn(warning, e);
                }
            }

            return null;
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

        private String stableTextureId(String source, String materialName, String textureName, String originalName) {
            return source + "/" + modelKey + "/" + materialName + "/" + textureName + "/" + originalName;
        }

        private void logImportSummary() {
            int embeddedCount = 0;
            for (FbxEmbeddedMedia media : embeddedMedia.values()) {
                if (media.bytes != null && media.bytes.length > 0) {
                    embeddedCount++;
                }
            }
            FBXPlayerModelsMod.LOGGER.info("FBX materials={} textures={} embeddedMedia={}", materials.size(), textures.size(), embeddedCount);
            for (FbxMaterialInfo material : materials.values()) {
                FBXPlayerModelsMod.LOGGER.info("FBX material {} ({}) textureChannels={}", material.id, material.name, material.textureIdsByChannel);
            }
            lastMaterialStatus = "FBX materials=" + materials.size() + ", textures=" + textures.size() + ", embedded=" + embeddedCount;
            List<String> diagnostics = new ArrayList<>();
            diagnostics.add("Material count: " + materials.size());
            diagnostics.add("Texture count: " + textures.size());
            diagnostics.add("Embedded texture count: " + embeddedCount);
            if (!materials.isEmpty()) {
                FbxMaterialInfo first = materials.values().iterator().next();
                diagnostics.add("Selected material: " + first.name);
                diagnostics.add("Selected texture source: " + first.textureSource);
            }
            diagnostics.addAll(warnings);
            lastMaterialDiagnostics = Collections.unmodifiableList(diagnostics);
        }

        private void logFallback(String message) {
            FBXPlayerModelsMod.LOGGER.info("FBX fallback material: {}", message);
        }

        private static boolean isNodeType(BinaryNode node, String type) {
            return node != null && node.name.equals(type);
        }

        private static boolean isMediaNode(BinaryNode node) {
            return node != null && (node.name.equals("Video") || node.name.equals("Media"));
        }

        private static byte[] firstByteProperty(BinaryNode node) {
            if (node == null) {
                return null;
            }

            for (Object property : node.properties) {
                if (property instanceof byte[] bytes && bytes.length > 0) {
                    return bytes;
                }
            }
            return null;
        }

        private static boolean materialTextureProperty(String property) {
            String normalized = property.toLowerCase(Locale.ROOT);
            return normalized.contains("diffuse")
                    || normalized.contains("basecolor")
                    || normalized.contains("base color")
                    || normalized.contains("color");
        }

        private static String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return "";
        }

        private static String stringProperties70Value(BinaryNode node, String propertyName) {
            BinaryNode properties = node.child("Properties70");
            if (properties == null) {
                return "";
            }

            for (BinaryNode property : properties.children) {
                if (!"P".equals(property.name) || property.properties.size() < 5) {
                    continue;
                }
                if (propertyName.equalsIgnoreCase(property.stringProperty(0))) {
                    Object value = property.properties.get(4);
                    return value instanceof String string ? string : "";
                }
            }
            return "";
        }

        private static String normalizeFileKey(String value) {
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

        private static String extensionOf(String value) {
            String normalized = value.replace('\\', '/');
            int slash = normalized.lastIndexOf('/');
            int dot = normalized.lastIndexOf('.');
            if (dot > slash && dot + 1 < normalized.length()) {
                return normalized.substring(dot + 1).toLowerCase(Locale.ROOT);
            }
            return "";
        }
    }

    private static final class FbxMaterialInfo {
        final long id;
        final String name;
        float[] diffuseColor = new float[] {1f, 1f, 1f};
        float alpha = 1f;
        final Map<String, Long> textureIdsByChannel = new LinkedHashMap<>();
        final Map<String, Long> mediaIdsByChannel = new LinkedHashMap<>();
        String fallbackTextureName = "";
        String textureSource = "fallback";

        FbxMaterialInfo(long id, String name) {
            this.id = id;
            this.name = name.isBlank() ? "material_" + id : name;
        }

        int argbColor() {
            return ((int) (clamp01(alpha) * 255) << 24)
                    | ((int) (clamp01(diffuseColor[0]) * 255) << 16)
                    | ((int) (clamp01(diffuseColor[1]) * 255) << 8)
                    | (int) (clamp01(diffuseColor[2]) * 255);
        }

        long preferredTextureId() {
            for (Map.Entry<String, Long> entry : textureIdsByChannel.entrySet()) {
                if (MaterialResolver.materialTextureProperty(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return textureIdsByChannel.isEmpty() ? Long.MIN_VALUE : textureIdsByChannel.values().iterator().next();
        }

        long preferredMediaId() {
            for (Map.Entry<String, Long> entry : mediaIdsByChannel.entrySet()) {
                if (MaterialResolver.materialTextureProperty(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return mediaIdsByChannel.isEmpty() ? Long.MIN_VALUE : mediaIdsByChannel.values().iterator().next();
        }
    }

    private static final class FbxTextureInfo {
        final long id;
        final String name;
        String fileName = "";
        String relativeFileName = "";
        long embeddedMediaId = Long.MIN_VALUE;
        byte[] embeddedBytes;
        String mimeOrExtension = "";

        FbxTextureInfo(long id, String name) {
            this.id = id;
            this.name = name.isBlank() ? "texture_" + id : name;
        }

        String displayName() {
            return MaterialResolver.firstNonBlank(relativeFileName, fileName, name);
        }
    }

    private static final class FbxEmbeddedMedia {
        final long id;
        final String name;
        String originalFileName = "";
        String extension = "";
        byte[] bytes;

        FbxEmbeddedMedia(long id, String name) {
            this.id = id;
            this.name = name.isBlank() ? "media_" + id : name;
        }
    }

    private static final class LayerData<T> {
        static final String BY_POLYGON_VERTEX = "ByPolygonVertex";
        static final String BY_VERTEX = "ByVertice";
        static final String INDEX_TO_DIRECT = "IndexToDirect";

        final List<T> values;
        final List<Integer> indices;
        final String mapping;
        final String reference;

        LayerData(List<T> values, List<Integer> indices, String mapping, String reference) {
            this.values = values;
            this.indices = indices;
            this.mapping = mapping;
            this.reference = reference;
        }

        T valueFor(FaceVertex faceVertex, T fallback) {
            if (values.isEmpty()) {
                return fallback;
            }

            int sourceIndex = BY_POLYGON_VERTEX.equals(mapping) ? faceVertex.polygonVertexIndex() : faceVertex.vertexIndex();
            int valueIndex = sourceIndex;

            if (INDEX_TO_DIRECT.equals(reference) && sourceIndex >= 0 && sourceIndex < indices.size()) {
                valueIndex = indices.get(sourceIndex);
            }

            if (valueIndex < 0 || valueIndex >= values.size()) {
                return fallback;
            }

            return values.get(valueIndex);
        }
    }

    private static LayerData<Float3> parseNormals(String geometryBlock) {
        List<String> blocks = findBlocks(geometryBlock, "LayerElementNormal:");
        if (blocks.isEmpty()) {
            return new LayerData<>(List.of(), List.of(), LayerData.BY_POLYGON_VERTEX, "Direct");
        }

        String block = blocks.getFirst();
        return new LayerData<>(
                blenderToGame(vec3List(floatArray(block, "Normals:"))),
                intArray(block, "NormalsIndex:"),
                quotedValue(block, "MappingInformationType:", LayerData.BY_POLYGON_VERTEX),
                quotedValue(block, "ReferenceInformationType:", "Direct")
        );
    }

    private static LayerData<Float3> parseNormals(BinaryNode geometryNode) {
        BinaryNode block = geometryNode.child("LayerElementNormal");
        if (block == null) {
            return new LayerData<>(List.of(), List.of(), LayerData.BY_POLYGON_VERTEX, "Direct");
        }

        return new LayerData<>(
                vec3List(floatListProperty(block.child("Normals"))),
                intListProperty(block.child("NormalsIndex")),
                stringProperty(block.child("MappingInformationType"), LayerData.BY_POLYGON_VERTEX),
                stringProperty(block.child("ReferenceInformationType"), "Direct")
        );
    }

    private static LayerData<Float2> parseUvs(String geometryBlock) {
        List<String> blocks = findBlocks(geometryBlock, "LayerElementUV:");
        if (blocks.isEmpty()) {
            return new LayerData<>(List.of(), List.of(), LayerData.BY_POLYGON_VERTEX, "Direct");
        }

        String block = blocks.getFirst();
        List<Integer> indices = intArray(block, "UVIndex:");
        String reference = quotedValue(block, "ReferenceInformationType:", indices.isEmpty() ? "Direct" : LayerData.INDEX_TO_DIRECT);

        return new LayerData<>(
                vec2List(floatArray(block, "UV:")),
                indices,
                quotedValue(block, "MappingInformationType:", LayerData.BY_POLYGON_VERTEX),
                reference
        );
    }

    private static LayerData<Float2> parseUvs(BinaryNode geometryNode) {
        BinaryNode block = geometryNode.child("LayerElementUV");
        if (block == null) {
            return new LayerData<>(List.of(), List.of(), LayerData.BY_POLYGON_VERTEX, "Direct");
        }

        List<Integer> indices = intListProperty(block.child("UVIndex"));
        String reference = stringProperty(block.child("ReferenceInformationType"), indices.isEmpty() ? "Direct" : LayerData.INDEX_TO_DIRECT);

        return new LayerData<>(
                vec2List(floatListProperty(block.child("UV"))),
                indices,
                stringProperty(block.child("MappingInformationType"), LayerData.BY_POLYGON_VERTEX),
                reference
        );
    }

    private static List<String> findBlocks(String source, String marker) {
        List<String> blocks = new ArrayList<>();
        int searchFrom = 0;

        while (searchFrom < source.length()) {
            int markerIndex = source.indexOf(marker, searchFrom);
            if (markerIndex < 0) {
                break;
            }

            int openBrace = source.indexOf('{', markerIndex);
            if (openBrace < 0) {
                break;
            }

            int closeBrace = matchingBrace(source, openBrace);
            if (closeBrace < 0) {
                break;
            }

            blocks.add(source.substring(markerIndex, closeBrace + 1));
            searchFrom = closeBrace + 1;
        }

        return blocks;
    }

    private static int matchingBrace(String source, int openBrace) {
        int depth = 0;

        for (int i = openBrace; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }

        return -1;
    }

    private static List<Float> floatArray(String source, String marker) {
        String arrayText = arrayPayload(source, marker);
        return NUMBER_PATTERN.matcher(arrayText).results()
                .map(match -> Float.parseFloat(match.group()))
                .toList();
    }

    private static List<Integer> intArray(String source, String marker) {
        String arrayText = arrayPayload(source, marker);
        return NUMBER_PATTERN.matcher(arrayText).results()
                .map(match -> Integer.parseInt(match.group().split("[.eE]", 2)[0]))
                .toList();
    }

    private static String arrayPayload(String source, String marker) {
        int markerIndex = propertyIndex(source, marker);
        if (markerIndex < 0) {
            return "";
        }

        int openBrace = source.indexOf('{', markerIndex);
        if (openBrace >= 0) {
            int closeBrace = matchingBrace(source, openBrace);
            if (closeBrace > openBrace) {
                int payloadStart = source.indexOf("a:", openBrace);
                if (payloadStart >= 0 && payloadStart < closeBrace) {
                    return source.substring(payloadStart + 2, closeBrace);
                }

                return source.substring(openBrace + 1, closeBrace);
            }
        }

        int lineEnd = source.indexOf('\n', markerIndex);
        return source.substring(markerIndex + marker.length(), lineEnd >= 0 ? lineEnd : source.length());
    }

    private static String quotedValue(String source, String marker, String fallback) {
        int markerIndex = propertyIndex(source, marker);
        if (markerIndex < 0) {
            return fallback;
        }

        int firstQuote = source.indexOf('"', markerIndex);
        if (firstQuote < 0) {
            return fallback;
        }

        int secondQuote = source.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return fallback;
        }

        return source.substring(firstQuote + 1, secondQuote);
    }

    private static int propertyIndex(String source, String marker) {
        int searchFrom = 0;

        while (searchFrom < source.length()) {
            int markerIndex = source.indexOf(marker, searchFrom);
            if (markerIndex < 0) {
                return -1;
            }

            if (markerIndex == 0 || !Character.isLetterOrDigit(source.charAt(markerIndex - 1))) {
                return markerIndex;
            }

            searchFrom = markerIndex + marker.length();
        }

        return -1;
    }

    private static List<Float3> vec3List(List<Float> floats) {
        List<Float3> result = new ArrayList<>();
        for (int i = 0; i + 2 < floats.size(); i += 3) {
            result.add(new Float3(floats.get(i), floats.get(i + 1), floats.get(i + 2)));
        }
        return result;
    }

    private static List<Float2> vec2List(List<Float> floats) {
        List<Float2> result = new ArrayList<>();
        for (int i = 0; i + 1 < floats.size(); i += 2) {
            result.add(new Float2(floats.get(i), 1f - floats.get(i + 1)));
        }
        return result;
    }

    private static List<Float3> blenderToGame(List<Float3> vectors) {
        List<Float3> result = new ArrayList<>(vectors.size());
        for (Float3 vector : vectors) {
            result.add(blenderToGame(vector));
        }
        return result;
    }

    private static Float3 blenderToGame(Float3 vector) {
        return new Float3(vector.x, vector.z, -vector.y);
    }

    private static List<Float> floatListProperty(BinaryNode node) {
        if (node == null || node.properties.isEmpty()) {
            return List.of();
        }

        Object value = node.properties.getFirst();

        if (value instanceof float[] floats) {
            List<Float> result = new ArrayList<>(floats.length);
            for (float item : floats) {
                result.add(item);
            }
            return result;
        }

        if (value instanceof double[] doubles) {
            List<Float> result = new ArrayList<>(doubles.length);
            for (double item : doubles) {
                result.add((float) item);
            }
            return result;
        }

        return List.of();
    }

    private static List<Integer> intListProperty(BinaryNode node) {
        if (node == null || node.properties.isEmpty()) {
            return List.of();
        }

        Object value = node.properties.getFirst();

        if (value instanceof int[] ints) {
            List<Integer> result = new ArrayList<>(ints.length);
            for (int item : ints) {
                result.add(item);
            }
            return result;
        }

        if (value instanceof long[] longs) {
            List<Integer> result = new ArrayList<>(longs.length);
            for (long item : longs) {
                result.add((int) item);
            }
            return result;
        }

        return List.of();
    }

    private static String stringProperty(BinaryNode node, String fallback) {
        if (node == null || node.properties.isEmpty()) {
            return fallback;
        }

        Object value = node.properties.getFirst();
        return value instanceof String string ? string : fallback;
    }

    private static float floatProperty(BinaryNode node, int index, float fallback) {
        if (node == null || index < 0 || index >= node.properties.size()) {
            return fallback;
        }

        Object value = node.properties.get(index);
        if (value instanceof Number number) {
            return number.floatValue();
        }

        return fallback;
    }

    private static Optional<Matrix4f> matrixProperty(BinaryNode node) {
        if (node == null || node.properties.isEmpty()) {
            return Optional.empty();
        }

        Object value = node.properties.getFirst();
        double[] matrix = null;

        if (value instanceof double[] doubles && doubles.length >= 16) {
            matrix = doubles;
        } else if (value instanceof float[] floats && floats.length >= 16) {
            matrix = new double[16];
            for (int i = 0; i < 16; i++) {
                matrix[i] = floats[i];
            }
        }

        if (matrix == null) {
            return Optional.empty();
        }

        Matrix4f result = new Matrix4f();
        result.m00((float) matrix[0]);
        result.m01((float) matrix[1]);
        result.m02((float) matrix[2]);
        result.m03((float) matrix[3]);
        result.m10((float) matrix[4]);
        result.m11((float) matrix[5]);
        result.m12((float) matrix[6]);
        result.m13((float) matrix[7]);
        result.m20((float) matrix[8]);
        result.m21((float) matrix[9]);
        result.m22((float) matrix[10]);
        result.m23((float) matrix[11]);
        result.m30((float) matrix[12]);
        result.m31((float) matrix[13]);
        result.m32((float) matrix[14]);
        result.m33((float) matrix[15]);
        return Optional.of(result);
    }

    private static String sanitizeName(String name) {
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

    private static float clamp01(float value) {
        return value < 0f ? 0f : Math.min(value, 1f);
    }

    private static Float3 computeNormal(Float3 a, Float3 b, Float3 c) {
        Float3 u = new Float3(b.x - a.x, b.y - a.y, b.z - a.z);
        Float3 v = new Float3(c.x - a.x, c.y - a.y, c.z - a.z);

        float x = u.y * v.z - u.z * v.y;
        float y = u.z * v.x - u.x * v.z;
        float z = u.x * v.y - u.y * v.x;
        float length = (float) Math.sqrt(x * x + y * y + z * z);

        if (length == 0) {
            return Float3.empty();
        }

        return new Float3(x / length, y / length, z / length);
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

    private static final class BinaryNode {
        final String name;
        final List<Object> properties = new ArrayList<>();
        final List<BinaryNode> children = new ArrayList<>();

        BinaryNode(String name) {
            this.name = name;
        }

        BinaryNode child(String childName) {
            for (BinaryNode child : children) {
                if (child.name.equals(childName)) {
                    return child;
                }
            }

            return null;
        }

        String stringProperty(int index) {
            if (index < 0 || index >= properties.size()) {
                return "";
            }

            Object value = properties.get(index);
            return value instanceof String string ? string : "";
        }

        long longProperty(int index) {
            if (index < 0 || index >= properties.size()) {
                return Long.MIN_VALUE;
            }

            Object value = properties.get(index);
            if (value instanceof Number number) {
                return number.longValue();
            }

            return Long.MIN_VALUE;
        }

        List<BinaryNode> findAll(String nodeName) {
            List<BinaryNode> result = new ArrayList<>();
            collect(nodeName, result);
            return result;
        }

        List<BinaryNode> allNodes() {
            List<BinaryNode> result = new ArrayList<>();
            collectAll(result);
            return result;
        }

        private void collect(String nodeName, List<BinaryNode> result) {
            if (name.equals(nodeName)) {
                result.add(this);
            }

            for (BinaryNode child : children) {
                child.collect(nodeName, result);
            }
        }

        private void collectAll(List<BinaryNode> result) {
            result.add(this);

            for (BinaryNode child : children) {
                child.collectAll(result);
            }
        }
    }

    private static final class SceneIndex {
        final Matrix4f sceneToModel;
        final Map<Long, BinaryNode> nodesById = new LinkedHashMap<>();
        final Map<Long, List<Long>> objectChildren = new HashMap<>();
        final Map<Long, List<Long>> objectParents = new HashMap<>();
        final Map<Long, Long> parentOf = new HashMap<>();

        SceneIndex(BinaryNode root) {
            BinaryNode settings = root.child("GlobalSettings");
            sceneToModel = FbxCoordinateSpace.fromUpAxis((int) propertyNumber(settings, "UpAxis", 1), (int) propertyNumber(settings, "UpAxisSign", 1));
            for (BinaryNode node : root.allNodes()) {
                long id = node.longProperty(0);
                if (id != Long.MIN_VALUE) {
                    nodesById.put(id, node);
                }
            }

            for (BinaryNode connection : root.findAll("C")) {
                if (connection.properties.size() < 3 || !"OO".equals(connection.stringProperty(0))) {
                    continue;
                }

                long child = connection.longProperty(1);
                long parent = connection.longProperty(2);
                objectChildren.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(child);
                objectParents.computeIfAbsent(child, ignored -> new ArrayList<>()).add(parent);
                BinaryNode childNode = nodesById.get(child), parentNode = nodesById.get(parent);
                if (childNode != null && parentNode != null && "Model".equals(childNode.name) && "Model".equals(parentNode.name)) {
                    parentOf.put(child, parent);
                }
            }
        }

        long firstConnectedOfType(long id, String nodeName, String type) {
            for (BinaryNode node : connectedOfType(id, nodeName, type)) {
                return node.longProperty(0);
            }

            return Long.MIN_VALUE;
        }

        long firstNodeOfType(String nodeName, String type) {
            for (BinaryNode node : nodesOfType(nodeName, type)) {
                return node.longProperty(0);
            }

            return Long.MIN_VALUE;
        }

        List<BinaryNode> nodesOfType(String nodeName, String type) {
            List<BinaryNode> result = new ArrayList<>();
            for (BinaryNode node : nodesById.values()) {
                if (node.name.equals(nodeName) && node.properties.size() >= 3 && type.equals(node.stringProperty(2))) {
                    result.add(node);
                }
            }

            return result;
        }

        List<BinaryNode> connectedOfType(long id, String nodeName, String type) {
            List<BinaryNode> result = new ArrayList<>();
            addMatching(result, objectChildren.getOrDefault(id, List.of()), nodeName, type);
            addMatching(result, objectParents.getOrDefault(id, List.of()), nodeName, type);

            return result;
        }

        private void addMatching(List<BinaryNode> result, List<Long> ids, String nodeName, String type) {
            for (long nodeId : ids) {
                BinaryNode node = nodesById.get(nodeId);
                if (node != null && node.name.equals(nodeName) && node.properties.size() >= 3 && type.equals(node.stringProperty(2))) {
                    result.add(node);
                }
            }
        }
    }

    private static final class BinaryFbxReader {
        private static final int HEADER_LENGTH = 27;

        private final ByteBuffer buffer;
        private final boolean largeOffsets;

        BinaryFbxReader(byte[] bytes) {
            buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            buffer.position(23);
            int version = buffer.getInt();
            largeOffsets = version >= 7500;
        }

        BinaryNode readRoot() {
            BinaryNode root = new BinaryNode("");
            buffer.position(HEADER_LENGTH);

            while (buffer.remaining() > nullRecordLength()) {
                BinaryNode node = readNode(buffer.limit());
                if (node == null) {
                    break;
                }
                root.children.add(node);
            }

            return root;
        }

        private BinaryNode readNode(long parentEndOffset) {
            if (buffer.position() + nullRecordLength() > buffer.limit()) {
                return null;
            }

            long endOffset = readOffset();
            long propertyCount = readOffset();
            readOffset(); // property list byte length; properties are self-delimiting here.
            int nameLength = Byte.toUnsignedInt(buffer.get());

            if (endOffset == 0 && propertyCount == 0 && nameLength == 0) {
                return null;
            }

            if (endOffset > parentEndOffset || endOffset > buffer.limit() || nameLength > buffer.remaining()) {
                throw new IllegalStateException("Invalid FBX node header");
            }

            byte[] nameBytes = new byte[nameLength];
            buffer.get(nameBytes);

            BinaryNode node = new BinaryNode(new String(nameBytes, StandardCharsets.UTF_8));
            for (long i = 0; i < propertyCount; i++) {
                node.properties.add(readProperty());
            }

            while (buffer.position() < endOffset) {
                int before = buffer.position();
                BinaryNode child = readNode(endOffset);
                if (child == null) {
                    break;
                }
                node.children.add(child);

                if (buffer.position() <= before) {
                    throw new IllegalStateException("FBX parser did not advance");
                }
            }

            buffer.position((int) endOffset);
            return node;
        }

        private Object readProperty() {
            char type = (char) Byte.toUnsignedInt(buffer.get());

            return switch (type) {
                case 'Y' -> buffer.getShort();
                case 'C' -> buffer.get() != 0;
                case 'I' -> buffer.getInt();
                case 'F' -> buffer.getFloat();
                case 'D' -> buffer.getDouble();
                case 'L' -> buffer.getLong();
                case 'R' -> readBytes(buffer.getInt());
                case 'S' -> new String(readBytes(buffer.getInt()), StandardCharsets.UTF_8);
                case 'f' -> readFloatArray();
                case 'd' -> readDoubleArray();
                case 'i' -> readIntArray();
                case 'l' -> readLongArray();
                case 'b', 'c' -> readByteArray();
                default -> throw new IllegalStateException("Unsupported FBX property type: " + type);
            };
        }

        private float[] readFloatArray() {
            ByteBuffer array = readArrayPayload(Float.BYTES);
            float[] result = new float[array.remaining() / Float.BYTES];
            for (int i = 0; i < result.length; i++) {
                result[i] = array.getFloat();
            }
            return result;
        }

        private double[] readDoubleArray() {
            ByteBuffer array = readArrayPayload(Double.BYTES);
            double[] result = new double[array.remaining() / Double.BYTES];
            for (int i = 0; i < result.length; i++) {
                result[i] = array.getDouble();
            }
            return result;
        }

        private int[] readIntArray() {
            ByteBuffer array = readArrayPayload(Integer.BYTES);
            int[] result = new int[array.remaining() / Integer.BYTES];
            for (int i = 0; i < result.length; i++) {
                result[i] = array.getInt();
            }
            return result;
        }

        private long[] readLongArray() {
            ByteBuffer array = readArrayPayload(Long.BYTES);
            long[] result = new long[array.remaining() / Long.BYTES];
            for (int i = 0; i < result.length; i++) {
                result[i] = array.getLong();
            }
            return result;
        }

        private byte[] readByteArray() {
            return readArrayPayload(1).array();
        }

        private ByteBuffer readArrayPayload(int elementSize) {
            int length = buffer.getInt();
            int encoding = buffer.getInt();
            int byteLength = buffer.getInt();
            byte[] payload = readBytes(byteLength);

            if (encoding == 0) {
                return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            }

            if (encoding != 1) {
                throw new IllegalStateException("Unsupported FBX array encoding: " + encoding);
            }

            byte[] inflated = inflate(payload, length * elementSize);
            return ByteBuffer.wrap(inflated).order(ByteOrder.LITTLE_ENDIAN);
        }

        private byte[] readBytes(int length) {
            byte[] bytes = new byte[length];
            buffer.get(bytes);
            return bytes;
        }

        private long readOffset() {
            return largeOffsets ? buffer.getLong() : Integer.toUnsignedLong(buffer.getInt());
        }

        private int nullRecordLength() {
            return largeOffsets ? 25 : 13;
        }

        private static byte[] inflate(byte[] compressed, int expectedLength) {
            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            byte[] output = new byte[expectedLength];

            try {
                int written = inflater.inflate(output);
                if (written == output.length) {
                    return output;
                }

                return Arrays.copyOf(output, written);
            } catch (DataFormatException e) {
                throw new IllegalStateException("Invalid compressed FBX array", e);
            } finally {
                inflater.end();
            }
        }
    }
}
