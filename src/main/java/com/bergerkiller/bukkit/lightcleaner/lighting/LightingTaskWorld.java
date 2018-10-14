package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashMap;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.reflection.net.minecraft.server.NMSRegionFile;
import com.bergerkiller.reflection.net.minecraft.server.NMSRegionFileCache;

import org.bukkit.Chunk;
import org.bukkit.World;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

public class LightingTaskWorld implements LightingTask {
    private static final int ASSUMED_CHUNKS_PER_REGION = 34 * 34;
    private static final double ASSUMED_AVG_REGION_CHUNK_RATIO = 0.75;
    private final World world;
    private final File regionFolder;
    private final LongHashMap<WorldRegion> regions;
    private final LongHashSet loadedChunks;
    private final LongHashSet chunks;
    private int chunkCount;
    private boolean aborted;

    public LightingTaskWorld(World world, File regionFolder) {
        this.world = world;
        this.regionFolder = regionFolder;
        this.regions = new LongHashMap<WorldRegion>();
        this.aborted = false;

        // Obtain the region file names
        Set<File> addedRegionFiles = new HashSet<File>();
        if (this.regionFolder != null) {
            String[] regionFileNames = this.regionFolder.list();
            for (String regionFileName : regionFileNames) {
                File file = new File(regionFolder, regionFileName);
                if (!file.isFile() || !file.exists() || file.length() < 4096) {
                    continue;
                }
                if (addRegionFile(file)) {
                    addedRegionFiles.add(file);
                }
            }
        }
        // Detect any addition Region Files in the cache that are not yet saved
        // Synchronized, since we are going to iterate the files here...unsafe not to do so!
        synchronized (NMSRegionFileCache.T.getType()) {
            for (File file : NMSRegionFileCache.FILES.keySet()) {
                if (addedRegionFiles.add(file)) {
                    addRegionFile(file);
                }
            }
        }

        // Add all loaded chunks for a further check-up later on
        Collection<Chunk> loadedChunks = WorldUtil.getChunks(world);
        this.loadedChunks = new LongHashSet(loadedChunks.size());
        for (Chunk chunk : loadedChunks) {
            this.loadedChunks.add(chunk.getX(), chunk.getZ());
        }

        // All done, finish off by initializing a new buffer to store all the chunks in
        this.chunkCount = this.regions.size() * ASSUMED_CHUNKS_PER_REGION;
        this.chunks = new LongHashSet((int) (ASSUMED_AVG_REGION_CHUNK_RATIO * this.chunkCount));
    }

    public boolean addRegionFile(File file) {
        String regionFileName = file.getName();
        String[] parts = regionFileName.split("\\.");
        if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("mca")) {
            return false;
        }
        // Obtain the chunk offset of this region file
        try {
            int rx = Integer.parseInt(parts[1]) << 5;
            int rz = Integer.parseInt(parts[2]) << 5;
            this.regions.put(MathUtil.longHashToLong(rx, rz), new WorldRegion(file, rx, rz));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public boolean containsChunk(int chunkX, int chunkZ) {
        // This task always contains all chunks
        return true;
    }

    @Override
    public int getChunkCount() {
        return chunkCount;
    }

    @Override
    public void syncTick() {
        // Nothing happens here...
    }

    @Override
    public void process() {
        // Stop
        if (this.aborted) {
            return;
        }

        // Start loading all regions and all chunks contained in these regions
        int dx, dz;
        Object regionFile;
        int regionChunkCount;
        for (WorldRegion region : this.regions.getValues()) {
            regionChunkCount = 0;
            // Is it contained in the cache? If so, use that to obtain coordinates
            if ((regionFile = NMSRegionFileCache.FILES.get(region.file)) == null) {
                // Start a new file stream to read the coordinates
                // Creating a new region file is too slow and results in memory leaks
                try {
                    DataInputStream stream = new DataInputStream(new FileInputStream(region.file));
                    try {
                        for (int coordIndex = 0; coordIndex < 1024 && !this.aborted; coordIndex++) {
                            if (stream.readInt() > 0) {
                                // Convert coordinate to dx/dz
                                // coordIndex = dx + (dz << 5)
                                dx = coordIndex & 31;
                                dz = coordIndex >> 5;

                                // Add
                                chunks.add(region.rx + dx, region.rz + dz);
                                regionChunkCount++;
                            }
                        }
                    } finally {
                        stream.close();
                    }
                } catch (IOException ex) {
                }
            } else {
                // Obtain all generated chunks in this region file
                for (dx = 0; dx < 32 && !this.aborted; dx++) {
                    for (dz = 0; dz < 32; dz++) {
                        if (NMSRegionFile.exists.invoke(regionFile, dx, dz)) {
                            // Region file exists - add it
                            chunks.add(region.rx + dx, region.rz + dz);
                            regionChunkCount++;
                        }
                    }
                }
            }
            // Update chunk count to subtract the missing chunks
            chunkCount -= ASSUMED_CHUNKS_PER_REGION - regionChunkCount;

            // Abort handling
            if (this.aborted) {
                return;
            }
        }

        // Now, for all loaded chunks remaining, add the region files they are in
        // Ignore pre-existing chunks, since they were already added above here (with region)
        if (!loadedChunks.isEmpty()) {
            int cx, cz, rx, rz;
            long regionKey;
            for (long loadedChunk : loadedChunks.toArray()) {
                // Add, if already added, ignore it (it was stored)
                if (!chunks.add(loadedChunk)) {
                    continue;
                }
                cx = MathUtil.longHashLsw(loadedChunk);
                cz = MathUtil.longHashMsw(loadedChunk);
                rx = (cx >> 5);
                rz = (cz >> 5);
                regionKey = MathUtil.longHashToLong(rx, rz);
                if (!regions.contains(regionKey)) {
                    regions.put(regionKey, new WorldRegion(null, rx, rz));
                }
            }
        }

        // Abort handling
        if (this.aborted) {
            return;
        }

        // We now know of all the regions to be processed, convert all of them into tasks
        // Use a slightly larger area to avoid cross-region errors
        for (WorldRegion region : regions.getValues()) {
            // Put the coordinates that are available
            final LongHashSet buffer = new LongHashSet(2000);
            for (dx = -1; dx < 33; dx++) {
                for (dz = -1; dz < 33; dz++) {
                    if (chunks.contains(region.rx + dx, region.rz + dz)) {
                        buffer.add(region.rx + dx, region.rz + dz);
                    }
                }
            }

            // Reduce count, schedule and clear the buffer
            this.chunkCount -= buffer.size();
            LightingService.schedule(world, buffer);

            // Abort handling
            if (this.aborted) {
                return;
            }
        }
    }

    @Override
    public void abort() {
        this.aborted = true;
    }

    private static class WorldRegion {
        public final File file;
        public final int rx, rz;

        public WorldRegion(File file, int rx, int rz) {
            this.file = file;
            this.rx = rx;
            this.rz = rz;
        }
    }
}
