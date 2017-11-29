package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.bases.IntVector2;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet.LongIterator;

import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.Collection;

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

    @Deprecated
    public LightingTaskBatch(World world, Collection<IntVector2> chunkCoordinates) {
        this(world, getCoords(chunkCoordinates));
    }

    public LightingTaskBatch(World world, LongHashSet chunkCoordinates) {
        this.world = world;
        this.chunksCoords = chunkCoordinates;
    }

    private static LongHashSet getCoords(Collection<IntVector2> chunkCoordinates) {
        LongHashSet result = new LongHashSet(chunkCoordinates.size());
        for (IntVector2 coord : chunkCoordinates) {
            result.add(coord.x, coord.z);
        }
        return result;
    }

    @Override
    public World getWorld() {
        return world;
    }

    public LongHashSet getChunks() {
        return chunksCoords;
    }

    @Override
    public int getChunkCount() {
        if (this.chunks == null) {
            return this.done ? 0 : this.chunksCoords.size();
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
    public boolean containsChunk(int chunkX, int chunkZ) {
        return chunksCoords.contains(chunkX, chunkZ);
    }

    @Override
    public void process() {
        // Initialize lighting chunks
        this.done = false;
        this.chunks = new LightingChunk[this.chunksCoords.size()];
        int chunkIdx = 0;
        LongIterator coordIter = this.chunksCoords.longIterator();
        while (coordIter.hasNext()) {
            long longCoord = coordIter.next();
            int x = MathUtil.longHashMsw(longCoord);
            int z = MathUtil.longHashLsw(longCoord);
            this.chunks[chunkIdx++] = new LightingChunk(x, z);
        }

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
        // Fix
        fix();
        // Apply
        startApplying();
        waitForCompletion();
        this.done = true;
        this.chunks = null;
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
                    // Save to chunk
                    lc.saveToChunk(bchunk);
                    // Resend to players
                    boolean isPlayerNear = WorldUtil.queueChunkSend(world, lc.chunkX, lc.chunkZ);
                    // Try to unload if no player near
                    LightingTaskBatch.this.chunksCoords.remove(lc.chunkX, lc.chunkZ);
                    if (!isPlayerNear) {
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
        } while (hasFaults);
        this.completed();

        long duration = System.currentTimeMillis() - startTime;
        if (DEBUG_LOG) {
            System.out.println("Processed " + totalLoops + " in " + duration + " ms");
        }
    }
}
