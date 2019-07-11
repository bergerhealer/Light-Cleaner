package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService.ScheduleArguments;
import com.bergerkiller.bukkit.lightcleaner.util.RegionInfo;
import com.bergerkiller.bukkit.lightcleaner.util.RegionInfoMap;

import org.bukkit.World;

public class LightingTaskWorld implements LightingTask {
    private static final int ASSUMED_CHUNKS_PER_REGION = 34 * 34;
    private final World world;
    private volatile RegionInfoMap regions = null;
    private final Object regionsWaitObject = new Object();
    private volatile int regionCountLoaded;
    private volatile int chunkCount;
    private volatile boolean aborted;
    private LightingService.ScheduleArguments options = new LightingService.ScheduleArguments();

    public LightingTaskWorld(World world) {
        this.world = world;
        this.regionCountLoaded = 0;
        this.aborted = false;
        this.chunkCount = 0;
    }

    @Override
    public World getWorld() {
        return this.world;
    }

    @Override
    public int getChunkCount() {
        return chunkCount;
    }

    @Override
    public String getStatus() {
        if (regions == null) {
            return "Reading available regions from world " + getWorld().getName();
        } else {
            return "Reading available chunks from world " + getWorld().getName() + " (region " + (regionCountLoaded+1) + "/" + regions.getRegionCount() + ")";
        }
    }

    @Override
    public void syncTick() {
        // Initialize the regions map
        if (this.regions == null) {
            synchronized (this.regionsWaitObject) {
                if (this.options.getLoadedChunksOnly()) {
                    this.regions = RegionInfoMap.createLoaded(this.getWorld());
                    this.regionCountLoaded = this.regions.getRegionCount();
                    this.chunkCount = 0;
                    for (RegionInfo region : this.regions.getRegions()) {
                        this.chunkCount += region.getChunkCount();
                    }
                } else {
                    this.regions = RegionInfoMap.create(this.getWorld());
                    this.regionCountLoaded = 0;
                    this.chunkCount = this.regions.getRegionCount() * ASSUMED_CHUNKS_PER_REGION;
                }
                this.regionsWaitObject.notifyAll();
            }
        }
    }

    @Override
    public void process() {
        // Wait until regions are loaded synchronously
        synchronized (this.regionsWaitObject) {
            while (this.regions == null) {
                try {
                    this.regionsWaitObject.wait(1000);
                } catch (InterruptedException e) {}

                if (this.aborted) {
                    return;
                }
            }
        }

        // Start loading all chunks contained in the regions
        if (!this.options.getLoadedChunksOnly()) {
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
        }

        // We now know of all the regions to be processed, convert all of them into tasks
        // Use a slightly larger area to avoid cross-region errors
        for (RegionInfo region : regions.getRegions()) {
            // Abort handling
            if (this.aborted) {
                return;
            }

            // If empty, skip
            if (region.getChunkCount() == 0) {
                continue;
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
            LightingTaskBatch batch_task = new LightingTaskBatch(this.getWorld(), buffer);
            batch_task.applyOptions(this.options);
            LightingService.schedule(batch_task);
        }
    }

    @Override
    public void abort() {
        this.aborted = true;
        synchronized (this.regionsWaitObject) {
            this.regionsWaitObject.notifyAll();
        }
    }

    @Override
    public void applyOptions(ScheduleArguments args) {
        this.options = args;
    }

    @Override
    public boolean canSave() {
        return false;
    }

}
