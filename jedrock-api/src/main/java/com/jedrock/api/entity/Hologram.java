package com.jedrock.api.entity;

import com.jedrock.api.world.Location;

import java.util.List;

/**
 * A <b>hologram</b>: lines of text floating in the world, anchored to nothing. The purest illusion in the
 * server — there is no entity here in any meaningful sense, only a name tag with the body taken away.
 *
 * <p>Each line is carried by its own invisible entity, stacked downwards from the hologram's location, and
 * every edition plays the trick with whatever it has: Java hangs the text on an invisible marker armor
 * stand, Bedrock (which has no armor stand in the legacy eras) on an invisible item entity — the same
 * hack PocketMine's floating text uses. The {@code api} never learns which.
 *
 * <p>Lines are authored in the edition-agnostic chat markup ({@code {color}} tags plus Markdown), so one
 * string renders identically on a phone and a PC.
 */
public interface Hologram extends Entity {

    /** The lines currently shown, top to bottom. */
    List<String> getLines();

    /** Replace every line; the hologram re-renders for every viewer. Empty removes all text. */
    void setLines(String... lines);

    /**
     * Replace one line, leaving the others alone.
     *
     * @throws IndexOutOfBoundsException if {@code index} is outside the current lines
     */
    void setLine(int index, String text);

    /** Move the hologram (and its whole stack of lines) and relay it to every viewer. */
    void teleport(Location to);
}
