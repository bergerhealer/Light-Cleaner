package com.bergerkiller.bukkit.lightcleaner.lighting;

import com.bergerkiller.bukkit.common.collections.BlockFaceSet;

/**
 * Represents a category of light being processed. All conditional logic
 * for this is handled by this class.
 */
public enum LightingCategory {
    SKY() {
        @Override
        public String getName() {
            return "Sky";
        }

        @Override
        public void initialize(LightingChunk chunk) {
            if (!chunk.hasSkyLight) {
                return;
            }

            // Find out the highest possible Y-position
            int x, y, z, light, height, opacity;
            BlockFaceSet opaqueFaces;
            LightingChunkSection section;
            // Apply initial sky lighting from top to bottom
            for (z = chunk.start.z; z <= chunk.end.z; z++) {
                for (x = chunk.start.x; x <= chunk.end.x; x++) {
                    light = 15;
                    height = chunk.getHeight(x, z);
                    for (y = chunk.maxY; y >= 0; y--) {
                        if ((section = chunk.sections[y >> 4]) == null) {
                            // Skip the remaining 15: they are all inaccessible as well
                            y -= 15;

                            // If not full skylight, reset light level, assuming it dimmed out
                            if (light != 15) {
                                light = 0;
                            }
                            continue;
                        }

                        // Set quickly when light level is at 0, or we are above height level
                        if (y > height || light <= 0) {
                            section.skyLight.set(x, y & 0xf, z, light);
                            continue;
                        }

                        // If opaque at the top, set light to 0 instantly
                        opaqueFaces = section.getOpaqueFaces(x, y & 0xf, z);
                        if (opaqueFaces.up()) {
                            light = 0;
                        } else {
                            // Apply the opacity to the light level
                            opacity = section.opacity.get(x, y & 0xf, z);
                            if (light < 15 && opacity == 0) {
                                opacity = 1;
                            }
                            if ((light -= opacity) <= 0) {
                                light = 0;
                            }
                        }

                        // Apply sky light to block
                        section.skyLight.set(x, y & 0xf, z, light);

                        // If opaque at the bottom, reset light to 0 for next block
                        // The block itself is lit
                        if (opaqueFaces.down()) {
                            light = 0;
                        }
                    }
                }
            }
        }

        @Override
        public int getStartY(LightingChunk chunk, int x, int z) {
            return chunk.getHeight(x, z);
        }

        @Override
        public void setDirty(LightingChunk chunk, boolean dirty) {
            chunk.isSkyLightDirty = dirty;
        }

        @Override
        public int get(LightingChunkSection section, int x, int y, int z) {
            return  section.skyLight.get(x, y, z);
        }

        @Override
        public void set(LightingChunkSection section, int x, int y, int z, int level) {
            section.skyLight.set(x, y, z, level);
        }
    },
    BLOCK() {
        @Override
        public String getName() {
            return "Block";
        }

        @Override
        public void initialize(LightingChunk chunk) {
            // Some blocks that emit light, also have opaque faces
            // They still emit light through the opaque faces to other blocks
            // To fix this, run an initial processing step that spreads all
            // emitted light to the neighbouring blocks' block light, ignoring own opaque faces
            int y_off = 0, x, y, z;
            for (LightingChunkSection section : chunk.sections) {
                if (section != null) {
                    for (y = 0; y <= 15; y++) {
                        for (z = chunk.start.z; z <= chunk.end.z; z++) {
                            for (x = chunk.start.x; x <= chunk.end.x; x++) {
                                chunk.spreadBlockLight(section, x, y + y_off, z);
                            }
                        }
                    }
                }
                y_off += 16;
            }
        }

        @Override
        public int getStartY(LightingChunk chunk, int x, int z) {
            return chunk.maxY;
        }

        @Override
        public void setDirty(LightingChunk chunk, boolean dirty) {
            chunk.isBlockLightDirty = dirty;
        }

        @Override
        public int get(LightingChunkSection section, int x, int y, int z) {
            return section.blockLight.get(x, y, z);
        }

        @Override
        public void set(LightingChunkSection section, int x, int y, int z, int level) {
            section.blockLight.set(x, y, z, level);
        }
    };

    /**
     * Gets the name of this type of light, used when logging
     * 
     * @return category name
     */
    public abstract String getName();

    /**
     * Initializes the lighting in the chunk for this category
     * 
     * @param chunk
     */
    public abstract void initialize(LightingChunk chunk);

    /**
     * Gets the y-coordinate to start processing from when spreading light around
     * 
     * @param chunk
     * @param x
     * @param z
     * @return start y-coordinate
     */
    public abstract int getStartY(LightingChunk chunk, int x, int z);

    /**
     * Sets whether this category of light is dirty, indicating this category of light is all good,
     * or that more work is needed spreading light around.
     * 
     * @param chunk
     * @param dirty
     */
    public abstract void setDirty(LightingChunk chunk, boolean dirty);

    /**
     * Gets the light level in a section at the coordinates specified.
     * No bounds checking is performed.
     * 
     * @param section
     * @param x
     * @param y
     * @param z
     * @return light level
     */
    public abstract int get(LightingChunkSection section, int x, int y, int z);

    /**
     * Sets the light level in a section at the coordinates specified.
     * No bounds checking is performed.
     * 
     * @param section
     * @param x
     * @param y
     * @param z
     * @param level
     */
    public abstract void set(LightingChunkSection section, int x, int y, int z, int level);
}