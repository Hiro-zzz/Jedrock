package com.jedrock.network;

import com.jedrock.api.config.PipelineSettings;

/**
 * The live {@link PipelineSettings}, installed once at boot and read from everywhere on the wire.
 *
 * <p>A holder rather than a constructor parameter, and that is a considered trade. These values are read
 * by static helpers on packet paths ({@code PacketGuard}, the batch inflater) that have no session, no
 * connection and no business growing a settings field just to see a number that is the same for every
 * client on the server. Threading them through would mean touching every call site to pass a value none
 * of them can vary — the definition of ceremony.
 *
 * <p>So: one volatile record reference, installed before the first listener binds, read as a plain field
 * access afterwards. Nothing rebinds it at runtime; the settings are a boot-time fact, exactly like the
 * constants they replaced.
 */
public final class Pipeline {

    private static volatile PipelineSettings active = PipelineSettings.defaults();

    private Pipeline() {}

    /** The settings in force. Never null — the compiled-in defaults until something installs a file's. */
    public static PipelineSettings get() {
        return active;
    }

    /** Install the loaded settings. Called once, by the server, before anything binds. */
    public static void install(PipelineSettings settings) {
        if (settings != null) {
            active = settings;
        }
    }
}
