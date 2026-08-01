package com.jedrock.api.event.player;

/**
 * Which kind of box a player has open. There are only two here, and they differ in the one way a script
 * cares about: whether it is part of the world.
 */
public enum ContainerType {

    /** A chest block somebody placed: it has a position, and its contents are in the level file. */
    CHEST,

    /** A virtual menu a script opened: no block, no position, and nothing persisted. */
    MENU
}
