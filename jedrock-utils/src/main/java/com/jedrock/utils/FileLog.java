package com.jedrock.utils;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Console output, also written to {@code logs/latest.log}.
 *
 * <p>A {@link JLogger.LoggerProvider} rather than a logging framework, for the reason every other choice
 * in this project was made: a framework would bring a configuration language, a class-path scanner and a
 * dependency to do what one synchronized writer does. What it gives up is what a server actually rarely
 * wants — per-package levels, appenders, async batching. What it keeps is the thing you always want at
 * 3am: the run you just had, on disk, with timestamps.
 *
 * <p><b>Rotation is per run, not per size.</b> On start, an existing {@code latest.log} is renamed to the
 * time it was last written to and a fresh one begins, so "the log" is always the same filename and the
 * previous runs are beside it in order. Old files past {@code keepFiles} are deleted, oldest first — a
 * server left running for a year should not quietly fill a disk with its own history.
 *
 * <p>Writes are line-at-a-time and flushed, because a log whose last thirty lines were still in a buffer
 * when the process died is a log missing exactly the part you needed. The console keeps getting everything
 * it got before: this wraps the existing console logger rather than replacing it.
 */
public final class FileLog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String CURRENT = "latest.log";

    /** The one open file. Null until {@link #install}, and again after {@link #close}. */
    private static volatile Writer writer;
    private static final Object LOCK = new Object();

    private FileLog() {}

    /**
     * Start writing to {@code folder/latest.log} and route every logger through here.
     *
     * <p>Never throws: a folder that can't be written to means the console keeps working alone, which is
     * the right failure for a feature whose whole job is to record what already happened.
     *
     * @param folder    where the log files live
     * @param keepFiles how many previous runs to keep beside the current one; 0 keeps none
     */
    public static void install(Path folder, int keepFiles) {
        try {
            Files.createDirectories(folder);
            rotate(folder, keepFiles);
            Writer open = Files.newBufferedWriter(folder.resolve(CURRENT), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            synchronized (LOCK) {
                writer = open;
            }
            JLogger.setProvider(TeeLogger::new);
        } catch (IOException e) {
            // Reported through the console logger, which is still the active provider at this point.
            JLogger.getLogger("Log").warn("Could not open " + folder.resolve(CURRENT).toAbsolutePath()
                    + " (" + e + ") — logging to the console only");
        }
    }

    /** Close the log file and go back to console-only. Safe to call twice, or without an install. */
    public static void close() {
        synchronized (LOCK) {
            if (writer == null) {
                return;
            }
            try {
                writer.flush();
                writer.close();
            } catch (IOException ignored) {
                // Nothing useful to do while shutting down; the console already has every line.
            }
            writer = null;
        }
        JLogger.setProvider(null);
    }

    /** Move the last run out of the way and prune the history down to {@code keepFiles}. */
    private static void rotate(Path folder, int keepFiles) throws IOException {
        Path current = folder.resolve(CURRENT);
        if (Files.isRegularFile(current) && Files.size(current) > 0) {
            LocalDateTime when = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(current).toInstant(), java.time.ZoneId.systemDefault());
            Path archived = folder.resolve(FILE_STAMP.format(when) + ".log");
            for (int n = 2; Files.exists(archived); n++) {
                archived = folder.resolve(FILE_STAMP.format(when) + "-" + n + ".log");
            }
            Files.move(current, archived);
        }
        prune(folder, keepFiles);
    }

    private static void prune(Path folder, int keepFiles) throws IOException {
        List<Path> archived = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".log"))
                    .filter(p -> !p.getFileName().toString().equals(CURRENT))
                    .forEach(archived::add);
        }
        if (archived.size() <= keepFiles) {
            return;
        }
        archived.sort(Comparator.comparing(Path::getFileName)); // the names sort chronologically by design
        for (int i = 0; i < archived.size() - keepFiles; i++) {
            try {
                Files.deleteIfExists(archived.get(i));
            } catch (IOException ignored) {
                // A log we couldn't delete is not worth failing a startup over.
            }
        }
    }

    private static void write(String name, String level, String message, Throwable thrown) {
        Writer out = writer;
        if (out == null) {
            return;
        }
        StringBuilder line = new StringBuilder(64 + message.length())
                .append('[').append(STAMP.format(LocalDateTime.now())).append("] [")
                .append(name).append('/').append(level).append("] ").append(message).append(System.lineSeparator());
        if (thrown != null) {
            StringWriter trace = new StringWriter();
            thrown.printStackTrace(new PrintWriter(trace));
            line.append(trace);
        }
        synchronized (LOCK) {
            if (writer == null) {
                return;
            }
            try {
                writer.write(line.toString());
                writer.flush(); // a buffered line is a line you don't have when the process dies
            } catch (IOException ignored) {
                // Don't let a failing log file take down whatever was being logged about.
            }
        }
    }

    /** Everything the console logger did, plus a line in the file. */
    private static final class TeeLogger implements JLogger {

        private final String name;
        private final JLogger console;

        TeeLogger(String name) {
            this.name = name;
            this.console = new JLogger.SimpleConsoleLogger(name);
        }

        @Override
        public boolean isDebugEnabled() {
            return console.isDebugEnabled();
        }

        @Override
        public void debug(String message) {
            if (isDebugEnabled()) {
                console.debug(message);
                write(name, "DEBUG", message, null);
            }
        }

        @Override
        public void debug(Supplier<String> messageSupplier) {
            if (isDebugEnabled()) {
                String message = messageSupplier.get();
                console.debug(message);
                write(name, "DEBUG", message, null);
            }
        }

        @Override
        public void info(String message) {
            console.info(message);
            write(name, "INFO", message, null);
        }

        @Override
        public void warn(String message) {
            console.warn(message);
            write(name, "WARN", message, null);
        }

        @Override
        public void error(String message) {
            console.error(message);
            write(name, "ERROR", message, null);
        }

        @Override
        public void error(String message, Throwable throwable) {
            console.error(message, throwable);
            write(name, "ERROR", message, throwable);
        }
    }
}
