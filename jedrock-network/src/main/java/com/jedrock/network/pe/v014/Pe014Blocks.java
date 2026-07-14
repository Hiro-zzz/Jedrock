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
 * <p>Deliberately narrow: stairs, slabs, fences, flowers, redstone gear and the newer/edge blocks are
 * left out. Grow the list only against a real 0.14 client.
 */
public final class Pe014Blocks {

    private Pe014Blocks() {}

    private static final boolean[] SUPPORTED = new boolean[256];
    private static final int[] PALETTE = buildPalette();

    static {
        // Everything in the menu is renderable; also allow the natural world blocks (water) a player
        // never places from the menu but the baked terrain contains.
        for (int state : PALETTE) {
            SUPPORTED[Blocks.idOf(state)] = true;
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
        List<Integer> s = new ArrayList<>(120);
        plain(s, 1, 2, 3, 4);                              // stone, grass, dirt, cobblestone
        metas(s, 5, 0, 1, 2, 3, 4, 5);                     // planks
        plain(s, 7, 13);                                   // bedrock, gravel
        metas(s, 12, 0, 1);                                // sand, red sand
        plain(s, 14, 15, 16);                              // gold / iron / coal ore
        metas(s, 17, 0, 1, 2, 3);                          // logs
        metas(s, 18, 0, 1, 2, 3);                          // leaves
        plain(s, 19, 20, 21, 22);                          // sponge, glass, lapis ore, lapis block
        metas(s, 24, 0, 1, 2);                             // sandstone
        metas(s, 35, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15); // wool
        plain(s, 41, 42, 45, 46, 47, 48, 49);              // gold, iron, brick, tnt, bookshelf, mossy, obsidian
        plain(s, 54, 56, 57, 58, 61);                      // chest, diamond ore/block, crafting table, furnace
        plain(s, 79, 80, 81, 82);                          // ice, snow, cactus, clay
        plain(s, 86, 87, 88, 89, 91);                      // pumpkin, netherrack, soul sand, glowstone, jack-o-lantern
        plain(s, 101, 102, 103);                           // iron bars, glass pane, melon
        plain(s, 112, 121, 129, 133, 152);                 // nether brick, end stone, emerald ore/block, redstone block
        metas(s, 155, 0, 1, 2);                            // quartz block
        metas(s, 159, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15); // stained clay
        metas(s, 161, 0, 1);                               // leaves2
        metas(s, 162, 0, 1);                               // log2
        plain(s, 172, 173, 174);                           // hardened clay, coal block, packed ice
        // Conservative additions — classic blocks present by 0.14, ids shared with Java. Grow this line
        // only against a real 0.14 client; an id 0.14 can't render crashes the menu.
        plain(s, 60, 110, 78, 30, 25);                     // farmland, mycelium, snow layer, cobweb, note block

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
