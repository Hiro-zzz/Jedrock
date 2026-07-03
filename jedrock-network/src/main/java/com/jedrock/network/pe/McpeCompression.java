package com.jedrock.network.pe;

import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * zlib (de)compression for the MCPE {@code 0xFE} game-packet batch.
 *
 * <p>Bedrock has used both zlib-with-header and raw DEFLATE across versions, and the exact
 * mode for 1.1.5 is not reliably documented. {@link #inflate} therefore auto-detects by
 * trying zlib first, then raw, and reports which worked so replies can match.
 */
public final class McpeCompression {

    private McpeCompression() {}

    /** @param raw true if the data was raw DEFLATE (no zlib header), false if zlib-wrapped. */
    public record Inflated(byte[] data, boolean raw) {}

    /** Inflate a batch payload, auto-detecting zlib vs raw. Returns {@code null} if neither works. */
    public static Inflated inflate(byte[] input) {
        for (boolean raw : new boolean[]{false, true}) {
            byte[] out = tryInflate(input, raw);
            if (out != null) {
                return new Inflated(out, raw);
            }
        }
        return null;
    }

    private static byte[] tryInflate(byte[] input, boolean raw) {
        Inflater inflater = new Inflater(raw);
        inflater.setInput(input);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length * 4));
        byte[] chunk = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(chunk);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break;
                }
                out.write(chunk, 0, n);
            }
            return out.size() > 0 ? out.toByteArray() : null;
        } catch (DataFormatException e) {
            return null;
        } finally {
            inflater.end();
        }
    }

    /** Deflate a batch payload, matching the mode detected on the way in. */
    public static byte[] deflate(byte[] input, boolean raw) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, raw);
        deflater.setInput(input);
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, input.length / 2));
        byte[] chunk = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(chunk);
            out.write(chunk, 0, n);
        }
        deflater.end();
        return out.toByteArray();
    }
}
