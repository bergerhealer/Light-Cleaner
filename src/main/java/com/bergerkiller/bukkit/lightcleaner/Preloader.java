package com.bergerkiller.bukkit.lightcleaner;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;

/**
 * A simple class that uses the 'preloader' section in the plugin.yml
 * to load the actual plugin. If dependencies declared in this section
 * are not available or other errors occur, the plugin isn't loaded.
 *
 * If the plugin couldn't be loaded, a message is sent to operators when
 * they join the server, as well as when executing the plugin's command.
 *
 * In case commands are registered inside onEnable() rather than as part
 * of plugin.yml, commands can be declared inside the preloader section.
 * These will be registered if the plugin could not be loaded.
 *
 * @author Irmo van den Berge (bergerkiller) - Feel free to use in this
 *         in your own plugin, I do not care.
 * @version 1.2
 */
public class Preloader extends JavaPlugin {
    private final String mainClassName;
    private final List<Depend> dependList;
    private final List<String> preloaderCommands;
    private final List<Depend> missingDepends = new ArrayList<>();
    private String loadError = null;

    public Preloader() {
        // Parse the required information from plugin.yml
        // If anything goes wrong, abort right away!
        try (InputStream stream = getResource("plugin.yml"); Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            YamlConfiguration config = new YamlConfiguration();
            config.load(reader);
            ConfigurationSection preloaderConfig = config.getConfigurationSection("preloader");
            if (preloaderConfig == null) {
                throw new IllegalStateException("plugin.yml has no preloader configuration");
            }
            mainClassName = preloaderConfig.getString("main");
            if (mainClassName == null) {
                throw new IllegalStateException("plugin.yml preloader configuration declares no main class");
            }

            // Check that all dependencies exist
            ConfigurationSection dependConfig = preloaderConfig.getConfigurationSection("depend");
            if (dependConfig == null) {
                dependList = Collections.emptyList();
            } else {
                Set<String> names = dependConfig.getKeys(false);
                dependList = new ArrayList<Depend>(names.size());
                for (String name : names) {
                    dependList.add(new Depend(name, dependConfig.getString(name)));
                }
            }

            // Check that commands need to be registered if loading fails
            List<String> preloaderCommandsTmp = preloaderConfig.getStringList("commands");
            if (preloaderCommandsTmp == null || preloaderCommandsTmp.isEmpty()) {
                preloaderCommands = Collections.emptyList();
            } else {
                preloaderCommands = new ArrayList<String>(preloaderCommandsTmp);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Corrupt jar: Failed to read plugin.yml", e);
        } catch (InvalidConfigurationException e) {
            throw new IllegalStateException("Corrupt jar: Failed to load plugin.yml", e);
        }
    }

    @Override
    @SuppressWarnings({ "deprecation", "unchecked" })
    public void onLoad() {
        // Verify all the hard depend plugins are available
        missingDepends.clear();
        for (Depend depend : dependList) {
            if (this.getServer().getPluginManager().getPlugin(depend.name) == null) {
                final PluginDescriptionFile desc = this.getDescription();
                this.getLogger().log(Level.SEVERE, "Plugin " + desc.getName() + " " + desc.getVersion() +
                        " requires plugin " + depend.name + " to be installed! But it is not!");
                this.getLogger().log(Level.SEVERE, "Download " + depend.name + " from " + depend.url);
                missingDepends.add(depend);
            }
        }
        if (!missingDepends.isEmpty()) {
            return;
        }

        // Get up-front
        final String pluginName = this.getName();

        // Load the main class as declared in the preloader configuration
        final Class<?> mainClass;
        try {
            mainClass = this.getClassLoader().loadClass(mainClassName);
        } catch (ClassNotFoundException e) {
            this.getLogger().log(Level.SEVERE, "Failed to initialize the plugin main class", e);
            this.loadError = "Failed to initialize the main class - check server log!";
            return;
        }

        // Inside the JavaPlugin constructor it initializes itself using the PluginClassLoader
        // As part of this, it checks that the plugin and pluginInit fields are unset.
        // So before we initialize a new instance, unset these fields.
        this.setLoaderPluginField(null, pluginName);

        // Initialize the plugin class the same way this preloader class was initialized
        // This is normally done inside the constructor of PluginClassLoader
        final JavaPlugin mainPlugin;
        try {
            mainPlugin = (JavaPlugin) mainClass.newInstance();
        } catch (Throwable t) {
            this.getLogger().log(Level.SEVERE, "Failed to load the plugin", t);
            this.loadError = "Failed to load the plugin - check server log!";
            this.setLoaderPluginField(this, pluginName); // Undo this, otherwise state is corrupted
            return;
        }

        // Normally done inside PluginClassLoader constructor, so do it now
        setLoaderPluginField(mainPlugin, pluginName);

        // Initialization successful! Now replace the plugin instance in all other places
        // It's absolutely important we replace it EVERYWHERE or things will probably break!
        PluginManager manager = this.getServer().getPluginManager();
        try {
            // private final List<Plugin> plugins
            {
                Field pluginsField = manager.getClass().getDeclaredField("plugins");
                pluginsField.setAccessible(true);
                List<Object> plugins = (List<Object>) pluginsField.get(manager);
                int index = plugins.indexOf(this);
                if (index == -1) {
                    throw new IllegalStateException("Preloader does not exist in plugins list");
                }
                plugins.set(index, mainPlugin);
            }

            // private final Map<String, Plugin> lookupNames
            {
                Field lookupNamesField = manager.getClass().getDeclaredField("lookupNames");
                lookupNamesField.setAccessible(true);
                Map<Object, Object> lookupNames = (Map<Object, Object>) lookupNamesField.get(manager);
                boolean found = false;
                for (Map.Entry<Object, Object> e : lookupNames.entrySet()) {
                    if (e.getValue() == this) {
                        e.setValue(mainPlugin);
                        found = true;
                    }
                }
                if (!found) {
                    throw new IllegalStateException("Preloader does not exist in lookupNames mapping");
                }
            }
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to load plugin into the server", t);
        }

        // Re-initialize the PluginClassLoader with the new main plugin
        // Call onLoad - if this fails, it's as if onLoad failed of the plugin itself
        mainPlugin.onLoad();
    }

    @Override
    public void onEnable() {
        // If nothing is wrong, something weird is going on
        // It should never get here...
        if (this.loadError == null && this.missingDepends.isEmpty()) {
            this.loadError = "Preloading failed - unsupported server?";
        }

        // Notify
        this.getLogger().log(Level.SEVERE, "Not enabled! - Check server log");

        // Register commands late if specified
        this.preloaderCommands.forEach(commandName -> {
            try {
                Command command;
                {
                    Constructor<PluginCommand> constr = PluginCommand.class.getDeclaredConstructor(String.class, Plugin.class);
                    constr.setAccessible(true);
                    command = constr.newInstance(commandName, this);
                    command.setDescription("Plugin " + getName() + " could not be loaded!");
                }

                Field commandMapField = Bukkit.getPluginManager().getClass().getDeclaredField("commandMap");
                commandMapField.setAccessible(true);
                CommandMap commandMap = (CommandMap) commandMapField.get(Bukkit.getPluginManager());
                commandMap.register(this.getName(), command);
            } catch (Throwable t) {
                getLogger().log(Level.SEVERE, "Failed to register preloader fallback command " + commandName, t);
            }
        });

        // Install a Listener into the server with the sole task of annoying people
        // Tell them the plugin is not enabled for the reasons as stated
        this.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerJoin(PlayerJoinEvent event) {
                if (event.getPlayer().isOp()) {
                    showErrors(event.getPlayer());
                }
            }
        }, this);
    }

    @SuppressWarnings("unchecked")
    private void setLoaderPluginField(JavaPlugin plugin, String pluginName) {
        ClassLoader loader = this.getClassLoader();
        try {
            Field pluginField = loader.getClass().getDeclaredField("plugin");
            pluginField.setAccessible(true);
            pluginField.set(loader, plugin);
        } catch (Throwable t) {
            this.getLogger().log(Level.SEVERE, "[Preloader] Failed to update 'plugin' field", t);
        }
        try {
            Field pluginInitField = loader.getClass().getDeclaredField("pluginInit");
            pluginInitField.setAccessible(true);
            pluginInitField.set(loader, plugin);
        } catch (Throwable t) {
            this.getLogger().log(Level.SEVERE, "[Preloader] Failed to update 'pluginInit' field", t);
        }

        // Make sure that during initialization (null) the PluginClassLoader for this plugin
        // is not registered in the 'global' JavaPluginLoader loaders field. Having this here
        // can cause a nullpointer exception because the plugin is initializing. Normally
        // it is also not registered here while calling the constructor.
        try {
            Field globalLoaderField = loader.getClass().getDeclaredField("loader");
            globalLoaderField.setAccessible(true);
            JavaPluginLoader globalLoader = (JavaPluginLoader) globalLoaderField.get(loader);
            Field globalLoaderPluginLoadersField = JavaPluginLoader.class.getDeclaredField("loaders");
            globalLoaderPluginLoadersField.setAccessible(true);
            Object rawLoaders = globalLoaderPluginLoadersField.get(globalLoader);
            if (rawLoaders instanceof List) {
                // Since Bukkit 1.10 onwards
                List<Object> pluginLoaders = (List<Object>) rawLoaders;
                if (plugin == null) {
                    pluginLoaders.remove(loader);
                } else if (!pluginLoaders.contains(loader)) {
                    pluginLoaders.add(loader);
                }
            } else if (rawLoaders instanceof Map) {
                // Bukkit/Minecraft 1.8 to 1.9.4 used a Map by plugin name
                Map<String, Object> pluginLoaders = (Map<String, Object>) rawLoaders;
                if (plugin == null) {
                    if (pluginLoaders.get(pluginName) == loader) {
                        pluginLoaders.remove(pluginName);
                    }
                } else {
                    if (pluginLoaders.get(pluginName) == null) {
                        pluginLoaders.put(pluginName, loader);
                    }
                }
            } else {
                throw new IllegalStateException("Unknown loaders field type: " + rawLoaders.getClass());
            }
        } catch (Throwable t) {
            this.getLogger().log(Level.SEVERE, "[Preloader] Failed to update class loader registry", t);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        showErrors(sender);
        return true;
    }

    private void showErrors(CommandSender sender) {
        if (this.loadError != null) {
            sender.sendMessage(ChatColor.RED + "There was a fatal error initializing " + this.getName());
            sender.sendMessage(ChatColor.RED + this.loadError);
        } else {
            sender.sendMessage(ChatColor.RED + "Plugin " + this.getName() + " could not be enabled!");
            sender.sendMessage(ChatColor.RED + "Please install these additional dependencies:");
            for (Depend depend : this.missingDepends) {
                sender.sendMessage(ChatColor.RED + "  ======== " + depend.name + " ========");
                sender.sendMessage(ChatColor.RED + "  > " + ChatColor.WHITE + ChatColor.UNDERLINE + depend.url);
            }
        }
    }

    private static final class Depend {
        public final String name;
        public final String url;

        public Depend(String name, String url) {
            this.name = name.replace(' ', '_');
            this.url = url;
        }
    }
}
