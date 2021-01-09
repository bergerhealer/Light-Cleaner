package com.bergerkiller.bukkit.lightcleaner;

import com.bergerkiller.bukkit.common.permissions.PermissionEnum;
import org.bukkit.permissions.PermissionDefault;

public class Permission extends PermissionEnum {
    public static final Permission CLEAN_VIEW = new Permission("lightcleaner.clean.view", PermissionDefault.OP, "Allows a player to fix lighting issues in chunks around the player within view radius");
    public static final Permission CLEAN_ANY_RADIUS = new Permission("lightcleaner.clean.any", PermissionDefault.OP, "Allows a player to fix lighting issues in chunks around the player with any radius");
    public static final Permission CLEAN_BY_RADIUS = new Permission("lightcleaner.clean.radius", PermissionDefault.OP, "Allows a player to only fix a specific radius of chunks (example perm: lightcleaner.clean.radius.4)", 1);
    public static final Permission CLEAN_WORLD = new Permission("lightcleaner.clean.world", PermissionDefault.OP, "Allows a player to fix lighting issues in all the chunks of an entire world");
    public static final Permission CLEAN_AT = new Permission( "lightcleaner.clean.at", PermissionDefault.OP, "Allows a player to specify coordinates to clean");
    public static final Permission STATUS = new Permission("lightcleaner.status", PermissionDefault.OP, "Allows a player to check the status of ongoing lighting operations");
    public static final Permission ABORT = new Permission("lightcleaner.abort", PermissionDefault.OP, "Allows a player to abort all current lighting operations");
    public static final Permission PAUSE = new Permission("lightcleaner.pause", PermissionDefault.OP, "Allows a player to pause and resume lighting operations");
    public static final Permission DIRTY_DEBUG = new Permission("lightcleaner.dirty.debug", PermissionDefault.FALSE, "Allows a player to corrupt lighting instead of clean it (for debugging purposes)");
    public static final Permission BLOCK_DEBUG = new Permission("lightcleaner.block.debug", PermissionDefault.OP, "Allows a player to mark blocks that will make light cleaner log the light level generated");

    private Permission(final String path, final PermissionDefault def, final String desc) {
        super(path, def, desc);
    }

    private Permission(final String path, final PermissionDefault def, final String desc, int argCount) {
        super(path, def, desc, argCount);
    }
}