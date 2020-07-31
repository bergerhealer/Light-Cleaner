package com.bergerkiller.bukkit.lightcleaner.util;

import java.util.BitSet;

import org.bukkit.World;

import com.bergerkiller.bukkit.common.utils.WorldUtil;

/**
 * Loads region information, storing whether or not
 * the 32x32 (1024) chunks are available.
 */
public class RegionInfo {
    public final World world;
    public final int rx, rz;
    public final int cx, cz;
    private final BitSet _chunks;
    private boolean _loadedFromDisk;

    public RegionInfo(World world, int rx, int rz) {
        this.world = world;
        this.rx = rx;
        this.rz = rz;
        this.cx = (rx << 5);
        this.cz = (rz << 5);
        this._chunks = new BitSet(1024);
        this._loadedFromDisk = false;
    }

    public void addChunk(int cx, int cz) {
        cx -= this.cx;
        cz -= this.cz;
        if (cx < 0 || cx >= 32 || cz < 0 || cz >= 32) {
            return;
        }
        this._chunks.set((cz << 5) | cx);
    }

    /**
     * Gets the number of chunks in this region.
     * If not loaded yet, the default 1024 is returned.
     * 
     * @return chunk count
     */
    public int getChunkCount() {
        return this._chunks.cardinality();
    }

    /**
     * Loads the region information, now telling what chunks are contained
     */
    public void load() {
        if (!this._loadedFromDisk) {
            this._loadedFromDisk = true;
            this._chunks.or(WorldUtil.getWorldSavedRegionChunks(this.world, this.rx, this.rz));
        }
    }

    /**
     * Ignores loading region chunk information from chunks that aren't loaded
     */
    public void ignoreLoad() {
        this._loadedFromDisk = true;
    }

    /**
     * Gets whether the chunk coordinates specified are within the range
     * of coordinates of this region
     * 
     * @param cx - chunk coordinates (world coordinates)
     * @param cz - chunk coordinates (world coordinates)
     * @return True if in range
     */
    public boolean isInRange(int cx, int cz) {
        cx -= this.cx;
        cz -= this.cz;
        return cx >= 0 && cz >= 0 && cx < 32 && cz < 32;
    }

    /**
     * Gets whether a chunk is contained and exists inside this region
     * 
     * @param cx - chunk coordinates (world coordinates)
     * @param cz - chunk coordinates (world coordinates)
     * @return True if the chunk is contained
     */
    public boolean containsChunk(int cx, int cz) {
        cx -= this.cx;
        cz -= this.cz;
        if (cx < 0 || cx >= 32 || cz < 0 || cz >= 32) {
            return false;
        }

        // Load region file information the first time this is accessed
        this.load();

        // Check in bitset
        return this._chunks.get((cz << 5) | cx);
    }

}
