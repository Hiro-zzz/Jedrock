package com.jedrock.network.pe.v014;

import com.jedrock.api.world.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * The <b>item</b> (id &gt; 255) half of the 0.14 creative menu — weapons, tools, armor, food and
 * materials. Exactly the 115 item entries of PocketMine-MP's own 0.14 creative list
 * ({@code resources/creativeitems.json} in the 0.14 tree at {@code e11b76318}), minus one: the spawn
 * egg ({@code 383:17}), whose meta doesn't fit the canonical 4-bit {@code (id << 4) | meta} state the
 * whole pipeline speaks. Battle-tested against the exact client generation that crashes on an unknown
 * id — which is also why this list is separate from the richer 1.1.5 one
 * ({@code PeCreativePalette.items()} carries ender pearls, blaze rods… that 0.14 predates).
 *
 * <p>Items are <b>inert</b> in Jedrock's illusionist model: held, stacked, moved, chest-stored — but
 * no durability, crafting or eating, and the block-placement paths refuse an item id, so a
 * "placeable" item (door, bed, sign) simply doesn't place. {@link #supports} is the safety gate for
 * anything item-shaped sent to a 0.14 client (inventory, chests): an id outside this set becomes air
 * rather than a crash.
 */
public final class Pe014Items {

    private Pe014Items() {}

    /** Item ids run to 458 (beetroot seeds); anything beyond the table is filtered to air for 0.14. */
    private static final boolean[] SUPPORTED = new boolean[512];
    private static final int[] ITEMS = build();

    static {
        for (int state : ITEMS) {
            SUPPORTED[Blocks.idOf(state)] = true;
        }
    }

    /** Whether this item id is in the 0.14-safe classic set. */
    public static boolean supports(int id) {
        return id >= 0 && id < SUPPORTED.length && SUPPORTED[id];
    }

    /** The 0.14 creative-menu item states (canonical {@code (id << 4) | meta}). Returns a private copy. */
    public static int[] creativeItems() {
        return ITEMS.clone();
    }

    private static int[] build() {
        // PMMP's 0.14 creativeitems.json order: placeables → utility → tools/weapons → armor →
        // materials → food. The spawn egg (383:17) is skipped — meta 17 overflows the 4-bit state.
        List<Integer> s = new ArrayList<>(120);
        plain(s, 324, 330, 355, 321, 379, 354, 323, 390); // doors, bed, painting, brewing stand, cake, sign, flower pot
        metas(s, 325, 10);                                // water bucket (the one bucket PMMP ships)
        plain(s, 331, 261, 346, 259, 359, 347, 345, 328); // redstone, bow, rod, flint&steel, shears, clock, compass, minecart
        plain(s, 268, 290, 269, 270, 271);                // wooden sword / hoe / shovel / pickaxe / axe
        plain(s, 272, 291, 273, 274, 275);                // stone tier
        plain(s, 267, 292, 256, 257, 258);                // iron tier
        plain(s, 276, 293, 277, 278, 279);                // diamond tier
        plain(s, 283, 294, 284, 285, 286);                // gold tier
        plain(s, 298, 299, 300, 301);                     // leather armor
        plain(s, 302, 303, 304, 305);                     // chain armor
        plain(s, 306, 307, 308, 309);                     // iron armor
        plain(s, 310, 311, 312, 313);                     // diamond armor
        plain(s, 314, 315, 316, 317);                     // gold armor
        plain(s, 332);                                    // snowball
        metas(s, 263, 0, 1);                              // coal, charcoal
        plain(s, 264, 265, 266, 388);                     // diamond, iron ingot, gold ingot, emerald
        plain(s, 280, 281, 287, 288, 318, 334, 337, 353); // stick, bowl, string, feather, flint, leather, clay, sugar
        plain(s, 406, 339, 340, 262, 352, 338);           // quartz, paper, book, arrow, bone, sugar cane
        plain(s, 296, 295, 361, 362, 458);                // wheat + the four seed kinds
        plain(s, 344, 260, 322);                          // egg, apple, golden apple
        metas(s, 349, 0, 1, 2, 3);                        // raw fish / salmon / clownfish / pufferfish
        metas(s, 350, 0, 1);                              // cooked fish / salmon
        plain(s, 297, 319, 320, 365, 366, 363, 364);      // bread, porkchops, chickens, beef, steak
        plain(s, 360, 391, 392, 393, 357, 400);           // melon, carrot, potato, baked potato, cookie, pie
        plain(s, 371, 341, 289, 348);                     // gold nugget, slimeball, gunpowder, glowstone dust
        metas(s, 351, 8);                                 // the dye PMMP ships (meta 8)

        int[] out = new int[s.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = s.get(i);
        }
        return out;
    }

    private static void plain(List<Integer> s, int... ids) {
        for (int id : ids) {
            s.add(Blocks.state(id, 0));
        }
    }

    private static void metas(List<Integer> s, int id, int... metas) {
        for (int meta : metas) {
            s.add(Blocks.state(id, meta));
        }
    }
}
