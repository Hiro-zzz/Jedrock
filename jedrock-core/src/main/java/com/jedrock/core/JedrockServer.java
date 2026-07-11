package com.jedrock.core;

import com.jedrock.api.Jedrock;
import com.jedrock.api.Server;
import com.jedrock.api.ServerStatus;
import com.jedrock.api.config.ServerProperties;
import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerJoinEvent;
import com.jedrock.api.event.player.PlayerQuitEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.core.command.CommandManager;
import com.jedrock.core.command.GameModeCommand;
import com.jedrock.core.command.HelpCommand;
import com.jedrock.core.command.SpawnCommand;
import com.jedrock.core.command.TeleportCommand;
import com.jedrock.core.config.JedrockConfig;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.core.world.LevelData;
import com.jedrock.gameloop.GameLoop;
import com.jedrock.gameloop.Scheduler;
import com.jedrock.network.ConnectionListener;
import com.jedrock.network.NetworkServer;
import com.jedrock.network.NettyNetworkServer;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.TickUtil;
import com.jedrock.utils.text.ChatText;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reference implementation of the Server interface.
 *
 * Design principles applied:
 * - As little state as possible
 * - Delegates to specialized modules (gameloop, network)
 * - Lazy-friendly (no eager world/player loading)
 */
public class JedrockServer implements Server, ConnectionListener {

    private static final JLogger LOGGER = JLogger.getLogger(JedrockServer.class);

    private final ServerProperties config;
    private final String name;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final EventBus eventBus = new EventBus();
    private final GameLoop gameLoop = new GameLoop();
    private final Scheduler scheduler = new Scheduler();
    private final NetworkServer networkServer;

    // In-memory state layer
    private final PlayerRegistry playerRegistry = new PlayerRegistry();
    private final CoreWorld defaultWorld;
    private final BlindJudge judge;
    private final CommandManager commandManager = new CommandManager(this);
    /** Remembers each player's last chosen game mode this run, so it survives a reconnect. */
    private final java.util.Map<UUID, GameMode> gameModes = new java.util.concurrent.ConcurrentHashMap<>();

    private final AtomicLong tickCounter = new AtomicLong(0);
    private final AtomicBoolean saving = new AtomicBoolean(false);

    public JedrockServer() {
        // Load configuration first — everything below is parameterized by it.
        this.config = JedrockConfig.load();
        this.name = config.name();
        this.defaultWorld = new CoreWorld("world", Dimension.OVERWORLD, config.seed());
        this.judge = new BlindJudge(config.judgeEnabled(), config.maxReach(), config.maxMoveDelta());

        // Attach scheduler + core tick to game loop
        gameLoop.addTickable(scheduler);
        gameLoop.addTickable(this::serverTick);
        gameLoop.setTickRate(config.tickRate());

        // Default network impl (can be swapped). Register state listener + config before binding.
        this.networkServer = new NettyNetworkServer();
        this.networkServer.setConnectionListener(this);
        this.networkServer.setProperties(config);
        this.networkServer.setWorld(defaultWorld); // clients serialize chunks from this shared world

        registerCommands();
    }

    /** Wire up the built-in in-game commands. */
    private void registerCommands() {
        commandManager.register(new HelpCommand());
        commandManager.register(new GameModeCommand());
        commandManager.register(new TeleportCommand());
        commandManager.register(new SpawnCommand());
    }

    /** The in-game command registry — used by commands (e.g. {@code /help}) to introspect. */
    public CommandManager getCommandManager() {
        return commandManager;
    }

    @Override
    public List<ConnectionListener.CommandInfo> commands() {
        // Bedrock refuses to send a command it wasn't advertised; the PE session turns this into an
        // AvailableCommands manifest at spawn. Java clients need nothing.
        List<ConnectionListener.CommandInfo> out = new java.util.ArrayList<>();
        for (com.jedrock.core.command.Command c : commandManager.commands()) {
            out.add(new ConnectionListener.CommandInfo(c.name(), c.description(), c.aliases()));
        }
        return out;
    }

    @Override
    public GameMode gameModeFor(UUID uuid) {
        // The join sequence reads this (on an I/O thread) to pick the mode a client spawns in.
        return gameModes.getOrDefault(uuid, config.defaultGameMode());
    }

