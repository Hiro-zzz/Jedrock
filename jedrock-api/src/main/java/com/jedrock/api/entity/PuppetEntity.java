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
     * and the name its avatar carries); for a mob puppet it is descriptive only — the text a mob shows
     * above its head is its {@linkplain #setNameTag(String) name tag}, set separately.
     */
    String getName();

    /**
     * The floating text above the puppet, or {@code null} if it has none. Authored in the edition-agnostic
     * chat markup ({@code {color}} tags plus Markdown), so one string renders identically everywhere.
     */
    String getNameTag();

    /**
     * Set the floating text above the puppet; {@code null} or empty removes it.
     *
     * <p>Has no effect on a {@link EntityType#PLAYER} puppet: every edition draws a player avatar's floating
     * name from its player name, not from entity metadata, so such a puppet is renamed by respawning it.
     */
    void setNameTag(String nameTag);

    /** Move the puppet to {@code to} and relay the move to every viewer. */
    void teleport(Location to);

    /** Turn the puppet in place (degrees) and relay it — head and body together. */
    void setRotation(float yaw, float pitch);

    /**
     * Turn the puppet to face a point — the cheapest illusion of attention there is: no AI, no tracking,
     * just the rotation a script asks for. Yaw and pitch are computed from the puppet's eyes to {@code target}.
     */
    void lookAt(Location target);

    /** Whether {@code flag} is currently set. */
    boolean hasFlag(PuppetFlag flag);

    /** Set or clear one visual flag and relay the puppet's full flag set to every viewer. */
    void setFlag(PuppetFlag flag, boolean on);

    /** Play the arm-swing animation on every viewer's client. */
    void swing();

    /**
     * Play the hurt animation (the red flash) on every viewer's client. A puppet has no health — this is
     * the look of damage, not damage; nothing is simulated.
     */
    void hurt();

    /**
     * Set the callback fired when a player interacts with (attacks) this puppet — the seam the future API
     * subscribes through. Passing {@code null} clears it. Called on a network thread; keep it lean and
     * thread-safe.
     */
    void onInteract(Consumer<Player> handler);
}
