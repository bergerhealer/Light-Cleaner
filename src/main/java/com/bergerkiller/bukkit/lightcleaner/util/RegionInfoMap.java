package com.bergerkiller.bukkit.lightcleaner.util;

import java.util.Collection;
import java.util.Set;

import org.bukkit.Chunk;
import org.bukkit.World;

import com.bergerkiller.bukkit.common.bases.IntVector2;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashMap;
import com.bergerkiller.bukkit.lightcleaner.LightCleaner;

/**
 * A map of region information
 */
public class RegionInfoMap {
    private final World _world;
    private final LongHashMap<RegionInfo> _regions;

    private RegionInfoMap(World world, LongHashMap<RegionInfo> regions) {
        this._world = world;
        this._regions = regions;
    }

    public World getWorld() {
        return this._world;
    }

    public int getRegionCount() {
        return this._regions.size();
    }

    public Collection<RegionInfo> getRegions() {
        return this._regions.getValues();
    }

    public RegionInfo getRegion(int cx, int cz) {
        return this._regions.get(cx >> 5, cz >> 5);
    }

    /**
     * Gets whether a chunk exists
     * 
     * @param cx
     * @param cz
     * @return True if the chunk exists
     */
    public boolean containsChunk(int cx, int cz) {
        RegionInfo region = getRegion(cx, cz);
        return region != null && region.containsChunk(cx, cz);
    }

    /**
     * Gets whether a chunk, and all its 8 neighbours, exist
     * 
     * @param cx
     * @param cz
     * @return True if the chunk and all its neighbours exist
     */
    public boolean containsChunkAndNeighbours(int cx, int cz) {
        RegionInfo region = getRegion(cx, cz);
        if (region == null) {
            return false;
        }
        for (int dx = -LightCleaner.WORLD_EDGE; dx <= LightCleaner.WORLD_EDGE; dx++) {
            for (int dz = -LightCleaner.WORLD_EDGE; dz <= LightCleaner.WORLD_EDGE; dz++) {
                int mx = cx + dx;
                int mz = cz + dz;
                if (region.isInRange(mx, mz)) {
                    if (!region.containsChunk(mx, mz)) {
                        return false;
                    }
                } else {
                    if (!this.containsChunk(mx, mz)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Creates a region information mapping of all existing chunks of a world
     * that are currently loaded. No further loading is required.
     * 
     * @param world
     * @return region info map
     */
    public static RegionInfoMap createLoaded(World world) {
        LongHashMap<RegionInfo> regions = new LongHashMap<RegionInfo>();
        for (Chunk chunk : world.getLoadedChunks()) {
            int rx = WorldUtil.chunkToRegionIndex(chunk.getX());
            int rz = WorldUtil.chunkToRegionIndex(chunk.getZ());
            RegionInfo info = regions.get(rx, rz);
            if (info == null) {
                info = new RegionInfo(world, rx, rz);
                info.ignoreLoad();
                regions.put(rx, rz, info);
            }

            info.addChunk(chunk.getX(), chunk.getZ());
        }

        return new RegionInfoMap(world, regions);
    }

    /**
     * Creates a region information mapping of all existing chunks of a world
     * 
     * @param world
     * @return region info map
     */
    public static RegionInfoMap create(World world) {
        LongHashMap<RegionInfo> regions = new LongHashMap<RegionInfo>();

        // Obtain the region coordinates
        Set<IntVector2> regionCoordinates = WorldUtil.getWorldRegions(world);

        // For each region, create a RegionInfo entry
        for (IntVector2 region : regionCoordinates) {
            regions.put(region.x, region.z, new RegionInfo(world, region.x, region.z));
        }

        // For all loaded chunks, add those chunks to their region up-front
        // They may not yet have been saved to the region file
        for (Chunk chunk : world.getLoadedChunks()) {
            int rx = WorldUtil.chunkToRegionIndex(chunk.getX());
            int rz = WorldUtil.chunkToRegionIndex(chunk.getZ());
            RegionInfo info = regions.get(rx, rz);
            if (info != null) {
                info.addChunk(chunk.getX(), chunk.getZ());
            }
        }

        return new RegionInfoMap(world, regions);
    }
}
