package com.jedrock.api.region;

/**
 * A named box in the world with rules attached — the primitive every game mode ends up needing: a lobby,
 * an arena, a shop floor, a spawn nobody can dig up.
 *
 * <p>It is deliberately the <b>illusionist</b> kind of region. There is no trigger volume being simulated,
 * nothing ticking, no physics: a region is six numbers and a set of {@linkplain RegionFlag allowances},
 * and the only question ever asked of it is "is this point inside?". That question is asked exactly where
 * the core was already asking permission — a block edit, a hit, a step — so a region costs nothing on a
 * server that has none, and costs a handful of integer comparisons on one that does.
 *
 * <p>Bounds are <b>inclusive</b> and stored normalized, so a region built from two opposite corners in any
 * order covers the blocks a player would expect to have selected, including both corners themselves.
 *
 * <p>Regions may overlap freely, and where they do the rule is the one this server already uses for
 * permissions: <b>deny wins</b>. A point inside three regions is allowed to build only if all three allow
 * it. That needs no priority number and no ordering, and it means dropping a small no-build region inside
 * a big free-build one does what it looks like it does.
 */
public interface Region {

    /** The region's name, as it was created. Unique per server, matched case-insensitively. */
    String getName();

    /** Lowest corner, inclusive. */
    int getMinX();

    int getMinY();

    int getMinZ();

    /** Highest corner, inclusive. */
    int getMaxX();

    int getMaxY();

    int getMaxZ();

    /** Whether the point {@code (x, y, z)} lies inside this region. Block coordinates, inclusive. */
    boolean contains(double x, double y, double z);

    /** Whether {@code flag} is allowed here. Everything is allowed until something is explicitly denied. */
    boolean allows(RegionFlag flag);

    /** Allow {@code flag} inside this region (the default state of every flag). */
    void allow(RegionFlag flag);

    /** Deny {@code flag} inside this region. */
    void deny(RegionFlag flag);

    /**
     * The permission node that <b>exempts</b> a player from this region's denial of {@code flag}:
     * {@code jedrock.region.<name>.<flag>}.
     *
     * <p>Exceptions are permissions rather than a member list kept on the region, and deliberately so. The
     * question "may <em>this</em> player do <em>this</em> here" is the one thing this server already has a
     * whole system for — with groups, inheritance, wildcards and an explicit deny — so a region that grew
     * its own roster would be a second rulebook of exactly the kind the flags themselves avoid. Instead:
     *
     * <ul>
     *   <li>per player — grant them {@code jedrock.region.plot7.build};</li>
     *   <li>per group — grant the same node to a group, and everyone in it (and in anything inheriting it)
     *       is exempt;</li>
     *   <li>whole region — {@code jedrock.region.plot7.*}; every region — {@code jedrock.region.*};</li>
     *   <li>a targeted revocation — {@code -jedrock.region.plot7.build} beats any grant, since deny wins
     *       there as it does here.</li>
     * </ul>
     *
     * <p>An <b>op holds every node</b>, so an operator is exempt everywhere — the same rule that governs
     * commands, and the reason a region never locks its own staff out.
     *
     * <p>Region names are restricted to letters, digits, {@code _} and {@code -} precisely so this node is
     * unambiguous: a name with a dot in it would silently invent a wildcard level.
     */
    default String bypassPermission(RegionFlag flag) {
        return "jedrock.region." + getName().toLowerCase(java.util.Locale.ROOT) + "." + flag.key();
    }

    /** The number of blocks this region covers — useful for a sanity check before denying half a world. */
    default long getVolume() {
        return (long) (getMaxX() - getMinX() + 1)
                * (getMaxY() - getMinY() + 1)
                * (getMaxZ() - getMinZ() + 1);
    }
}
