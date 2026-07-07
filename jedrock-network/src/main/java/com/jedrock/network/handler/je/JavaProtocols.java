package com.jedrock.network.handler.je;

import com.jedrock.api.protocol.ProtocolVersion;

/**
 * Registry mapping a Java Edition client's handshake protocol number to the {@link JavaProtocol}
 * that speaks it. Each lookup returns a <b>fresh</b> instance, because a protocol holds
 * per-connection transient state (creative hotbar, etc.).
 *
 * <p>This is the seam that lets one TCP listener serve several JE versions at once: the client
 * announces its protocol in the handshake, and {@link JavaHandshakeHandler} installs the match here.
 */
public final class JavaProtocols {

    private JavaProtocols() {}

    /**
     * @return a new protocol handler for the given handshake protocol number, or {@code null} if the
     *         version is not supported (the caller then refuses the login).
     */
    public static JavaProtocol create(int protocolNumber) {
        ProtocolVersion version = ProtocolVersion.fromProtocolNumber(protocolNumber);
        return version == null ? null : create(version);
    }

    /** @return a new protocol handler for a resolved supported version, or {@code null}. */
    public static JavaProtocol create(ProtocolVersion version) {
        return switch (version) {
            case JE_1_8 -> new Java1_8ProtocolHandler();
            case JE_1_12_2 -> new JavaEditionProtocolHandler();
            default -> null; // Bedrock versions never reach the JE registry
        };
    }
}
