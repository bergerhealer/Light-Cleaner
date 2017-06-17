package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.bases.NibbleArrayBase;
import com.bergerkiller.bukkit.common.wrappers.BlockData;
import com.bergerkiller.bukkit.common.wrappers.ChunkSection;
import com.bergerkiller.reflection.net.minecraft.server.NMSChunkSection;

public class LightingChunkSection {
    public final LightingChunk owner;
    public final NibbleArrayBase skyLight;
    public final NibbleArrayBase blockLight;
    public final NibbleArrayBase opacity;

    public LightingChunkSection(LightingChunk owner, ChunkSection chunkSection) {
        this.owner = owner;

        // Block light data
        this.blockLight = new NibbleArrayBase(chunkSection.getBlockLightData());

        // Sky light data
        if (chunkSection.hasSkyLight()) {
            this.skyLight = new NibbleArrayBase(chunkSection.getSkyLightData());
        } else {
            this.skyLight = null;
        }

        // Fill opacity and initial block lighting values
        this.opacity = new NibbleArrayBase();
        int x, y, z, opacity, maxlight, light, blockEmission;
        boolean withinBounds;
        BlockData info;
        for (x = 0; x < 16; x++) {
            for (z = 0; z < 16; z++) {
                withinBounds = x >= owner.startX && x <= owner.endX && z >= owner.startZ && z <= owner.endZ;
                for (y = 0; y < 16; y++) {
                    info = chunkSection.getBlockData(x, y, z);
                    opacity = info.getOpacity() & 0xf;
                    blockEmission = info.getEmission();
                    if (withinBounds) {
                        // Within bounds: Regenerate (skylight is regenerated elsewhere)
                        this.opacity.set(x, y, z, opacity);
                        this.blockLight.set(x, y, z, blockEmission);
                    } else {
                        // Outside bounds: only fix blatant errors in the light
                        maxlight = 15 - opacity;

                        // Sky light
                        if (this.skyLight != null) {
                            light = this.skyLight.get(x, y, z);
                            if (light > maxlight) {
                                this.skyLight.set(x, y, z, 0);
                            }
                        }

                        // Block light (take in account light emission values)
                        if (blockEmission > maxlight) {
                            maxlight = blockEmission;
                        }
                        light = this.blockLight.get(x, y, z);
                        if (light > maxlight) {
                            this.blockLight.set(x, y, z, 0);
                        }
                    }
                }
            }
        }
    }

    /**
     * Sets a light level
     *
     * @param x        - coordinate
     * @param y        - coordinate
     * @param z        - coordinate
     * @param skyLight state: True for skyLight, False for blockLight
     * @param value    of light to set to
     */
    public void setLight(boolean skyLight, int x, int y, int z, int value) {
        (skyLight ? this.skyLight : this.blockLight).set(x, y, z, value);
    }

    /**
     * Gets a light level
     *
     * @param x        - coordinate
     * @param y        - coordinate
     * @param z        - coordinate
     * @param skyLight state: True for skyLight, False for blockLight
     * @return the light level
     */
    public int getLight(boolean skyLight, int x, int y, int z) {
        return (skyLight ? this.skyLight : this.blockLight).get(x, y, z);
    }

    /**
     * Applies the lighting information to a chunk section
     *
     * @param chunkSection to save to
     */
    public void saveToChunk(ChunkSection chunkSection) {
        Object handle = chunkSection.getHandle();
        NMSChunkSection.blockLight.set(handle, blockLight.toHandle());
        if (skyLight != null) {
            NMSChunkSection.skyLight.set(handle, skyLight.toHandle());
        }
    }
}
