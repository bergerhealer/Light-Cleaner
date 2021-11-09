package com.bergerkiller.bukkit.lightcleaner.handler;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Base implementation for a handler
 */
public interface Handler {
    /**
     * Checks whether currently this handler is supported on the server,
     * and so should be chosen as a handler.
     *
     * @return True if this handler is supported
     */
    boolean isSupported();

    /**
     * Gets the message logged when this handler was successfully enabled
     *
     * @return enable message
     */
    String getEnableMessage();

    /**
     * Gets the message logged when this handler is disabled again (plugin disables)
     *
     * @return disable message
     */
    String getDisableMessage();

    /**
     * Initializes a new instance of this handler. Is called if
     * {@link #isSupported()} returns true.
     *
     * @param ops Provides the operations handlers can perform
     * @return New instance
     * @throws Exception
     * @throws Error
     */
    HandlerInstance enable(HandlerOps ops) throws Exception, Error;

    /**
     * Checks whether a plugin by the name specified is enabled, or that a different
     * plugin is enabled that provided that same plugin's functionality.
     *
     * @param pluginName
     * @return True if the plugin is enabled, or provided
     */
    public static boolean isPluginEnabledOrProvided(String pluginName) {
        // WorldEdit plugin itself
        {
            Plugin p = Bukkit.getPluginManager().getPlugin(pluginName);
            if (p != null && p.isEnabled()) {
                return true;
            }
        }

        // Plugins that substitute WorldEdit (provides)
        try {
            for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
                if (p.isEnabled()) {
                    for (String provide : p.getDescription().getProvides()) {
                        if (pluginName.equalsIgnoreCase(provide)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            /* Ignore - probably missing provides api */
        }
        return false;
    }

    /**
     * An instance of the handler that was initialized
     */
    public static abstract class HandlerInstance {
        protected final HandlerOps ops;

        protected HandlerInstance(HandlerOps ops) {
            this.ops = ops;
        }

        /**
         * Disables the instance again, stopping any tasks that were
         * started and cleaning it up
         */
        public abstract void disable() throws Exception, Error;
    }
}
