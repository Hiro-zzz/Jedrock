package com.jedrock.network.pe;

import com.jedrock.api.world.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * The shared creative-menu block palette for both Bedrock editions (1.1.5 and 0.14). Entries are
 * canonical {@code (id << 4) | meta} states, so the menu now carries per-meta variants — every wool /
 * terracotta / carpet colour, each wood and stone type — not just one item per block id. The world
 * stores the full state and both editions serialize the meta nibble, so a variant placed from here
 * round-trips and renders distinctly cross-edition.
 *
 * <p>Only ids/metas shared by the legacy Java and Bedrock numbering (the classic pre-flattening set)
 * are used, and liquids / air / technical multi-block ids (doors, beds, wire, pistons heads, portals,
 * fire) are left out — they don't render as a static full block. A handful of the newer ids may show
 * as a placeholder on the very old 0.14 client, which is harmless.
 */
public final class PeCreativePalette {

    private PeCreativePalette() {}

    private static final int[] STATES = build();

    /**
     * Creative-menu states for the modern PE edition (1.1.5 / protocol 113), which renders any legacy
     * id/meta. Returns a private copy (callers may keep/iterate it). (MCPE 0.14 uses its own, far
     * narrower {@code Pe014Blocks} palette — it crashes on an id it can't render.)
     */
    public static int[] states() {
        return STATES.clone();
    }

    private static int[] build() {
        List<Integer> s = new ArrayList<>(220);

        // Stone, brick & manufactured building blocks (with variants)
        metas(s, 1, 0, 1, 2, 3, 4, 5, 6);         // stone, granite/diorite/andesite (+ polished)
        plain(s, 4, 48);                          // cobblestone, mossy cobblestone
        metas(s, 98, 0, 1, 2, 3);                 // stone brick: normal / mossy / cracked / chiseled
        plain(s, 45);                             // brick block
        metas(s, 24, 0, 1, 2);                    // sandstone: normal / chiseled / smooth
        metas(s, 155, 0, 1, 2);                   // quartz block: normal / chiseled / pillar
        plain(s, 168, 169, 172, 173, 174, 112);   // prismarine, sea lantern, hardened clay, coal, packed ice, nether brick

        // Wood
        metas(s, 17, 0, 1, 2, 3);                 // logs: oak / spruce / birch / jungle
        metas(s, 162, 0, 1);                      // logs2: acacia / dark oak
        metas(s, 5, 0, 1, 2, 3, 4, 5);            // planks (all six)
        metas(s, 18, 0, 1, 2, 3);                 // leaves
        metas(s, 161, 0, 1);                      // leaves2
        plain(s, 47);                             // bookshelf

        // Natural ground
        plain(s, 2);                              // grass
        metas(s, 3, 0, 1, 2);                     // dirt / coarse dirt / podzol
        plain(s, 60, 110, 13, 82, 87, 88, 121);   // farmland, mycelium, gravel, clay, netherrack, soul sand, end stone
        metas(s, 12, 0, 1);                       // sand / red sand

        // Ice / snow / desert
        plain(s, 79, 80, 78, 81);                 // ice, snow block, snow layer, cactus

        // Foliage / plant blocks
        plain(s, 106, 111, 86, 91, 103);          // vines, lily pad, pumpkin, jack-o-lantern, melon

        // Ores
        plain(s, 14, 15, 16, 21, 56, 73, 129, 153);

        // Metal / mineral / misc solid blocks
        plain(s, 41, 42, 57, 133, 22, 152, 89, 49, 19, 30);

        // Glass & panes
        plain(s, 20, 102, 101);

        // Wool / terracotta / carpet — the full colour range
        metas(s, 35, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        metas(s, 159, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        metas(s, 171, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15);

        // Utility & interactive blocks
        plain(s, 54, 58, 61, 84, 25, 46, 116, 145, 130, 138, 23, 158, 137, 165, 170, 52);

        // Stairs
        plain(s, 53, 67, 108, 109, 114, 128, 156, 134, 135, 136, 163, 164);

        // Slabs / walls / fences
        plain(s, 44, 126, 139, 85, 107);

        // Redstone-ish blocks
        plain(s, 29, 33, 123);

        // More building & decorative blocks (ids shared by legacy Java + Bedrock, not already above)
        plain(s, 7, 99, 100);                     // bedrock, brown / red mushroom block
        metas(s, 179, 0, 1, 2);                   // red sandstone: normal / chiseled / smooth

        return toArray(s);
    }

    /** Built once: the 1.1.5 item palette (id ≥ 256). */
    private static final int[] ITEMS = buildItems();

    /**
     * Creative-menu <b>item</b> states for 1.1.5 (id ≥ 256 — tools, armor, food, materials). The 1.1.5
     * client renders these legacy Bedrock item ids; {@link PeBlockEditDecoder}'s placement rejects non-block
     * ids, so holding one and right-clicking places nothing. 0.14 must NOT receive these (it crashes on ids
     * it can't render), so its narrower {@link com.jedrock.network.pe.v014.Pe014Blocks} palette is separate.
     * Ids from PocketMine-MP {@code ItemIds} at protocol 113. Returns a private copy.
     */
    public static int[] items() {
        return ITEMS.clone();
    }

    /** The full 1.1.5 creative menu: blocks followed by items. (0.14 uses the narrower Pe014Blocks set.) */
    public static int[] forV115() {
        int[] out = new int[STATES.length + ITEMS.length];
        System.arraycopy(STATES, 0, out, 0, STATES.length);
        System.arraycopy(ITEMS, 0, out, STATES.length, ITEMS.length);
        return out;
    }

    private static int[] buildItems() {
        List<Integer> s = new ArrayList<>(90);
        // Tools & weapons — wood, stone, iron, gold, diamond
        plain(s, 268, 272, 267, 283, 276);        // swords
        plain(s, 270, 274, 257, 285, 278);        // pickaxes
        plain(s, 271, 275, 258, 286, 279);        // axes
        plain(s, 269, 273, 256, 284, 277);        // shovels
        plain(s, 290, 291, 292, 294, 293);        // hoes
        plain(s, 259, 359, 261, 262, 346);        // flint & steel, shears, bow, arrow, fishing rod
        // Armor — leather, chainmail, iron, gold, diamond
        plain(s, 298, 299, 300, 301);
        plain(s, 302, 303, 304, 305);
        plain(s, 306, 307, 308, 309);
        plain(s, 314, 315, 316, 317);
        plain(s, 310, 311, 312, 313);
        // Food
        plain(s, 260, 322, 297, 282, 360);        // apple, golden apple, bread, mushroom stew, melon slice
        plain(s, 364, 366, 320, 350);             // cooked beef / chicken / porkchop / fish
        plain(s, 391, 392, 393, 357, 354);        // carrot, potato, baked potato, cookie, cake
        // Materials
        metas(s, 263, 0, 1);                      // coal, charcoal
        plain(s, 264, 265, 266, 388);             // diamond, iron ingot, gold ingot, emerald
        plain(s, 280, 281, 287, 288, 289);        // stick, bowl, string, feather, gunpowder
        plain(s, 331, 348, 352, 337, 336);        // redstone, glowstone dust, bone, clay ball, brick
        plain(s, 339, 340, 341, 318, 368);        // paper, book, slimeball, flint, ender pearl
        plain(s, 369, 406, 353, 344, 332);        // blaze rod, nether quartz, sugar, egg, snowball
        plain(s, 296, 338, 334, 325);             // wheat, sugar cane, leather, bucket
        return toArray(s);
    }

    private static int[] toArray(List<Integer> s) {
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
