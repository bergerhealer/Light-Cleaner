package com.bergerkiller.bukkit.lightcleaner;

import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import com.bergerkiller.bukkit.common.Common;
import com.bergerkiller.bukkit.common.PluginBase;
import com.bergerkiller.bukkit.common.config.FileConfiguration;
import com.bergerkiller.bukkit.common.permissions.NoPermissionException;
import com.bergerkiller.bukkit.common.utils.ParseUtil;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService;

public class LightCleaner extends PluginBase {
    public static LightCleaner plugin;
    public static long minFreeMemory = 100 * 1024 * 1024;
    public static boolean autoCleanEnabled = false;
    public static boolean loadChunksAsync = true;

    @Override
    public int getMinimumLibVersion() {
        return Common.VERSION;
    }

    @Override
    public void permissions() {
        this.loadPermissions(Permission.class);
    }

    @Override
    public void enable() {
        plugin = this;

        register(new NLLListener());

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

        config.setHeader("autoCleanEnabled", "\nSets whether lighting is cleaned up for newly generated chunks");
        config.addHeader("autoCleanEnabled", "This will eliminate dark shadows during world generation");
        autoCleanEnabled = config.get("autoCleanEnabled", false);

        config.setHeader("loadChunksAsync", "\nSets whether chunks to be processed are loaded asynchronously");
        config.addHeader("loadChunksAsync", "Best to keep this on true to avoid tps drop, unless problems occur");
        loadChunksAsync = config.get("loadChunksAsync", true);

        config.save();

        LightingService.loadPendingBatches();
    }

    @Override
    public void disable() {        
        LightingService.abort();

        plugin = null;
    }

    @Override
    public boolean command(CommandSender sender, String command, String[] args) {
        try {
            String subCmd = (args.length == 0) ? "" : args[0];
            if (subCmd.equalsIgnoreCase("world")) {
                // cleanlight world
                Permission.CLEAN.handle(sender);
                Permission.CLEAN_WORLD.handle(sender);

                final World world;
                if (args.length >= 2) {
                    world = Bukkit.getWorld(args[1]);
                    if (world == null) {
                        sender.sendMessage(ChatColor.RED + "World '" + args[1] + "' was not found!");
                        return true;
                    }
                } else if (sender instanceof Player) {
                    world = ((Player) sender).getWorld();
                } else {
                    sender.sendMessage("As a console you have to specify the world to fix!");
                    return true;
                }

                // Fix all the chunks in this world
                sender.sendMessage(ChatColor.YELLOW + "The world is now being fixed, this may take very long!");
                sender.sendMessage(ChatColor.YELLOW + "To view the fixing status, use /cleanlight status");
                LightingService.addRecipient(sender);
                // Get an iterator for all the chunks to fix
                LightingService.scheduleWorld(world);
            } else if (subCmd.equalsIgnoreCase("abort")) {
                // cleanlight abort
                Permission.ABORT.handle(sender);
                if (LightingService.isProcessing()) {
                    LightingService.clearTasks();
                    sender.sendMessage(ChatColor.GREEN + "All pending tasks cleared.");
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "No lighting was being processed; there was nothing to abort.");
                }
            } else if (subCmd.equalsIgnoreCase("pause")) {
                // cleanlight pause
                Permission.PAUSE.handle(sender);
                LightingService.setPaused(true);
                sender.sendMessage(ChatColor.YELLOW + "Light cleaning " + ChatColor.RED + "paused");
                if (LightingService.isProcessing()) {
                    sender.sendMessage(ChatColor.RED.toString() + LightingService.getChunkFaults() + ChatColor.YELLOW + " chunks are pending");
                }

            } else if (subCmd.equalsIgnoreCase("resume")) {
                // cleanlight resume
                Permission.PAUSE.handle(sender);
                LightingService.setPaused(false);
                sender.sendMessage(ChatColor.YELLOW + "Light cleaning " + ChatColor.GREEN + "resumed");
                if (LightingService.isProcessing()) {
                    sender.sendMessage(ChatColor.RED.toString() + LightingService.getChunkFaults() + ChatColor.YELLOW + " chunks will now be processed");
                }

            } else if (subCmd.equalsIgnoreCase("status")) {
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
                        sender.sendMessage(ChatColor.YELLOW + "Current: " + ChatColor.GREEN + LightingService.getCurrentStatus());
                    }
                } else {
                    sender.sendMessage(ChatColor.GREEN + "No lighting is being processed at this time.");
                }
            } else if (args.length == 0 || ParseUtil.isNumeric(args[0])) {
                // cleanlight
                Permission.CLEAN.handle(sender);

                if (sender instanceof Player) {
                    Player p = (Player) sender;
                    int radius = Bukkit.getServer().getViewDistance();
                    if (args.length >= 1) {
                        int new_radius = ParseUtil.parseInt(args[0], radius);
                        if (new_radius > radius && !Permission.CLEAN_AREA.has(sender)) {
                            int n = (radius * 2 + 1);
                            sender.sendMessage(ChatColor.RED + "You do not have permission to clean areas larger than " +
                                n + " x " + n);
                            return true;
                        }
                        radius = new_radius;
                    }
                    Location l = p.getLocation();
                    LightingService.scheduleArea(p.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4, radius);
                    p.sendMessage(ChatColor.GREEN + "A " + (radius * 2 + 1) + " X " + (radius * 2 + 1) + " chunk area around you is currently being fixed from lighting issues...");
                    LightingService.addRecipient(sender);
                } else {
                    sender.sendMessage("This command is only available to players");
                }
            } else {
                return false; // unknown command
            }
        } catch (NoPermissionException ex) {
            if (sender instanceof Player) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to use this!");
            } else {
                sender.sendMessage("This command is only for players!");
            }
        }
        return true;
    }
}
