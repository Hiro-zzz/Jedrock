package com.jedrock.network.pe;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Bedrock client sends a slash command as a structured CommandStep packet, never as chat — pin the
 * decode back into the {@code "/command args"} line the core's command system routes.
 */
class McpeCommandStepTest {

    /** Build a CommandStep body exactly as the client writes it (id already consumed by the dispatcher). */
    private static ByteBuf body(String command, String inputJson) {
        ByteBuf b = Unpooled.buffer();
        ByteBufUtils.writeString(b, command);
        ByteBufUtils.writeString(b, "");   // overload
        ByteBufUtils.writeVarInt(b, 0);    // unused
        ByteBufUtils.writeVarInt(b, 0);    // currentStep
        b.writeBoolean(true);              // done
        ByteBufUtils.writeVarLong(b, 42);  // clientId
        ByteBufUtils.writeString(b, inputJson);
        ByteBufUtils.writeString(b, "");   // outputJson (never read)
        return b;
    }

    @Test
    void rebuildsCommandLineWithArguments() {
        ByteBuf b = body("gamemode", "{\"gameMode\":\"survival\"}");
        try {
            assertEquals("/gamemode survival", McpeCommandStep.readCommandLine(b));
        } finally {
            b.release();
        }
    }

    @Test
    void rebuildsBareCommandWithNoArguments() {
        ByteBuf b = body("help", "null");
        try {
            assertEquals("/help", McpeCommandStep.readCommandLine(b));
        } finally {
            b.release();
        }
    }

    @Test
    void emptyCommandNameYieldsNoLine() {
        ByteBuf b = body("", "null");
        try {
            assertNull(McpeCommandStep.readCommandLine(b));
        } finally {
            b.release();
        }
    }

    @Test
    void jsonValuesTakesObjectValuesInOrderSkippingKeys() {
        assertEquals(List.of("survival"), McpeCommandStep.jsonValues("{\"gameMode\":\"survival\"}"));
        assertEquals(List.of("x", "y"), McpeCommandStep.jsonValues("{\"a\":\"x\",\"b\":\"y\"}"));
        assertEquals(List.of("Steve", "1"), McpeCommandStep.jsonValues("{\"who\":\"Steve\",\"mode\":1}"));
    }

    @Test
    void jsonValuesHandlesArraysAndScalars() {
        assertEquals(List.of("survival"), McpeCommandStep.jsonValues("[\"survival\"]"));
        assertEquals(List.of("1", "2", "3"), McpeCommandStep.jsonValues("[1, 2, 3]"));
    }

    @Test
    void jsonValuesUnescapesStrings() {
        assertEquals(List.of("he said \"hi\""),
                McpeCommandStep.jsonValues("{\"a\":\"he said \\\"hi\\\"\"}"));
    }

    @Test
    void jsonValuesIgnoresNonContainers() {
        assertTrue(McpeCommandStep.jsonValues("null").isEmpty());
        assertTrue(McpeCommandStep.jsonValues("").isEmpty());
        assertTrue(McpeCommandStep.jsonValues("{}").isEmpty());
        assertTrue(McpeCommandStep.jsonValues(null).isEmpty());
    }
}
