package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.Player;
import com.jedrock.api.region.Region;
import com.jedrock.api.world.Location;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.moderation.Durations;
import com.jedrock.core.moderation.Punishment;
import com.jedrock.utils.text.ChatText;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * {@code /playerinfo <player>} — everything this server knows about somebody, in one screen.
 *
 * <p>It answers a question that otherwise takes five commands: who are they, what are they connected
 * with, where are they, what rights do they hold, and is anything already against them. That last part is
 * why it sits with the moderation commands rather than with the informational ones — the moment an
 * operator wants this is the moment before they decide what to do about a player.
 *
 * <p>Works on somebody who is offline, and says less about them, because less is known: a name, when they
 * were last here, their rights, and their punishments. Nothing is invented to fill the gap.
 */
public final class PlayerInfoCommand implements Command {

    @Override
    public String name() {
        return "playerinfo";
    }

    @Override
    public List<String> aliases() {
        return List.of("whois", "pinfo");
    }

    @Override
    public String description() {
        return "Everything known about a player";
    }

    @Override
    public String usage() {
        return "/playerinfo <player>";
    }

    @Override
    public String permission() {
        return "jedrock.command.playerinfo";
    }

    @Override
    public List<CommandArg> arguments() {
        return List.of(CommandArg.required("player", ArgType.WORD));
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("{red}Usage: " + usage());
            return;
        }
        String name = args[0];
        Optional<? extends Player> online = server.getPlayer(name);
        String shown = online.map(Player::getName).orElse(name);

        sender.sendMessage("{gold}=== {white}" + ChatText.escape(shown) + " {gold}===");
        if (online.isPresent()) {
            describeOnline(server, sender, online.get());
        } else {
            describeOffline(server, sender, name);
        }
        describeRights(server, sender, shown);
        describePunishments(server, sender, shown, online.orElse(null));
    }

    private void describeOnline(JedrockServer server, CommandSender sender, Player player) {
        Location at = player.getLocation();
        line(sender, "Status", "{green}online");
        line(sender, "UUID", player.getUniqueId().toString());
        line(sender, "Edition", player.getConnection().getProtocolVersion().getVersionName()
                + " {dark_gray}(" + player.getAddress() + ")");
        line(sender, "Ping", player.getPing() < 0 ? "unknown" : player.getPing() + " ms");
        line(sender, "World", player.getWorld().getName() + " {dark_gray}"
                + String.format(Locale.ROOT, "%.1f, %.1f, %.1f", at.x(), at.y(), at.z()));
        line(sender, "Mode", player.getGameMode().name().toLowerCase(Locale.ROOT)
                + " {dark_gray}· {white}" + player.getHealth() + "/" + player.getMaxHealth() + " hp");
        String displayName = player.getDisplayName();
        if (!displayName.equals(player.getName())) {
            line(sender, "Shown as", displayName);
        }
        int held = player.getHeldItem();
        if (held != 0) {
            line(sender, "Holding", "state " + held);
        }
        // Regions are per world and only exist on a server that made some, so this stays silent otherwise.
        List<Region> regions = server.getRegions().at(player.getWorld(), at.x(), at.y(), at.z());
        if (!regions.isEmpty()) {
            line(sender, "Regions", regions.stream().map(Region::getName)
                    .reduce((a, b) -> a + ", " + b).orElse(""));
        }
    }

    private void describeOffline(JedrockServer server, CommandSender sender, String name) {
        line(sender, "Status", "{gray}offline");
        long when = server.getModeration().getLastSeen().lastSeen(name);
        line(sender, "Last seen", when <= 0 ? "never (by this server)"
                : Durations.describe(System.currentTimeMillis() - when) + " ago");
    }

    private void describeRights(JedrockServer server, CommandSender sender, String name) {
        boolean op = server.getOpList().isOp(name);
        Set<String> groups = server.getPermissions().userGroups(name);
        Set<String> nodes = server.getPermissions().userPermissions(name);
        line(sender, "Operator", op ? "{green}yes" : "{gray}no");
        if (!groups.isEmpty()) {
            line(sender, "Groups", String.join(", ", groups));
        }
        if (!nodes.isEmpty()) {
            line(sender, "Own nodes", String.join(", ", nodes));
        }
        if (server.getModeration().getWhitelist().isEnabled()) {
            line(sender, "Whitelisted",
                    server.getModeration().getWhitelist().contains(name) ? "{green}yes" : "{red}no");
        }
    }

    private void describePunishments(JedrockServer server, CommandSender sender, String name,
                                     Player online) {
        long now = System.currentTimeMillis();
        boolean any = false;
        for (Punishment.Kind kind : Punishment.Kind.values()) {
            // A name never carries an ip ban; the address they are on might, so that one is looked up by
            // address and only when they are here to have one.
            String target = kind == Punishment.Kind.IP_BAN
                    ? (online == null ? null
                            : com.jedrock.core.moderation.Moderation.hostOf(online.getAddress()))
                    : name;
            Punishment found = target == null ? null
                    : server.getModeration().getPunishments().find(kind, target, now);
            if (found == null) {
                continue;
            }
            any = true;
            line(sender, kind.shortName(), "{red}" + (found.isPermanent() ? "permanent"
                    : Durations.describe(found.remaining(now)) + " left")
                    + " {gray}by " + ChatText.escape(found.issuer())
                    + " {dark_gray}" + ChatText.escape(found.reason()));
        }
        if (!any) {
            line(sender, "Punishments", "{gray}none");
        }
    }

    private static void line(CommandSender sender, String label, String value) {
        sender.sendMessage("{gray}" + label + ": {white}" + value);
    }
}
