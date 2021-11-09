package com.bergerkiller.bukkit.lightcleaner.handler;

import java.util.concurrent.atomic.AtomicInteger;

import com.bergerkiller.bukkit.common.Task;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet.LongIterator;
import com.bergerkiller.mountiplex.reflection.util.FastMethod;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.util.eventbus.EventBus;
import com.sk89q.worldedit.util.eventbus.Subscribe;

public final class WorldEditHandlerV1 implements Handler {

    @Override
    public boolean isSupported() {
        // A plugin must exist on the server that is, or provides, WorldEdit
        if (!Handler.isPluginEnabledOrProvided("WorldEdit")) {
            return false;
        }

        // These classes must exist
        try {
            Class.forName("com.sk89q.worldedit.extent.AbstractDelegateExtent");
            Class.forName("com.sk89q.worldedit.Vector");
            Class.forName("com.sk89q.worldedit.blocks.BaseBlock");
        } catch (ClassNotFoundException ex) {
            return false;
        }

        return true;
    }

    @Override
    public String getEnableMessage() {
        return "Added support for automatic light cleaning when WorldEdit (<= v6.0.0) operations are performed!";
    }

    @Override
    public String getDisableMessage() {
        return "WorldEdit (<= v6.0.0) was disabled, automatic light cleaning is disabled again";
    }

    @Override
    public HandlerInstance enable(HandlerOps ops) throws Exception, Error {
        return new WEV1HandlerInstance(ops);
    }

    private static final class WEV1HandlerInstance extends HandlerInstance {
        private final EventBus eventBus;

        public WEV1HandlerInstance(HandlerOps ops) {
            super(ops);
            eventBus = WorldEdit.getInstance().getEventBus();
            eventBus.register(this);
        }

        @Override
        public void disable() {
            eventBus.unregister(this);
        }

        private static final long[] NEIGHBOURS = new long[] {
                MathUtil.toLong(-1, -1),
                MathUtil.toLong(-1, 0),
                MathUtil.toLong(-1, 1),
                MathUtil.toLong(0, -1),
                MathUtil.toLong(0, 1),
                MathUtil.toLong(1, -1),
                MathUtil.toLong(1, 0),
                MathUtil.toLong(1, 1)
        };

        @Subscribe
        public void onEditSession(EditSessionEvent event) {
            if (event.getStage() == EditSession.Stage.BEFORE_CHANGE) {
                org.bukkit.World world = adaptWorldMethod.invoke(null, event.getWorld());
                event.setExtent(new LightCleanerAdapter(event.getExtent(), ops, world));
            }
        }

        // On very old versions of WorldEdit the BukkitAdapter.adapt(world) method is not public,
        // because the class does not have public scope. This is a workaround for that.
        private static final FastMethod<org.bukkit.World> adaptWorldMethod = new FastMethod<org.bukkit.World>();
        static {
            try {
                Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
                Class<?> worldClass = Class.forName("com.sk89q.worldedit.world.World");
                adaptWorldMethod.init(bukkitAdapter.getDeclaredMethod("adapt", worldClass));
            } catch (Throwable t) {
                t.printStackTrace();
                adaptWorldMethod.initUnavailable("Failed to find adapt()");
            }
        }

        // Main adapter, supports latest worldedit version
        public static class LightCleanerAdapter extends AbstractDelegateExtent {
            private final HandlerOps _ops;
            private final org.bukkit.World _world;
            private final LongHashSet _chunks = new LongHashSet();
            private final Task _commitEarlyTask;
            private final Task _commitLateTask;
            private final AtomicInteger _ticksOfNoChanges = new AtomicInteger();

            public LightCleanerAdapter(Extent extent, HandlerOps ops, org.bukkit.World world) {
                super(extent);
                this._ops = ops;
                this._world = world;

                // This task runs every tick until 4 ticks go by with no changes
                this._commitEarlyTask = new Task(_ops.getPlugin()) {
                    @Override
                    public void run() {
                        if (_ticksOfNoChanges.incrementAndGet() >= 5) {
                            scheduleCleanup();
                        }
                    }
                };

                // This task runs guaranteed every 100 ticks
                this._commitLateTask = new Task(_ops.getPlugin()) {
                    @Override
                    public void run() {
                        scheduleCleanup();
                    }
                };
            }

            public synchronized void scheduleCleanup() {
                // Include all the neighbouring chunks in the results
                LongHashSet chunksWithNeighbours = new LongHashSet(_chunks.size() * 2);
                LongIterator iter = _chunks.longIterator();
                while (iter.hasNext()) {
                    long value = iter.next();
                    chunksWithNeighbours.add(value);
                    for (long n : NEIGHBOURS) {
                        chunksWithNeighbours.add(MathUtil.longHashSumW(value, n));
                    }
                }

                // This task must be stopped now
                _commitEarlyTask.stop();

                // Wipe old chunks mapping, further register() will spawn new tasks
                _chunks.clear();

                // Schedule them all
                _ops.scheduleMany(_world, chunksWithNeighbours);
            }

            public synchronized void register(int blockX, int blockZ) {
                _ticksOfNoChanges.set(0);
                if (_chunks.isEmpty()) {
                    _commitEarlyTask.start(1, 1);
                    _commitLateTask.start(100);
                }
                _chunks.add(MathUtil.toChunk(blockX), MathUtil.toChunk(blockZ));
            }

            @Override
            public boolean setBlock(Vector location, BaseBlock block) throws WorldEditException {
                if (super.setBlock(location, block)) {
                    register(location.getBlockX(), location.getBlockZ());
                    return true;
                }
                return false;
            }
        }
    }
}
