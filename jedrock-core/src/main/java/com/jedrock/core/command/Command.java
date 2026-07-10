package com.jedrock.core.command;

import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;

import java.util.List;

/**
 * One in-game slash command. Kept deliberately tiny — a name, help text and an {@code execute} — so a
 * command is a small stateless class the {@link CommandManager} dispatches to. Commands run on the
 * caller's network thread (where chat is decoded); they only touch thread-safe server state.
 */
public interface Command {

    /** Primary label, without the slash, lower-case (e.g. {@code "gamemode"}). */
    String name();

    /** Extra labels that also invoke this command (e.g. {@code "gm"} for gamemode). */
    default List<String> aliases() {
        return List.of();
    }

    /** One-line description shown by {@code /help}. */
    String description();

    /** Usage hint shown by {@code /help} and on bad input (e.g. {@code "/gamemode <mode> [player]"}). */
    String usage();

    /**
     * Run the command. {@code args} is everything after the label, split on whitespace (never null,
     * possibly empty). Report problems back with {@link CorePlayer#sendMessage} — throwing is caught
     * and shown to the sender, so it never takes the connection down.
     */
    void execute(JedrockServer server, CorePlayer sender, String[] args);
}
