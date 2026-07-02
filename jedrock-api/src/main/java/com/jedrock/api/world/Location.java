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
}
