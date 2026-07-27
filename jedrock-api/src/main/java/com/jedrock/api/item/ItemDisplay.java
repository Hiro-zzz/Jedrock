package com.jedrock.api.item;

/**
 * How one stack should <em>look</em> — the name shown in place of the vanilla one, and the lines under it.
 *
 * <p>The only part of a {@linkplain CustomItem custom item} the client ever learns. Everything else about
 * it — its key, its behaviour — is the server's business, so this is deliberately the whole of what crosses
 * the network layer: a name and some lore, already rendered to the legacy {@code §} codes every target
 * version understands.
 *
 * <p>{@code null} anywhere a display is expected means "an ordinary item": no NBT is written for it, which
 * is exactly the wire the server sent before custom items existed.
 *
 * @param name the display name, legacy-rendered; never {@code null} or empty in a present display
 * @param lore the lines under it, legacy-rendered; empty when there are none, never {@code null}
 */
public record ItemDisplay(String name, String[] lore) {

    private static final String[] NO_LORE = new String[0];

    public ItemDisplay {
        name = name == null ? "" : name;
        lore = lore == null ? NO_LORE : lore.clone();
    }

    /** A display with a name and no lore. */
    public static ItemDisplay of(String name) {
        return new ItemDisplay(name, NO_LORE);
    }

    /** True when there is nothing worth writing — an empty name and no lore. */
    public boolean isEmpty() {
        return name.isEmpty() && lore.length == 0;
    }

    @Override
    public String[] lore() {
        return lore.clone();
    }
}
