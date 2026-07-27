package com.jedrock.core.region;

import com.jedrock.api.region.Region;
import com.jedrock.api.region.RegionFlag;

/**
 * The server's {@link Region}: six normalized bounds and a bitmask of denied flags.
 *
 * <p>Deliberately thin. A region has no behaviour of its own — it can't tick, it doesn't know who is
 * inside it, and it never touches the world. All of that lives in {@link RegionManager}, which is the one
 * thing that owns regions; a region itself is a value the manager hands out. That is also why this class
 * exposes barely more than the interface does: a script is given one of these directly, so anything public
 * here is part of the contract whether the {@code api} says so or not (the lesson {@code ScriptWrapFactory}
 * was written for).
 *
 * <p>Flags are stored as a <b>denied</b> mask rather than an allowed one, so the zero value is "a region
 * that changes nothing" — which is what a freshly created region has to be.
 */
public final class CoreRegion implements Region {

    private final String name;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;

    /** Bit per {@link RegionFlag} ordinal; set = denied. 0 = a region that allows everything. */
    private volatile int deniedMask;

    /** Build from any two opposite corners, in any order — they are normalized here, once. */
    public CoreRegion(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.name = name;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getMinX() {
        return minX;
    }

    @Override
    public int getMinY() {
        return minY;
    }

    @Override
    public int getMinZ() {
        return minZ;
    }

    @Override
    public int getMaxX() {
        return maxX;
    }

    @Override
    public int getMaxY() {
        return maxY;
    }

    @Override
    public int getMaxZ() {
        return maxZ;
    }

    /**
     * Inclusive containment on block coordinates. A player standing at {@code x = 10.7} is in the block
     * {@code 10}, so the fractional position is floored rather than rounded — otherwise a player would
     * count as inside a region whose edge they are merely leaning towards.
     */
    @Override
    public boolean contains(double x, double y, double z) {
        int bx = (int) Math.floor(x);
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(z);
        return bx >= minX && bx <= maxX
                && by >= minY && by <= maxY
                && bz >= minZ && bz <= maxZ;
    }

    @Override
    public boolean allows(RegionFlag flag) {
        return flag == null || (deniedMask & (1 << flag.ordinal())) == 0;
    }

    @Override
    public void allow(RegionFlag flag) {
        if (flag != null) {
            deniedMask &= ~(1 << flag.ordinal());
        }
    }

    @Override
    public void deny(RegionFlag flag) {
        if (flag != null) {
            deniedMask |= 1 << flag.ordinal();
        }
    }

    /** The raw denied mask — for persistence only, so a region round-trips as the integer it is. */
    int deniedMask() {
        return deniedMask;
    }

    void setDeniedMask(int mask) {
        this.deniedMask = mask;
    }

    @Override
    public String toString() {
        return "Region[" + name + " " + minX + "," + minY + "," + minZ
                + " .. " + maxX + "," + maxY + "," + maxZ + "]";
    }
}
