package com.bergerkiller.bukkit.lightcleaner;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkPopulateEvent;

import com.bergerkiller.bukkit.lightcleaner.lighting.LightingAutoClean;

public class NLLListener implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkGenerate(ChunkPopulateEvent event) {
        LightingAutoClean.handleChunkGenerated(event.getWorld(), event.getChunk().getX(), event.getChunk().getZ());
    }
}
