package com.jedrock.core.plugin;

/**
 * A custom, script-defined event — the payload of {@code events.emit(name, data)} handed to every
 * {@code events.on(name, ...)} listener registered for that name. Plugin-to-plugin messaging: one script
 * fires it, others react, and the firer can read back the (possibly mutated) {@link #getData() data} or see
 * whether a listener {@link #cancel() cancelled} it.
 *
 * <p>{@code data} is whatever the emitter passed — typically a JS object; listeners see the same reference,
 * so mutating its fields (or calling {@code cancel()}) communicates back to the emitter after {@code emit}
 * returns.
 */
public final class ScriptEvent {

    private final String name;
    private final Object data;
    private boolean cancelled;

    ScriptEvent(String name, Object data) {
        this.name = name;
        this.data = data;
    }

    /** The event name it was emitted under. */
    public String getName() {
        return name;
    }

    /** The payload passed to {@code emit} (may be null); the same reference every listener sees. */
    public Object getData() {
        return data;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Mark the event cancelled — a convention the emitter can check after {@code emit} returns. */
    public void cancel() {
        this.cancelled = true;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
