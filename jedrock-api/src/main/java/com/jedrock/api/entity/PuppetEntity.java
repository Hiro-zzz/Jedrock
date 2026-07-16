package com.jedrock.api.entity;

import com.jedrock.api.player.Player;
import com.jedrock.api.world.Location;

import java.util.function.Consumer;

/**
 * A <b>puppet</b>: a server-puppeteered visual entity, the illusionist take on a mob / NPC / hologram. It is
 * never simulated — the server spawns a visual, moves it, and relays it cross-edition, and that is all. Out
 * of the box a puppet is a dummy that stands where placed; its "life" comes from the platform API driving it
 * (movement, reactions) rather than any server-side AI or physics.
 *
 * <p>This is the primitive the scripting API will build on. For now it exposes just enough to place one,
 * move it, remove it, and react to a player interacting with it.
 */
public interface PuppetEntity extends Entity {

    /** The canonical type this puppet renders as. */
    EntityType getEntityType();

    /**
     * The puppet's name. For a {@link EntityType#PLAYER} puppet this is the NPC's shown name (its tab entry
     * and nametag); for a mob puppet it is descriptive only (mobs carry no nametag in this foundation).
     */
    String getName();

    /** Move the puppet to {@code to} and relay the move to every viewer. */
    void teleport(Location to);

    /**
     * Set the callback fired when a player interacts with (attacks) this puppet — the seam the future API
     * subscribes through. Passing {@code null} clears it. Called on a network thread; keep it lean and
     * thread-safe.
     */
    void onInteract(Consumer<Player> handler);
}
