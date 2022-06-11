package com.bergerkiller.bukkit.lightcleaner;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import com.bergerkiller.bukkit.lightcleaner.lighting.LightingAutoClean;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService;

public class LCListener implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkGenerate(ChunkPopulateEvent event) {
        LightingAutoClean.handleChunkGenerated(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        LightingService.clearTasksForWorld(event.getWorld());
    }
}
