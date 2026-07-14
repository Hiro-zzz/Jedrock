package com.jedrock.core.command;

import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.Collection;
import java.util.List;

/**
 * {@code /list} (aliases {@code /who}, {@code /players}) — show every online player and their edition,
 * cross-platform: a Java and a Bedrock player both appear in one list, the same roster the console
 * {@code players} command prints.
 */
public final class ListCommand implements Command {

    @Override
    public String name() {
        return "list";
    }

    @Override
    public List<String> aliases() {
        return List.of("who", "players");
    }

    @Override
    public String description() {
        return "List online players";
    }

    @Override
    public String usage() {
        return "/list";
    }

    @Override
    public void execute(JedrockServer server, CorePlayer sender, String[] args) {
        Collection<Player> players = server.getPlayers();
        sender.sendMessage("{gold}{bold}Online ({white}" + players.size() + "{gold}):");
        for (Player p : players) {
            sender.sendMessage("{gray}• {white}" + ChatText.escape(p.getName())
                    + " {dark_gray}[" + p.getConnection().getProtocolVersion().getVersionName() + "]");
        }
    }
}
