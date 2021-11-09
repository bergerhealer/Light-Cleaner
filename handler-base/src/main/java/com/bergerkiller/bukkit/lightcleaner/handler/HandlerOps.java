package com.bergerkiller.bukkit.lightcleaner.handler;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.World;

import com.bergerkiller.bukkit.common.wrappers.LongHashSet;

/**
 * Operations a Handler is allowed to perform while
 * handling change events.
 */
public interface HandlerOps {

    /**
     * Gets the JavaPlugin instance of the LightCleaner plugin
     *
     * @return LightCleaner plugin instance
     */
    JavaPlugin getPlugin();

    /**
     * Instructs the plugin to schedule many chunks for cleaning right
     * away.
     *
     * @param world World
     * @param chunkCoordinates Long Hash Set of all the chunk coordinates to clean
     */
    void scheduleMany(World world, LongHashSet chunkCoordinates);

    /**
     * Instructs the plugin to schedule a single chunk for cleaning, as
     * part of auto-cleaning. Some debouncing is involved so that many
     * rapid changes to chunks get combined into a single operation.
     *
     * @param world World
     * @param chunkX Chunk X-coordinate to schedule
     * @param chunkZ Chunk Z-coordinate to schedule
     */
    void scheduleAuto(World world, int chunkX, int chunkZ);
}
