package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.moderation.Durations;
import com.jedrock.core.moderation.Punishment;
import com.jedrock.utils.text.ChatText;

import java.util.List;

/**
 * {@code /mute <player> [duration] [reason]} — let somebody stay, but stop anything they say reaching
 * anybody else.
 *
 * <p>The punishment for the thing that is a nuisance rather than a danger, and the one worth having
 * because the alternative for a chat problem is a ban. Covers plain chat and every command that speaks
 * ({@code /me}, {@code /msg}, {@code /say}); only the muted player is told, since announcing a silencing
 * gives the argument the audience it was after.
 *
 * <p>{@code /pardon} lifts it, like any other punishment.
 */
public final class MuteCommand implements Command {

    @Override
    public String name() {
        return "mute";
    }

    @Override
    public String description() {
        return "Stop a player being heard, optionally for a while";
    }

    @Override
    public String usage() {
        return "/mute <player> [duration] [reason]";
    }

    @Override
    public String permission() {
        return "jedrock.command.mute";
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

        Punishment mute = new Punishment(Punishment.Kind.MUTE, target, split.reason(),
                sender.getName(), System.currentTimeMillis(), expiresAt);
        server.getModeration().getPunishments().add(mute);

        sender.sendMessage("{green}Muted {white}" + ChatText.escape(target) + "{green} "
                + Moderating.forHowLong(split.durationMillis()) + "{gray} — "
                + ChatText.escape(split.reason()));
        // Tell them, quietly. Finding out by talking into a void is worse than being told.
        server.getPlayer(target).ifPresent(
                player -> player.sendMessage(server.getModeration().muteNotice(mute)));
    }
}
