package com.jedrock.core.plugin;

import com.jedrock.api.player.Player;
import com.jedrock.api.region.Region;
import com.jedrock.api.world.Location;
import com.jedrock.core.region.RegionManager;

/**
 * The {@code regions} global — named boxes with rules, the primitive most scripted content ends up
 * needing: a lobby, an arena, a shop floor, a spawn nobody can dig up.
 *
 * <pre>{@code
 *   var spawn = regions.create('spawn', -20, 60, -20, 20, 90, 20);
 *   if (spawn) { spawn.deny('build'); spawn.deny('pvp'); }   // null = the name was taken
 *
 *   events.on('PlayerRegionEnter', function (e) {
 *       if (e.getRegion().getName() === 'arena') e.getPlayer().sendMessage('{red}Fight!');
 *   });
 *   events.on('PlayerRegionLeave', function (e) { … });      // cancel it to keep them in
 * }</pre>
 *
 * <p>Unlike almost everything else a script can make, a region is <b>server-owned</b>: it outlives the
 * plugin, a hot reload and a restart, the same way a saved scene does. So this global is not torn down
 * with the plugin, and {@code create} refuses a name that already exists rather than replacing it —
 * a script that runs {@code create} on every load keeps the flags an operator set by hand.
 *
 * <p>The rules themselves are not enforced here. Each flag cancels an event the core already routes its
 * decision through, so a script sees a region's refusal as exactly the cancellation it could have written
 * itself — there is no second rulebook, and a script listening at a higher priority can overrule one.
 */
public final class ScriptRegions {

    private final RegionManager regions;
    private final ScriptRegion.RegionManagerView view;

    ScriptRegions(RegionManager regions) {
        this.regions = regions;
        this.view = regions::markDirty;
    }

    /**
     * Create a region spanning the two opposite corners, in any order.
     *
     * @return the region, or {@code null} if the name is blank or already taken
     */
    public ScriptRegion create(String name, int x1, int y1, int z1, int x2, int y2, int z2) {
        Region created = regions.create(name, x1, y1, z1, x2, y2, z2);
        return created == null ? null : new ScriptRegion(view, created);
    }

    /** The region called {@code name} (case-insensitive), or {@code null}. */
    public ScriptRegion get(String name) {
        Region found = regions.get(name);
        return found == null ? null : new ScriptRegion(view, found);
    }

    /** Delete a region. @return {@code true} if there was one to delete */
    public boolean remove(String name) {
        return regions.remove(name);
    }

    /** Every region on the server, in creation order. */
    public ScriptRegion[] all() {
        return wrap(regions.all());
    }

    /** How many regions exist. */
    public int count() {
        return regions.size();
    }

    /** The regions containing this point — empty when none do. */
    public ScriptRegion[] at(double x, double y, double z) {
        return wrap(regions.at(x, y, z));
    }

    /**
     * The regions {@code player} is standing in right now.
     *
     * <p>Derived from where they are, not from the membership the movement path remembers, so it is right
     * even for a player who has just been teleported and hasn't reported a position yet.
     */
    public ScriptRegion[] of(Object player) {
        Player target = ScriptWrapFactory.unwrapPlayer(player);
        if (target == null) {
            throw new IllegalArgumentException("regions.of expects a player");
        }
        Location at = target.getLocation();
        return wrap(regions.at(at.x(), at.y(), at.z()));
    }

    /**
     * Whether {@code flag} is allowed at this point <em>for anyone</em>, applying every region that covers
     * it — the rule as the world states it, before anybody's exemptions. {@code true} where nothing has an
     * opinion.
     */
    public boolean allows(double x, double y, double z, String flag) {
        return regions.allows(x, y, z, flagOf(flag));
    }

    /**
     * Whether <em>this player</em> may do {@code flag} at this point — the same question the core asks
     * before it lets a block be broken, exemptions and all.
     *
     * <p>A player holding a region's {@linkplain ScriptRegion#getBypassPermission bypass node} passes that
     * region's denial as if it weren't there, so this and {@link #allows} disagree exactly where somebody
     * has been excused.
     */
    public boolean allowsFor(Object player, double x, double y, double z, String flag) {
        Player target = ScriptWrapFactory.unwrapPlayer(player);
        if (target == null) {
            throw new IllegalArgumentException("regions.allowsFor expects a player");
        }
        return regions.allows(target, x, y, z, flagOf(flag));
    }

    private static com.jedrock.api.region.RegionFlag flagOf(String flag) {
        com.jedrock.api.region.RegionFlag resolved = com.jedrock.api.region.RegionFlag.byName(flag);
        if (resolved == null) {
            throw new IllegalArgumentException("no such region flag: '" + flag
                    + "' (build, interact, pvp, damage, entry)");
        }
        return resolved;
    }

    private ScriptRegion[] wrap(java.util.List<Region> found) {
        ScriptRegion[] wrapped = new ScriptRegion[found.size()];
        for (int i = 0; i < wrapped.length; i++) {
            wrapped[i] = new ScriptRegion(view, found.get(i));
        }
        return wrapped;
    }
}
