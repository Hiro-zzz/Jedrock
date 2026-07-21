package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.text.ChatText;

/**
 * The server console as a {@link CommandSender}, so the same commands players run from chat also run from
 * stdin. The console is always an operator and holds every permission (it's how the first {@code /op} gets
 * granted on a fresh server). Its {@code sendMessage} renders the markup and then strips the legacy
 * {@code §} codes, since a terminal can't show them — the console reads plain text.
 */
public final class ConsoleSender implements CommandSender {

    /** A single shared instance — the console is stateless. */
    public static final ConsoleSender INSTANCE = new ConsoleSender();

    private static final JLogger LOGGER = JLogger.getLogger("Console");

    private ConsoleSender() {}

    @Override
    public String getName() {
        return "Console";
    }

    @Override
    public void sendMessage(String message) {
        LOGGER.info(ChatText.stripCodes(ChatText.toLegacy(message)));
    }

    @Override
    public boolean isOp() {
        return true;
    }

    @Override
    public boolean hasPermission(String permission) {
        return true;
    }
}
