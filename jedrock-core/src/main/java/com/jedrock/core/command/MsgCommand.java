package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.text.ChatText;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * {@code /msg <player> <message>} (aliases {@code /w}, {@code /tell}, {@code /pm}) — send a private
 * message to one player, cross-edition. The message body is left raw so chat markup renders; the sender's
 * name is escaped. Both parties see the line (the sender gets a confirmation copy).
 */
public final class MsgCommand implements Command {

    @Override
    public String name() {
        return "msg";
    }

    @Override
    public List<String> aliases() {
        return List.of("w", "tell", "pm");
    }

    @Override
    public String description() {
        return "Send a private message";
    }

    @Override
    public String usage() {
        return "/msg <player> <message>";
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        Optional<Player> found = server.getPlayer(args[0]);
        if (found.isEmpty() || !(found.get() instanceof CorePlayer target)) {
            sender.sendMessage("{red}Player not found: {white}" + ChatText.escape(args[0]));
            return;
        }
        if (target == sender) {
            sender.sendMessage("{red}You can't message yourself.");
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        target.sendMessage("{light_purple}[{white}" + ChatText.escape(sender.getName())
                + "{light_purple} → you] {reset}" + message);
        sender.sendMessage("{light_purple}[you → {white}" + ChatText.escape(target.getName())
                + "{light_purple}] {reset}" + message);
    }
}
