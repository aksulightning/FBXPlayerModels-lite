# FBX Player Models Lite

Use an `.fbx` model for your own Minecraft player without installing anything on the server.

FBX Player Models Lite is entirely client-side:

- Your selected FBX file is loaded from your own game directory.
- The custom model replaces only your local player's rendering on your client.
- Other players continue to render normally on your client.
- Other players cannot see your custom model; they see your normal Minecraft skin.
- No model files, hashes, selections, or permissions are sent to a server.

FBX mobs, server model storage, multiplayer model synchronization, and server skin uploads are not included.

## Download

- [Minecraft 1.21.1 nightly](https://github.com/aksulightning/FBXPlayerModels/releases/tag/nightly-1.21.1)
- [Minecraft 26.2 nightly](https://github.com/aksulightning/FBXPlayerModels/releases/tag/nightly-26.2)

Both downloads are experimental Fabric builds.

The source also includes a Beta 1.7.3 target (`fabric-b1.7.3`). It requires Fabric Loader `0.19.3` or newer, StationAPI `2.0.0-alpha.6.4` or newer, and Java 17 or newer. See [Code_Guide.md](Code_Guide.md) for build requirements and target differences.

## How to use a model

1. Install the mod on your client only.
2. Start Minecraft and run `/skin`, use Mod Menu, or select the preview on the title screen.
3. Choose an `.fbx` file.
4. Open **Settings** if the arms, legs, head, or body need different rig bindings.
5. Use **Auto Bind** first, then adjust individual body-part bindings if necessary.

On Beta 1.7.3, press **O** during gameplay, run `/skin`, or use the title-screen preview to open the menu. The shortcut can be reassigned in Controls; Beta does not require Mod Menu.

The selected file is copied into the local `.fbxplayermodels/skins/` cache. The selection and rig settings are stored in `.fbxplayermodels/.config`.

## Good model characteristics

Simple models exported from Blender are the most likely to work well. Prefer:

- Low-detail geometry.
- One material.
- Embedded or adjacent textures.
- Clear bone names such as `Head`, `Chest`, `Right Arm`, `Left Arm`, `Right Leg`, and `Left Leg`.

A model template is available on the [template branch](https://github.com/aksulightning/FBXPlayerModels/tree/template).

## Help and feedback

- [Report a bug or suggest an idea](https://github.com/aksulightning/FBXPlayerModels/issues)
- [Contribute code](https://github.com/aksulightning/FBXPlayerModels/pulls)

FBX Player Models Lite is based on [AllTheSkins by 1TheCrazy](https://github.com/1TheCrazy/AllTheSkins).
