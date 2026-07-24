package com.jedrock.network.pe;

import com.jedrock.api.world.Particle;
import com.jedrock.api.world.Sound;

/**
 * Canonical {@link Sound} / {@link Particle} → Bedrock wire values, for both PE eras. Ground truth is
 * PocketMine-MP: tag {@code 1.7dev-27} for protocol 113 ({@code LevelEventPacket},
 * {@code LevelSoundEventPacket}, {@code level/particle/Particle.php}) and the 0.14 tree at
 * {@code e11b76318} for protocol 45 (whose {@code LevelEventPacket} palette is smaller and whose
 * particle type ids are shifted).
 *
 * <p>Sounds ride two vehicles on 113: most map to a LevelEvent 1000-series id (shared with 0.14);
 * explode / level-up / note exist only as LevelSoundEvent ids there. 0.14 has LevelEvent only, so
 * sounds it predates fall back to the closest available id (documented per case below).
 */
public final class PeEffects {

    /** OR-mask that turns a particle type id into a LevelEvent event id (same on both eras). */
    public static final int ADD_PARTICLE_MASK = 0x4000;

    /**
     * Protocol-113 LevelEvent sound id for this sound, or {@code -1} when it must go out as a
     * LevelSoundEvent instead (see {@link #levelSound113(Sound)}).
     */
    public static int levelEventSound113(Sound sound) {
        return switch (sound) {
            case CLICK -> 1000;
            case BOW -> 1002;          // EVENT_SOUND_SHOOT
            case DOOR -> 1003;
            case FIZZ -> 1004;
            case TELEPORT -> 1018;     // enderman teleport
            case ANVIL_BREAK -> 1020;
            case ANVIL_USE -> 1021;
            case POP -> 1030;
            case ORB -> 1051;
            case EXPLODE, LEVELUP, NOTE -> -1; // LevelSoundEvent-only at 113
        };
    }

    /** Protocol-113 LevelSoundEvent id for the sounds that have no LevelEvent form. */
    public static int levelSound113(Sound sound) {
        return switch (sound) {
            case EXPLODE -> 45;        // SOUND_EXPLODE
            case LEVELUP -> 55;        // SOUND_LEVELUP
            case NOTE -> 72;           // SOUND_NOTE
            default -> throw new IllegalArgumentException(sound + " rides LevelEvent at 113");
        };
    }

    /**
     * Protocol-45 (0.14) LevelEvent sound id. The 0.14 palette stops at the anvil sounds, so the
     * younger sounds fall back to the closest available bang or click — a sound always plays.
     */
    public static int levelEventSound014(Sound sound) {
        return switch (sound) {
            case CLICK -> 1000;
            case BOW -> 1002;
            case DOOR -> 1003;
            case FIZZ -> 1004;
            case TELEPORT -> 1018;
            case ANVIL_BREAK -> 1020;
            case ANVIL_USE -> 1021;
            case EXPLODE -> 1012;              // nearest: door crash (a bang)
            case LEVELUP, POP, ORB, NOTE -> 1000; // nearest: a click
        };
    }

    /** Protocol-113 particle type id ({@code level/particle/Particle.php} at 1.7dev-27). */
    public static int particle113(Particle particle) {
        return switch (particle) {
            case POOF -> 5;            // TYPE_EXPLODE
            case HUGE_EXPLOSION -> 14;
            case BUBBLE -> 1;
            case SPLASH -> 21;
            case CRIT -> 2;
            case SMOKE -> 4;
            case LARGE_SMOKE -> 9;
            case DRIP_WATER -> 23;
            case DRIP_LAVA -> 24;
            case VILLAGER_ANGRY -> 32;
            case VILLAGER_HAPPY -> 33;
            case NOTE -> 36;
            case PORTAL -> 20;
            case ENCHANTMENT -> 34;
            case FLAME -> 7;
            case LAVA -> 8;
            case REDSTONE -> 10;
            case SNOWBALL_POOF -> 13;
            case SLIME -> 30;
            case HEART -> 17;
        };
    }

    /** Protocol-45 (0.14) particle type id — a shorter, shifted table; NOTE predates it (→ redstone speck). */
    public static int particle014(Particle particle) {
        return switch (particle) {
            case POOF -> 4;            // TYPE_EXPLODE
            case HUGE_EXPLOSION -> 13;
            case BUBBLE -> 1;
            case SPLASH -> 19;
            case CRIT -> 2;
            case SMOKE -> 3;
            case LARGE_SMOKE -> 8;
            case DRIP_WATER -> 21;
            case DRIP_LAVA -> 22;
            case VILLAGER_ANGRY -> 30;
            case VILLAGER_HAPPY -> 31;
            case NOTE -> 9;            // no note particle at 0.14 — nearest coloured speck
            case PORTAL -> 18;
            case ENCHANTMENT -> 32;
            case FLAME -> 6;
            case LAVA -> 7;
            case REDSTONE -> 9;
            case SNOWBALL_POOF -> 11;
            case SLIME -> 28;
            case HEART -> 15;
        };
    }

    private PeEffects() {}
}
