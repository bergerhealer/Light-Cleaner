package com.bergerkiller.bukkit.lightcleaner.handler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import com.fastasyncworldedit.core.queue.IBatchProcessor;
import com.fastasyncworldedit.core.queue.IChunk;
import com.fastasyncworldedit.core.queue.IChunkGet;
import com.fastasyncworldedit.core.queue.IChunkSet;
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
public final class FastAsyncWorldEditHandlerV2 implements Handler {

    @Override
    public boolean isSupported() {
        // This plugin must exist and be enabled
        if (!Handler.isPluginEnabledOrProvided("FastAsyncWorldEdit")) {
            return false;
        }

        // These classes must exist
        try {
            Class.forName("com.fastasyncworldedit.core.queue.IBatchProcessor");
            Class.forName("com.fastasyncworldedit.core.queue.IChunk");
        } catch (ClassNotFoundException ex) {
            return false;
        }

        return true;
    }

    @Override
    public String getEnableMessage() {
        return "Added support for automatic light cleaning when FastAsyncWorldEdit (>= v1.17) operations are performed!";
    }

    @Override
    public String getDisableMessage() {
        return "FastAsyncWorldEdit was disabled, automatic light cleaning is disabled again";
    }

    @Override
    public HandlerInstance enable(HandlerOps ops) {
        return new FAWEV1HandlerInstance(ops);
    }

    private static final class FAWEV1HandlerInstance extends HandlerInstance {
        private final EventBus eventBus;

        public FAWEV1HandlerInstance(HandlerOps ops) {
            super(ops);
            eventBus = WorldEdit.getInstance().getEventBus();
            eventBus.register(this);
        }

        @Override
        public void disable() {
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
                        ops.scheduleAuto(world, chunk.getX(), chunk.getZ());
                        return chunkSet;
                    }

                    @Override
                    public Future<IChunkSet> postProcessSet(IChunk chunk, IChunkGet get, IChunkSet set) {
                        return CompletableFuture.completedFuture(set);
                    }
                });
            }
        }
    }
}
