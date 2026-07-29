package com.jedrock.utils.yaml;

import com.jedrock.utils.JLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A reader for the part of YAML a config file actually uses, and nothing else.
 *
 * <p>This exists instead of a dependency because of what it is for: one file, written by us, edited by an
 * operator, read once at startup. SnakeYAML would bring a general document model, tags, anchors, aliases
 * and a code-execution surface to read four nested maps of numbers — against a codebase whose stated rule
 * is few dependencies. So the subset is drawn deliberately tight:
 *
 * <ul>
 *   <li><b>Mappings</b> nested by indentation ({@code key: value}, or {@code key:} followed by an indented
 *       block). Indentation may be any consistent number of spaces; a tab is refused, because a file where
 *       tabs and spaces mix is a file whose shape depends on your editor.</li>
 *   <li><b>Sequences</b> of scalars, block style ({@code - item}) or inline ({@code [a, b, c]}).</li>
 *   <li><b>Scalars</b>: {@code true}/{@code false}, integers, decimals, {@code null}/{@code ~}, and text —
 *       quoted with {@code '} or {@code "} when it would otherwise read as one of those.</li>
 *   <li><b>Comments</b>: {@code #} to end of line, unless it is inside quotes.</li>
 * </ul>
 *
 * <p>Everything else — anchors, aliases, tags, multiple documents, block scalars, complex keys, nested
 * collections inside sequences — is <em>not</em> supported, and a line using one is skipped with a warning
 * naming the line. A config reader's job is to end up with settings; refusing to start because line 40 is
 * strange would be the wrong trade for a file whose every key already has a default.
 *
 * <p>Reading is by dotted path ({@code get("bedrock.v1_1_5.max-view-radius", 4)}) and always takes a
 * default: a missing key, a key of the wrong shape and a malformed file all end the same way — the built-in
 * value, and a warning if the operator wrote something that looks like an attempt.
 */
public final class Yaml {

    private static final JLogger LOGGER = JLogger.getLogger("Yaml");

    private Yaml() {}

    /** Read a file. A missing file is not an error here — it yields an empty section (all defaults). */
    public static Section load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new Section(Map.of(), file.getFileName().toString());
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.getFileName().toString());
    }

    /** Parse text that came from somewhere other than a file (a test, a resource, a string). */
    public static Section parse(String text, String source) {
        List<Line> lines = new ArrayList<>();
        int number = 0;
        for (String raw : text.split("\r?\n", -1)) {
            number++;
            String stripped = stripComment(raw);
            if (stripped.isBlank()) {
                continue;
            }
            int indent = indentOf(stripped);
            if (indent < 0) {
                LOGGER.warn(source + ":" + number + " is indented with a tab; the line is ignored");
                continue;
            }
            lines.add(new Line(indent, stripped.strip(), number));
        }
        int[] cursor = {0};
        Object root = lines.isEmpty() ? Map.of() : parseBlock(lines, cursor, lines.get(0).indent, source);
        if (root instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return new Section(typed, source);
        }
        LOGGER.warn(source + " does not start with a mapping; every setting falls back to its default");
        return new Section(Map.of(), source);
    }

    // ===== Parsing =====

    private record Line(int indent, String text, int number) {}

    /** A mapping or a sequence, whichever the first line at this indent turns out to be. */
    private static Object parseBlock(List<Line> lines, int[] cursor, int indent, String source) {
        if (cursor[0] < lines.size() && lines.get(cursor[0]).text.startsWith("- ")) {
            return parseSequence(lines, cursor, indent, source);
        }
        return parseMapping(lines, cursor, indent, source);
    }

    private static Map<String, Object> parseMapping(List<Line> lines, int[] cursor, int indent, String source) {
        Map<String, Object> map = new LinkedHashMap<>();
        while (cursor[0] < lines.size()) {
            Line line = lines.get(cursor[0]);
            if (line.indent < indent) {
                break; // the block we belong to has ended
            }
            if (line.indent > indent) {
                LOGGER.warn(source + ":" + line.number + " is indented under a value that isn't a block; ignored");
                cursor[0]++;
                continue;
            }
            int colon = colonAt(line.text);
            if (colon < 0) {
                LOGGER.warn(source + ":" + line.number + " is not 'key: value' and isn't understood; ignored");
                cursor[0]++;
                continue;
            }
            String key = unquote(line.text.substring(0, colon).strip());
            String rest = line.text.substring(colon + 1).strip();
            cursor[0]++;
            if (!rest.isEmpty()) {
                map.put(key, scalarOrInlineList(rest));
                continue;
            }
            // 'key:' with nothing after it — an indented block, or an empty value at the end of the file.
            if (cursor[0] < lines.size() && lines.get(cursor[0]).indent > indent) {
                map.put(key, parseBlock(lines, cursor, lines.get(cursor[0]).indent, source));
            } else {
                map.put(key, null);
            }
        }
        return map;
    }

    private static List<Object> parseSequence(List<Line> lines, int[] cursor, int indent, String source) {
        List<Object> list = new ArrayList<>();
        while (cursor[0] < lines.size()) {
            Line line = lines.get(cursor[0]);
            if (line.indent < indent || !line.text.startsWith("- ")) {
                break;
            }
            if (line.indent > indent) {
                LOGGER.warn(source + ":" + line.number + " is a nested sequence, which this reader "
                        + "doesn't support; ignored");
                cursor[0]++;
                continue;
            }
            list.add(scalar(line.text.substring(2).strip()));
            cursor[0]++;
        }
        return list;
    }

    /** The first colon that ends a key — one inside quotes belongs to the key, not to the syntax. */
    private static int colonAt(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ':' && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    /** Cut a trailing {@code #} comment, leaving one that is inside quotes alone. */
    private static String stripComment(String raw) {
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '#' && (i == 0 || raw.charAt(i - 1) == ' ' || raw.charAt(i - 1) == '\t')) {
                return raw.substring(0, i);
            }
        }
        return raw;
    }

    /** Leading spaces, or {@code -1} if the line is indented with a tab. */
    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i < line.length() && line.charAt(i) == '\t' ? -1 : i;
    }

    private static Object scalarOrInlineList(String text) {
        if (text.length() >= 2 && text.charAt(0) == '[' && text.endsWith("]")) {
            List<Object> list = new ArrayList<>();
            String body = text.substring(1, text.length() - 1).strip();
            if (!body.isEmpty()) {
                for (String item : body.split(",")) {
                    list.add(scalar(item.strip()));
                }
            }
            return list;
        }
        return scalar(text);
    }

    private static Object scalar(String text) {
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() >= 2 && (text.charAt(0) == '\'' || text.charAt(0) == '"')
                && text.charAt(text.length() - 1) == text.charAt(0)) {
            return text.substring(1, text.length() - 1); // quoted: always text, never a number
        }
        String lower = text.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "true", "yes", "on" -> { return Boolean.TRUE; }
            case "false", "no", "off" -> { return Boolean.FALSE; }
            case "null", "~" -> { return null; }
            default -> { }
        }
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException ignored) {
            // not an integer
        }
        try {
            return Double.valueOf(text);
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && (text.charAt(0) == '\'' || text.charAt(0) == '"')
                && text.charAt(text.length() - 1) == text.charAt(0)) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    // ===== Reading =====

    /**
     * A parsed mapping, read by dotted path. Every getter takes a default and returns it for a missing key,
     * a null value or a value of the wrong shape — with a warning in the last case, since that one is
     * someone having tried and got it wrong, not someone having left it out.
     */
    public static final class Section {

        private final Map<String, Object> map;
        private final String source;

        Section(Map<String, Object> map, String source) {
            this.map = map;
            this.source = source;
        }

        /** Whether the file had anything in it at all — a fresh install has no pipeline file yet. */
        public boolean isEmpty() {
            return map.isEmpty();
        }

        /** The keys directly under this section, in file order. */
        public Set<String> keys() {
            return Collections.unmodifiableSet(map.keySet());
        }

        /** A nested mapping by dotted path; never null — a missing one is an empty section. */
        public Section section(String path) {
            Object value = lookup(path);
            if (value instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) nested;
                return new Section(typed, source);
            }
            return new Section(Map.of(), source);
        }

        public boolean has(String path) {
            return lookup(path) != null;
        }

        public String getString(String path, String def) {
            Object value = lookup(path);
            return value == null ? def : String.valueOf(value);
        }

        public boolean getBool(String path, boolean def) {
            Object value = lookup(path);
            if (value == null) {
                return def;
            }
            if (value instanceof Boolean b) {
                return b;
            }
            warnType(path, value, "true or false", def);
            return def;
        }

        public long getLong(String path, long def) {
            Object value = lookup(path);
            if (value == null) {
                return def;
            }
            if (value instanceof Number n) {
                return n.longValue();
            }
            warnType(path, value, "a whole number", def);
            return def;
        }

        /** A whole number, clamped into {@code [min, max]} — the shape most pipeline knobs have. */
        public int getInt(String path, int def, int min, int max) {
            long value = getLong(path, def);
            if (value < min || value > max) {
                LOGGER.warn(source + ": " + path + " must be between " + min + " and " + max
                        + " (got " + value + "); using " + def);
                return def;
            }
            return (int) value;
        }

        public int getInt(String path, int def) {
            return getInt(path, def, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        public double getDouble(String path, double def) {
            Object value = lookup(path);
            if (value == null) {
                return def;
            }
            if (value instanceof Number n) {
                return n.doubleValue();
            }
            warnType(path, value, "a number", def);
            return def;
        }

        /** A sequence of scalars as text; an empty list for a missing key. */
        public List<String> getList(String path) {
            Object value = lookup(path);
            if (value instanceof List<?> list) {
                List<String> out = new ArrayList<>(list.size());
                for (Object item : list) {
                    out.add(String.valueOf(item));
                }
                return out;
            }
            return List.of();
        }

        private Object lookup(String path) {
            Map<String, Object> current = map;
            int from = 0;
            while (true) {
                int dot = path.indexOf('.', from);
                if (dot < 0) {
                    return current.get(path.substring(from));
                }
                Object next = current.get(path.substring(from, dot));
                if (!(next instanceof Map<?, ?> nested)) {
                    return null;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) nested;
                current = typed;
                from = dot + 1;
            }
        }

        private void warnType(String path, Object value, String expected, Object def) {
            LOGGER.warn(source + ": " + path + " should be " + expected + " but is '" + value
                    + "'; using " + def);
        }
    }
}
