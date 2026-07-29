package com.jedrock.core.data;

import java.util.Map;

/**
 * Where the server's small persistent facts live — and the seam that lets that be somewhere else.
 *
 * <p>Jedrock keeps its bookkeeping in plain files in {@code data/}, and for a server with twenty players
 * that is the right answer: a few kilobytes, human-editable, no daemon to run. This interface exists
 * because it is not the <em>only</em> answer. A network of servers wants one shared account of who is
 * where; a host may already have a database and no interest in a folder of text files. Neither is a
 * reason to make everyone else run one.
 *
 * <p>So: an interface with a file-backed default that writes exactly what the server wrote before, and a
 * JDBC backend for anyone who asks for it. Turning it on changes where the rows are, not what they mean.
 *
 * <p><b>Whole tables, not single rows.</b> Every store built on this loads once at boot and rewrites on
 * change — they are lists of tens of entries, not query workloads, and a per-key API would invite the
 * write-per-operation pattern that makes a database a latency source on the game loop. Read all, write
 * all, rarely.
 *
 * <p>Implementations must be safe to call from several threads. Failures are absorbed and reported, never
 * thrown: losing the record of which world someone logged out in is a lost convenience, and taking the
 * server down over it would be the larger bug.
 */
public interface DataStore extends AutoCloseable {

    /** A short description for the startup line — "files in data/" or the JDBC url, minus any password. */
    String describe();

    /**
     * Every row of {@code table}, in insertion order where the backend keeps one.
     *
     * @return the rows, or an empty map if the table has never been written (and on any read failure)
     */
    Map<String, String> load(String table);

    /**
     * Replace {@code table} with exactly {@code rows}. Deleting a key means saving without it, which is
     * why this is a replace rather than a merge.
     */
    void save(String table, Map<String, String> rows);

    /** Release whatever the backend holds open. The file backend holds nothing; JDBC holds a connection. */
    @Override
    void close();
}
