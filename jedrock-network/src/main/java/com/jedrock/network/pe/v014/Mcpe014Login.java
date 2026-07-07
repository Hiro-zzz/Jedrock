package com.jedrock.network.pe.v014;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Decoder for the MCPE 0.14 (protocol 45) Login packet, id {@code 0x8f}.
 *
 * <p>Layout confirmed from a real 0.14 client: {@code string username}, {@code int32 protocol},
 * {@code int32 protocol2}, then the client uuid, client id, server address and the inline skin. Only
 * the leading {@code username} + {@code protocol} are needed to place the player, so — exactly like
 * the Java handlers — we derive a stable <em>offline</em> UUID from the username rather than trusting
 * the exact position of the wire uuid (whose field order past {@code protocol2} still wants a
 * protocol-45 source cross-check). No Xbox/JWT chain exists in 0.14; this is all plaintext.
 */
public final class Mcpe014Login {

    /** MCPE 0.14 Login packet id. */
    public static final int PACKET_ID = 0x8f;

    /** Everything the core needs to register a 0.14 player. */
    public record Identity(String name, int protocol, UUID uuid) {}

    private Mcpe014Login() {}

    /**
     * Decode the identity from a Login body (positioned just past the packet id).
     * Reads only the leading fields it needs; the rest of the buffer (ids, address, skin) is ignored.
     */
    public static Identity decode(ByteBuf body) {
        String name = Mcpe014Codec.readString(body);
        int protocol = body.readInt();
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return new Identity(name, protocol, uuid);
    }
}
