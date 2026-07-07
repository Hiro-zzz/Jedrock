package com.jedrock.core;

import com.jedrock.api.Jedrock;
import com.jedrock.api.Server;
import com.jedrock.api.ServerStatus;
import com.jedrock.api.config.ServerProperties;
import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.PlayerJoinEvent;
import com.jedrock.api.event.player.PlayerQuitEvent;
import com.jedrock.api.player.Player;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.core.config.JedrockConfig;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.gameloop.GameLoop;
import com.jedrock.gameloop.Scheduler;
import com.jedrock.network.ConnectionListener;
import com.jedrock.network.NetworkServer;
import com.jedrock.network.NettyNetworkServer;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.TickUtil;

import java.net.InetSocketAddress;
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

    private final AtomicLong tickCounter = new AtomicLong(0);

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
    }

    private void serverTick(long currentTick) {
        // Core server-wide per-tick work goes here.
        // Keep this extremely lean.
        this.tickCounter.set(currentTick);

        // Tick network (keep-alives, etc.)
        networkServer.tick(currentTick);
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

        LOGGER.info("Shutdown complete.");
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
        Location spawn = defaultWorld.getSpawnLocation();
        CorePlayer player = new CorePlayer(uuid, username, connection, defaultWorld, spawn);

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

        player.sendMessage("§aWelcome to Jedrock!");
        broadcast("§e" + username + " joined the game", player);

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
        broadcast("§e" + player.getName() + " left the game", null);

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
        // Blind judge: refuse to believe a blatant teleport / speed jump and snap the client back to
        // its last valid spot (keeping its new look angles, which aren't cheating), then drop the move.
        Location from = player.getLocation();
        if (!judge.allowsMove(from.x(), from.y(), from.z(), x, y, z)) {
            connection.teleport(from.x(), from.y(), from.z(), yaw, pitch);
            return;
        }
        player.setLocation(new Location(player.getWorld(), x, y, z, yaw, pitch));
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
        // Apply to the shared world, then push the edit to every client (including the editor, so
        // the server stays authoritative). {@code state} is the canonical (id << 4 | meta) value;
        // each connection serializes it in its own protocol.
        defaultWorld.setBlockId(x, y, z, state);
        for (Player p : playerRegistry.all()) {
            p.getConnection().sendBlockChange(x, y, z, state);
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

    @Override
    public void onChat(PlayerConnection connection, String message) {
        CorePlayer sender = playerRegistry.getByConnectionOrNull(connection);
        if (sender == null) {
            return;
        }
        String line = "<" + sender.getName() + "> " + message;
        LOGGER.info("[chat] " + line);
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
