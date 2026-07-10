package com.jedrock.utils.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Pins JSON string escaping (see {@link JsonText}) — structural chars and control chars alike. */
class JsonTextTest {

    @Test
    void nullAndEmptyPassThrough() {
        assertNull(JsonText.escape(null));
        assertEquals("", JsonText.escape(""));
    }

    @Test
    void plainTextIsNotCopied() {
        // The common chat case (no special chars, § codes included) must allocate nothing.
        String plain = "§ahello §rworld";
        assertSame(plain, JsonText.escape(plain));
    }

    @Test
    void escapesQuoteAndBackslash() {
        assertEquals("a\\\"b\\\\c", JsonText.escape("a\"b\\c"));
    }

    @Test
    void escapesNamedControlCharactersSoJsonStaysValid() {
        // A newline a Bedrock client slips into a chat line would otherwise break the Java chat JSON.
        assertEquals("line1\\nline2", JsonText.escape("line1\nline2"));
        assertEquals("a\\tb", JsonText.escape("a\tb"));
        assertEquals("\\r\\b\\f", JsonText.escape("\r\b\f"));
    }

    @Test
    void escapesOtherControlCharsAsUnicode() {
        assertEquals("\\u0000", JsonText.escape("\0"));       // NUL
        assertEquals("x\\u001fy", JsonText.escape("x\037y")); // unit separator (0x1F)
    }
}
