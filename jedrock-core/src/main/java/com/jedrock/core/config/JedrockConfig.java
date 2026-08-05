package com.jedrock.core.config;

import com.jedrock.api.config.ServerProperties;
import com.jedrock.api.player.GameMode;
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
    static final String FILE_NAME = "jedrock.properties";

    private JedrockConfig() {}

    /** Load the config from the working directory — what a server started with no arguments does. */
    public static ServerProperties load() {
        return load(Path.of("."));
    }

    /** Load the config, creating the template on first run. Never throws — worst case, all defaults. */
    public static ServerProperties load(Path root) {
        Path path = root.resolve(FILE_NAME);
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
                positiveInt(file, "game.view-distance", def.viewDistance()),
                bool(file, "judge.enabled", def.judgeEnabled()),
                positiveDouble(file, "judge.max-reach", def.maxReach()),
                positiveDouble(file, "judge.max-move-delta", def.maxMoveDelta()),
                port(file, "server.port.pe014", def.bedrock014Port()),
                bool(file, "pe014.enabled", def.bedrock014Enabled()),
                gameMode(file, "game.default-gamemode", def.defaultGameMode()),
                anyInt(file, "pe.sidebar.raise", def.peSidebarRaise()),
                anyInt(file, "pe.sidebar.shift", def.peSidebarShift()),
                bool(file, "player.remember-world", def.rememberWorld()),
                new ServerProperties.Paths(
                        folder(file, "paths.worlds", def.paths().worlds()),
                        folder(file, "paths.plugins", def.paths().plugins()),
                        folder(file, "paths.logs", def.paths().logs()),
                        folder(file, "paths.data", def.paths().data())),
                new ServerProperties.Worlds(
                        str(file, "world.default-name", def.worlds().defaultName()),
                        str(file, "world.default-template", def.worlds().defaultTemplate()),
                        bool(file, "world.load-all", def.worlds().loadAll()),
                        nonNegativeLong(file, "world.autosave-seconds", def.worlds().autosaveSeconds())),
                new ServerProperties.Plugins(
                        bool(file, "plugins.enabled", def.plugins().enabled()),
                        bool(file, "plugins.hot-reload", def.plugins().hotReload()),
                        positiveLong(file, "plugins.reload-millis", def.plugins().reloadMillis()),
                        new ServerProperties.Http(
                                bool(file, "plugins.http.enabled", def.plugins().http().enabled()),
                                str(file, "plugins.http.allowed-hosts", def.plugins().http().allowedHosts()),
                                positiveLong(file, "plugins.http.timeout-millis",
                                        def.plugins().http().timeoutMillis()),
                                // A ceiling that can be raised without limit is not a ceiling; 64 MiB is
                                // already far past any webhook reply worth reading into memory.
                                boundedInt(file, "plugins.http.max-response-bytes",
                                        def.plugins().http().maxResponseBytes(), 1024, 64 * 1024 * 1024),
                                boundedInt(file, "plugins.http.max-concurrent",
                                        def.plugins().http().maxConcurrent(), 1, 64))),
                new ServerProperties.Logging(
                        bool(file, "logging.to-file", def.logging().toFile()),
                        nonNegativeInt(file, "logging.keep-files", def.logging().keepFiles()),
                        str(file, "logging.debug", def.logging().debug()),
                        nonNegativeLong(file, "logging.status-seconds", def.logging().statusSeconds())),
                new ServerProperties.Rcon(
                        bool(file, "rcon.enabled", def.rcon().enabled()),
                        str(file, "rcon.bind", def.rcon().bind()),
                        port(file, "rcon.port", def.rcon().port()),
                        // Not str(): a password is taken exactly as written, spaces and all, and a blank
                        // one must stay blank rather than falling back to anything.
                        raw(file, "rcon.password") == null ? def.rcon().password() : raw(file, "rcon.password")),
                new ServerProperties.Storage(
                        str(file, "storage.backend", def.storage().backend()),
                        str(file, "storage.url", def.storage().url()),
                        str(file, "storage.driver", def.storage().driver()),
                        str(file, "storage.user", def.storage().user()),
                        raw(file, "storage.password") == null
                                ? def.storage().password() : raw(file, "storage.password"))
        );
    }

    /**
     * A folder name, which is a path component and not free text: the config is how a script-free operator
     * points the server at another disk, and {@code ../..} is not a folder name. An absolute path is
     * allowed (that is the point of moving worlds to another volume); a traversal out of the root is not.
     */
    private static String folder(Properties file, String key, String def) {
        String v = raw(file, key);
        if (v == null || v.isBlank()) {
            return def;
        }
        String name = v.trim();
        if (name.contains("..")) {
            LOGGER.warn(key + " may not walk out of the server folder ('" + name + "'); using '" + def + "'");
            return def;
        }
        return name;
    }

    /** Parse a game mode (name / shorthand / id); an unrecognised value falls back with a warning. */
    private static GameMode gameMode(Properties file, String key, GameMode def) {
        String v = raw(file, key);
        if (v == null || v.isBlank()) {
            return def;
        }
        GameMode parsed = GameMode.fromString(v);
        if (parsed == null) {
            LOGGER.warn(key + " is not a game mode ('" + v.trim() + "'); using default " + def.displayName());
            return def;
        }
        return parsed;
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

    /**
     * A number that has to sit inside a range, for the settings that are ceilings. An obeyed nonsense
     * value here is a limit somebody else chose, which is the same reasoning the pipeline guards use —
     * so a value outside the range is refused and the default kept, with a line saying so.
     */
    private static int boundedInt(Properties file, String key, int def, int min, int max) {
        String v = raw(file, key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            int parsed = Integer.parseInt(v.trim());
            if (parsed < min || parsed > max) {
                LOGGER.warn(key + " must be between " + min + " and " + max + " (got " + parsed
                        + "); using default " + def);
                return def;
            }
            return parsed;
        } catch (NumberFormatException e) {
            LOGGER.warn(key + " is not a number ('" + v.trim() + "'); using default " + def);
            return def;
        }
    }

    /** Like {@link #positiveInt} but zero is a legal answer — usually meaning "off". */
    private static int nonNegativeInt(Properties file, String key, int def) {
        long parsed = nonNegativeLong(file, key, def);
        return (int) Math.min(parsed, Integer.MAX_VALUE);
    }

    /** A duration or a count where 0 means "never" / "none", so only a negative value is wrong. */
    private static long nonNegativeLong(Properties file, String key, long def) {
        String v = raw(file, key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            long parsed = Long.parseLong(v.trim());
            if (parsed < 0) {
                LOGGER.warn(key + " must not be negative (got " + parsed + "); using default " + def);
                return def;
            }
            return parsed;
        } catch (NumberFormatException e) {
            LOGGER.warn(key + " is not a number ('" + v.trim() + "'); using default " + def);
            return def;
        }
    }

    /** An interval that would be meaningless at zero (a poll that never polls is just "off" elsewhere). */
    private static long positiveLong(Properties file, String key, long def) {
        long parsed = nonNegativeLong(file, key, def);
        if (parsed == 0) {
            LOGGER.warn(key + " must be greater than zero; using default " + def);
            return def;
        }
        return parsed;
    }

    /** Like {@link #positiveInt} but signed: a knob whose direction is carried by the sign. */
    private static int anyInt(Properties file, String key, int def) {
        String v = raw(file, key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn(key + " is not a whole number ('" + v.trim() + "'); using default " + def);
            return def;
        }
    }

    private static boolean bool(Properties file, String key, boolean def) {
        String v = raw(file, key);
        return v == null || v.isBlank() ? def : Boolean.parseBoolean(v.trim());
    }

    private static double positiveDouble(Properties file, String key, double def) {
        String v = raw(file, key);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            double parsed = Double.parseDouble(v.trim());
            if (parsed <= 0 || !Double.isFinite(parsed)) {
                LOGGER.warn(key + " must be a positive number (got " + v.trim() + "); using default " + def);
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
