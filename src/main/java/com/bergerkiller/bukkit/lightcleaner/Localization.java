package com.bergerkiller.bukkit.lightcleaner;

import org.bukkit.ChatColor;

import com.bergerkiller.bukkit.common.localization.LocalizationEnum;

/**
 * Messages used in light cleaner
 */
public class Localization extends LocalizationEnum {
    public static final Localization NO_PERMISSION = new Localization("lightcleaner.noperm", ChatColor.RED + "You don't have permission to use this!");
    public static final Localization PLAYERS_ONLY = new Localization("lightcleaner.playeronly", "This command is only for players!");

    private Localization(String name, String defValue) {
        super(name, defValue);
    }

    @Override
    public String get(String... arguments) {
        return LightCleaner.plugin.getLocale(this.getName(), arguments);
    }
}
