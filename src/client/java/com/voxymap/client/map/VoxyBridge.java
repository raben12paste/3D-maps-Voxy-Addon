package com.voxymap.client.map;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bridge to Voxy's internal API via Java reflection.
 * Accesses VoxyCommon.getInstance() -> VoxyInstance -> WorldEngine -> WorldSection
 *
 * Voxy data format (from WorldSection.data[], long[]):
 *   bits [26:0]  = lower bits (flags, etc)
 *   bits [46:27] = blockId (20 bits) - index into Mapper's block state list
 *   bits [55:47] = biomeId (9 bits)
 *   bits [63:56] = light   (8 bits)
 *
 * WorldSection: 32x32x32 region of blocks at a given LOD level
 * Index: ((y & 31) << 10) | ((z & 31) << 5) | (x & 31)
 */
public class VoxyBridge {

    private static final Logger LOGGER = LoggerFactory.getLogger("voxymap.bridge");
    private static final Set<String> TESTED_VOXY_VERSIONS = Set.of("0.2.15-beta");

    private static final boolean VOXY_PRESENT;
    private static final String VOXY_VERSION;
    private static boolean initialized = false;
    private static boolean initFailed = false;

    // Reflected classes and methods
    private static Class<?> voxyCommonClass;
    private static Method getInstanceMethod;
    private static Method getNullableMethod;  // VoxyInstance.getNullable(WorldIdentifier)
    private static Class<?> worldEngineClass;
    private static Method acquireIfExistsMethod;
    private static Field worldSectionDataField;
    private static Method copySectionDataMethod;
    private static Class<?> mapperClass;
    private static Method getMapperMethod;
    private static Method getBlockStateMethod; // Mapper.getBlockStateFromBlockId(int)
    private static Field worldEngineMapperfField;
    private static Boolean previousEnvironmentalFog = null;

    // World identifier access
    private static Class<?> worldIdentifierClass;
    private static Method worldIdentifierOfEngineNullableMethod;

    static {
        VOXY_PRESENT = FabricLoader.getInstance().isModLoaded("voxy");
        VOXY_VERSION = FabricLoader.getInstance().getModContainer("voxy")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("not installed");
        if (!VOXY_PRESENT) {
            LOGGER.info("[VoxyMap] Voxy not found - map will show empty until Voxy is installed");
        }
    }

    public static boolean isVoxyPresent() {
        return VOXY_PRESENT;
    }

    public static String getVoxyVersion() {
        return VOXY_VERSION;
    }

    public static String testedVoxyVersionsText() {
        return String.join(", ", TESTED_VOXY_VERSIONS);
    }

    public static boolean isTestedVoxyVersion() {
        return VOXY_PRESENT && TESTED_VOXY_VERSIONS.contains(VOXY_VERSION);
    }

