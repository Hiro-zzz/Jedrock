package com.jedrock.api.world;

import java.util.Objects;

/**
 * A named recipe for a world: what kind it is, how big, whether it gets decorated, and — optionally —
 * the exact seed it is grown from. Creating a world names a template; the template supplies everything
 * the generator needs.
 *
 * <p>A template is <em>not</em> a saved world. It carries no blocks: two worlds built from the same
 * template with different seeds share nothing but their shape rules. That is the point — a template is
 * cheap to declare (a script can register one at load time) and a world built from one is baked fresh.
 *
 * @param name       the template's own name, how {@code createWorld} refers to it
 * @param dimension  {@link Dimension#OVERWORLD} or {@link Dimension#NETHER} — the two kinds of world
 *                   Jedrock generates
 * @param sizeChunks the finite world's extent, in chunks per side, centred on the origin
 * @param decorate   whether the bake runs its decoration passes (trees / lakes / caves, or glowstone /
 *                   soul sand / ore); {@code false} leaves bare terrain, which is what a build server
 *                   usually wants
 * @param seed       a fixed seed every world from this template shares, or {@code null} for "each world
 *                   gets its own", which is what makes a template reusable rather than a clone
 */
public record WorldTemplate(String name, Dimension dimension, int sizeChunks, boolean decorate, Long seed) {

    /** The largest world a template may ask for, in chunks per side. */
    public static final int MAX_SIZE_CHUNKS = 96;

    public WorldTemplate {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimension, "dimension");
        if (name.isBlank()) {
            throw new IllegalArgumentException("template name must not be blank");
        }
        if (dimension == Dimension.END) {
            throw new IllegalArgumentException(
                    "The End is not a world type Jedrock generates — use OVERWORLD or NETHER");
        }
        // A bake is one-time but not free: it is O(size²) sections in memory, and an unbounded number
        // here is how a script turns "create a world" into an out-of-memory error on a live server.
        if (sizeChunks < 2 || sizeChunks > MAX_SIZE_CHUNKS) {
            throw new IllegalArgumentException(
                    "world size must be 2.." + MAX_SIZE_CHUNKS + " chunks per side, got " + sizeChunks);
        }
    }

    /** The default overworld: 48×48 chunks, decorated, a fresh seed each time. */
    public static WorldTemplate overworld() {
        return new WorldTemplate("overworld", Dimension.OVERWORLD, 48, true, null);
    }

    /** The default nether: 48×48 chunks, decorated, a fresh seed each time. */
    public static WorldTemplate nether() {
        return new WorldTemplate("nether", Dimension.NETHER, 48, true, null);
    }

    /** A small overworld — 16×16 chunks, baked in a moment. Handy for an arena or a lobby. */
    public static WorldTemplate smallOverworld() {
        return new WorldTemplate("overworld_small", Dimension.OVERWORLD, 16, true, null);
    }

    /** A small nether, on the same reasoning as {@link #smallOverworld()}. */
    public static WorldTemplate smallNether() {
        return new WorldTemplate("nether_small", Dimension.NETHER, 16, true, null);
    }

    /** Bare overworld terrain, no trees / lakes / caves — an empty canvas to build on. */
    public static WorldTemplate flatland() {
        return new WorldTemplate("bare", Dimension.OVERWORLD, 32, false, null);
    }

    /** A copy of this template under a new name — the usual way to derive one from a built-in. */
    public WorldTemplate named(String newName) {
        return new WorldTemplate(newName, dimension, sizeChunks, decorate, seed);
    }

    /** A copy that always grows from {@code fixedSeed}, so every world built from it is identical. */
    public WorldTemplate withSeed(long fixedSeed) {
        return new WorldTemplate(name, dimension, sizeChunks, decorate, fixedSeed);
    }

    /** A copy of a different size. */
    public WorldTemplate withSize(int chunksPerSide) {
        return new WorldTemplate(name, dimension, chunksPerSide, decorate, seed);
    }

    /** A copy with decoration turned on or off. */
    public WorldTemplate withDecoration(boolean decorated) {
        return new WorldTemplate(name, dimension, sizeChunks, decorated, seed);
    }
}
