package com.jedrock.api.item;

/**
 * A <b>custom item</b>: a name, some lore and a set of behaviours hung on an ordinary item state.
 *
 * <p>The illusionist take on items, and the only one this server can honestly offer. There is no resource
 * pack (that would break the promise that any unmodified client can join, and 0.14 barely supports one),
 * so a custom item is <em>rendered</em> as whatever vanilla item it is built on — a diamond sword stays a
 * diamond sword to the eye. What makes it custom is everything else: what it is called, what it says, and
 * what happens when it is used.
 *
 * <p>Identity is the <b>key</b>, a short stable string the script chooses ({@code "frostblade"}). A stack
 * carries that key, not a copy of this definition, which is what lets a custom item survive things a
 * reference could not: the world file loads long before any plugin is running, a hot reload replaces every
 * definition, and a script may be uninstalled entirely. An item whose key nothing currently defines simply
 * behaves as the vanilla item it is drawn as, and comes back to life the moment the script does.
 *
 * <p>Two stacks are the same item only if their state <em>and</em> their key match, so a custom sword never
 * silently stacks with an ordinary one.
 */
public interface CustomItem {

    /** The stable identity a stack carries — short, lower-case, chosen by whoever defined the item. */
    String getKey();

    /** The vanilla state {@code (id << 4) | meta} this item is drawn as. */
    int getState();

    /** The name shown in place of the vanilla one, in the unified markup. Never {@code null}. */
    String getDisplayName();

    /** The lines shown under the name, in the unified markup. Empty when there are none. */
    String[] getLore();
}
