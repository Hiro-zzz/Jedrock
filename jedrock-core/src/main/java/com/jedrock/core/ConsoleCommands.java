package com.jedrock.core;

import com.jedrock.api.player.Player;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.Debug;
import com.jedrock.utils.JLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Minimal stdin console for the running server: live status / TPS, the player list, toggling the
 * extended-debug subsystem, and a graceful stop. Runs on a daemon thread and reads one command per
 * line, so the server still runs headless if stdin is closed (e.g. under a service manager).
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
                    handle(line.trim());
                } catch (RuntimeException e) {
                    LOGGER.error("Command failed: " + e);
                }
            }
        } catch (IOException ignored) {
            // stdin closed (headless run) — the console simply stops, the server keeps running.
        }
    }

    private void handle(String line) {
        if (line.isEmpty()) {
            return;
        }
        int space = line.indexOf(' ');
        String cmd = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        String args = space < 0 ? "" : line.substring(space + 1).trim();

        switch (cmd) {
            case "status", "tps" -> LOGGER.info(server.getStatus().summary());
            case "players", "list" -> printPlayers();
            case "debug" -> {
                if (!args.isEmpty()) {
                    Debug.configure(args);
                }
                LOGGER.info("debug: " + Debug.describe()
                        + (args.isEmpty() ? "  (usage: debug all | off | <tags e.g. pe,chunk>)" : ""));
            }
            case "gc" -> {
                System.gc();
                LOGGER.info("requested GC — " + server.getStatus().summary());
            }
            case "say", "broadcast" -> say(args);
            case "kick" -> kick(args);
            case "kill" -> affect(args, "kill", server::kill, "killed", "is in creative (no damage)");
            case "heal" -> affect(args, "heal", server::heal, "healed", "is in creative (no health)");
            case "help", "?" -> LOGGER.info("commands: status | players | say <msg> | "
                    + "kick <player> [reason] | kill <player> | heal <player> | "
                    + "debug [all|off|<tags>] | gc | stop | help");
            case "stop", "shutdown", "exit" -> {
                LOGGER.info("stopping...");
                server.shutdown();
                System.exit(0);
            }
            default -> LOGGER.info("unknown command '" + cmd + "' — try 'help'");
        }
    }

    /** {@code say <message>} — broadcast a server line to every online player, like the in-game {@code /say}. */
    private void say(String message) {
        if (message.isEmpty()) {
            LOGGER.info("usage: say <message>");
            return;
        }
        server.broadcast("{light_purple}[Server] {reset}" + message);
        LOGGER.info("[say] " + message);
    }

    /**
     * Shared body for the single-player ops ({@code kill}, {@code heal}): look the player up by name, run
     * {@code action} on them, and log the outcome. {@code action} returns {@code false} when it doesn't
     * apply (a creative player), in which case {@code notApplicable} explains why.
     */
    private void affect(String name, String verb, java.util.function.Predicate<CorePlayer> action,
                        String pastTense, String notApplicable) {
        if (name.isEmpty()) {
            LOGGER.info("usage: " + verb + " <player>");
            return;
        }
        var found = server.getPlayer(name);
        if (found.isEmpty() || !(found.get() instanceof CorePlayer target)) {
            LOGGER.info("no such player: " + name);
            return;
        }
        if (action.test(target)) {
            LOGGER.info(pastTense + " " + target.getName());
        } else {
            LOGGER.info(target.getName() + " " + notApplicable);
        }
    }

    /** {@code kick <player> [reason]} — disconnect a player by name (case-insensitive), with an optional reason. */
    private void kick(String args) {
        if (args.isEmpty()) {
            LOGGER.info("usage: kick <player> [reason]");
            return;
        }
        int space = args.indexOf(' ');
        String name = space < 0 ? args : args.substring(0, space);
        String reason = space < 0 ? "Kicked by an operator" : args.substring(space + 1).trim();
        var found = server.getPlayer(name);
        if (found.isEmpty()) {
            LOGGER.info("no such player: " + name);
            return;
        }
        Player target = found.get();
        target.kick(reason);
        LOGGER.info("kicked " + target.getName() + " (" + reason + ")");
    }

    private void printPlayers() {
        var players = server.getPlayers();
        StringBuilder sb = new StringBuilder("players (").append(players.size()).append("):");
        for (Player p : players) {
            sb.append("\n  ").append(p.getName())
                    .append(" [").append(p.getConnection().getProtocolVersion().getVersionName()).append("]");
        }
        LOGGER.info(sb.toString());
    }
}
