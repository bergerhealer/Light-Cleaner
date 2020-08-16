package com.bergerkiller.bukkit.lightcleaner.lighting;

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