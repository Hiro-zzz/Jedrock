package com.jedrock.network;

import com.jedrock.api.entity.PuppetFlag;

/**
 * The entity-flag "palette" — {@link EntityTypeIds}' sibling, and the rest of the two-headed monster's
 * entity tax. Maps a canonical {@link PuppetFlag} mask to each edition's real flags field:
 *
 * <ul>
 *   <li><b>Java</b>: a bit<em>mask</em> in the shared entity-flags byte (metadata index 0), identical on
 *       1.8 and 1.12.2.</li>
 *   <li><b>Bedrock</b>: bit <em>positions</em> in {@code DATA_FLAGS} (metadata index 0), identical on 1.1.5
 *       and 0.14 — only the field's width differs (a LONG at 113, a BYTE at 45), so both read this one
 *       mapping and write it at their own width.</li>
 * </ul>
 *
 * Verified against PocketMine-MP ({@code Entity.php} at both eras) and, on the Java side, ViaVersion's
 * version-pinned tables — see the protocol reference notes.
 */
public final class EntityFlagIds {

    private EntityFlagIds() {}

    // --- Java Edition: bits of the shared flags byte (1.8 and 1.12.2 agree) ---
    private static final int JAVA_ON_FIRE = 0x01;
    private static final int JAVA_CROUCHED = 0x02;
    private static final int JAVA_INVISIBLE = 0x20;

    // --- Bedrock: bit positions in DATA_FLAGS (protocol 45 and 113 agree) ---
    private static final int PE_BIT_ON_FIRE = 0;
    private static final int PE_BIT_SNEAKING = 1;
    private static final int PE_BIT_INVISIBLE = 5;

    /** Translate a canonical {@link PuppetFlag} mask into Java Edition's entity-flags byte. */
    public static int javaBits(int canonical) {
        int bits = 0;
        if (PuppetFlag.ON_FIRE.isSet(canonical)) bits |= JAVA_ON_FIRE;
        if (PuppetFlag.SNEAKING.isSet(canonical)) bits |= JAVA_CROUCHED;
        if (PuppetFlag.INVISIBLE.isSet(canonical)) bits |= JAVA_INVISIBLE;
        return bits;
    }

    /**
     * Translate a canonical {@link PuppetFlag} mask into Bedrock's {@code DATA_FLAGS} bits. 1.1.5 writes the
     * result as a LONG and 0.14 as a BYTE — every bit here is below 8, so neither truncates.
     */
    public static long bedrockBits(int canonical) {
        long bits = 0L;
        if (PuppetFlag.ON_FIRE.isSet(canonical)) bits |= 1L << PE_BIT_ON_FIRE;
        if (PuppetFlag.SNEAKING.isSet(canonical)) bits |= 1L << PE_BIT_SNEAKING;
        if (PuppetFlag.INVISIBLE.isSet(canonical)) bits |= 1L << PE_BIT_INVISIBLE;
        return bits;
    }
}
