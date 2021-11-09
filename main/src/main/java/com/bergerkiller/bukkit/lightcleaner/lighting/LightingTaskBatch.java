package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.bases.IntVector2;
import com.bergerkiller.bukkit.common.utils.CommonUtil;
import com.bergerkiller.bukkit.common.utils.LogicUtil;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.lightcleaner.LightCleaner;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService.ScheduleArguments;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.stream.Stream;

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
    private final int[] region_y_coords;
    private volatile LightingChunk[] chunks = null;
    private volatile long[] chunks_coords;
    private boolean done = false;
    private boolean aborted = false;
    private volatile long timeStarted = 0;
    private int numBeingLoaded = 0;
    private volatile Stage stage = Stage.LOADING;
    private LightingService.ScheduleArguments options = new LightingService.ScheduleArguments();

    public LightingTaskBatch(World world, int[] regionYCoordinates, long[] chunkCoordinates) {
        this.world = world;
        this.region_y_coords = regionYCoordinates;
        this.chunks_coords = chunkCoordinates;
    }

    public LightingTaskBatch(World world, int[] regionYCoordinates, LongHashSet chunkCoordinates) {
        this.world = world;
        this.region_y_coords = regionYCoordinates;

        // Turn contents of the long hash set into an easily sortable IntVector2[] array
        IntVector2[] coordinates = new IntVector2[chunkCoordinates.size()];
        {
            LongHashSet.LongIterator iter = chunkCoordinates.longIterator();
            for (int i = 0; iter.hasNext(); i++) {
                long coord = iter.next();
                coordinates[i] = new IntVector2(MathUtil.longHashMsw(coord), MathUtil.longHashLsw(coord));
            }
        }

        // Sort the array along the axis. This makes chunk loading more efficient.
        Arrays.sort(coordinates, (a, b) -> {
            int comp = Integer.compare(a.x, b.x);
            if (comp == 0) {
                comp = Integer.compare(a.z, b.z);
            }
            return comp;
        });

        // Turn back into a long[] array for memory efficiency
        this.chunks_coords = Stream.of(coordinates).mapToLong(c -> MathUtil.longHashToLong(c.x, c.z)).toArray();
    }

    @Override
    public World getWorld() {
        return world;
    }

    /**
     * Gets the X and Z-coordinates of all the chunk columns to process.
     * The coordinates are combined into a single Long, which can be decoded
     * using {@link MathUtil#longHashMsw(long)} for X and {@link MathUtil#longHashLsw(long) for Z.
     * 
     * @return chunk coordinates
     */
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

    /**
     * Gets the Y-coordinates of all the regions to look for chunk data. A region stores 32 chunk
     * slices vertically, and goes up/down 512 blocks every coordinate increase/decrease.
     * 
     * @return region Y-coordinates
     */
    public int[] getRegionYCoordinates() {
        return this.region_y_coords;
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
    public long getTimeStarted() {
        return this.timeStarted;
    }

    private static final class BatchChunkInfo {
        public final int cx;
        public final int cz;
        public final int count;

        public BatchChunkInfo(int cx, int cz, int count) {
            this.cx = cx;
            this.cz = cz;
            this.count = count;
        }
    }

    public BatchChunkInfo getAverageChunk() {
        int count = 0;
        long cx = 0;
        long cz = 0;
        synchronized (this.chunks_lock) {
            if (this.chunks != null) {
                count = this.chunks.length;
                for (LightingChunk chunk : this.chunks) {
                    cx += chunk.chunkX;
                    cz += chunk.chunkZ;
                }
            } else if (this.chunks_coords != null) {
                count = this.chunks_coords.length;
                for (long chunk : this.chunks_coords) {
                    cx += MathUtil.longHashMsw(chunk);
                    cz += MathUtil.longHashLsw(chunk);
                }
            } else {
                return null;
            }
        }
        if (count > 0) {
            cx /= count;
            cz /= count;
        }
        return new BatchChunkInfo((int) cx, (int) cz, count);
    }

    @Override
    public String getStatus() {
        BatchChunkInfo chunk = this.getAverageChunk();
        if (chunk != null) {
            String postfix = " chunks near " +
                    "x=" + (chunk.cx*16) + " z=" + (chunk.cz*16);
            if (this.stage == Stage.LOADING) {
                synchronized (this.chunks_lock) {
                    if (this.chunks != null) {
                        int num_loaded = 0;
                        for (LightingChunk lc : this.chunks) {
                            if (!lc.forcedChunk.isNone() && lc.forcedChunk.getChunkAsync().isDone()) {
                                num_loaded++;
                            }
                        }
                        return "Loaded " + num_loaded + "/" + chunk.count + postfix;
                    }
                }
            } else if (this.stage == Stage.APPLYING) {
                synchronized (this.chunks_lock) {
                    if (this.chunks != null) {
                        int num_saved = 0;
                        for (LightingChunk lc : this.chunks) {
                            if (lc.isApplied) {
                                num_saved++;
                            }
                        }
                        return "Saved " + num_saved + "/" + chunk.count + postfix;
                    }
                }
            }

            return "Cleaning " + chunk.count + postfix;
        } else {
            return done ? "Done" : "No Data";
        }
    }

    private String getShortStatus() {
        BatchChunkInfo chunk = this.getAverageChunk();
        if (chunk != null) {
            return "[x=" + (chunk.cx*16) + " z=" + (chunk.cz*16) + " count=" + chunk.count + "]";
        } else {
            return "[Unknown]";
        }
    }

    private boolean waitForCheckAborted(CompletableFuture<?> future) {
        while (!aborted) {
            try {
                future.get(200, TimeUnit.MILLISECONDS);
                return !aborted;
            } catch (InterruptedException | TimeoutException e1) {
                // Ignore
            } catch (ExecutionException ex) {
                LightCleaner.plugin.getLogger().log(Level.SEVERE, "Error while processing", ex.getCause());
                return false;
            }
        }
        return false;
    }

    private void tryLoadMoreChunks(final CompletableFuture<Void>[] chunkFutures) {
        if (this.aborted) {
            return;
        }

        int i = 0;
        while (true) {
            // While synchronized, pick the next chunk to load
            LightingChunk nextChunk = null;
            CompletableFuture<Void> nextChunkFuture = null;
            synchronized (chunks_lock) {
                for (;i < chunks.length && numBeingLoaded < LightCleaner.asyncLoadConcurrency; i++) {
                    LightingChunk lc = chunks[i];
                    if (lc.loadingStarted) {
                        continue; // Already (being) loaded
                    }

                    // Pick it
                    numBeingLoaded++;
                    lc.loadingStarted = true;
                    nextChunk = lc;
                    nextChunkFuture = chunkFutures[i];
                    break;
                }
            }

            // No more chunks to load / capacity reached
            if (nextChunk == null) {
                break;
            }

            // This shouldn't happen, but just in case, a check
            if (nextChunkFuture.isDone()) {
                continue;
            }

            // Outside of the lock, start loading the next chunk
            final CompletableFuture<Void> f_nextChunkFuture = nextChunkFuture;
            nextChunk.forcedChunk.move(WorldUtil.forceChunkLoaded(world, nextChunk.chunkX, nextChunk.chunkZ));
            nextChunk.forcedChunk.getChunkAsync().whenComplete((chunk, t) -> {
                synchronized (chunks_lock) {
                    numBeingLoaded--;
                }

                f_nextChunkFuture.complete(null);
                tryLoadMoreChunks(chunkFutures);
            });
        }
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Void> loadChunks() {
        // For every LightingChunk, make a completable future
        // Once all these futures are resolved the returned completable future resolves
        CompletableFuture<Void>[] chunkFutures;
        synchronized (this.chunks_lock) {
            chunkFutures = new CompletableFuture[this.chunks.length];
        }
        for (int i = 0; i < chunkFutures.length; i++) {
            chunkFutures[i] = new CompletableFuture<Void>();
        }

        // Start loading up to [asyncLoadConcurrency] number of chunks right now
        // When a callback for a chunk load completes, we start loading additional chunks
        tryLoadMoreChunks(chunkFutures);

        return CompletableFuture.allOf(chunkFutures);
    }

    @Override
    public void process() {
        // Begin
        this.stage = Stage.LOADING;
        this.timeStarted = System.currentTimeMillis();

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

        // Check aborted
        if (aborted) {
            return;
        }

        // Load all the chunks. Wait for loading to finish.
        // Regularly check that this task is not aborted
        CompletableFuture<Void> loadChunksFuture = this.loadChunks();
        if (!waitForCheckAborted(loadChunksFuture)) {
            return;
        }

        // All chunks that can be loaded, are now loaded.
        // Some chunks may have failed to be loaded, get rid of those now!
        // To avoid massive spam, only show the average x/z coordinates of the chunk affected
        synchronized (this.chunks_lock) {
            long failed_chunk_avg_x = 0;
            long failed_chunk_avg_z = 0;
            int failed_chunk_count = 0;

            LightingChunk[] new_chunks = this.chunks;
            for (int i = new_chunks.length-1; i >= 0; i--) {
                LightingChunk lc = new_chunks[i];
                if (lc.forcedChunk.getChunkAsync().isCompletedExceptionally()) {
                    failed_chunk_avg_x += lc.chunkX;
                    failed_chunk_avg_z += lc.chunkZ;
                    failed_chunk_count++;
                    new_chunks = LogicUtil.removeArrayElement(new_chunks, i);
                }
            }
            this.chunks = new_chunks;

            // Tell all the (remaining) chunks about other neighbouring chunks before initialization
            for (LightingChunk lc : new_chunks) {
                for (LightingChunk neigh : new_chunks) {
                    lc.notifyAccessible(neigh);
                }
            }

            // Log when chunks fail to be loaded
            if (failed_chunk_count > 0) {
                failed_chunk_avg_x = ((failed_chunk_avg_x / failed_chunk_count) << 4);
                failed_chunk_avg_z = ((failed_chunk_avg_z / failed_chunk_count) << 4);
                LightCleaner.plugin.getLogger().severe("Failed to load " + failed_chunk_count + " chunks near " +
                        "world=" + world.getName() + " x=" + failed_chunk_avg_x + " z=" + failed_chunk_avg_z);
            }
        }

        // Schedule, on the main thread, to fill all the loaded chunks with data
        CompletableFuture<Void> chunkFillFuture = CompletableFuture.runAsync(() -> {
            synchronized (this.chunks_lock) {
                if (!this.aborted) {
                    for (LightingChunk lc : chunks) {
                        lc.fill(lc.forcedChunk.getChunk(), region_y_coords);
                    }
                }
            }
        }, CommonUtil.getPluginExecutor(LightCleaner.plugin));

        if (!waitForCheckAborted(chunkFillFuture)) {
            return;
        }

        // Now that all chunks we can process are filled, let all the 16x16x16 cubes know of their neighbors
        // This neighboring data is only used during the fix() (initialize + spread) phase
        synchronized (this.chunks_lock) {
            for (LightingChunk lc : chunks) {
                lc.detectCubeNeighbors();
            }
        }

        // Fix
        this.stage = Stage.FIXING;
        fix();
        if (this.aborted) {
            return;
        }

        // Apply and wait for it to be finished
        // Wait in 200ms intervals to allow for aborting
        // After 2 minutes of inactivity, stop waiting and consider applying failed
        this.stage = Stage.APPLYING;
        try {
            CompletableFuture<Void> future = apply();
            int max_num_of_waits = (5*120);
            while (true) {
                if (--max_num_of_waits == 0) {
                    LightCleaner.plugin.getLogger().log(Level.WARNING, "Failed to apply lighting data for " + getShortStatus() + ": Timeout");
                    break;
                }
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
            LightCleaner.plugin.getLogger().log(Level.SEVERE, "Failed to apply lighting data for " + getShortStatus(), e.getCause());
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
    public CompletableFuture<Void> apply() {
        // Apply data to chunks and unload if needed
        LightingChunk[] chunks = LightingTaskBatch.this.chunks;
        CompletableFuture<?>[] applyFutures = new CompletableFuture[chunks.length];
        for (int i = 0; i < chunks.length; i++) {
            LightingChunk lc = chunks[i];
            Chunk bchunk = lc.forcedChunk.getChunk();

            // Save to chunk
            applyFutures[i] = lc.saveToChunk(bchunk, options.getForceSaving()).whenCompleteAsync((changed, t) -> {
                if (t != null) {
                    t.printStackTrace();
                } else if (changed.booleanValue()) {
                    WorldUtil.queueChunkSendLight(world, lc.chunkX, lc.chunkZ);
                }

                // Closes our forced chunk, may cause the chunk to now unload
                lc.forcedChunk.close();
            }, CommonUtil.getPluginExecutor(LightCleaner.plugin));
        }
        return CompletableFuture.allOf(applyFutures);
    }

    /**
     * Performs the (slow) fixing procedure (call from another thread)
     */
    public void fix() {
        // Initialize light
        for (LightingCategory category : LightingCategory.values()) {
            for (LightingChunk chunk : chunks) {
                category.initialize(chunk);
                if (this.aborted) {
                    return;
                }
            }
        }

        // Skip spread phase when debug mode is active
        if (this.options.getDebugMakeCorrupted()) {
            return;
        }

        // Before spreading, change the opacity values to have a minimum of 1
        // Spreading can never be done without losing light
        // This isn't done during initialization because it is important
        // for calculating the first opacity>0 block for sky light.
        for (LightingChunk chunk : chunks) {
            for (LightingCube section : chunk.getSections()) {
                //TODO: Maybe build something into BKCommonLib for this
                int x, y, z;
                for (y = 0; y < 16; y++) {
                    for (z = 0; z < 16; z++) {
                        for (x = 0; x < 16; x++) {
                            if (section.opacity.get(x, y, z) == 0) {
                                section.opacity.set(x, y, z, 1);
                            }
                        }
                    }
                }
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

    private static enum Stage {
        LOADING, FIXING, APPLYING
    }
}
