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
     * Gets the amount of chunks this task is going to fix.
     * This can be a wild estimate. While processing this amount should be
     * updated as well.
     *
     * @return estimated total chunk count
     */
    int getChunkCount();

    /**
     * Gets a descriptive status of the current task being processed
     * 
     * @return status
     */
    String getStatus();

    /**
     * Processes this task (called from another thread!)
     */
    void process();

    /**
     * Orders this task to abort
     */
    void abort();

    /**
     * Whether this task can be saved to PendingLight.dat
     * 
     * @return True if it can be saved
     */
    boolean canSave();

    /**
     * Loads additional options
     */
    void applyOptions(LightingService.ScheduleArguments args);
}
