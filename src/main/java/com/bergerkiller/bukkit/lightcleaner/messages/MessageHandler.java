package com.bergerkiller.bukkit.lightcleaner.messages;

import com.bergerkiller.bukkit.lightcleaner.LightCleaner;
import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Level;

public class MessageHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static MessageHandler instance;
    private final Map<String, String> messages = Maps.newHashMap();

    public MessageHandler() {
        instance = this;
        LightCleaner plugin = LightCleaner.plugin;
        plugin.saveResource("messages.json", false);
        try (final JsonReader reader = GSON.newJsonReader(Files
                .newReader(new File(plugin.getDataFolder(), "messages.json"),
                        StandardCharsets.UTF_8))) {
            final JsonObject object = GSON.fromJson(reader, JsonObject.class);
            for (final Map.Entry<String, JsonElement> elements : object.entrySet()) {
                messages.put(elements.getKey(), elements.getValue().getAsString());
            }
        } catch (final IOException e) {
            Bukkit.getLogger().log(Level.WARNING, "Failed to load messages "+ e);
        }
    }

    /**
     * Get a translation from a translation key
     *
     * @param key Translation Key
     * @return Translation
     * @throws IllegalArgumentException If the translation does not exist
     */
    public String getTranslation(final String key) {
        Preconditions.checkNotNull(key, "Key cannot be null");
        final String value = this.messages.get(key);
        if (value == null) {
            throw new IllegalArgumentException("There is no message with that key: " + key);
        }
        return value;
    }

}
