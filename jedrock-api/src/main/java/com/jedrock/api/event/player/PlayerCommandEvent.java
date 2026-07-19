package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired when a player runs a slash command, before it is dispatched. <b>Cancellable</b>: cancelling stops
 * the command from running (a listener that fully handled it should cancel so the core doesn't also try).
 *
 * <p>The {@link #getCommand() command} is the whole line <em>without</em> the leading slash and is mutable,
 * so a listener can rewrite arguments or redirect to a different command before it reaches the dispatcher.
 */
public class PlayerCommandEvent extends CancellablePlayerEvent {

    private String command;

    public PlayerCommandEvent(Player player, String command) {
        super(player);
        this.command = command;
    }

    /** The command line without the leading slash (e.g. {@code "gamemode creative"}). */
    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }
}
