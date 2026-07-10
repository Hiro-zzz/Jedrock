package com.jedrock.core.command;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.List;
import java.util.Optional;

/**
 * {@code /gamemode <mode> [player]} (alias {@code /gm}) — switch a player's game mode live. The mode
 * accepts a name, a one-letter shorthand or a numeric id (see {@link GameMode#fromString}). With no
 * player it targets the sender. The switch flips the client's HUD and flight ability on the wire (MCPE
 * 0.14 is the one exception — it applies on next join and says so).
 */
public final class GameModeCommand implements Command {

    @Override
    public String name() {
        return "gamemode";
    }

    @Override
    public List<String> aliases() {
        return List.of("gm");
    }

    @Override
    public String description() {
        return "Change a player's game mode";
    }

    @Override
    public String usage() {
        return "/gamemode <survival|creative|s|c|0|1> [player]";
    }

    @Override
    public void execute(JedrockServer server, CorePlayer sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        GameMode mode = GameMode.fromString(args[0]);
        if (mode == null) {
            sender.sendMessage("{red}Unknown game mode: {white}" + ChatText.escape(args[0]));
            return;
        }

        CorePlayer target = sender;
        if (args.length >= 2) {
            Optional<Player> found = server.getPlayer(args[1]);
            if (found.isEmpty() || !(found.get() instanceof CorePlayer cp)) {
                sender.sendMessage("{red}Player not found: {white}" + ChatText.escape(args[1]));
                return;
            }
            target = cp;
        }

        server.setGameMode(target, mode); // persists the choice + pushes the live switch to the client
        target.sendMessage("{green}Your game mode is now {white}" + mode.displayName());
        if (target != sender) {
            sender.sendMessage("{green}Set {white}" + ChatText.escape(target.getName())
                    + "{green}'s game mode to {white}" + mode.displayName());
        }
    }
}
