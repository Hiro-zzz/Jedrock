package com.jedrock.core;

import com.jedrock.api.player.Player;
import com.jedrock.utils.Debug;
import com.jedrock.utils.JLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
        String cmd = (space < 0 ? line : line.substring(0, space)).toLowerCase();
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
            case "help", "?" -> LOGGER.info("commands: status | players | debug [all|off|<tags>] | gc | stop | help");
            case "stop", "shutdown", "exit" -> {
                LOGGER.info("stopping...");
                server.shutdown();
                System.exit(0);
            }
            default -> LOGGER.info("unknown command '" + cmd + "' — try 'help'");
        }
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
