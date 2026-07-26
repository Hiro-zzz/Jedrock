package com.jedrock.network.handler.je;

/**
 * A per-connection boss bar. The wire has one add and then incremental updates, so this remembers whether
 * the bar is up (and its last colour) and turns each {@code set} into the minimal packets: an add the
 * first time, then a health update, a title update, and a style update only when the colour actually
 * changed. The version-specific packet bytes are the {@link Wire}'s; the sequencing is here and testable.
 *
 * <p>Only 1.12.2 has a boss-bar packet, so that is the only handler that drives one; 1.8 and Bedrock let
 * the api default no-op stand.
 */
final class JeBossBar {

    /** The version-specific packet writes — one per boss-bar action. */
    interface Wire {
        void add(String title, float progress, int color);
        void updateHealth(float progress);
        void updateTitle(String title);
        void updateStyle(int color);
        void remove();
    }

    private final Wire wire;
    private boolean shown;
    private int color = -1;

    JeBossBar(Wire wire) {
        this.wire = wire;
    }

    /** Show or update the bar. First call adds it; later calls send only what changed. */
    void set(String title, float progress, int color) {
        if (!shown) {
            wire.add(title, progress, color);
            shown = true;
            this.color = color;
            return;
        }
        wire.updateHealth(progress);
        wire.updateTitle(title);
        if (color != this.color) {
            wire.updateStyle(color);
            this.color = color;
        }
    }

    /** Remove the bar if it's up. */
    void clear() {
        if (shown) {
            wire.remove();
            shown = false;
            color = -1;
        }
    }
}
