package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.item.ItemNames;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.item.CoreCustomItem;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.List;
import java.util.Optional;

/**
 * {@code /give <player> <item> [count]} — put something in somebody's inventory.
 *
 * <p>An item is named ({@code red_wool}), a family with a meta ({@code wool:14}), or numeric
 * ({@code 35:14}, {@code 276}) — see {@link ItemNames}, which is also where tab-completion comes from.
 * A <b>custom item's key wins</b> over any of those: {@code /give Steve frostblade} hands over the real
 * thing, carrying the identity that makes it one, rather than the diamond sword it is drawn as.
 *
 * <p>What arrives is the inventory the server keeps. On a client that owns its own inventory — creative
 * on either Bedrock era — that is the same limitation every other give has here, and the command says so
 * rather than pretending the stack landed.
 */
public final class GiveCommand implements Command {

    /** More than this in one command is a typo, not a request. A stack is 64. */
    private static final int MAX_COUNT = 512;

    @Override
    public String name() {
        return "give";
    }

    @Override
    public String description() {
        return "Give a player an item";
    }

    @Override
    public String usage() {
        return "/give <player> <item> [count]";
    }

    @Override
    public String permission() {
        return "jedrock.command.give";
    }

    @Override
    public List<CommandArg> arguments() {
        return List.of(
                CommandArg.required("player", ArgType.PLAYER),
                CommandArg.required("item", ArgType.ITEM),
                CommandArg.optional("count", ArgType.INTEGER));
    }

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

        int count = 1;
        if (args.length >= 3) {
            try {
                count = Integer.parseInt(args[2].trim());
            } catch (NumberFormatException e) {
                sender.sendMessage("{red}'" + ChatText.escape(args[2]) + "' is not a whole number.");
                return;
            }
            if (count < 1 || count > MAX_COUNT) {
                sender.sendMessage("{red}Count must be between {white}1{red} and {white}" + MAX_COUNT + "{red}.");
                return;
            }
        }

        // A custom item first: its key names a definition the server holds, which is a different (and
        // richer) thing than a state, so it can't be resolved by the name table.
        String token = args[1].trim();
        CoreCustomItem custom = server.getItems().get(token.toLowerCase(java.util.Locale.ROOT));
        int state;
        String key;
        String label;
        if (custom != null) {
            state = custom.getState();
            key = custom.getKey();
            label = custom.getDisplayName() != null ? custom.getDisplayName() : custom.getKey();
        } else {
            state = ItemNames.parse(token);
            if (state <= 0) {
                sender.sendMessage("{red}'" + ChatText.escape(token) + "' is not an item. Try a name like "
                        + "{white}red_wool{red}, an id like {white}35:14{red}, or a custom item's key.");
                return;
            }
            key = null;
            label = ItemNames.name(state);
        }

        int given = target.giveItem(state, count, key);
        if (given == 0) {
            sender.sendMessage("{red}" + ChatText.escape(target.getName())
                    + "{red}'s inventory is full — nothing was given.");
            return;
        }

        String what = "{white}" + given + " × " + ChatText.escape(label);
        String shortfall = given < count ? " {gray}(" + (count - given) + " didn't fit)" : "";
        target.sendMessage("{green}You were given " + what + "{green}." + shortfall);
        if (target != sender) {
            sender.sendMessage("{green}Gave " + what + "{green} to {white}"
                    + ChatText.escape(target.getName()) + "{green}." + shortfall);
        }
    }
}
