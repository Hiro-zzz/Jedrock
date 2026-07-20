package com.jedrock.core.net;

import com.jedrock.api.player.Player;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;

/**
 * One tapped packet handed to a packet listener — the raw, edition-agnostic view a script sees through the
 * {@code packets} global. A packet is just its numeric {@code id} and its {@code payload} bytes (everything
 * after the id), tagged with the {@link ProtocolVersion edition} that speaks it and the {@link PacketDirection
 * direction} it's travelling. Cross-edition typing is impossible (four wire formats), so this is deliberately
 * the honest low-level primitive — {@code id + bytes} — that every protocol shares.
 *
 * <p>A listener may {@link #cancel()} the packet (drop it before the core handles an inbound one, or before an
 * outbound one hits the socket). The payload is read-only: this first cut observes and cancels, it does not
 * rewrite bytes in flight. {@link #getPlayer()} is {@code null} for a packet seen before the player has
 * finished logging in (handshake / login traffic).
 */
public final class PacketEvent {

    private final ProtocolVersion protocol;
    private final PacketDirection direction;
    private final int id;
    private final byte[] payload;
    private final Player player;
    private final PlayerConnection connection;
    private boolean cancelled;

    public PacketEvent(ProtocolVersion protocol, PacketDirection direction, int id, byte[] payload,
                       Player player, PlayerConnection connection) {
        this.protocol = protocol;
        this.direction = direction;
        this.id = id;
        this.payload = payload;
        this.player = player;
        this.connection = connection;
    }

    /** The packet's numeric id (a JE/PE packet id in that edition's own numbering). */
    public int getId() {
        return id;
    }

    /**
     * The packet payload — every byte after the id. Read-only: mutating the array does not change what goes
     * on the wire (this cut cancels, it does not rewrite). In JS it's a Java {@code byte[]} — {@code
     * getBytes().length} and indexing work (bytes are signed, so {@code & 0xFF} to read one unsigned).
     */
    public byte[] getBytes() {
        return payload;
    }

    /** How many payload bytes there are (after the id) — a convenience for scripts. */
    public int getLength() {
        return payload.length;
    }

    /** The edition this packet belongs to (JE 1.8/1.12.2, PE 1.1.5, PE 0.14). */
    public ProtocolVersion getProtocol() {
        return protocol;
    }

    public PacketDirection getDirection() {
        return direction;
    }

    /** {@code true} if this is a client→server packet. */
    public boolean isInbound() {
        return direction == PacketDirection.INBOUND;
    }

    /** The player this packet is to/from, or {@code null} if seen before login completed. */
    public Player getPlayer() {
        return player;
    }

    /** The raw connection — always present, even before {@link #getPlayer()} exists. */
    public PlayerConnection getConnection() {
        return connection;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Drop this packet: an inbound one never reaches the core, an outbound one never hits the socket. */
    public void cancel() {
        this.cancelled = true;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
