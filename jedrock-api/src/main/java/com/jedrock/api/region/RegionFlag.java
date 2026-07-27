package com.jedrock.api.region;

import java.util.Locale;

/**
 * What a {@linkplain Region region} may or may not allow inside itself.
 *
 * <p>Every flag is an <b>allowance</b>, and every allowance starts <b>on</b>: a fresh region changes
 * nothing until something is denied on it. That way a region is only ever as restrictive as it was
 * explicitly made, and a flag this server grows later defaults to the behaviour servers already have.
 *
 * <p>Each one is enforced by cancelling an event the core already routes its decision through — there is
 * no second rulebook. That is the whole reason the set is this small: a flag exists here only where the
 * core already asks permission, so nothing has to be re-checked in a second place and a script sees the
 * refusal as the same cancellation it could have made itself.
 */
public enum RegionFlag {

    /** Placing and breaking blocks ({@code BlockPlaceEvent} / {@code BlockBreakEvent}). */
    BUILD,

    /** Right-clicking a block — chests, doors ({@code PlayerInteractBlockEvent}). */
    INTERACT,

    /** Being hurt by another player ({@code PlayerDamageEvent} with cause {@code ATTACK}). */
    PVP,

    /** Being hurt <em>at all</em> — a safe zone; covers falling and the void as well as combat. */
    DAMAGE,

    /** Walking in. Denying it turns the region into a wall: a player outside is snapped back at its edge. */
    ENTRY;

    /** The lower-case name a script and the {@code /region} command use ({@code "build"}). */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Resolve a flag by name, case-insensitively.
     *
     * @return the flag, or {@code null} if no such flag exists (the caller decides how loudly to say so)
     */
    public static RegionFlag byName(String name) {
        if (name == null) {
            return null;
        }
        String want = name.trim().toUpperCase(Locale.ROOT);
        for (RegionFlag flag : values()) {
            if (flag.name().equals(want)) {
                return flag;
            }
        }
        return null;
    }
}
