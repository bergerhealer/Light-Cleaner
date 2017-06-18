package com.bergerkiller.bukkit.lightcleaner;

import java.io.File;

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
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService;
import com.bergerkiller.reflection.net.minecraft.server.NMSRegionFileCache;

public class LightCleaner extends PluginBase {
    public static LightCleaner plugin;
    public static long minFreeMemory = 100 * 1024 * 1024;

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
        config.setHeader("minFreeMemory", "The minimum amount of memory (in MB) allowed while processing chunk lighting");
        config.addHeader("minFreeMemory", "If the remaining free memory drops below this value, measures are taken to reduce it");
        config.addHeader("minFreeMemory", "Memory will be Garbage Collected and all worlds will be saved to free memory");
        minFreeMemory = 1024 * 1024 * config.get("minFreeMemory", 100);

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

                // Obtain the region folder
                File regionFolder = WorldUtil.getWorldRegionFolder(world.getName());
                if (regionFolder == null && WorldUtil.getChunks(world).isEmpty() && NMSRegionFileCache.FILES.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "World " + world.getName() + " contains no loaded chunks neither any offline-stored regions files to read");
                    sender.sendMessage(ChatColor.RED + "This could be a bug in the program, or it could be that there really are no regions generated (yet?)");
                    return true;
                }

                // Fix all the chunks in this world
                sender.sendMessage(ChatColor.YELLOW + "The world is now being fixed, this may take very long!");
                sender.sendMessage(ChatColor.YELLOW + "To view the fixing status, use /cleanlight status");
                LightingService.addRecipient(sender);
                // Get an iterator for all the chunks to fix
                LightingService.scheduleWorld(world, regionFolder);
            } else if (subCmd.equalsIgnoreCase("abort")) {
                // cleanlight abort
                Permission.ABORT.handle(sender);
                if (LightingService.isProcessing()) {
                    LightingService.clearTasks();
                    sender.sendMessage(ChatColor.GREEN + "All pending tasks cleared, will finish current " + LightingService.getChunkFaults() + " chunks now...");
                } else {
                    sender.sendMessage(ChatColor.YELLOW + "No lighting was being processed; there was nothing to abort.");
                }
            } else if (subCmd.equalsIgnoreCase("status")) {
                // cleanlight status
                Permission.STATUS.handle(sender);
                if (LightingService.isProcessing()) {
                    sender.sendMessage(ChatColor.YELLOW + "Lighting is being cleaned, " + ChatColor.RED + LightingService.getChunkFaults() +
                            " " + ChatColor.YELLOW + "chunks remaining");
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
                        Permission.CLEAN_AREA.handle(sender);
                        radius = ParseUtil.parseInt(args[0], radius);
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
