package org.kseries.instanceworld.api;

import org.bukkit.Location;
import org.bukkit.World;

public record InstancePoint(double x, double y, double z, float yaw, float pitch) {
    public Location toLocation(World world) {
        return new Location(world, x, y, z, yaw, pitch);
    }
}
