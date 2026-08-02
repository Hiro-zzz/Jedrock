package com.jedrock.api.config;

import com.jedrock.api.player.GameMode;

/**
 * Immutable, protocol-agnostic server settings — the file-backed knobs the core and the network
 * layer both read (bind addresses, world seed, the server-list advertisement). Pure data: it holds
 * no file-IO logic (that lives in the core's config loader), so the {@code api} module stays a
 * dependency-free contract. Obtain the built-in defaults via {@link #defaults()}.
 *
 * @param name         human-readable server name
 * @param bindHost     address to bind both listeners to (e.g. {@code 0.0.0.0})
 * @param javaPort     TCP port for Java Edition
 * @param bedrockPort  UDP port for Bedrock / RakNet
 * @param maxPlayers   advertised player cap (server-list ping + JE Join Game)
 * @param motd         message-of-the-day shown in the server list
 * @param seed         world-generation seed
 * @param tickRate     game-loop rate in ticks per second
 * @param viewDistance chunk streaming radius around each player
 * @param judgeEnabled whether the "blind judge" lazy anti-cheat validation is on
 * @param maxReach     max block-interaction distance (blocks) from the player
 * @param maxMoveDelta max position change (blocks) between two movement reports
 * @param bedrock014Port    UDP port for the experimental MCPE 0.14 listener (own RakNet version)
 * @param bedrock014Enabled whether the MCPE 0.14 listener is bound at all
 * @param defaultGameMode   game mode a player joins in (survival / creative / …)
 * @param peSidebarRaise    blank rows padded under the Bedrock sidebar, lifting it off the hotbar
 * @param peSidebarShift    spaces padded on each Bedrock sidebar row, nudging it sideways
 * @param rememberWorld     whether a player rejoins the world they logged out in, rather than the default
 * @param paths             where the server's four folders live
 * @param worlds            which world comes up at boot, and how often everything is written down
 * @param plugins           the script layer: on or off, and how eagerly an edit is noticed
 * @param logging           what is written to the log folder, and how much of it is kept
 * @param rcon              the remote console: off, loopback and password-gated unless said otherwise
 * @param storage           where the server's small persistent facts live — files, or a database
 */
