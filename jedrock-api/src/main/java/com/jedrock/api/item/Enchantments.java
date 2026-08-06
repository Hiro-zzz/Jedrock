package com.jedrock.api.item;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * What one stack is enchanted with — an immutable {@link Enchantment} → level map, small by nature.
 *
 * <p><b>Immutable and compared by value</b>, which is the load-bearing part rather than a stylistic
 * preference: a container merges two stacks only when everything about their identity matches, so
 * enchantments join the custom key and the per-stack data in that test. Without value equality a sharpness
 * sword would quietly dissolve into a plain one the moment they met in a slot.
 *
 * <p>{@link #NONE} is the empty set and the common case; a plain stack holds exactly it and costs nothing.
 */
public final class Enchantments {

    /** No enchantments — what almost every stack in the world holds. */
    public static final Enchantments NONE = new Enchantments(new EnumMap<>(Enchantment.class));

    /** Vanilla's own ceiling for a legal level; higher reads as gibberish on a client rather than failing. */
    public static final int MAX_LEVEL = 255;

    private final Map<Enchantment, Integer> levels;

    private Enchantments(Map<Enchantment, Integer> levels) {
        this.levels = levels;
    }

    /** One enchantment at one level. */
    public static Enchantments of(Enchantment enchantment, int level) {
        return NONE.with(enchantment, level);
    }

    /**
     * This set plus {@code enchantment} at {@code level}, replacing any level it already had. A level of
     * zero or less removes it, which is how "take it off" is spelled everywhere else here too.
     */
    public Enchantments with(Enchantment enchantment, int level) {
        if (enchantment == null) {
            return this;
        }
        if (level <= 0) {
            return without(enchantment);
        }
        EnumMap<Enchantment, Integer> copy = new EnumMap<>(levels);
        copy.put(enchantment, Math.min(MAX_LEVEL, level));
        return new Enchantments(copy);
    }

    /** This set without {@code enchantment}. */
    public Enchantments without(Enchantment enchantment) {
        if (enchantment == null || !levels.containsKey(enchantment)) {
            return this;
        }
        EnumMap<Enchantment, Integer> copy = new EnumMap<>(levels);
        copy.remove(enchantment);
        return copy.isEmpty() ? NONE : new Enchantments(copy);
    }

    /** The level of one enchantment, or {@code 0} if the stack doesn't carry it. */
    public int level(Enchantment enchantment) {
        Integer level = levels.get(enchantment);
        return level == null ? 0 : level;
    }

    public boolean has(Enchantment enchantment) {
        return levels.containsKey(enchantment);
    }

    public boolean isEmpty() {
        return levels.isEmpty();
    }

    public int size() {
        return levels.size();
    }

    /** Every enchantment and its level, in the enum's own order. Unmodifiable. */
    public Map<Enchantment, Integer> asMap() {
        return Collections.unmodifiableMap(levels);
    }

    /**
     * Parse the compact form used on the wire-adjacent surfaces that need a string — the level file's
     * neighbours, a script's shorthand: {@code "sharpness:3,unbreaking:1"}. Unknown names are skipped
     * rather than refused, since this also has to read back what an older build wrote.
     */
    public static Enchantments parse(String text) {
        if (text == null || text.isBlank()) {
            return NONE;
        }
        Enchantments result = NONE;
        for (String part : text.split(",")) {
            int colon = part.indexOf(':');
            if (colon < 0) {
                continue;
            }
            Enchantment enchantment = Enchantment.fromString(part.substring(0, colon));
            if (enchantment == null) {
                continue;
            }
            try {
                result = result.with(enchantment, Integer.parseInt(part.substring(colon + 1).trim()));
            } catch (NumberFormatException ignored) {
                // a level that isn't a number names nothing; skip it rather than lose the whole stack
            }
        }
        return result;
    }

    /** The inverse of {@link #parse}: {@code "sharpness:3,unbreaking:1"}, empty for none. */
    public String toCompactString() {
        if (levels.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(32);
        for (Map.Entry<Enchantment, Integer> e : levels.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey().getKey().toLowerCase(Locale.ROOT)).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Enchantments e && levels.equals(e.levels);
    }

    @Override
    public int hashCode() {
        return levels.hashCode();
    }

    @Override
    public String toString() {
        return levels.isEmpty() ? "none" : toCompactString();
    }
}
