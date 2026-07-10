package com.jedrock.utils.text;

/**
 * Escapes a string for embedding inside a JSON string literal — the one JSON concern Jedrock has, since
 * Java Edition carries chat / MOTD as a tiny hand-built {@code {"text":"…"}} blob (no JSON dependency).
 *
 * <p>Unlike a naive backslash + double-quote replace, this also escapes every ASCII control character
 * (U+0000–U+001F). That gap was a real cross-edition bug: a Bedrock client can put a raw control char
 * (e.g. a newline) into a chat line, which then reaches a Java recipient's chat JSON — and a single
 * unescaped control char makes the whole blob invalid, so the Java client rejects the message.
 */
public final class JsonText {

    private JsonText() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /**
     * Escape {@code s} for use between the quotes of a JSON string. Uses the short forms JSON defines
     * ({@code \" \\ \n \r \t \b \f}) and {@code \\u00XX} for any other control character. Allocates
     * nothing when nothing needs escaping (the common chat case, § colour codes included).
     * {@code null} in → {@code null} out.
     */
    public static String escape(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        StringBuilder sb = null; // built lazily — only if a char actually needs escaping
        int last = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String repl;
            switch (c) {
                case '"'  -> repl = "\\\"";
                case '\\' -> repl = "\\\\";
                case '\n' -> repl = "\\n";
                case '\r' -> repl = "\\r";
                case '\t' -> repl = "\\t";
                case '\b' -> repl = "\\b";
                case '\f' -> repl = "\\f";
                default -> {
                    if (c >= 0x20) {
                        continue; // ordinary character (incl. § and other printables) — leave as-is
                    }
                    // Any other control char: \\u00XX (c < 0x20, so the high byte is always 00).
                    repl = new String(new char[]{'\\', 'u', '0', '0', HEX[(c >> 4) & 0xF], HEX[c & 0xF]});
                }
            }
            if (sb == null) {
                sb = new StringBuilder(s.length() + 16);
            }
            sb.append(s, last, i).append(repl);
            last = i + 1;
        }
        if (sb == null) {
            return s; // fast path: no escaping needed, no allocation
        }
        sb.append(s, last, s.length());
        return sb.toString();
    }
}