    @Override
    public GameMode gameModeOf(PlayerConnection connection) {
        CorePlayer p = playerRegistry.getByConnectionOrNull(connection);
        return p != null ? p.getGameMode() : config.defaultGameMode();
    }

    /**
     * Change a player's game mode: remember it (so a reconnect keeps it — the only way MCPE 0.14
     * ever changes mode) and push the live switch to the client where the edition supports it.
     */
    public void setGameMode(CorePlayer player, GameMode mode) {
        gameModes.put(player.getUniqueId(), mode);
        player.setGameMode(mode);
        // Entering survival, show the survival inventory (empty, or what's been mined) so any items the
        // creative menu left in hand don't linger.
        if (mode == GameMode.SURVIVAL) {
            player.syncInventory();
        }
    }

    /**
     * Server-authoritative teleport: reposition {@code player} and relay the move to every other
     * player's avatar. Bypasses the blind judge (the server initiated it) and the edge wall; callers
     * are responsible for sane destinations.
     */
    public void teleport(CorePlayer player, Location to) {
        player.setLocation(to);
        player.resetFall(); // a teleport is not a fall — don't bill the next move for the jump (or a void drop)
        player.getConnection().teleport(to.x(), to.y(), to.z(), to.yaw(), to.pitch());
        long entityId = player.getEntityId();
        for (CorePlayer other : playerRegistry.online()) {
            if (other != player) {
                other.getConnection().moveAvatar(entityId, to.x(), to.y(), to.z(), to.yaw(), to.pitch());
            }
        }
    }

    /** Interval (ticks) between environmental-damage sweeps — vanilla applies void damage every 10 ticks. */
    private static final int ENVIRONMENT_TICK_INTERVAL = 10;
    /** Void damage per sweep, in half-hearts (vanilla is 4 = two hearts). */
    private static final int VOID_DAMAGE = 4;

    private void serverTick(long currentTick) {
        // Core server-wide per-tick work goes here.
        // Keep this extremely lean.
        this.tickCounter.set(currentTick);

        // Tick network (keep-alives, etc.)
        networkServer.tick(currentTick);

        // Environmental damage runs on the loop (not per move) so it fires even for a player who has
        // stopped sending position updates. Gated to a coarse interval — no need to check every tick.
        if (currentTick % ENVIRONMENT_TICK_INTERVAL == 0) {
            environmentTick();
        }
    }

