package com.bergerkiller.bukkit.lightcleaner.impl;

import java.util.logging.Level;

import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet;
import com.bergerkiller.bukkit.common.wrappers.LongHashSet.LongIterator;
import com.bergerkiller.bukkit.lightcleaner.LightCleaner;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService;
import com.bergerkiller.bukkit.lightcleaner.lighting.LightingService.ScheduleArguments;
import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.eventbus.EventBus;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.block.BlockStateHolder;

public class WorldEditHandler implements Handler {
    private final EventBus eventBus;

    public WorldEditHandler() {
        this.eventBus = WorldEdit.getInstance().getEventBus();
    }

    @Override
    public void enable(LightCleaner plugin) {
        eventBus.register(this);
        plugin.log(Level.INFO, "Added support for automatic light cleaning when WorldEdit operations are performed!");
    }

    @Override
    public void disable(LightCleaner plugin) {
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

    // Note: added in BKCommonLib 1.16.1 and later
    public static long longHashSumW(long keyA, long keyB) {
        long sum_msw = (keyA & 0xFFFFFFFF00000000L) + (keyB & 0xFFFFFFFF00000000L);
        long sum_lsw = (keyA & 0xFFFFFFFF) + (keyB & 0xFFFFFFFF);
        return sum_msw + (int) sum_lsw - Integer.MIN_VALUE;
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() == EditSession.Stage.BEFORE_CHANGE) {
            final org.bukkit.World world = BukkitAdapter.adapt(event.getWorld());
            event.setExtent(new AbstractDelegateExtent(event.getExtent()) {
                private final LongHashSet _chunks = new LongHashSet();

                private void register(BlockVector3 location) {
                    _chunks.add(MathUtil.toChunk(location.getBlockX()), MathUtil.toChunk(location.getBlockZ()));
                }

                @Override
                public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 location, T block) throws WorldEditException {
                    if (super.setBlock(location, block)) {
                        register(location);
                        return true;
                    }
                    return false;
                }

                @Override
                public Operation commitBefore() {
                    // Include all the neighbouring chunks in the results
                    LongHashSet chunksWithNeighbours = new LongHashSet(_chunks.size() * 2);
                    LongIterator iter = _chunks.longIterator();
                    while (iter.hasNext()) {
                        long value = iter.next();
                        chunksWithNeighbours.add(value);
                        for (long n : NEIGHBOURS) {
                            chunksWithNeighbours.add(longHashSumW(value, n));
                        }
                    }

                    // Schedule them all
                    LightingService.schedule(ScheduleArguments.create()
                            .setWorld(world)
                            .setChunks(chunksWithNeighbours));

                    return null;
                }
            });
        }
    }
}
