package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.moderation.Durations;
import com.jedrock.core.moderation.Punishment;
import com.jedrock.utils.text.ChatText;

import java.util.List;

/**
 * {@code /banlist [ban|ip|mute]} — what is currently in force. Everything by default.
 *
 * <p>Only live entries: something that has run out is not on the list, because the list is what the login
 * gate will actually do and not a history. (A history is a different feature and would need a different
 * file — this one is pruned as it is written.)
 */
public final class BanListCommand implements Command {

    /** More than this and it is a file to read, not a chat window to scroll. */
    private static final int MAX_SHOWN = 20;

    @Override
    public String name() {
        return "banlist";
    }

    @Override
    public String description() {
        return "List the punishments in force";
    }

    @Override
    public String usage() {
        return "/banlist [ban|ip|mute]";
    }

    @Override
    public String permission() {
        return "jedrock.command.banlist";
    }

    @Override
    public List<CommandArg> arguments() {
        return List.of(CommandArg.optional("kind", ArgType.choice("ban", "ip", "mute")));
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        Punishment.Kind only = args.length > 0 ? Punishment.Kind.byName(args[0]) : null;
        if (args.length > 0 && only == null) {
            sender.sendMessage("{red}'" + ChatText.escape(args[0])
                    + "' is not a kind — one of {white}ban{red}, {white}ip{red}, {white}mute{red}.");
            return;
        }
        long now = System.currentTimeMillis();
        int total = 0;
        for (Punishment.Kind kind : Punishment.Kind.values()) {
            if (only != null && kind != only) {
                continue;
            }
            List<Punishment> live = server.getModeration().getPunishments().list(kind, now);
            total += live.size();
            if (live.isEmpty()) {
                continue;
            }
            sender.sendMessage("{gold}" + kind.table() + " ({white}" + live.size() + "{gold}):");
            int shown = 0;
            for (Punishment p : live) {
                if (shown++ == MAX_SHOWN) {
                    sender.sendMessage("{gray}  …and " + (live.size() - MAX_SHOWN) + " more.");
                    break;
                }
                sender.sendMessage("{white}  " + ChatText.escape(p.target())
                        + " {gray}(" + (p.isPermanent() ? "permanent"
                                : Durations.describe(p.remaining(now)) + " left")
                        + ", by " + ChatText.escape(p.issuer()) + ") {dark_gray}"
                        + ChatText.escape(p.reason()));
            }
        }
        if (total == 0) {
            sender.sendMessage("{gray}Nothing in force.");
        }
    }
}
