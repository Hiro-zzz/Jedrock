package com.jedrock.core.plugin;

import com.jedrock.api.region.Region;
import com.jedrock.api.region.RegionFlag;

/**
 * One {@linkplain Region region} as a script sees it — the same box the core enforces, with its flags
 * addressed by name instead of by a Java enum a script has no comfortable way to hold.
 *
 * <pre>{@code
 *   var spawn = regions.create('spawn', -20, 60, -20, 20, 90, 20);
 *   spawn.deny('build');            // nobody digs up the spawn
 *   spawn.deny('pvp');              // ...or fights on it
 *   if (spawn.contains(x, y, z)) …
 * }</pre>
 *
 * <p>Flags: {@code build}, {@code interact}, {@code pvp}, {@code damage}, {@code entry}. Every one starts
 * allowed, so a new region changes nothing until something is denied on it. Where regions overlap,
 * <b>deny wins</b> — the same rule permissions use.
 *
 * <p>A region is <b>server-owned</b>, like a saved scene: it survives the script that created it, a hot
 * reload and a restart. Creating one from a script that runs on every load is therefore fine — the second
 * create returns {@code null} rather than replacing what is there, so nothing loses its flags.
 */
public final class ScriptRegion {

    private final RegionManagerView regions;
    private final Region region;

    ScriptRegion(RegionManagerView regions, Region region) {
        this.regions = regions;
        this.region = region;
    }

    /** The wrapped region — for the core's own use, never reachable from JavaScript. */
    Region unwrap() {
        return region;
    }

    public String getName() {
        return region.getName();
    }

    public int getMinX() {
        return region.getMinX();
    }

    public int getMinY() {
        return region.getMinY();
    }

    public int getMinZ() {
        return region.getMinZ();
    }

    public int getMaxX() {
        return region.getMaxX();
    }

    public int getMaxY() {
        return region.getMaxY();
    }

    public int getMaxZ() {
        return region.getMaxZ();
    }

    /** How many blocks this region covers. */
    public double getVolume() {
        return region.getVolume();
    }

    /** Whether this point is inside. Block coordinates, both corners included. */
    public boolean contains(double x, double y, double z) {
        return region.contains(x, y, z);
    }

    /** Whether {@code flag} is allowed here — true unless it was explicitly denied. */
    public boolean allows(String flag) {
        return region.allows(flagOf(flag));
    }

    /** Allow {@code flag} here (the state every flag starts in). Returns this region, so calls chain. */
    public ScriptRegion allow(String flag) {
        region.allow(flagOf(flag));
        regions.markDirty();
        return this;
    }

    /** Deny {@code flag} here. Returns this region, so calls chain. */
    public ScriptRegion deny(String flag) {
        region.deny(flagOf(flag));
        regions.markDirty();
        return this;
    }

    /**
     * The permission node that exempts a player from this region's denial of {@code flag} —
     * {@code jedrock.region.<name>.<flag>}.
     *
     * <pre>{@code
     *   var plot = regions.get('plot7');
     *   server.dispatchCommand(null, 'perm player ' + owner.getName()
     *       + ' add ' + plot.getBypassPermission('build'));   // the owner may build in their own plot
     * }</pre>
     *
     * <p>Grant it to a group instead and everyone in it is exempt; {@code jedrock.region.plot7.*} covers
     * every flag of one region, {@code jedrock.region.*} every region. An op holds every node, so operators
     * are exempt everywhere.
     */
    public String getBypassPermission(String flag) {
        return region.bypassPermission(flagOf(flag));
    }

    /** Every flag currently denied here, by name — what {@code /region info} would print. */
    public String[] getDenied() {
        java.util.List<String> denied = new java.util.ArrayList<>();
        for (RegionFlag flag : RegionFlag.values()) {
            if (!region.allows(flag)) {
                denied.add(flag.key());
            }
        }
        return denied.toArray(new String[0]);
    }

    private static RegionFlag flagOf(String name) {
        RegionFlag flag = RegionFlag.byName(name);
        if (flag == null) {
            throw new IllegalArgumentException("no such region flag: '" + name
                    + "' (build, interact, pvp, damage, entry)");
        }
        return flag;
    }

    @Override
    public String toString() {
        return "Region[" + getName() + "]";
    }

    /** The sliver of the manager a region needs: telling it the flags changed so they get saved. */
    interface RegionManagerView {
        void markDirty();
    }
}