    /**
     * Periodic environmental damage. Today: the void — a survival player who has dropped past the finite
     * world's floor takes damage until they die (silent respawn at spawn). Runs on the game-loop thread;
     * {@link #hurt} and its packet sends are thread-safe. Other sources (lava, suffocation) would slot in
     * here.
     */
    private void environmentTick() {
        for (CorePlayer player : playerRegistry.online()) {
            if (player.getGameMode() == GameMode.SURVIVAL && defaultWorld.isInVoid(player.getLocation().y())) {
                hurt(player, VOID_DAMAGE,
                        "{gray}" + ChatText.escape(player.getName()) + " fell out of the world");
            }
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getVersion() {
        return Jedrock.VERSION + " (MCPE " + Jedrock.MCPE_VERSION + " / JE " + Jedrock.JE_VERSION + ")";
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            LOGGER.warn("Server already running");
            return;
        }

        LOGGER.info("Starting " + getVersion());

        // Load (or, on first run, generate) the world before any listener accepts logins, so a joining
        // player sees the baked terrain and persisted edits rather than a half-built world.
        prepareWorld();

        try {
            // Java Edition 1.12.2 (TCP) — the primary listener; a failure here is fatal.
            networkServer.bind(new InetSocketAddress(config.bindHost(), config.javaPort()), ProtocolVersion.JE_1_12_2);
        } catch (Exception e) {
            LOGGER.error("Failed to bind Java Edition listener on " + config.bindHost() + ":" + config.javaPort(), e);
            running.set(false);
            return;
        }

        // Bedrock listeners are bound best-effort: a busy UDP port (commonly the Minecraft Bedrock
        // client itself holds 19132 for LAN discovery) disables just that edition, never the whole
        // server, so Java and the other Bedrock version still come up.
        bindBedrock("1.1.5", config.bedrockPort(), ProtocolVersion.PE_1_1_5);
        if (config.bedrock014Enabled()) {
            bindBedrock("0.14", config.bedrock014Port(), ProtocolVersion.PE_0_14);
        }

        gameLoop.start();

        // Interactive console (status / players / debug / stop). Daemon thread; headless-safe.
        new ConsoleCommands(this).start();

        // Periodic world autosave (edits are otherwise only in memory). Off with 0; the save runs on
        // a background thread so it never stalls a tick, guarded so two saves can't overlap.
        long saveSeconds = Long.getLong("jedrock.world.save-seconds", 300L);
        if (saveSeconds > 0) {
            long periodTicks = saveSeconds * TickUtil.TPS;
            scheduler.runTaskTimer(this::autosave, periodTicks, periodTicks);
            LOGGER.info("World autosave enabled (every " + saveSeconds + "s)");
        }

        // Optional periodic status line, e.g. -Djedrock.status.seconds=30 (0 = off).
        long statusSeconds = Long.getLong("jedrock.status.seconds", 0L);
        if (statusSeconds > 0) {
            long periodTicks = statusSeconds * TickUtil.TPS;
            scheduler.runTaskTimer(() -> LOGGER.info(getStatus().summary()), periodTicks, periodTicks);
            LOGGER.info("Periodic status logging enabled (every " + statusSeconds + "s)");
        }

        LOGGER.info("Jedrock server started successfully. Type 'help' for console commands.");
    }

    /** Bind one Bedrock listener without letting a busy port abort startup. */
    private void bindBedrock(String label, int port, ProtocolVersion version) {
        try {
            networkServer.bind(new InetSocketAddress(config.bindHost(), port), version);
        } catch (Exception e) {
            LOGGER.warn("Could not bind Bedrock " + label + " on " + config.bindHost() + ":" + port
                    + " (" + rootCauseMessage(e) + "); " + label + " disabled for this run. "
                    + "Is the Minecraft Bedrock client (it holds UDP 19132) or another instance using the port?");
        }
    }

    /** The deepest cause message — nukkitx wraps the real BindException in a CompletionException. */
    private static String rootCauseMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() != null ? c.getMessage() : c.toString();
    }

    @Override
    public void shutdown() {
        if (!running.compareAndSet(true, false)) return;

        LOGGER.info("Shutting down Jedrock...");

        gameLoop.stop();
        networkServer.shutdown();

        // Save last, with the loop stopped and listeners closed, so no edit races the snapshot.
        if (defaultWorld.isDirty()) {
            saveWorld();
        }

        LOGGER.info("Shutdown complete.");
    }

    // ===== World persistence =====

    /** Path of the level file for the default world, e.g. {@code world/level.jdw}. */
    private Path levelFile() {
        return Path.of(defaultWorld.getName(), "level.jdw");
    }

    /**
     * Bring the world up: load a saved level if present, then bake if it isn't generated yet (a fresh
     * world, or a pre-bake file). A freshly baked world is saved immediately. A file we can't read is
     * left untouched and we bake a fresh world over it in memory rather than clobber it.
     */
    private void prepareWorld() {
        Path file = levelFile();
        if (Files.isRegularFile(file)) {
            try {
                LevelData meta = defaultWorld.load(file);
                if (meta.seed() != defaultWorld.getSeed()) {
                    LOGGER.warn("Saved world seed " + meta.seed() + " differs from configured seed "
                            + defaultWorld.getSeed() + " — edits were made against the saved terrain");
                }
                LOGGER.info("Loaded world from " + file.toAbsolutePath()
                        + " (" + defaultWorld.loadedSections() + " sections)");
            } catch (IOException e) {
                LOGGER.error("Failed to load world from " + file.toAbsolutePath()
                        + " — leaving it untouched and generating a fresh world in memory", e);
            }
        } else {
            LOGGER.info("No saved world at " + file.toAbsolutePath() + " — generating a fresh one");
        }

        if (!defaultWorld.isGenerated()) {
            LOGGER.info("Baking finite " + CoreWorld.BOUNDS_CHUNKS + "x" + CoreWorld.BOUNDS_CHUNKS
                    + "-chunk world (one-time)...");
            long t0 = System.nanoTime();
            defaultWorld.bake();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            LOGGER.info("Baked " + defaultWorld.loadedSections() + " sections in " + ms + " ms");
            saveWorld(); // persist the freshly baked world so the next run just loads it
        }
    }

