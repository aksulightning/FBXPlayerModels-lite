# FBX Player Models Lite Code Guide

## Lite architecture

FBX Player Models Lite is a Fabric client-only mod. It loads one locally selected FBX model and uses it only when rendering the active local player.

The client-only boundary is enforced in several layers:

- All three `fabric.mod.json` files declare `"environment": "client"`.
- Modern targets use the `me.onethecrazy.FBXPlayerModelsClient` client initializer. Beta initializes it through `me.onethecrazy.b173.ClientInitListener` on StationAPI's client event bus. There is no `main` entrypoint.
- `SkinManager` owns one `selfSkin` cache rather than a UUID-indexed player cache.
- Each player-render mixin checks that the rendered player is the current Minecraft client player before using the custom model.
- The first-person renderer accepts only the local-player type and reads the same local cache.
- The project registers no custom payloads, network receivers, server commands, entity types, or entity renderers.

The stable mod id remains `fbx-player-models` so existing configuration and asset identifiers continue to work. The display name and archive base name are `FBX Player Models Lite` and `fbx-player-models-lite`.

## Removed full-version features

This branch does not contain:

- FBX display entities or pathfinding FBX mobs.
- Server-side model storage or upload permissions.
- Client-to-server model uploads.
- Server-to-client model downloads.
- Player model lookup, distribution, broadcast, or synchronization packets.
- Remote-player custom-model caching or rendering.
- Mob-model download caches.
- Community-server upload disclaimers or upload controls.

Do not reintroduce server model handling into the Lite branch. Features that need multiplayer distribution belong in the full mod.

## Supported Fabric targets

Each target is independently buildable and contains its own Minecraft-version-sensitive client code. `common` supplies pure Java model data and shared assets; it is not a standalone mod.

| Module | Minecraft | Namespace | Java | Fabric Loom | Fabric Loader | Platform API |
| --- | --- | --- | --- | --- | --- | --- |
| `fabric-1.21.1` | `1.21.1` | Yarn `1.21.1+build.3` | 21 | `1.16.2` | `0.16.14` | `0.116.6+1.21.1` |
| `fabric-26.2` | `26.2` | unobfuscated game names | 25 | `1.17.17` | `0.19.3` | `0.156.0+26.2` |
| `fabric-b1.7.3` | `b1.7.3` | BINY `b1.7.3+e1fe071` | 17 | `1.16.3` + Babric Loom Extension `1.17.2` | `0.19.3` | StationAPI `2.0.0-alpha.6.4` |

| Module | Mod version | Mod Menu | LWJGL Assimp/NFD | Production jar |
| --- | --- | --- | --- | --- |
| `fabric-1.21.1` | `2.0.0+1.21.1` | `11.0.3` | `3.3.3` | `fbx-player-models-lite-v2.0.0+1.21.1+mc1.21.1.jar` |
| `fabric-26.2` | `2.0.0+26.2` | `20.0.1` | `3.4.1` | `fbx-player-models-lite-v2.0.0+26.2+mc26.2.jar` |
| `fabric-b1.7.3` | `2.0.0+b1.7.3` | none required | game-provided LWJGL 2; no Assimp/NFD | `fbx-player-models-lite-v2.0.0+b1.7.3+mcb1.7.3.jar` |

The 1.21.1 target uses Yarn and Loom's `remapJar`. Minecraft 26.2 exposes unobfuscated names and produces its artifact through `jar`. Both modern targets bundle jgltf `2.0.4` plus Native File Dialog and Assimp natives for Windows, Linux, and macOS on x64 and arm64. Beta uses `remapJar`, bundles JOML `1.10.8`, and requires StationAPI instead of modern Fabric API.

`settings.gradle` includes all three targets for unqualified task requests. Explicit `fabric-*` task paths configure only the requested targets, so a modern-only compilation does not resolve Babric plugins or Beta dependencies. The Babric extension may resolve a newer transitive Loom version than its declared `fabric-loom` plugin marker.

`common` and Beta select Java 17 compiler toolchains. Modern modules retain their Java 21 and Java 25 release settings and use the Gradle daemon's compiler. Use a Java 25 JDK to launch Gradle when compiling 26.2, with a Java 17 JDK installed for the shared/Beta toolchains. A Beta-only build needs a Java 21 or newer Gradle daemon because of Loom; Beta's runtime and bytecode still target Java 17. The Beta repository's Java 21 daemon pin was not imported, because it would select a compiler too old for the current 26.2 build configuration.

