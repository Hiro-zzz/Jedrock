package com.jedrock.core.config;

import com.jedrock.api.config.ServerProperties;
import com.jedrock.utils.JLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Loads {@link ServerProperties} from {@code jedrock.properties} in the working directory.
 *
 * <p>Behaviour, mirroring a vanilla server:
 * <ul>
 *   <li>On first run the file does not exist — the bundled template is written to disk so the
 *       operator has something to edit, and defaults are used for this run.</li>
 *   <li>On subsequent runs the file is read; any missing or malformed key falls back to its
 *       {@link ServerProperties#defaults() default} (with a warning), never a hard failure.</li>
 *   <li>A matching {@code -Dkey=value} system property overrides the file — handy for tests and
 *       one-off ops tweaks without editing the file.</li>
 * </ul>
 *
 * <p>The world seed accepts {@code random} (or blank) for a fresh random world, a numeric value
 * used verbatim, or any other text hashed to a seed — the same rule Minecraft uses.
 */
public final class JedrockConfig {

    private static final JLogger LOGGER = JLogger.getLogger("Config");
    private static final String FILE_NAME = "jedrock.properties";

    private JedrockConfig() {}

    /** Load the config, creating the template on first run. Never throws — worst case, all defaults. */
    public static ServerProperties load() {
        Path path = Path.of(FILE_NAME);
        Properties props = new Properties();

        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                props.load(reader);
                LOGGER.info("Loaded configuration from " + path.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.warn("Failed to read " + FILE_NAME + " (" + e.getMessage() + "); using defaults");
            }
        } else {
            writeDefaultTemplate(path);
        }

        return parse(props);
    }

    /** Package-visible for tests: turn a loaded {@link Properties} into typed settings. */
    static ServerProperties parse(Properties file) {
        ServerProperties def = ServerProperties.defaults();
        return new ServerProperties(
                str(file, "server.name", def.name()),
                str(file, "server.bind", def.bindHost()),
                port(file, "server.port.je", def.javaPort()),
                port(file, "server.port.pe", def.bedrockPort()),
                positiveInt(file, "server.max-players", def.maxPlayers()),
                str(file, "server.motd", def.motd()),
                seed(file, "world.seed", def.seed()),
                positiveInt(file, "game.tick-rate", def.tickRate()),
                positiveInt(file, "game.view-distance", def.viewDistance())
        );
    }

    /** File value, unless a matching {@code -Dkey} system property overrides it. */
    private static String raw(Properties file, String key) {
        String override = System.getProperty(key);
        if (override != null) {
            return override;
        }
        return file.getProperty(key);
    }

    private static String str(Properties file, String key, String def) {
        String v = raw(file, key);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private static int positiveInt(Properties file, String key, int def) {
        String v = raw(file, key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            int parsed = Integer.parseInt(v.trim());
            if (parsed <= 0) {
                LOGGER.warn(key + " must be positive (got " + parsed + "); using default " + def);
                return def;
            }
            return parsed;
        } catch (NumberFormatException e) {
            LOGGER.warn(key + " is not a number ('" + v.trim() + "'); using default " + def);
            return def;
        }
    }

    private static int port(Properties file, String key, int def) {
        int p = positiveInt(file, key, def);
        if (p > 65535) {
            LOGGER.warn(key + " out of range (" + p + "); using default " + def);
            return def;
        }
        return p;
    }

    /** {@code random}/blank → the fixed default; numeric → verbatim; other text → hashed. */
    private static long seed(Properties file, String key, long def) {
        String v = raw(file, key);
        if (v == null || v.isBlank()) {
            return def;
        }
        String s = v.trim();
        if (s.equalsIgnoreCase("random")) {
            long random = new java.util.Random().nextLong();
            LOGGER.info("world.seed=random → generated seed " + random);
            return random;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return s.hashCode(); // Minecraft's rule for non-numeric seeds
        }
    }

    private static void writeDefaultTemplate(Path path) {
        try (InputStream in = JedrockConfig.class.getResourceAsStream("/" + FILE_NAME)) {
            if (in == null) {
                LOGGER.warn("No bundled " + FILE_NAME + " template found; running with defaults");
                return;
            }
            Files.copy(in, path);
            LOGGER.info("No " + FILE_NAME + " found — wrote a default one to " + path.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.warn("Could not write default " + FILE_NAME + " (" + e.getMessage() + "); using defaults");
        }
    }
}
