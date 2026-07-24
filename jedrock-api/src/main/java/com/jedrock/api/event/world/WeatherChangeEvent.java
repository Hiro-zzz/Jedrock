package com.jedrock.api.event.world;

import com.jedrock.api.event.Cancellable;
import com.jedrock.api.event.Event;
import com.jedrock.api.world.Weather;
import com.jedrock.api.world.World;

/**
 * Fired before a world's sky changes — from {@code /weather}, from a script's {@code world.setWeather},
 * or from any other caller of {@link World#setWeather}. Every path funnels through the world itself, so a
 * listener sees them all.
 *
 * <p><b>Cancellable</b>: cancelling leaves the sky exactly as it was, and nothing is sent to any client —
 * the change is refused before it is drawn, so there is nothing to undo. A listener may also
 * {@linkplain #setTo redirect} it, turning a requested thunderstorm into rain.
 *
 * <p>Weather here is scenery, not simulation: no timer drives it, and this event only fires because
 * something asked for a change.
 */
public class WeatherChangeEvent implements Event, Cancellable {

    private final World world;
    private final Weather from;
    private Weather to;
    private boolean cancelled;

    public WeatherChangeEvent(World world, Weather from, Weather to) {
        this.world = world;
        this.from = from;
        this.to = to;
    }

    /** The world whose sky is about to change. */
    public World getWorld() {
        return world;
    }

    /** The weather in effect until now. */
    public Weather getFrom() {
        return from;
    }

    /** The weather being asked for — what listeners can redirect. */
    public Weather getTo() {
        return to;
    }

    /**
     * Redirect the change to a different weather. A {@code null} is ignored (cancel instead), and
     * redirecting to the current weather is the same as cancelling — the sky doesn't change, so nothing
     * is sent.
     */
    public void setTo(Weather to) {
        if (to != null) {
            this.to = to;
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
