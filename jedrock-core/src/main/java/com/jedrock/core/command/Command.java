package com.jedrock.core.command;

import com.jedrock.api.Server;
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

    /**
     * The command's declared arguments, in order, or an empty list for a command that parses its own raw
     * {@code String[]} (the historical style). Declaring them lets the core parse the tokens once (see
     * {@link ArgCommand}) and drive {@linkplain #complete tab-completion} for free. Default: none.
     */
    default List<CommandArg> arguments() {
        return List.of();
    }

    /**
     * Tab-completion suggestions for the argument currently being typed. {@code args} is every token after
     * the label, with the <em>last</em> element being the partial token under the cursor (possibly empty
     * when the user just pressed space). Return bare tokens for that partial; the caller filters nothing
     * further.
     *
     * <p>The default derives suggestions from {@link #arguments()} — the online roster for a
     * {@link ArgType#PLAYER}, the literals of a {@link ArgType#choice}, and so on — so a command that
     * declares its arguments gets completion without writing any. A raw-args command may override this to
     * offer its own.
     */
    default List<String> complete(Server server, CommandSender sender, String[] args) {
        return CommandCompletion.forArguments(arguments(), server, sender, args);
    }

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