public record ServerProperties(
        String name,
        String bindHost,
        int javaPort,
        int bedrockPort,
        int maxPlayers,
        String motd,
        long seed,
        int tickRate,
        int viewDistance,
        boolean judgeEnabled,
        double maxReach,
        double maxMoveDelta,
        int bedrock014Port,
        boolean bedrock014Enabled,
        GameMode defaultGameMode,
        int peSidebarRaise,
        int peSidebarShift,
        boolean rememberWorld,
        Paths paths,
        Worlds worlds,
        Plugins plugins,
        Logging logging,
        Rcon rcon,
        Storage storage
) {

    /**
     * The four folders the server writes to, by name, relative to where it was started. Grouped rather
     * than spelled out as four more components because they are one decision — "where does my server keep
     * its things" — and because a record with thirty flat fields is a record nobody reads.
     *
     * @param worlds  one folder per world, each with its own level file
     * @param plugins script plugins ({@code *.js})
     * @param logs    the log files
     * @param data    ops, permissions, remembered worlds, script storage
     */
    public record Paths(String worlds, String plugins, String logs, String data) {
        public static Paths defaults() {
            return new Paths("worlds", "plugins", "logs", "data");
        }
    }

    /**
     * The world the server comes up with, and how often what changed is written down.
     *
     * @param defaultName      the world every player joins into unless they are remembered elsewhere
     * @param defaultTemplate  the recipe it is created from the very first time (it is loaded ever after)
     * @param loadAll          whether the other world folders are loaded at boot, or only on demand
     * @param autosaveSeconds  how often a dirty world (and scenes, regions, script storage) is saved; 0 = off
     */
    public record Worlds(String defaultName, String defaultTemplate, boolean loadAll, long autosaveSeconds) {
        public static Worlds defaults() {
            return new Worlds("world", "overworld", true, 300L);
        }
    }

    /**
     * The script layer.
     *
     * @param enabled       whether {@code plugins/} is read at all — off is a server with no scripting
     * @param hotReload     whether a saved edit is picked up without a restart
     * @param reloadMillis  how often the plugins folder is polled for changes, when hot reload is on
     * @param http          whether scripts may reach the network, and how far
     */
    public record Plugins(boolean enabled, boolean hotReload, long reloadMillis, Http http) {
        public static Plugins defaults() {
            return new Plugins(true, true, 1000L, Http.defaults());
        }
    }

    /**
     * Outbound HTTP for scripts — the {@code http} global.
     *
     * <p><b>Off by default</b>, and that is the interesting decision. Everything else a plugin can do
     * stays inside this process; this is the one capability that lets a script talk to the outside world,
     * and therefore the one that can carry what happens on the server somewhere else. Turning it on is a
     * choice the person running the server should make deliberately, not one they discover a plugin made
     * for them.
     *
     * @param enabled     whether the {@code http} global exists at all
     * @param allowedHosts comma-separated hosts a request may go to; empty means any. A host matches
     *                     itself and its subdomains, so {@code discord.com} covers
     *                     {@code discord.com} and {@code api.discord.com} but not {@code notdiscord.com}
     * @param timeoutMillis how long one request may take before it is abandoned
     * @param maxResponseBytes most bytes a response body may be, so a large or hostile reply cannot
     *                     decide how much memory this process uses
     * @param maxConcurrent how many requests may be in flight at once across every plugin
     */
    public record Http(boolean enabled, String allowedHosts, long timeoutMillis,
                       int maxResponseBytes, int maxConcurrent) {
        public static Http defaults() {
            return new Http(false, "", 10_000L, 1024 * 1024, 8);
        }
    }

    /**
     * What is written where, and how much of it is kept.
     *
     * @param toFile        whether console output is also written to {@code logs/latest.log}
     * @param keepFiles     how many previous runs are kept beside it; 0 = only the current one
     * @param debug         which subsystems log verbosely — the same spec {@code -Djedrock.debug} takes:
     *                      {@code off}, {@code all}, or a comma list of logger-name tags ({@code pe,chunk})
     * @param statusSeconds how often a one-line status is logged; 0 = never
     */
    public record Logging(boolean toFile, int keepFiles, String debug, long statusSeconds) {
        public static Logging defaults() {
            return new Logging(true, 5, "off", 0L);
        }
    }

    /**
     * Remote console (Source RCON) — the console surface over a socket, for the tools that already speak
     * it. Off by default and loopback by default, and the loader refuses to start it with a blank
     * password however enabled it is: the protocol is plaintext, so an open RCON port with no password is
     * a remote shell for whoever finds it.
     *
     * @param enabled  whether the listener is bound at all
     * @param bind     address to bind it to — {@code 127.0.0.1} unless you have a tunnel
     * @param port     TCP port; 25575 is what every RCON client tries first
     * @param password the shared secret. Blank = the listener does not start.
     */
    public record Rcon(boolean enabled, String bind, int port, String password) {
        public static Rcon defaults() {
            return new Rcon(false, "127.0.0.1", 25575, "");
        }
    }

    /**
     * Where the server's small persistent facts are kept. {@code flatfile} — the default — is the
     * {@code data/} folder it has always used; {@code jdbc} is for anyone who would rather they lived in a
     * database, and needs the driver jar dropped in {@code libs/} since none is bundled. A backend that
     * can't be opened falls back to files with a warning rather than stopping the server.
     *
     * @param backend  {@code flatfile} or {@code jdbc}
     * @param url      the JDBC url, e.g. {@code jdbc:sqlite:data/jedrock.db}
     * @param driver   the driver class to load out of {@code libs/}
     * @param user     database user; blank for a file-backed database like SQLite
     * @param password database password
     */
    public record Storage(String backend, String url, String driver, String user, String password) {
        /**
         * The url and driver default to a working SQLite setup even though the backend does not use them,
         * so turning a database on is two steps and not four: set {@code storage.backend=jdbc}, drop the
         * driver in {@code libs/}.
         */
        public static Storage defaults() {
            return new Storage("flatfile", "jdbc:sqlite:data/jedrock.db", "org.sqlite.JDBC", "", "");
        }
    }

    /** The built-in defaults, used when no config file is present or a key is missing/invalid. */
    public static ServerProperties defaults() {
        return new ServerProperties(
                "Jedrock",       // name
                "0.0.0.0",       // bindHost
                25565,           // javaPort
                19132,           // bedrockPort
                20,              // maxPlayers
                "Jedrock",       // motd
                0x5EED1EAFL,     // seed — fixed so restarts reproduce the same terrain
                20,              // tickRate
                6,               // viewDistance
                true,            // judgeEnabled
                7.0,             // maxReach — creative reach (~6) plus margin
                16.0,            // maxMoveDelta — generous; catches teleport/speed, not lag/falls
                19133,           // bedrock014Port — experimental MCPE 0.14, its own UDP port
                true,            // bedrock014Enabled
                GameMode.CREATIVE, // defaultGameMode — creative preserves the current join behaviour
                4,               // peSidebarRaise — lift the popup clear of the hotbar
                16,              // peSidebarShift — nudge it aside, the way a Java sidebar sits off-centre
                true,            // rememberWorld — you come back where you left, which is the friendlier surprise
                Paths.defaults(),
                Worlds.defaults(),
                Plugins.defaults(),
                Logging.defaults(),
                Rcon.defaults(),
                Storage.defaults()
        );
    }
}
