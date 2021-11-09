package com.bergerkiller.bukkit.lightcleaner;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import com.bergerkiller.bukkit.common.Common;
import com.bergerkiller.bukkit.common.PluginBase;
import com.bergerkiller.bukkit.common.Task;
import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.config.FileConfiguration;
import com.bergerkiller.bukkit.common.permissions.NoPermissionException;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.utils.ParseUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.lightcleaner.handler.Handler;
import com.bergerkiller.bukkit.lightcleaner.handler.HandlerOps;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingAutoClean;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingCube;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService.ScheduleArguments;
import com.bergerkiller.bukkit.lightcleaner.util.DelayClosedForcedChunk;

public class LightCleaner extends PluginBase {
    public static LightCleaner plugin;
    public static long minFreeMemory = 100 * 1024 * 1024;
    public static boolean autoCleanEnabled = false;
    public static int asyncLoadConcurrency = 50;
    public static boolean skipWorldEdge = true;
    public static final int WORLD_EDGE = 2;
    public static Set<String> unsavedWorldNames = new HashSet<String>();
    private boolean worldEditHandlerEnabled = false;
    private Handler worldEditHandler = null;

    /**
     * Used by auto-cleaning handlers
     */
    private final HandlerOps handlerOps = new HandlerOps() {
        @Override
        public JavaPlugin getPlugin() {
            return LightCleaner.this;
        }

        @Override
        public void scheduleMany(World world, LongHashSet chunkCoordinates) {
            LightingService.schedule(ScheduleArguments.create()
                    .setWorld(world)
                    .setChunks(chunkCoordinates));
        }

        @Override
        public void scheduleAuto(World world, int chunkX, int chunkZ) {
            LightingAutoClean.schedule(world, chunkX, chunkZ, 20);
        }
    };

    private final Task closeForcedChunksTask = new Task(this) {
        @Override
        public void run() {
            DelayClosedForcedChunk.cleanup();
        }
    };

    public static boolean isWorldSaveEnabled(World world) {
        return !unsavedWorldNames.contains(world.getName());
    }

    @Override
    public int getMinimumLibVersion() {
        return Common.VERSION;
    }

    @Override
    public void localization() {
        this.loadLocales(Localization.class);
    }

    @Override
    public void permissions() {
        this.loadPermissions(Permission.class);
    }

