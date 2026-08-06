package com.jedrock.core;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerWorldChangeEvent;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;
import com.jedrock.core.entity.EntityDirector;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerTracker;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.utils.JLogger;

/**
 * Moving one player from one world to another — the one operation multiple worlds exist for, and the
 * only place in the core that has to undo a player's whole presence and build it again somewhere else.
 *
 * <p>A world switch is not a teleport with extra steps. A teleport changes coordinates and everyone
 * keeps watching; this changes <em>what everyone can see</em>. Four things move together, and leaving
 * any one of them out is a visible bug rather than a subtle one:
 *
 * <ul>
 *   <li><b>Membership</b> — the player leaves the old world's roster and joins the new one's, which is
 *       what the block-change relay and the weather use to decide who hears about them.</li>
 *   <li><b>Avatars</b> — they vanish from every client that was holding them, and appear on the clients
 *       near enough to want them over there; and their own client is told to forget the first set and
 *       shown the second. Both halves are one call to {@link PlayerTracker} each, because a pair in its
 *       interest set is symmetric — skip the "forget" half and the departed stand around as ghosts
 *       forever.</li>
 *   <li><b>Props</b> — puppets, holograms and scenes belong to a world too, so the traveller's client
 *       drops the old world's and is caught up on the new one's.</li>
 *   <li><b>Terrain</b> — the connection re-points at the new world and re-streams it, which is the
 *       protocol half and the only part that differs per edition (see {@code switchWorld}).</li>
 * </ul>
 *
 * <p>The order matters: everything is torn down while the player still counts as being in the old
 * world, and built back up only after they count as being in the new one. Doing it the other way round
 * means iterating a roster the player is already half-in and half-out of.
 */
final class WorldTravel {

    private static final JLogger LOGGER = JLogger.getLogger(WorldTravel.class);

    private final PlayerTracker tracker;
    private final EntityDirector entities;
    private final EventBus events;
    /** Re-stated on arrival; set after construction and null in tests that don't wire one. */
    private com.jedrock.core.effect.EffectService effects;

    void setEffects(com.jedrock.core.effect.EffectService effects) {
        this.effects = effects;
    }

    WorldTravel(PlayerTracker tracker, EntityDirector entities, EventBus events) {
        this.tracker = tracker;
        this.entities = entities;
        this.events = events;
    }

    /**
     * Move {@code player} to {@code to}, which must be in a different world than they are in.
     *
     * @return {@code false} if a listener vetoed the move (the player has not been touched)
     */
    boolean travel(CorePlayer player, Location to) {
        CoreWorld from = player.getCoreWorld();
        if (!(to.world() instanceof CoreWorld target) || target == from) {
            return false; // not a world change; the caller should have used a plain teleport
        }

        // The last chance to refuse or redirect. Nothing has moved yet, so a veto leaves nothing to undo
        // — the same contract every other cancellable event in this core keeps.
        if (events.hasListeners(PlayerWorldChangeEvent.class)) {
            PlayerWorldChangeEvent event = events.post(new PlayerWorldChangeEvent(player, from, to));
            if (event.isCancelled()) {
                return false;
            }
            to = event.getTo();
            if (!(to.world() instanceof CoreWorld redirected) || redirected == from) {
                return false; // redirected back into the world they're already in — nothing to do here
            }
            target = redirected;
        }

        // ===== Leave =====
        // Both directions at once: forget() breaks every pair this player was in, which hides their avatar
        // on each watcher and each watcher's on theirs. Skip the "forget" half and the departed stand
        // around as ghosts forever.
        tracker.forget(player);
        entities.hideAllFrom(player.getConnection(), from);
        from.removePlayer(player);

        // ===== Cross =====
        // State first, then the wire: the connection's switchWorld re-points at the new world and streams
        // it, and the player must already belong there when the first block edit is relayed back.
        player.enterWorld(to);
        player.resetFall(); // arriving is not falling, whatever the drop between the two worlds looks like
        target.addPlayer(player);
        player.getConnection().switchWorld(target, to.x(), to.y(), to.z(), to.yaw(), to.pitch(),
                player.getGameMode());

        // ===== Arrive =====
        // enterWorld above put the player's own state at the destination, so the tracker is reading the
        // right position when it decides who over there is close enough to see, and to see them.
        tracker.refresh(player);
        entities.showAllTo(player.getConnection(), target);

        // The sky the new world is under — a late arrival walks into the weather everyone else sees.
        if (target.getWeather() != com.jedrock.api.world.Weather.CLEAR) {
            player.getConnection().sendWeather(target.getWeather());
        }
        // Worlds keep their own time, so arriving in one means arriving at its hour, not carrying yours.
        player.getConnection().sendTime(target.getTime(), target.isDaylightCycle());
        // Effects, unlike the weather and the hour, belong to the player rather than the world — so they
        // travel with them, and are re-stated because the client is the one holding the countdown and a
        // dimension change is exactly the sort of thing it drops them on.
        if (effects != null) {
            effects.resend(player);
        }

        LOGGER.info(player.getName() + " travelled from '" + from.getName() + "' to '" + target.getName()
                + "' (" + target.getDimension() + ")");
        return true;
    }

    /** Whether {@code to} is in a different world than {@code player} — what makes a teleport a journey. */
    static boolean isCrossWorld(Player player, Location to) {
        return to != null && to.world() != null && to.world() != player.getWorld();
    }
}
