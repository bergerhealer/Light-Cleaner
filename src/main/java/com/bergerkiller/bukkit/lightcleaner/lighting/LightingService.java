package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.AsyncTask;
import com.bergerkiller.bukkit.common.bases.IntVector2;
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
import com.bergerkiller.bukkit.lightcleaner.Permission;
import com.bergerkiller.bukkit.lightcleaner.util.RegionInfo;
import com.bergerkiller.bukkit.lightcleaner.util.RegionInfoMap;

import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class LightingService extends AsyncTask {
    private static final Set<String> recipientsForDone = new HashSet<String>();
    private static final LinkedList<LightingTask> tasks = new LinkedList<LightingTask>();
    private static final int PENDING_WRITE_INTERVAL = 10;
    private static AsyncTask fixThread = null;
    private static int taskChunkCount = 0;
    private static int taskCounter = 0;
    private static boolean pendingFileInUse = false;
    private static LightingTask currentTask;
    private static boolean paused = false;

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
        if (current == null) {
            return "Finished.";
        } else {
            return current.getStatus();
        }
    }

    /**
     * Adds a player who will be notified of the lighting operations being completed
     *
     * @param player to add, null for console
     */
    public static void addRecipient(CommandSender sender) {
        synchronized (recipientsForDone) {
            recipientsForDone.add((sender instanceof Player) ? sender.getName() : null);
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
            LongIterator iter = chunks.longIterator();

            if (args.getLoadedChunksOnly()) {
                // Remove coordinates of chunks that aren't loaded
                while (iter.hasNext()) {
                    long chunk = iter.next();
                    int cx = MathUtil.longHashMsw(chunk);
                    int cz = MathUtil.longHashLsw(chunk);
                    if (WorldUtil.isLoaded(args.getWorld(), cx, cz)) {
                        chunks_filtered.add(chunk);
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
                    }
                }
            }

            // Schedule it
            if (!chunks_filtered.isEmpty()) {
                LightingTaskBatch task = new LightingTaskBatch(args.getWorld(), chunks_filtered);
                task.applyOptions(args);
                schedule(task);
            }
            return;
        }

        // Too many chunks requested. Separate the operations per region file with small overlap.
        RegionInfoMap regions;
        if (args.getLoadedChunksOnly()) {
            regions = RegionInfoMap.createLoaded(args.getWorld());
        } else {
            regions = RegionInfoMap.create(args.getWorld());
        }

        LongIterator iter = chunks.longIterator();
        LongHashSet scheduledRegions = new LongHashSet();
        while (iter.hasNext()) {
            long first_chunk = iter.next();
            int first_chunk_x = MathUtil.longHashMsw(first_chunk);
            int first_chunk_z = MathUtil.longHashLsw(first_chunk);
            RegionInfo region = regions.getRegion(first_chunk_x, first_chunk_z);
            if (region == null || scheduledRegions.contains(region.rx, region.rz)) {
                continue; // Does not exist or already scheduled
            }
            if (!region.containsChunk(first_chunk_x, first_chunk_z)) {
                continue; // Chunk does not exist in world (not generated yet) or isn't loaded (loaded chunks only option)
            }

            // Collect all chunks to process for this region.
            // This is an union of the 34x34 area of chunks and the region file data set
            LongHashSet buffer = new LongHashSet();
            int dx, dz;
            for (dx = -1; dx < 33; dx++) {
                for (dz = -1; dz < 33; dz++) {
                    int cx = region.cx + dx;
                    int cz = region.cz + dz;
                    long chunk_key = MathUtil.longHashToLong(cx, cz);
                    if (!chunks.contains(chunk_key)) {
                        continue;
                    }
                    if (dx >= 0 && dz >= 0 && dx < 32 && dz < 32) {
                        if (!region.containsChunk(cx, cz)) {
                            continue;
                        }
                    } else {
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
                LightingTaskBatch task = new LightingTaskBatch(args.getWorld(), buffer);
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
                final int count = stream.readInt();
                // Empty file? Strange, but ignore it then
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
                    final int chunkCount = stream.readInt();
                    if (world == null) {
                        stream.skip(chunkCount * (Long.SIZE / Byte.SIZE));
                        continue;
                    }

                    // Load all the coordinates
                    long[] coords = new long[chunkCount];
                    for (int i = 0; i < chunkCount; i++) {
                        coords[i] = stream.readLong();
                    }

                    // Schedule and clear
                    schedule(new LightingTaskBatch(world, coords));
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
                    stream.writeInt(batches.size());
                    for (LightingTaskBatch batch : batches) {
                        // Write world name
                        stream.writeUTF(batch.getWorld().getName());
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
            final String message = ChatColor.GREEN + "All lighting operations are completed.";
            synchronized (recipientsForDone) {
                for (String player : recipientsForDone) {
                    CommandSender recip = player == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(player);
                    if (recip != null) {
                        recip.sendMessage(message);
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
            if (runtime.freeMemory() >= LightCleaner.minFreeMemory) {
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
            long free = runtime.freeMemory();
            if (free >= LightCleaner.minFreeMemory) {
                // Memory successfully reduced
                LightCleaner.plugin.log(Level.WARNING, "All worlds saved. Free memory: " + (free >> 20) + "MB. Continueing...");
            } else {
                // WAIT! We are running out of juice here!
                LightCleaner.plugin.log(Level.WARNING, "Almost running out of memory still (" + (free >> 20) + "MB) ...waiting for a bit");

                // Wait until memory drops below safe values. Do check if aborting!
                while ((free = runtime.freeMemory()) < LightCleaner.minFreeMemory && !this.isStopRequested()) {
                    sleep(10000);
                    runtime.gc();
                }

                if (!this.isStopRequested()) {
                    LightCleaner.plugin.log(Level.WARNING, "Got enough memory again to resume (" + (free >> 20) + "MB)");
                }
            }
        }
    }

    public static class ScheduleArguments {
        private World world;
        private String worldName;
        private LongHashSet chunks;
        private boolean debugMakeCorrupted = false;
        private boolean loadedChunksOnly = false;
        private int radius = Bukkit.getServer().getViewDistance();

        public boolean getDebugMakeCorrupted() {
            return this.debugMakeCorrupted;
        }

        public boolean getLoadedChunksOnly() {
            return this.loadedChunksOnly;
        }

        public int getRadius() {
            return this.radius;
        }

        public boolean isEntireWorld() {
            return this.chunks == null;
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

        /**
         * Parses the arguments specified in a command
         * 
         * @param sender
         * @return false if the input is incorrect and operations may not proceed
         * @throws NoPermissionException
         */
        public boolean handleCommandInput(CommandSender sender, String[] args) throws NoPermissionException {
            {
                // Parsing
                boolean entireWorld = false;
                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];
                    if (arg.equalsIgnoreCase("dirty")) {
                        setDebugMakeCorrupted(true);
                    } else if (arg.equalsIgnoreCase("loaded")) {
                        setLoadedChunksOnly(true);
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
                    Permission.DIRTY_DEBUG.handle(sender);
                } else if (entireWorld || !Permission.CLEAN_BY_RADIUS.has(sender, Integer.toString(this.getRadius()))) {
                    Permission.CLEAN.handle(sender);
                }
                if (entireWorld) {
                    // Clean world permission
                    Permission.CLEAN_WORLD.handle(sender);
                } else {
                    // Check radius is less than the default, unless special permission is granted
                    if (this.getRadius() > Bukkit.getServer().getViewDistance() && !Permission.CLEAN_AREA.has(sender)) {
                        int n = (Bukkit.getServer().getViewDistance() * 2 + 1);
                        sender.sendMessage(ChatColor.RED + "You do not have permission to clean areas larger than " +
                                n + " x " + n);
                        return false;
                    }

                    // Check sender is a player
                    if (!(sender instanceof Player)) {
                        // Can't do this from console. TODO?
                        sender.sendMessage("This command is only available to players");
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
                        if (sender instanceof Player) {
                            this.setWorld(((Player) sender).getWorld());
                        } else {
                            sender.sendMessage("As a console you have to specify the world to fix!");
                            return false;
                        }
                    }

                    this.setEntireWorld();
                } else {
                    this.setChunksAround(((Player) sender).getLocation(), this.getRadius());
                }
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
                sender.sendMessage(message);
                sender.sendMessage(ChatColor.YELLOW + "To view the status, use /cleanlight status");
            } else {
                // Radius logic
                int n = (getRadius() * 2 + 1);
                String message = ChatColor.GREEN + "A " + n + " X " + n + " ";
                if (this.getLoadedChunksOnly()) {
                    message += ChatColor.YELLOW + "loaded " + ChatColor.GREEN;
                }
                if (this.getDebugMakeCorrupted()) {
                    message += "chunk area around you is currently being corrupted, introducing lighting issues...";
                } else {
                    message += "chunk area around you is currently being fixed from lighting issues...";
                }
                sender.sendMessage(message);
            }

            return true;
        }
    }
}
