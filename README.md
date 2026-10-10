
# Hey, the mod will no longer be supported by me due to time constraints, but if someone wants to support it, I have nothing against it.

## 3D Maps Voxy Addon

3D Maps Voxy Addon, also packaged as **VoxyMap**, is a client-side Fabric mod that adds a full-screen 3D world map powered by Voxy LOD data.

The goal is to provide a large, cinematic world overview instead of a flat minimap: you can grab the map, zoom toward the cursor, and inspect terrain from far away.

https://modrinth.com/mod/3d-maps-voxy-addon

## Features

- Full-screen 3D map opened with the `M` key.
- Voxy-powered terrain data for long-range world previews.
- Smooth map dragging, cursor-centered zoom, keyboard movement, and centering on the player.
- A player marker that shows where you are and which way you face; when you are off screen it
  waits at the edge of the map with your distance, and clicking it brings the map back to you.
- Full support for English, Polish, and Russian languages.

## Requirements

- Minecraft `26.3`
- Java `25`
- Fabric Loader `0.19.5` or newer
- Fabric API `0.162.0+26.3` or compatible
- Voxy `0.2.20-beta` for 26.3 (built from Voxy's [`263` branch](https://github.com/MCRcortex/voxy/tree/263)) or compatible

VoxyMap is a client-side addon. It does not need to be installed on the server, but server rules may still restrict minimap, world map, LOD, or waypoint mods. Check the rules before using it on public servers.

## Usage

1. Install Fabric Loader and Fabric API.
2. Install Voxy.
3. Put the VoxyMap `.jar` file into your Minecraft `mods` folder.
4. Start the game and press `M` to open the map.

### Map controls

- `Left mouse button` - grab and drag the map.
- `Middle mouse button` - rotate the camera left or right.
- `W/A/S/D` or `Arrow keys` - move the map camera.
- `Shift` - move faster.
- `Mouse wheel` - zoom toward the cursor.
- `C` - center the map on the player (or click the player marker at the edge of the map).
- `M` or `Esc` - close the map.

## Building From Source

```shell
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

The compiled mod will be created in `build/libs`. Building requires JDK 25, the same
Java version Minecraft 26.3 runs on.

Voxy for Minecraft 26.3 is not published on Modrinth yet. Put a Voxy jar built from its
[`263` branch](https://github.com/MCRcortex/voxy/tree/263) into `libs/` (for example
`libs/voxy-0.2.20-beta.jar`) and the build compiles against it.

Minecraft 26.3 ships deobfuscated, so the build uses Fabric Loom without mappings and
without a remap step: the class and method names in this repository are the ones the
game uses at runtime.

## Compatibility

The mod integrates with the Voxy client API. Voxy is required, but VoxyMap does not restrict the installed Voxy version.

## License

This project is licensed under the MIT License. See `LICENSE` for details.