    private static boolean ensureInit() {
        if (initialized) return true;
        if (initFailed) return false;
        if (!VOXY_PRESENT) { initFailed = true; return false; }

        try {
            voxyCommonClass = Class.forName("me.cortex.voxy.commonImpl.VoxyCommon");
            getInstanceMethod = voxyCommonClass.getDeclaredMethod("getInstance");
            getInstanceMethod.setAccessible(true);

            worldEngineClass = Class.forName("me.cortex.voxy.common.world.WorldEngine");
            acquireIfExistsMethod = worldEngineClass.getDeclaredMethod("acquireIfExists", int.class, int.class, int.class, int.class);
            acquireIfExistsMethod.setAccessible(true);

            try {
                getMapperMethod = worldEngineClass.getDeclaredMethod("getMapper");
                getMapperMethod.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
                worldEngineMapperfField = worldEngineClass.getDeclaredField("mapper");
                worldEngineMapperfField.setAccessible(true);
            }

            Class<?> worldSectionClass = Class.forName("me.cortex.voxy.common.world.WorldSection");
            try {
                copySectionDataMethod = worldSectionClass.getDeclaredMethod("copyData");
                copySectionDataMethod.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
                worldSectionDataField = worldSectionClass.getDeclaredField("data");
                worldSectionDataField.setAccessible(true);
            }

            worldIdentifierClass = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier");
            try {
                worldIdentifierOfEngineNullableMethod = worldIdentifierClass.getDeclaredMethod("ofEngineNullable", net.minecraft.world.level.Level.class);
                worldIdentifierOfEngineNullableMethod.setAccessible(true);
            } catch (NoSuchMethodException e) {
                LOGGER.debug("[VoxyMap] Voxy WorldIdentifier.ofEngineNullable(Level) is not available, falling back to active worlds");
            }

            // Mapper.getBlockStateFromBlockId(int)
            mapperClass = Class.forName("me.cortex.voxy.common.world.other.Mapper");
            getBlockStateMethod = mapperClass.getDeclaredMethod("getBlockStateFromBlockId", int.class);
            getBlockStateMethod.setAccessible(true);

            initialized = true;
            LOGGER.info("[VoxyMap] Successfully hooked into Voxy internals");
            return true;
        } catch (Exception e) {
            LOGGER.warn("[VoxyMap] Failed to hook into Voxy: {}", e.getMessage());
            initFailed = true;
            return false;
        }
    }

    /**
     * Get the active Voxy WorldEngine for the currently active world.
     * Returns null if Voxy is not available or no world is loaded.
     */
    public static Object getActiveWorldEngine(Minecraft client) {
        if (!ensureInit()) return null;
        if (client != null && client.level != null && worldIdentifierOfEngineNullableMethod != null) {
            try {
                Object worldEngine = worldIdentifierOfEngineNullableMethod.invoke(null, client.level);
                if (worldEngine != null) return worldEngine;
            } catch (Exception e) {
                LOGGER.debug("[VoxyMap] Error getting current World's Voxy engine: {}", e.toString());
            }
        }
        return getActiveWorldEngine();
    }

    public static Object getActiveWorldEngine() {
        if (!ensureInit()) return null;
        try {
            // VoxyCommon.getInstance() returns VoxyInstance
            Object voxyInstance = getInstanceMethod.invoke(null);
            if (voxyInstance == null) return null;

            // VoxyInstance has a HashMap<WorldIdentifier, WorldEngine> activeWorlds
            // We need to get the current world's engine
            // Use reflection to access the activeWorlds field
            Field activeWorldsField = voxyInstance.getClass().getSuperclass().getDeclaredField("activeWorlds");
            activeWorldsField.setAccessible(true);
            Map<?, ?> activeWorlds = (Map<?, ?>) activeWorldsField.get(voxyInstance);

            if (activeWorlds.isEmpty()) return null;

            // Return first (and usually only) active world engine
            return activeWorlds.values().iterator().next();
        } catch (Exception e) {
            LOGGER.debug("[VoxyMap] Error getting WorldEngine: {}", e.getMessage());
            return null;
        }
    }

    public static String describeDiagnostics(Minecraft client) {
        StringBuilder out = new StringBuilder();
        out.append("present=").append(VOXY_PRESENT)
                .append(" version=").append(VOXY_VERSION)
                .append(" tested=").append(isTestedVoxyVersion())
                .append(" bridgeInit=").append(initialized)
                .append(" bridgeFailed=").append(initFailed);

        Object engine = getActiveWorldEngine(client);
        Object mapper = getMapper(engine);
        out.append(" engine=").append(simpleName(engine))
                .append(" mapper=").append(simpleName(mapper));

        describeActiveWorlds(out);
        describeConfig(out);
        describeRenderSystem(out);
        return out.toString();
    }

