package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.BlockData;
import com.bergerkiller.bukkit.common.wrappers.ChunkSection;
import com.bergerkiller.generated.net.minecraft.server.NibbleArrayHandle;

public class LightingChunkSection {
    public final LightingChunk owner;
    public final NibbleArrayHandle skyLight;
    public final NibbleArrayHandle blockLight;
    public final NibbleArrayHandle opacity;

    public LightingChunkSection(LightingChunk owner, ChunkSection chunkSection, boolean hasSkyLight) {
        this.owner = owner;

        // Block light data
        byte[] blockLightData = WorldUtil.getSectionBlockLight(owner.world,
                owner.chunkX, chunkSection.getY(), owner.chunkZ);
        if (blockLightData != null) {
            this.blockLight = NibbleArrayHandle.createNew(blockLightData);
        } else {
            this.blockLight = NibbleArrayHandle.createNew();
        }

        // Sky light data
        byte[] skyLightData = WorldUtil.getSectionSkyLight(owner.world,
                owner.chunkX, chunkSection.getY(), owner.chunkZ);

        if (skyLightData != null) {
            this.skyLight = NibbleArrayHandle.createNew(skyLightData);
        } else if (hasSkyLight) {
            this.skyLight = NibbleArrayHandle.createNew();
        } else {
            this.skyLight = null;
        }

        // World coordinates
        int worldX = owner.chunkX << 4;
        int worldY = chunkSection.getYPosition();
        int worldZ = owner.chunkZ << 4;

        // Fill opacity and initial block lighting values
        this.opacity = NibbleArrayHandle.createNew();
        int x, y, z, opacity, blockEmission;
        BlockData info;
        for (x = owner.start.x; x <= owner.end.x; x++) {
            for (z = owner.start.z; z <= owner.end.z; z++) {
                for (y = 0; y < 16; y++) {
                    info = chunkSection.getBlockData(x, y, z);
                    blockEmission = info.getEmission();
                    opacity = info.getOpacity(owner.world, worldX+x, worldY+y, worldZ+z);
                    if (opacity <= 0) {
                        opacity = 1;
                    } else if (opacity > 0xF) {
                        opacity = 0xF;
                    }

                    this.opacity.set(x, y, z, opacity);
                    this.blockLight.set(x, y, z, blockEmission);
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

        if (this.blockLight != null) {
            byte[] newBlockLight = this.blockLight.getData();
            byte[] oldBlockLight = WorldUtil.getSectionBlockLight(owner.world,
                    owner.chunkX, chunkSection.getY(), owner.chunkZ);
            boolean blockLightChanged = false;
            if (oldBlockLight == null || newBlockLight.length != oldBlockLight.length) {
                blockLightChanged = true;
            } else {
                for (int i = 0; i < oldBlockLight.length; i++) {
                    if (oldBlockLight[i] != newBlockLight[i]) {
                        blockLightChanged = true;
                        break;
                    }
                }
            }
            if (blockLightChanged) {
                WorldUtil.setSectionBlockLight(owner.world,
                        owner.chunkX, chunkSection.getY(), owner.chunkZ,
                        newBlockLight);
                changed = true;
            }
        }
        if (this.skyLight != null) {
            byte[] newSkyLight = this.skyLight.getData();
            byte[] oldSkyLight = WorldUtil.getSectionSkyLight(owner.world,
                    owner.chunkX, chunkSection.getY(), owner.chunkZ);
            boolean skyLightChanged = false;
            if (oldSkyLight == null || newSkyLight.length != oldSkyLight.length) {
                skyLightChanged = true;
            } else {
                for (int i = 0; i < oldSkyLight.length; i++) {
                    if (oldSkyLight[i] != newSkyLight[i]) {
                        skyLightChanged = true;
                        break;
                    }
                }
            }
            if (skyLightChanged) {
                WorldUtil.setSectionSkyLight(owner.world,
                        owner.chunkX, chunkSection.getY(), owner.chunkZ,
                        newSkyLight);
                changed = true;
            }
        }
        return changed;
    }

}
