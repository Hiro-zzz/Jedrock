package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.api.player.Player;
import com.jedrock.core.JedrockServer;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.text.ChatText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers in-game slash commands and dispatches a chat line that starts with {@code /} to the right
 * one. Registration order is preserved (a {@link LinkedHashMap}) so {@code /help} lists commands in a
 * stable order. Labels (name + aliases) are matched case-insensitively.
 *
 * <p><b>Threading.</b> Registration is not confined to startup: the plugin watcher hot-reloads a script on
 * its own daemon thread and re-registers that script's commands, while players dispatch and tab-complete
 * from their network threads and the console from its own. So the lookup index is concurrent, and the
 * ordered set is guarded and only ever handed out as a snapshot — iterating the live map for {@code /help}
 * while a reload rewrote it is exactly the kind of concurrent modification that fails on the rare tick.
 */
public final class CommandManager {

    private static final JLogger LOGGER = JLogger.getLogger(CommandManager.class);

    private final JedrockServer server;
    /** Registration-ordered set of distinct commands (for /help). Guarded by itself; see {@link #commands()}. */
    private final Map<String, Command> commands = new LinkedHashMap<>();
    /** Every label (name + aliases) → its command, for lookup. Read from every dispatching thread. */
    private final Map<String, Command> byLabel = new ConcurrentHashMap<>();

    public CommandManager(JedrockServer server) {
        this.server = server;
    }

    /** Register a command under its name and every alias. A later registration overrides a clash. */
    public void register(Command command) {
        String name = command.name().toLowerCase(Locale.ROOT);
        synchronized (commands) {
            commands.put(name, command);
        }
        byLabel.put(name, command);
        for (String alias : command.aliases()) {
            byLabel.put(alias.toLowerCase(Locale.ROOT), command);
        }
    }

    /**
     * Unregister a command — drop its name and every alias, but only where they still point at <em>this</em>
     * command, so a later registration that reused a label isn't clobbered. Used to tear down a script's
     * commands on unload / hot-reload. A no-op if it isn't the registered command.
     */
    public void unregister(Command command) {
        String name = command.name().toLowerCase(Locale.ROOT);
        synchronized (commands) {
            commands.remove(name, command);
        }
        byLabel.remove(name, command);
        for (String alias : command.aliases()) {
            byLabel.remove(alias.toLowerCase(Locale.ROOT), command);
        }
    }

    /**
     * The distinct registered commands, in registration order (for {@code /help}). A snapshot, not the live
     * view: a hot-reload can rewrite the set from another thread mid-iteration, and {@code /help} is cold
     * enough that a copy costs nothing worth counting.
     */
    public Collection<Command> commands() {
        synchronized (commands) {
            return List.copyOf(commands.values());
        }
    }

    /** Look up a command by any of its labels (case-insensitive), or {@code null}. */
    public Command get(String label) {
        return byLabel.get(label.toLowerCase(Locale.ROOT));
    }

    /**
     * Handle a raw chat line known to start with {@code /}. Splits off the label and arguments, finds
     * the command and runs it, reporting an unknown command or a thrown error back to the sender. A
     * command never reaches the public chat.
     */
    public void dispatch(CommandSender sender, String rawLine) {
        String line = rawLine.substring(1).strip(); // drop the leading '/'
        if (line.isEmpty()) {
            return; // a lone "/" — nothing to do
        }
        String[] parts = line.split("\\s+");
        String label = parts[0];
        Command command = get(label);
        if (command == null) {
            sender.sendMessage("{red}Unknown command: {white}/" + ChatText.escape(label)
                    + "{red}. Try {white}/help{red}.");
            return;
        }
        // Gate on permission, then on player-only, before running — a clear message for each.
        if (command.permission() != null && !sender.hasPermission(command.permission())) {
            sender.sendMessage("{red}You don't have permission to use {white}/" + ChatText.escape(label));
            return;
        }
        if (command.playerOnly() && !(sender instanceof Player)) {
            sender.sendMessage("{red}/" + ChatText.escape(label) + " can only be run by a player.");
            return;
        }
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        try {
            command.execute(server, sender, args);
        } catch (RuntimeException e) {
            LOGGER.warn("Command /" + label + " from " + sender.getName() + " failed: " + e);
            sender.sendMessage("{red}Command failed: " + ChatText.escape(String.valueOf(e.getMessage())));
        }
    }

    /**
     * Tab-completion for a partially typed command line ({@code rawLine} includes the leading {@code /}).
     * The single entry point the wire calls; the result is a list of completions for the token under the
     * cursor.
     *
     * <ul>
     *   <li>Still on the command name (no space yet) → matching command labels, each with its {@code /},
     *       and only ones the sender may actually run (permission-gated, like {@code /help}).</li>
     *   <li>Past the name → the command's own {@link Command#complete} for the argument being typed,
     *       again gated on permission and player-only status so a console or an unprivileged sender is
     *       offered nothing it couldn't use.</li>
     * </ul>
     *
     * <p>A trailing space matters: {@code "/tp "} is completing the (empty) first argument, not the name,
     * so the split preserves that empty token.
     */
    public List<String> complete(CommandSender sender, String rawLine) {
        String line = rawLine.startsWith("/") ? rawLine.substring(1) : rawLine;
        // Split keeping a trailing empty token — the partial the user is about to type after a space.
        String[] tokens = line.split("\\s+", -1);

        if (tokens.length <= 1) {
            // Completing the command name itself. Suggest labels the sender may run, each with its slash.
            String partial = tokens.length == 0 ? "" : tokens[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (Command command : commands()) {
                if (!canUse(sender, command)) {
                    continue;
                }
                for (String label : labelsOf(command)) {
                    if (label.startsWith(partial)) {
                        out.add("/" + label);
                    }
                }
            }
            return out;
        }

        Command command = get(tokens[0]);
        if (command == null || !canUse(sender, command)) {
            return List.of(); // unknown, or the sender couldn't run it — offer nothing
        }
        String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);
        return command.complete(server, sender, args);
    }

    /** Whether {@code sender} could actually run {@code command} — permission and player-only, as dispatch checks. */
    private static boolean canUse(CommandSender sender, Command command) {
        if (command.permission() != null && !sender.hasPermission(command.permission())) {
            return false;
        }
        return !command.playerOnly() || sender instanceof Player;
    }

    /** A command's name followed by its aliases, all lower-cased — the labels it answers to. */
    private static List<String> labelsOf(Command command) {
        List<String> labels = new ArrayList<>();
        labels.add(command.name().toLowerCase(Locale.ROOT));
        for (String alias : command.aliases()) {
            labels.add(alias.toLowerCase(Locale.ROOT));
        }
        return labels;
    }
}