    private static void describeActiveWorlds(StringBuilder out) {
        if (!ensureInit()) return;
        try {
            Object voxyInstance = getInstanceMethod.invoke(null);
            Field activeWorldsField = voxyInstance.getClass().getSuperclass().getDeclaredField("activeWorlds");
            activeWorldsField.setAccessible(true);
            Map<?, ?> activeWorlds = (Map<?, ?>) activeWorldsField.get(voxyInstance);
            out.append(" activeWorlds=").append(activeWorlds.size()).append("[");
            int shown = 0;
            for (Map.Entry<?, ?> entry : activeWorlds.entrySet()) {
                if (shown++ > 0) out.append(", ");
                if (shown > 4) {
                    out.append("...");
                    break;
                }
                out.append(String.valueOf(entry.getKey())).append("=>").append(simpleName(entry.getValue()));
            }
            out.append("]");
        } catch (Throwable t) {
            out.append(" activeWorldsErr=").append(t.getClass().getSimpleName()).append(":").append(t.getMessage());
        }
    }

    private static void describeConfig(StringBuilder out) {
        try {
            Class<?> configClass = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
            Object config = configClass.getField("CONFIG").get(null);
            out.append(" config{")
                    .append("enabled=").append(fieldValue(config, "enabled"))
                    .append(",rendering=").append(fieldValue(config, "enableRendering"))
                    .append(",ingest=").append(fieldValue(config, "ingestEnabled"))
                    .append(",sectionDistance=").append(fieldValue(config, "sectionRenderDistance"))
                    .append(",subdivision=").append(fieldValue(config, "subDivisionSize"))
                    .append(",envFog=").append(fieldValue(config, "useEnvironmentalFog"))
                    .append(",threads=").append(fieldValue(config, "serviceThreads"))
                    .append("}");
        } catch (Throwable t) {
            out.append(" configErr=").append(t.getClass().getSimpleName()).append(":").append(t.getMessage());
        }
    }

    public static void suppressEnvironmentalFogForMap() {
        setEnvironmentalFog(false, true);
    }

    public static void restoreEnvironmentalFogAfterMap() {
        if (previousEnvironmentalFog == null) return;
        setEnvironmentalFog(previousEnvironmentalFog, false);
        previousEnvironmentalFog = null;
    }

    private static void setEnvironmentalFog(boolean value, boolean rememberPrevious) {
        try {
            Class<?> configClass = Class.forName("me.cortex.voxy.client.config.VoxyConfig");
            Object config = configClass.getField("CONFIG").get(null);
            Field field = findField(config.getClass(), "useEnvironmentalFog");
            field.setAccessible(true);
            Object current = field.get(config);
            if (rememberPrevious && previousEnvironmentalFog == null && current instanceof Boolean bool) {
                previousEnvironmentalFog = bool;
            }
            if (current instanceof Boolean bool && bool == value) {
                return;
            }
            field.setBoolean(config, value);
            LOGGER.info("[VoxyMap] Voxy environmental fog {} for the 3D map.", value ? "restored" : "disabled");
        } catch (Throwable t) {
            LOGGER.debug("[VoxyMap] Could not change Voxy environmental fog: {}", t.toString());
        }
    }

    private static void describeRenderSystem(StringBuilder out) {
        try {
            Class<?> getterClass = Class.forName("me.cortex.voxy.client.core.IGetVoxyRenderSystem");
            Object renderSystem = getterClass.getDeclaredMethod("getNullable").invoke(null);
            out.append(" renderSystem=").append(simpleName(renderSystem));
            if (renderSystem == null) return;

            Object tracker = fieldValueObject(renderSystem, "renderDistanceTracker");
            out.append(" tracker=").append(simpleName(tracker));
            if (tracker != null) {
                out.append("{distance=").append(fieldValue(tracker, "renderDistance"))
                        .append(",posX=").append(fieldValue(tracker, "posX"))
                        .append(",posZ=").append(fieldValue(tracker, "posZ"))
                        .append(",rate=").append(fieldValue(tracker, "processRate"))
                        .append(",minSec=").append(fieldValue(tracker, "minSec"))
                        .append(",maxSec=").append(fieldValue(tracker, "maxSec"))
                        .append("}");
            }

            Object viewport = renderSystem.getClass().getDeclaredMethod("getViewport").invoke(renderSystem);
            out.append(" viewport=").append(simpleName(viewport));
            if (viewport != null) {
                out.append("{frame=").append(fieldValue(viewport, "frameId"))
                        .append(",size=").append(fieldValue(viewport, "width")).append("x").append(fieldValue(viewport, "height"))
                        .append(",camera=").append(fieldValue(viewport, "cameraX")).append("/")
                        .append(fieldValue(viewport, "cameraY")).append("/")
                        .append(fieldValue(viewport, "cameraZ"))
                        .append("}");
            }
        } catch (Throwable t) {
            out.append(" renderErr=").append(t.getClass().getSimpleName()).append(":").append(t.getMessage());
        }
    }

