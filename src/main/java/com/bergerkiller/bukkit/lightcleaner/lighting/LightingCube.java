package com.bergerkiller.bukkit.lightcleaner.lighting;

import java.util.concurrent.CompletableFuture;

import com.bergerkiller.bukkit.common.Timings;
import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.collections.BlockFaceSet;
import com.bergerkiller.bukkit.common.utils.CommonUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.BlockData;
import com.bergerkiller.bukkit.common.wrappers.ChunkSection;
import com.bergerkiller.bukkit.lightcleaner.LCTimings;
import com.bergerkiller.bukkit.lightcleaner.util.BlockFaceSetSection;
import com.bergerkiller.generated.net.minecraft.world.level.chunk.NibbleArrayHandle;

/**
 * A single 16x16x16 cube of stored block information
 */
public class LightingCube {
    public static IntVector3 DEBUG_BLOCK = null; // logs light levels of blocks
    public static final int OOC = ~0xf; // Outside Of Cube
    public final LightingChunk owner;
    public final LightingCubeNeighboring neighbors = new LightingCubeNeighboring();
    public final int cy;
    public final NibbleArrayHandle skyLight;
    public final NibbleArrayHandle blockLight;
    public final NibbleArrayHandle emittedLight;
    public final NibbleArrayHandle opacity;
    private final BlockFaceSetSection opaqueFaces;

    // Memory optimization for all-air cubes
    private static final NibbleArrayHandle ALL_ZERO_NIBBLE_ARRAY = NibbleArrayHandle.createNew();
    private static final BlockFaceSetSection ALL_TRANSPARENT_OPAQUE_FACES = new BlockFaceSetSection();

    private LightingCube(Data currentData) {
        this.owner = currentData.owner;
        this.skyLight = currentData.currentSkyLight;
        this.blockLight = currentData.currentBlockLight;
        this.cy = currentData.cy;

        // Don't do anything more if there is no block data
        if (currentData.chunkSection == null) {
            this.opacity = ALL_ZERO_NIBBLE_ARRAY;
            this.emittedLight = ALL_ZERO_NIBBLE_ARRAY;
            this.opaqueFaces = ALL_TRANSPARENT_OPAQUE_FACES;
            return;
        }

        // World coordinates
        int worldX = owner.chunkX << 4;
        int worldY = currentData.chunkSection.getYPosition();
        int worldZ = owner.chunkZ << 4;

        // Fill opacity and initial block lighting values
        this.opacity = NibbleArrayHandle.createNew();
        this.emittedLight = NibbleArrayHandle.createNew();
        this.opaqueFaces = new BlockFaceSetSection();
        int x, y, z, opacity, blockEmission;
        BlockFaceSet opaqueFaces;
        BlockData info;
        for (z = owner.start.z; z <= owner.end.z; z++) {
            for (x = owner.start.x; x <= owner.end.x; x++) {
                for (y = 0; y < 16; y++) {
                    info = currentData.chunkSection.getBlockData(x, y, z);
                    blockEmission = info.getEmission();
                    opacity = info.getOpacity(owner.world, worldX+x, worldY+y, worldZ+z);
                    if (opacity >= 0xf) {
                        opacity = 0xf;
                        opaqueFaces = BlockFaceSet.ALL;
                    } else {
                        if (opacity < 0) {
                            opacity = 0;
                        }
                        opaqueFaces = info.getOpaqueFaces(owner.world, worldX+x, worldY+y, worldZ+z);
                    }

                    this.opacity.set(x, y, z, opacity);
                    this.emittedLight.set(x, y, z, blockEmission);
                    this.blockLight.set(x, y, z, blockEmission);
                    this.opaqueFaces.set(x, y, z, opaqueFaces);
                }
            }
        }
    }

    /**
     * Gets the opaque faces of a block
     * 
     * @param x        - coordinate
     * @param y        - coordinate
     * @param z        - coordinate
     * @return opaque face set
     */
    public BlockFaceSet getOpaqueFaces(int x, int y, int z) {
        return this.opaqueFaces.get(x, y, z);
    }

    /**
     * Read light level of a neighboring block.
     * If possibly more, also check opaque faces, and then return the
     * higher light value if all these tests pass.
     * The x/y/z coordinates are allowed to check neighboring cubes.
     * 
     * @param category
     * @param old_light
     * @param faceMask
     * @param x The X-coordinate of the block (-1 to 16)
     * @param y The Y-coordinate of the block (-1 to 16)
     * @param z The Z-coordinate of the block (-1 to 16)
     * @return higher light level if propagated, otherwise the old light value
     */
    public int getLightIfHigherNeighbor(LightingCategory category, int old_light, int faceMask, int x, int y, int z) {
        if ((x & OOC | y & OOC | z & OOC) == 0) {
            return this.getLightIfHigher(category, old_light, faceMask, x, y, z);
        } else {
            LightingCube neigh = this.neighbors.get(x>>4, y>>4, z>>4);
            if (neigh != null) {
                return neigh.getLightIfHigher(category, old_light, faceMask, x & 0xf, y & 0xf, z & 0xf);
            } else {
                return old_light;
            }
        }
    }

