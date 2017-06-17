package com.bergerkiller.bukkit.lightcleaner.lighting;

import org.bukkit.World;

/**
 * A single task the Lighting Service can handle
 */
public interface LightingTask {
    /**
     * Gets the world this task is working on
     *
     * @return task world
     */
    World getWorld();

    /**
     * Checks whether a certain chunk is contained
     *
     * @param chunkX
     * @param chunkZ
     * @return True if contained, False if not
     */
    boolean containsChunk(int chunkX, int chunkZ);

    /**
     * Gets the amount of chunks this task is going to fix.
     * This can be a wild estimate. While processing this amount should be
     * updated as well.
     *
     * @return estimated total chunk count
     */
    int getChunkCount();

    /**
     * Processes this task (called from another thread!)
     */
    void process();

    /**
     * Called from a synchronized task, ticking this task every tick
     */
    void syncTick();
}
