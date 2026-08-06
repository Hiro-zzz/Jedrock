package com.jedrock.api.item;

/**
 * How one stack should <em>look</em> — the name shown in place of the vanilla one, the lines under it, and
 * what it is enchanted with.
 *
 * <p>The only part of a stack the client ever learns. Everything else about a
 * {@linkplain CustomItem custom item} — its key, its behaviour — is the server's business, so this is
 * deliberately the whole of what crosses the network layer: a name and some lore, already rendered to the
 * legacy {@code §} codes every target version understands, plus the enchantments the client draws the
 * glint and the tooltip from.
 *
 * <p>Enchantments live here rather than in a parallel array beside every {@code ItemDisplay[]} because
 * they are the same kind of fact — something true of one stack that a client has to be told. Threading
 * them through the display means every slot-writing path already carries them.
 *
 * <p>{@code null} anywhere a display is expected means "an ordinary item": no NBT is written for it, which
 * is exactly the wire the server sent before custom items existed.
 *
 * @param name the display name, legacy-rendered; empty when the stack keeps its vanilla one
 * @param lore the lines under it, legacy-rendered; empty when there are none, never {@code null}
 * @param enchantments what it is enchanted with; {@link Enchantments#NONE} for a plain stack
 */
public record ItemDisplay(String name, String[] lore, Enchantments enchantments) {

    private static final String[] NO_LORE = new String[0];

    public ItemDisplay {
        name = name == null ? "" : name;
        lore = lore == null ? NO_LORE : lore.clone();
        enchantments = enchantments == null ? Enchantments.NONE : enchantments;
    }

    /** A display that is only a name and lore — what a custom item's definition alone can say. */
    public ItemDisplay(String name, String[] lore) {
        this(name, lore, Enchantments.NONE);
    }

    /** A display with a name and no lore. */
    public static ItemDisplay of(String name) {
        return new ItemDisplay(name, NO_LORE, Enchantments.NONE);
    }

    /** A display that is only enchantments — an ordinary item that happens to be enchanted. */
    public static ItemDisplay enchanted(Enchantments enchantments) {
        return new ItemDisplay("", NO_LORE, enchantments);
    }

    /** This display with {@code enchantments} instead of its own — how a stack's own set is layered on. */
    public ItemDisplay withEnchantments(Enchantments enchantments) {
        return new ItemDisplay(name, lore, enchantments);
    }

    /** True when there is nothing worth writing — no name, no lore and no enchantments. */
    public boolean isEmpty() {
        return name.isEmpty() && lore.length == 0 && enchantments.isEmpty();
    }

    @Override
    public String[] lore() {
        return lore.clone();
    }
}
