package com.bergerkiller.bukkit.lightcleaner.handler;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import com.bergerkiller.bukkit.common.Task;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet.LongIterator;
import com.bergerkiller.mountiplex.reflection.ClassHook;
import com.bergerkiller.mountiplex.reflection.util.FastMethod;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.util.eventbus.EventBus;
import com.sk89q.worldedit.util.eventbus.Subscribe;

public class WorldEditHandler implements Handler {
    private final EventBus eventBus;
    private HandlerOps ops;

    public WorldEditHandler() {
        this.eventBus = WorldEdit.getInstance().getEventBus();
    }

    @Override
    public void enable(HandlerOps ops) {
        this.ops = ops;
        eventBus.register(this);
        ops.getPlugin().getLogger().log(Level.INFO, "Added support for automatic light cleaning when WorldEdit operations are performed!");
    }

    @Override
    public void disable(HandlerOps ops) {
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
            event.setExtent(LightCleanerAdapterHook.create(event.getExtent(), ops, world));
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
    }

    // In older versions of WorldEdit a setBlock handler is used that refers to a now-missing type.
    // We generate a class at runtime to deal with this difference.
    public static class LightCleanerAdapterHook extends ClassHook<LightCleanerAdapterHook> {
        private static final LightCleanerAdapterHook INSTANCE = new LightCleanerAdapterHook();
        private final FastMethod<Integer> vector_getBlockX = new FastMethod<Integer>();
        private final FastMethod<Integer> vector_getBlockY = new FastMethod<Integer>();
        private final FastMethod<Integer> vector_getBlockZ = new FastMethod<Integer>();

        private final FastMethod<Integer> blockvector_getBlockX = new FastMethod<Integer>();
        private final FastMethod<Integer> blockvector_getBlockY = new FastMethod<Integer>();
        private final FastMethod<Integer> blockvector_getBlockZ = new FastMethod<Integer>();

        public static LightCleanerAdapter create(Extent extent, HandlerOps ops, org.bukkit.World world) {
            return INSTANCE.constructInstance(LightCleanerAdapter.class,
                    new Class<?>[] { Extent.class, HandlerOps.class, org.bukkit.World.class },
                    new Object[] { extent, ops, world });
        }

        public LightCleanerAdapterHook() {
            // Vector utils
            try {
                Class<?> vectorClass = Class.forName("com.sk89q.worldedit.Vector");
                try {
                    vector_getBlockX.init(vectorClass.getDeclaredMethod("getBlockX"));
                    vector_getBlockY.init(vectorClass.getDeclaredMethod("getBlockY"));
                    vector_getBlockZ.init(vectorClass.getDeclaredMethod("getBlockZ"));
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            } catch (Throwable t) {
                vector_getBlockX.initUnavailable("Vector class does not exist");
                vector_getBlockY.initUnavailable("Vector class does not exist");
                vector_getBlockZ.initUnavailable("Vector class does not exist");
            }

            // BlockVector utils
            try {
                Class<?> blockVectorClass = Class.forName("com.sk89q.worldedit.math.BlockVector3");
                try {
                    blockvector_getBlockX.init(blockVectorClass.getDeclaredMethod("getBlockX"));
                    blockvector_getBlockY.init(blockVectorClass.getDeclaredMethod("getBlockY"));
                    blockvector_getBlockZ.init(blockVectorClass.getDeclaredMethod("getBlockZ"));
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            } catch (Throwable t) {
                blockvector_getBlockX.initUnavailable("BlockVector class does not exist");
                blockvector_getBlockY.initUnavailable("BlockVector class does not exist");
                blockvector_getBlockZ.initUnavailable("BlockVector class does not exist");
            }
        }

        @HookMethod(value="public boolean setBlock(com.sk89q.worldedit.Vector location, com.sk89q.worldedit.blocks.BaseBlock block)", optional=true)
        public boolean setBlockWithVector(Object location, Object block) {
            if (base.setBlockWithVector(location, block)) {
                int blockX = vector_getBlockX.invoke(location);
                int blockZ = vector_getBlockZ.invoke(location);
                ((LightCleanerAdapter) this.instance()).register(blockX, blockZ);
                return true;
            }
            return false;
        }

        @HookMethod(value="public boolean setBlock(com.sk89q.worldedit.math.BlockVector3 location, com.sk89q.worldedit.world.block.BlockStateHolder block)", optional=true)
        public boolean setBlockWithBlockVector(Object location, Object block) {
            if (base.setBlockWithBlockVector(location, block)) {
                int blockX = blockvector_getBlockX.invoke(location);
                int blockZ = blockvector_getBlockZ.invoke(location);
                ((LightCleanerAdapter) this.instance()).register(blockX, blockZ);
                return true;
            }
            return false;
        }
    }
}
