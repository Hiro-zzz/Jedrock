package com.jedrock.api.entity;

/**
 * Canonical, protocol-agnostic visual flags a {@link PuppetEntity} can carry — the pose half of the
 * illusion: the server never simulates the state a flag depicts, it just asserts the look.
 *
 * <p>Deliberately only the flags that map to <em>one bit of one flags field on every supported edition</em>
 * (JE 1.8 / 1.12.2 share a flags byte; PE 1.1.5 carries a {@code DATA_FLAGS} long; PE 0.14 a
 * {@code DATA_FLAGS} byte). A flag renders everywhere or isn't offered at all — the same rule
 * {@link EntityType} follows. The bits below are Jedrock's own; the network layer maps them to each
 * edition's real bit (the entity counterpart of the block palette).
 *
 * <p>Notably absent: <b>baby</b>. Bedrock has a universal flag bit for it, but Java models age as
 * per-mob metadata at a per-mob index, so it can't be one canonical bit — it would render on Bedrock
 * and silently do nothing on Java. Same reasoning keeps {@code GLOWING} out (1.9+, so 1.8 can't).
 */
public enum PuppetFlag {

    /** Wrapped in flames. */
    ON_FIRE(0x01),

    /** Not rendered — the body vanishes but a name tag, if set, still floats. */
    INVISIBLE(0x02),

    /** The crouch pose. */
    SNEAKING(0x04);

    private final int bit;

    PuppetFlag(int bit) {
        this.bit = bit;
    }

    /** This flag's bit in a canonical mask. Not a wire value — the network layer translates per edition. */
    public int bit() {
        return bit;
    }

    /** Whether this flag is set in {@code mask}. */
    public boolean isSet(int mask) {
        return (mask & bit) != 0;
    }

    /** Combine flags into a canonical mask. */
    public static int mask(PuppetFlag... flags) {
        int mask = 0;
        for (PuppetFlag flag : flags) {
            mask |= flag.bit;
        }
        return mask;
    }
}
