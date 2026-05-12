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
