package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.lightcleaner.util.RegionInfo;
import com.bergerkiller.bukkit.lightcleaner.util.RegionInfoMap;

import org.bukkit.World;

public class LightingTaskWorld implements LightingTask {
    private static final int ASSUMED_CHUNKS_PER_REGION = 34 * 34;
    private final RegionInfoMap regions;
    private int regionCountLoaded;
    private int chunkCount;
    private boolean aborted;

    public LightingTaskWorld(World world) {
        this.regions = RegionInfoMap.create(world);
        this.regionCountLoaded = 0;
        this.aborted = false;
        this.chunkCount = this.regions.getRegionCount() * ASSUMED_CHUNKS_PER_REGION;
    }

    @Override
    public World getWorld() {
        return this.regions.getWorld();
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
    public String getStatus() {
        return "Reading available chunks from world " + getWorld().getName() + " (region " + (regionCountLoaded+1) + "/" + regions.getRegionCount() + ")";
    }

    @Override
    public void syncTick() {
        // Nothing happens here...
    }

    @Override
    public void process() {
        // Start loading all regions and all chunks contained in these regions
        for (RegionInfo region : this.regions.getRegions()) {
            // Abort handling
            if (this.aborted) {
                return;
            }

            // Load and update stats
            region.load();
            this.chunkCount -= ASSUMED_CHUNKS_PER_REGION - region.getChunkCount();
            this.regionCountLoaded++;
        }

        // We now know of all the regions to be processed, convert all of them into tasks
        // Use a slightly larger area to avoid cross-region errors
        for (RegionInfo region : regions.getRegions()) {
            // Abort handling
            if (this.aborted) {
                return;
            }

            // Reduce count, schedule and clear the buffer
            // Put the coordinates that are available
            final LongHashSet buffer = new LongHashSet(34*34);
            int dx, dz;
            for (dx = -1; dx < 33; dx++) {
                for (dz = -1; dz < 33; dz++) {
                    int cx = region.cx + dx;
                    int cz = region.cz + dz;
                    if (this.regions.containsChunk(cx, cz)) {
                        buffer.add(cx, cz);
                    }
                }
            }

            // Schedule and return amount of chunks
            this.chunkCount -= buffer.size();
            LightingService.schedule(new LightingTaskBatch(this.getWorld(), buffer));
        }
    }

    @Override
    public void abort() {
        this.aborted = true;
    }

}
