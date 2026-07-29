package com.jedrock.core.config;

import com.jedrock.api.config.ServerProperties;
import com.jedrock.utils.JLogger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Every path the server writes to, decided once and asked for by name from then on.
 *
 * <p>The server used to scatter its files across whatever directory it was launched from: world folders,
 * {@code ops.txt}, {@code plugin-storage.jdb} and a {@code plugins/} folder all as siblings of the jar,
 * with each owner calling {@code Path.of("…")} for itself. That is fine for a project you run from an IDE
 * and unpleasant for one someone is handed. So there is a layout, it is made on first run, and it looks
 * like this:
 *
 * <pre>
 *   jedrock.jar
 *   jedrock.properties   the settings
 *   pipeline.yml         the network's own settings
 *   worlds/              one folder per world, each with its level.jdw
 *   plugins/             *.js, hot-reloaded
 *   logs/                latest.log, and the runs before it
 *   data/                ops.txt, permissions.txt, player-worlds.txt, plugin-storage.jdb
 * </pre>
 *
 * <p>The four folder names are configurable, because the one thing an operator reliably wants to move is
 * where the big files go (a world on another disk, logs on a volume that is backed up). The two config
 * files are not: they are how you say where everything else is, so they have to be somewhere already known.
 *
 * <p><b>An older install is migrated, not abandoned.</b> A flat layout from a previous version is walked
 * once at startup and its pieces are moved into place — a folder holding a {@code level.jdw} is a world and
 * goes to {@code worlds/}, the four known runtime files go to {@code data/}. Nothing is overwritten: if the
 * destination already exists the old file is left alone and said so, because two files claiming to be the
 * same world is a question for a person, not a heuristic.
 */
public final class ServerLayout {

    private static final JLogger LOGGER = JLogger.getLogger("Layout");

    /** The level file inside a world's folder — the marker that makes a folder a world, for migration. */
    private static final String LEVEL_FILE = "level.jdw";

    /** Runtime files that used to sit beside the jar and now live in {@code data/}. */
    private static final List<String> DATA_FILES =
            List.of("ops.txt", "permissions.txt", "player-worlds.txt", "plugin-storage.jdb");

    private final Path root;
    private final Path worlds;
    private final Path plugins;
    private final Path logs;
    private final Path data;

    private ServerLayout(Path root, Path worlds, Path plugins, Path logs, Path data) {
        this.root = root;
        this.worlds = worlds;
        this.plugins = plugins;
        this.logs = logs;
        this.data = data;
    }

    /**
     * Work out the layout under {@code root}, create anything missing, and migrate a flat install into it.
     * Called once, before any collaborator that owns a file is built.
     */
    public static ServerLayout prepare(Path root, ServerProperties config) {
        ServerProperties.Paths names = config.paths();
        ServerLayout layout = new ServerLayout(root,
                resolve(root, names.worlds(), "worlds"),
                resolve(root, names.plugins(), "plugins"),
                resolve(root, names.logs(), "logs"),
                resolve(root, names.data(), "data"));
        layout.createFolders();
        layout.migrateFlatInstall();
        return layout;
    }

    /** The layout for a test or an embedder that just wants the default names under {@code root}. */
    public static ServerLayout defaults(Path root) {
        return prepare(root, ServerProperties.defaults());
    }

    /** Where the process was launched (or {@code -Djedrock.home}) — the parent of everything below. */
    public Path root() {
        return root;
    }

    /** One folder per world, each holding its own {@code level.jdw}. */
    public Path worlds() {
        return worlds;
    }

    /** Script plugins: {@code *.js}, watched for edits. */
    public Path plugins() {
        return plugins;
    }

    /** {@code latest.log} and the runs before it. */
    public Path logs() {
        return logs;
    }

    /** The server's own bookkeeping — ops, permissions, remembered worlds, script storage. */
    public Path data() {
        return data;
    }