## Required compile command

For requested coding work, use the exact validation command required by `AGENTS.md`:

```bash
bash ./gradlew \
  :fabric-1.21.1:compileJava \
  :fabric-1.21.1:compileClientJava \
  :fabric-26.2:compileJava \
  :fabric-26.2:compileClientJava
```

Production build tasks remain:

```bash
./gradlew :fabric-1.21.1:build
./gradlew :fabric-26.2:build
./gradlew :fabric-b1.7.3:remapJar
```

Artifacts are written under each module's `build/libs/` directory.

The prescribed validation command compiles shared code and both modern targets. It does not compile Beta; no Beta build or regression task is run as part of this import's validation.

Import validation on 2026-09-13: the prescribed four-task command succeeded with the Gradle wrapper launched using Java 25. Shared sources compiled with the installed Java 17 toolchain; both modern client compilation tasks passed, and both modern main compilation tasks have no Java sources. An initial Java 21 run compiled shared code and 1.21.1 but could not compile 26.2's `--release 25`. The successful retry used a checksum-verified temporary JDK at `/tmp/fbx-beta-import-jdk25/jdk-25.0.4.1` via `JAVA_HOME`; this path is specific to this session and is not checked into project configuration.

## Nightly builds

`.github/workflows/nightly-lite.yml` builds all three targets in separate matrix jobs on pushes to `clientside` or manual dispatch. The Beta job runs `./gradlew :fabric-b1.7.3:remapJar`, captures `fabric-b1.7.3/build/libs`, and publishes with tag `nightly-b1.7.3` and title **Nightly Build Beta 1.7.3**. Its production filename is `fbx-player-models-lite-v2.0.0+b1.7.3+mcb1.7.3.jar`.

