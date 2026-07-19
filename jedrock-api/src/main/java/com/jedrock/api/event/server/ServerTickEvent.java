package com.jedrock.api.event.server;

import com.jedrock.api.event.Event;

/**
 * Fired once per server tick (the loop runs at 20 TPS). The heartbeat a script hangs timers and periodic
 * work on — "every 20 ticks do X" — without polling. Not cancellable.
 *
 * <p>It rides the core game-loop thread, so a listener must be quick; slow work stalls the whole tick. And
 * like every hot path, the core only posts it when something is actually listening, so an idle server pays
 * nothing for the event existing.
 */
public final class ServerTickEvent implements Event {

    private final long tick;

    public ServerTickEvent(long tick) {
        this.tick = tick;
    }

    /** The monotonic tick number — {@code tick % 20 == 0} is once a second. */
    public long getTick() {
        return tick;
    }
}
