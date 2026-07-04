package com.jedrock.network.pe;

import com.jedrock.utils.JLogger;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts a player's gamertag + UUID from the MCPE Login body (a protocol int, then a connection
 * request holding a chain of JWTs). Best-effort: a parse failure falls back to a generated identity
 * so it never blocks the join.
 */
final class McpeLoginIdentity {

    private static final JLogger LOGGER = JLogger.getLogger(McpeLoginIdentity.class);

    private McpeLoginIdentity() {}

    /** The identity the core keys a Bedrock player on. */
    record Identity(UUID uuid, String name) {}

    private static final Pattern JWT_TOKEN =
            Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    private static final Pattern DISPLAY_NAME =
            Pattern.compile("\"displayName\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern IDENTITY =
            Pattern.compile("\"identity\"\\s*:\\s*\"([0-9a-fA-F-]{32,36})\"");

    /**
     * Best-effort extraction of the player's gamertag + UUID from the MCPE Login body. Falls back to
     * a generated identity so a parse failure never blocks the join.
     */
    static Identity extract(ByteBuf loginBody) {
        String name = null;
        String identity = null;
        try {
            // Scan the whole Login body for JWT tokens (ASCII "eyJ..." inside the binary framing) —
            // robust to the exact packet layout. Decode each payload and look for the authenticated
            // extraData.
            byte[] all = new byte[loginBody.readableBytes()];
            loginBody.getBytes(loginBody.readerIndex(), all);
            String text = new String(all, StandardCharsets.ISO_8859_1);

            Matcher tokens = JWT_TOKEN.matcher(text);
            while (tokens.find() && (name == null || identity == null)) {
                String[] parts = tokens.group().split("\\.");
                if (parts.length < 2) continue;
                byte[] decoded;
                try {
                    decoded = Base64.getUrlDecoder().decode(pad(parts[1]));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                String payload = new String(decoded, StandardCharsets.UTF_8);
                if (name == null) name = firstGroup(DISPLAY_NAME, payload);
                if (identity == null) identity = firstGroup(IDENTITY, payload);
            }
        } catch (Exception e) {
            LOGGER.warn("[PE] Login identity parse failed: " + e);
        }

        UUID uuid = null;
        if (identity != null) {
            try {
                uuid = UUID.fromString(identity);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (uuid == null) uuid = UUID.randomUUID();
        if (name == null || name.isBlank()) name = "Bedrock-" + Integer.toHexString(uuid.hashCode());
        return new Identity(uuid, name);
    }

    private static String firstGroup(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    private static String pad(String base64Url) {
        int rem = base64Url.length() % 4;
        return rem == 0 ? base64Url : base64Url + "====".substring(rem);
    }
}
