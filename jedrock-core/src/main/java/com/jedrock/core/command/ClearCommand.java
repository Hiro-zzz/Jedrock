package com.jedrock.core.command;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.inventory.Container;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.Optional;

/**
 * {@code /clear [player]} — empty a player's survival inventory. With no argument it targets the sender.
 * Survival only: the creative inventory is managed client-side, so clearing it server-side would just
 * desync — the command reports that instead.
 */
public final class ClearCommand implements Command {

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "Clear a player's inventory (survival)";
    }

    @Override
    public String usage() {
        return "/clear [player]";
    }

    @Override
    public void execute(JedrockServer server, CorePlayer sender, String[] args) {
        CorePlayer target = sender;
        if (args.length >= 1) {
            Optional<Player> found = server.getPlayer(args[0]);
            if (found.isEmpty() || !(found.get() instanceof CorePlayer cp)) {
                sender.sendMessage("{red}Player not found: {white}" + ChatText.escape(args[0]));
                return;
            }
            target = cp;
        }
        if (target.getGameMode() != GameMode.SURVIVAL) {
            sender.sendMessage("{red}Only a survival inventory can be cleared.");
            return;
        }
        Container inv = target.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            inv.clear(i);
        }
        target.syncInventory();
        target.sendMessage("{green}Your inventory was cleared.");
        if (target != sender) {
            sender.sendMessage("{green}Cleared {white}" + ChatText.escape(target.getName())
                    + "{green}'s inventory.");
        }
    }
}
