package com.bergerkiller.bukkit.lightcleaner.util;

import java.io.File;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import org.bukkit.Chunk;
import org.bukkit.World;

import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashMap;
import com.bergerkiller.reflection.net.minecraft.server.NMSRegionFileCache;

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
     * Creates a region information mapping of all existing chunks of a world
     * 
     * @param world
     * @return region info map
     */
    public static RegionInfoMap create(World world) {
        LongHashMap<RegionInfo> regions = new LongHashMap<RegionInfo>();

        // Obtain the region file names
        Set<File> regionFiles = new HashSet<File>();
        File regionFolder = WorldUtil.getWorldRegionFolder(world.getName());
        if (regionFolder != null) {
            String[] regionFileNames = regionFolder.list();
            for (String regionFileName : regionFileNames) {
                File file = new File(regionFolder, regionFileName);
                if (file.isFile() && file.exists() && file.length() >= 4096) {
                    regionFiles.add(file);
                }
            }
        }

        // Detect any addition Region Files in the cache that are not yet saved
        // Synchronized, since we are going to iterate the files here...unsafe not to do so!
        synchronized (NMSRegionFileCache.T.getType()) {
            regionFiles.addAll(NMSRegionFileCache.FILES.keySet());
        }

        // Go by all found region files and parse the filename to deduce the region coordinates
        for (File regionFile : regionFiles) {
            String regionFileName = regionFile.getName();

            // Parse r.0.0.mca
            // Step one: verify starts with r. and ends with .mca
            if (!regionFileName.startsWith("r.") || !regionFileName.endsWith(".mca")) {
                continue;
            }

            // Find dot between coordinates
            int coord_sep_idx = regionFileName.indexOf('.', 2);
            if (coord_sep_idx == -1 || coord_sep_idx >= regionFileName.length() - 4) {
                continue;
            }

            // Parse the two numbers as integers - should succeed
            try {
                int rx = Integer.parseInt(regionFileName.substring(2, coord_sep_idx));
                int rz = Integer.parseInt(regionFileName.substring(coord_sep_idx + 1, regionFileName.length() - 4));
                long key = MathUtil.longHashToLong(rx, rz);
                if (!regions.contains(key)) {
                    regions.put(key, new RegionInfo(world, regionFile, rx, rz));
                }
            } catch (Exception ex) {
            }
        }

        // Go by all loaded chunks, and add the regions they are in as well
        // This may happen (?) when a chunk was recently generated but not yet saved to disk
        // The RegionFileCache should catch those, but you never know...
        for (Chunk chunk : WorldUtil.getChunks(world)) {
            int rx = chunk.getX() >> 5;
            int rz = chunk.getZ() >> 5;
            long key = MathUtil.longHashToLong(rx, rz);
            RegionInfo info = regions.get(key);
            if (info == null) {
                info = new RegionInfo(world, null, rx, rz);
                regions.put(key, info);
            }
            info.addChunk(chunk.getX(), chunk.getZ());
        }

        return new RegionInfoMap(world, regions);
    }

}
