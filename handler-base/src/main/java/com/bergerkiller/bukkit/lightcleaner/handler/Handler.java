package com.bergerkiller.bukkit.lightcleaner.handler;

/**
 * Base implementation for a handler
 */
public interface Handler {
    void enable(HandlerOps ops);
    void disable(HandlerOps ops);
}
