package com.jedrock.core.command;

import com.jedrock.api.Server;
import com.jedrock.api.command.CommandSender;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.inventory.ListMenu;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.List;

/**
 * {@code /pick <option>} — choose an option from a button menu that was shown as a text <b>list</b> (the
 * fallback for a client that can't open a chest window, i.e. 1.1.5). The chosen label fires the menu's
 * click. It's a built-in so it's in the Bedrock command manifest from the start — a menu-time command
 * couldn't be, since the client rejects any command it wasn't told about.
 *
 * <p>On Java the option labels tab-complete (from the player's pending menu). On 1.1.5 the client only
 * knows the command takes free text, so the player reads the options from the chat list and types one;
 * true client-side label completion there would need a per-menu command manifest, a follow-up.
 */
public final class PickCommand implements Command {

    @Override
    public String name() {
        return "pick";
    }

    @Override
    public String description() {
        return "Choose an option from a list menu";
    }

    @Override
    public String usage() {
        return "/pick <option>";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        CorePlayer player = (CorePlayer) sender; // playerOnly guarantees a player
        ListMenu menu = player.getPendingMenu();
        if (menu == null) {
            sender.sendMessage("{red}There's nothing to pick right now.");
            return;
        }
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            list(sender, menu);
            return;
        }
        String label = String.join(" ", args);
        if (menu.pick(player, label)) {
            player.setPendingMenu(null); // one pick per list
        } else {
            sender.sendMessage("{red}No such option: {white}" + ChatText.escape(label));
            list(sender, menu);
        }
    }

    @Override
    public List<String> complete(Server server, CommandSender sender, String[] args) {
        if (!(sender instanceof CorePlayer player) || player.getPendingMenu() == null) {
            return List.of();
        }
        String partial = args.length == 0 ? "" : String.join(" ", args);
        return ArgType.matching(partial, player.getPendingMenu().labels());
    }

    private static void list(CommandSender sender, ListMenu menu) {
        for (String label : menu.labels()) {
            sender.sendMessage(" {gray}• {white}/pick " + label);
        }
    }
}
