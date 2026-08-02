package com.jedrock.core.command;

import com.jedrock.api.Jedrock;
import com.jedrock.api.ServerStatus;
import com.jedrock.api.command.CommandSender;
import com.jedrock.api.world.World;
import com.jedrock.core.JedrockServer;

import java.util.List;
import java.util.Locale;

/**
 * {@code /about} — what am I connected to, and how is it holding up.
 *
 * <p>Everything here is already available in pieces: {@code /tps} has the health line, {@code /list} has
 * the roster, the console has the version banner nobody who joined later saw. This is those pieces on one
 * screen, which is what somebody asking "what is this server" actually wants — and, unlike the console
 * banner, it is reachable from inside the game on every edition.
 *
 * <p>Deliberately readable by anyone. There is nothing here an operator would mind a player knowing:
 * a version, a tick rate and how many worlds there are. Address and configuration stay in
 * {@code /playerinfo} and the config file, where they belong.
 */
public final class AboutCommand implements Command {

    @Override
    public String name() {
        return "about";
    }

    @Override
    public List<String> aliases() {
        return List.of("version", "ver");
    }

    @Override
    public String description() {
        return "What this server is, and how it is doing";
    }

    @Override
    public String usage() {
        return "/about";
    }

    @Override
    public String permission() {
        return null; // no node: this is the one command a stranger should be able to run
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        ServerStatus status = server.getStatus();

        sender.sendMessage("{gold}Jedrock {white}" + Jedrock.VERSION
                + " {dark_gray}— a cross-platform Minecraft server written from scratch");
        sender.sendMessage("{gray}Speaks: {white}Java 1.8 & 1.12.2 {dark_gray}· {white}Bedrock 1.1.5 & 0.14"
                + " {dark_gray}(one world, all four)");

        sender.sendMessage("{gray}Health: {white}" + String.format(Locale.ROOT,
                "%.1f TPS {dark_gray}· {white}%.2f ms/tick {dark_gray}(peak %.2f)",
                status.tps(), status.mspt(), status.peakMspt()));
        sender.sendMessage("{gray}Memory: {white}" + status.usedMemoryBytes() / (1024 * 1024) + "/"
                + status.maxMemoryBytes() / (1024 * 1024) + " MB {dark_gray}· {gray}Uptime: {white}"
                + uptime(status.uptimeMillis()));

        sender.sendMessage("{gray}Players: {white}" + status.onlinePlayers()
                + "{dark_gray}/{white}" + server.getMaxPlayers()
                + " {dark_gray}· {gray}Worlds: {white}" + worlds(server)
                + " {dark_gray}· {gray}Plugins: {white}" + server.getPlugins().pluginNames().size());

        // The one thing worth saying out loud, because it is the difference between "it is broken" and
        // "that client is": 1.1.5 has known faults that are not this server's.
        sender.sendMessage("{dark_gray}Bedrock 1.1.5 is experimental — see the project's Known limits.");
    }

    /** World names, or a count once there are too many to read in a chat line. */
    private static String worlds(JedrockServer server) {
        List<String> names = server.getWorlds().stream().map(World::getName).toList();
        if (names.size() > 4) {
            return names.size() + "";
        }
        return String.join(", ", names);
    }

    private static String uptime(long millis) {
        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes > 0 ? minutes + "m " + (seconds % 60) + "s" : seconds + "s";
    }
}
