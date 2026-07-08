package com.jedrock.utils.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Pins the unified-markup → legacy {@code §} rendering (see {@link ChatText}). */
class ChatTextTest {

    private static final String S = "§"; // §

    @Test
    void plainTextPassesThrough() {
        assertEquals("hello world", ChatText.toLegacy("hello world"));
        assertNull(ChatText.toLegacy(null));
        assertEquals("", ChatText.toLegacy(""));
    }

    @Test
    void colorTags() {
        assertEquals(S + "ahi", ChatText.toLegacy("{green}hi"));
        assertEquals(S + "6gold", ChatText.toLegacy("{gold}gold"));
        // aliases
        assertEquals(S + "7grey", ChatText.toLegacy("{grey}grey"));
        assertEquals(S + "dpink", ChatText.toLegacy("{pink}pink"));
    }

    @Test
    void resetClearsColor() {
        assertEquals(S + "ca" + S + "rb", ChatText.toLegacy("{red}a{reset}b"));
    }

    @Test
    void markdownStyles() {
        assertEquals(S + "lbold", ChatText.toLegacy("**bold**"));
        // a style turned off mid-line inserts §r then re-applies nothing
        assertEquals("x" + S + "lb" + S + "ry", ChatText.toLegacy("x**b**y"));
        assertEquals(S + "ounder", ChatText.toLegacy("_under_")); // single underscore = italic
        assertEquals(S + "nunder", ChatText.toLegacy("__under__")); // double = underline
    }

    @Test
    void colorFirstThenStyleSurvives() {
        // gold + bold "b", then gold "c": the colour is re-emitted after the §r so it isn't lost
        assertEquals(S + "6" + S + "lb" + S + "r" + S + "6c", ChatText.toLegacy("{gold}**b**c"));
    }

    @Test
    void escapesAndUnknownTagsStayLiteral() {
        assertEquals("{green}", ChatText.toLegacy("\\{green\\}"));
        assertEquals("2 * 3", ChatText.toLegacy("2 \\* 3"));
        assertEquals("{unknown}", ChatText.toLegacy("{unknown}"));
    }

    @Test
    void rawLegacyCodesPassThrough() {
        assertEquals(S + "araw", ChatText.toLegacy(S + "araw"));
    }

    @Test
    void escapeLeavesPlainTextAlone() {
        assertEquals("Steve", ChatText.escape("Steve"));
        assertNull(ChatText.escape(null));
        assertEquals("", ChatText.escape(""));
    }

    @Test
    void escapedNameRendersVerbatim() {
        // A '_' in a name must not toggle italic once embedded in markup and rendered.
        assertEquals("Steve_123", ChatText.toLegacy("{aqua}" + ChatText.escape("Steve_123")).substring(2));
        // Colour-tag and raw § injection in a name are neutralised.
        assertEquals("{red}Admin", ChatText.toLegacy(ChatText.escape("{red}Admin")));
        assertEquals("4Admin", ChatText.toLegacy(ChatText.escape(S + "4Admin")));
        // Markdown-style characters stay literal too.
        assertEquals("a*b~~c", ChatText.toLegacy(ChatText.escape("a*b~~c")));
    }

    @Test
    void stripCodesDropsSectionAndControlChars() {
        assertEquals("Steve", ChatText.stripCodes("Steve"));
        assertNull(ChatText.stripCodes(null));
        // Raw legacy codes and control chars go; the surrounding text (and a legitimate '_') stays.
        assertEquals("4Admin", ChatText.stripCodes(S + "4Admin"));
        assertEquals("Steve_123", ChatText.stripCodes("Steve_123"));
        assertEquals("ab", ChatText.stripCodes("a\nb"));
        assertEquals("with space", ChatText.stripCodes("with space")); // real spaces survive
    }
}
