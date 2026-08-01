package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.moderation.Durations;
import com.jedrock.core.moderation.Moderation;
import com.jedrock.core.moderation.Punishment;
import com.jedrock.utils.text.ChatText;

import java.util.List;
import java.util.Optional;

/**
 * {@code /ban-ip <player|address> [duration] [reason]} — refuse an address rather than a name.
 *
 * <p>The answer to the one real weakness of a name ban on a server with no authentication: somebody
 * changes their name and comes back. Given an online player's name it bans the address they are connected
 * from; given an address it bans that.
 *
 * <p>It is the blunt instrument and should be treated as one. Addresses are shared by everybody in a
 * house and handed back to somebody else by the provider a week later, so this catches people it did not
 * mean to. That is a reason to prefer a plain ban, not a reason to have neither.
 */
public final class BanIpCommand implements Command {

    @Override
    public String name() {
        return "ban-ip";
    }

    @Override
    public List<String> aliases() {
        return List.of("banip");
    }

    @Override
    public String description() {
        return "Ban an address, or the address a player is on";
    }

    @Override
    public String usage() {
        return "/ban-ip <player|address> [duration] [reason]";
    }

    @Override
    public String permission() {
        return "jedrock.command.ban-ip";
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
        // A name only resolves to an address while they are connected — an address is not something this
        // server remembers about somebody who has left, and inventing one would be worse than saying so.
        Optional<? extends Player> online = server.getPlayer(args[0]);
        String address = online.map(p -> Moderation.hostOf(p.getAddress()))
                .orElseGet(() -> Moderation.hostOf(args[0]));
        if (address.isBlank()) {
            sender.sendMessage("{red}No address for '" + ChatText.escape(args[0])
                    + "' — they are offline, so ban the address itself.");
            return;
        }
        Moderating.Split split = Moderating.splitDurationAndReason(args, 1);
        long expiresAt = split.durationMillis() == Durations.PERMANENT
                ? 0L : System.currentTimeMillis() + split.durationMillis();

        server.getModeration().getPunishments().add(new Punishment(Punishment.Kind.IP_BAN, address,
                split.reason(), sender.getName(), System.currentTimeMillis(), expiresAt));

        sender.sendMessage("{green}Banned address {white}" + ChatText.escape(address) + "{green} "
                + Moderating.forHowLong(split.durationMillis()) + "{gray} — "
                + ChatText.escape(split.reason()));
        // Everybody currently on that address, not just the one named: that is what banning it means.
        int shown = 0;
        for (Player player : server.getPlayers()) {
            if (address.equalsIgnoreCase(Moderation.hostOf(player.getAddress()))) {
                player.kick("{red}Your address is banned.\n{gray}" + ChatText.escape(split.reason()));
                shown++;
            }
        }
        if (shown > 1) {
            sender.sendMessage("{gray}Disconnected " + shown + " players on that address.");
        }
    }
}