    /** Save the world synchronously (used on shutdown). Best-effort: an I/O failure is logged, not fatal. */
    private void saveWorld() {
        Path file = levelFile();
        try {
            defaultWorld.save(file);
            LOGGER.info("Saved world to " + file.toAbsolutePath()
                    + " (" + defaultWorld.loadedSections() + " sections)");
        } catch (IOException e) {
            LOGGER.error("Failed to save world to " + file.toAbsolutePath(), e);
        }
    }

    /** Periodic autosave: runs {@link #saveWorld()} off-thread if the world changed, else skips. */
    private void autosave() {
        if (!defaultWorld.isDirty()) {
            return; // nothing changed since the last save — don't rewrite the level file
        }
        if (!saving.compareAndSet(false, true)) {
            return; // a previous save is still running — skip this round rather than pile up
        }
        Thread t = new Thread(() -> {
            try {
                saveWorld();
            } finally {
                saving.set(false);
            }
        }, "jedrock-autosave");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public EventBus getEventBus() {
        return eventBus;
    }

    @Override
    public Collection<Player> getPlayers() {
        return playerRegistry.all();
    }

    @Override
    public Optional<Player> getPlayer(String name) {
        return playerRegistry.getByName(name);
    }

    @Override
    public Collection<World> getWorlds() {
        return List.of(defaultWorld);
    }

    @Override
    public Optional<World> getWorld(String name) {
        return defaultWorld.getName().equalsIgnoreCase(name) ? Optional.of(defaultWorld) : Optional.empty();
    }

    // ===== ConnectionListener: network → core state bridge =====
    //
    // The network protocol handler is responsible for sending the protocol-mandatory
    // packets (LoginSuccess, JoinGame, initial chunks, position) so the client can spawn.
    // Core receives the player here and can safely do game logic, events, and extra packets.

    @Override
    public void onLogin(PlayerConnection connection, UUID uuid, String username) {
        // The username is untrusted (a 0.14 client picks its own over a plaintext login): strip raw
        // § codes and control chars once here so it's safe to show verbatim in every nametag / tab
        // entry below. Markup sites still escape() it — stripCodes keeps a legitimate '_' intact.
        username = ChatText.stripCodes(username);

        Location spawn = defaultWorld.getSpawnLocation();
        // Match the mode the client actually joined in (the same value the join packets used): the
        // remembered choice from earlier this run, or the config default for a first join.
        CorePlayer player = new CorePlayer(uuid, username, connection, defaultWorld, spawn, gameModeFor(uuid));

        playerRegistry.add(player);
        defaultWorld.addPlayer(player);

        PlayerJoinEvent event = new PlayerJoinEvent(player);
        eventBus.post(event);

        if (event.isCancelled()) {
            // A listener refused the join — undo the state we just added and drop the client.
            defaultWorld.removePlayer(player);
            playerRegistry.removeByConnection(connection);
            player.kick(event.getJoinMessage() != null ? event.getJoinMessage() : "Connection refused");
            return;
        }

        player.sendMessage("{green}Welcome to **Jedrock**!");
        broadcast("{yellow}" + ChatText.escape(username) + " joined the game", player);

        // Tab list: give the newcomer the whole roster, and add the newcomer to everyone else's.
        // Tab entries must land before the avatar spawns below (JE renders only listed uuids).
        for (Player other : playerRegistry.all()) {
            connection.addToTab(other.getUniqueId(), other.getName());
            if (other != player) {
                other.getConnection().addToTab(uuid, username);
            }
        }

        // Avatars: show the existing roster to the newcomer, and the newcomer to everyone else.
        for (Player other : playerRegistry.all()) {
            if (other == player) continue;
            Location loc = other.getLocation();
            connection.showPlayer(other.getUniqueId(), other.getName(), other.getEntityId(),
                    loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch());
            // Sync a currently posed player (crouch / sprint / item-use) so the newcomer sees it.
            if (other instanceof CorePlayer oc && (oc.isSneaking() || oc.isSprinting() || oc.isUsingItem())) {
                connection.setPose(other.getEntityId(), oc.isSneaking(), oc.isSprinting(), oc.isUsingItem());
            }
            other.getConnection().showPlayer(uuid, username, player.getEntityId(),
                    spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
        }

        LOGGER.info(username + " joined [" + connection.getProtocolVersion().getVersionName()
                + "] (" + playerRegistry.size() + " online)");
    }

    @Override
    public void onDisconnect(PlayerConnection connection) {
        CorePlayer player = playerRegistry.removeByConnection(connection);
        if (player == null) {
            return; // never fully logged in
        }
        defaultWorld.removePlayer(player);
        eventBus.post(new PlayerQuitEvent(player));
        broadcast("{yellow}" + ChatText.escape(player.getName()) + " left the game", null);

        // Drop the leaver from everyone else's tab and world.
        for (Player other : playerRegistry.all()) {
            other.getConnection().removeFromTab(player.getUniqueId());
            other.getConnection().hidePlayer(player.getUniqueId(), player.getEntityId());
        }

        LOGGER.info(player.getName() + " disconnected (" + playerRegistry.size() + " online)");
    }

    @Override
    public void onMove(PlayerConnection connection, double x, double y, double z, float yaw, float pitch) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null) {
            return;
        }
        Location from = player.getLocation();

        // Edge wall: the world is finite. Refuse a step past the bounds and snap the player back inside
        // (an invisible wall, keeping their look angles); if they're somehow already outside, send them
        // home to spawn. Enforced regardless of the anti-cheat toggle — the edge is a world constraint.
        if (!defaultWorld.isInsideBounds(x, z)) {
            if (defaultWorld.isInsideBounds(from.x(), from.z())) {
                connection.teleport(from.x(), from.y(), from.z(), yaw, pitch);
            } else {
                Location spawn = defaultWorld.getSpawnLocation();
                player.setLocation(new Location(player.getWorld(), spawn.x(), spawn.y(), spawn.z(), yaw, pitch));
                connection.teleport(spawn.x(), spawn.y(), spawn.z(), yaw, pitch);
            }
            return;
        }

        // Blind judge: refuse to believe a blatant teleport / speed jump and snap the client back to
        // its last valid spot (keeping its new look angles, which aren't cheating), then drop the move.
        if (!judge.allowsMove(from.x(), from.y(), from.z(), x, y, z)) {
            connection.teleport(from.x(), from.y(), from.z(), yaw, pitch);
            return;
        }
        player.setLocation(new Location(player.getWorld(), x, y, z, yaw, pitch));

        // Fall damage for editions with no client fall-report packet: watch the descent server-side and
        // apply damage on landing. This is Java (no such packet at all) and Bedrock 0.14 (its client never
        // reports one). Bedrock 1.1.5 is skipped here — it reports falls itself via EntityFall (onFall).
        ProtocolVersion version = connection.getProtocolVersion();
        if (version.isJava() || version == ProtocolVersion.PE_0_14) {
            double fell = player.trackFall(from.y(), y);
            if (fell > 0) {
                applyFallDamage(player, fell);
            }
        }

        // Relay at the sender's own rate; both clients interpolate between updates. Iterate the live
        // roster view directly and skip the Optional/capturing lambda, so a move packet allocates
        // only the immutable Location snapshot (kept immutable so getLocation stays an atomic swap).
        long entityId = player.getEntityId();
        for (CorePlayer other : playerRegistry.online()) {
            if (other != player) {
                other.getConnection().moveAvatar(entityId, x, y, z, yaw, pitch);
            }
        }
    }