    @Override
    public void enable() {
        plugin = this;

        register(new LCListener());

        FileConfiguration config = new FileConfiguration(this);
        config.load();

        // Top header
        config.setHeader("This is the configuration of Light Cleaner, in here you can enable or disable features as you please");

        // Minimum free memory to perform fixes when loading chunks
        config.setHeader("minFreeMemory", "\nThe minimum amount of memory (in MB) allowed while processing chunk lighting");
        config.addHeader("minFreeMemory", "If the remaining free memory drops below this value, measures are taken to reduce it");
        config.addHeader("minFreeMemory", "Memory will be Garbage Collected and all worlds will be saved to free memory");
        config.addHeader("minFreeMemory", "The process will be stalled for so long free memory is below this value");

        int minFreeMemOption = config.get("minFreeMemory", 400);
        if (minFreeMemOption < 400) {
            log(Level.WARNING, "minFreeMemory is set to " + minFreeMemOption + "MB which is less than recommended (400MB)");
            log(Level.WARNING, "It is recommended to correct this in the config.yml of Light Cleaner, or risk out of memory errors");
        }
        minFreeMemory = (long) (1024 * 1024) * (long) minFreeMemOption;

        // Skips chunks at the edge of the world, that if cleaned, would cause more chunks to generate
        config.setHeader("skipWorldEdge", "\nWhether to skip processing chunks at the edge of the world");
        config.addHeader("skipWorldEdge", "Setting this to true prevents additional chunks being generated there");
        config.addHeader("skipWorldEdge", "This does mean the border chunks do not get cleaned");
        skipWorldEdge = config.get("skipWorldEdge", true);

        config.setHeader("autoCleanEnabled", "\nSets whether lighting is cleaned up for newly generated chunks");
        config.addHeader("autoCleanEnabled", "This will eliminate dark shadows during world generation");
        autoCleanEnabled = config.get("autoCleanEnabled", false);

        config.setHeader("autoCleanWorldEditEnabled", "\nSets whether lighting is cleaned up when players perform WorldEdit operations");
        config.addHeader("autoCleanWorldEditEnabled", "This is primarily useful for FastAsyncWorldEdit");
        worldEditHandlerEnabled = config.get("autoCleanWorldEditEnabled", false);

        config.setHeader("asyncLoadConcurrency", "\nHow many chunks are asynchronously loaded at the same time");
        config.addHeader("asyncLoadConcurrency", "Setting this value too high may overflow the internal queues. Too low and it will idle too much.");
        asyncLoadConcurrency = config.get("asyncLoadConcurrency", 50);

        config.setHeader("unsavedWorldNames", "\nA list of world names that have saving disabled");
        config.addHeader("unsavedWorldNames", "Light Cleaner will not save these worlds to free up memory,");
        config.addHeader("unsavedWorldNames", "and will not write persistent PendingLight.dat entries for these worlds");
        unsavedWorldNames.clear();
        unsavedWorldNames.addAll(config.getList("unsavedWorldNames", String.class, Arrays.asList("dummyUnsavedWorldName")));

        config.save();

        // Warn if no memory limit is set
        if (Runtime.getRuntime().maxMemory() == Long.MAX_VALUE && minFreeMemory > 0) {
            log(Level.WARNING, "No memory limitation is configured for Java. An out of memory condition might occur for large operations!");
            log(Level.WARNING, "To silence this warning, set minFreeMemory to 0 in config.yml");
        }

        LightingService.loadPendingBatches();

        // Start unloading forced chunks after a delay
        // No real need to run this every tick, every 5 ticks is fine
        closeForcedChunksTask.start(5, 5);
    }

    @Override
    public void disable() {        
        LightingService.abort();

        closeForcedChunksTask.stop();
        DelayClosedForcedChunk.clear();

        plugin = null;
    }

    @Override
    public void updateDependency(Plugin plugin, String pluginName, boolean enabled) {
        if (worldEditHandlerEnabled && providesWorldEdit(plugin)) {
            if (enabled && worldEditHandler == null) {
                try {
                    Class.forName("com.boydti.fawe.beta.IBatchProcessor");
                    worldEditHandler = new com.bergerkiller.bukkit.lightcleaner.handler.FastAsyncWorldEditHandlerV1();
                } catch (ClassNotFoundException ex) {
                    worldEditHandler = new com.bergerkiller.bukkit.lightcleaner.handler.WorldEditHandler();
                }
                worldEditHandler.enable(handlerOps);
            } else if (!enabled && worldEditHandler != null) {
                worldEditHandler.disable(handlerOps);
                worldEditHandler = null;
                log(Level.INFO, "WorldEdit was disabled, support for automatic light cleaning turned off");
            }
        }
    }

