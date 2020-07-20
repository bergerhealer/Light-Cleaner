package com.bergerkiller.bukkit.lightcleaner.impl;

import com.bergerkiller.bukkit.lightcleaner.LightCleaner;

/**
 * Base implementation for a handler
 */
public interface Handler {
    void enable(LightCleaner plugin);
    void disable(LightCleaner plugin);
}
