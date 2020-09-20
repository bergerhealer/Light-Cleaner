package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.bases.IntVector2;
import com.bergerkiller.bukkit.common.chunk.ForcedChunk;
import com.bergerkiller.bukkit.common.collections.BlockFaceSet;
import com.bergerkiller.bukkit.common.utils.ChunkUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.wrappers.ChunkSection;
import com.bergerkiller.bukkit.common.wrappers.HeightMap;
import com.bergerkiller.bukkit.lightcleaner.LightCleaner;
import com.bergerkiller.generated.net.minecraft.server.ChunkHandle;

import org.bukkit.Chunk;
import org.bukkit.World;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Represents a single chunk full with lighting-relevant information.
 * Initialization and use of this chunk in the process is as follows:<br>
 * - New lighting chunks are created for all chunks to be processed<br>
 * - notifyAccessible is called for all chunks, passing in all chunks<br>
 * - fill/fillSection is called for all chunks, after which initLight is called<br>
 * - spread is called on all chunks until all spreading is finished<br>
 * - data from all LightingChunks/Sections is gathered and saved to chunks or region files<br>
 * - possible chunk resends are performed
 */
public class LightingChunk {
    public static final int OB = ~0xf; // Outside blocks
    public static final int OC = ~0xff; // Outside chunk
    public LightingChunkSection[] sections;
    public final LightingChunkNeighboring neighbors = new LightingChunkNeighboring();
    public final int[] heightmap = new int[256];
    public final World world;
    public final int chunkX, chunkZ;
    public boolean hasSkyLight = true;
    public boolean isSkyLightDirty = true;
    public boolean isBlockLightDirty = true;
    public boolean isFilled = false;
    public boolean isApplied = false;
    public IntVector2 start = new IntVector2(1, 1);
    public IntVector2 end = new IntVector2(14, 14);
    public int maxY = 0;
    public final ForcedChunk forcedChunk = ForcedChunk.none();
    public volatile boolean loadingStarted = false;

    public LightingChunk(World world, int x, int z) {
        this.world = world;
        this.chunkX = x;
        this.chunkZ = z;
    }

    /**
     * Gets the number of 16-high chunk sections used.
     * This is normally 16.
     * 
     * @return section count
     */
    public int getSectionCount() {
        return this.sections.length;
    }

    /**
     * Gets the chunk section at a given y-coordinate.
     * If no section is stored here, null is returned.
     * 
     * @param y Block y coordinate
     * @return section at block y
     */
    public LightingChunkSection getSectionAtY(int y) {
        int cy = y >> 4;
        return (cy >= 0 && cy < this.sections.length) ? this.sections[cy] : null;
    }

