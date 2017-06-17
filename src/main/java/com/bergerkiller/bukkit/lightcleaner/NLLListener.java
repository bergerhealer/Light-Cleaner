package com.bergerkiller.bukkit.lightcleaner;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;

import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService;

public class NLLListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (LightingService.isProcessing(event.getChunk())) {
            event.setCancelled(true);
        }
    }
}
