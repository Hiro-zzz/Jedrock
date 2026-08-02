package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.moderation.Punishment;
import com.jedrock.utils.text.ChatText;

import java.util.List;

/**
 * {@code /pardon <player|address> [ban|ip|mute]} — lift a punishment. With no kind named, lifts <b>all</b>
 * of them.
 *
 * <p>One command rather than an unban, an unmute and an un-ip-ban, because the thing an operator wants at
 * the moment they type it is "this person is fine now", and having to remember which of three lists they
 * are on is how somebody stays half-punished for a month. Naming a kind narrows it for the case where
 * lifting the mute but keeping the ban is genuinely what was meant.
 */
public final class PardonCommand implements Command {

    @Override
    public String name() {
        return "pardon";
    }

    @Override
    public List<String> aliases() {
        return List.of("unban", "unmute");
    }

    @Override
    public String description() {
        return "Lift a player's punishments (all of them, or one kind)";
    }

    @Override
    public String usage() {
        return "/pardon <player|address> [ban|ip|mute]";
    }

    @Override
    public String permission() {
        return "jedrock.command.pardon";
    }

    @Override
    public List<CommandArg> arguments() {
        return List.of(CommandArg.required("target", ArgType.WORD),
                CommandArg.optional("kind", ArgType.choice("ban", "ip", "mute")));
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        String target = args[0];
        if (args.length > 1) {
            Punishment.Kind kind = Punishment.Kind.byName(args[1]);
            if (kind == null) {
                sender.sendMessage("{red}'" + ChatText.escape(args[1])
                        + "' is not a kind — one of {white}ban{red}, {white}ip{red}, {white}mute{red}.");
                return;
            }
            boolean lifted = server.getModeration().getPunishments().remove(kind, target);
            sender.sendMessage(lifted
                    ? "{green}Lifted the " + kind.shortName() + " on {white}" + ChatText.escape(target)
                    : "{yellow}" + ChatText.escape(target) + " had no " + kind.shortName() + ".");
            return;
        }
        int lifted = server.getModeration().getPunishments().pardon(target);
        sender.sendMessage(lifted > 0
                ? "{green}Pardoned {white}" + ChatText.escape(target) + "{green} ({white}" + lifted
                        + "{green} punishment(s) lifted)."
                : "{yellow}" + ChatText.escape(target) + " had nothing against them.");
    }
}
