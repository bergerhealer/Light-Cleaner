package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.AsyncTask;
import com.bergerkiller.bukkit.common.utils.ChunkUtil;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.lightcleaner.LightCleaner;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService.ScheduleArguments;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Chunk;
import org.bukkit.World;

/**
 * Contains all the chunk coordinates that have to be fixed,
 * and handles the full process of this fixing.
 * It is literally a batch of chunks being processed.
 */
public class LightingTaskBatch implements LightingTask {
    private static boolean DEBUG_LOG = false; // logs performance stats
    public final World world;
    private final Object chunks_lock = new Object();
    private LightingChunk[] chunks = null;
    private long[] chunks_coords;
    private boolean done = false;
    private boolean aborted = false;
    private LightingService.ScheduleArguments options = new LightingService.ScheduleArguments();

    public LightingTaskBatch(World world, long[] chunkCoordinates) {
        this.world = world;
        this.chunks_coords = chunkCoordinates;
    }

    public LightingTaskBatch(World world, LongHashSet chunkCoordinates) {
        this.world = world;
        this.chunks_coords = chunkCoordinates.toArray();
    }

    @Override
    public World getWorld() {
        return world;
    }

    public long[] getChunks() {
        synchronized (this.chunks_lock) {
            LightingChunk[] chunks = this.chunks;
            if (chunks != null) {
                long[] coords = new long[chunks.length];
                for (int i = 0; i < chunks.length; i++) {
                    coords[i] = MathUtil.longHashToLong(chunks[i].chunkX, chunks[i].chunkZ);
                }
                return coords;
            } else if (this.chunks_coords != null) {
                return this.chunks_coords;
            } else {
                return new long[0];
            }
        }
    }

    @Override
    public int getChunkCount() {
        synchronized (this.chunks_lock) {
            if (this.chunks == null) {
                return this.done ? 0 : this.chunks_coords.length;
            } else {
                int faults = 0;
                for (LightingChunk chunk : this.chunks) {
                    if (chunk.hasFaults()) {
                        faults++;
                    }
                }
                return faults;
            }
        }
    }

    @Override
    public String getStatus() {
        int count = 0;
        long cx = 0;
        long cz = 0;
        synchronized (this.chunks_lock) {
            if (this.chunks == null) {
                count = this.chunks_coords.length;
                for (long chunk : this.chunks_coords) {
                    cx += MathUtil.longHashMsw(chunk);
                    cz += MathUtil.longHashLsw(chunk);
                }
            } else {
                count = this.chunks.length;
                for (LightingChunk chunk : this.chunks) {
                    cx += chunk.chunkX;
                    cz += chunk.chunkZ;
                }
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
        synchronized (this.chunks_lock) {
            LightingChunk[] chunks_new = new LightingChunk[this.chunks_coords.length];
            this.done = false;
            int chunkIdx = 0;
            for (long longCoord : this.chunks_coords) {
                int x = MathUtil.longHashMsw(longCoord);
                int z = MathUtil.longHashLsw(longCoord);
                chunks_new[chunkIdx++] = new LightingChunk(this.world, x, z);
                if (this.aborted) {
                    return;
                }
            }

            // Update fields. We can remove the coordinates to free memory.
            this.chunks = chunks_new;
            this.chunks_coords = null;
        }

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
                        try {
                            if (!LightingTaskBatch.this.aborted) {
                                lc.fill(chunk);
                            }
                        } catch (Throwable t) {
                            LightCleaner.plugin.getLogger().log(Level.SEVERE, "Failed to fill chunk [" + chunk.getX() + "/" + chunk.getZ() + "]", t);
                            abort();
                        }
                        lc.isChunkLoading = false;
                    }
                });
            }

            // Wait a short while.
            // TODO: Perhaps would be better to use a Lock object for this
            if (!isFullyLoaded) {
                AsyncTask.sleep(25);
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

        // Apply and wait for it to be finished
        // Wait in 200ms intervals to allow for aborting
        try {
            CompletableFuture<Void> future = apply();
            while (true) {
                try {
                    future.get(200, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException e) {
                    if (this.aborted) {
                        return;
                    }
                }
            }
        } catch (InterruptedException e) {
            // Ignore
        } catch (ExecutionException e) {
            LightCleaner.plugin.getLogger().log(Level.SEVERE, "Failed to apply lighting data", e.getCause());
        }

        this.done = true;
        synchronized (this.chunks_lock) {
            this.chunks = null;
        }
    }

    @Override
    public void abort() {
        this.aborted = true;

        // Close chunks kept loaded
        LightingChunk[] chunks;
        synchronized (this.chunks_lock) {
            chunks = this.chunks;
        }
        if (chunks != null) {
            for (LightingChunk lc : chunks) {
                lc.forcedChunk.close();
            }
        }
    }

    /**
     * Starts applying the new data to the world.
     * This is done in several ticks on the main thread.
     * The completable future is resolved when applying is finished.
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Void> apply() {
        // Apply data to chunks and unload if needed
        LightingChunk[] chunks = LightingTaskBatch.this.chunks;
        CompletableFuture<Void>[] applyFutures = new CompletableFuture[chunks.length];
        for (int i = 0; i < chunks.length; i++) {
            LightingChunk lc = chunks[i];
            Chunk bchunk = lc.forcedChunk.getChunk();

            // Save to chunk
            applyFutures[i] = lc.saveToChunk(bchunk).thenAccept((changed) -> {
                // Chunk changed, we need to resend to players
                if (changed.booleanValue()) {
                    WorldUtil.queueChunkSendLight(world, lc.chunkX, lc.chunkZ);
                }

                // Closes our forced chunk, may cause the chunk to now unload
                lc.forcedChunk.close();
            });
        }
        return CompletableFuture.allOf(applyFutures);
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
