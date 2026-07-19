package com.jedrock.api.event.player;

import com.jedrock.api.player.Player;

/**
 * Fired when a player sends a chat message (not a command — a line starting with {@code /} never reaches
 * here). <b>Cancellable</b>: cancelling suppresses the broadcast entirely.
 *
 * <p>Both the raw {@link #getMessage() message} and the {@link #getFormat() format} it's wrapped in are
 * mutable, so a listener can edit what was said or restyle how it's shown. The format is a template with a
 * single {@code %s} where the message goes; both are authored in the unified chat markup.
 */
public class PlayerChatEvent extends CancellablePlayerEvent {

    /** The default chat layout: {@code <name> message}. A listener may replace it wholesale. */
    public static final String DEFAULT_FORMAT = "{gray}<{aqua}%name%{gray}>{reset} %s";

    private String message;
    private String format = DEFAULT_FORMAT;

    public PlayerChatEvent(Player player, String message) {
        super(player);
        this.message = message;
    }

    /** What the player typed (raw markup preserved — rendering it is a documented feature). */
    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * The template the message is wrapped in: {@code %name%} is replaced with the sender's name and
     * {@code %s} with the message. Change it to restyle the line or to drop the name entirely.
     */
    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }
}
