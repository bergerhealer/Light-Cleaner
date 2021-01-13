package com.bergerkiller.bukkit.lightcleaner;

import com.bergerkiller.bukkit.common.Timings;

public class LCTimings {
    private static final Timings timings = new Timings(LightCleaner.plugin);
    public static final Timings FILL_CHUNK_DATA = timings.create("fillChunkData");
    public static final Timings FILL_CUBE_LIGHT = timings.create("fillCubeLight (Fill Chunk Data)");
    public static final Timings FILL_CUBE_BLOCKDATA = timings.create("fillCubeBlockData (Fill Chunk Data)");
    public static final Timings INIT_HEIGHT_MAP = timings.create("initHeightmap (Fill Chunk Data)");
}
