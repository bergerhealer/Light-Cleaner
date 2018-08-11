package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.bases.IntVector2;
import com.bergerkiller.bukkit.common.conversion.type.HandleConversion;
import com.bergerkiller.bukkit.common.utils.ChunkUtil;
import com.bergerkiller.bukkit.common.wrappers.ChunkSection;
import com.bergerkiller.bukkit.common.wrappers.HeightMap;
import com.bergerkiller.bukkit.lightcleaner.LightCleaner;
import com.bergerkiller.generated.net.minecraft.server.ChunkHandle;
import com.bergerkiller.generated.net.minecraft.server.NibbleArrayHandle;
import com.bergerkiller.generated.net.minecraft.server.WorldHandle;
import com.bergerkiller.mountiplex.reflection.MethodAccessor;
import com.bergerkiller.mountiplex.reflection.SafeDirectMethod;
import com.bergerkiller.mountiplex.reflection.SafeField;
import com.bergerkiller.mountiplex.reflection.SafeMethod;
import com.bergerkiller.mountiplex.reflection.declarations.Template;

import org.bukkit.Chunk;

import java.util.Arrays;
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
    public static final int SECTION_COUNT = 16;
    public static final int OB = ~0xf; // Outside blocks
    public static final int OC = ~0xff; // Outside chunk
    public final LightingChunkSection[] sections = new LightingChunkSection[SECTION_COUNT];
    public final LightingChunkNeighboring neighbors = new LightingChunkNeighboring();
    public final int[] heightmap = new int[256];
    public final int chunkX, chunkZ;
    public boolean hasSkyLight = true;
    public boolean isSkyLightDirty = true;
    public boolean isBlockLightDirty = true;
    public boolean isFilled = false;
    public boolean isApplied = false;
    public IntVector2 start = new IntVector2(1, 1);
    public IntVector2 end = new IntVector2(14, 14);

    public LightingChunk(int x, int z) {
        this.chunkX = x;
        this.chunkZ = z;
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
        hasSkyLight = WorldHandle.fromBukkit(chunk.getWorld()).getWorldProvider().hasSkyLight();
        ChunkSection[] chunkSections = ChunkUtil.getSections(chunk);
        for (int section = 0; section < SECTION_COUNT; section++) {
            ChunkSection chunkSection = chunkSections[section];
            if (chunkSection != null) {
                sections[section] = new LightingChunkSection(chunk.getWorld(), this, chunkSection, hasSkyLight);
            }
        }

        // Initialize and then load sky light heightmap information
        if (this.hasSkyLight) {
            HeightMap heightmap = ChunkUtil.getHeightMap(chunk, HeightMap.Type.LIGHT_BLOCKING);
            heightmap.initialize();
            for (int x = 0; x < 16; ++x) {
                for (int z = 0; z < 16; ++z) {
                    this.heightmap[this.getHeightKey(x, z)] = heightmap.getHeight(x, z);
                }
            }
        } else {
            Arrays.fill(this.heightmap, ChunkHandle.fromBukkit(chunk).getTopSliceY() + 15);
        }

        this.isFilled = true;
    }

    private int getTopY() {
        for (int section = SECTION_COUNT; section > 0; section--) {
            if (sections[section - 1] != null) {
                return (section << 4) - 1;
            }
        }
        return 0;
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
        return heightmap[getHeightKey(x, z)] & 0xff;
    }

    /**
     * Initializes the sky lighting and generates the heightmap
     */
    public void initLight() {
        if (!hasSkyLight) {
            return;
        }

        // Find out the highest possible Y-position
        int topY = getTopY();
        int x, y, z, light, height, opacity;
        LightingChunkSection section;
        // Apply initial sky lighting from top to bottom
        for (x = start.x; x <= end.x; x++) {
            for (z = start.z; z <= end.z; z++) {
                light = 15;
                height = getHeight(x, z);
                for (y = topY; y >= 0; y--) {
                    if ((section = sections[y >> 4]) == null) {
                        // Skip the remaining 15: they are all inaccessible as well
                        y -= 15;
                        continue;
                    }

                    // Only process these once below the height at this x/z
                    if (y <= height) {
                        // Apply the opacity to the light level
                        opacity = section.opacity.get(x, y & 0xf, z);
                        if (light <= 0 || (light -= opacity) <= 0) {
                            light = 0;
                        }
                    }

                    // Apply sky light to block
                    section.skyLight.set(x, y & 0xf, z, light);
                }
            }
        }
    }


    private final int getMaxLightLevel(LightingChunkSection section, boolean skyLight, int lightLevel, int x, int y, int z) {
        if (x >= 1 && z >= 1 && x <= 14 && z <= 14) {
            // All within this chunk - simplified calculation
            final int dy = y & 0xf;
            final NibbleArrayHandle light = skyLight ? section.skyLight : section.blockLight;
            lightLevel = Math.max(lightLevel, light.get(x - 1, dy, z));
            lightLevel = Math.max(lightLevel, light.get(x + 1, dy, z));
            lightLevel = Math.max(lightLevel, light.get(x, dy, z - 1));
            lightLevel = Math.max(lightLevel, light.get(x, dy, z + 1));

            // If dy is also within this section, we can simplify it
            if (dy >= 1 && dy <= 14) {
                lightLevel = Math.max(lightLevel, light.get(x, dy - 1, z));
                lightLevel = Math.max(lightLevel, light.get(x, dy + 1, z));
                return lightLevel;
            }
        } else {
            // Crossing chunk boundaries - requires neighbor checks
            lightLevel = Math.max(lightLevel, getLightLevel(skyLight, x - 1, y, z));
            lightLevel = Math.max(lightLevel, getLightLevel(skyLight, x + 1, y, z));
            lightLevel = Math.max(lightLevel, getLightLevel(skyLight, x, y, z - 1));
            lightLevel = Math.max(lightLevel, getLightLevel(skyLight, x, y, z + 1));
        }

        // Slice below
        if (y >= 1) {
            final LightingChunkSection sectionBelow = this.sections[(y - 1) >> 4];
            if (sectionBelow != null) {
                lightLevel = Math.max(lightLevel, sectionBelow.getLight(skyLight, x, (y - 1) & 0xf, z));
            }
        }

        // Slice above
        if (y <= 254) {
            final LightingChunkSection sectionAbove = this.sections[(y + 1) >> 4];
            if (sectionAbove != null) {
                lightLevel = Math.max(lightLevel, sectionAbove.getLight(skyLight, x, (y + 1) & 0xf, z));
            }
        }

        return lightLevel;
    }

    private final int getLightLevel(boolean skyLight, int x, int y, int z) {
        // Outside the blocks space of this chunk?
        final LightingChunk chunk = (x & OB | z & OB) == 0 ? this : neighbors.get(x >> 4, z >> 4);
        final LightingChunkSection section = chunk.sections[y >> 4];
        return section == null ? 0 : section.getLight(skyLight, x & 0xf, y & 0xf, z & 0xf);
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
                count += spread(true);
            }
            if (isBlockLightDirty) {
                count += spread(false);
            }
            return count;
        } else {
            return 0;
        }
    }

    private int spread(boolean skyLight) {
        if (skyLight && !hasSkyLight) {
            this.isSkyLightDirty = false;
            return 0;
        }
        int x, y, z, light, factor, startY, newlight;
        int loops = -1;
        int lasterrx = 0, lasterry = 0, lasterrz = 0;
        final int maxY = getTopY();
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
        do {
            haserror = false;
            if (++loops > 100) {
                lasterrx += this.chunkX << 4;
                lasterrz += this.chunkZ << 4;
                StringBuilder msg = new StringBuilder();
                msg.append("Failed to fix all " + (skyLight ? "Sky" : "Block") + " lighting at [");
                msg.append(lasterrx).append('/').append(lasterry);
                msg.append('/').append(lasterrz).append(']');
                LightCleaner.plugin.log(Level.WARNING, msg.toString());
                break;
            }

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
                    startY = skyLight ? getHeight(x, z) : maxY;
                    for (y = startY; y > 0; y--) {
                        if ((chunksection = this.sections[y >> 4]) == null) {
                            // Skip this section entirely by setting y to the bottom of the section
                            y &= ~0xf;
                            continue;
                        }
                        factor = Math.max(1, chunksection.opacity.get(x, y & 0xf, z));
                        if (factor == 15) {
                            continue;
                        }

                        // Read the old light level and try to find a light level around it that exceeds
                        light = chunksection.getLight(skyLight, x, y & 0xf, z);
                        newlight = light + factor;
                        if (newlight < 15) {
                            newlight = getMaxLightLevel(chunksection, skyLight, newlight, x, y, z);
                        }
                        newlight -= factor;

                        // pick the highest value
                        if (newlight > light) {
                            chunksection.setLight(skyLight, x, y & 0xf, z, newlight);
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
        } while (haserror);

        if (skyLight) {
            this.isSkyLightDirty = false;
        } else {
            this.isBlockLightDirty = false;
        }

        // When we change blocks at our chunk borders, neighbours have to do another spread cycle
        if (err_neigh_nx) markNeighbor(-1, 0, skyLight);
        if (err_neigh_px) markNeighbor(1, 0, skyLight);
        if (err_neigh_nz) markNeighbor(0, -1, skyLight);
        if (err_neigh_pz) markNeighbor(0, 1, skyLight);

        return loops;
    }

    private void markNeighbor(int dx, int dz, boolean skyLight) {
        LightingChunk n = neighbors.get(dx, dz);
        if (n != null) {
            if (skyLight) {
                n.isSkyLightDirty = true;
            } else {
                n.isBlockLightDirty = true;
            }
        }
    }

    /**
     * Applies the lighting information to a chunk
     *
     * @param chunk to save to
     * @return whether the chunk had any corrected light levels
     */
    public boolean saveToChunk(Chunk chunk) {
        ChunkSection[] chunkSections = ChunkUtil.getSections(chunk);
        boolean hasChanges = false;
        for (int section = 0; section < SECTION_COUNT; section++) {
            if (chunkSections[section] != null && sections[section] != null) {
                hasChanges |= sections[section].saveToChunk(chunkSections[section]);
            }
        }
        if (hasChanges) {
            // Call markDirty() on the chunk
            markDirtyMethod.invoke(HandleConversion.toChunkHandle(chunk));
        }
        this.isApplied = true;
        return hasChanges;
    }

    /*
     * markDirty() initialization and fallback for older BKCommonLib versions
     */
    private static final MethodAccessor<Void> markDirtyMethod = findMarkDirtyMethod();

    private static MethodAccessor<Void> findMarkDirtyMethod() {
        // Find in the most recent BKCommonLib
        if (SafeField.contains(ChunkHandle.T.getClass(), "markDirty", Template.Method.class)) {
            Template.Method<?> bkcMethod = SafeField.get(ChunkHandle.T, "markDirty", Template.Method.class);
            return bkcMethod.toMethodAccessor();
        }

        // Fallback only officially supports MC 1.8.8 - MC1.12.2
        if (SafeMethod.contains(ChunkHandle.T.getType(), "markDirty")) {
            // >= MC1.12
            return new SafeMethod<Void>(ChunkHandle.T.getType(), "markDirty");
        } else if (SafeMethod.contains(ChunkHandle.T.getType(), "e")) {
            // < MC1.12
            return new SafeMethod<Void>(ChunkHandle.T.getType(), "e");
        } else {
            // No idea :(
            return new SafeDirectMethod<Void>() {
                @Override
                public Void invoke(Object arg0, Object... arg1) {
                    return null;
                }
            };
        }
    }
}
