package org.kseries.instanceworld.api;

public record InstanceBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public InstanceBounds {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("Invalid instance bounds");
        }
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }
}
