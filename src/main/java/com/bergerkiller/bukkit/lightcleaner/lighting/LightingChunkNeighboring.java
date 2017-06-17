package com.bergerkiller.bukkit.lightcleaner.lighting;

/**
 * Keeps track of the 4 x/z neighbors of chunks
 */
public class LightingChunkNeighboring {
    public final LightingChunk[] values = new LightingChunk[4];

    /**
     * Generates a key ranging 0 - 3 for fixed x/z combinations<br>
     * - Bit 1 is set to contain which of the two is not 1<br>
     * - Bit 2 is set to contain whether x/z is 1 or -1<br><br>
     * <p/>
     * This system requires that the x/z pairs are one the following:<br>
     * (0, 1) | (0, -1) | (1, 0) | (-1, 0)
     *
     * @param x value
     * @param z value
     * @return key
     */
    private static final int getIndexByChunk(int x, int z) {
        return (x & 1) | ((x + z + 1) & 0x2);
    }

    /**
     * Gets the neighbor representing the given relative chunk
     *
     * @param deltaChunkX
     * @param deltaChunkZ
     * @return neighbor
     */
    public LightingChunk get(int deltaChunkX, int deltaChunkZ) {
        return values[getIndexByChunk(deltaChunkX, deltaChunkZ)];
    }

    /**
     * Sets the neighbor representing the given relative chunk
     *
     * @param deltaChunkX
     * @param deltaChunkZ
     * @param neighbor    to set to
     */
    public void set(int deltaChunkX, int deltaChunkZ, LightingChunk neighbor) {
        values[getIndexByChunk(deltaChunkX, deltaChunkZ)] = neighbor;
    }
}
