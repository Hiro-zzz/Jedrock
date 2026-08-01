package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * A player has closed the container they had open. Not cancellable: by the time this fires the window is
 * already gone from their screen, and no edition here has a way to put one back that the player did not
 * ask for.
 *
 * <p>The useful moment is that a chest's contents have settled — this is where a shop reconciles what was
 * taken, or a script saves what somebody arranged.
 */
public final class ContainerCloseEvent extends PlayerEvent {

    private final ContainerType type;

    public ContainerCloseEvent(Player player, ContainerType type) {
        super(player);
        this.type = type;
    }

    /** Whether the thing that closed was a world chest or a script's menu. */
    public ContainerType getType() {
        return type;
    }
}
