package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.AsyncTask;
import com.bergerkiller.bukkit.common.utils.ChunkUtil;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet.LongIterator;
import com.bergerkiller.bukkit.lightcleaner.LightCleaner;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService.ScheduleArguments;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
    private LightingService.ScheduleArguments options = new LightingService.ScheduleArguments();

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
            chunks_new[chunkIdx++] = new LightingChunk(this.world, x, z);
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

        // Check aborted
        if (aborted) {
            return;
        }

        // Asynchronously load all the chunks
        boolean isFullyLoaded;
        do {
            // Get number of chunks currently being loaded
            int numBeingLoaded = 0;
            for (LightingChunk lc : LightingTaskBatch.this.chunks) {
                if (lc.isChunkLoading) {
                    numBeingLoaded++;
                }
            }

            // Load chunks
            isFullyLoaded = true;
            for (final LightingChunk lc : LightingTaskBatch.this.chunks) {
                if (lc.isFilled) {
                    continue;
                }

                isFullyLoaded = false;

                if (lc.isChunkLoading) {
                    continue;
                }

                if (numBeingLoaded >= LightCleaner.asyncLoadConcurrency) {
                    break;
                }

                // Load the chunk sync or async
                lc.isChunkLoading = true;
                lc.forcedChunk.move(ChunkUtil.forceChunkLoaded(world, lc.chunkX, lc.chunkZ));
                CompletableFuture<Chunk> asyncLoad = lc.forcedChunk.getChunkAsync();
                if (!asyncLoad.isDone()) {
                    numBeingLoaded++;
                }

                // Once loaded, fill with the data from the chunk
                asyncLoad.thenAccept(new Consumer<Chunk>() {
                    @Override
                    public void accept(Chunk chunk) {
                        if (!LightingTaskBatch.this.aborted) {
                            lc.fill(chunk);
                        }
                        lc.isChunkLoading = false;
                    }
                });
            }

            // Wait a short while.
            // TODO: Perhaps would be better to use a Lock object for this
            if (!isFullyLoaded) {
                AsyncTask.sleep(100);
            }

            // If aborted, stop entirely
            if (this.aborted) {
                return;
            }
        } while (!isFullyLoaded);

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

        // Close chunks kept loaded
        LightingChunk[] chunks = this.chunks;
        if (chunks != null) {
            for (LightingChunk lc : chunks) {
                lc.forcedChunk.close();
            }
        }
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

    static int i = 0;

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
                    lc.saveToChunk(bchunk).thenAccept((changed) -> {
                        // Chunk changed, we need to resend to players
                        if (changed.booleanValue()) {
                            WorldUtil.queueChunkSendLight(world, lc.chunkX, lc.chunkZ);
                        }
                    });

                    // Closes our forced chunk, may cause the chunk to now unload
                    lc.forcedChunk.close();

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

        // Skip spread phase when debug mode is active
        if (this.options.getDebugMakeCorrupted()) {
            this.completed();
            return;
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

    @Override
    public void applyOptions(ScheduleArguments args) {
        this.options = args;
    }

    @Override
    public boolean canSave() {
        return !this.options.getLoadedChunksOnly() && !this.options.getDebugMakeCorrupted();
    }
}
