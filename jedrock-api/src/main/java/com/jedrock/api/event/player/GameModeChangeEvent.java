package com.jedrock.api.event.player;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;

/**
 * Fired when a player's game mode is about to change (via {@code /gamemode} or the API). <b>Cancellable</b>:
 * cancelling leaves the player in their current mode. The {@link #getNewGameMode() target mode} is mutable,
 * so a listener can redirect the switch rather than veto it outright.
 */
public class GameModeChangeEvent extends CancellablePlayerEvent {

    private final GameMode from;
    private GameMode newGameMode;

    public GameModeChangeEvent(Player player, GameMode from, GameMode newGameMode) {
        super(player);
        this.from = from;
        this.newGameMode = newGameMode;
    }

    /** The mode the player is in now. */
    public GameMode getFrom() {
        return from;
    }

    /** The mode they are about to switch to — change it to redirect the switch. */
    public GameMode getNewGameMode() {
        return newGameMode;
    }

    public void setNewGameMode(GameMode newGameMode) {
        this.newGameMode = newGameMode;
    }
}
