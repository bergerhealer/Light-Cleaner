package com.bergerkiller.bukkit.lightcleaner;

import org.bukkit.ChatColor;

import com.bergerkiller.bukkit.common.localization.LocalizationEnum;

/**
 * Messages used in light cleaner
 */
public class Localization extends LocalizationEnum {
    public static final Localization NO_PERMISSION = new Localization("lightcleaner.noperm", ChatColor.RED + "You don't have permission to use this!");
    public static final Localization PLAYERS_ONLY = new Localization("lightcleaner.playeronly", "This command is only for players!");
    public static final Localization AREA_CORRUPT = new Localization("lightcleaner.area.corrupt", ChatColor.YELLOW + "A %0% chunk area around you is currently being corrupted, introducing lighting issues...");
    public static final Localization AREA_FIX = new Localization("lightcleaner.area.fix", ChatColor.GREEN + "A %0% chunk area around you is currently being fixed from lighting issues...");

    private Localization(String name, String defValue) {
        super(name, defValue);
    }

    @Override
    public String get(String... arguments) {
        return LightCleaner.plugin.getLocale(this.getName(), arguments);
    }
}
