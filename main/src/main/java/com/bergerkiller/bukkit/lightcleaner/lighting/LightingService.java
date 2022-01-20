package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.AsyncTask;
import com.bergerkiller.bukkit.common.bases.IntVector2;
import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.config.CompressedDataReader;
import com.bergerkiller.bukkit.common.config.CompressedDataWriter;
import com.bergerkiller.bukkit.common.permissions.NoPermissionException;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.ParseUtil;
import com.bergerkiller.bukkit.common.utils.StringUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet.LongIterator;
import com.bergerkiller.bukkit.lightcleaner.LightCleaner;
import com.bergerkiller.bukkit.lightcleaner.Localization;
import com.bergerkiller.bukkit.lightcleaner.Permission;
import com.bergerkiller.bukkit.lightcleaner.util.FlatRegionInfo;
import com.bergerkiller.bukkit.lightcleaner.util.FlatRegionInfoMap;
import com.bergerkiller.bukkit.lightcleaner.util.LightingUtil;

import org.bukkit.*;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class LightingService extends AsyncTask {
    private static final Set<RecipientWhenDone> recipientsForDone = new HashSet<RecipientWhenDone>();
    private static final LinkedList<LightingTask> tasks = new LinkedList<LightingTask>();
    private static final int PENDING_WRITE_INTERVAL = 10;
    private static AsyncTask fixThread = null;
    private static int taskChunkCount = 0;
    private static int taskCounter = 0;
    private static boolean pendingFileInUse = false;
    private static LightingTask currentTask;
    private static boolean paused = false;
    private static boolean lowOnMemory = false;

    /**
     * Gets whether this service is currently processing something
     *
     * @return True if processing, False if not
     */
    public static boolean isProcessing() {
        return fixThread != null;
    }

    /**
     * Starts or stops the processing service.
     * Stopping the service does not instantly abort, the current task is continued.
     *
     * @param process to abort
     */
    public static void setProcessing(boolean process) {
        if (process == isProcessing()) {
            return;
        }
        if (process) {
            fixThread = new LightingService().start(true);
        } else {
            // Fix thread is running, abort
            AsyncTask.stop(fixThread);
            fixThread = null;
        }
    }

    /**
     * Gets whether execution is paused, and pending tasks are not being processed
     * 
     * @return True if paused
     */
    public static boolean isPaused() {
        return paused;
    }

    /**
     * Sets whether execution is paused.
     * 
     * @param pause state to set to
     */
    public static void setPaused(boolean pause) {
        if (paused != pause) {
            paused = pause;
        }
    }

    /**
     * Gets the status of the currently processed task
     * 
     * @return current task status
     */
    public static String getCurrentStatus() {
        final LightingTask current = currentTask;
        if (lowOnMemory) {
            return ChatColor.RED + "Too low on available memory (paused)";
        } else if (current == null) {
            return "Finished.";
        } else {
            return current.getStatus();
        }
    }

    /**
     * Gets the time the currently processing task was started. If no task is being processed,
     * an empty result is returned. If processing didn't start yet, the value will be 0.
     * 
     * @return time when the current task was started
     */
    public static java.util.OptionalLong getCurrentStartTime() {
        final LightingTask current = currentTask;
        return (current == null) ? java.util.OptionalLong.empty() : OptionalLong.of(current.getTimeStarted());
    }

    /**
     * Adds a player who will be notified of the lighting operations being completed
     *
     * @param player to add, null for console
     */
    public static void addRecipient(CommandSender sender) {
        if (sender instanceof BlockCommandSender) {
            return;
        }

        synchronized (recipientsForDone) {
            recipientsForDone.add(new RecipientWhenDone(sender));
        }
    }

    public static void scheduleWorld(final World world) {
        ScheduleArguments args = new ScheduleArguments();
        args.setWorld(world);
        args.setEntireWorld();
        schedule(args);
    }

    /**
     * Schedules a square chunk area for lighting fixing
     *
     * @param world   the chunks are in
     * @param middleX
     * @param middleZ
     * @param radius
     */
    public static void scheduleArea(World world, int middleX, int middleZ, int radius) {
        ScheduleArguments args = new ScheduleArguments();
        args.setWorld(world);
        args.setChunksAround(middleX, middleZ, radius);
        schedule(args);
    }

    @Deprecated
    public static void schedule(World world, Collection<IntVector2> chunks) {
        ScheduleArguments args = new ScheduleArguments();
        args.setWorld(world);
        args.setChunks(chunks);
        schedule(args);
    }

    public static void schedule(World world, LongHashSet chunks) {
        ScheduleArguments args = new ScheduleArguments();
        args.setWorld(world);
        args.setChunks(chunks);
        schedule(args);
    }

    public static void schedule(ScheduleArguments args) {
        // World not allowed to be null
        if (args.getWorld() == null) {
            throw new IllegalArgumentException("Schedule arguments 'world' is null");
        }

        // If no chunks specified, entire world
        if (args.isEntireWorld()) {
            LightingTaskWorld task = new LightingTaskWorld(args.getWorld());
            task.applyOptions(args);
            schedule(task);
            return;
        }

        // If less than 34x34 chunks are requested, schedule as one task
        // In that case, be sure to only schedule chunks that actually exist
        // This prevents generating new chunks as part of this command
        LongHashSet chunks = args.getChunks();
        if (chunks.size() <= (34*34)) {

            LongHashSet chunks_filtered = new LongHashSet(chunks.size());
            Set<IntVector2> region_coords_filtered = new HashSet<IntVector2>();
            int[] regionYCoordinates;
            LongIterator iter = chunks.longIterator();

            if (args.getLoadedChunksOnly()) {
                // Remove coordinates of chunks that aren't loaded
                Set<Integer> chunkYCoords = new HashSet<Integer>();
                while (iter.hasNext()) {
                    long chunkKey = iter.next();
                    int cx = MathUtil.longHashMsw(chunkKey);
                    int cz = MathUtil.longHashLsw(chunkKey);
                    Chunk loadedChunk = WorldUtil.getChunk(args.getWorld(), cx, cz);
                    if (loadedChunk != null) {
                        chunks_filtered.add(chunkKey);
                        region_coords_filtered.add(new IntVector2(
                                WorldUtil.chunkToRegionIndex(cx),
                                WorldUtil.chunkToRegionIndex(cz)));
                        chunkYCoords.addAll(WorldUtil.getLoadedSectionCoordinates(loadedChunk));
                    }
                }

                // Turn chunk y-coordinates into region y-coordinates
                regionYCoordinates = chunkYCoords.stream()
                        .mapToInt(Integer::intValue)
                        .map(WorldUtil::chunkToRegionIndex)
                        .sorted().distinct().toArray();
            } else {
                if (LightCleaner.skipWorldEdge) {
                    // Remove coordinates of chunks that don't actually exist (avoid generating new chunks)
                    // isChunkAvailable isn't very fast, but fast enough below this threshold of chunks
                    // To check for border chunks, we check that all 9 chunks are are available
                    Map<IntVector2, Boolean> tmp = new HashMap<>();
                    while (iter.hasNext()) {
                        long chunk = iter.next();
                        int cx = MathUtil.longHashMsw(chunk);
                        int cz = MathUtil.longHashLsw(chunk);

                        boolean fully_loaded = true;
                        for (int dx = -LightCleaner.WORLD_EDGE; dx <= LightCleaner.WORLD_EDGE && fully_loaded; dx++) {
                            for (int dz = -LightCleaner.WORLD_EDGE; dz <= LightCleaner.WORLD_EDGE && fully_loaded; dz++) {
                                IntVector2 pos = new IntVector2(cx + dx, cz + dz);
                                fully_loaded &= tmp.computeIfAbsent(pos, p -> WorldUtil.isChunkAvailable(args.getWorld(), p.x, p.z)).booleanValue();
                            }
                        }

                        if (fully_loaded) {
                            chunks_filtered.add(chunk);
                            region_coords_filtered.add(new IntVector2(
                                    WorldUtil.chunkToRegionIndex(cx),
                                    WorldUtil.chunkToRegionIndex(cz)));
                        }
                    }
                } else {
                    // Remove coordinates of chunks that don't actually exist (avoid generating new chunks)
                    // isChunkAvailable isn't very fast, but fast enough below this threshold of chunks
                    while (iter.hasNext()) {
                        long chunk = iter.next();
                        int cx = MathUtil.longHashMsw(chunk);
                        int cz = MathUtil.longHashLsw(chunk);
                        if (WorldUtil.isChunkAvailable(args.getWorld(), cx, cz)) {
                            chunks_filtered.add(chunk);
                            region_coords_filtered.add(new IntVector2(
                                    WorldUtil.chunkToRegionIndex(cx),
                                    WorldUtil.chunkToRegionIndex(cz)));
                        }
                    }
                }

                // For all filtered chunk coordinates, compute loadable regions
                {
                    Set<IntVector3> regions = WorldUtil.getWorldRegions3ForXZ(args.getWorld(), region_coords_filtered);

                    // Simplify to just the unique Y-coordinates
                    regionYCoordinates = regions.stream().mapToInt(r -> r.y).sorted().distinct().toArray();
                }
            }

            // Schedule it
            if (!chunks_filtered.isEmpty()) {
                LightingTaskBatch task = new LightingTaskBatch(args.getWorld(), regionYCoordinates, chunks_filtered);
                task.applyOptions(args);
                schedule(task);
            }
            return;
        }

        // Too many chunks requested. Separate the operations per region file with small overlap.
        FlatRegionInfoMap regions;
        if (args.getLoadedChunksOnly()) {
            regions = FlatRegionInfoMap.createLoaded(args.getWorld());
        } else {
            regions = FlatRegionInfoMap.create(args.getWorld());
        }

        LongIterator iter = chunks.longIterator();
        LongHashSet scheduledRegions = new LongHashSet();
        while (iter.hasNext()) {
            long first_chunk = iter.next();
            int first_chunk_x = MathUtil.longHashMsw(first_chunk);
            int first_chunk_z = MathUtil.longHashLsw(first_chunk);
            FlatRegionInfo region = regions.getRegionAtChunk(first_chunk_x, first_chunk_z);
            if (region == null || scheduledRegions.contains(region.rx, region.rz)) {
                continue; // Does not exist or already scheduled
            }
            if (!region.containsChunk(first_chunk_x, first_chunk_z)) {
                continue; // Chunk does not exist in world (not generated yet) or isn't loaded (loaded chunks only option)
            }

            // Collect all the region Y coordinates used for this region and the neighbouring regions
            // This makes sure we find all chunk slices we might need on an infinite height world
            int[] region_y_coordinates = regions.getRegionYCoordinatesSelfAndNeighbours(region);

            // Collect all chunks to process for this region.
            // This is an union of the 34x34 area of chunks and the region file data set
            LongHashSet buffer = new LongHashSet();
            int rdx, rdz;
            for (rdx = -1; rdx < 33; rdx++) {
                for (rdz = -1; rdz < 33; rdz++) {
                    int cx = region.cx + rdx;
                    int cz = region.cz + rdz;
                    long chunk_key = MathUtil.longHashToLong(cx, cz);
                    if (!chunks.contains(chunk_key)) {
                        continue;
                    }

                    if (LightCleaner.skipWorldEdge) {
                        // Check the chunk and the surrounding chunks are all present
                        if (!regions.containsChunkAndNeighbours(cx, cz)) {
                            continue;
                        }
                    } else {
                        // Only check chunk
                        if (!regions.containsChunk(cx, cz)) {
                            continue;
                        }
                    }
                    buffer.add(chunk_key);
                }
            }

            // Schedule the region
            if (!buffer.isEmpty()) {
                scheduledRegions.add(region.rx, region.rz);
                LightingTaskBatch task = new LightingTaskBatch(args.getWorld(), region_y_coordinates, buffer);
                task.applyOptions(args);
                schedule(task);
            }
        }
    }

    public static void schedule(LightingTask task) {
        synchronized (tasks) {
            tasks.offer(task);
            taskChunkCount += task.getChunkCount();
        }
        setProcessing(true);
    }

    /**
     * Loads the pending chunk batch operations from a save file.
     * If it is there, it will start processing these again.
     */
    public static void loadPendingBatches() {
        final File saveFile = LightCleaner.plugin.getDataFile("PendingLight.dat");
        if (!saveFile.exists()) {
            return;
        }
        final HashSet<String> missingWorlds = new HashSet<String>();
        pendingFileInUse = true;
        if (!new CompressedDataReader(saveFile) {
            @Override
            public void read(DataInputStream stream) throws IOException {
                int version = 1;
                if (stream.readInt() >= 0) {
                    // Before version byte was added
                    LightCleaner.plugin.log(Level.WARNING, "PendingLight.dat stores an older data format that is not supported");
                    return;
                } else {
                    version = stream.readByte() & 0xFF;
                    if (version != 2) {
                        LightCleaner.plugin.log(Level.WARNING, "PendingLight.dat stores an older or newer data format that is not supported");
                        return;
                    }
                }

                // Empty file? Strange, but ignore it then
                final int count = stream.readInt();
                if (count == 0) {
                    return;
                }
                LightCleaner.plugin.log(Level.INFO, "Continuing previously saved lighting operations (" + count + ")...");
                for (int c = 0; c < count; c++) {
                    String worldName = stream.readUTF();
                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        // Load it?
                        if (new File(Bukkit.getWorldContainer(), worldName).exists()) {
                            world = Bukkit.createWorld(new WorldCreator(worldName));
                        } else {
                            missingWorlds.add(worldName);
                        }
                    }

                    // Load all the region coordinates
                    final int regionYCoordinateCount = stream.readInt();
                    int[] regions;
                    if (world == null) {
                        regions = null;
                        stream.skip(regionYCoordinateCount * (Integer.SIZE / Byte.SIZE));
                    } else {
                        regions = new int[regionYCoordinateCount];
                        for (int i = 0; i < regionYCoordinateCount; i++) {
                            regions[i] = stream.readInt();
                        }
                    }

                    // Load all the coordinates
                    final int chunkCount = stream.readInt();
                    long[] coords;
                    if (world == null) {
                        coords = null;
                        stream.skip(chunkCount * (Long.SIZE / Byte.SIZE));
                    } else {
                        coords = new long[chunkCount];
                        for (int i = 0; i < chunkCount; i++) {
                            coords[i] = stream.readLong();
                        }
                    }

                    // Skip if world isn't loaded
                    if (world == null) {
                        continue;
                    }

                    // Schedule and clear
                    schedule(new LightingTaskBatch(world, regions, coords));
                }
            }
        }.read()) {
            LightCleaner.plugin.log(Level.SEVERE, "Failed to continue previous saved lighting operations");
        } else if (!missingWorlds.isEmpty()) {
            LightCleaner.plugin.log(Level.WARNING, "Removed lighting operations for the following (now missing) worlds: ");
            LightCleaner.plugin.log(Level.WARNING, StringUtil.combineNames(missingWorlds));
        }
        pendingFileInUse = false;
    }

    /**
     * Saves all pending chunk batch operations to a save file.
     * If the server, for whatever reason, crashes, it can restore using this file.
     */
    public static void savePendingBatches() {
        if (pendingFileInUse) {
            return;
        }
        pendingFileInUse = true;
        try {
            final File saveFile = LightCleaner.plugin.getDataFile("PendingLight.dat");
            if (saveFile.exists() && tasks.isEmpty()) {
                saveFile.delete();
                return;
            }
            // Write the data to a temporary save file
            final File tmpFile = new File(saveFile.toString() + ".tmp");
            final List<LightingTaskBatch> batches = new ArrayList<LightingTaskBatch>(tasks.size());
            synchronized (tasks) {
                if (tmpFile.exists() && !tmpFile.delete()) {
                    LightCleaner.plugin.log(Level.WARNING, "Failed to delete temporary pending light file. No states saved.");
                    return;
                }
                // Obtain all the batches to save
                for (LightingTask task : tasks) {
                    if (task instanceof LightingTaskBatch && task.canSave() && LightCleaner.isWorldSaveEnabled(task.getWorld())) {
                        batches.add((LightingTaskBatch) task);
                    }
                }
            }
            // Write to the tmp file
            if (new CompressedDataWriter(tmpFile) {
                @Override
                public void write(DataOutputStream stream) throws IOException {
                    stream.writeInt(-1); // Legacy version
                    stream.writeByte(2); // Version ID
                    stream.writeInt(batches.size());
                    for (LightingTaskBatch batch : batches) {
                        // Write world name
                        stream.writeUTF(batch.getWorld().getName());
                        // Write the range of Y-region coordinates to check
                        int[] region_y_coordinates = batch.getRegionYCoordinates();
                        stream.writeInt(region_y_coordinates.length);
                        for (int region : region_y_coordinates) {
                            stream.writeInt(region);
                        }
                        // Write all chunks
                        long[] chunks = batch.getChunks();
                        stream.writeInt(chunks.length);
                        for (long chunk : chunks) {
                            stream.writeLong(chunk);
                        }
                    }
                }
            }.write()) {

                // Move the files around
                if (saveFile.exists() && !saveFile.delete()) {
                    LightCleaner.plugin.log(Level.WARNING, "Failed to remove the previous pending light save file. No states saved.");
                } else if (!tmpFile.renameTo(saveFile)) {
                    LightCleaner.plugin.log(Level.WARNING, "Failed to move pending save file to the actual save file. No states saved.");
                }
            } else {
                LightCleaner.plugin.log(Level.WARNING, "Failed to write to pending save file. No states saved.");
            }
        } finally {
            pendingFileInUse = false;
        }
    }

    /**
     * Clears all pending tasks, does continue with the current tasks
     */
    public static void clearTasks() {
        synchronized (tasks) {
            tasks.clear();
        }
        final LightingTask current = currentTask;
        if (current != null) {
            current.abort();
        }
        synchronized (tasks) {
            tasks.clear();
        }
        currentTask = null;
        taskChunkCount = 0;
    }

    /**
     * Orders this service to abort all tasks, finishing the current task in an orderly fashion.
     * This method can only be called from the main Thread.
     */
    public static void abort() {
        // Finish the current lighting task if available
        final LightingTask current = currentTask;
        final AsyncTask service = fixThread;
        if (service != null && current != null) {
            setProcessing(false);
            current.abort();
        }
        // Clear lighting tasks
        synchronized (tasks) {
            if (current != null) {
                tasks.addFirst(current);
            }
            if (!tasks.isEmpty()) {
                LightCleaner.plugin.log(Level.INFO, "Writing the pending lighting tasks (" + tasks.size() + ") to file to continue later...");
                LightCleaner.plugin.log(Level.INFO, "Want to abort all operations? Delete the 'PendingLighting.dat' file from the plugins/LightCleaner folder");
            }
            savePendingBatches();
            clearTasks();
        }
    }

    /**
     * Gets the amount of chunks that are still faulty
     *
     * @return faulty chunk count
     */
    public static int getChunkFaults() {
        final LightingTask current = currentTask;
        return taskChunkCount + (current == null ? 0 : current.getChunkCount());
    }

    @Override
    public void run() {
        // While paused, do nothing
        while (paused) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            synchronized (tasks) {
                if (tasks.isEmpty()) {
                    break; // Stop processing.
                }
            }
            if (fixThread.isStopRequested()) {
                return;
            }
        }

        synchronized (tasks) {
            currentTask = tasks.poll();
        }
        if (currentTask == null) {
            // No more tasks, end this thread
            // Messages
            synchronized (recipientsForDone) {
                for (RecipientWhenDone recipient : recipientsForDone) {
                    CommandSender recip = recipient.player_name == null ?
                            Bukkit.getConsoleSender() : Bukkit.getPlayer(recipient.player_name);
                    if (recip != null) {
                        String timeStr = LightingUtil.formatDuration(System.currentTimeMillis() - recipient.timeStarted);
                        Localization.COMPLETED.message(recip, timeStr);
                    }
                }
                recipientsForDone.clear();
            }
            // Stop task and abort
            taskCounter = 0;
            setProcessing(false);
            savePendingBatches();
            return;
        } else {
            // Write to file?
            if (taskCounter++ >= PENDING_WRITE_INTERVAL) {
                taskCounter = 0;
                // Start saving on another thread (IO access is slow...)
                new AsyncTask() {
                    public void run() {
                        savePendingBatches();
                    }
                }.start();

                // Save the world of the current task being processed
                if (LightCleaner.isWorldSaveEnabled(currentTask.getWorld())) {
                    WorldUtil.saveToDisk(currentTask.getWorld());
                }
            }
            // Subtract task from the task count
            taskChunkCount -= currentTask.getChunkCount();
            // Process the task
            try {
                currentTask.process();
            } catch (Throwable t) {
                LightCleaner.plugin.getLogger().log(Level.SEVERE, "Failed to process task: " + currentTask.getStatus(), t);
            }

            // Protection against 'out of memory' issues
            // Every time a lighting task is done, we leave behind a very large amount of data
            // This includes LightingChunk data, but also Chunk data
            // Without explicit calls to gc() this does not appear to be cleaned up, resulting in out of memory
            final Runtime runtime = Runtime.getRuntime();
            runtime.gc();

            // If we exceed the limit, proceed to take further measures
            if (calcAvailableMemory(runtime) >= LightCleaner.minFreeMemory) {
                return;
            }

            // Save all worlds: memory after garbage collecting is still too high
            LightCleaner.plugin.log(Level.WARNING, "Saving all worlds to free some memory...");
            for (World world : WorldUtil.getWorlds()) {
                if (LightCleaner.isWorldSaveEnabled(world)) {
                    WorldUtil.saveToDisk(world);
                }
            }
            runtime.gc();
            long free = calcAvailableMemory(runtime);
            if (free >= LightCleaner.minFreeMemory) {
                // Memory successfully reduced
                LightCleaner.plugin.log(Level.WARNING, "All worlds saved. Free memory: " + (free >> 20) + "MB. Continueing...");
            } else {
                // WAIT! We are running out of juice here!
                LightCleaner.plugin.log(Level.WARNING, "Almost running out of memory still (" + (free >> 20) + "MB) ...waiting for a bit");

                // Wait until memory drops below safe values. Do check if aborting!
                lowOnMemory = true;
                try {
                    while ((free = calcAvailableMemory(runtime)) < LightCleaner.minFreeMemory && !this.isStopRequested()) {
                        sleep(30000);
                        runtime.gc();
                    }
                } finally {
                    lowOnMemory = false;
                }

                if (!this.isStopRequested()) {
                    LightCleaner.plugin.log(Level.WARNING, "Got enough memory again to resume (" + (free >> 20) + "MB)");
                }
            }
        }
    }

    private static long calcAvailableMemory(Runtime runtime) {
        long max = runtime.maxMemory();
        if (max == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        } else {
            long used = (runtime.totalMemory() - runtime.freeMemory());
            return (max - used);
        }
    }

    public static class ScheduleArguments {
        private World world;
        private String worldName;
        private LongHashSet chunks;
        private boolean debugMakeCorrupted = false;
        private boolean loadedChunksOnly = false;
        private boolean forceSaving = false;
        private boolean silent = false;
        private int radius = Bukkit.getServer().getViewDistance();

        public boolean getDebugMakeCorrupted() {
            return this.debugMakeCorrupted;
        }

        public boolean getLoadedChunksOnly() {
            return this.loadedChunksOnly;
        }

        public boolean getForceSaving() {
            return this.forceSaving;
        }

        public int getRadius() {
            return this.radius;
        }

        public boolean isEntireWorld() {
            return this.chunks == null;
        }

        /**
         * Whether to send messages to players invoking the command,
         * or when the command completes
         *
         * @return True if silent
         */
        public boolean isSilent() {
            return this.silent;
        }

        public World getWorld() {
            return this.world;
        }

        public String getWorldName() {
            return this.worldName;
        }

        public LongHashSet getChunks() {
            return this.chunks;
        }

        /**
         * Sets the world itself. Automatically updates the world name.
         * 
         * @param world
         * @return these arguments
         */
        public ScheduleArguments setWorld(World world) {
            this.world = world;
            this.worldName = world.getName();
            return this;
        }

        /**
         * Sets the world name to perform operations on.
         * If the world by this name does not exist, the world is null.
         * 
         * @param worldName
         * @return these arguments
         */
        public ScheduleArguments setWorldName(String worldName) {
            this.world = Bukkit.getWorld(worldName);
            this.worldName = worldName;
            return this;
        }

        public ScheduleArguments setEntireWorld() {
            this.chunks = null;
            return this;
        }

        public ScheduleArguments setDebugMakeCorrupted(boolean debug) {
            this.debugMakeCorrupted = debug;
            return this;
        }

        public ScheduleArguments setLoadedChunksOnly(boolean loadedChunksOnly) {
            this.loadedChunksOnly = loadedChunksOnly;
            return this;
        }

        public ScheduleArguments setForceSaving(boolean forceSaving) {
            this.forceSaving = forceSaving;
            return this;
        }

        public ScheduleArguments setSilent(boolean silent) {
            this.silent = silent;
            return this;
        }

        public ScheduleArguments setRadius(int radius) {
            this.radius = radius;
            return this;
        }

        public ScheduleArguments setChunksAround(Location location, int radius) {
            this.setWorld(location.getWorld());
            return this.setChunksAround(location.getBlockX()>>4, location.getBlockZ()>>4, radius);
        }

        public ScheduleArguments setChunksAround(int middleX, int middleZ, int radius) {
            this.setRadius(radius);

            LongHashSet chunks_hashset = new LongHashSet((2*radius)*(2*radius));
            for (int a = -radius; a <= radius; a++) {
                for (int b = -radius; b <= radius; b++) {
                    int cx = middleX + a;
                    int cz = middleZ + b;
                    chunks_hashset.add(cx, cz);
                }
            }
            return this.setChunks(chunks_hashset);
        }

        /**
         * Sets the chunks to a cuboid area of chunks.
         * Make sure the minimum chunk coordinates are less or equal to
         * the maximum chunk coordinates.
         * 
         * @param minChunkX Minimum chunk x-coordinate (inclusive)
         * @param minChunkZ Minimum chunk z-coordinate (inclusive)
         * @param maxChunkX Maximum chunk x-coordinate (inclusive)
         * @param maxChunkZ Maximum chunk z-coordinate (inclusive)
         * @return this
         */
        public ScheduleArguments setChunkFromTo(int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {
            int num_dx = (maxChunkX - minChunkX) + 1;
            int num_dz = (maxChunkZ - minChunkZ) + 1;
            if (num_dx <= 0 || num_dz <= 0) {
                return this.setChunks(new LongHashSet()); // nothing
            }

            LongHashSet chunks_hashset = new LongHashSet(num_dx * num_dz);
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    chunks_hashset.add(chunkX, chunkZ);
                }
            }
            return this.setChunks(chunks_hashset);
        }

        public ScheduleArguments setChunks(Collection<IntVector2> chunks) {
            LongHashSet chunks_hashset = new LongHashSet(chunks.size());
            for (IntVector2 coord : chunks) {
                chunks_hashset.add(coord.x, coord.z);
            }
            return this.setChunks(chunks_hashset);
        }

        public ScheduleArguments setChunks(LongHashSet chunks) {
            this.chunks = chunks;
            return this;
        }

        private boolean checkRadiusPermission(CommandSender sender, int radius) throws NoPermissionException {
            if (Permission.CLEAN_ANY_RADIUS.has(sender)) {
                return true;
            }

            int maxRadius = 0;
            if (Permission.CLEAN_VIEW.has(sender)) {
                maxRadius = Bukkit.getServer().getViewDistance();
                if (radius <= maxRadius) {
                    return true;
                }
            }

            if (Permission.CLEAN_BY_RADIUS.has(sender, Integer.toString(radius))) {
                return true;
            }

            for (int i = 100; i >= 1; i--) {
                if (Permission.CLEAN_BY_RADIUS.has(sender, Integer.toString(i))) {
                    if (i > maxRadius) {
                        maxRadius = i;
                    }
                    break;
                }
            }

            if (radius <= maxRadius) {
                return true;
            }

            if (maxRadius == 0) {
                throw new NoPermissionException();
            } else {
                int n = (maxRadius * 2 + 1);
                sender.sendMessage(ChatColor.RED + "You do not have permission to clean areas larger than " +
                        n + " x " + n);
                return false;
            }
        }

        /**
         * Parses the arguments specified in a command
         * 
         * @param sender
         * @param args Input arguments
         * @return false if the input is incorrect and operations may not proceed
         * @throws NoPermissionException
         */
        public boolean handleCommandInput(CommandSender sender, String[] args) throws NoPermissionException {
            {
                // Parsing
                boolean entireWorld = false;
                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];
                    if (arg.equalsIgnoreCase("silent")) {
                        setSilent(true);
                    } else if (arg.equalsIgnoreCase("dirty")) {
                        setDebugMakeCorrupted(true);
                    } else if (arg.equalsIgnoreCase("loaded")) {
                        setLoadedChunksOnly(true);
                    } else if (arg.equalsIgnoreCase("force")) {
                        setForceSaving(true);
                    } else if (i == 0 && arg.equalsIgnoreCase("world")) {
                        entireWorld = true;
                    } else if (entireWorld) {
                        this.setWorldName(arg);
                    } else if (ParseUtil.isNumeric(arg)) {
                        this.setRadius(ParseUtil.parseInt(arg, this.getRadius()));
                    }
                }

                // Permission handling
                if (this.getDebugMakeCorrupted()) {
                    // Permission for corrupting light
                    Permission.DIRTY_DEBUG.handle(sender);
                } else if (entireWorld) {
                    // Clean world permission
                    Permission.CLEAN_WORLD.handle(sender);
                } else {
                    // Check sender has location info
                    if (getLocationOfSender(sender) == null) {
                        // Can't do this from console (command blocks work)
                        sender.sendMessage("This command can not be done from the console");
                        return false;
                    }

                    // Check permissions for standard cleaning
                    // Players with the CLEAN_ANY permission can clean any radius
                    // Players with the CLEAN_VIEW permission can clean up to the view radius
                    // Players with the CLEAN_BY_RADIUS permission can clean up to [radius]
                    if (sender instanceof Player && !checkRadiusPermission(sender, this.getRadius())) {
                        return false;
                    }
                }

                // Input validation or completion
                if (entireWorld) {
                    if (this.getWorldName() != null && this.getWorld() == null) {
                        sender.sendMessage(ChatColor.RED + "World '" + this.getWorldName() + "' was not found!");
                        return false;
                    }

                    if (this.getWorldName() == null) {
                        Location loc = getLocationOfSender(sender);
                        if (loc != null) {
                            this.setWorld(loc.getWorld());
                        } else {
                            sender.sendMessage("As a console you have to specify the world to fix!");
                            return false;
                        }
                    }

                    this.setEntireWorld();
                } else {
                    this.setChunksAround(getLocationOfSender(sender), this.getRadius());
                }
            }

            // No messages when silent
            if (this.silent) {
                return true;
            }

            // Response message
            if (this.isEntireWorld()) {
                // World logic
                String message = ChatColor.YELLOW + "The ";
                if (this.getLoadedChunksOnly()) {
                    message += "loaded chunks of ";
                }
                message += "the world " + this.getWorldName() + " ";
                if (this.getDebugMakeCorrupted()) {
                    message += "is now being corrupted, this may take very long!";
                } else {
                    message += "is now being fixed, this may take very long!";
                }
                if (this.getForceSaving()) {
                    message += " (Forced Saving)";
                }
                sender.sendMessage(message);
                sender.sendMessage(ChatColor.YELLOW + "To view the status, use /cleanlight status");
            } else {
                // Radius logic
                int n = (getRadius() * 2 + 1);
                String part = n + " X " + n;
                if (this.getLoadedChunksOnly()) {
                    part += " [loaded]";
                }
                if (this.getForceSaving()) {
                    part += " [forced]";
                }

                if (this.getDebugMakeCorrupted()) {
                    Localization.AREA_CORRUPT.message(sender, part);
                } else {
                    Localization.AREA_FIX.message(sender, part);
                }
            }

            return true;
        }

        /**
         * Creates a new ScheduleArguments instance ready to be configured
         * 
         * @return args
         */
        public static ScheduleArguments create() {
            return new ScheduleArguments();
        }

        private static Location getLocationOfSender(CommandSender sender) {
            if (sender instanceof BlockCommandSender) {
                return ((BlockCommandSender) sender).getBlock().getLocation();
            } else if (sender instanceof Entity) {
                return ((Entity) sender).getLocation();
            } else {
                return null;
            }
        }
    }

    private static class RecipientWhenDone {
        public final String player_name;
        public final long timeStarted;

        public RecipientWhenDone(CommandSender sender) {
            this.player_name = (sender instanceof Player) ? sender.getName() : null;
            this.timeStarted = System.currentTimeMillis();
        }
    }
}
