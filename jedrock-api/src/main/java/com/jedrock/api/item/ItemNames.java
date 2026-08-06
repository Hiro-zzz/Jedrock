package com.jedrock.api.item;

import com.jedrock.api.world.Blocks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Names for the canonical {@code (id << 4) | meta} states — the vocabulary that lets a person type
 * {@code red_wool} where the rest of this project says {@code 574}.
 *
 * <p>There is one table for blocks and items alike, because there is one model for both (see
 * {@link Blocks}). A name is the legacy one every target version shares, lower-case with underscores.
 *
 * <p><b>What this is not.</b> It is not a palette and not a render gate: naming a state says nothing
 * about whether a given client can draw it — that stays each edition's own business (the Bedrock eras
 * filter through their own lists, and 0.14 crashes on ids it doesn't know). It is also deliberately
 * <em>incomplete</em>. Where the two legacy numberings disagree about what an id means, the state is
 * left unnamed rather than given a name that would be wrong on half the server:
 *
 * <ul>
 *   <li>{@code 158} — a dropper on Java, a wooden slab on Bedrock.</li>
 *   <li>{@code 85:1}-{@code 85:5} — wood types in a fence's meta on Bedrock; on Java those are ids of
 *       their own and {@code 85} is oak alone.</li>
 *   <li>{@code 243} — podzol on Bedrock, unused on Java, and {@code 3:2} already means podzol.</li>
 * </ul>
 *
 * <p>Anything unnamed is still fully addressable as {@code id} or {@code id:meta}, which
 * {@link #parse} accepts and {@link #name} falls back to — so the table can never make a state
 * unreachable, only easier to reach.
 */
public final class ItemNames {

    private ItemNames() {}

    /** Name → canonical state. Insertion-ordered, so completion offers them in a sensible order. */
    private static final Map<String, Integer> BY_NAME = new LinkedHashMap<>(768);
    /** State → its canonical name (the first one registered; later ones are aliases). */
    private static final Map<Integer, String> BY_STATE = new LinkedHashMap<>(768);
    /** Base name → block id, for the {@code wool:14} form. */
    private static final Map<String, Integer> FAMILIES = new LinkedHashMap<>(64);

    /**
     * Resolve a typed token to a canonical state, or {@code -1} if it names nothing.
     *
     * <p>Accepted, in this order: a name ({@code red_wool}), a family name with an explicit meta
     * ({@code wool:14}), and the numeric forms {@code id} ({@code 35}, {@code 276}) or {@code id:meta}
     * ({@code 35:14}). The numeric forms are always accepted, named or not — this table adds a way to
     * say things, it never takes one away.
     *
     * <p>A bare number is an <b>id</b>, never a packed state: that is what a person means when they
     * type {@code 276}, and it is the form every other legacy server has taken since forever. A packed
     * state is what the API speaks internally and is reachable here as {@code id:meta}.
     */
    public static int parse(String token) {
        if (token == null) {
            return -1;
        }
        String text = token.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return -1;
        }
        Integer exact = BY_NAME.get(text);
        if (exact != null) {
            return exact;
        }
        int colon = text.indexOf(':');
        if (colon < 0) {
            int id = number(text);
            return id < 0 || id > MAX_ID ? -1 : Blocks.state(id, 0);
        }
        String head = text.substring(0, colon);
        int meta = number(text.substring(colon + 1));
        if (meta < 0 || meta > 15) {
            return -1;
        }
        Integer family = FAMILIES.get(head);
        if (family != null) {
            return Blocks.state(family, meta);
        }
        int id = number(head);
        return id < 0 || id > MAX_ID ? -1 : Blocks.state(id, meta);
    }

    /**
     * The largest id a token may name. Legacy item ids run past 450 (beetroot seeds are 458); the
     * ceiling only exists so a typo'd number is refused rather than packed into a nonsense state.
     */
    private static final int MAX_ID = 4095;

    /**
     * The name of a state, or {@code id:meta} ({@code id} when the meta is 0) for one nothing names.
     * Always printable: this is what a command shows a player, so it never returns {@code null}.
     */
    public static String name(int state) {
        String named = BY_STATE.get(state);
        if (named != null) {
            return named;
        }
        int id = Blocks.idOf(state);
        int meta = Blocks.metaOf(state);
        return meta == 0 ? Integer.toString(id) : id + ":" + meta;
    }

    /** Whether anything in the table names this state. */
    public static boolean isNamed(int state) {
        return BY_STATE.containsKey(state);
    }

    /** Every name, in table order — the completion source. */
    public static List<String> names() {
        return new ArrayList<>(BY_NAME.keySet());
    }

    /** How many states carry a name. */
    public static int size() {
        return BY_STATE.size();
    }

    private static int number(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value < 0 ? -1 : value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ===== The table =====

    /** The dye colours, in legacy meta order — wool, carpet and stained clay all use it. */
    private static final String[] COLOURS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };

    /** The six wood types, in legacy meta order. Ids 17/18 carry the first four, 162/161 the last two. */
    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak"};

    static {
        // --- Stone & manufactured building blocks ---
        put("stone", 1, 0);
        put("granite", 1, 1);
        put("polished_granite", 1, 2);
        put("diorite", 1, 3);
        put("polished_diorite", 1, 4);
        put("andesite", 1, 5);
        put("polished_andesite", 1, 6);
        family("stone", 1);
        put("grass", 2, 0);
        put("dirt", 3, 0);
        put("coarse_dirt", 3, 1);
        put("podzol", 3, 2);
        family("dirt", 3);
        put("cobblestone", 4, 0);
        put("bedrock", 7, 0);
        put("water", 9, 0);
        put("flowing_water", 8, 0);
        put("lava", 11, 0);
        put("flowing_lava", 10, 0);
        put("sand", 12, 0);
        put("red_sand", 12, 1);
        family("sand", 12);
        put("gravel", 13, 0);
        put("sponge", 19, 0);
        put("glass", 20, 0);
        put("sandstone", 24, 0);
        put("chiseled_sandstone", 24, 1);
        put("smooth_sandstone", 24, 2);
        family("sandstone", 24);
        put("cobweb", 30, 0);
        put("bricks", 45, 0);
        alias("brick_block", 45, 0);
        put("mossy_cobblestone", 48, 0);
        put("obsidian", 49, 0);
        put("stone_bricks", 98, 0);
        put("mossy_stone_bricks", 98, 1);
        put("cracked_stone_bricks", 98, 2);
        put("chiseled_stone_bricks", 98, 3);
        family("stone_bricks", 98);
        put("netherrack", 87, 0);
        put("soul_sand", 88, 0);
        put("glowstone", 89, 0);
        put("nether_bricks", 112, 0);
        put("end_stone", 121, 0);
        put("quartz_block", 155, 0);
        put("chiseled_quartz_block", 155, 1);
        put("quartz_pillar", 155, 2);
        family("quartz_block", 155);
        put("prismarine", 168, 0);
        put("sea_lantern", 169, 0);
        put("hay_block", 170, 0);
        put("hardened_clay", 172, 0);
        put("coal_block", 173, 0);
        put("packed_ice", 174, 0);
        put("red_sandstone", 179, 0);
        put("chiseled_red_sandstone", 179, 1);
        put("smooth_red_sandstone", 179, 2);
        family("red_sandstone", 179);

        // --- Wood ---
        for (int i = 0; i < 4; i++) {
            put(WOODS[i] + "_log", 17, i);
            put(WOODS[i] + "_leaves", 18, i);
        }
        for (int i = 0; i < 2; i++) {
            put(WOODS[i + 4] + "_log", 162, i);
            put(WOODS[i + 4] + "_leaves", 161, i);
        }
        family("log", 17);
        family("leaves", 18);
        for (int i = 0; i < WOODS.length; i++) {
            put(WOODS[i] + "_planks", 5, i);
            put(WOODS[i] + "_sapling", 6, i);
        }
        family("planks", 5);
        family("sapling", 6);
        put("bookshelf", 47, 0);

        // --- Ground & natural ---
        put("farmland", 60, 0);
        put("mycelium", 110, 0);
        put("clay", 82, 0);
        put("ice", 79, 0);
        put("snow_block", 80, 0);
        put("snow_layer", 78, 0);
        put("cactus", 81, 0);
        put("vines", 106, 0);
        put("lily_pad", 111, 0);
        put("pumpkin", 86, 0);
        put("jack_o_lantern", 91, 0);
        put("melon", 103, 0);
        put("dandelion", 37, 0);
        put("poppy", 38, 0);
        put("blue_orchid", 38, 1);
        put("allium", 38, 2);
        put("azure_bluet", 38, 3);
        put("red_tulip", 38, 4);
        put("orange_tulip", 38, 5);
        put("white_tulip", 38, 6);
        put("pink_tulip", 38, 7);
        put("oxeye_daisy", 38, 8);
        family("flower", 38);
        put("brown_mushroom", 39, 0);
        put("red_mushroom", 40, 0);
        put("brown_mushroom_block", 99, 0);
        put("red_mushroom_block", 100, 0);
        put("tall_grass", 31, 1);
        put("fern", 31, 2);
        put("dead_bush", 32, 0);

        // --- Ores & mineral blocks ---
        put("gold_ore", 14, 0);
        put("iron_ore", 15, 0);
        put("coal_ore", 16, 0);
        put("lapis_ore", 21, 0);
        put("lapis_block", 22, 0);
        put("diamond_ore", 56, 0);
        put("redstone_ore", 73, 0);
        put("emerald_ore", 129, 0);
        put("quartz_ore", 153, 0);
        put("gold_block", 41, 0);
        put("iron_block", 42, 0);
        put("diamond_block", 57, 0);
        put("emerald_block", 133, 0);
        put("redstone_block", 152, 0);

        // --- Glass, panes & bars ---
        put("glass_pane", 102, 0);
        put("iron_bars", 101, 0);

        // --- Wool, carpet & terracotta: the full colour range ---
        for (int meta = 0; meta < COLOURS.length; meta++) {
            put(COLOURS[meta] + "_wool", 35, meta);
            put(COLOURS[meta] + "_carpet", 171, meta);
            put(COLOURS[meta] + "_terracotta", 159, meta);
            alias(COLOURS[meta] + "_stained_clay", 159, meta);
        }
        family("wool", 35);
        family("carpet", 171);
        family("terracotta", 159);

        // --- Utility & interactive ---
        put("chest", 54, 0);
        put("crafting_table", 58, 0);
        put("furnace", 61, 0);
        put("jukebox", 84, 0);
        put("note_block", 25, 0);
        put("tnt", 46, 0);
        put("enchanting_table", 116, 0);
        put("anvil", 145, 0);
        put("ender_chest", 130, 0);
        put("beacon", 138, 0);
        put("dispenser", 23, 0);
        put("command_block", 137, 0);
        put("torch", 50, 0);
        put("ladder", 65, 0);
        put("spawner", 52, 0);
        put("stonecutter", 245, 0);
        put("end_portal_frame", 120, 0);
        put("redstone_lamp", 123, 0);
        put("sticky_piston", 29, 0);
        put("piston", 33, 0);
        put("trapdoor", 96, 0);
        put("iron_trapdoor", 167, 0);
        put("nether_brick_fence", 113, 0);
        put("oak_fence", 85, 0);
        put("slime_block", 165, 0);

        // --- Stairs ---
        put("oak_stairs", 53, 0);
        put("cobblestone_stairs", 67, 0);
        put("brick_stairs", 108, 0);
        put("stone_brick_stairs", 109, 0);
        put("nether_brick_stairs", 114, 0);
        put("sandstone_stairs", 128, 0);
        put("quartz_stairs", 156, 0);
        put("spruce_stairs", 134, 0);
        put("birch_stairs", 135, 0);
        put("jungle_stairs", 136, 0);
        put("acacia_stairs", 163, 0);
        put("dark_oak_stairs", 164, 0);

        // --- Slabs & walls ---
        put("stone_slab", 44, 0);
        put("sandstone_slab", 44, 1);
        put("cobblestone_slab", 44, 3);
        put("brick_slab", 44, 4);
        put("stone_brick_slab", 44, 5);
        put("nether_brick_slab", 44, 6);
        put("quartz_slab", 44, 7);
        family("slab", 44);
        // The wooden slabs, whose id is one of the places the two numberings part company: 126 here (the
        // one both creative palettes actually offer), while 0.14 also ships 158 — left unnamed above.
        for (int i = 0; i < WOODS.length; i++) {
            put(WOODS[i] + "_slab", 126, i);
        }
        family("wooden_slab", 126);
        put("cobblestone_wall", 139, 0);
        put("mossy_cobblestone_wall", 139, 1);
        put("oak_fence_gate", 107, 0);
        put("spruce_fence_gate", 183, 0);
        put("birch_fence_gate", 184, 0);
        put("jungle_fence_gate", 185, 0);
        put("dark_oak_fence_gate", 186, 0);
        put("acacia_fence_gate", 187, 0);

        items();
    }

    /** The item half (id ≥ 256) — tools, armor, food and materials. */
    private static void items() {
        // Tools & weapons, by tier. Legacy ids are grouped by tool, not by tier, hence the tables.
        tier("sword", 268, 272, 267, 283, 276);
        tier("pickaxe", 270, 274, 257, 285, 278);
        tier("axe", 271, 275, 258, 286, 279);
        tier("shovel", 269, 273, 256, 284, 277);
        tier("hoe", 290, 291, 292, 294, 293);
        put("flint_and_steel", 259, 0);
        put("shears", 359, 0);
        put("bow", 261, 0);
        put("arrow", 262, 0);
        put("fishing_rod", 346, 0);
        put("clock", 347, 0);
        put("compass", 345, 0);
        put("minecart", 328, 0);
        put("bucket", 325, 0);
        put("water_bucket", 325, 10);

        // Armor, head to feet, by tier.
        armor("leather", 298, 299, 300, 301);
        armor("chainmail", 302, 303, 304, 305);
        armor("iron", 306, 307, 308, 309);
        armor("diamond", 310, 311, 312, 313);
        armor("golden", 314, 315, 316, 317);

        // Food
        put("apple", 260, 0);
        put("golden_apple", 322, 0);
        put("bread", 297, 0);
        put("mushroom_stew", 282, 0);
        put("melon_slice", 360, 0);
        put("porkchop", 319, 0);
        put("cooked_porkchop", 320, 0);
        put("beef", 363, 0);
        put("cooked_beef", 364, 0);
        put("chicken", 365, 0);
        put("cooked_chicken", 366, 0);
        put("fish", 349, 0);
        put("salmon", 349, 1);
        put("clownfish", 349, 2);
        put("pufferfish", 349, 3);
        put("cooked_fish", 350, 0);
        put("cooked_salmon", 350, 1);
        put("carrot", 391, 0);
        put("potato", 392, 0);
        put("baked_potato", 393, 0);
        put("cookie", 357, 0);
        put("cake", 354, 0);
        put("pumpkin_pie", 400, 0);

        // Materials
        put("coal", 263, 0);
        put("charcoal", 263, 1);
        put("diamond", 264, 0);
        put("iron_ingot", 265, 0);
        put("gold_ingot", 266, 0);
        put("gold_nugget", 371, 0);
        put("emerald", 388, 0);
        put("stick", 280, 0);
        put("bowl", 281, 0);
        put("string", 287, 0);
        put("feather", 288, 0);
        put("gunpowder", 289, 0);
        put("redstone", 331, 0);
        put("glowstone_dust", 348, 0);
        put("bone", 352, 0);
        put("clay_ball", 337, 0);
        put("brick", 336, 0);
        put("paper", 339, 0);
        put("book", 340, 0);
        put("slimeball", 341, 0);
        put("flint", 318, 0);
        put("ender_pearl", 368, 0);
        put("blaze_rod", 369, 0);
        put("nether_quartz", 406, 0);
        put("sugar", 353, 0);
        put("egg", 344, 0);
        put("snowball", 332, 0);
        put("wheat", 296, 0);
        put("wheat_seeds", 295, 0);
        put("pumpkin_seeds", 361, 0);
        put("melon_seeds", 362, 0);
        put("beetroot_seeds", 458, 0);
        put("sugar_cane", 338, 0);
        put("leather", 334, 0);
        put("bed", 355, 0);
        put("painting", 321, 0);
        put("sign", 323, 0);
        put("flower_pot", 390, 0);
        put("brewing_stand", 379, 0);
        put("oak_door", 324, 0);
        put("iron_door", 330, 0);
    }

    /** The five tiers of one tool, in the order wood / stone / iron / gold / diamond. */
    private static void tier(String tool, int wood, int stone, int iron, int gold, int diamond) {
        put("wooden_" + tool, wood, 0);
        put("stone_" + tool, stone, 0);
        put("iron_" + tool, iron, 0);
        put("golden_" + tool, gold, 0);
        put("diamond_" + tool, diamond, 0);
    }

    /** One armor set, head to feet. */
    private static void armor(String material, int helmet, int chestplate, int leggings, int boots) {
        put(material + "_helmet", helmet, 0);
        put(material + "_chestplate", chestplate, 0);
        put(material + "_leggings", leggings, 0);
        put(material + "_boots", boots, 0);
    }

    /** Register a name and make it the one {@link #name} gives back. */
    private static void put(String name, int id, int meta) {
        int state = Blocks.state(id, meta);
        BY_NAME.put(name, state);
        BY_STATE.putIfAbsent(state, name);
    }

    /** Another way to type a state that already has a name — accepted, never printed. */
    private static void alias(String name, int id, int meta) {
        BY_NAME.put(name, Blocks.state(id, meta));
    }

    /**
     * Let {@code <base>:<meta>} address every variant of an id, named or not — and let the bare base word
     * mean the meta-0 member, so somebody who types {@code wool} gets white wool rather than an error.
     * The alias is registered second, so {@link #name} keeps giving back the specific name.
     */
    private static void family(String base, int id) {
        FAMILIES.put(base, id);
        BY_NAME.putIfAbsent(base, Blocks.state(id, 0));
    }
}
