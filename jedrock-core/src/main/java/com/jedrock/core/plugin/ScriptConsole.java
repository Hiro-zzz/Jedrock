package com.jedrock.core.plugin;

import com.jedrock.utils.JLogger;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The {@code console} object a script sees — {@code console.log(...)} / {@code .warn(...)} / {@code .error(...)},
 * routed to the server log prefixed with the plugin's name so its output is attributable. A familiar handle
 * so a script author doesn't have to learn a new logging call.
 */
public final class ScriptConsole {

    private static final JLogger LOGGER = JLogger.getLogger("plugin");

    private final String prefix;

    ScriptConsole(String pluginName) {
        this.prefix = "[" + pluginName + "] ";
    }

    public void log(Object... args) {
        LOGGER.info(prefix + join(args));
    }

    public void warn(Object... args) {
        LOGGER.warn(prefix + join(args));
    }

    public void error(Object... args) {
        LOGGER.error(prefix + join(args));
    }

    private static String join(Object... args) {
        if (args == null || args.length == 0) {
            return "";
        }
        return Arrays.stream(args).map(String::valueOf).collect(Collectors.joining(" "));
    }
}