    private static String simpleName(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static Object fieldValueObject(Object target, String fieldName) throws ReflectiveOperationException {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object fieldValue(Object target, String fieldName) {
        try {
            return fieldValueObject(target, fieldName);
        } catch (Throwable t) {
            return "err:" + t.getClass().getSimpleName();
        }
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    /**
     * Extract blockId from Voxy's packed long data format.
     * blockId = bits [46:27] (20 bits)
     */
    public static int getBlockId(long voxyData) {
        return (int) ((voxyData >> 27) & 0xFFFFF);
    }

    /**
     * Check if this data entry represents air/void.
     */
    public static boolean isAir(long voxyData) {
        return ((voxyData & (((1L << 20) - 1) << 27)) == 0);
    }

    /**
     * Get light value from Voxy data.
     * light = bits [63:56] (8 bits)
     */
    public static int getLight(long voxyData) {
        return (int) ((voxyData >> 56) & 0xFF);
    }

    /**
     * Get the WorldSection data array (long[32*32*32]) for a specific section.
     * @param worldEngine the WorldEngine object
     * @param lvl LOD level (0 = highest detail)
     * @param secX section X coordinate (block X / 32)
     * @param secY section Y coordinate (block Y / 32)
     * @param secZ section Z coordinate (block Z / 32)
     * @return copy of data array, or null if section doesn't exist
     */
    public static long[] getSectionData(Object worldEngine, int lvl, int secX, int secY, int secZ) {
        if (!ensureInit() || worldEngine == null) return null;
        try {
            Object section = acquireIfExistsMethod.invoke(worldEngine, lvl, secX, secY, secZ);
            if (section == null) return null;

            try {
                if (copySectionDataMethod != null) {
                    return (long[]) copySectionDataMethod.invoke(section);
                }
                long[] data = (long[]) worldSectionDataField.get(section);
                return data != null ? data.clone() : null;
            } finally {
                Method releaseMethod = section.getClass().getDeclaredMethod("release");
                releaseMethod.setAccessible(true);
                releaseMethod.invoke(section);
            }
        } catch (Exception e) {
            LOGGER.debug("[VoxyMap] Error getting section data at {},{},{}: {}", secX, secY, secZ, e.getMessage());
            return null;
        }
    }

    /**
     * Get the Mapper object from a WorldEngine.
     */
    public static Object getMapper(Object worldEngine) {
        if (!ensureInit() || worldEngine == null) return null;
        try {
            if (getMapperMethod != null) {
                return getMapperMethod.invoke(worldEngine);
            }
            return worldEngineMapperfField.get(worldEngine);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get a BlockState from Voxy's Mapper by block ID.
     */
    public static BlockState getBlockState(Object mapper, int blockId) {
        if (!ensureInit() || mapper == null || blockId <= 0) return null;
        try {
            return (BlockState) getBlockStateMethod.invoke(mapper, blockId);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the 3D index into a WorldSection's data array.
     * Formula: ((y & 31) << 10) | ((z & 31) << 5) | (x & 31)
     */
    public static int getSectionIndex(int x, int y, int z) {
        return ((y & 31) << 10) | ((z & 31) << 5) | (x & 31);
    }
}
