package com.jedrock.network.protocol;

import com.jedrock.api.protocol.ProtocolVersion;

/**
 * Holds the current protocol state for one connection.
 * This lets lazy packet handling know what a given ID means right now.
 */
public final class ConnectionProtocol {

    private volatile ProtocolState state = ProtocolState.HANDSHAKE;
    private final ProtocolVersion version;

    public ConnectionProtocol(ProtocolVersion version) {
        this.version = version;
    }

    public ProtocolState getState() {
        return state;
    }

    public void setState(ProtocolState state) {
        this.state = state;
    }

    public ProtocolVersion getVersion() {
        return version;
    }

    public boolean isPlay() {
        return state == ProtocolState.PLAY;
    }
}