    /**
     * Read light level of a neighboring block.
     * If possibly more, also check opaque faces, and then return the
     * higher light value if all these tests pass.
     * Requires the x/y/z coordinates to lay within this cube.
     * 
     * @param category Category of light to check
     * @param old_light Previous light value
     * @param faceMask The BlockFaceSet mask indicating the light-traveling direction
     * @param x The X-coordinate of the block (0 to 15)
     * @param y The Y-coordinate of the block (0 to 15)
     * @param z The Z-coordinate of the block (0 to 15)
     * @return higher light level if propagated, otherwise the old light value
     */
    public int getLightIfHigher(LightingCategory category, int old_light, int faceMask, int x, int y, int z) {
        int new_light_level = category.get(this, x, y, z);
        return (new_light_level > old_light && !this.getOpaqueFaces(x, y, z).get(faceMask))
                ? new_light_level : old_light;
    }

    /**
     * Called during initialization of block light to spread the light emitted by a block
     * to all neighboring blocks.
     * 
     * @param x The X-coordinate of the block (0 to 15)
     * @param y The Y-coordinate of the block (0 to 15)
     * @param z The Z-coordinate of the block (0 to 15)
     */
    public void spreadBlockLight(int x, int y, int z) {
        int emitted = this.emittedLight.get(x, y, z);
        if (emitted <= 1) {
            return; // Skip if neighbouring blocks won't receive light from it
        }
        if (x >= 1 && z >= 1 && x <= 14 && z <= 14) {
            trySpreadBlockLightWithin(emitted, BlockFaceSet.MASK_EAST,  x-1, y, z);
            trySpreadBlockLightWithin(emitted, BlockFaceSet.MASK_WEST,  x+1, y, z);
            trySpreadBlockLightWithin(emitted, BlockFaceSet.MASK_SOUTH, x, y, z-1);
            trySpreadBlockLightWithin(emitted, BlockFaceSet.MASK_NORTH, x, y, z+1);
        } else {
            trySpreadBlockLight(emitted, BlockFaceSet.MASK_EAST,  x-1, y, z);
            trySpreadBlockLight(emitted, BlockFaceSet.MASK_WEST,  x+1, y, z);
            trySpreadBlockLight(emitted, BlockFaceSet.MASK_SOUTH, x, y, z-1);
            trySpreadBlockLight(emitted, BlockFaceSet.MASK_NORTH, x, y, z+1);
        }
        if (y >= 1 && y <= 14) {
            trySpreadBlockLightWithin(emitted, BlockFaceSet.MASK_UP,    x, y-1, z);
            trySpreadBlockLightWithin(emitted, BlockFaceSet.MASK_DOWN,  x, y+1, z);
        } else {
            trySpreadBlockLight(emitted, BlockFaceSet.MASK_UP,   x, y-1, z);
            trySpreadBlockLight(emitted, BlockFaceSet.MASK_DOWN, x, y+1, z);
        }
    }

    /**
     * Tries to spread block light from an emitting block to one of the 6 sites.
     * The block being spread to is allowed to be outside of the bounds of this cube,
     * in which case neighboring cubes are spread to instead.
     * 
     * @param emitted The light that is emitted by the block
     * @param faceMask The BlockFaceSet mask indicating the light-traveling direction
     * @param x The X-coordinate of the block to spread to (-1 to 16)
     * @param y The Y-coordinate of the block to spread to (-1 to 16)
     * @param z The Z-coordinate of the block to spread to (-1 to 16)
     */
    public void trySpreadBlockLight(int emitted, int faceMask, int x, int y, int z) {
        if ((x & OOC | y & OOC | z & OOC) == 0) {
            this.trySpreadBlockLightWithin(emitted, faceMask, x, y, z);
        } else {
            LightingCube neigh = this.neighbors.get(x>>4, y>>4, z>>4);
            if (neigh != null) {
                neigh.trySpreadBlockLightWithin(emitted, faceMask, x & 0xf, y & 0xf, z & 0xf);
            }
        }
    }

    /**
     * Tries to spread block light from an emitting block to one of the 6 sides.
     * Assumes that the block being spread to is within this cube.
     * 
     * @param emitted The light that is emitted by the block
     * @param faceMask The BlockFaceSet mask indicating the light-traveling direction
     * @param x The X-coordinate of the block to spread to (0 to 15)
     * @param y The Y-coordinate of the block to spread to (0 to 15)
     * @param z The Z-coordinate of the block to spread to (0 to 15)
     */
    public void trySpreadBlockLightWithin(int emitted, int faceMask, int x, int y, int z) {
        if (!this.getOpaqueFaces(x, y, z).get(faceMask)) {
            int new_level = emitted - Math.max(1,  this.opacity.get(x, y, z));
            if (new_level > this.blockLight.get(x, y, z)) {
                this.blockLight.set(x, y, z, new_level);
            }
        }
    }