    @Override
    public void onBlockChange(PlayerConnection connection, int x, int y, int z, int state) {
        // Edge wall: an edit outside the finite world is refused and the client corrected with the
        // real (void = air) block, so the world can't grow past its bounds.
        if (!defaultWorld.isInsideBounds(x, z)) {
            connection.sendBlockChange(x, y, z, defaultWorld.getBlockId(x, y, z));
            return;
        }
        // Blind judge: reject an edit outside the editor's reach sphere and correct their client by
        // re-sending the real (unchanged) block, so a reach hack can't touch distant blocks.
        CorePlayer editor = playerRegistry.getByConnectionOrNull(connection);
        if (editor != null) {
            Location loc = editor.getLocation();
            if (!judge.allowsInteraction(loc.x(), loc.y(), loc.z(), x, y, z)) {
                connection.sendBlockChange(x, y, z, defaultWorld.getBlockId(x, y, z));
                return;
            }
        }
        // The block that was here, captured before the edit — a survival miner picks it up.
        int previous = defaultWorld.getBlockId(x, y, z);

        // Apply to the shared world, then push the edit to every client (including the editor, so
        // the server stays authoritative). {@code state} is the canonical (id << 4 | meta) value;
        // each connection serializes it in its own protocol.
        defaultWorld.setBlockId(x, y, z, state);
        for (Player p : playerRegistry.all()) {
            p.getConnection().sendBlockChange(x, y, z, state);
        }

        // Minimal survival inventory: mining a block drops it straight into the inventory, placing one
        // consumes it. Creative is untouched (the client has the creative menu). state 0 = air = a break.
        // Push just the changed slot so the hotbar HUD refreshes live (a full resend didn't).
        if (editor != null && editor.getGameMode() == GameMode.SURVIVAL) {
            int slot = state == 0 ? editor.giveItem(previous) : editor.takeItem(state);
            if (slot >= 0) {
                editor.syncSlot(slot);
            }
        }
    }

