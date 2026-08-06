package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.item.Enchantment;
import com.jedrock.api.item.Enchantments;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code /enchant <player> <enchantment> [level]}, {@code /enchant <player> clear} and
 * {@code /enchant <player> list} — enchant what somebody is holding.
 *
 * <p>There is no enchanting <em>table</em> here and no experience: an enchantment is given. A table would
 * need levels this server doesn't keep and a window the 1.1.5 client cannot raise, and neither is worth
 * inventing for a system whose visible half — the glint and the tooltip — the client draws either way.
 *
 * <p>Worth knowing while using it: <b>most enchantments are decoration on this server</b>, honestly so.
 * The ones that change what the server does are sharpness (and its two cousins), protection, feather
 * falling, thorns and fortune. The rest are for durability, projectiles, fire and knockback — none of
 * which this server simulates — so they render, glint and read correctly and do nothing else.
 */
public final class EnchantCommand implements Command {

    /** Vanilla tops out at V; past that the level is the player's business, but a typo shouldn't stick. */
    private static final int MAX_LEVEL = 10;

    @Override
    public String name() {
        return "enchant";
    }

    @Override
    public String description() {
        return "Enchant the item a player is holding";
    }

    @Override
    public String usage() {
        return "/enchant <player> <enchantment|clear|list> [level]";
    }

    @Override
    public String permission() {
        return "jedrock.command.enchant";
    }

    @Override
    public List<CommandArg> arguments() {
        return List.of(
                CommandArg.required("player", ArgType.PLAYER),
                CommandArg.required("enchantment", ENCHANTMENT),
                CommandArg.optional("level", ArgType.INTEGER));
    }

    /** An enchantment by name, completing to every one there is plus the two verbs. */
    private static final ArgType<String> ENCHANTMENT = new ArgType<>() {
        @Override public String label() { return "enchantment|clear|list"; }
        @Override public String parse(com.jedrock.api.Server s, CommandSender c, String t) {
            return t;   // resolved in execute, which also has to accept 'clear' and 'list'
        }
        @Override public List<String> complete(com.jedrock.api.Server s, CommandSender c, String partial) {
            List<String> options = new ArrayList<>(List.of("clear", "list"));
            for (Enchantment e : Enchantment.values()) {
                options.add(e.getKey());
            }
            return ArgType.matching(partial, options);
        }
    };

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: {white}" + usage());
            return;
        }
        Optional<Player> found = server.getPlayer(args[0]);
        if (found.isEmpty() || !(found.get() instanceof CorePlayer target)) {
            sender.sendMessage("{red}Player not found: {white}" + ChatText.escape(args[0]));
            return;
        }
        if (target.getHeldItem() == 0) {
            sender.sendMessage("{red}" + ChatText.escape(target.getName()) + " isn't holding anything.");
            return;
        }
        String what = args[1].trim().toLowerCase(Locale.ROOT);

        if (what.equals("list")) {
            list(sender, target);
            return;
        }
        if (what.equals("clear")) {
            sender.sendMessage(server.getEnchants().disenchant(target, target.getHeldItemSlot())
                    ? "{green}Stripped every enchantment from {white}" + held(target) + "{green}."
                    : "{gray}That item isn't enchanted.");
            return;
        }

        Enchantment enchantment = Enchantment.fromString(what);
        if (enchantment == null) {
            sender.sendMessage("{red}'" + ChatText.escape(what) + "' is not an enchantment. Try {white}"
                    + "sharpness{red}, {white}protection{red}, {white}fortune{red} — or {white}/enchant "
                    + ChatText.escape(target.getName()) + " list{red}.");
            return;
        }

        int level = 1;
        if (args.length >= 3) {
            try {
                level = Integer.parseInt(args[2].trim());
            } catch (NumberFormatException e) {
                sender.sendMessage("{red}'" + ChatText.escape(args[2]) + "' is not a whole number.");
                return;
            }
            if (level < 0 || level > MAX_LEVEL) {
                sender.sendMessage("{red}The level must be between {white}0{red} (which takes it off) and "
                        + "{white}" + MAX_LEVEL + "{red}.");
                return;
            }
        }

        if (!server.getEnchants().enchantHeld(target, enchantment, level)) {
            sender.sendMessage("{gray}Nothing changed — it already was that, or something refused it.");
            return;
        }

        String label = "{white}" + enchantment.getKey() + (level > 1 ? " " + roman(level) : "");
        if (level <= 0) {
            sender.sendMessage("{green}Took {white}" + enchantment.getKey() + "{green} off {white}"
                    + held(target) + "{green}.");
            return;
        }
        sender.sendMessage("{green}Enchanted {white}" + held(target) + "{green} with " + label + "{green}."
                + (enchantment.isHonoured() ? "" : " {dark_gray}(decoration on this server)"));
        if (target != sender) {
            target.sendMessage("{green}Your " + held(target) + " is now " + label + "{green}.");
        }
    }

    private static void list(CommandSender sender, CorePlayer target) {
        Enchantments held = target.getHeldEnchantments();
        if (held.isEmpty()) {
            sender.sendMessage("{gray}" + held(target) + " isn't enchanted.");
            return;
        }
        sender.sendMessage("{gold}" + held(target) + ":");
        for (Map.Entry<Enchantment, Integer> e : held.asMap().entrySet()) {
            sender.sendMessage(" {gray}• {white}" + e.getKey().getKey()
                    + (e.getValue() > 1 ? " " + roman(e.getValue()) : "")
                    + (e.getKey().isHonoured() ? "" : " {dark_gray}— decoration here"));
        }
    }

    /** What the player is holding, by name — the same table {@code /give} parses. */
    private static String held(CorePlayer player) {
        String key = player.getHeldItemKey();
        if (key != null) {
            return ChatText.escape(key);
        }
        return ChatText.escape(com.jedrock.api.item.ItemNames.name(player.getHeldItem()));
    }

    /** Levels are shown the way the game shows them. Past five, the number is clearer than the numeral. */
    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> Integer.toString(level);
        };
    }
}
