package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.AsyncTask;
import com.bergerkiller.bukkit.common.Task;
import com.bergerkiller.bukkit.common.bases.IntVector2;
import com.bergerkiller.bukkit.common.config.CompressedDataReader;
import com.bergerkiller.bukkit.common.config.CompressedDataWriter;
import com.bergerkiller.bukkit.common.utils.StringUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.lightcleaner.LightCleaner;

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
    private static Task tickTask = null;
    private static int taskChunkCount = 0;
    private static int taskCounter = 0;
    private static boolean pendingFileInUse = false;
    private static LightingTask currentTask;

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
            tickTask = new Task(LightCleaner.plugin) {
                @Override
                public void run() {
                    final LightingTask current = currentTask;
                    if (current != null) {
                        current.syncTick();
                    }
                }
            }.start(1, 1);
        } else {
            // Fix thread is running, abort
            Task.stop(tickTask);
            AsyncTask.stop(fixThread);
            tickTask = null;
            fixThread = null;
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

    public static void scheduleWorld(final World world, File regionFolder) {
        schedule(new LightingTaskWorld(world, regionFolder));
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
        LongHashSet chunks = new LongHashSet((2*radius)*(2*radius));
        for (int a = -radius; a <= radius; a++) {
            for (int b = -radius; b <= radius; b++) {
                chunks.add(middleX + a, middleZ + b);
            }
        }
        schedule(world, chunks);
    }

    @Deprecated
    public static void schedule(World world, Collection<IntVector2> chunks) {
        schedule(new LightingTaskBatch(world, chunks));
    }

    public static void schedule(World world, LongHashSet chunks) {
        schedule(new LightingTaskBatch(world, chunks));
    }

    public static void schedule(LightingTask task) {
        synchronized (tasks) {
            tasks.offer(task);
            taskChunkCount += task.getChunkCount();
        }
        setProcessing(true);
    }

    /**
     * Checks whether the chunk specified is currently being processed on
     *
     * @param chunk to check
     * @return True if the chunk is being processed, False if not
     */
    public static boolean isProcessing(Chunk chunk) {
        final LightingTask current = currentTask;
        if (current == null) {
            return false;
        } else {
            return current.getWorld() == chunk.getWorld() && current.containsChunk(chunk.getX(), chunk.getZ());
        }
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
                long chunk;
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
                    LongHashSet coords = new LongHashSet(chunkCount);
                    if (world == null) {
                        stream.skip(chunkCount * (Long.SIZE / Byte.SIZE));
                        continue;
                    }
                    // Load all the coordinates
                    for (int i = 0; i < chunkCount; i++) {
                        coords.add(stream.readLong());
                    }
                    // Schedule and clear
                    schedule(world, coords);
                    coords.clear();
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
                    if (task instanceof LightingTaskBatch) {
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
                        LongHashSet chunks = batch.getChunks();
                        stream.writeInt(chunks.size());
                        for (long chunk : chunks.toArray()) {
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
            LightCleaner.plugin.log(Level.INFO, "Processing lighting in the remaining " + current.getChunkCount() + " chunks...");

            // Sync task no longer executes: make sure that we tick the tasks
            while (service.isRunning()) {
                current.syncTick();
                sleep(20);
            }
        }
        // Clear lighting tasks
        synchronized (tasks) {
            if (!tasks.isEmpty()) {
                LightCleaner.plugin.log(Level.INFO, "Writing the pending lighting tasks (" + tasks.size() + ") to file to continue later...");
                LightCleaner.plugin.log(Level.INFO, "Want to abort all operations? Delete the 'PendingLighting.dat' file from the plugins/NoLagg folder");
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
            }
            // Subtract task from the task count
            taskChunkCount -= currentTask.getChunkCount();
            // Process the task
            currentTask.process();

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
                WorldUtil.saveToDisk(world);
            }
            runtime.gc();
            final long freemb = runtime.freeMemory() >> 20;
            if (runtime.freeMemory() >= LightCleaner.minFreeMemory) {
                // Memory successfully reduced
                LightCleaner.plugin.log(Level.WARNING, "All worlds saved. Free memory: " + freemb + "MB. Continueing...");
            } else {
                // WAIT! We are running out of juice here!
                LightCleaner.plugin.log(Level.WARNING, "Almost running out of memory still (" + freemb + "MB) ...waiting for a bit");
                sleep(10000);
                runtime.gc();

                // Wait until memory drops below safe values. Do check if aborting!
                while (runtime.freeMemory() < LightCleaner.minFreeMemory && !fixThread.isStopRequested()) {
                    sleep(1000);
                }
            }
        }
    }
}