    /**
     * Applies the lighting information to a chunk section
     *
     * @param force whether to force the save, even when light wasn't changed
     * @return future completed when saving is finished. Future resolves to False if no changes occurred, True otherwise.
     */
    public CompletableFuture<Boolean> saveToChunk(boolean force) {
        CompletableFuture<Void> blockLightFuture = null;
        CompletableFuture<Void> skyLightFuture = null;

        try {
            if (this.blockLight != null) {
                byte[] newBlockLight = this.blockLight.getData();
                byte[] oldBlockLight = WorldUtil.getSectionBlockLight(owner.world,
                        owner.chunkX, this.cy, owner.chunkZ);
                boolean blockLightChanged = false;
                if (force || oldBlockLight == null || newBlockLight.length != oldBlockLight.length) {
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
                    blockLightFuture = WorldUtil.setSectionBlockLightAsync(owner.world,
                            owner.chunkX, this.cy, owner.chunkZ,
                            newBlockLight);
                }
            }
            if (this.skyLight != null) {
                byte[] newSkyLight = this.skyLight.getData();
                byte[] oldSkyLight = WorldUtil.getSectionSkyLight(owner.world,
                        owner.chunkX, this.cy, owner.chunkZ);
                boolean skyLightChanged = false;
                if (force || oldSkyLight == null || newSkyLight.length != oldSkyLight.length) {
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
                    skyLightFuture = WorldUtil.setSectionSkyLightAsync(owner.world,
                            owner.chunkX, this.cy, owner.chunkZ,
                            newSkyLight);
                }
            }
        } catch (Throwable t) {
            CompletableFuture<Boolean> exceptionally = new CompletableFuture<Boolean>();
            exceptionally.completeExceptionally(t);
            return exceptionally;
        }

        // Debug block
        if (DEBUG_BLOCK != null &&
            DEBUG_BLOCK.getChunkX() == this.owner.chunkX &&
            DEBUG_BLOCK.getChunkY() == this.cy &&
            DEBUG_BLOCK.getChunkZ() == this.owner.chunkZ
        ) {
            String message = "Block [" + DEBUG_BLOCK.x + "/" + DEBUG_BLOCK.y + "/" + DEBUG_BLOCK.z + "]";
            if (this.skyLight != null) {
                int level = this.skyLight.get(DEBUG_BLOCK.x & 0xf, DEBUG_BLOCK.y & 0xf, DEBUG_BLOCK.z & 0xf);
                message += " SkyLight=" + level;
                if (skyLightFuture != null) {
                    message += " [changed]";
                }
            }
            if (this.blockLight != null) {
                int level = this.blockLight.get(DEBUG_BLOCK.x & 0xf, DEBUG_BLOCK.y & 0xf, DEBUG_BLOCK.z & 0xf);
                message += " BlockLight=" + level;
                if (blockLightFuture != null) {
                    message += " [changed]";
                }
            }
            CommonUtil.broadcast(message);
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

    /**
     * Stores just the lighting information read from the chunk
     */
    public static class Data {
        public final LightingChunk owner;
        public final int cy;
        public final ChunkSection chunkSection;
        public final NibbleArrayHandle currentSkyLight;
        public final NibbleArrayHandle currentBlockLight;

        private Data(LightingChunk owner, int cy, ChunkSection chunkSection) {
            this.owner = owner;
            this.cy = cy;
            this.chunkSection = chunkSection;

            if (owner.neighbors.hasAll()) {
                // Block light data (is re-initialized in the fill operation below, no need to read)
                this.currentBlockLight = NibbleArrayHandle.createNew();

                // Sky light data (is re-initialized using heightmap operation later, no need to read)
                if (owner.hasSkyLight) {
                    this.currentSkyLight = NibbleArrayHandle.createNew();
                } else {
                    this.currentSkyLight = null;
                }
            } else {
                // We need to load the original light data, because we have a border that we do not update

                // Block light data
                byte[] blockLightData = WorldUtil.getSectionBlockLight(owner.world,
                        owner.chunkX, cy, owner.chunkZ);
                if (blockLightData != null) {
                    this.currentBlockLight = NibbleArrayHandle.createNew(blockLightData);
                } else {
                    this.currentBlockLight = NibbleArrayHandle.createNew();
                }

                // Sky light data
                if (owner.hasSkyLight) {
                    byte[] skyLightData = WorldUtil.getSectionSkyLight(owner.world,
                            owner.chunkX, cy, owner.chunkZ);
                    if (skyLightData != null) {
                        this.currentSkyLight = NibbleArrayHandle.createNew(skyLightData);
                    } else {
                        this.currentSkyLight = NibbleArrayHandle.createNew();
                    }
                } else {
                    this.currentSkyLight = null;
                }
            }
        }

        public LightingCube build() {
            return new LightingCube(this);
        }

        public static Data create(LightingChunk owner, int cy, ChunkSection chunkSection) {
            try (Timings t = LCTimings.FILL_CUBE_LIGHT.start()) {
                return new Data(owner, cy, chunkSection);
            }
        }
    }
}
