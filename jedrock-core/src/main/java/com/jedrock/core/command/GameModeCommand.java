package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.List;

/**
 * {@code /gamemode <mode> [player]} (alias {@code /gm}) — switch a player's game mode live. The mode
 * accepts a name, a one-letter shorthand or a numeric id (see {@link GameMode#fromString}); with no
 * player it targets the sender. The switch flips the client's HUD and flight ability on the wire (MCPE
 * 0.14 is the one exception — it applies on next join and says so).
 *
 * <p>Declared as typed arguments, so the core parses the mode and the optional player (and rejects a bad
 * mode) before {@link #run}, and tab-completion offers the four mode names and the online roster.
 */
public final class GameModeCommand extends ArgCommand {

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
    public List<CommandArg> arguments() {
        return List.of(
                CommandArg.required("mode", ArgType.GAME_MODE),
                CommandArg.optional("player", ArgType.PLAYER));
    }

    @Override
    public String permission() {
        return "jedrock.command.gamemode";
    }

    @Override
    protected void run(JedrockServer server, CommandSender sender, CommandContext ctx) {
        GameMode mode = ctx.getGameMode("mode");

        CorePlayer target;
        if (ctx.has("player")) {
            Player named = ctx.getPlayer("player");
            if (!(named instanceof CorePlayer cp)) {
                sender.sendMessage("{red}Player not found: {white}" + ChatText.escape(named.getName()));
                return;
            }
            target = cp;
        } else if (sender instanceof CorePlayer self) {
            target = self;
        } else {
            sender.sendMessage("{red}From the console, name a player: {white}" + usage());
            return;
        }

        // persists the choice + pushes the live switch to the client; a listener may veto it.
        if (!server.setGameMode(target, mode)) {
            sender.sendMessage("{red}The game mode change was cancelled.");
            return;
        }
        target.sendMessage("{green}Your game mode is now {white}" + mode.displayName());
        if (target != sender) {
            sender.sendMessage("{green}Set {white}" + ChatText.escape(target.getName())
                    + "{green}'s game mode to {white}" + mode.displayName());
        }
    }
}