    private static boolean providesWorldEdit(Plugin plugin) {
        if (plugin.getName().equalsIgnoreCase("worldedit")) {
            return true;
        }
        try {
            for (String provide : plugin.getDescription().getProvides()) {
                if ("worldedit".equalsIgnoreCase(provide)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean command(CommandSender sender, String command, String[] args) {
        try {
            String subCmd = (args.length == 0) ? "" : args[0];
            if (subCmd.equalsIgnoreCase("debugblock")) {
                // cleanlight debugblock <x> <y> <z>
                Permission.BLOCK_DEBUG.handle(sender);
                if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
                    LightingCube.DEBUG_BLOCK = null;
                    sender.sendMessage(ChatColor.GREEN + "Cleared block being debugged");
                } else if (args.length <= 3) {
                    sender.sendMessage(ChatColor.RED + "/cleanlight debugblock <x> <y> <z>");
                    sender.sendMessage(ChatColor.RED + "/cleanlight debugblock clear");
                } else {
                    int x = ParseUtil.parseInt(args[1], 0);
                    int y = ParseUtil.parseInt(args[2], 0);
                    int z = ParseUtil.parseInt(args[3], 0);
                    LightingCube.DEBUG_BLOCK = new IntVector3(x, y, z);
                    sender.sendMessage(ChatColor.GREEN + "Will show generated levels for block " +
                            x + "/" + y + "/" + z);
                }
                return true;
            }
            if (subCmd.equalsIgnoreCase("abort")) {
                // cleanlight abort
                Permission.ABORT.handle(sender);
                if (LightingService.isProcessing()) {
                    LightingService.clearTasks();
                    sender.sendMessage(ChatColor.GREEN + "All pending tasks cleared.");
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "No lighting was being processed; there was nothing to abort.");
                }
                return true;
            }
            if (subCmd.equalsIgnoreCase("pause")) {
                // cleanlight pause
                Permission.PAUSE.handle(sender);
                LightingService.setPaused(true);
                sender.sendMessage(ChatColor.YELLOW + "Light cleaning " + ChatColor.RED + "paused");
                if (LightingService.isProcessing()) {
                    sender.sendMessage(ChatColor.RED.toString() + LightingService.getChunkFaults() + ChatColor.YELLOW + " chunks are pending");
                }
                return true;
            }
            if (subCmd.equalsIgnoreCase("resume")) {
                // cleanlight resume
                Permission.PAUSE.handle(sender);
                LightingService.setPaused(false);
                sender.sendMessage(ChatColor.YELLOW + "Light cleaning " + ChatColor.GREEN + "resumed");
                if (LightingService.isProcessing()) {
                    sender.sendMessage(ChatColor.RED.toString() + LightingService.getChunkFaults() + ChatColor.YELLOW + " chunks will now be processed");
                }
                return true;
            }
            if (subCmd.equalsIgnoreCase("status")) {
                // cleanlight status
                Permission.STATUS.handle(sender);
                if (LightingService.isProcessing()) {
                    if (LightingService.isPaused()) {
                        sender.sendMessage(ChatColor.YELLOW + "Light cleaning is currently paused, " + ChatColor.RED + LightingService.getChunkFaults() +
                                " " + ChatColor.YELLOW + "chunks remaining");
                        sender.sendMessage(ChatColor.YELLOW + "To start processing these chunks, use /cleanlight resume");
                    } else {
                        sender.sendMessage(ChatColor.YELLOW + "Lighting is being cleaned, " + ChatColor.RED + LightingService.getChunkFaults() +
                                " " + ChatColor.YELLOW + "chunks remaining");

                        String message = ChatColor.YELLOW + "Current: " + ChatColor.GREEN + LightingService.getCurrentStatus();
                        java.util.OptionalLong startTime = LightingService.getCurrentStartTime();
                        if (startTime.isPresent()) {
                            long time = startTime.getAsLong();
                            if (time != 0) {
                                time = System.currentTimeMillis() - time;
                            }
                            if (time == 0) {
                                message += ChatColor.YELLOW + " (Starting...)";
                            } else if (time > (60*60*1000)) {
                                message += ChatColor.RED + " (>1h)";
                            } else if (time > 60000) {
                                message += ChatColor.RED + " (" + (time/60000) + "m)";
                            }
                        }
                        sender.sendMessage(message);
                    }
                } else {
                    sender.sendMessage(ChatColor.GREEN + "No lighting is being processed at this time.");
                }
                return true;
            }
            if (subCmd.equalsIgnoreCase("at")) {
                // cleanlight at <x> <z> <radius> [world_name]
                Permission.CLEAN_AT.handle(sender);

                if (args.length < 4) {
                    sender.sendMessage(ChatColor.RED + "Invalid syntax!");
                    sender.sendMessage(ChatColor.YELLOW + "Syntax: /cleanlight at <x> <z> <radius> [world_name]");
                    sender.sendMessage(ChatColor.YELLOW + "Note: x, z and radius are chunk coordinates!");
                    return true;
                }

                // Relative to sender when ~ is used (or no world is specified)
                Location senderLocation;
                if (sender instanceof BlockCommandSender) {
                    senderLocation = ((BlockCommandSender) sender).getBlock().getLocation();
                } else if (sender instanceof Entity) {
                    senderLocation = ((Entity) sender).getLocation();
                } else {
                    senderLocation = new Location(Bukkit.getWorlds().get(0), 0, 0, 0);
                }

                int x, z, radius;
                World world;

                // Ew, parsing!
                if (args[1].startsWith("~")) {
                    if ((x = ParseUtil.parseInt(args[1].substring(1), Integer.MAX_VALUE)) == Integer.MAX_VALUE) {
                        sender.sendMessage(ChatColor.RED + "Incorrect syntax for chunk x-coordinate: " + args[1]);
                        return true;
                    }
                    x += MathUtil.toChunk(senderLocation.getBlockX());
                } else {
                    if ((x = ParseUtil.parseInt(args[1], Integer.MAX_VALUE)) == Integer.MAX_VALUE) {
                        sender.sendMessage(ChatColor.RED + "Incorrect syntax for chunk x-coordinate: " + args[1]);
                        return true;
                    }
                }
                if (args[2].startsWith("~")) {
                    if ((z = ParseUtil.parseInt(args[2].substring(1), Integer.MAX_VALUE)) == Integer.MAX_VALUE) {
                        sender.sendMessage(ChatColor.RED + "Incorrect syntax for chunk z-coordinate: " + args[2]);
                        return true;
                    }
                    z += MathUtil.toChunk(senderLocation.getBlockZ());
                } else {
                    if ((z = ParseUtil.parseInt(args[2], Integer.MAX_VALUE)) == Integer.MAX_VALUE) {
                        sender.sendMessage(ChatColor.RED + "Incorrect syntax for chunk z-coordinate: " + args[2]);
                        return true;
                    }
                }
                if ((radius = ParseUtil.parseInt(args[3], Integer.MAX_VALUE)) == Integer.MAX_VALUE) {
                    sender.sendMessage(ChatColor.RED + "Incorrect syntax for radius: " + args[3]);
                    return true;
                }
                if (args.length >= 5) {
                    world = Bukkit.getWorld(args[4]);
                    if (world == null) {
                        sender.sendMessage(ChatColor.RED + "World not found: " + args[4]);
                        return true;
                    }
                } else {
                    world = senderLocation.getWorld();
                }

                // Display confirm message
                sender.sendMessage(ChatColor.GREEN + "Cleaning light near chunk [x=" + x + ", z=" + z + "] on world " + world.getName());

                // Store as schedule arguments and try scheduling
                LightingService.ScheduleArguments scheduleArgs = new LightingService.ScheduleArguments();
                scheduleArgs.setWorld(world);
                scheduleArgs.setChunksAround(x, z, radius);
                LightingService.schedule(scheduleArgs);
                LightingService.addRecipient(sender);
                return true;
            }

            // All other commands are parsed into schedule arguments for the lighting scheduler
            LightingService.ScheduleArguments scheduleArgs = new LightingService.ScheduleArguments();
            if (scheduleArgs.handleCommandInput(sender, args)) {
                LightingService.schedule(scheduleArgs);
                LightingService.addRecipient(sender);
            }

        } catch (NoPermissionException ex) {
            if (sender instanceof Player) {
                Localization.NO_PERMISSION.message(sender);
            } else {
                Localization.PLAYERS_ONLY.message(sender);
            }
        }
        return true;
    }
}
