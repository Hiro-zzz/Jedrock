package com.jedrock.api;

/**
 * Jedrock entry point constants and version info.
 * Lightweight core: no static singletons by default.
 */
public final class Jedrock {

    public static final String NAME = "Jedrock";
    public static final String VERSION = "0.1.0";

    // Core philosophy markers
    public static final boolean LIGHTWEIGHT = true;
    public static final boolean ABSOLUTE_ABSTRACTION = true;
    public static final boolean LAZY_PARSING = true;

    // Supported versions (explicit, minimal surface)
    public static final String MCPE_VERSION = "1.1.5";
    public static final String JE_VERSION = "1.12.2";

    private Jedrock() {
        // Utility class
    }
}
