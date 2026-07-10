package com.jedrock.core.command;

import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.text.ChatText;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registers in-game slash commands and dispatches a chat line that starts with {@code /} to the right
 * one. Registration order is preserved (a {@link LinkedHashMap}) so {@code /help} lists commands in a
 * stable order. Labels (name + aliases) are matched case-insensitively.
 */
public final class CommandManager {

    private static final JLogger LOGGER = JLogger.getLogger(CommandManager.class);

    private final JedrockServer server;
    /** Registration-ordered set of distinct commands (for /help). */
    private final Map<String, Command> commands = new LinkedHashMap<>();
    /** Every label (name + aliases) → its command, for lookup. */
    private final Map<String, Command> byLabel = new LinkedHashMap<>();

    public CommandManager(JedrockServer server) {
        this.server = server;
    }

    /** Register a command under its name and every alias. A later registration overrides a clash. */
    public void register(Command command) {
        commands.put(command.name().toLowerCase(Locale.ROOT), command);
        byLabel.put(command.name().toLowerCase(Locale.ROOT), command);
        for (String alias : command.aliases()) {
            byLabel.put(alias.toLowerCase(Locale.ROOT), command);
        }
    }

    /** The distinct registered commands, in registration order (for {@code /help}). */
    public Collection<Command> commands() {
        return commands.values();
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
    public void dispatch(CorePlayer sender, String rawLine) {
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
        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        try {
            command.execute(server, sender, args);
        } catch (RuntimeException e) {
            LOGGER.warn("Command /" + label + " from " + sender.getName() + " failed: " + e);
            sender.sendMessage("{red}Command failed: " + ChatText.escape(String.valueOf(e.getMessage())));
        }
    }
}
