package com.bergerkiller.bukkit.lightcleaner;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkPopulateEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import com.bergerkiller.bukkit.lightcleaner.lighting.LightingAutoClean;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService;

public class NLLListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (LightingService.isProcessing(event.getChunk())) {
            try {
                event.setCancelled(true);
            } catch (Throwable t) {
                //TODO: Bukkit broken!
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkGenerate(ChunkPopulateEvent event) {
        LightingAutoClean.handleChunkGenerated(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }
}
