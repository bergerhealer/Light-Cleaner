package com.bergerkiller.bukkit.lightcleaner.util;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.BitSet;

import org.bukkit.World;

import com.bergerkiller.reflection.net.minecraft.server.NMSRegionFile;
import com.bergerkiller.reflection.net.minecraft.server.NMSRegionFileCache;

/**
 * Loads region information, storing whether or not
 * the 32x32 (1024) chunks are available.
 */
public class RegionInfo {
    public final World world;
    public final int rx, rz;
    public final int cx, cz;
    private final File _file;
    private final BitSet _chunks;
    private boolean _loadedFromDisk;

    public RegionInfo(World world, File file, int rx, int rz) {
        this.world = world;
        this.rx = rx;
        this.rz = rz;
        this.cx = (rx << 5);
        this.cz = (rz << 5);
        this._file = file;
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
        if (this._chunks == null) {
            return 32 * 32;
        } else {
            return this._chunks.cardinality();
        }
    }

    /**
     * Loads the region information, now telling what chunks are contained
     */
    public void load() {
        if (!this._loadedFromDisk) {
            this._loadedFromDisk = true;

            // From region files
            if (this._file != null) {
                Object regionFile;
                if ((regionFile = NMSRegionFileCache.FILES.get(this._file)) == null) {
                    // Start a new file stream to read the coordinates
                    // Creating a new region file is too slow and results in memory leaks
                    try {
                        DataInputStream stream = new DataInputStream(new FileInputStream(this._file));
                        try {
                            for (int coordIndex = 0; coordIndex < 1024; coordIndex++) {
                                if (stream.readInt() > 0) {
                                    this._chunks.set(coordIndex);
                                }
                            }
                        } finally {
                            stream.close();
                        }
                    } catch (IOException ex) {
                    }
                } else {
                    // Obtain all generated chunks in this region file
                    int coordIndex = 0;
                    int dx, dz;
                    for (dz = 0; dz < 32; dz++) {
                        for (dx = 0; dx < 32; dx++) {
                            if (NMSRegionFile.exists.invoke(regionFile, dx, dz)) {
                                this._chunks.set(coordIndex);
                            }
                            coordIndex++;
                        }
                    }
                }
            }
        }
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
