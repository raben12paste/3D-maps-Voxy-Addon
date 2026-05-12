# 3D Maps Voxy Addon

3D Maps Voxy Addon, also packaged as **VoxyMap**, is a client-side Fabric mod that adds a full-screen 3D world map powered by Voxy LOD data.

The goal is to provide a large, cinematic world overview instead of a flat minimap: you can rotate the camera, zoom out, inspect terrain from far away, and manage visible waypoints.

## Features

- Full-screen 3D map opened with the `M` key.
- Voxy-powered terrain data for long-range world previews.
- Smooth camera movement, zoom, rotation, and centering on the player.
- Waypoints visible both on the map and in normal gameplay.
- Double-click on the map to create a waypoint.
- Select a waypoint to rename, hide the waypoint panel, or delete it.
- Polish and English translations.
- Client command for debug logs: `/3dmaps debug on/off`.
- Warning when Voxy is missing or when an untested Voxy version is detected.

## Requirements

- Minecraft `1.21.11`
- Java `21`
- Fabric Loader `0.19.2` or newer
- Fabric API `0.141.3+1.21.11` or compatible
- Voxy, tested with `0.2.15-beta`

VoxyMap is a client-side addon. It does not need to be installed on the server, but server rules may still restrict minimap, world map, LOD, or waypoint mods. Check the rules before using it on public servers.

## Usage

1. Install Fabric Loader and Fabric API.
2. Install Voxy.
3. Put the VoxyMap `.jar` file into your Minecraft `mods` folder.
4. Start the game and press `M` to open the map.

### Map controls

- `W/A/S/D` - move the map camera.
- `Shift` - move faster.
- `Right mouse button` - rotate/look around.
- `Mouse wheel` - zoom.
- `Double left click` - create a waypoint.
- `C` - center the map on the player.
- `M` or `Esc` - close the map.

### Debug command

Debug logging is disabled by default.

```text
/3dmaps debug
/3dmaps debug on
/3dmaps debug off
```

Use debug logging only when collecting logs for bug reports.

## Building From Source

```shell
./gradlew build
```

On Windows:

```powershell
.\gradlew.bat build
```

The compiled mod will be created in `build/libs`.

## Compatibility

The mod uses Voxy internals through reflection, so compatibility can change when Voxy updates. Version `0.2.15-beta` is currently treated as tested.

## License

This project is licensed under the MIT License. See `LICENSE` for details.
