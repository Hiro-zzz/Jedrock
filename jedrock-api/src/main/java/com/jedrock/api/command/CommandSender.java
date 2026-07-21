package com.jedrock.api.command;

/**
 * Whoever issued a command — an online {@link com.jedrock.api.player.Player} or the server console. A
 * command is written against this abstraction so the same command runs from chat and from the console;
 * only commands that genuinely need a player (a self location, an inventory) narrow to {@code Player}.
 *
 * <p>Permissions are deliberately simple: an <em>operator</em> (op) has every permission, and the console
 * is always an op. {@link #hasPermission} is what command gating checks; {@link #isOp} is the coarse
 * "is this an admin" flag most commands care about.
 */
public interface CommandSender {

    /** Display name — a player's username, or a fixed label like {@code "Console"} for the console. */
    String getName();

    /** Send a chat/system line back to the sender (rendered in its own edition, or printed to stdout). */
    void sendMessage(String message);

    /** {@code true} if this sender is a server operator. The console is always an op. */
    boolean isOp();

    /**
     * Whether this sender holds a permission node (e.g. {@code "jedrock.command.gamemode"}). An op holds
     * every node; the console is an op. A {@code null} or blank node is always granted (an unguarded
     * command).
     */
    boolean hasPermission(String permission);
}
