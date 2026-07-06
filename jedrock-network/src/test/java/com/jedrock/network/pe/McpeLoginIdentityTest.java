package com.jedrock.network.pe;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Login-JWT parsing: gamertag, UUID and the real skin are pulled from the token payloads, and a
 * malformed / wrong-sized skin degrades to no skin (the session then uses a synthetic one).
 */
class McpeLoginIdentityTest {

    private static final String UUID_STR = "01234567-89ab-cdef-0123-456789abcdef";

    /** Wrap a JSON payload as a single unsigned "header.payload." JWT and then as a Login body. */
    private static ByteBuf loginBodyWith(String json) {
        Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
        String header = url.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = url.encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String token = header + "." + payload + ".";
        return Unpooled.wrappedBuffer(token.getBytes(StandardCharsets.ISO_8859_1));
    }

    @Test
    void extractsNameUuidAndRealSkin() {
        byte[] skin = new byte[64 * 64 * 4]; // valid modern skin size
        for (int i = 0; i < skin.length; i++) skin[i] = (byte) (i * 7);
        String skinB64 = Base64.getEncoder().encodeToString(skin);
        String json = "{\"displayName\":\"Steve\",\"identity\":\"" + UUID_STR + "\","
                + "\"SkinId\":\"Custom\",\"SkinData\":\"" + skinB64 + "\"}";

        McpeLoginIdentity.Identity id = McpeLoginIdentity.extract(loginBodyWith(json));

        assertEquals("Steve", id.name());
        assertEquals(UUID_STR, id.uuid().toString());
        assertEquals("Custom", id.skinId());
        assertArrayEquals(skin, id.skinData(), "raw skin bytes round-trip");
    }

    @Test
    void wrongSizedSkinIsDropped() {
        String skinB64 = Base64.getEncoder().encodeToString(new byte[100]); // not a valid texture size
        String json = "{\"displayName\":\"Alex\",\"identity\":\"" + UUID_STR + "\","
                + "\"SkinId\":\"Custom\",\"SkinData\":\"" + skinB64 + "\"}";

        McpeLoginIdentity.Identity id = McpeLoginIdentity.extract(loginBodyWith(json));

        assertEquals("Alex", id.name());
        assertNull(id.skinData(), "an implausible skin size is rejected");
    }
}
