package com.backrooms.mod.world;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the X,Z positions of all null zone columns per dimension.
 * This is an in-memory map only — null zones are regenerated deterministically
 * from the chunk seed on each world load (see NullZoneEventHandler#onChunkLoad).
 *
 * Thread-safe because chunk loading can happen off the main thread.
 */
public class NullZoneManager {

    /** Map from DimensionKey -> Set of (x,z) positions (stored as BlockPos with y=0). */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> NULL_ZONE_COLUMNS =
            new ConcurrentHashMap<>();

    /**
     * Registers a null zone column at the given (x, ?, z) position.
     * Only X and Z are used; Y is ignored.
     */
    public static void register(ResourceKey<Level> dimension, BlockPos xzPos) {
        NULL_ZONE_COLUMNS
                .computeIfAbsent(dimension, k -> ConcurrentHashMap.newKeySet())
                .add(new BlockPos(xzPos.getX(), 0, xzPos.getZ()));
    }

    /**
     * Returns true if the given (x, z) is a null zone column in the specified dimension.
     */
    public static boolean isNullZone(ResourceKey<Level> dimension, int x, int z) {
        Set<BlockPos> columns = NULL_ZONE_COLUMNS.get(dimension);
        if (columns == null) return false;
        return columns.contains(new BlockPos(x, 0, z));
    }

    /**
     * Returns all registered null zone X,Z positions for a dimension.
     */
    public static Set<BlockPos> getAll(ResourceKey<Level> dimension) {
        return NULL_ZONE_COLUMNS.getOrDefault(dimension, Collections.emptySet());
    }

    /**
     * Clears all null zone data (used on world unload).
     */
    public static void clear() {
        NULL_ZONE_COLUMNS.clear();
    }
}
