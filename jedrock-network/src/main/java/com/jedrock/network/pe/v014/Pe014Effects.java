package com.jedrock.network.pe.v014;

import com.jedrock.api.entity.Effect;

/**
 * The status effects MCPE 0.14 knows — the crash gate for effects, exactly as {@link Pe014Blocks} is for
 * blocks and {@link Pe014Items} for items, and for the same reason: this client has no placeholder for
 * something it can't place, so an id it doesn't know is a crash rather than a shrug.
 *
 * <p>Ground truth is PocketMine-MP at {@code CURRENT_PROTOCOL = 45} ({@code entity/Effect.php}), whose
 * registered set is these sixteen. The seven it leaves as {@code TODO} — instant health and damage,
 * blindness, night vision, hunger, absorption and saturation — are not sent to a 0.14 client at all.
 *
 * <p>Two of those seven still <em>land</em> there, because they are not really packets: instant health
 * and instant damage change a number the server owns, so a 0.14 player is healed or hurt exactly like
 * everybody else and simply doesn't see the swirl.
 */
public final class Pe014Effects {

    private Pe014Effects() {}

    private static final boolean[] SUPPORTED = new boolean[32];

    static {
        for (int id : new int[]{
                1,   // speed
                2,   // slowness
                3,   // haste
                4,   // mining fatigue
                5,   // strength
                8,   // jump boost
                9,   // nausea
                10,  // regeneration
                11,  // resistance
                12,  // fire resistance
                13,  // water breathing
                14,  // invisibility
                18,  // weakness
                19,  // poison
                20,  // wither
                21   // health boost
        }) {
            SUPPORTED[id] = true;
        }
    }

    /** Whether a 0.14 client can be told about this effect at all. */
    public static boolean supports(Effect effect) {
        return effect != null && supports(effect.getId());
    }

    /** The same test on a raw legacy id. */
    public static boolean supports(int id) {
        return id >= 0 && id < SUPPORTED.length && SUPPORTED[id];
    }
}
