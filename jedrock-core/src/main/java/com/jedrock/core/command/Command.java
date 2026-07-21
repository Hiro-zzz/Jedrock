package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
import com.jedrock.core.JedrockServer;

import java.util.List;

/**
 * One in-game slash command. Kept deliberately tiny — a name, help text and an {@code execute} — so a
 * command is a small stateless class the {@link CommandManager} dispatches to. Commands run on the
 * caller's thread (a player's network thread, or the console thread); they only touch thread-safe server
 * state.
 *
 * <p>The sender is a {@link CommandSender}, so a command works from chat <em>and</em> the console. A
 * command that needs a real player (its location, its inventory) declares {@link #playerOnly()} and the
 * manager rejects a console caller before {@code execute} runs. A command guarded by {@link #permission()}
 * only runs for a sender that holds that node (an op holds every node; the console is an op).
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

    /** {@code true} if only a player may run this (needs a self location / inventory); the console can't. */
    default boolean playerOnly() {
        return false;
    }

    /**
     * Permission node required to run this, or {@code null} for an unguarded command anyone may use. An op
     * (and the console) holds every node. Admin commands return a {@code "jedrock.command.<name>"} node.
     */
    default String permission() {
        return null;
    }

    /**
     * Run the command. {@code args} is everything after the label, split on whitespace (never null,
     * possibly empty). Report problems back with {@link CommandSender#sendMessage} — throwing is caught
     * and shown to the sender, so it never takes the connection down. When {@link #playerOnly()} is
     * {@code true} the sender is guaranteed to be a {@link com.jedrock.api.player.Player}.
     */
    void execute(JedrockServer server, CommandSender sender, String[] args);
}
