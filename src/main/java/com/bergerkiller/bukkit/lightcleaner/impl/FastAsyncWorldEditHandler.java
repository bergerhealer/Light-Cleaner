package com.bergerkiller.bukkit.lightcleaner.impl;

import java.util.logging.Level;

import com.bergerkiller.bukkit.lightcleaner.LightCleaner;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingAutoClean;
import com.boydti.fawe.beta.IBatchProcessor;
import com.boydti.fawe.beta.IChunk;
import com.boydti.fawe.beta.IChunkGet;
import com.boydti.fawe.beta.IChunkSet;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.util.eventbus.EventBus;
import com.sk89q.worldedit.util.eventbus.Subscribe;

/**
 * Uses the experimental (beta) API of FastAsyncWorldEdit to
 * detect chunks that change.
 */
public class FastAsyncWorldEditHandler implements Handler {
    private final EventBus eventBus;

    public FastAsyncWorldEditHandler() {
        this.eventBus = WorldEdit.getInstance().getEventBus();
    }

    @Override
    public void enable(LightCleaner plugin) {
        eventBus.register(this);
        plugin.log(Level.INFO, "Added support for automatic light cleaning when FastAsyncWorldEdit operations are performed!");
    }

    @Override
    public void disable(LightCleaner plugin) {
        eventBus.unregister(this);
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() == EditSession.Stage.BEFORE_CHANGE) {
            final org.bukkit.World world = BukkitAdapter.adapt(event.getWorld());
            event.getExtent().addProcessor(new IBatchProcessor() {
                @Override
                public Extent construct(Extent extent) {
                    return extent;
                }

                @Override
                public IChunkSet processSet(IChunk chunk, IChunkGet chunkGet, IChunkSet chunkSet) {
                    LightingAutoClean.schedule(world, chunk.getX(), chunk.getZ(), 20);
                    return chunkSet;
                }
            });
        }
    }
}
