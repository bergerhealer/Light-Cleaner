package com.bergerkiller.bukkit.lightcleaner.messages;

import com.bergerkiller.bukkit.lightcleaner.LightCleaner;
import org.bukkit.ChatColor;

public class TranslatableMessage {
    private final String key;

    private TranslatableMessage(final String key) {
        this.key = key;
    }

    /**
     * Create a new translatable message with a given message key
     *
     * @param key Message key
     * @return Message instance
     */
    public static String of(final String key) {
        return ChatColor.translateAlternateColorCodes('&', LightCleaner.getMessageHandler().getTranslation("prefix") +
                LightCleaner.getMessageHandler().getTranslation(key));
    }

}
