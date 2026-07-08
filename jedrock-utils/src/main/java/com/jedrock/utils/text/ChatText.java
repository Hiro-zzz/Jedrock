package com.jedrock.utils.text;

import java.util.HashMap;
import java.util.Map;

/**
 * Renders a unified, edition-agnostic chat markup into Minecraft's legacy {@code §} formatting codes.
 *
 * <p>Every version Jedrock speaks — Java 1.8 / 1.12.2 and Bedrock 0.14 / 1.1.5 — understands the legacy
 * {@code §} codes (Java applies them inside a {@code {"text":"…"}} component, Bedrock inside a raw
 * {@code TextPacket}), so a single rendered string reaches all editions identically. Authors write in
 * one friendly format instead of raw codes:
 *
 * <ul>
 *   <li><b>Colours</b> as {@code {name}} braces — the 16 Minecraft colours ({@code {red}}, {@code {gold}},
 *       {@code {dark_green}}, …, plus a few aliases like {@code {grey}}, {@code {pink}}) and {@code {reset}}.
 *       Hex colours are deliberately unsupported — the legacy editions can't render them.</li>
 *   <li><b>Styles</b> as Markdown: {@code **bold**}, {@code *italic*} / {@code _italic_},
 *       {@code __underline__}, {@code ~~strike~~}. Style names also work in braces
 *       ({@code {bold}}, {@code {italic}}, …).</li>
 *   <li>{@code \} escapes the next character (so a literal {@code *} or {@code &#123;} can be sent).</li>
 * </ul>
 *
 * <p>The renderer is stateful about colour vs. styles: a legacy colour code resets active styles on the
 * client, so it always emits the colour first and re-applies styles after, and inserts a {@code §r} only
 * when a style must actually be turned off — keeping the output correct on every edition. Any raw
 * {@code §} already in the input is passed through untouched, so existing messages keep working.
 */
public final class ChatText {

    public static final char SECTION = '§';

    private ChatText() {}

    // Style bits.
    private static final int BOLD = 1, ITALIC = 2, UNDERLINE = 4, STRIKE = 8, OBFUSCATED = 16;

    private static final Map<String, Character> COLORS = new HashMap<>();
    private static final Map<String, Integer> STYLES = new HashMap<>();

    static {
        color('0', "black");
        color('1', "dark_blue");
        color('2', "dark_green");
        color('3', "dark_aqua", "dark_cyan");
        color('4', "dark_red");
        color('5', "dark_purple", "dark_magenta");
        color('6', "gold", "orange");
        color('7', "gray", "grey");
        color('8', "dark_gray", "dark_grey");
        color('9', "blue");
        color('a', "green", "lime");
        color('b', "aqua", "cyan");
        color('c', "red");
        color('d', "light_purple", "pink", "magenta");
        color('e', "yellow");
        color('f', "white");
        style(BOLD, "bold", "b");
        style(ITALIC, "italic", "i", "em");
        style(UNDERLINE, "underline", "u");
        style(STRIKE, "strikethrough", "strike", "s");
        style(OBFUSCATED, "obfuscated", "magic");
    }

    private static void color(char code, String... names) {
        for (String n : names) COLORS.put(n, code);
    }

    private static void style(int bit, String... names) {
        for (String n : names) STYLES.put(n, bit);
    }

    /** Render unified markup to a legacy {@code §} string. {@code null} in → {@code null} out. */
    public static String toLegacy(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        int n = input.length();
        Renderer r = new Renderer(n);
        StringBuilder run = new StringBuilder();

        char color = 0;      // desired colour ('0'..'f'), 0 = none
        int styles = 0;      // desired style bitmask

        int i = 0;
        while (i < n) {
            char c = input.charAt(i);

            if (c == '\\' && i + 1 < n) {                 // escape next char
                run.append(input.charAt(i + 1));
                i += 2;
                continue;
            }

            // Detect a formatting token; if found, flush the pending run under the current state first.
            if (c == '{') {
                int close = input.indexOf('}', i + 1);
                if (close > i) {
                    String tag = input.substring(i + 1, close).trim().toLowerCase();
                    Character col = COLORS.get(tag);
                    Integer sty = STYLES.get(tag);
                    if (tag.equals("reset") || tag.equals("r")) {
                        r.emit(run, color, styles);
                        color = 0; styles = 0;
                        i = close + 1;
                        continue;
                    } else if (col != null) {
                        r.emit(run, color, styles);
                        color = col;
                        i = close + 1;
                        continue;
                    } else if (sty != null) {
                        r.emit(run, color, styles);
                        styles |= sty;
                        i = close + 1;
                        continue;
                    }
                }
                run.append(c);
                i++;
                continue;
            }

            int toggle = 0, consumed = 0;
            if (c == '*') {
                if (i + 1 < n && input.charAt(i + 1) == '*') { toggle = BOLD; consumed = 2; }
                else { toggle = ITALIC; consumed = 1; }
            } else if (c == '_') {
                if (i + 1 < n && input.charAt(i + 1) == '_') { toggle = UNDERLINE; consumed = 2; }
                else { toggle = ITALIC; consumed = 1; }
            } else if (c == '~' && i + 1 < n && input.charAt(i + 1) == '~') {
                toggle = STRIKE; consumed = 2;
            }

            if (toggle != 0) {
                r.emit(run, color, styles);
                styles ^= toggle;
                i += consumed;
            } else {
                run.append(c);
                i++;
            }
        }
        r.emit(run, color, styles);
        return r.toString();
    }

    /** Tracks what formatting is actually on the wire, emitting the minimal codes to reach the target. */
    private static final class Renderer {
        private final StringBuilder out;
        private char appliedColor = 0;
        private int appliedStyles = 0;
        private boolean anyApplied = false;

        Renderer(int hint) {
            this.out = new StringBuilder(hint + 16);
        }

        /** Emit {@code run} (if any) formatted as ({@code color}, {@code styles}). */
        void emit(StringBuilder run, char color, int styles) {
            if (run.length() == 0) {
                return;
            }
            boolean colorChanged = color != appliedColor;
            boolean styleRemoved = (appliedStyles & ~styles) != 0;

            if (colorChanged || styleRemoved) {
                // A colour code clears styles on the client; add an explicit §r when we must drop a
                // style without a colour to carry the reset.
                if (color == 0) {
                    if (anyApplied) out.append(SECTION).append('r');
                } else {
                    if (anyApplied) out.append(SECTION).append('r');
                    out.append(SECTION).append(color);
                }
                appendStyles(styles);
                appliedColor = color;
                appliedStyles = styles;
            } else {
                appendStyles(styles & ~appliedStyles); // only newly-added styles
                appliedStyles = styles;
            }
            anyApplied = appliedColor != 0 || appliedStyles != 0;

            out.append(run);
            run.setLength(0);
        }

        private void appendStyles(int styles) {
            if ((styles & BOLD) != 0) out.append(SECTION).append('l');
            if ((styles & ITALIC) != 0) out.append(SECTION).append('o');
            if ((styles & UNDERLINE) != 0) out.append(SECTION).append('n');
            if ((styles & STRIKE) != 0) out.append(SECTION).append('m');
            if ((styles & OBFUSCATED) != 0) out.append(SECTION).append('k');
        }

        @Override
        public String toString() {
            return out.toString();
        }
    }
}
