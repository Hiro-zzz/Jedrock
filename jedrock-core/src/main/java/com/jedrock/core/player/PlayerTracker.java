package com.jedrock.core.player;

import com.jedrock.api.player.ArmorSlot;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;

/**
 * Who can see whom — the interest set behind every avatar relay.
 *
 * <p>Before this existed, every player held an avatar of every other player in their world, and every
 * relay (a move, a pose, an arm swing) walked the whole roster. That is a packet per player per movement
 * packet: n players walking cost n² writes a tick, and most of them told a client to move an avatar
 * standing in terrain that client was never sent. This narrows both ends — a client is only ever told
 * about players it could actually see, and the relay loop is over that set rather than the roster.
 *
 * <p><b>The rule.</b> Chunk-square distance, against the same radius {@code ChunkView} streams: if your
 * chunk isn't in my window, your avatar would be standing in terrain I don't have, so I don't want it.
 * Being the same test and the same radius for everyone, visibility is <b>symmetric</b> — which is why one
 * set per player serves as both "whose avatars I hold" and "who holds mine", and why a link is made and
 * broken for the pair rather than per direction. Leaving costs one extra chunk of hysteresis
 * ({@link #keepRadius}) so a player treading back and forth over one chunk line doesn't spawn and despawn
 * on every step.
 *
 * <p><b>When it runs.</b> Only when a player crosses a chunk boundary — exactly the condition
 * {@code ChunkView.recenter} already no-ops on. Standing still cannot change who is in range, and if the
 * other party moves, their own crossing updates the pair from their side.
 *
 * <p><b>Threading.</b> Refreshes arrive from each player's own network thread, so two of them can meet on
 * the same pair. A pair is therefore gated on a single atomic add (or remove) against the lower entity id
 * of the two, so exactly one thread performs the transition and nobody sends a client a second spawn for
 * an avatar it already has — which on a 1.1.5 client is not a cosmetic problem.
 */
public final class PlayerTracker {

    private final PlayerRegistry players;
    /** Within this many chunks, an avatar is shown. */
    private final int spawnRadius;
    /** Past this many chunks, it is hidden — one wider than {@link #spawnRadius}, so the edge doesn't chatter. */
    private final int keepRadius;

    public PlayerTracker(PlayerRegistry players, int viewDistanceChunks) {
        this.players = players;
        this.spawnRadius = Math.max(1, viewDistanceChunks);
        this.keepRadius = this.spawnRadius + 1;
    }

    /**
     * Bring {@code subject}'s interest set up to date: spawn the avatars that came into range and despawn
     * the ones that left, in both directions. Call it when they cross a chunk boundary, join, or arrive in
     * a world.
     */
    public void refresh(CorePlayer subject) {
        World world = subject.getWorld();
        Location at = subject.getLocation();
        int cx = at.getBlockX() >> 4;
        int cz = at.getBlockZ() >> 4;

        for (CorePlayer other : players.online()) {
            if (other == subject) {
                continue;
            }
            boolean linked = subject.sees(other);
            if (other.getWorld() != world) {
                // Another world is not another distance — there is no avatar there to move. The world
                // switch itself unlinks, so this only catches a pair that raced one.
                if (linked) {
                    unlink(subject, other);
                }
                continue;
            }
            Location oat = other.getLocation();
            int distance = Math.max(Math.abs((oat.getBlockX() >> 4) - cx),
                                    Math.abs((oat.getBlockZ() >> 4) - cz));
            if (!linked && distance <= spawnRadius) {
                link(subject, other);
            } else if (linked && distance > keepRadius) {
                unlink(subject, other);
            }
        }
    }

    /**
     * Drop {@code gone} from every interest set that holds them, hiding the avatar on each side. What a
     * disconnect and the leaving half of a world switch both need — after it, nobody is relaying anything
     * about a player who isn't there.
     */
    public void forget(CorePlayer gone) {
        for (CorePlayer other : gone.getVisible()) {
            unlink(gone, other);
        }
    }

    /**
     * Whether {@code viewer} currently holds {@code subject}'s avatar. For a caller that has to decide
     * whether a targeted push is worth sending.
     */
    public static boolean canSee(CorePlayer viewer, CorePlayer subject) {
        return viewer == subject || viewer.sees(subject);
    }

    // ===== The pair transitions =====

    /**
     * Make the pair mutually visible, once. The add against the lower entity id is the gate: only the
     * thread that wins it goes on to spawn the avatars, so a pair refreshed from both sides at the same
     * moment still produces exactly one spawn each way.
     */
    private void link(CorePlayer a, CorePlayer b) {
        CorePlayer first = a.getEntityId() < b.getEntityId() ? a : b;
        CorePlayer second = first == a ? b : a;
        if (!first.see(second)) {
            return; // another thread already linked this pair
        }
        second.see(first);
        show(first, second);
        show(second, first);
    }

    /** Break the pair, once — same gate, in reverse. */
    private void unlink(CorePlayer a, CorePlayer b) {
        CorePlayer first = a.getEntityId() < b.getEntityId() ? a : b;
        CorePlayer second = first == a ? b : a;
        if (!first.unsee(second)) {
            return; // another thread already unlinked this pair
        }
        second.unsee(first);
        hide(first, second);
        hide(second, first);
    }

    /**
     * Put {@code subject}'s avatar on {@code viewer}'s client, dressed: the pose it is holding, what is in
     * its hand and what it is wearing. Sent with the spawn rather than left to the next change, or an
     * avatar that came into view stands there bare and upright until its owner does something.
     */
    private static void show(CorePlayer viewer, CorePlayer subject) {
        PlayerConnection conn = viewer.getConnection();
        Location at = subject.getLocation();
        long entityId = subject.getEntityId();
        conn.showPlayer(subject.getUniqueId(), subject.getName(), entityId,
                at.x(), at.y(), at.z(), at.yaw(), at.pitch());
        if (subject.isSneaking() || subject.isSprinting() || subject.isUsingItem()) {
            conn.setPose(entityId, subject.isSneaking(), subject.isSprinting(), subject.isUsingItem());
        }
        int held = subject.getHeldItem();
        if (held != Blocks.AIR) {
            conn.showHeldItem(entityId, held);
        }
        if (subject.hasArmor()) {
            conn.showArmor(entityId,
                    subject.getArmor(ArmorSlot.HELMET), subject.getArmor(ArmorSlot.CHESTPLATE),
                    subject.getArmor(ArmorSlot.LEGGINGS), subject.getArmor(ArmorSlot.BOOTS));
        }
    }

    /** Take {@code subject}'s avatar off {@code viewer}'s client. */
    private static void hide(CorePlayer viewer, CorePlayer subject) {
        viewer.getConnection().hidePlayer(subject.getUniqueId(), subject.getEntityId());
    }
}
