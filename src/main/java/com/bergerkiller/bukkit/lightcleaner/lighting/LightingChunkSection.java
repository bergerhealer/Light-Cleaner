package com.bergerkiller.bukkit.lightcleaner.lighting;

import java.util.concurrent.CompletableFuture;

import com.bergerkiller.bukkit.common.utils.CommonUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.BlockData;
import com.bergerkiller.bukkit.common.wrappers.ChunkSection;
import com.bergerkiller.generated.net.minecraft.server.NibbleArrayHandle;

public class LightingChunkSection {
    public final LightingChunk owner;
    public final NibbleArrayHandle skyLight;
    public final NibbleArrayHandle blockLight;
    public final NibbleArrayHandle opacity;
    private static boolean HAS_ASYNC_SET_LIGHT_METHODS = true;

    public LightingChunkSection(LightingChunk owner, ChunkSection chunkSection, boolean hasSkyLight) {
        this.owner = owner;

        if (owner.neighbors.hasAll()) {
            // Block light data (is re-initialized in the fill operation below, no need to read)
            this.blockLight = NibbleArrayHandle.createNew();

            // Sky light data (is re-initialized using heightmap operation later, no need to read)
            this.skyLight = NibbleArrayHandle.createNew();
        } else {
            // We need to load the original light data, because we have a border that we do not update

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
     * @return future completed when saving is finished. Future resolves to False if no changes occurred, True otherwise.
     */
    @SuppressWarnings("deprecation")
    public CompletableFuture<Boolean> saveToChunk(ChunkSection chunkSection) {
        CompletableFuture<Void> blockLightFuture = null;
        CompletableFuture<Void> skyLightFuture = null;

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

            //TODO: Maybe do blockLightChanged check inside BKCommonLib?
            if (blockLightChanged) {
                // Async method since BKCommonLib 1.15.2-v2
                if (HAS_ASYNC_SET_LIGHT_METHODS) {
                    try {
                        blockLightFuture = WorldUtil.setSectionBlockLightAsync(owner.world,
                                owner.chunkX, chunkSection.getY(), owner.chunkZ,
                                newBlockLight);
                    } catch (NoSuchMethodError err) {
                        HAS_ASYNC_SET_LIGHT_METHODS = false;
                    }
                }

                // Fallback for older BKCommonLib: schedule on main thread
                if (!HAS_ASYNC_SET_LIGHT_METHODS) {
                    final CompletableFuture<Void> future = new CompletableFuture<Void>();
                    CommonUtil.nextTick(() -> {
                        WorldUtil.setSectionBlockLight(owner.world,
                                owner.chunkX, chunkSection.getY(), owner.chunkZ,
                                newBlockLight);
                        future.complete(null);
                    });
                    blockLightFuture = future;
                }
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

            //TODO: Maybe do skyLightChanged check inside BKCommonLib?
            if (skyLightChanged) {
                // Async method since BKCommonLib 1.15.2-v2
                if (HAS_ASYNC_SET_LIGHT_METHODS) {
                    try {
                        skyLightFuture = WorldUtil.setSectionSkyLightAsync(owner.world,
                            owner.chunkX, chunkSection.getY(), owner.chunkZ,
                            newSkyLight);
                    } catch (NoSuchMethodError err) {
                        HAS_ASYNC_SET_LIGHT_METHODS = false;
                    }
                }

                // Fallback for older BKCommonLib: schedule on main thread
                if (!HAS_ASYNC_SET_LIGHT_METHODS) {
                    final CompletableFuture<Void> future = new CompletableFuture<Void>();
                    CommonUtil.nextTick(() -> {
                        WorldUtil.setSectionSkyLight(owner.world,
                                owner.chunkX, chunkSection.getY(), owner.chunkZ,
                                newSkyLight);
                        future.complete(null);
                    });
                    skyLightFuture = future;
                }
            }
        }

        // No updates performed
        if (blockLightFuture == null && skyLightFuture == null) {
            return CompletableFuture.completedFuture(Boolean.FALSE);
        }

        // Join both completable futures as one, if needed
        CompletableFuture<Void> combined;
        if (blockLightFuture == null) {
            combined = skyLightFuture;
        } else if (skyLightFuture == null) {
            combined = blockLightFuture;
        } else {
            combined = CompletableFuture.allOf(blockLightFuture, skyLightFuture);
        }

        // When combined resolves, return one that returns True
        return combined.thenApply((c) -> Boolean.TRUE);
    }

}
