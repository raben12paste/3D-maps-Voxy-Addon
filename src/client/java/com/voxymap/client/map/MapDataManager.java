package com.voxymap.client.map;

import com.voxymap.client.VoxyMapClient;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages map data by scanning Voxy's LOD sections in background threads.
 * Builds an ARGB pixel array representing the top-down view of the world.
 *
 * Uses LOD level 1 for map rendering (coarser but covers more area).
 * Each pixel = 1 LOD-section column = 32 blocks wide at level 0, 64 at level 1.
 */
public class MapDataManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("voxymap.data");

    // Map resolution in "map tiles"
    // Each tile covers 32 blocks at LOD lvl=0, or 64 blocks at LOD lvl=1
    public static final int MAP_TILES_RADIUS = 256; // tiles in each direction from center
    public static final int MAP_SIZE = MAP_TILES_RADIUS * 2; // total map width/height in tiles

    // LOD level to use for map rendering
    // Level 0 = highest detail (32x32x32 blocks per section)
    // Level 1 = lower detail  (64x64x64 blocks per section)
    private static final int LOD_LEVEL = 1;
    private static final int BLOCKS_PER_TILE = 32 * (1 << LOD_LEVEL); // 32 << 1 = 64 blocks per tile at lvl=1

    // The map pixel buffer [y][x] = ARGB color, null means not yet scanned
    // null = not scanned, 0x00000000 = scanned but empty, other = color
    private final int[] mapPixels = new int[MAP_SIZE * MAP_SIZE];
    private final boolean[] tileScanned = new boolean[MAP_SIZE * MAP_SIZE];
    private final boolean[] tileQueued = new boolean[MAP_SIZE * MAP_SIZE];
    private final ConcurrentMap<Long, Integer> tileColors = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Integer> tileHeights = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Integer> vanillaTileColors = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Integer> vanillaTileHeights = new ConcurrentHashMap<>();
    private final Set<Long> queuedTileKeys = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<Long, Integer> missingRetryAfterTick = new ConcurrentHashMap<>();

    // Map center in block coordinates
    private volatile int centerBlockX = 0;
    private volatile int centerBlockZ = 0;

    // Background executor
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final AtomicInteger totalQueuedTiles = new AtomicInteger(0);
    private final AtomicInteger coloredTiles = new AtomicInteger(0);
    private final AtomicInteger emptyTiles = new AtomicInteger(0);
    private final AtomicInteger missingDataTiles = new AtomicInteger(0);
    private final AtomicInteger failedTiles = new AtomicInteger(0);
    private final AtomicInteger sectionHits = new AtomicInteger(0);
    private final AtomicInteger sectionMisses = new AtomicInteger(0);
    private final AtomicInteger mapperMisses = new AtomicInteger(0);
    private final AtomicInteger vanillaColoredTiles = new AtomicInteger(0);
    private volatile int lastQueuedBatch = 0;

    // Tick counter for throttling
    private volatile int tickCounter = 0;
    private static final int SCAN_INTERVAL_TICKS = 20; // scan every second
    private static final int STATS_INTERVAL_TICKS = 100;
    private static final int MAX_PENDING_TASKS = 64;
    private static final int MAX_QUEUED_PER_SCAN = 64;
    private static final int MISSING_RETRY_DELAY_TICKS = 200;

    // Cached Voxy world engine reference
    private Object cachedWorldEngine = null;
    private Object cachedMapper = null;
    private long lastEngineCheckTick = 0;

    public MapDataManager() {
        // Initialize map as "not scanned"
        java.util.Arrays.fill(tileScanned, false);
        java.util.Arrays.fill(mapPixels, 0x00000000);

        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "VoxyMap-Scanner");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        LOGGER.info("[VoxyMap] MapDataManager initialized, map size: {}x{} tiles ({} blocks/tile)",
                MAP_SIZE, MAP_SIZE, BLOCKS_PER_TILE);
    }

    /**
     * Called every client tick. Updates center position and queues scanning tasks.
     */
    public void tick(Minecraft client) {
        if (!running.get()) return;
        tickCounter++;

        var player = client.player;
        if (player == null) return;
        if (client.level != null && isUnsupportedDimension(client.level.dimension().identifier().toString())) {
            return;
        }

        int playerBlockX = (int) player.getX();
        int playerBlockZ = (int) player.getZ();

        // Update center
        centerBlockX = playerBlockX;
        centerBlockZ = playerBlockZ;

        // Refresh world engine reference periodically
        if (tickCounter - lastEngineCheckTick > 40) {
            lastEngineCheckTick = tickCounter;
            cachedWorldEngine = VoxyBridge.getActiveWorldEngine(client);
            if (cachedWorldEngine != null) {
                cachedMapper = VoxyBridge.getMapper(cachedWorldEngine);
            }
        }

        // Queue scanning tasks every SCAN_INTERVAL_TICKS
        if (tickCounter % SCAN_INTERVAL_TICKS == 0 && cachedWorldEngine != null && cachedMapper != null && pendingTasks.get() < MAX_PENDING_TASKS) {
            queueScanAroundPlayer(playerBlockX, playerBlockZ);
        }

        if (VoxyMapClient.debugLogging && tickCounter % STATS_INTERVAL_TICKS == 0) {
            LOGGER.info("[VoxyMap] scan status: voxy={}, engine={}, mapper={}, pending={}, inFlight={}, cached={}, vanillaCached={}, lastQueued={}, colored={}, vanillaColored={}, empty={}, missingData={}, failed={}, sectionHits={}, sectionMisses={}, mapperMisses={}",
                    VoxyBridge.isVoxyPresent(), cachedWorldEngine != null, cachedMapper != null, pendingTasks.get(), queuedTileKeys.size(), tileColors.size(), vanillaTileColors.size(), lastQueuedBatch,
                    coloredTiles.get(), vanillaColoredTiles.get(), emptyTiles.get(), missingDataTiles.get(), failedTiles.get(), sectionHits.get(), sectionMisses.get(), mapperMisses.get());
        }
    }

    /**
     * Queue scanning tasks around the player's current position.
     * Only scans tiles that haven't been scanned yet.
     */
    private void queueScanAroundPlayer(int playerX, int playerZ) {
        // Convert player position to tile coordinates
        int centerTileX = blockToTile(playerX);
        int centerTileZ = blockToTile(playerZ);

        // Scan in expanding rings around player (prioritize nearby tiles)
        int scanRadius = 16; // scan 16 tiles in each direction
        Object worldEngine = cachedWorldEngine;
        Object mapper = cachedMapper;

        if (worldEngine == null || mapper == null) return;

        int queued = 0;
        scan:
        for (int r = 0; r <= scanRadius; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (queued >= MAX_QUEUED_PER_SCAN || pendingTasks.get() >= MAX_PENDING_TASKS) break scan;
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue; // only the ring

                    int tileX = centerTileX + dx;
                    int tileZ = centerTileZ + dz;

                    long tileKey = packTile(tileX, tileZ);
                    if (tileColors.containsKey(tileKey)) continue;
                    Integer retryAfter = missingRetryAfterTick.get(tileKey);
                    if (retryAfter != null) {
                        if (retryAfter > tickCounter) continue;
                        missingRetryAfterTick.remove(tileKey, retryAfter);
                    }
                    if (!queuedTileKeys.add(tileKey)) continue;

                    int finalTileX = tileX;
                    int finalTileZ = tileZ;
                    long finalTileKey = tileKey;
                    int attemptTick = tickCounter;
                    pendingTasks.incrementAndGet();
                    totalQueuedTiles.incrementAndGet();
                    queued++;
                    executor.submit(() -> {
                        try {
                            ScanResult result = scanTile(worldEngine, mapper, finalTileX, finalTileZ, attemptTick);
                            if (result == ScanResult.COLORED) coloredTiles.incrementAndGet();
                            if (result == ScanResult.EMPTY) emptyTiles.incrementAndGet();
                            if (result == ScanResult.MISSING_DATA) missingDataTiles.incrementAndGet();
                            if (result == ScanResult.FAILED) failedTiles.incrementAndGet();
                        } finally {
                            queuedTileKeys.remove(finalTileKey);
                            pendingTasks.decrementAndGet();
                        }
                    });
                }
            }
        }
        lastQueuedBatch = queued;
    }

    private static boolean isUnsupportedDimension(String dimension) {
        return "minecraft:the_nether".equals(dimension) || "minecraft:the_end".equals(dimension);
    }

    /**
     * Scan a single LOD tile and extract its surface color.
     * A "tile" at LOD level 1 covers 64x64 blocks.
     */
    private ScanResult scanTile(Object worldEngine, Object mapper, int tileX, int tileZ, int attemptTick) {
        long tileKey = packTile(tileX, tileZ);
        if (tileColors.containsKey(tileKey)) return ScanResult.EMPTY;

        SurfaceScan scan = new SurfaceScan();

        // Prefer the configured LOD tile, then fall back to finer child sections.
        for (int lod = LOD_LEVEL; lod >= 0; lod--) {
            scanLodTile(worldEngine, mapper, tileX, tileZ, lod, scan);
            if (scan.bestColor != 0) break;
        }

        if (!scan.sawSection) {
            missingRetryAfterTick.put(tileKey, attemptTick + MISSING_RETRY_DELAY_TICKS);
            return ScanResult.MISSING_DATA;
        }

        tileColors.put(tileKey, scan.bestColor);
        tileHeights.put(tileKey, scan.bestHeight);
        missingRetryAfterTick.remove(tileKey);
        return scan.bestColor == 0 ? ScanResult.EMPTY : ScanResult.COLORED;
    }

    private void scanLodTile(Object worldEngine, Object mapper, int tileX, int tileZ, int lod, SurfaceScan scan) {
        int childScale = 1 << (LOD_LEVEL - lod);
        int baseSecX = tileX * childScale;
        int baseSecZ = tileZ * childScale;
        int sectionSize = 32 << lod;
        int minSecY = Math.floorDiv(-64, sectionSize);
        int maxSecY = Math.floorDiv(319, sectionSize);

        for (int secY = maxSecY; secY >= minSecY; secY--) {
            for (int childZ = 0; childZ < childScale; childZ++) {
                for (int childX = 0; childX < childScale; childX++) {
                    scanSection(worldEngine, mapper, lod, baseSecX + childX, secY, baseSecZ + childZ, sectionSize, scan);
                }
            }
            if (scan.bestColor != 0) return;
        }
    }

    private void scanSection(Object worldEngine, Object mapper, int lod, int secX, int secY, int secZ, int sectionSize, SurfaceScan scan) {
        long[] data = VoxyBridge.getSectionData(worldEngine, lod, secX, secY, secZ);
        if (data == null) {
            sectionMisses.incrementAndGet();
            return;
        }

        scan.sawSection = true;
        sectionHits.incrementAndGet();
        int sectionBaseY = secY * sectionSize;
        int step = 1 << lod;

        for (int localY = 31; localY >= 0; localY--) {
            int worldY = sectionBaseY + localY * step;
            if (worldY <= scan.bestHeight) return;

            for (int localZ = 0; localZ < 32; localZ++) {
                for (int localX = 0; localX < 32; localX++) {
                    int dataIdx = VoxyBridge.getSectionIndex(localX, localY, localZ);
                    if (dataIdx >= data.length) continue;
                    long entry = data[dataIdx];

                    if (VoxyBridge.isAir(entry)) continue;

                    int blockId = VoxyBridge.getBlockId(entry);
                    if (blockId <= 0) continue;

                    BlockState state = VoxyBridge.getBlockState(mapper, blockId);
                    if (state == null) {
                        mapperMisses.incrementAndGet();
                        continue;
                    }
                    if (state.isAir()) continue;

                    int rawColor = BlockColorTable.getMapColor(state);
                    if ((rawColor & 0xFF000000) != 0) {
                        scan.bestHeight = worldY;
                        scan.bestColor = BlockColorTable.applyHeightShading(rawColor, worldY, 63);
                        return;
                    }
                }
            }
        }
    }

    /**
     * Force re-scan of all tiles (e.g., when opening map or player teleports far).
     */
    public void resetScan() {
        synchronized (this) {
            java.util.Arrays.fill(tileScanned, false);
            java.util.Arrays.fill(tileQueued, false);
        }
        java.util.Arrays.fill(mapPixels, 0x00000000);
        tileColors.clear();
        tileHeights.clear();
        vanillaTileColors.clear();
        vanillaTileHeights.clear();
        queuedTileKeys.clear();
        missingRetryAfterTick.clear();
        cachedWorldEngine = null;
        cachedMapper = null;
        coloredTiles.set(0);
        emptyTiles.set(0);
        missingDataTiles.set(0);
        failedTiles.set(0);
        sectionHits.set(0);
        sectionMisses.set(0);
        mapperMisses.set(0);
        vanillaColoredTiles.set(0);
        totalQueuedTiles.set(0);
        lastQueuedBatch = 0;
    }

    /**
     * Get the ARGB pixel array for rendering.
     * Array index: z * MAP_SIZE + x, where x and z are in [0, MAP_SIZE)
     */
    public int[] getMapPixels() {
        return mapPixels;
    }

    public int getMapSize() {
        return MAP_SIZE;
    }

    public int getCenterBlockX() {
        return centerBlockX;
    }

    public int getCenterBlockZ() {
        return centerBlockZ;
    }

    public int getBlocksPerTile() {
        return BLOCKS_PER_TILE;
    }

    public String getDebugStatus() {
        return String.format("Voxy:%s Engine:%s Mapper:%s Pending:%d Cached:%d Local:%d Queued:%d Color:%d Empty:%d Missing:%d Sec:%d/%d Map:%d blk/tile",
                VoxyBridge.isVoxyPresent() ? "yes" : "no",
                cachedWorldEngine != null ? "yes" : "no",
                cachedMapper != null ? "yes" : "no",
                pendingTasks.get(),
                tileColors.size(),
                vanillaTileColors.size(),
                totalQueuedTiles.get(),
                coloredTiles.get(),
                emptyTiles.get(),
                missingDataTiles.get(),
                sectionHits.get(),
                sectionMisses.get(),
                BLOCKS_PER_TILE);
    }

    /**
     * Convert block coordinate to tile coordinate.
     */
    public int blockToTile(int blockCoord) {
        return Math.floorDiv(blockCoord, BLOCKS_PER_TILE);
    }

    /**
     * Convert tile coordinate to map X index (centered on map center).
     */
    private int tileToMapX(int tileX) {
        int centerTileX = blockToTile(centerBlockX);
        return tileX - centerTileX + MAP_TILES_RADIUS;
    }

    private int tileToMapZ(int tileZ) {
        int centerTileZ = blockToTile(centerBlockZ);
        return tileZ - centerTileZ + MAP_TILES_RADIUS;
    }

    /**
     * Get pixel color at a given block position.
     */
    public int getColorAt(int blockX, int blockZ) {
        int tileX = blockToTile(blockX);
        int tileZ = blockToTile(blockZ);
        return tileColors.getOrDefault(packTile(tileX, tileZ), 0);
    }

    public int getColorAt(Minecraft client, int blockX, int blockZ) {
        int tileX = blockToTile(blockX);
        int tileZ = blockToTile(blockZ);
        return getColorForTile(client, tileX, tileZ);
    }

    public int getColorForTile(Minecraft client, int tileX, int tileZ) {
        return getColorForTile(client, tileX, tileZ, true);
    }

    public int getColorForTile(Minecraft client, int tileX, int tileZ, boolean allowVanillaFallback) {
        long tileKey = packTile(tileX, tileZ);
        Integer voxyColor = tileColors.get(tileKey);
        if (voxyColor != null && voxyColor != 0) return voxyColor;
        if (!allowVanillaFallback) return 0;

        Integer vanillaColor = vanillaTileColors.get(tileKey);
        if (vanillaColor != null) return vanillaColor;

        int computed = sampleLoadedWorldTile(client, tileX, tileZ);
        vanillaTileColors.put(tileKey, computed);
        if (computed != 0) vanillaColoredTiles.incrementAndGet();
        return computed;
    }

    public int getHeightForTile(int tileX, int tileZ) {
        long tileKey = packTile(tileX, tileZ);
        Integer voxyHeight = tileHeights.get(tileKey);
        if (voxyHeight != null) return voxyHeight;
        Integer vanillaHeight = vanillaTileHeights.get(tileKey);
        return vanillaHeight != null ? vanillaHeight : 63;
    }

    public Integer getKnownSurfaceY(Minecraft client, int blockX, int blockZ) {
        int tileX = blockToTile(blockX);
        int tileZ = blockToTile(blockZ);
        long tileKey = packTile(tileX, tileZ);

        if (client != null && client.level != null && client.level.hasChunk(blockX >> 4, blockZ >> 4)) {
            return client.level.getHeight(Heightmap.Types.WORLD_SURFACE, blockX, blockZ);
        }

        Integer voxyHeight = tileHeights.get(tileKey);
        if (voxyHeight != null) return voxyHeight;

        Integer vanillaHeight = vanillaTileHeights.get(tileKey);
        if (vanillaHeight != null) return vanillaHeight;

        return null;
    }

    private int sampleLoadedWorldTile(Minecraft client, int tileX, int tileZ) {
        if (client == null || client.level == null) return 0;

        long tileKey = packTile(tileX, tileZ);
        int blockX = tileX * BLOCKS_PER_TILE + BLOCKS_PER_TILE / 2;
        int blockZ = tileZ * BLOCKS_PER_TILE + BLOCKS_PER_TILE / 2;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(blockX, 0, blockZ);

        int minY = client.level.getMinY();
        int maxY = client.level.getMaxY();
        for (int y = maxY - 1; y >= minY; y--) {
            pos.set(blockX, y, blockZ);
            BlockState state = client.level.getBlockState(pos);
            if (state == null || state.isAir()) continue;

            int rawColor = BlockColorTable.getMapColor(state);
            if ((rawColor & 0xFF000000) != 0) {
                vanillaTileHeights.put(tileKey, y);
                return BlockColorTable.applyHeightShading(rawColor, y, 63);
            }
        }
        return 0;
    }

    private static long packTile(int tileX, int tileZ) {
        return (Integer.toUnsignedLong(tileX) << 32) | Integer.toUnsignedLong(tileZ);
    }

    public void close() {
        running.set(false);
        executor.shutdownNow();
    }

    private enum ScanResult {
        COLORED,
        EMPTY,
        MISSING_DATA,
        FAILED
    }

    private static final class SurfaceScan {
        private boolean sawSection = false;
        private int bestColor = 0x00000000;
        private int bestHeight = Integer.MIN_VALUE;
    }
}
