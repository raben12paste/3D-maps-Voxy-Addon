# CLAUDE.md

Guidance for working in this repository.

## What this is

VoxyMap (repo name: 3D Maps Voxy Addon) is a **client-only Fabric mod** for Minecraft
that renders a full-screen 3D world map using [Voxy](https://github.com/MCRcortex/voxy)
LOD data. Pressing `M` opens a `Screen` that takes over the game camera and lets the
player fly over the terrain Voxy has already stored.

It does not draw the map itself. It moves the vanilla camera and suppresses the things
that would get in the way (fog, weather, clouds, the HUD, the held item), so the normal
world render — fed by Voxy — becomes the map.

## Toolchain

| Thing | Version |
| --- | --- |
| Minecraft | `26.3` |
| Java | **25** (JDK 25 required to build *and* to run Gradle; MC 26.3 runs on Java 25) |
| Fabric Loader | `0.19.5` |
| Fabric API | `0.162.0+26.3` |
| Loom | `1.18.3` (plugin id `net.fabricmc.fabric-loom`) |
| Gradle | `9.8.1` (Loom 1.18 needs ≥ 9.7 running on Java 25) |
| Voxy | `0.2.20-beta`, `263` branch (compile-only, local jar — see below) |
| Iris | `1.11.7+26.3-fabric` (compile-only) |
| Xaero's World Map | `1.47.0` (compile-only) |

All versions live in `gradle.properties`; `build.gradle` reads them from there. Bump
them in one place.

Voxy for 26.3 is not on Modrinth yet. If any `libs/voxy-*.jar` exists it is used instead
of `maven.modrinth:voxy:${voxy_version}`; drop a build of Voxy's
[`263` branch](https://github.com/MCRcortex/voxy/tree/263) there. `*.jar` is git-ignored
and Voxy is All-Rights-Reserved, so the jar is never committed. Once Voxy publishes a
26.3 build, delete `libs/` and the Modrinth artifact is picked up again.

```shell
./gradlew build          # produces build/libs/VoxyMap-<version>.jar
```

Set `JAVA_HOME` to a JDK 25 if the default JVM is older.

## Minecraft 26.x is deobfuscated — this changes everything

From the 26.x series Mojang ships the client with real class and method names and no
longer publishes `client_mappings`. Consequences that keep biting:

- **No `mappings` line in `build.gradle`.** `loom.officialMojangMappings()` fails with
  "Failed to find official mojang mappings".
- **Use the `net.fabricmc.fabric-loom` plugin, not `net.fabricmc.fabric-loom-remap`.**
  The `-remap` variant exists for older, obfuscated versions.
- **Plain `implementation` / `clientCompileOnly`**, not `modImplementation` /
  `modCompileOnly`. There is no remap step, so mod jars are ordinary dependencies.
- **`remap = false` on every mixin annotation.** The names in this source tree are the
  names at runtime; there is no refmap.
- The built `jar` is the shippable mod jar — there is no `remapJar`.

## Layout

```
src/main/resources/         fabric.mod.json, mixin config, lang files, icon
src/client/java/com/voxymap/client/
  VoxyMapClient.java        entrypoint; polls M each client tick
  gui/MapScreen.java        the map screen: camera control, HUD, input
  gui/PlayerMarker.java     the player arrow on the map, or on its edge when off screen
  map/VoxyMapCameraController.java  the detached camera state the mixins read
  map/MapView.java                  that camera as a basis: world→screen and screen→ground
  map/VoxyMapGuiRenderer.java       Iris-shaderpack fallback path (see below)
  map/VoxyBridge.java               Voxy fog mode and render distance while the map is open
  map/MapRenderSettingsGuard.java   forces clouds off while the map is open
  map/VoxyMapSettings.java          config/voxymap.properties
  integration/              Xaero's World Map bridge + its return button
  mixin/                    all mixins, listed in voxymap.client.mixins.json
```

`VoxyMapCameraController` is the hinge: `MapScreen` writes the desired camera into it
each frame, and `CameraMixin` applies it to the vanilla `Camera`. Everything else keys
off `VoxyMapCameraController.isActive()`.

The camera aims at a focus point on the ground (`viewCenterY` is the heightmap top under
the player), not at a fixed height: aiming high above the terrain shifts everything on
screen away from the player. `MapView` must build its basis exactly like vanilla
`Camera.setRotation` (`rotationYXZ(π - yaw, -pitch, 0)`), or zoom-to-cursor and the player
marker drift off their ground points.

Zooming in narrows the lens with the camera held at a fixed distance; zooming out backs the
camera away and tilts it towards straight down (`VoxyMapCameraController`), up to
`MAX_BLOCKS_PER_PIXEL`. Voxy only draws within its render distance of the **camera**,
measured across the ground (`RenderDistanceTracker` plus the circle test in
`traversal_dev.comp`), so while the map is open `VoxyBridge` raises
`VoxyConfig.sectionRenderDistance` to cover the screen (`MapView.groundReach`) and puts the
user's value back on close. Voxy settings are restored *before* `VoxyMapGuiRenderer.close`,
because under Iris that rebuilds Voxy's renderer and the new pipeline reads them.

`VoxyMapGuiRenderer` is only used when an Iris shader pack is active — the shader
pipeline cannot render the detached map camera in the normal world pass, so Voxy's
world render is cancelled and re-run straight into the main render target just before
the GUI draws.

## Porting notes / API landmines

Things that moved recently. Check these first when a new Minecraft version breaks the build.

Minecraft 26.3:

- **GLFW is gone; windowing and input are SDL3** (`org.lwjgl.sdl`). There is no
  `org.lwjgl.glfw` on the classpath. Use `com.mojang.blaze3d.platform.InputConstants`:
  `InputConstants.isKeyDown(scancode)` (no window argument) replaces `glfwGetKey`.
  Key constants are now **SDL scancodes** (`KEY_A = 4`, `KEY_ESCAPE = 41`) and
  `KeyEvent.key()` carries the scancode. Mouse buttons are SDL numbers:
  `MOUSE_BUTTON_LEFT = 1`, `MOUSE_BUTTON_MIDDLE = 2` (GLFW's left was 0). Keys
  `InputConstants` lacks (e.g. keypad minus) come from `org.lwjgl.sdl.SDLScancode`.
- **The GPU API moved to `com.mojang.renderpearl`** (OpenGL and Vulkan backends):
  `com.mojang.blaze3d.buffers.*` → `com.mojang.renderpearl.api.buffers.*`,
  `com.mojang.blaze3d.textures.*` → `com.mojang.renderpearl.api.textures.*`,
  `com.mojang.blaze3d.systems.CommandEncoder` → `com.mojang.renderpearl.api.commands.CommandEncoder`,
  `com.mojang.blaze3d.opengl.GlTextureView` → `com.mojang.renderpearl.backend.opengl.GlTextureView`.
  `RenderSystem` and `RenderTarget` stay in `com.mojang.blaze3d`. Voxy rejects the Vulkan
  backend, so the GL texture views are always there when Voxy is.
- `LevelRenderer.addCloudsPass` / `addWeatherPass` are gone. Clouds and weather are drawn
  from the translucent stage through `CloudRenderer.render`/`renderOit` and
  `WeatherEffectRenderer.render`/`renderOit` (classic vs. order-independent transparency).
  Both classes have a private `render` overload, so give the public one a full descriptor.
- `GameRenderer.render(DeltaTracker, boolean)` → `render()`;
  `renderItemInHand(CameraRenderState, float, Matrix4fc)` →
  `renderItemInHand(CameraRenderState, PlayerRenderState, GpuTextureView)`.
- `ClientClockManager.getTotalTicks(Holder)` is gone; `ClockManager.getInstance(holder)`
  returns a `ClockInstance` and every reader calls `totalTicks()` on it
  (`ClientClockManager$ClientClockInstance` on the client).

Minecraft 26.2:

- `GuiGraphics` **no longer exists**; it is `net.minecraft.client.gui.GuiGraphicsExtractor`.
  `drawString` → `text`, `drawCenteredString` → `centeredText`.
- `Screen.render(...)` → `Screen.extractRenderState(GuiGraphicsExtractor, int, int, float)`.
  `Screen.renderBackground(...)` → `Screen.extractBackground(...)`.
- `net.minecraft.client.gui.Gui` is now screen/overlay management. The HUD widgets
  (crosshair, hotbar, health…) live in `net.minecraft.client.gui.Hud` as `extractX` methods.
- `Minecraft.setScreen` / `Minecraft.screen` → `Minecraft.gui.setScreen(...)` / `Minecraft.gui.screen()`.
- `Minecraft.getMainRenderTarget()` → `Minecraft.gameRenderer.mainRenderTarget()`.
- `Player.displayClientMessage(Component, boolean)` → `LocalPlayer.sendSystemMessage(Component)`.
- `ResourceLocation` is `net.minecraft.resources.Identifier`; `ResourceKey.location()` is `identifier()`.
- `GuiRenderer.render(GpuBufferSlice)` → `GuiRenderer.render()` (no arguments).
- Input arrives as records: `mouseClicked(MouseButtonEvent, boolean)`, `keyPressed(KeyEvent)`.
- **The depth buffer is reversed**: clear depth to `0.0`, not `1.0`. Vanilla clears via
  `RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(...)`.

Voxy 0.2.20-beta (`263` branch) specifics:

- `VoxyConfig.useEnvironmentalFog` is gone; it is `getFogMode()` / `setFogMode(...)` with
  `NormalRenderPipeline.FogMode` (`FOG_AND_FADE`, `FOG`, `FADE`, `OFF`). `OFF` is the old
  `useEnvironmentalFog = false`. Voxy reads `removesVanillaEnvFog` every frame but bakes
  `hasFog`/`hasFade` into the pipeline when it is created.

Voxy 0.2.18-beta specifics:

- `IGetVoxyRenderSystem` was renamed to `IVoxyRenderSystemHolder`.
- `VoxyRenderSystem.renderOpaque(Viewport)` → `renderOpaque(Viewport, int depthTextureViewGlId, int colorTextureViewGlId)`;
  Voxy now owns the framebuffer and restores GL state itself.
- `RenderPipelineFactory.createIrisPipeline` is private and takes five arguments.
  Returning `null` from it makes `createPipeline` fall back to `NormalRenderPipeline`.

## Verifying mixins without launching the game

Mixin failures are runtime failures, and a wrong `method =` with `require = 0` fails
*silently* — the map simply misbehaves. Do not trust "it compiles".

Because the game is deobfuscated, the client jar can be inspected directly:

```shell
curl -s https://piston-meta.mojang.com/mc/game/version_manifest_v2.json   # find the version json
# then download its downloads.client.url as client.jar
javap -p -classpath client.jar net.minecraft.client.Camera            # method/field names
javap -p -c -classpath client.jar net.minecraft.client.renderer.GameRenderer   # @At INVOKE call sites
```

After changing any mixin, confirm for each one that (a) the target class exists,
(b) every `method = "..."` name exists on it, (c) the handler's parameter list equals
the target's parameter list, and (d) every `@At(target = "...")` call site is really
present in the method being injected into. A short `javap` + regex script over
`src/client/java/com/voxymap/client/mixin` does this in seconds and is worth rerunning
on every version bump.

Prefer the default `require = 1` for vanilla targets so a broken injector fails loudly
at load. `require = 0` is reserved for `@Pseudo` mixins into optional mods
(`XaeroGuiMapMixin`), which legitimately may not be present.

## Conventions

- Mixin handler methods are prefixed `voxymap$`.
- Optional-mod access is wrapped in a nested holder class (`IrisAccess`, `XaeroAccess`)
  so the classes are only loaded when the mod is present, guarded by
  `FabricLoader.getInstance().isModLoaded(...)`.
- User-visible strings are translation keys in `assets/voxymap/lang/`
  (`en_us`, `pl_pl`, `ru_ru` — keep all three in sync).
- The Nether is deliberately unsupported and shows an explanatory panel.
