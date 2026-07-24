package com.jedrock.network.pe.v014;

import com.jedrock.api.world.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * The block ids MCPE <b>0.14</b> (protocol 45) can safely handle, and the creative-menu palette built
 * from them. The very old 0.14 client has no "unknown item" fallback: an id it doesn't recognise
 * <em>crashes</em> it — whether it arrives in the creative menu or inside a chunk. So 0.14 is hard-limited
 * to this conservative, high-confidence classic block set on both paths:
 *
 * <ul>
 *   <li>{@link #creativePalette()} — the creative menu is filled only from here.</li>
 *   <li>{@link #supports(int)} — the chunk serializer maps any other id to air, so a block a Java or
 *       1.1.5 player placed that 0.14 doesn't know never reaches the 0.14 client.</li>
 * </ul>
 *
 * <p>The palette mirrors <b>PocketMine-MP's own 0.14 creative list</b>
 * ({@code resources/creativeitems.json} in the 0.14 tree at {@code e11b76318}): its 207 block entries,
 * plus two conservative extras (farmland, note block) validated against a real client earlier, plus
 * the <b>item</b> half (weapons / tools / armor / food — {@link Pe014Items}). Every id/meta is
 * battle-tested against this exact client generation. Items are inert (held / stored, never placed);
 * {@link #supports} stays block-only — it gates what a chunk may carry, and an item id never appears
 * in a chunk.
 */
public final class Pe014Blocks {

    private Pe014Blocks() {}

    private static final boolean[] SUPPORTED = new boolean[256];
    private static final int[] PALETTE = buildPalette();

    static {
        // Everything in the menu is renderable; also allow the natural world blocks (water) a player
        // never places from the menu but the baked terrain contains. Item entries (id > 255) live in
        // the menu too, but never in a chunk — only block ids feed this table.
        for (int state : PALETTE) {
            int id = Blocks.idOf(state);
            if (id < SUPPORTED.length) {
                SUPPORTED[id] = true;
            }
        }
        SUPPORTED[8] = true;  // flowing water
        SUPPORTED[9] = true;  // still water
    }

    /** Whether the 0.14 client can render this block id (else the chunk serializer sends air instead). */
    public static boolean supports(int id) {
        return id >= 0 && id < 256 && SUPPORTED[id];
    }

    /** The 0.14 creative-menu states (canonical {@code (id<<4)|meta}). Returns a private copy. */
    public static int[] creativePalette() {
        return PALETTE.clone();
    }

    private static int[] buildPalette() {
        // PMMP's creativeitems.json order (roughly: building → ores/minerals → utility → plants →
        // wool/carpet), so the menu reads like the one 0.14 players actually saw.
        List<Integer> s = new ArrayList<>(216);
        plain(s, 4);                                       // cobblestone
        metas(s, 98, 0, 1, 2, 3);                          // stone bricks: plain, mossy, cracked, chiseled
        plain(s, 48);                                      // moss stone
        metas(s, 5, 0, 1, 2, 3, 4, 5);                     // planks
        plain(s, 45);                                      // bricks
        metas(s, 1, 0, 1, 2, 3, 4, 5, 6);                  // stone: granite/diorite/andesite (+polished)
        plain(s, 3, 243, 2, 110, 82, 172);                 // dirt, podzol, grass, mycelium, clay, hardened clay
        metas(s, 159, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15); // stained clay
        metas(s, 24, 0, 1, 2);                             // sandstone: plain, chiseled, smooth
        metas(s, 12, 0, 1);                                // sand, red sand
        plain(s, 13);                                      // gravel
        metas(s, 17, 0, 1, 2, 3);                          // logs
        metas(s, 162, 0, 1);                               // log2: acacia, dark oak
        plain(s, 112, 87, 88, 7);                          // nether brick, netherrack, soul sand, bedrock
        plain(s, 67, 53, 134, 135, 136, 163, 164);         // stairs: cobble + the six wood types
        plain(s, 108, 128, 109, 114, 156);                 // stairs: brick, sandstone, stone brick, nether, quartz
        metas(s, 44, 0, 1, 3, 4, 5, 6, 7);                 // stone slabs (2 = the 0.14 wood-slab legacy id — skip)
        metas(s, 158, 0, 1, 2, 3, 4, 5);                   // wooden slabs
        metas(s, 155, 0, 1, 2);                            // quartz block: plain, chiseled, pillar
        plain(s, 16, 15, 14, 56, 21, 73, 129);             // ores: coal, iron, gold, diamond, lapis, redstone, emerald
        plain(s, 49, 79, 174, 80, 121);                    // obsidian, ice, packed ice, snow block, end stone
        metas(s, 139, 0, 1);                               // cobblestone wall, mossy
        plain(s, 111);                                     // lily pad
        plain(s, 41, 42, 57, 22, 173, 133, 152);           // mineral blocks: gold…redstone
        plain(s, 78, 20, 89, 106, 65, 19, 102);            // snow layer, glass, glowstone, vines, ladder, sponge, pane
        plain(s, 96, 167);                                 // wooden / iron trapdoor
        metas(s, 85, 0, 1, 2, 3, 4, 5);                    // fences (0.14 keeps wood type in the meta)
        plain(s, 113);                                     // nether brick fence
        plain(s, 107, 183, 184, 185, 187, 186);            // fence gates: oak, spruce, birch, jungle, acacia, dark oak
        plain(s, 101, 47, 58, 245, 54, 61, 120);           // iron bars, bookshelf, crafting, stonecutter, chest, furnace, end portal frame
        metas(s, 145, 8);                                  // anvil (PMMP ships the meta-8 variant)
        plain(s, 37);                                      // dandelion
        metas(s, 38, 0, 1, 2, 3, 4, 5, 6, 7, 8);           // poppy…oxeye daisy
        plain(s, 39, 40, 81, 103, 86, 91, 30, 170);        // mushrooms, cactus, melon, pumpkin, jack-o, cobweb, hay
        metas(s, 31, 1, 2);                                // tall grass, fern
        plain(s, 32);                                      // dead bush
        metas(s, 6, 0, 1, 2, 3, 4, 5);                     // saplings
        metas(s, 18, 0, 1, 2, 3);                          // leaves
        metas(s, 161, 0, 1);                               // leaves2: acacia, dark oak
        plain(s, 52, 116);                                 // monster spawner, enchanting table
        metas(s, 35, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15); // wool
        metas(s, 171, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15); // carpets
        plain(s, 50, 46);                                  // torch, tnt
        // Extras beyond the PMMP list, validated against a real 0.14 client earlier.
        plain(s, 60, 25);                                  // farmland, note block

        // The item half (weapons, tools, armor, food, materials) — PMMP's own 0.14 item entries.
        for (int state : Pe014Items.creativeItems()) {
            s.add(state);
        }

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
