package com.jedrock.network.pe;

import java.util.UUID;

/**
 * Builds a placeholder Bedrock skin for a player until real skins are relayed from the Login JWT.
 *
 * <p>MCPE 1.1 has no signed-skin requirement, but the data must be a valid RGBA texture
 * (64x32 = 8192 bytes). We build a simple two-tone humanoid: a per-player palette colour on the
 * body and a lighter shade on the head, so remote players are distinct and read as characters
 * rather than flat blobs.
 */
final class McpeSkin {

    private McpeSkin() {}

    /** A small palette of clearly distinct colours so avatars are easy to tell apart. */
    private static final int[] PALETTE = {
            0xE64A3B, // red
            0x3B82E6, // blue
            0x2EA044, // green
            0xE6B02E, // amber
            0x8E44C4, // purple
            0x16A0A0, // teal
            0xE66AB0, // pink
            0xE67E22, // orange
            0x7FB31B, // lime
            0x5D6D7E, // slate
            0xC0392B, // crimson
            0x2C3E50, // navy
    };

    /**
     * A 64x32 RGBA placeholder skin keyed off the player's UUID. The head (top 16 rows of a
     * standard skin) is tinted lighter than the body.
     */
    static byte[] synthetic(UUID uuid) {
        int rgb = PALETTE[Math.floorMod(uuid.hashCode(), PALETTE.length)];
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        byte[] data = new byte[64 * 32 * 4];
        for (int y = 0; y < 32; y++) {
            boolean head = y < 16;
            int pr = head ? lighten(r) : r;
            int pg = head ? lighten(g) : g;
            int pb = head ? lighten(b) : b;
            for (int x = 0; x < 64; x++) {
                int i = (y * 64 + x) * 4;
                data[i] = (byte) pr;
                data[i + 1] = (byte) pg;
                data[i + 2] = (byte) pb;
                data[i + 3] = (byte) 0xFF;
            }
        }
        return data;
    }

    /** Shift a channel ~40% toward white — used to tint the head lighter than the body. */
    private static int lighten(int c) {
        return c + (255 - c) * 2 / 5;
    }
}
