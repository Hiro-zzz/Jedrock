package com.jedrock.core.rcon;

import com.jedrock.api.command.CommandSender;
import com.jedrock.utils.text.ChatText;

/**
 * A remote administrator as a {@link CommandSender}: the third thing that can run a command, beside a
 * player in chat and the console on stdin.
 *
 * <p>The only difference from the console is where the words go. A command doesn't print — it calls
 * {@link #sendMessage}, and this collects those lines instead of logging them, because RCON has to hand
 * the whole reply back inside one response packet. Markup is rendered and the legacy {@code §} codes
 * stripped, exactly as the console does it: an RCON client is a terminal, and a terminal shows a colour
 * code as garbage.
 *
 * <p>Like the console it is an operator holding every permission — it authenticated with the server's
 * password, which is the same claim the console makes by being on the machine.
 *
 * <p>One instance per executed command; not thread-safe and has no reason to be.
 */
public final class RconSender implements CommandSender {

    private final StringBuilder collected = new StringBuilder();

    @Override
    public String getName() {
        return "Rcon";
    }

    @Override
    public void sendMessage(String message) {
        if (message == null) {
            return;
        }
        if (!collected.isEmpty()) {
            collected.append('\n');
        }
        collected.append(ChatText.stripCodes(ChatText.toLegacy(message)));
    }

    @Override
    public boolean isOp() {
        return true;
    }

    @Override
    public boolean hasPermission(String permission) {
        return true;
    }

    /** Everything the command said, as one reply body. */
    public String output() {
        return collected.toString();
    }
}
