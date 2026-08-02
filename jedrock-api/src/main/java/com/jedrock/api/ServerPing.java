package com.jedrock.api;

/**
 * What the server is about to say about itself to somebody who is only asking — the line in a client's
 * multiplayer list, before any connection exists.
 *
 * <p>Mutable on purpose: the network layer fills it in from the config and the live player count, hands it
 * to the core for anyone who wants to change it, and then serializes whatever it reads back. That is the
 * whole mechanism. There is no player here and there never will be — a ping is answered and the socket
 * closes, and the thing on the other end has not identified itself as anyone.
 *
 * <p>Answered on an I/O thread, once per client per refresh of their server list, which for a popular
 * server is often. Whatever a listener does here is on that path: read a counter, don't query a database.
 */
public final class ServerPing {

    private final String address;
    private final int protocol;
    private final boolean bedrock;

    private String motd;
    private int onlinePlayers;
    private int maxPlayers;

    public ServerPing(String address, int protocol, boolean bedrock,
                      String motd, int onlinePlayers, int maxPlayers) {
        this.address = address;
        this.protocol = protocol;
        this.bedrock = bedrock;
        this.motd = motd;
        this.onlinePlayers = onlinePlayers;
        this.maxPlayers = maxPlayers;
    }

    /** Where the ping came from. All that is known about who is asking. */
    public String getAddress() {
        return address;
    }

    /**
     * The protocol number in play — on Java the number the <em>client</em> announced (so a listener can
     * tell a 1.8 client from a 1.12.2 one and answer differently), on Bedrock the one this server speaks,
     * since that query carries no client version at all.
     */
    public int getProtocol() {
        return protocol;
    }

    /** Whether this is the Bedrock query rather than the Java status request. */
    public boolean isBedrock() {
        return bedrock;
    }

    /** The message shown under the server name. Authored in the unified markup. */
    public String getMotd() {
        return motd;
    }

    public void setMotd(String motd) {
        this.motd = motd == null ? "" : motd;
    }

    /** The player count to show. Not necessarily the real one — this is a display, not a source of truth. */
    public int getOnlinePlayers() {
        return onlinePlayers;
    }

    public void setOnlinePlayers(int onlinePlayers) {
        this.onlinePlayers = Math.max(0, onlinePlayers);
    }

    /** The maximum to show beside it. Changing it here does not let anybody in; it changes a number. */
    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = Math.max(0, maxPlayers);
    }
}