Each job explicitly installs a Java 17 compiler through `actions/setup-java@v5`, then selects the matrix JDK as the default Gradle runtime: Java 21 for Beta/1.21.1 and Java 25 for 26.2. The build passes the Java 17 setup step's `path` output through `org.gradle.java.installations.paths` so shared and Beta compiler toolchains can find it without relying on preinstalled runner JDKs. This follows the action's [documented multiple-JDK setup and path output](https://github.com/actions/setup-java/blob/v5/docs/advanced-usage.md#installing-jdk-without-setting-as-default). Beta remains Java 17 bytecode despite its Java 21 build runtime.

Added the Beta nightly matrix entry and explicit compiler setup on 2026-09-13. Existing modern jobs, workflow triggers, and permissions are retained. Validation: the four compile tasks required by `AGENTS.md` passed using Java 25; existing compilation outputs were up to date. The GitHub Actions workflow was not executed, no release was published, and the Beta artifact was not built locally.

### Publication recovery

The reported Beta release creation and 26.2 tag-push failures returned GitHub server errors. Publication now runs through `.github/scripts/publish-nightly.sh` for all three targets. It validates that artifact files exist before publishing, pushes the checked-out build commit directly to the nightly tag, and checks the remote tag's SHA before touching the release. Push, tag verification, release lookup/creation, each asset upload, and the final metadata/publication update independently allow five attempts with delays of 10, 20, 40, and 60 seconds. Exhausting retries fails the job and preserves the command's error status. Later-stage failures do not repeat completed earlier stages or other asset uploads.

The publisher updates existing releases instead of deleting them. It uses a [GraphQL query through `gh api`](https://cli.github.com/manual/gh_api) to look up both published and draft releases by tag. Creation occurs only after a successful lookup reports no release; failed lookups return their error status for retry. A new release starts as a draft, and an existing draft left by a successful request with a failed response is reused on the next attempt. Uploads use [GitHub CLI's `--clobber` option](https://cli.github.com/manual/gh_release_upload) to replace matching asset names, then the release's title, file-based notes, and prerelease status are updated and any draft is published. This lets retries resume after partial creation/upload/publication. Asset replacement deletes an existing matching asset before uploading its replacement, so persistent upload failures can still leave that asset missing; retries do not guarantee success during a continuing GitHub outage.

Jobs use concurrency groups scoped to the workflow and nightly tag with `cancel-in-progress: false`. Different Minecraft targets can still build simultaneously, while runs publishing the same tag cannot race or cancel a publication midway. The earlier unchecked deletion step and separate single-attempt tag/create steps are removed. These workflow changes do not alter mod sources, build tasks, tokens, or permission scopes.

Publication-recovery validation on 2026-09-13: the prescribed four compile tasks passed using Java 25 with existing compilation outputs up to date. The publisher's command/error paths were reviewed against the official GitHub CLI documentation. The compile command does not exercise the Bash publisher or simulate GitHub failures; no additional checks, live tag pushes, release changes, or workflow dispatches were run.

## Project layout

```text
root/
  settings.gradle
  build.gradle
  gradle.properties
  common/
    src/main/java/...
    src/main/resources/...
  fabric-1.21.1/
    build.gradle
    src/main/resources/fabric.mod.json
    src/client/java/...
    src/client/resources/fbx-player-models.client.mixins.json
  fabric-26.2/
    build.gradle
    src/main/resources/fabric.mod.json
    src/client/java/...
    src/client/resources/fbx-player-models.client.mixins.json
  fabric-b1.7.3/
    build.gradle
    src/main/java/...
    src/main/resources/fabric.mod.json
    src/main/resources/fbx-player-models.b173.client.mixins.json
    src/main/resources/assets/fbx-player-models/stationapi/lang/en_US.lang
    src/test/java/...
```

Modern modules use split client source sets and have no version-specific `src/main/java` implementation. Babric uses `src/main/java`; Beta's metadata, StationAPI entrypoint, and client-only mixin configuration enforce its client boundary.

## Common code

Keep version-independent code in `common`, including:

- `FBXPlayerModels.MOD_ID` and `DISPLAY_NAME`.
- Configuration/save data classes.
- Pure Java model, skeleton, rig, and animation data.
- Mapping-independent numeric utility types.
- Shared assets.
- Platform-neutral client interfaces under `com.aksulightning.platform`.

Common code must not import Minecraft, Fabric, Mixin, Mod Menu, or mapping-specific classes.

Keep shared sources compatible with Java 17 so all three targets can consume the same classes. `LogicalRigBinding.firstName()` uses `List.get(0)` after checking for an empty list, preserving its behavior without requiring Java 21's `List.getFirst()`.

## Version-specific client code

Keep Minecraft- and Fabric-sensitive code in the corresponding target modules, including:

- `ClientModInitializer` and Mod Menu integration.
- Client commands and screens.
- FBX parsing and dynamic texture creation.
- Player and first-person rendering.
- Camera and held-item hooks.
- Client lifecycle integration and game-directory access.

When a shared feature changes, make the equivalent mapping-appropriate change in each affected target. Beta's platform adapters live under `com.aksulightning.platform.b173`; its parser, texture, screen, rendering, and input code remain inside `fabric-b1.7.3`.

## Local model lifecycle

`SkinManager.pickClientSkin()` opens the platform file chooser: Native File Dialog on modern targets and Swing `JFileChooser` on Beta. Selection continues on the client thread through `selectSelfSkin(Path)`:

1. Read the selected local file.
2. Reject formats other than FBX.
3. Hash the bytes and copy them into `.fbxplayermodels/skins/<sha256>.fbx`. Beta also preserves referenced external images in a model-specific `<sha256>.assets/` sidecar.
4. Save the selected hash, original display name, and rig settings in `.fbxplayermodels/.config`.
5. Parse and normalize the model into the single in-memory `selfSkin` cache.

`loadSelfSkin()` restores only that configured local model. No UUID lookup, connection event, server request, or world state participates in loading.

## Rendering boundary

Third-person rendering is implemented by the version-specific player-render mixin:

- 1.21.1 requires `renderedPlayer == MinecraftClient.getInstance().player`.
- 26.2 requires `renderedPlayer == Minecraft.getInstance().player`.
- Beta's `PlayerRenderMixin` requires `renderedPlayer == FBXPlayerModelsClient.minecraft().player` before cancelling vanilla player rendering.

If that identity check fails, vanilla rendering continues untouched. This guarantees that remote players cannot receive the local model on the same client. Because the mod has no networking and is not installed on the server, no other client can learn or render the selection.

`FirstPersonSelfModelRenderer` is separately guarded by the local player, first-person camera, option state, and sleeping pose. Modern targets additionally check spectator state and invisibility. GUI previews render the same local cache directly; modern targets fall back to the vanilla local player when no custom model is selected, while Beta leaves the preview empty.

## Mixins

Modern targets use `fbx-player-models.client.mixins.json`. Their client mixins are:

- `CameraMixin`
- `HeldItemRendererMixin` on 1.21.1 / `ItemInHandRendererMixin` on 26.2
- `RenderMixin`
- `MainMenuMixin`

There is no common/server mixin configuration and no access widener.

Beta uses `fbx-player-models.b173.client.mixins.json` with `CameraMixin`, `ClientChatMixin`, `HeldItemRendererMixin`, `MainMenuMixin`, `MinecraftMixin`, `PlayerRenderMixin`, and `WorldRendererMixin`. They implement camera offsets, local `/skin` interception, hand/item suppression, title-screen access, queued client work, local-player replacement, and first-person body rendering respectively.

## Beta 1.7.3 import

Imported `fabric-b1.7.3/build.gradle` and the complete `src` tree from `~/gits/FBXPlayerModels-lite-beta/` on 2026-09-13. This includes StationAPI initialization and platform adapters, FBX loading and normalization, logical animation and skinning, configuration/editor screens, previews, first-person rendering, camera offsets, the **O** menu key binding, local `/skin` handling, metadata, language assets, and the two existing standalone regression fixtures. Build outputs, runtime directories, Gradle caches, repository configuration, and the source repository's removal/disablement of modern targets were not imported.

Integration adds Beta's version properties, the Babric/JitPack plugin repositories, and selective inclusion alongside the existing 1.21.1 and 26.2 modules. Shared code now targets Java 17 and replaces the one Java 21-only list call. Existing modern sources and the shared mod-id constant are retained. The three metadata files continue to use the existing `fbx-player-models` asset/mod namespace.

Beta retains the single local `SkinManager` cache and saved rig/configuration classes. `DynamicTextureLoader` decodes images with `ImageIO` and registers integer texture handles through Beta's `TextureManager`. `LegacyModelRenderer` uses the game's LWJGL 2 and `Tessellator`, restoring fixed-function GL state around rendering. No LWJGL 3 Assimp/NFD, jgltf, modern Fabric API, or Mod Menu dependency is added to Beta.

The imported binary-FBX path includes the source build's multi-mesh and texture handling plus authored-joint repairs: mesh scene transforms use `TransformLink * Transform`, normalization transforms skeleton roots while retaining child-local binds, and head yaw/pitch rotates in the bind basis around the fixed authored neck joint. Logical animation, weights, materials, UVs, and the malformed-joint fallback remain in the existing pipeline. Camera offsets are rotated from saved view-space X/Y/Z into the vanilla camera transform; sleeping and debug camera bypass them.

The Java FBX backend's ASCII fallback provides static geometry, and clips available only through modern Assimp are not imported by Beta. The existing `SkinnedModelHeadPoseRegression.java` and `FBXHeadPoseRegression.java` fixtures were copied but not executed. The latter requires an external FBX fixture and an OpenGL display; neither fixtures nor development classpaths/caches are bundled. Imported source-repository validation records are not validation of this workspace. Interactive Beta gameplay, file selection, persistence, camera/held-item behavior, and multiplayer rendering remain unverified here.

## Minecraft 26.2 rendering notes

The 26.2 path uses `SubmitNodeCollector.submitCustomGeometry`, `RenderTypes.entityCutout`, `PoseStack`, and `VertexConsumer`. It contains no direct raw OpenGL state manipulation. Animated vertices are generated during render submission so inventory-preview body/head rotations from `LivingEntityRenderState` are respected.

Assimp parses FBX files but does not issue rendering calls. OpenGL is the minimum runtime target; experimental Vulkan behavior has not been functionally verified.

## Adding another Fabric target

1. Confirm the Minecraft version, mappings, Java, Loader, compatible platform API, Loom, and optional menu integration versions.
2. Add target properties and include the module in `settings.gradle`.
3. Copy the closest Fabric module's client source and resources.
4. Adapt mapping-sensitive GUI, renderer, lifecycle, and mixin APIs.
5. Keep metadata client-only and declare no `main` entrypoint.
6. Preserve the local-player identity check and single self-model cache.
7. Do not add server networking, registration, commands, persistence, or entities.
8. Run the focused compile command permitted by `AGENTS.md` and document any target it does not cover.
