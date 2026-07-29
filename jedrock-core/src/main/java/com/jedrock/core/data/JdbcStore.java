package com.jedrock.core.data;

import com.jedrock.utils.JLogger;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * A {@link DataStore} on any JDBC database — SQLite for one server, MySQL or Postgres for several sharing
 * one account of who is where.
 *
 * <p>Every table is the same two columns, {@code k} and {@code v}, in a table named {@code jedrock_<name>}.
 * That is not a shortcut: this layer carries the server's small facts, and giving each of them a schema
 * would be inventing a migration problem for data that is a few dozen rows. Anything that genuinely wants
 * columns — a world's terrain, a plugin's own tables — should own its storage rather than borrow this.
 *
 * <p><b>The driver is never bundled.</b> It is loaded at runtime from the jars an operator dropped in
 * {@code libs/}, which is what keeps "few dependencies" true for everyone who doesn't want a database.
 * That has one consequence worth stating, because it is the thing that silently doesn't work otherwise:
 * {@code DriverManager} refuses drivers loaded by a classloader it doesn't own, so this holds the
 * {@link Driver} instance and calls {@link Driver#connect} directly instead of asking for a connection by
 * url.
 *
 * <p>A write replaces a whole table inside one transaction, so a crash mid-save leaves the previous
 * contents rather than half of the new ones.
 */
public final class JdbcStore implements DataStore {

    private static final JLogger LOGGER = JLogger.getLogger("Storage");

    private final Connection connection;
    private final String url;

    /**
     * @throws SQLException if the database can't be reached — the caller decides what that means, and
     *                      {@link DataStores} decides it means "fall back to files and say so loudly"
     */
    JdbcStore(Driver driver, String url, Properties credentials) throws SQLException {
        Connection open = driver.connect(url, credentials);
        if (open == null) {
            // A driver returns null for a url it doesn't recognise, which is its way of saying "not mine".
            throw new SQLException("the driver does not handle " + url + " — is it the right driver for it?");
        }
        this.connection = open;
        this.url = url;
        this.connection.setAutoCommit(true);
    }

    @Override
    public String describe() {
        return url; // urls in a config may carry a password, so callers pass one that doesn't
    }

    @Override
    public synchronized Map<String, String> load(String table) {
        Map<String, String> rows = new LinkedHashMap<>();
        String name = tableName(table);
        try {
            ensureTable(name);
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT k, v FROM " + name)) {
                while (result.next()) {
                    rows.put(result.getString(1), result.getString(2));
                }
            }
        } catch (SQLException e) {
            LOGGER.warn("Could not read " + name + ": " + e.getMessage() + " — treating it as empty");
        }
        return rows;
    }

    @Override
    public synchronized void save(String table, Map<String, String> rows) {
        String name = tableName(table);
        try {
            ensureTable(name);
            connection.setAutoCommit(false);
            try (Statement clear = connection.createStatement()) {
                clear.executeUpdate("DELETE FROM " + name);
            }
            try (PreparedStatement insert =
                         connection.prepareStatement("INSERT INTO " + name + " (k, v) VALUES (?, ?)")) {
                for (Map.Entry<String, String> row : rows.entrySet()) {
                    insert.setString(1, row.getKey());
                    insert.setString(2, row.getValue());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            LOGGER.warn("Could not write " + name + ": " + e.getMessage());
            rollback();
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // The connection is already unhappy; the warning above is the useful part.
            }
        }
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            LOGGER.warn("Could not close the storage connection: " + e.getMessage());
        }
    }

    private void ensureTable(String name) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + name
                    + " (k VARCHAR(190) PRIMARY KEY, v TEXT)");
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Nothing further to try; the table is whatever the database says it is.
        }
    }

    /**
     * A table name is built here and interpolated into SQL, so it is not allowed to be interesting:
     * letters, digits and underscores, from a name the server itself chose. The values are always bound
     * as parameters.
     */
    private static String tableName(String table) {
        StringBuilder sb = new StringBuilder("jedrock_");
        for (char c : table.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) ? c : '_');
        }
        return sb.toString();
    }
}
