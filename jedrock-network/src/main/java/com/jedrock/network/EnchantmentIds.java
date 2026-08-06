package com.jedrock.network;

import com.jedrock.api.item.Enchantment;

/**
 * The enchantment "palette" — the enchantment counterpart of {@link EntityTypeIds}, and the place the two
 * editions' disagreement is written down once instead of being rediscovered.
 *
 * <p><b>Java and Bedrock number enchantments differently, and not by an offset.</b> This is the whole
 * reason this class exists. Sharpness is {@code 16} on Java and {@code 9} on Bedrock. Worse than the gap
 * is the reordering: Java runs respiration {@code 5}, aqua affinity {@code 6}, thorns {@code 7}, while
 * Bedrock runs thorns {@code 5}, respiration {@code 6}, depth strider {@code 7}, aqua affinity {@code 8}.
 * Three ids that all exist on both sides and mean something different on each — so a single shared table
 * would not fail, it would hand a Bedrock player thorns when a Java player asked for respiration, and
 * nothing anywhere would say so.
 *
 * <p>Sources: <b>Java</b> from minecraft-data {@code pc/1.12} ({@code enchantments.json}); the ids are
 * stable across 1.8 and 1.12.2, since string ids only arrived in 1.13. <b>Bedrock</b> from PocketMine-MP
 * {@code item/enchantment/Enchantment.php} — at {@code 1.7dev-27} (protocol 113) and at {@code e11b76318}
 * (protocol 45), whose constant blocks agree for every id here.
 *
 * <p>The canonical set is deliberately the pre-1.9 one (see {@link Enchantment}), which is exactly the
 * {@code 0}-{@code 24} block 0.14 defines — so on that era every id this maps to is one the client knows.
 */
public final class EnchantmentIds {

    private EnchantmentIds() {}

    /** The highest id MCPE 0.14 defines. Above it that client knows nothing, and it is not one to guess at. */
    public static final int MAX_BEDROCK_014_ID = 24;

    /** The Java Edition numeric id (1.8 and 1.12.2 alike — the {@code ench} tag's {@code id} field). */
    public static int javaId(Enchantment enchantment) {
        return switch (enchantment) {
            case PROTECTION -> 0;
            case FIRE_PROTECTION -> 1;
            case FEATHER_FALLING -> 2;
            case BLAST_PROTECTION -> 3;
            case PROJECTILE_PROTECTION -> 4;
            case RESPIRATION -> 5;
            case AQUA_AFFINITY -> 6;
            case THORNS -> 7;
            case DEPTH_STRIDER -> 8;
            // The weapon block starts at 16 on this side, not at 9.
            case SHARPNESS -> 16;
            case SMITE -> 17;
            case BANE_OF_ARTHROPODS -> 18;
            case KNOCKBACK -> 19;
            case FIRE_ASPECT -> 20;
            case LOOTING -> 21;
            // …the tool block at 32…
            case EFFICIENCY -> 32;
            case SILK_TOUCH -> 33;
            case UNBREAKING -> 34;
            case FORTUNE -> 35;
            // …the bow block at 48…
            case POWER -> 48;
            case PUNCH -> 49;
            case FLAME -> 50;
            case INFINITY -> 51;
            // …and fishing at 61.
            case LUCK_OF_THE_SEA -> 61;
            case LURE -> 62;
        };
    }

    /** The Bedrock numeric id, shared by 1.1.5 and 0.14 — one contiguous run, unlike Java's blocks. */
    public static int bedrockId(Enchantment enchantment) {
        return switch (enchantment) {
            case PROTECTION -> 0;
            case FIRE_PROTECTION -> 1;
            case FEATHER_FALLING -> 2;
            case BLAST_PROTECTION -> 3;
            case PROJECTILE_PROTECTION -> 4;
            // Here are the three that swap places against Java: thorns comes before respiration, and
            // aqua affinity sits after depth strider rather than before thorns.
            case THORNS -> 5;
            case RESPIRATION -> 6;
            case DEPTH_STRIDER -> 7;
            case AQUA_AFFINITY -> 8;
            case SHARPNESS -> 9;
            case SMITE -> 10;
            case BANE_OF_ARTHROPODS -> 11;
            case KNOCKBACK -> 12;
            case FIRE_ASPECT -> 13;
            case LOOTING -> 14;
            case EFFICIENCY -> 15;
            case SILK_TOUCH -> 16;
            case UNBREAKING -> 17;
            case FORTUNE -> 18;
            case POWER -> 19;
            case PUNCH -> 20;
            case FLAME -> 21;
            case INFINITY -> 22;
            case LUCK_OF_THE_SEA -> 23;
            case LURE -> 24;
        };
    }

    /** Whether MCPE 0.14 knows this one at all — the crash gate, as {@code Pe014Blocks} is for blocks. */
    public static boolean supportedBy014(Enchantment enchantment) {
        return bedrockId(enchantment) <= MAX_BEDROCK_014_ID;
    }
}
