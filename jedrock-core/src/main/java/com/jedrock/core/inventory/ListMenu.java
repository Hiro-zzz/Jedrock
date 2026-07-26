package com.jedrock.core.inventory;

import com.jedrock.core.player.CorePlayer;

import java.util.List;

/**
 * A button menu shown as a <b>list</b> rather than a chest window — the fallback for a client that can't
 * open one (the retail 1.1.5, which crashes on a chest window). Each labelled button becomes a pick-able
 * option; the player chooses one with {@code /pick <label>}, which fires the same {@link MenuClick} the
 * window path would.
 *
 * <p>The label is the option's user-facing name and how it's matched (case-insensitively). Only slots a
 * script gave a label to appear here — a plain decorative item has no place in a text list.
 */
public final class ListMenu {

    private final String title;
    private final List<String> labels; // display order
    private final int[] slots;         // menu slot per label
    private final int[] states;        // canonical item state per label (passed to onClick)
    private final MenuClick onClick;

    public ListMenu(String title, List<String> labels, int[] slots, int[] states, MenuClick onClick) {
        this.title = title;
        this.labels = List.copyOf(labels);
        this.slots = slots.clone();
        this.states = states.clone();
        this.onClick = onClick;
    }

    /** The menu's title, for the chat header. */
    public String title() {
        return title;
    }

    /** The option labels, in order — what {@code /pick} completes and lists. */
    public List<String> labels() {
        return labels;
    }

    /**
     * Choose the option named {@code label} (case-insensitive), firing the menu's click for its slot.
     *
     * @return {@code true} if a matching option was found and fired
     */
    public boolean pick(CorePlayer player, String label) {
        String want = label == null ? "" : label.trim();
        for (int i = 0; i < labels.size(); i++) {
            if (labels.get(i).equalsIgnoreCase(want)) {
                onClick.onClick(player, slots[i], states[i]);
                return true;
            }
        }
        return false;
    }
}
