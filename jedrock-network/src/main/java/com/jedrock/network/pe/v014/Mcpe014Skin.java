package com.jedrock.network.pe.v014;

import java.util.UUID;

/**
 * A placeholder skin for the MCPE 0.14 player list. Unlike 1.1.5, 0.14's {@code AddPlayer} carries no
 * skin (the client draws avatars with its own), but {@code PlayerList} <em>does</em> — and the 0.14
 * client crashes on an empty/zero-length one. A 0.14 client sends a 64×64 RGBA skin (16384 bytes) under
 * geometry {@code Standard_Custom}, so we hand the list a valid texture of exactly that size.
 */
public final class Mcpe014Skin {

    private Mcpe014Skin() {}

    /** Geometry id paired with the {@value #WIDTH}×{@value #HEIGHT} texture below. */
    public static final String SKIN_NAME = "Standard_Custom";

    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;

    private static final int[] PALETTE = {
            0xE64A3B, 0x3B82E6, 0x2EA044, 0xE6B02E, 0x8E44C4, 0x16A0A0,
            0xE66AB0, 0xE67E22, 0x7FB31B, 0x5D6D7E, 0xC0392B, 0x2C3E50,
    };

    /** A 64×64 RGBA placeholder keyed off the UUID (top half tinted lighter, like a head over a body). */
    public static byte[] synthetic(UUID uuid) {
        int rgb = PALETTE[Math.floorMod(uuid.hashCode(), PALETTE.length)];
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        byte[] data = new byte[WIDTH * HEIGHT * 4];
        for (int y = 0; y < HEIGHT; y++) {
            boolean head = y < HEIGHT / 2;
            int pr = head ? lighten(r) : r;
            int pg = head ? lighten(g) : g;
            int pb = head ? lighten(b) : b;
            for (int x = 0; x < WIDTH; x++) {
                int i = (y * WIDTH + x) * 4;
                data[i] = (byte) pr;
                data[i + 1] = (byte) pg;
                data[i + 2] = (byte) pb;
                data[i + 3] = (byte) 0xFF;
            }
        }
        return data;
    }

    private static int lighten(int c) {
        return c + (255 - c) * 2 / 5;
    }
}