    /**
     * Notifies that a new chunk is accessible.
     *
     * @param chunk that is accessible
     */
    public void notifyAccessible(LightingChunk chunk) {
        final int dx = chunk.chunkX - this.chunkX;
        final int dz = chunk.chunkZ - this.chunkZ;
        // Only check neighbours, ignoring the corners and self
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1 || (dx != 0) == (dz != 0)) {
            return;
        }
        // Results in -16, 16 or 0 for the x/z coordinates
        neighbors.set(dx, dz, chunk);
        // Update start/end coordinates
        if (dx == 1) {
            end = new IntVector2(15, end.z);
        } else if (dx == -1) {
            start = new IntVector2(0, start.z);
        } else if (dz == 1) {
            end = new IntVector2(end.x, 15);
        } else if (dz == -1) {
            start = new IntVector2(start.x, 0);
        }
    }

    public void fill(Chunk chunk) {
        // Fill using chunk sections
        hasSkyLight = WorldUtil.getDimensionType(chunk.getWorld()).hasSkyLight();
        ChunkSection[] chunkSections = ChunkUtil.getSections(chunk);
        this.sections = new LightingChunkSection[chunkSections.length];
        for (int section = 0; section < getSectionCount(); section++) {
            ChunkSection chunkSection = chunkSections[section];
            if (chunkSection != null) {
                sections[section] = new LightingChunkSection(this, chunkSection, hasSkyLight);
            }
        }

        // Compute max y using sections that are available
        this.maxY = 0;
        for (int section = getSectionCount(); section > 0; section--) {
            if (sections[section - 1] != null) {
                this.maxY = (section << 4) - 1;
                break;
            }
        }

        // Initialize and then load sky light heightmap information
        if (this.hasSkyLight) {
            HeightMap heightmap = ChunkUtil.getLightHeightMap(chunk, true);
            for (int x = 0; x < 16; ++x) {
                for (int z = 0; z < 16; ++z) {
                    this.heightmap[this.getHeightKey(x, z)] = Math.max(0, heightmap.getHeight(x, z));
                }
            }
        } else {
            Arrays.fill(this.heightmap, ChunkHandle.fromBukkit(chunk).getTopSliceY() + 15);
        }

        this.isFilled = true;
    }

    private int getHeightKey(int x, int z) {
        return x | (z << 4);
    }

    /**
     * Gets the height level (the top block that does not block light)
     *
     * @param x - coordinate
     * @param z - coordinate
     * @return height
     */
    public int getHeight(int x, int z) {
        return this.heightmap[getHeightKey(x, z)];
    }

    // Checks for a higher light level, potentially outside of the current chunk section, on the same x/z
    // Selects the appropriate chunk slice of this chunk
    private int getLightIfHigherVertical(LightingCategory category, int old_light, int faceMask, int x, int y, int z) {
        final LightingChunkSection section = getSectionAtY(y);
        return (section == null) ? old_light : getLightIfHigher(category, section, old_light, faceMask, x, y & 0xf, z);
    }

    // Checks for a higher light level, potentially outside of this chunk, on the same y level
    // Selects the appropriate neighbouring chunk
    private int getLightIfHigherNeighbour(LightingCategory category, int old_light, int faceMask, int x, int y, int z) {
        final LightingChunk chunk = (x & OB | z & OB) == 0 ? this : neighbors.get(x >> 4, z >> 4);
        final LightingChunkSection section = chunk.getSectionAtY(y);
        return (section == null) ? old_light : getLightIfHigher(category, section, old_light, faceMask, x & 0xf, y & 0xf, z & 0xf);
    }

    // Read light level. If possibly more, also check opaque faces, and then return the higher light value if passed
    private static int getLightIfHigher(LightingCategory category, LightingChunkSection section, int old_light, int faceMask, int x, int y, int z) {
        int new_light_level = category.get(section, x, y, z);
        return (new_light_level > old_light && !section.getOpaqueFaces(x, y, z).get(faceMask))
                ? new_light_level : old_light;
    }

    private final int getMaxLightLevel(LightingChunkSection section, LightingCategory category, int lightLevel, int x, int y, int z) {
        final int dy = y & 0xf;
        BlockFaceSet selfOpaqueFaces = section.getOpaqueFaces(x, dy, z);
        if (x >= 1 && z >= 1 && x <= 14 && z <= 14) {
            // All within this chunk - simplified calculation
            if (!selfOpaqueFaces.west()) {
                lightLevel = getLightIfHigher(category, section, lightLevel,
                        BlockFaceSet.MASK_EAST, x - 1, dy, z);
            }
            if (!selfOpaqueFaces.east()) {
                lightLevel = getLightIfHigher(category, section, lightLevel,
                        BlockFaceSet.MASK_WEST, x + 1, dy, z);
            }
            if (!selfOpaqueFaces.north()) {
                lightLevel = getLightIfHigher(category, section, lightLevel,
                        BlockFaceSet.MASK_SOUTH, x, dy, z - 1);
            }
            if (!selfOpaqueFaces.south()) {
                lightLevel = getLightIfHigher(category, section, lightLevel,
                        BlockFaceSet.MASK_NORTH, x, dy, z + 1);
            }

            // If dy is also within this section, we can simplify it
            if (dy >= 1 && dy <= 14) {
                if (!selfOpaqueFaces.down()) {
                    lightLevel = getLightIfHigher(category, section, lightLevel,
                            BlockFaceSet.MASK_UP, x, dy - 1, z);
                }
                if (!selfOpaqueFaces.up()) {
                    lightLevel = getLightIfHigher(category, section, lightLevel,
                            BlockFaceSet.MASK_DOWN, x, dy + 1, z);
                }
                return lightLevel;
            }
        } else {
            // Crossing chunk boundaries - requires neighbor checks
            if (!selfOpaqueFaces.west()) {
                lightLevel = getLightIfHigherNeighbour(category, lightLevel,
                        BlockFaceSet.MASK_EAST, x - 1, y, z);
            }
            if (!selfOpaqueFaces.east()) {
                lightLevel = getLightIfHigherNeighbour(category, lightLevel,
                        BlockFaceSet.MASK_WEST, x + 1, y, z);
            }
            if (!selfOpaqueFaces.north()) {
                lightLevel = getLightIfHigherNeighbour(category, lightLevel,
                        BlockFaceSet.MASK_SOUTH, x, y, z - 1);
            }
            if (!selfOpaqueFaces.south()) {
                lightLevel = getLightIfHigherNeighbour(category, lightLevel,
                        BlockFaceSet.MASK_NORTH, x, y, z + 1);
            }
        }

        // Slice below
        if (y >= 1 && !selfOpaqueFaces.down()) {
            lightLevel = getLightIfHigherVertical(category, lightLevel,
                    BlockFaceSet.MASK_UP, x, (y - 1), z);
        }

        // Slice above
        if (y <= 254 && !selfOpaqueFaces.up()) {
            lightLevel = getLightIfHigherVertical(category, lightLevel,
                    BlockFaceSet.MASK_DOWN, x, (y + 1), z);
        }

        return lightLevel;
    }

    // Used during block light initialization, to spread emitted light to neighbouring blocks
    public void spreadBlockLight(LightingChunkSection section, int x, int y, int z) {
        final int dy = y & 0xf;
        int emitted = section.emittedLight.get(x, dy, z);
        if (emitted <= 1) {
            return; // Skip if neighbouring blocks won't receive light from it
        }
        if (x >= 1 && z >= 1 && x <= 14 && z <= 14) {
            trySpreadBlockLightWithin(section, emitted, BlockFaceSet.MASK_EAST,  x-1, dy, z);
            trySpreadBlockLightWithin(section, emitted, BlockFaceSet.MASK_WEST,  x+1, dy, z);
            trySpreadBlockLightWithin(section, emitted, BlockFaceSet.MASK_SOUTH, x, dy, z-1);
            trySpreadBlockLightWithin(section, emitted, BlockFaceSet.MASK_NORTH, x, dy, z+1);
        } else {
            trySpreadBlockLight(emitted, BlockFaceSet.MASK_EAST,  x-1, y, z);
            trySpreadBlockLight(emitted, BlockFaceSet.MASK_WEST,  x+1, y, z);
            trySpreadBlockLight(emitted, BlockFaceSet.MASK_SOUTH, x, y, z-1);
            trySpreadBlockLight(emitted, BlockFaceSet.MASK_NORTH, x, y, z+1);
        }
        if (dy >= 1 && dy <= 14) {
            trySpreadBlockLightWithin(section, emitted, BlockFaceSet.MASK_UP,    x, dy-1, z);
            trySpreadBlockLightWithin(section, emitted, BlockFaceSet.MASK_DOWN,  x, dy+1, z);
        } else {
            trySpreadBlockLight(emitted, BlockFaceSet.MASK_UP,   x, y-1, z);
            trySpreadBlockLight(emitted, BlockFaceSet.MASK_DOWN, x, y+1, z);
        }
    }

    // This is used when chunk section must be selected first
    private void trySpreadBlockLight(int emitted, int faceMask, int x, int y, int z) {
        if (y >= 1 && y <= 254) {
            final LightingChunk chunk = (x & OB | z & OB) == 0 ? this : neighbors.get(x >> 4, z >> 4);
            final LightingChunkSection section = chunk.getSectionAtY(y);
            if (section != null) {
                trySpreadBlockLightWithin(section, emitted, faceMask, x & 0xf, y & 0xf, z & 0xf);
            }
        }
    }

    // This is used for blocks within the same chunk section
    private static void trySpreadBlockLightWithin(LightingChunkSection section, int emitted, int faceMask, int x, int y, int z) {
        if (!section.getOpaqueFaces(x, y, z).get(faceMask)) {
            int new_level = emitted - Math.max(1,  section.opacity.get(x, y, z));
            if (new_level > section.blockLight.get(x, y, z)) {
                section.blockLight.set(x, y, z, new_level);
            }
        }
    }

    /**
     * Gets whether this lighting chunk has faults that need to be fixed
     *
     * @return True if there are faults, False if not
     */
    public boolean hasFaults() {
        return isSkyLightDirty || isBlockLightDirty;
    }

    /**
     * Spreads the light from sources to 'zero' light level blocks
     *
     * @return Number of processing loops executed. 0 indicates no faults were found.
     */
    public int spread() {
        if (hasFaults()) {
            int count = 0;
            if (isSkyLightDirty) {
                count += spread(LightingCategory.SKY);
            }
            if (isBlockLightDirty) {
                count += spread(LightingCategory.BLOCK);
            }
            return count;
        } else {
            return 0;
        }
    }

    private int spread(LightingCategory category) {
        if ((category == LightingCategory.SKY) && !hasSkyLight) {
            this.isSkyLightDirty = false;
            return 0;
        }

        int x, y, z, light, factor, startY, newlight;
        int loops = 0;
        int lasterrx = 0, lasterry = 0, lasterrz = 0;
        boolean haserror;

        boolean err_neigh_nx = false;
        boolean err_neigh_px = false;
        boolean err_neigh_nz = false;
        boolean err_neigh_pz = false;

        LightingChunkSection chunksection;
        // Keep spreading the light in this chunk until it is done
        boolean mode = false;
        IntVector2 loop_start, loop_end;
        int loop_increment;
        while (true) {
            haserror = false;

            // Alternate iterating positive and negative
            // This allows proper optimized spreading in all directions
            mode = !mode;
            if (mode) {
                loop_start = start;
                loop_end = end.add(1, 1);
                loop_increment = 1;
            } else {
                loop_start = end;
                loop_end = start.subtract(1, 1);
                loop_increment = -1;
            }

            // Go through all blocks, using the heightmap for sky light to skip a few
            for (x = loop_start.x; x != loop_end.x; x += loop_increment) {
                for (z = loop_start.z; z != loop_end.z; z += loop_increment) {
                    startY = category.getStartY(this, x, z);
                    for (y = startY; y > 0; y--) {
                        if ((chunksection = getSectionAtY(y)) == null) {
                            // Skip this section entirely by setting y to the bottom of the section
                            y &= ~0xf;
                            continue;
                        }
                        factor = Math.max(1, chunksection.opacity.get(x, y & 0xf, z));
                        if (factor == 15) {
                            continue;
                        }

                        // Read the old light level and try to find a light level around it that exceeds
                        light = category.get(chunksection, x, y & 0xf, z);
                        newlight = light + factor;
                        if (newlight < 15) {
                            newlight = getMaxLightLevel(chunksection, category, newlight, x, y, z);
                        }
                        newlight -= factor;

                        // pick the highest value
                        if (newlight > light) {
                            category.set(chunksection, x, y & 0xf, z, newlight);
                            lasterrx = x;
                            lasterry = y;
                            lasterrz = z;
                            err_neigh_nx |= (x == 0);
                            err_neigh_nz |= (z == 0);
                            err_neigh_px |= (x == 15);
                            err_neigh_pz |= (z == 15);
                            haserror = true;
                        }
                    }
                }
            }

            if (!haserror) {
                break;
            } else if (++loops > 100) {
                lasterrx += this.chunkX << 4;
                lasterrz += this.chunkZ << 4;
                StringBuilder msg = new StringBuilder();
                msg.append("Failed to fix all " + category.getName() + " lighting at [");
                msg.append(lasterrx).append('/').append(lasterry);
                msg.append('/').append(lasterrz).append(']');
                LightCleaner.plugin.log(Level.WARNING, msg.toString());
                break;
            }
        }

        // Set self as no longer dirty, all light is good
        category.setDirty(this, false);

        // When we change blocks at our chunk borders, neighbours have to do another spread cycle
        if (err_neigh_nx) setNeighbourDirty(-1, 0, category);
        if (err_neigh_px) setNeighbourDirty(1, 0, category);
        if (err_neigh_nz) setNeighbourDirty(0, -1, category);
        if (err_neigh_pz) setNeighbourDirty(0, 1, category);

        return loops;
    }

    private void setNeighbourDirty(int dx, int dz, LightingCategory category) {
        LightingChunk n = neighbors.get(dx, dz);
        if (n != null) {
            category.setDirty(n, true);
        }
    }

    /**
     * Applies the lighting information to a chunk. The returned completable future is called
     * on the main thread when saving finishes.
     *
     * @param chunk to save to
     * @return completable future completed when the chunk is saved,
     *         with value True passed when saving occurred, False otherwise
     */
    @SuppressWarnings("unchecked")
    public CompletableFuture<Boolean> saveToChunk(Chunk chunk) {
        ChunkSection[] chunkSections = ChunkUtil.getSections(chunk);
        int numSectionsToApply = Math.min(chunkSections.length, getSectionCount());
        final CompletableFuture<Boolean>[] futures = new CompletableFuture[numSectionsToApply];
        for (int section = 0; section < numSectionsToApply; section++) {
            if (chunkSections[section] != null && sections[section] != null) {
                futures[section] = sections[section].saveToChunk(chunkSections[section]);
            } else {
                futures[section] = CompletableFuture.completedFuture(Boolean.FALSE);
            }
        }

        // When all of them complete, combine them into a single future
        // If any changes were made to the chunk, return True as completed value
        return CompletableFuture.allOf(futures).thenApply((o) -> {
            isApplied = true;

            try {
                for (CompletableFuture<Boolean> future : futures) {
                    if (future.get().booleanValue()) {
                        ChunkHandle.fromBukkit(chunk).markDirty();
                        return Boolean.TRUE;
                    }
                }
            } catch (Throwable t) {
                t.printStackTrace();
            }

            // None of the futures completed true
            return Boolean.FALSE;
        });
    }
}