    /** A named file in {@code data/} — the only way anything in the core names one. */
    public Path dataFile(String name) {
        return data.resolve(name);
    }

    /** {@code jedrock.properties}, always at the root: it is what says where everything else is. */
    public Path configFile() {
        return root.resolve(JedrockConfig.FILE_NAME);
    }

    /** {@code pipeline.yml}, beside it, for the same reason. */
    public Path pipelineFile() {
        return root.resolve(PipelineConfig.FILE_NAME);
    }

    /** Where a world's folder is — {@code worlds/<name>}, and the reason nothing else spells that out. */
    public Path worldFolder(String name) {
        return worlds.resolve(name);
    }

    /** A blank or absurd folder name in the config falls back to the built-in one rather than failing. */
    private static Path resolve(Path root, String configured, String fallback) {
        String name = configured == null || configured.isBlank() ? fallback : configured.trim();
        Path path = root.resolve(name).normalize();
        if (path.equals(root.normalize())) {
            LOGGER.warn("paths." + fallback + " resolves to the server root itself; using '" + fallback + "'");
            return root.resolve(fallback);
        }
        return path;
    }

    private void createFolders() {
        for (Path dir : List.of(worlds, plugins, logs, data)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                LOGGER.error("Could not create " + dir.toAbsolutePath() + ": " + e
                        + " — anything that writes there will fail");
            }
        }
    }

    /**
     * Move a pre-layout install into place. A no-op the second time and on a fresh folder, so it costs one
     * directory listing at startup and is worth that to never strand someone's world.
     */
    private void migrateFlatInstall() {
        int moved = 0;
        moved += migrateWorldFolders();
        for (String name : DATA_FILES) {
            if (move(root.resolve(name), data.resolve(name), "file")) {
                moved++;
            }
        }
        if (moved > 0) {
            LOGGER.info("Moved " + moved + " item(s) from the old flat layout into " + worlds.getFileName()
                    + "/ and " + data.getFileName() + "/");
        }
    }

    /** Every folder beside the jar that holds a level file is a world from an older install. */
    private int migrateWorldFolders() {
        int moved = 0;
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path dir : dirs) {
                if (isOwnFolder(dir) || !Files.isRegularFile(dir.resolve(LEVEL_FILE))) {
                    continue;
                }
                if (move(dir, worlds.resolve(dir.getFileName().toString()), "world")) {
                    moved++;
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not scan " + root.toAbsolutePath() + " for worlds to migrate: " + e);
        }
        return moved;
    }

    /** One of the four folders this layout owns — never migrated into itself. */
    private boolean isOwnFolder(Path dir) {
        Path normalized = dir.normalize();
        return normalized.equals(worlds.normalize()) || normalized.equals(plugins.normalize())
                || normalized.equals(logs.normalize()) || normalized.equals(data.normalize())
                || dir.getFileName().toString().toLowerCase(Locale.ROOT).equals("target");
    }

    private static boolean move(Path from, Path to, String what) {
        if (!Files.exists(from)) {
            return false;
        }
        if (Files.exists(to)) {
            LOGGER.warn("Not migrating the old " + what + " " + from.toAbsolutePath() + ": "
                    + to.toAbsolutePath() + " already exists. Whichever is the real one, that's a decision "
                    + "for you — the old copy is left untouched.");
            return false;
        }
        try {
            Files.move(from, to);
            LOGGER.info("Migrated " + what + " " + from.getFileName() + " → " + to.toAbsolutePath());
            return true;
        } catch (IOException e) {
            LOGGER.warn("Could not migrate " + from.toAbsolutePath() + " to " + to.toAbsolutePath()
                    + ": " + e + " — it is left where it is");
            return false;
        }
    }

    /** A one-line summary for the startup banner. */
    public String summary() {
        return root.toAbsolutePath().normalize() + " (worlds/ plugins/ logs/ data/)";
    }
}