    @Override
    public void onFall(PlayerConnection connection, float fallDistance) {
        // Bedrock 1.1.5 reports its own falls (EntityFall) — apply the damage from the client's report.
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player != null) {
            applyFallDamage(player, fallDistance);
        }
    }

    /**
     * Turn a fall distance into vanilla fall damage (one point per block past the first three) and apply
     * it via {@link #hurt}. Shared by the Bedrock client's {@code EntityFall} report and the server-side
     * fall tracking used by editions with no fall-report packet (Java, PE 0.14).
     */
    private void applyFallDamage(CorePlayer player, double fallDistance) {
        int damage = (int) Math.floor(fallDistance) - 3;
        hurt(player, damage, "{gray}" + ChatText.escape(player.getName()) + " fell to their death");
    }

    /**
     * Apply {@code amount} half-hearts of damage to a survival player from any source (fall, void, and
     * later PvP), push the new health to their HUD, and handle death. Death is deliberately primitive —
     * a silent respawn at spawn with full health, no death-screen handshake (a kept feature) — and
     * broadcasts {@code deathMessage}. A no-op outside survival or for a non-positive {@code amount}.
     */
    private void hurt(CorePlayer player, int amount, String deathMessage) {
        if (player.getGameMode() != GameMode.SURVIVAL || amount <= 0) {
            return;
        }
        // Show the hit to everyone else: the victim's avatar flashes red (its own client shows the hit
        // from its dropping health bar, so it doesn't need this). Covers every source — PvP, fall, void.
        long entityId = player.getEntityId();
        for (CorePlayer other : playerRegistry.online()) {
            if (other != player) {
                other.getConnection().playHurtAnimation(entityId);
            }
        }
        PlayerConnection connection = player.getConnection();
        if (player.damage(amount) <= 0) {
            player.setHealth(CorePlayer.MAX_HEALTH);
            teleport(player, defaultWorld.getSpawnLocation()); // resets fall tracking too
            connection.setHealth(CorePlayer.MAX_HEALTH);
            broadcast(deathMessage, null);
        } else {
            connection.setHealth(player.getHealth());
        }
    }

    @Override
    public int getOnlinePlayerCount() {
        return playerRegistry.size();
    }

    @Override
    public void onSneak(PlayerConnection connection, boolean sneaking) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null) {
            return;
        }
        player.setSneaking(sneaking);
        relayPose(player);
    }

    @Override
    public void onSprint(PlayerConnection connection, boolean sprinting) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null) {
            return;
        }
        player.setSprinting(sprinting);
        relayPose(player);
    }

    @Override
    public void onUseItem(PlayerConnection connection, boolean using) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null) {
            return;
        }
        player.setUsingItem(using);
        relayPose(player);
    }

    /** Relay a player's full pose (sneak + sprint + item-use share a flags field, so send together). */
    private void relayPose(CorePlayer player) {
        long entityId = player.getEntityId();
        boolean sneaking = player.isSneaking();
        boolean sprinting = player.isSprinting();
        boolean usingItem = player.isUsingItem();
        for (CorePlayer other : playerRegistry.online()) {
            if (other != player) {
                other.getConnection().setPose(entityId, sneaking, sprinting, usingItem);
            }
        }
    }

    @Override
    public void onSwingArm(PlayerConnection connection) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null) {
            return;
        }
        long entityId = player.getEntityId();
        for (CorePlayer other : playerRegistry.online()) {
            if (other != player) {
                other.getConnection().swingArm(entityId);
            }
        }
    }

    /** Bare-hand melee damage, in half-hearts (vanilla is 2 = one heart). */
    private static final int ATTACK_DAMAGE = 2;

    @Override
    public void onAttack(PlayerConnection connection, long targetEntityId) {
        CorePlayer attacker = playerRegistry.getByConnectionOrNull(connection);
        if (attacker == null) {
            return;
        }
        CorePlayer victim = playerRegistry.getByEntityIdOrNull(targetEntityId);
        if (victim == null || victim == attacker) {
            return; // unknown target, or a self-hit — nothing to do
        }
        // Reach check (the blind judge): reject a hit from implausibly far — a reach hack — measured to
        // the victim's cell. The attacker's own arm swing is relayed separately via onSwingArm.
        Location a = attacker.getLocation();
        Location v = victim.getLocation();
        if (!judge.allowsInteraction(a.x(), a.y(), a.z(),
                (int) Math.floor(v.x()), (int) Math.floor(v.y()), (int) Math.floor(v.z()))) {
            return;
        }
        // Invulnerability frames: drop a hit landing inside the victim's half-second window, so a
        // click-spamming attacker can't deal damage faster than vanilla.
        if (victim.isOnHurtCooldown()) {
            return;
        }
        hurt(victim, ATTACK_DAMAGE, "{gray}" + ChatText.escape(victim.getName())
                + " was slain by " + ChatText.escape(attacker.getName()));
    }

    @Override
    public void onChat(PlayerConnection connection, String message) {
        CorePlayer sender = playerRegistry.getByConnectionOrNull(connection);
        if (sender == null) {
            return;
        }
        // A line starting with '/' is a command, not chat — dispatch it and never broadcast it.
        if (message.startsWith("/")) {
            commandManager.dispatch(sender, message);
            return;
        }
        // The name is escaped so an untrusted username (a 0.14 client picks its own; a '_' would
        // otherwise italicise) can't inject markup. The message body is intentionally left raw —
        // rendering it is the documented "players can use {color} / Markdown in chat" feature.
        String line = "{gray}<{aqua}" + ChatText.escape(sender.getName()) + "{gray}>{reset} " + message;
        LOGGER.info("[chat] <" + sender.getName() + "> " + message);
        broadcast(line, null); // relay to everyone, including the sender
    }

    /**
     * Send a system message to every online player — each {@link PlayerConnection} serializes
     * it in its own protocol, so a JE and a PE player see the same line. This is the core of
     * cross-platform interaction. Optionally skips one player (e.g. the join announcement's subject).
     */
    private void broadcast(String message, Player except) {
        for (Player p : playerRegistry.all()) {
            if (p != except) {
                p.sendMessage(message);
            }
        }
    }

    @Override
    public long getCurrentTick() {
        return tickCounter.get();
    }

    @Override
    public ServerStatus getStatus() {
        var metrics = gameLoop.metrics();
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        return new ServerStatus(
                metrics.tps(), metrics.mspt(), metrics.peakMspt(), tickCounter.get(), metrics.uptimeMillis(),
                playerRegistry.size(), usedMemory, runtime.maxMemory());
    }

    // Accessors for modules that need to reach internal components
    public GameLoop getGameLoop() {
        return gameLoop;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public NetworkServer getNetworkServer() {
        return networkServer;
    }

    public static void main(String[] args) {
        JedrockServer server = new JedrockServer();
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));

        server.start();

        // Keep the main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {}
    }
}
