package com.jedrock.core;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.Player;
import com.jedrock.core.command.ConsoleSender;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.Debug;
import com.jedrock.utils.JLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * The console surface: live status / TPS, the player list, toggling extended debug, broadcasting,
 * kicking, and a graceful stop — plus every in-game command, run as an operator.
 *
 * <p>Two things reach it. <b>stdin</b>, on a daemon thread reading one line at a time, so the server
 * still runs headless if stdin is closed (e.g. under a service manager); and <b>RCON</b>, which is the
 * same commands over a socket. That is why {@link #execute} takes the sender rather than logging
 * directly: the console's sender prints to the terminal, RCON's collects the same lines into a reply.
 */
final class ConsoleCommands implements Runnable {

    private static final JLogger LOGGER = JLogger.getLogger("Console");

    private final JedrockServer server;

    ConsoleCommands(JedrockServer server) {
        this.server = server;
    }

    void start() {
        Thread thread = new Thread(this, "Jedrock-Console");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while (server.isRunning() && (line = reader.readLine()) != null) {
                try {
                    Runnable deferred = execute(line.trim(), ConsoleSender.INSTANCE);
                    if (deferred != null) {
                        deferred.run(); // nothing to deliver first on stdin — it has already printed
                    }
                } catch (RuntimeException e) {
                    LOGGER.error("Command failed: " + e);
                }
            }
        } catch (IOException ignored) {
            // stdin closed (headless run) — the console simply stops, the server keeps running.
        }
    }

    /**
     * Run one console line as {@code sender}, whose {@code sendMessage} is where the output goes.
     *
     * @return work the caller must run <em>after</em> it has delivered that output, or {@code null}.
     *         Today that is only {@code stop}: over RCON the reply has to reach the socket before the
     *         process ends, and a shutdown that runs inline would take the answer with it.
     */
    Runnable execute(String line, CommandSender sender) {
        if (line.isEmpty()) {
            return null;
        }
        int space = line.indexOf(' ');
        String cmd = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        String args = space < 0 ? "" : line.substring(space + 1).trim();

        switch (cmd) {
            case "status", "tps" -> sender.sendMessage(server.getStatus().summary());
            case "players", "list" -> printPlayers(sender);
            case "debug" -> {
                if (!args.isEmpty()) {
                    Debug.configure(args);
                }
                sender.sendMessage("debug: " + Debug.describe()
                        + (args.isEmpty() ? "  (usage: debug all | off | <tags e.g. pe,chunk>)" : ""));
            }
            case "gc" -> {
                System.gc();
                sender.sendMessage("requested GC — " + server.getStatus().summary());
            }
            case "say", "broadcast" -> say(args, sender);
            case "kick" -> kick(args, sender);
            case "kill" -> affect(args, "kill", server::kill, "killed", "is in creative (no damage)", sender);
            case "heal" -> affect(args, "heal", server::heal, "healed", "is in creative (no health)", sender);
            case "plugins", "pl" -> plugins(args, sender);
            case "help", "?" -> sender.sendMessage("console: status | players | say <msg> | "
                    + "kick <player> [reason] | kill <player> | heal <player> | "
                    + "plugins [reload] | debug [all|off|<tags>] | gc | stop | help  "
                    + "(plus every in-game command, e.g. op / gamemode / tp — run as an operator)");
            case "stop", "shutdown", "exit" -> {
                sender.sendMessage("stopping...");
                return () -> {
                    server.shutdown();
                    System.exit(0);
                };
            }
            // Not a console-native command — run it through the in-game registry as an operator, so
            // op / deop / gamemode / spawn / … work from here too (a player-only command like /spawn is
            // rejected with a clear message).
            default -> server.getCommandManager().dispatch(sender, "/" + line);
        }
        return null;
    }

    /** {@code plugins} lists loaded script plugins; {@code plugins reload} hot-reloads changed files now. */
    private void plugins(String args, CommandSender sender) {
        if (args.equalsIgnoreCase("reload")) {
            server.getPlugins().reloadChanged();
            sender.sendMessage("reloaded changed plugins");
        }
        List<String> names = server.getPlugins().pluginNames();
        sender.sendMessage(names.isEmpty() ? "no plugins loaded" : "plugins (" + names.size() + "): "
                + String.join(", ", names));
        var storage = server.getPlugins().storage();
        sender.sendMessage("storage: " + storage.totalKeys() + " key(s) in " + storage.buckets().size()
                + " bucket(s)" + (storage.isDirty() ? " (unsaved changes)" : ""));
    }

    /** {@code say <message>} — broadcast a server line to every online player, like the in-game {@code /say}. */
    private void say(String message, CommandSender sender) {
        if (message.isEmpty()) {
            sender.sendMessage("usage: say <message>");
            return;
        }
        server.broadcast("{light_purple}[Server] {reset}" + message);
        LOGGER.info("[say] " + message); // the broadcast is a server event, so it is logged either way
    }

    /**
     * Shared body for the single-player ops ({@code kill}, {@code heal}): look the player up by name, run
     * {@code action} on them, and log the outcome. {@code action} returns {@code false} when it doesn't
     * apply (a creative player), in which case {@code notApplicable} explains why.
     */
    private void affect(String name, String verb, java.util.function.Predicate<CorePlayer> action,
                        String pastTense, String notApplicable, CommandSender sender) {
        if (name.isEmpty()) {
            sender.sendMessage("usage: " + verb + " <player>");
            return;
        }
        var found = server.getPlayer(name);
        if (found.isEmpty() || !(found.get() instanceof CorePlayer target)) {
            sender.sendMessage("no such player: " + name);
            return;
        }
        if (action.test(target)) {
            sender.sendMessage(pastTense + " " + target.getName());
        } else {
            sender.sendMessage(target.getName() + " " + notApplicable);
        }
    }

    /** {@code kick <player> [reason]} — disconnect a player by name (case-insensitive), with an optional reason. */
    private void kick(String args, CommandSender sender) {
        if (args.isEmpty()) {
            sender.sendMessage("usage: kick <player> [reason]");
            return;
        }
        int space = args.indexOf(' ');
        String name = space < 0 ? args : args.substring(0, space);
        String reason = space < 0 ? "Kicked by an operator" : args.substring(space + 1).trim();
        var found = server.getPlayer(name);
        if (found.isEmpty()) {
            sender.sendMessage("no such player: " + name);
            return;
        }
        Player target = found.get();
        target.kick(reason);
        sender.sendMessage("kicked " + target.getName() + " (" + reason + ")");
    }

    private void printPlayers(CommandSender sender) {
        var players = server.getPlayers();
        StringBuilder sb = new StringBuilder("players (").append(players.size()).append("):");
        for (Player p : players) {
            sb.append("\n  ").append(p.getName())
                    .append(" [").append(p.getConnection().getProtocolVersion().getVersionName()).append("]");
        }
        sender.sendMessage(sb.toString());
    }
}
