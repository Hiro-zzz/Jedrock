package com.jedrock.network.pe;

import com.jedrock.utils.ByteBufUtils;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the MCPE 1.1.5 {@code CommandStep} packet (0x4f) back into a plain {@code "/command args"}
 * line the core's command system can route.
 *
 * <p>A Bedrock client never sends a slash command as chat: with commands enabled it parses the line
 * itself and sends this structured packet; with commands disabled it refuses the input outright
 * ("cheats must be enabled"). So a server that wants in-game commands has to read this.
 *
 * <p>Wire layout (PocketMine-MP @ {@code 1.7dev-27}, {@code CURRENT_PROTOCOL = 113}):
 * <pre>
 *   string  command        (the name, no leading slash)
 *   string  overload
 *   uvarint (unused)
 *   uvarint currentStep
 *   bool    done
 *   uvarlong clientId
 *   string  inputJson      (the arguments, as a JSON object/array)
 *   string  outputJson     (ignored)
 *   ...     command origin data (ignored)
 * </pre>
 *
 * <p>PocketMine rebuilds the line as {@code command} followed by each <em>value</em> of the decoded
 * {@code inputJson}, space-separated — so {@code {"gameMode":"survival"}} becomes
 * {@code /gamemode survival}. Jedrock does the same, with a tiny value scanner instead of a JSON
 * dependency (the project keeps its dependency set minimal).
 */
final class McpeCommandStep {

    private McpeCommandStep() {}

    /**
     * Read a CommandStep body and rebuild the command line, e.g. {@code "/gamemode survival"}.
     *
     * @return the line, or {@code null} if the packet carries no command name
     */
    static String readCommandLine(ByteBuf pk) {
        String command = ByteBufUtils.readString(pk);
        if (command.isEmpty()) {
            return null;
        }
        ByteBufUtils.readString(pk);  // overload
        ByteBufUtils.readVarInt(pk);  // unused
        ByteBufUtils.readVarInt(pk);  // currentStep
        pk.readBoolean();             // done
        ByteBufUtils.readVarLong(pk); // clientId
        String inputJson = ByteBufUtils.readString(pk);

        StringBuilder line = new StringBuilder(command.length() + 16).append('/').append(command);
        for (String arg : jsonValues(inputJson)) {
            line.append(' ').append(arg);
        }
        return line.toString();
    }

    /**
     * The values of a flat JSON object or array, in order — the arguments the client packed into
     * {@code inputJson}. Keys are skipped; strings are unquoted, other scalars pass through verbatim.
     * Anything that isn't a JSON object/array (e.g. {@code null} or blank) yields no arguments.
     */
    static List<String> jsonValues(String json) {
        List<String> out = new ArrayList<>();
        if (json == null) {
            return out;
        }
        String s = json.trim();
        if (s.length() < 2) {
            return out;
        }
        char open = s.charAt(0);
        if (open != '{' && open != '[') {
            return out; // "null", a bare scalar, garbage — no arguments
        }
        boolean object = open == '{';
        int end = s.length() - 1; // index of the closing brace/bracket
        int i = 1;
        while (i < end) {
            while (i < end && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) {
                i++;
            }
            if (i >= end) {
                break;
            }
            if (object) {
                if (s.charAt(i) != '"') {
                    break; // malformed key — stop rather than emit noise
                }
                i = skipString(s, i);
                while (i < end && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
                if (i >= end || s.charAt(i) != ':') {
                    break;
                }
                i++; // ':'
                while (i < end && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
                if (i >= end) {
                    break;
                }
            }
            int start = i;
            i = skipValue(s, i, end);
            String raw = s.substring(start, Math.min(i, end)).trim();
            if (!raw.isEmpty()) {
                out.add(unquote(raw));
            }
        }
        return out;
    }

    /** Index just past the closing quote of the string starting at {@code i}. Honours backslash escapes. */
    private static int skipString(String s, int i) {
        i++; // opening quote
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            i++;
        }
        return i;
    }

    /** Index just past the value starting at {@code i} (string, nested container, or bare scalar). */
    private static int skipValue(String s, int i, int end) {
        char c = s.charAt(i);
        if (c == '"') {
            return skipString(s, i);
        }
        if (c == '{' || c == '[') {
            int depth = 0;
            while (i < s.length()) {
                char d = s.charAt(i);
                if (d == '"') {
                    i = skipString(s, i);
                    continue;
                }
                if (d == '{' || d == '[') {
                    depth++;
                } else if (d == '}' || d == ']') {
                    if (--depth == 0) {
                        return i + 1;
                    }
                }
                i++;
            }
            return i;
        }
        while (i < end && s.charAt(i) != ',') { // number / true / false / null
            i++;
        }
        return i;
    }

    /** Strip the quotes off a JSON string value and undo the two escapes that can appear in one. */
    private static String unquote(String raw) {
        if (raw.length() >= 2 && raw.charAt(0) == '"' && raw.charAt(raw.length() - 1) == '"') {
            return raw.substring(1, raw.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return raw;
    }
}
