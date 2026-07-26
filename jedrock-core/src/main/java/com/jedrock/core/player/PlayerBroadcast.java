package com.jedrock.core.player;

import com.jedrock.api.player.ArmorSlot;
import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;

/**
 * The one place that walks the online roster and pushes something to every connection.
 *
 * <p>Every cross-edition relay in the core is the same shape — loop the players, skip the one this is
 * about, call one method on each {@code PlayerConnection} — and each copy of that loop was a chance to
 * forget the skip or to iterate an allocating view. Gathering them here keeps the pattern in one place
 * and keeps it lean: the loops read the registry's live {@link PlayerRegistry#online()} view (no
 * unmodifiable wrapper per call), hoist the values they broadcast out of the loop, and take no lambda,
 * so a relay on a hot path allocates nothing.
 *
 * <p>Presentation only. Nothing here decides anything — callers have already applied the state change
 * to the core; this just tells everyone about it.
 */
public final class PlayerBroadcast {

    private final PlayerRegistry players;

    public PlayerBroadcast(PlayerRegistry players) {
        this.players = players;
    }

    /**
     * Send a system message to every online player — each connection serializes it in its own protocol,
     * so a JE and a PE player see the same line. This is the core of cross-platform interaction.
     *
     * @param except a player to skip (e.g. the join announcement's subject), or {@code null} for everyone
     */
    public void message(String message, Player except) {
        for (CorePlayer p : players.online()) {
            if (p != except) {
                p.sendMessage(message);
            }
        }
    }

    /** Relay a player's move to every other player's copy of their avatar. */
    public void move(CorePlayer player, double x, double y, double z, float yaw, float pitch) {
        long entityId = player.getEntityId();
        for (CorePlayer other : players.online()) {
            if (other != player) {
                other.getConnection().moveAvatar(entityId, x, y, z, yaw, pitch);
            }
        }
    }

    /**
     * The mechanics of a server-authoritative teleport, with no event: set the position, clear fall
     * tracking (a teleport is not a fall — don't bill the next move for the jump or a void drop), tell the
     * player's own client, and relay the move to every other avatar. Shared by the eventful teleport and
     * the respawn path.
     */
    public void teleport(CorePlayer player, Location to) {
        player.setLocation(to);
        player.resetFall();
        player.getConnection().teleport(to.x(), to.y(), to.z(), to.yaw(), to.pitch());
        move(player, to.x(), to.y(), to.z(), to.yaw(), to.pitch());
    }

    /** Relay a player's full pose (sneak + sprint + item-use share a flags field, so send together). */
    public void pose(CorePlayer player) {
        long entityId = player.getEntityId();
        boolean sneaking = player.isSneaking();
        boolean sprinting = player.isSprinting();
        boolean usingItem = player.isUsingItem();
        for (CorePlayer other : players.online()) {
            if (other != player) {
                other.getConnection().setPose(entityId, sneaking, sprinting, usingItem);
            }
        }
    }

    /**
     * Show what {@code holder} is holding on every other player's copy of their avatar, cross-edition.
     * Called when they switch hotbar slots and when the held stack itself changes (mine / place /
     * script edit) — the holder's own client draws their hand from their inventory, so they're skipped.
     */
    public void heldItem(CorePlayer holder) {
        int state = holder.getHeldItem();
        long entityId = holder.getEntityId();
        for (CorePlayer other : players.online()) {
            if (other != holder) {
                other.getConnection().showHeldItem(entityId, state);
            }
        }
    }

    /**
     * Dress {@code wearer}'s avatar on every client, cross-edition. Other players get the packet that
     * dresses an avatar; the wearer gets their own copy through a different one — JE reads its own
     * armor from the inventory window, but a Bedrock client shows the wearer nothing unless the pieces
     * are pushed to its dedicated armor window (which is why it's a separate call).
     */
    public void armor(CorePlayer wearer) {
        int helmet = wearer.getArmor(ArmorSlot.HELMET);
        int chestplate = wearer.getArmor(ArmorSlot.CHESTPLATE);
        int leggings = wearer.getArmor(ArmorSlot.LEGGINGS);
        int boots = wearer.getArmor(ArmorSlot.BOOTS);
        long entityId = wearer.getEntityId();
        for (CorePlayer other : players.online()) {
            if (other != wearer) {
                other.getConnection().showArmor(entityId, helmet, chestplate, leggings, boots);
            }
        }
        wearer.getConnection().sendOwnArmor(helmet, chestplate, leggings, boots);
    }

    /**
     * Re-send the sidebar to every player whose client can't hold one by itself.
     *
     * <p>A Java scoreboard is stateful — sent once, then only diffed — so nothing here touches it. A
     * Bedrock client has no scoreboard at all and borrows a HUD line that fades after a couple of
     * seconds, so its connection asks (via {@link com.jedrock.api.player.PlayerConnection#sidebarRepaintTicks()})
     * to be repainted on a cadence. The core never learns which edition that is; it just honours the
     * number.
     *
     * <p>Cost when nobody uses a sidebar: one field read per online player per tick. The
     * {@code hasSidebar} check comes first precisely so a server with no sidebars pays nothing else.
     */
    public void repaintSidebars(long tick) {
        for (CorePlayer p : players.online()) {
            if (!p.hasSidebar()) {
                continue;
            }
            int every = p.getConnection().sidebarRepaintTicks();
            if (every > 0 && tick % every == 0) {
                p.getConnection().setSidebar(p.getSidebarTitle(), p.getSidebarLines());
            }
        }
    }

    /** Relay a player's arm swing to every other client (their own already drew it). */
    public void swing(CorePlayer player) {
        long entityId = player.getEntityId();
        for (CorePlayer other : players.online()) {
            if (other != player) {
                other.getConnection().swingArm(entityId);
            }
        }
    }

    /**
     * Flash the victim's avatar red on every other client. The victim's own client shows the hit from its
     * dropping health bar, so it doesn't need this. Covers every damage source — PvP, fall, void.
     */
    public void hurtAnimation(CorePlayer victim) {
        long entityId = victim.getEntityId();
        for (CorePlayer other : players.online()) {
            if (other != victim) {
                other.getConnection().playHurtAnimation(entityId);
            }
        }
    }
}
