package com.jedrock.gameloop;

/**
 * Something that participates in the main server tick.
 */
public interface Tickable {

    /**
     * Called every server tick.
     *
     * @param currentTick monotonically increasing tick number
     */
    void tick(long currentTick);
}
