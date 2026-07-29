package com.jedrock.core.data;

import com.jedrock.api.config.ServerProperties;
import com.jedrock.utils.JLogger;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Picks the {@link DataStore} the configuration asked for, and falls back to files when it can't have it.
 *
 * <p>The fallback is the whole point of the class. A database that is down, a driver that wasn't
 * downloaded, a url with a typo — none of those are reasons for a Minecraft server not to start, and all
 * of them are reasons to say something an operator can act on. So a failed backend is a loud warning
 * naming what to do about it, and a server that comes up on files.
 */
public final class DataStores {

    private static final JLogger LOGGER = JLogger.getLogger("Storage");

    /** Where an operator drops a JDBC driver jar. Not created unless something looks for it. */
    public static final String LIBS_FOLDER = "libs";

    private DataStores() {}

    /**
     * Open the configured store.
     *
     * @param home    the server folder — {@code libs/} is looked for beside it
     * @param data    the folder the file backend writes in
     * @param config  the {@code storage.*} settings
     */
    public static DataStore open(Path home, Path data, ServerProperties.Storage config) {
        if (!config.backend().equalsIgnoreCase("jdbc")) {
            return new FlatFileStore(data);
        }
        if (config.url() == null || config.url().isBlank()) {
            LOGGER.warn("storage.backend=jdbc but storage.url is blank — using files in " + data);
            return new FlatFileStore(data);
        }
        try {
            Driver driver = loadDriver(home, config);
            Properties credentials = new Properties();
            if (!config.user().isBlank()) {
                credentials.setProperty("user", config.user());
                credentials.setProperty("password", config.password());
            }
            DataStore store = new JdbcStore(driver, config.url(), credentials);
            LOGGER.info("Storage backend: " + safe(config.url()));
            return store;
        } catch (Exception e) {
            LOGGER.warn("Could not open the JDBC storage backend (" + safe(config.url()) + "): "
                    + e.getMessage() + " — falling back to files in " + data + ". "
                    + "The driver jar goes in " + home.resolve(LIBS_FOLDER).toAbsolutePath()
                    + " (for SQLite that is sqlite-jdbc from org.xerial); storage.driver names its class.");
            return new FlatFileStore(data);
        }
    }

    /**
     * Load the JDBC driver from {@code libs/}, and hold on to the instance.
     *
     * <p>Not {@code DriverManager.getConnection}: that only sees drivers loaded by the application
     * classloader, and this one deliberately isn't — it comes from a jar the operator added after the
     * server was built. Asking DriverManager for the url would report "no suitable driver" with the driver
     * sitting right there, which is the confusing failure this comment exists to prevent.
     */
    private static Driver loadDriver(Path home, ServerProperties.Storage config) throws Exception {
        Path libs = home.resolve(LIBS_FOLDER);
        List<URL> jars = new ArrayList<>();
        if (Files.isDirectory(libs)) {
            try (Stream<Path> files = Files.list(libs)) {
                for (Path jar : files.filter(p -> p.getFileName().toString().endsWith(".jar")).toList()) {
                    jars.add(jar.toUri().toURL());
                }
            } catch (IOException e) {
                LOGGER.warn("Could not list " + libs.toAbsolutePath() + ": " + e);
            }
        }
        // The application classloader is the parent, so a driver that IS bundled (someone's own build)
        // still resolves and libs/ is simply empty.
        ClassLoader loader = new URLClassLoader(jars.toArray(new URL[0]), DataStores.class.getClassLoader());
        Class<?> type;
        try {
            type = Class.forName(config.driver(), true, loader);
        } catch (ClassNotFoundException e) {
            // The bare class name is what this throws, and on its own it reads like a typo rather than a
            // missing file. Say which of the two it is.
            throw new ClassNotFoundException("the driver class " + config.driver() + " is in none of the "
                    + jars.size() + " jar(s) in libs/");
        }
        return (Driver) type.getDeclaredConstructor().newInstance();
    }

    /** A url without its password, for logging — jdbc urls often carry one in the query string. */
    private static String safe(String url) {
        return url.replaceAll("(?i)([?&;](password|pwd)=)[^&;]*", "$1***");
    }
}
