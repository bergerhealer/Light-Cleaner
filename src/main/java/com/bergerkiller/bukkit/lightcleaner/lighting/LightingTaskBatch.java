package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet.LongIterator;

import org.bukkit.Chunk;
import org.bukkit.World;

/**
 * Contains all the chunk coordinates that have to be fixed,
 * and handles the full process of this fixing.
 * It is literally a batch of chunks being processed.
 */
public class LightingTaskBatch implements LightingTask {
    public static final int MAX_PROCESSING_TICK_TIME = 30; // max ms per tick processing
    private static boolean DEBUG_LOG = false; // logs performance stats
    public final World world;
    private LightingChunk[] chunks = null;
    private final LongHashSet chunksCoords;
    private final Object waitObject = new Object();
    private Runnable activeTask = null;
    private boolean done = false;
    private boolean aborted = false;

    public LightingTaskBatch(World world, LongHashSet chunkCoordinates) {
        this.world = world;
        this.chunksCoords = chunkCoordinates;
    }

    @Override
    public World getWorld() {
        return world;
    }

    public long[] getChunks() {
        synchronized (this.chunksCoords) {
            return chunksCoords.toArray();
        }
    }

    @Override
    public int getChunkCount() {
        if (this.chunks == null) {
            synchronized (this.chunksCoords) {
                return this.done ? 0 : this.chunksCoords.size();
            }
        }
        int faults = 0;
        for (LightingChunk chunk : this.chunks) {
            if (chunk.hasFaults()) {
                faults++;
            }
        }
        return faults;
    }

    @Override
    public String getStatus() {
        int count = 0;
        long cx = 0;
        long cz = 0;
        if (this.chunks == null) {
            synchronized (this.chunksCoords) {
                LongIterator iter = this.chunksCoords.longIterator();
                while (iter.hasNext()) {
                    long chunk = iter.next();
                    cx += MathUtil.longHashMsw(chunk);
                    cz += MathUtil.longHashLsw(chunk);
                    count++;
                }
            }
        } else {
            for (LightingChunk chunk : this.chunks) {
                cx += chunk.chunkX;
                cz += chunk.chunkZ;
                count++;
            }
        }
        if (count > 0) {
            cx /= count;
            cz /= count;
        }
        return "Cleaning " + count + " chunks near x=" + (cx*16) + " z=" + (cz*16);
    }

    @Override
    public boolean containsChunk(int chunkX, int chunkZ) {
        synchronized (this.chunksCoords) {
            return this.chunksCoords.contains(chunkX, chunkZ);
        }
    }

    @Override
    public void process() {
        // Initialize lighting chunks
        LightingChunk[] chunks_new = new LightingChunk[this.chunksCoords.size()];
        this.done = false;
        int chunkIdx = 0;
        LongIterator coordIter = this.chunksCoords.longIterator();
        while (coordIter.hasNext()) {
            long longCoord = coordIter.next();
            int x = MathUtil.longHashMsw(longCoord);
            int z = MathUtil.longHashLsw(longCoord);
            chunks_new[chunkIdx++] = new LightingChunk(x, z);
            if (this.aborted) {
                return;
            }
        }
        this.chunks = chunks_new;

        // Accessibility
        // Load the chunk with data
        for (LightingChunk lc : this.chunks) {
            for (LightingChunk neigh : this.chunks) {
                lc.notifyAccessible(neigh);
            }
        }

        // Load
        startLoading();
        waitForCompletion();
        if (this.aborted) {
            return;
        }
        // Fix
        fix();
        if (this.aborted) {
            return;
        }
        // Apply
        startApplying();
        waitForCompletion();
        if (this.aborted) {
            return;
        }
        this.done = true;
        this.chunks = null;
    }

    @Override
    public void abort() {
        this.aborted = true;
        LightingTaskBatch.this.completed();
    }

    /**
     * Waits the calling thread until a task is completed
     */
    public void waitForCompletion() {
        synchronized (waitObject) {
            try {
                waitObject.wait();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void syncTick() {
        final Runnable t = activeTask;
        if (t != null) {
            t.run();
        }
    }

    private void completed() {
        activeTask = null;
        synchronized (waitObject) {
            waitObject.notifyAll();
        }
    }

    /**
     * Starts loads the chunks that are to be fixed into memory.
     * This is done in several ticks.
     */
    public void startLoading() {
        activeTask = new Runnable() {
            @Override
            public void run() {
                boolean loaded = false;
                long startTime = System.currentTimeMillis();
                // Load chunks
                for (LightingChunk lc : LightingTaskBatch.this.chunks) {
                    if (lc.isFilled) {
                        continue;
                    }
                    loaded = true;
                    lc.fill(world.getChunkAt(lc.chunkX, lc.chunkZ));
                    // Too long?
                    if ((System.currentTimeMillis() - startTime) > MAX_PROCESSING_TICK_TIME) {
                        break;
                    }
                }
                // Nothing loaded, all is done?
                if (!loaded) {
                    LightingTaskBatch.this.completed();
                }
            }
        };
    }

    /**
     * Starts applying the new data to the world.
     * This is done in several ticks.
     */
    public void startApplying() {
        activeTask = new Runnable() {
            @Override
            public void run() {
                boolean applied = false;
                long startTime = System.currentTimeMillis();
                // Apply data to chunks and unload if needed
                for (LightingChunk lc : LightingTaskBatch.this.chunks) {
                    if (lc.isApplied) {
                        continue;
                    }
                    applied = true;
                    Chunk bchunk = world.getChunkAt(lc.chunkX, lc.chunkZ);

                    // Remove chunk from management so that it can be unloaded
                    synchronized (LightingTaskBatch.this.chunksCoords) {
                        LightingTaskBatch.this.chunksCoords.remove(lc.chunkX, lc.chunkZ);
                    }

                    // Save to chunk
                    if (lc.saveToChunk(bchunk)) {
                        // Chunk changed, we need to resend to players
                        boolean isPlayerNear = WorldUtil.queueChunkSend(world, lc.chunkX, lc.chunkZ);
                        if (!isPlayerNear) {
                            world.unloadChunkRequest(lc.chunkX, lc.chunkZ, true);
                        }
                    } else {
                        // No changes. Unload the chunk if no player is nearby.
                        world.unloadChunkRequest(lc.chunkX, lc.chunkZ, true);
                    }

                    // Too long?
                    if ((System.currentTimeMillis() - startTime) > MAX_PROCESSING_TICK_TIME) {
                        break;
                    }
                }
                // Nothing applied, all is done?
                if (!applied) {
                    LightingTaskBatch.this.completed();
                }
            }
        };
    }

    /**
     * Performs the (slow) fixing procedure (call from another thread)
     */
    public void fix() {
        // Initialize light
        for (LightingChunk chunk : chunks) {
            chunk.initLight();
            if (this.aborted) {
                return;
            }
        }

        // Spread (timed, for debug)
        boolean hasFaults;
        long startTime = System.currentTimeMillis();
        int totalLoops = 0;
        do {
            hasFaults = false;
            for (LightingChunk chunk : chunks) {
                int count = chunk.spread();
                totalLoops += count;
                hasFaults |= count > 0;
            }
        } while (hasFaults && !this.aborted);
        this.completed();

        long duration = System.currentTimeMillis() - startTime;
        if (DEBUG_LOG) {
            System.out.println("Processed " + totalLoops + " in " + duration + " ms");
        }
    }
}
