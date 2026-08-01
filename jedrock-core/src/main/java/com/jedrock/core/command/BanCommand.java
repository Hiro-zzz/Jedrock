package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.moderation.Durations;
import com.jedrock.core.moderation.Punishment;
import com.jedrock.utils.text.ChatText;

import java.util.List;
import java.util.Optional;

/**
 * {@code /ban <player> [duration] [reason]} — refuse someone at the login gate, and disconnect them if
 * they are standing there now.
 *
 * <p>The duration is optional and makes this the temporary-ban command too: {@code /ban alice 2d spam}.
 * A second argument that isn't a duration is read as the first word of the reason, so
 * {@code /ban alice spamming} still says what everybody expects — which is why {@link Durations} refuses
 * to guess a unit for a bare number.
 *
 * <p>Works on a name that has never connected. Bans are by name here; see {@link Punishment} for why, and
 * {@code /ban-ip} for the case that trade-off costs you.
 */
public final class BanCommand implements Command {

    @Override
    public String name() {
        return "ban";
    }

    @Override
    public String description() {
        return "Ban a player by name, optionally for a while";
    }

    @Override
    public String usage() {
        return "/ban <player> [duration] [reason]";
    }

    @Override
    public String permission() {
        return "jedrock.command.ban";
    }

    @Override
    public List<CommandArg> arguments() {
        return List.of(CommandArg.required("player", ArgType.PLAYER),
                CommandArg.optional("duration", ArgType.WORD),
                CommandArg.optional("reason", ArgType.GREEDY));
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        String target = args[0];
        Moderating.Split split = Moderating.splitDurationAndReason(args, 1);
        long expiresAt = split.durationMillis() == Durations.PERMANENT
                ? 0L : System.currentTimeMillis() + split.durationMillis();

        server.getModeration().getPunishments().add(new Punishment(Punishment.Kind.BAN, target,
                split.reason(), sender.getName(), System.currentTimeMillis(), expiresAt));

        sender.sendMessage("{green}Banned {white}" + ChatText.escape(target) + "{green} "
                + Moderating.forHowLong(split.durationMillis()) + "{gray} — " + ChatText.escape(split.reason()));
        // Already here: the gate only runs at login, so somebody banned mid-session has to be shown out.
        Optional<? extends Player> online = server.getPlayer(target);
        online.ifPresent(player -> player.kick("{red}You are banned.\n{gray}"
                + ChatText.escape(split.reason())));
        if (online.isEmpty()) {
            sender.sendMessage("{gray}They were not online; the ban applies when they try to join.");
        }
    }
}
