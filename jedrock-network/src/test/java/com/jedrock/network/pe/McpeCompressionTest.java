package com.jedrock.network.pe;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpeCompressionTest {

    private final byte[] sample = "Jedrock PE 1.1.5 login batch payload — with some bytes to compress"
            .getBytes(StandardCharsets.UTF_8);

    @Test
    void zlibRoundTripDetectedAsNonRaw() {
        byte[] compressed = McpeCompression.deflate(sample, false);
        McpeCompression.Inflated result = McpeCompression.inflate(compressed);
        assertNotNull(result);
        assertArrayEquals(sample, result.data());
        assertFalse(result.raw(), "zlib-wrapped data should be detected as non-raw");
    }

    @Test
    void rawDeflateRoundTripDetectedAsRaw() {
        byte[] compressed = McpeCompression.deflate(sample, true);
        McpeCompression.Inflated result = McpeCompression.inflate(compressed);
        assertNotNull(result);
        assertArrayEquals(sample, result.data());
        assertTrue(result.raw(), "raw DEFLATE data should be detected as raw");
    }
}
