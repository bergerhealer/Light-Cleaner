package com.bergerkiller.bukkit.lightcleaner.lighting;

import java.util.Arrays;

import com.bergerkiller.bukkit.common.bases.NibbleArrayBase;
import com.bergerkiller.bukkit.common.wrappers.BlockData;
import com.bergerkiller.bukkit.common.wrappers.ChunkSection;
import com.bergerkiller.generated.net.minecraft.server.NibbleArrayHandle;
import com.bergerkiller.reflection.net.minecraft.server.NMSChunkSection;

public class LightingChunkSection {
    public final LightingChunk owner;
    public final NibbleArrayBase skyLight;
    public final NibbleArrayBase blockLight;
    public final NibbleArrayBase opacity;

    public LightingChunkSection(LightingChunk owner, ChunkSection chunkSection, boolean hasSkyLight) {
        this.owner = owner;

        // Block light data
        this.blockLight = new NibbleArrayBase(chunkSection.getBlockLightData());

        // Sky light data
        if (chunkSection.hasSkyLight()) {
            this.skyLight = new NibbleArrayBase(chunkSection.getSkyLightData());
        } else if (hasSkyLight) {
            this.skyLight = new NibbleArrayBase();
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
                withinBounds = x >= owner.start.x && x <= owner.end.x && z >= owner.start.z && z <= owner.end.z;
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
     * @return True if data in the chunk section changed as a result
     */
    public boolean saveToChunk(ChunkSection chunkSection) {
        boolean changed = false;
        Object handle = chunkSection.getHandle();

        if (isNibbleArrayDifferent(blockLight, NMSChunkSection.blockLight.get(handle))) {
            NMSChunkSection.blockLight.set(handle, blockLight.toHandle());
            changed = true;
        }
        if (isNibbleArrayDifferent(skyLight, NMSChunkSection.skyLight.get(handle))) {
            NMSChunkSection.skyLight.set(handle, skyLight.toHandle());
            changed = true;
        }
        return changed;
    }

    private static boolean isNibbleArrayDifferent(NibbleArrayBase n1, Object n2) {
        if (n1 == null) return false;
        if (n2 == null) return true;
        return !Arrays.equals(n1.getData(), NibbleArrayHandle.createHandle(n2).getData());
    }
}
