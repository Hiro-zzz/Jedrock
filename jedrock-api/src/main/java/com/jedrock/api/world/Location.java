package com.jedrock.api.world;

import java.util.Objects;

/**
 * Immutable lightweight location.
 * For performance critical code, prefer direct x/y/z + world when possible.
 */
public record Location(World world, double x, double y, double z, float yaw, float pitch) {

    public Location {
        Objects.requireNonNull(world, "world");
    }

    public Location(World world, double x, double y, double z) {
        this(world, x, y, z, 0f, 0f);
    }

    public int getBlockX() { return (int) Math.floor(x); }
    public int getBlockY() { return (int) Math.floor(y); }
    public int getBlockZ() { return (int) Math.floor(z); }

    /**
     * Squared straight-line distance to {@code other} — cheaper than {@link #distance(Location)} (no square
     * root), so prefer it for range checks (compare against {@code radius * radius}).
     *
     * @throws IllegalArgumentException if the two locations are in different worlds
     */
    public double distanceSquared(Location other) {
        Objects.requireNonNull(other, "other");
        if (other.world != world) {
            throw new IllegalArgumentException("locations are in different worlds");
        }
        double dx = x - other.x, dy = y - other.y, dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Straight-line distance to {@code other} (in blocks).
     *
     * @throws IllegalArgumentException if the two locations are in different worlds
     */
    public double distance(Location other) {
        return Math.sqrt(distanceSquared(other));
    }

    /** A copy shifted by {@code (dx, dy, dz)}, keeping the same world and facing. */
    public Location add(double dx, double dy, double dz) {
        return new Location(world, x + dx, y + dy, z + dz, yaw, pitch);
    }

    /** A copy at {@code (newX, newY, newZ)}, keeping the same world and facing. */
    public Location withPosition(double newX, double newY, double newZ) {
        return new Location(world, newX, newY, newZ, yaw, pitch);
    }
}
