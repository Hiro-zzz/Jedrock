package com.jedrock.network.handler.je;

import com.jedrock.api.world.Particle;
import com.jedrock.api.world.Sound;

/**
 * Canonical {@link Sound} / {@link Particle} → Java Edition wire values. Particle numeric ids are the
 * pre-flattening table (stable from 1.8 through 1.12.2 — ground truth PrismarineJS/minecraft-data
 * {@code pc/1.8/particles.json}); sound <em>names</em> changed between the eras, so each version keeps
 * its own table ({@code random.click} vs {@code ui.button.click}).
 */
final class JeEffects {

    /** 1.8 (protocol 47) sound name for the Named Sound Effect packet (0x29). */
    static String soundName1_8(Sound sound) {
        return switch (sound) {
            case CLICK -> "random.click";
            case DOOR -> "random.door_open";
            case FIZZ -> "random.fizz";
            case BOW -> "random.bow";
            case TELEPORT -> "mob.endermen.portal";
            case ANVIL_USE -> "random.anvil_use";
            case ANVIL_BREAK -> "random.anvil_break";
            case EXPLODE -> "random.explode";
            case LEVELUP -> "random.levelup";
            case POP -> "random.pop";
            case ORB -> "random.orb";
            case NOTE -> "note.pling";
        };
    }

    /** 1.12.2 (protocol 340) sound name for the Named Sound Effect packet (0x19). */
    static String soundName1_12(Sound sound) {
        return switch (sound) {
            case CLICK -> "ui.button.click";
            case DOOR -> "block.wooden_door.open";
            case FIZZ -> "block.fire.extinguish";
            case BOW -> "entity.arrow.shoot";
            case TELEPORT -> "entity.endermen.teleport";
            case ANVIL_USE -> "block.anvil.use";
            case ANVIL_BREAK -> "block.anvil.break";
            case EXPLODE -> "entity.generic.explode";
            case LEVELUP -> "entity.player.levelup";
            case POP -> "entity.item.pickup";
            case ORB -> "entity.experience_orb.pickup";
            case NOTE -> "block.note.pling";
        };
    }

    /** Numeric particle id for the World Particles packet (1.8 0x2a / 1.12.2 0x22 — same table). */
    static int particleId(Particle particle) {
        return switch (particle) {
            case POOF -> 0;             // "explode"
            case HUGE_EXPLOSION -> 2;
            case BUBBLE -> 4;
            case SPLASH -> 5;
            case CRIT -> 9;
            case SMOKE -> 11;
            case LARGE_SMOKE -> 12;
            case DRIP_WATER -> 18;
            case DRIP_LAVA -> 19;
            case VILLAGER_ANGRY -> 20;
            case VILLAGER_HAPPY -> 21;
            case NOTE -> 23;
            case PORTAL -> 24;
            case ENCHANTMENT -> 25;
            case FLAME -> 26;
            case LAVA -> 27;
            case REDSTONE -> 30;
            case SNOWBALL_POOF -> 31;
            case SLIME -> 33;
            case HEART -> 34;
        };
    }

    /**
     * Write the World Particles body — identical on 1.8 (0x2a) and 1.12.2 (0x22), ground truth
     * minecraft-data {@code packet_world_particles}: particle id (i32), long-distance (bool), position
     * (3 × f32), offsets (3 × f32 — the client scatters gaussian-scaled by these), particle data /
     * "speed" (f32), count (i32). Ids 36–38 carry extra varints; the canonical set stays below them.
     */
    static void writeParticleBody(io.netty.buffer.ByteBuf b, int particleId,
                                  double x, double y, double z, int count, double spread) {
        b.writeInt(particleId);
        b.writeBoolean(false);
        b.writeFloat((float) x);
        b.writeFloat((float) y);
        b.writeFloat((float) z);
        b.writeFloat((float) spread);
        b.writeFloat((float) spread);
        b.writeFloat((float) spread);
        b.writeFloat(0f);
        b.writeInt(Math.max(1, count));
    }

    /**
     * Write the Entity Effect body, which is the same five fields on both JE versions: entity id
     * (VarInt), effect, amplifier, duration (VarInt ticks), and one trailing byte.
     *
     * <p>That last byte is where they part company, so the caller supplies it: on <b>1.8</b> it is a bool
     * that <i>hides</i> the particles, on <b>1.12.2</b> a flags byte that <i>shows</i> them
     * ({@code 0x01} ambient, {@code 0x02} particles). Same position, opposite meaning — which is exactly
     * the field ViaVersion converts between the two, and the kind of thing that would otherwise be found
     * by wondering why the swirl is missing on one client and not the other.
     */
    static void writeEffectBody(io.netty.buffer.ByteBuf b, int entityId, int effectId, int amplifier,
                                int durationTicks, int trailingByte) {
        com.jedrock.utils.ByteBufUtils.writeVarInt(b, entityId);
        b.writeByte(effectId);
        b.writeByte(amplifier);
        com.jedrock.utils.ByteBufUtils.writeVarInt(b, durationTicks);
        b.writeByte(trailingByte);
    }

    /** Write the Remove Entity Effect body — the entity and the effect, identical on both versions. */
    static void writeRemoveEffectBody(io.netty.buffer.ByteBuf b, int entityId, int effectId) {
        com.jedrock.utils.ByteBufUtils.writeVarInt(b, entityId);
        b.writeByte(effectId);
    }

    /** The trailing byte's two spellings, so neither handler has to remember which way round it is. */
    static int particleFlags1_12(boolean particles) {
        return particles ? 0x02 : 0x00;
    }

    static int particleFlag1_8(boolean particles) {
        return particles ? 0 : 1;   // the field is "hide particles"
    }

    private JeEffects() {}
}
