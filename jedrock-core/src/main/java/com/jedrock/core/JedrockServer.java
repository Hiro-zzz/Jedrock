package com.jedrock.core;

import com.jedrock.api.Jedrock;
import com.jedrock.api.Server;
import com.jedrock.api.ServerStatus;
import com.jedrock.api.config.ServerProperties;
import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.Hologram;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.block.BlockBreakEvent;
import com.jedrock.api.event.block.BlockPlaceEvent;
import com.jedrock.api.event.player.PlayerChatEvent;
import com.jedrock.api.event.player.PlayerInteractEntityEvent;
import com.jedrock.api.event.player.PlayerJoinEvent;
import com.jedrock.api.event.player.PlayerMoveEvent;
import com.jedrock.api.event.player.PlayerQuitEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.core.command.ClearCommand;
import com.jedrock.core.command.CommandManager;
import com.jedrock.core.command.GameModeCommand;
import com.jedrock.core.command.HealCommand;
import com.jedrock.core.command.HelpCommand;
import com.jedrock.core.command.KillCommand;
import com.jedrock.core.command.ListCommand;
import com.jedrock.core.command.MeCommand;
import com.jedrock.core.command.MsgCommand;
import com.jedrock.core.command.SayCommand;
import com.jedrock.core.command.SpawnCommand;
import com.jedrock.core.command.TeleportCommand;
import com.jedrock.core.command.TpAllCommand;
import com.jedrock.core.command.TpHereCommand;
import com.jedrock.core.command.TpsCommand;
import com.jedrock.core.command.HologramCommand;
import com.jedrock.core.command.PuppetCommand;
import com.jedrock.core.config.JedrockConfig;
import com.jedrock.core.entity.CoreHologram;
import com.jedrock.core.entity.CorePuppet;
import com.jedrock.core.entity.PuppetRegistry;
import com.jedrock.core.inventory.Container;
import com.jedrock.core.inventory.Cursor;
import com.jedrock.core.inventory.InventoryClick;
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
    private final PuppetRegistry puppets = new PuppetRegistry();
    /** Live holograms. Iterated on every join and mutated rarely, so a copy-on-write list fits. */
    private final List<CoreHologram> holograms = new java.util.concurrent.CopyOnWriteArrayList<>();
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
        commandManager.register(new ListCommand());
        commandManager.register(new TpsCommand());
        commandManager.register(new SayCommand());
        commandManager.register(new MeCommand());
        commandManager.register(new MsgCommand());
        commandManager.register(new GameModeCommand());
        commandManager.register(new TeleportCommand());
        commandManager.register(new TpHereCommand());
        commandManager.register(new TpAllCommand());
        commandManager.register(new SpawnCommand());
        commandManager.register(new HealCommand());
        commandManager.register(new KillCommand());
        commandManager.register(new ClearCommand());
        commandManager.register(new PuppetCommand());
        commandManager.register(new HologramCommand());
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
            if (version == ProtocolVersion.PE_1_1_5) {
                LOGGER.warn("Bedrock 1.1.5 is EXPERIMENTAL / known-buggy: the retail client (PC AND mobile) "
                        + "double-fires place/break (mitigated, not eliminated) and chests can't be opened. "
                        + "Join/move/chat/blocks/cross-play work. Prefer 0.14 or Java for a clean experience.");
            }
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
                        + " (" + defaultWorld.loadedSections() + " sections, "
                        + defaultWorld.compressedSections() + " compressed)");
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
            LOGGER.info("Baked " + defaultWorld.loadedSections() + " sections ("
                    + defaultWorld.compressedSections() + " compressed) in " + ms + " ms");
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

    // ===== Puppets (server-puppeteered visual entities) =====

    @Override
    public PuppetEntity spawnPuppet(EntityType type, Location at, String name) {
        CorePuppet puppet = new CorePuppet(type, name, defaultWorld, at, this);
        puppets.add(puppet);
        // Show it to everyone currently online; a later joiner is shown it in onLogin.
        for (CorePlayer p : playerRegistry.online()) {
            spawnPuppetTo(p.getConnection(), puppet);
        }
        LOGGER.info("Spawned puppet " + type.canonicalName() + " #" + puppet.getEntityId()
                + " at " + String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", at.x(), at.y(), at.z()));
        return puppet;
    }

    /**
     * Show one puppet to one connection. A {@link EntityType#PLAYER} puppet renders through the player-avatar
     * path (a tab / player-list entry + spawn-player), reusing exactly what real players use; a mob puppet
     * uses the spawn-entity path. Shared by the spawn broadcast and the join-time catch-up in onLogin.
     */
    private void spawnPuppetTo(PlayerConnection conn, CorePuppet puppet) {
        Location loc = puppet.getLocation();
        if (puppet.getEntityType().isPlayer()) {
            conn.addToTab(puppet.getUniqueId(), puppet.getName()); // JE needs the tab entry before the avatar
            conn.showPlayer(puppet.getUniqueId(), puppet.getName(), puppet.getEntityId(),
                    loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch());
            return;
        }
        conn.spawnEntity(puppet.getEntityId(), puppet.getUniqueId(), puppet.getEntityType(),
                loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch());
        // Catch the newcomer up on the look the puppet has accumulated since it spawned.
        if (puppet.getFlags() != 0) {
            conn.setEntityFlags(puppet.getEntityId(), puppet.getFlags());
        }
        if (puppet.getNameTag() != null && !puppet.getNameTag().isEmpty()) {
            conn.setEntityNameTag(puppet.getEntityId(), ChatText.toLegacy(puppet.getNameTag()));
        }
    }

    /** Relay a puppet's floating text to every viewer. Called by {@link CorePuppet#setNameTag}. */
    public void relayPuppetNameTag(CorePuppet puppet) {
        if (puppet.getEntityType().isPlayer()) {
            return; // a player avatar's name is its player name on every edition — nothing to set
        }
        String nameTag = puppet.getNameTag();
        String rendered = nameTag == null ? "" : ChatText.toLegacy(nameTag);
        for (CorePlayer p : playerRegistry.online()) {
            p.getConnection().setEntityNameTag(puppet.getEntityId(), rendered);
        }
    }

    /** Relay a puppet's whole flag set to every viewer. Called by {@link CorePuppet#setFlag}. */
    public void relayPuppetFlags(CorePuppet puppet) {
        for (CorePlayer p : playerRegistry.online()) {
            p.getConnection().setEntityFlags(puppet.getEntityId(), puppet.getFlags());
        }
    }

    /** Relay a puppet's arm swing to every viewer. Called by {@link CorePuppet#swing}. */
    public void relayPuppetSwing(CorePuppet puppet) {
        for (CorePlayer p : playerRegistry.online()) {
            p.getConnection().swingArm(puppet.getEntityId());
        }
    }

    /** Relay a puppet's hurt flash to every viewer. Called by {@link CorePuppet#hurt}. */
    public void relayPuppetHurt(CorePuppet puppet) {
        for (CorePlayer p : playerRegistry.online()) {
            p.getConnection().playHurtAnimation(puppet.getEntityId());
        }
    }

    /** Relay a puppet's move to every viewer. Called by {@link CorePuppet#teleport}. */
    public void movePuppet(CorePuppet puppet, Location to) {
        boolean asPlayer = puppet.getEntityType().isPlayer();
        for (CorePlayer p : playerRegistry.online()) {
            if (asPlayer) {
                p.getConnection().moveAvatar(puppet.getEntityId(), to.x(), to.y(), to.z(), to.yaw(), to.pitch());
            } else {
                p.getConnection().moveEntity(puppet.getEntityId(), to.x(), to.y(), to.z(), to.yaw(), to.pitch());
            }
        }
    }

    /** Despawn a puppet from every viewer and drop it from the registry. Called by {@link CorePuppet#remove}. */
    public void removePuppet(CorePuppet puppet) {
        if (puppets.remove(puppet.getEntityId()) == null) {
            return; // already gone
        }
        boolean asPlayer = puppet.getEntityType().isPlayer();
        for (CorePlayer p : playerRegistry.online()) {
            if (asPlayer) {
                p.getConnection().hidePlayer(puppet.getUniqueId(), puppet.getEntityId());
                p.getConnection().removeFromTab(puppet.getUniqueId());
            } else {
                p.getConnection().removeEntity(puppet.getEntityId());
            }
        }
    }

    /** The live puppet roster — used by the {@code /puppet} command to list / resolve puppets. */
    public PuppetRegistry getPuppets() {
        return puppets;
    }

    // ===== Holograms (floating text) =====

    @Override
    public Hologram spawnHologram(Location at, String... lines) {
        CoreHologram hologram = new CoreHologram(defaultWorld, at, this, lines);
        holograms.add(hologram);
        for (CorePlayer p : playerRegistry.online()) {
            spawnHologramTo(p.getConnection(), hologram);
        }
        LOGGER.info("Spawned hologram #" + hologram.getEntityId() + " (" + lines.length + " lines) at "
                + String.format(java.util.Locale.ROOT, "%.1f,%.1f,%.1f", at.x(), at.y(), at.z()));
        return hologram;
    }

    /** Show every line of one hologram to one connection. Shared by the spawn broadcast and onLogin. */
    private void spawnHologramTo(PlayerConnection conn, CoreHologram hologram) {
        List<String> lines = hologram.getLines();
        long[] ids = hologram.getLineIds();
        for (int i = 0; i < lines.size(); i++) {
            Location at = hologram.lineLocation(i);
            conn.spawnTextLine(ids[i], hologram.getUniqueId(), at.x(), at.y(), at.z(),
                    ChatText.toLegacy(lines.get(i)));
        }
    }

    /** Relay one re-texted hologram line. Called by {@link CoreHologram#setLine}. */
    public void relayHologramLine(CoreHologram hologram, int index) {
        long id = hologram.getLineIds()[index];
        String rendered = ChatText.toLegacy(hologram.getLines().get(index));
        for (CorePlayer p : playerRegistry.online()) {
            p.getConnection().setEntityNameTag(id, rendered);
        }
    }

    /** Move a hologram's whole stack. Called by {@link CoreHologram#teleport}. */
    public void moveHologram(CoreHologram hologram) {
        long[] ids = hologram.getLineIds();
        for (int i = 0; i < ids.length; i++) {
            Location at = hologram.lineLocation(i);
            for (CorePlayer p : playerRegistry.online()) {
                p.getConnection().moveEntity(ids[i], at.x(), at.y(), at.z(), 0f, 0f);
            }
        }
    }

    /** Despawn {@code oldIds} and re-show the hologram — its line count changed ({@link CoreHologram#setLines}). */
    public void respawnHologram(CoreHologram hologram, long[] oldIds) {
        for (CorePlayer p : playerRegistry.online()) {
            for (long id : oldIds) {
                p.getConnection().removeEntity(id);
            }
            spawnHologramTo(p.getConnection(), hologram);
        }
    }

    /** Despawn every line and drop the hologram. Called by {@link CoreHologram#remove}. */
    public void removeHologram(CoreHologram hologram) {
        if (!holograms.remove(hologram)) {
            return; // already gone
        }
        for (CorePlayer p : playerRegistry.online()) {
            for (long id : hologram.getLineIds()) {
                p.getConnection().removeEntity(id);
            }
        }
    }

    /** The live holograms — used by the {@code /puppet} command to list / resolve them. */
    public List<CoreHologram> getHolograms() {
        return holograms;
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

        // Evict any stale session for the same account before the new one registers. A crashed client is
        // only timed out by RakNet later, so the same player can rejoin while their old session (avatar,
        // registry + world entry) still lingers; left in place it shows a ghost avatar to everyone and its
        // eventual timeout would race the live session. Remove it cleanly first.
        CorePlayer ghost = playerRegistry.getByIdOrNull(uuid);
        if (ghost != null && ghost.getConnection() != connection) {
            evictPlayer(ghost);
            ghost.getConnection().close("Logged in from another location");
            LOGGER.info("Evicted stale session for " + username + " on re-login");
        }

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

        // Show every existing puppet and hologram to the newcomer (existing players already see them).
        for (CorePuppet puppet : puppets.all()) {
            spawnPuppetTo(connection, puppet);
        }
        for (CoreHologram hologram : holograms) {
            spawnHologramTo(connection, hologram);
        }

        LOGGER.info(username + " joined [" + connection.getProtocolVersion().getVersionName()
                + "] (" + playerRegistry.size() + " online)");
    }

    @Override
    public void onDisconnect(PlayerConnection connection) {
        CorePlayer player = playerRegistry.removeByConnection(connection);
        if (player == null) {
            return; // never fully logged in, or a stale connection already replaced by a re-login
        }
        evictPlayer(player);
        eventBus.post(new PlayerQuitEvent(player));
        broadcast("{yellow}" + ChatText.escape(player.getName()) + " left the game", null);
        LOGGER.info(player.getName() + " disconnected (" + playerRegistry.size() + " online)");
    }

    /**
     * Tear down a player's presence: drop it from the registry, the world, and every other player's tab
     * and view (hiding its avatar). Shared by a normal disconnect and the re-login eviction of a stale
     * session, so a lingering avatar never outlives its player. Safe to call for an already-removed player.
     */
    private void evictPlayer(CorePlayer player) {
        playerRegistry.removeByConnection(player.getConnection());
        defaultWorld.removePlayer(player);
        for (Player other : playerRegistry.all()) {
            other.getConnection().removeFromTab(player.getUniqueId());
            other.getConnection().hidePlayer(player.getUniqueId(), player.getEntityId());
        }
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

        // Let listeners veto a move (a region border, a freeze). This is the hottest path in the server —
        // a packet per client per move — so the event is only built when something is actually listening;
        // with no listener, movement costs exactly what it did before the event existed. Cancelling snaps
        // the client back to where it was and drops the report.
        if (eventBus.hasListeners(PlayerMoveEvent.class)) {
            Location to = new Location(player.getWorld(), x, y, z, yaw, pitch);
            if (eventBus.post(new PlayerMoveEvent(player, from, to)).isCancelled()) {
                connection.teleport(from.x(), from.y(), from.z(), from.yaw(), from.pitch());
                return;
            }
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
            LOGGER.debug(() -> "[edit] REFUSED (out of bounds) " + x + "," + y + "," + z);
            connection.sendBlockChange(x, y, z, defaultWorld.getBlockId(x, y, z));
            return;
        }
        // Blind judge: reject an edit outside the editor's reach sphere and correct their client by
        // re-sending the real (unchanged) block, so a reach hack can't touch distant blocks.
        CorePlayer editor = playerRegistry.getByConnectionOrNull(connection);
        if (editor != null) {
            Location loc = editor.getLocation();
            if (!judge.allowsInteraction(loc.x(), loc.y(), loc.z(), x, y, z)) {
                LOGGER.debug(() -> "[edit] REFUSED (reach) " + x + "," + y + "," + z + " from "
                        + String.format("%.1f,%.1f,%.1f", loc.x(), loc.y(), loc.z()));
                connection.sendBlockChange(x, y, z, defaultWorld.getBlockId(x, y, z));
                return;
            }
        }
        // The block that was here, captured before the edit — a survival miner picks it up.
        int previous = defaultWorld.getBlockId(x, y, z);

        // Let listeners veto the edit. A break is state 0 (air); anything else is a place. Cancelling
        // leaves the world untouched and re-sends the real block to the editor, so their client reverts
        // the change they optimistically drew. (Only posted when the editor is a known player.)
        if (editor != null && eventBus.hasListeners(state == Blocks.AIR ? BlockBreakEvent.class : BlockPlaceEvent.class)) {
            boolean cancelled = state == Blocks.AIR
                    ? eventBus.post(new BlockBreakEvent(editor, x, y, z, previous)).isCancelled()
                    : eventBus.post(new BlockPlaceEvent(editor, x, y, z, state, previous)).isCancelled();
            if (cancelled) {
                LOGGER.debug(() -> "[edit] cancelled " + x + "," + y + "," + z);
                editor.getConnection().sendBlockChange(x, y, z, previous);
                return;
            }
        }
        LOGGER.debug(() -> "[edit] APPLIED " + x + "," + y + "," + z + " state=" + state
                + " → " + playerRegistry.size() + " clients");

        // Apply to the shared world, then push the edit to every client (including the editor, so
        // the server stays authoritative). {@code state} is the canonical (id << 4 | meta) value;
        // each connection serializes it in its own protocol.
        defaultWorld.setBlockId(x, y, z, state);
        // A broken chest drops its container (contents lost — no item entities in the illusion).
        if (state == Blocks.AIR && Blocks.idOf(previous) == Blocks.CHEST) {
            defaultWorld.removeChestContainer(x, y, z);
        }
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

    @Override
    public void onWindowClick(PlayerConnection connection, int coreSlot, int button, boolean shift) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null || player.getGameMode() != GameMode.SURVIVAL) {
            return; // creative manages its own inventory client-side — don't apply or resync over it
        }
        Container inv = player.getInventory();
        Cursor cur = player.getCursor();
        // coreSlot < 0 = an unbacked slot (crafting grid) or a click mode we don't model — resync only.
        if (coreSlot >= 0 && coreSlot < inv.size()) {
            if (shift) {
                // Quick-move to the "other" region: hotbar↔main, and armor/off-hand back into storage.
                int from, to;
                if (coreSlot < 9) { from = 9; to = 36; }        // hotbar → main
                else if (coreSlot < 36) { from = 0; to = 9; }   // main → hotbar
                else { from = 0; to = 36; }                     // armor / off-hand → storage
                InventoryClick.shift(inv, coreSlot, from, to);
            } else {
                InventoryClick.normal(inv, cur, coreSlot, button == 1);
            }
        }
        // Server is authoritative: resync the whole window + cursor, overriding any client misprediction.
        player.syncInventory();
        connection.setCursorItem(cur.state(), cur.count());
    }

    @Override
    public void onWindowClose(PlayerConnection connection) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null) {
            return;
        }
        // In creative, only intervene when closing a chest — closing the creative menu must not disturb it.
        if (player.getGameMode() != GameMode.SURVIVAL && !player.hasContainerOpen()) {
            return;
        }
        player.closeContainer(); // a chest, if any, is no longer open
        Cursor cur = player.getCursor();
        // Return any carried item to storage; whatever doesn't fit is lost (no item entities to drop).
        while (!cur.isEmpty() && player.giveItem(cur.state()) >= 0) {
            cur.set(cur.state(), cur.count() - 1);
        }
        cur.clear();
        player.syncInventory();
        connection.setCursorItem(0, 0);
    }

    /**
     * Window id for a player's open chest (a player has at most one container open). Must be ≥ 2: MCPE
     * reserves 0/1 (PMMP assigns custom windows from {@code max(2, …)}) and a 1.1.5 client crashes on a
     * ContainerOpen with id 1; 119-122 are the off-hand / armor / creative / hotbar windows. 10 is safe
     * on every edition (Java accepts any container window id).
     */
    private static final int CHEST_WINDOW_ID = 10;

    @Override
    public boolean onUseBlock(PlayerConnection connection, int x, int y, int z) {
        if (Blocks.idOf(defaultWorld.getBlockId(x, y, z)) != Blocks.CHEST) {
            return false; // not interactable — let the caller place the held block
        }
        // A chest: consume the right-click (so no block is placed on it) and open it. Works in creative
        // too — the creative inventory is mirrored server-side via onCreativeSetSlot, so the chest's
        // player-inventory half is tracked.
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player != null) {
            Container chest = defaultWorld.getChestContainer(x, y, z);
            player.openContainer(CHEST_WINDOW_ID, chest);
            connection.openContainer(CHEST_WINDOW_ID, "Chest", 27, x, y, z);
            sendChestContents(player, connection);
        }
        return true;
    }

    @Override
    public boolean onChestInteract(PlayerConnection connection, int x, int y, int z, int heldSlot) {
        if (Blocks.idOf(defaultWorld.getBlockId(x, y, z)) != Blocks.CHEST) {
            return false; // not a chest — let the caller place the held block
        }
        // Click-transfer chest (the retail 1.1.5 client crashes on a real chest window): a plain
        // right-click withdraws the first stack, a sneaking right-click deposits the held hotbar slot.
        // Works in both modes — creative deposits its (infinite) held item without consuming it, and its
        // held item is mirrored server-side from the client's MobEquipment (see PeSession). The click is
        // always consumed so no block is placed on the chest.
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player != null) {
            Container chest = defaultWorld.getChestContainer(x, y, z);
            boolean creative = player.getGameMode() == GameMode.CREATIVE;
            if (player.isSneaking()) {
                chestDeposit(player, chest, heldSlot, creative);
            } else {
                chestWithdraw(player, chest, creative);
            }
        }
        return true; // it's a chest — always suppress the placement
    }

    /**
     * Right-click transfer out of the chest, one stack per click. In <b>survival</b> the first non-empty
     * stack moves into the player's inventory (as much as fits). In <b>creative</b> the player's inventory
     * is infinite and client-managed, so handing real items over would let a deposit→withdraw cycle mint
     * items (the reported duplication: deposit never consumes an infinite hand, so withdrawing the copy is
     * pure gain). Instead a creative click just removes the stack from the chest — an edit of its contents.
     */
    private void chestWithdraw(CorePlayer player, Container chest, boolean creative) {
        for (int i = 0; i < chest.size(); i++) {
            if (chest.isEmpty(i)) {
                continue;
            }
            int state = chest.stateAt(i);
            int have = chest.countAt(i);
            if (creative) {
                chest.clear(i);                 // no real items to a creative player — just clear the stack
                defaultWorld.markDirty();
                player.sendMessage("{gray}Убрано из сундука ×" + have);
                return;
            }
            int prev = -1, moved = 0;
            for (int c = 0; c < have; c++) {
                int slot = player.giveItem(state);
                if (slot < 0) break; // inventory full
                if (slot != prev) {
                    if (prev >= 0) player.syncSlot(prev);
                    prev = slot;
                }
                moved++;
            }
            if (prev >= 0) player.syncSlot(prev);
            if (moved > 0) {
                chest.set(i, have - moved > 0 ? state : 0, have - moved);
                defaultWorld.markDirty();
                player.sendMessage("{gray}Взято из сундука ×" + moved
                        + (moved < have ? " {dark_gray}(инвентарь полон)" : ""));
            }
            return; // one stack per click
        }
        player.sendMessage("{gray}Сундук пуст");
    }

    /**
     * Deposit the player's held hotbar slot ({@code heldSlot}, 0-8) into the chest (as much as fits).
     * The amount is exactly what the slot holds (in creative that comes from the client's MobEquipment
     * mirror). In survival the deposited items are consumed from the slot; in {@code creative} the hand is
     * infinite, so the slot is left untouched — but the count is honest, not a forced stack, so a
     * deposit→withdraw cycle can't inflate.
     */
    private void chestDeposit(CorePlayer player, Container chest, int heldSlot, boolean creative) {
        if (heldSlot < 0 || heldSlot >= 9) {
            return;
        }
        Container inv = player.getInventory();
        int state = inv.stateAt(heldSlot);
        int have = inv.countAt(heldSlot);
        if (state == 0 || have <= 0) {
            player.sendMessage("{gray}В руке ничего нет");
            return;
        }
        int moved = 0;
        for (int c = 0; c < have; c++) {
            if (chest.give(state, 0, chest.size()) < 0) break; // chest full
            moved++;
        }
        if (moved > 0) {
            if (!creative) { // survival consumes the deposited items; creative's are infinite
                inv.set(heldSlot, have - moved > 0 ? state : 0, have - moved);
                player.syncSlot(heldSlot);
            }
            defaultWorld.markDirty();
            player.sendMessage("{gray}Положено в сундук ×" + moved
                    + (moved < have ? " {dark_gray}(сундук полон)" : ""));
        } else {
            player.sendMessage("{gray}Сундук полон");
        }
    }

    @Override
    public void onChestClick(PlayerConnection connection, int windowSlot, int button, boolean shift) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null || !player.hasContainerOpen()) {
            return; // works in creative too — the chest window is server-authoritative in both modes
        }
        Container chest = player.getOpenContainer();
        Container inv = player.getInventory();
        // Chest-window layout: 0-26 the chest, 27-53 the player's main (core 9-35), 54-62 the hotbar (0-8).
        boolean inChest = windowSlot >= 0 && windowSlot < 27;
        Container target;
        int index;
        if (inChest) {
            target = chest; index = windowSlot;
        } else if (windowSlot >= 27 && windowSlot < 54) {
            target = inv; index = 9 + (windowSlot - 27);
        } else if (windowSlot >= 54 && windowSlot < 63) {
            target = inv; index = windowSlot - 54;
        } else {
            target = null; index = -1; // outside / unmodelled — resync only
        }
        if (target != null) {
            if (shift) {
                // Quick-move across the two containers: chest → player storage, or player → chest.
                if (inChest) {
                    InventoryClick.shiftTo(chest, index, inv, 0, 36);
                } else {
                    InventoryClick.shiftTo(inv, index, chest, 0, 27);
                }
            } else {
                InventoryClick.normal(target, player.getCursor(), index, button == 1);
            }
            defaultWorld.markDirty(); // a chest edit must be persisted by autosave / shutdown
        }
        sendChestContents(player, connection);
    }

    /**
     * Push the open chest window's contents. The two editions frame it differently: <b>Java</b> puts the
     * player inventory <em>inside</em> the chest window (27 chest + 27 main + 9 hotbar = 63 slots), and is
     * server-authoritative (also resyncs the cursor); <b>Bedrock</b>'s chest window is just the 27 chest
     * slots — the player inventory is the separate window 0 the client already owns.
     */
    private void sendChestContents(CorePlayer player, PlayerConnection connection) {
        Container chest = player.getOpenContainer();
        int windowId = player.getOpenWindowId();
        if (connection.getProtocolVersion().isBedrock()) {
            connection.setWindowItems(windowId, chest.states(), chest.counts());
            return;
        }
        int[] ps = player.inventoryStates();
        int[] pc = player.inventoryCounts();
        int[] states = new int[63];
        int[] counts = new int[63];
        for (int i = 0; i < 27; i++) {                 // chest
            states[i] = chest.stateAt(i);
            counts[i] = chest.countAt(i);
        }
        for (int i = 0; i < 27; i++) {                 // player main (core 9-35)
            states[27 + i] = ps[9 + i];
            counts[27 + i] = pc[9 + i];
        }
        for (int i = 0; i < 9; i++) {                  // player hotbar (core 0-8)
            states[54 + i] = ps[i];
            counts[54 + i] = pc[i];
        }
        connection.setWindowItems(windowId, states, counts);
        connection.setCursorItem(player.getCursor().state(), player.getCursor().count());
    }

    @Override
    public void onContainerSetSlot(PlayerConnection connection, int windowId, int slot, int state, int count) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null || slot < 0) {
            return;
        }
        // Bedrock is client-authoritative for an open chest window: it already moved the item and just
        // tells us the new slot value.
        if (player.hasContainerOpen() && windowId == player.getOpenWindowId()) {
            Container chest = player.getOpenContainer();
            if (slot < chest.size()) {
                chest.set(slot, state, count);
                defaultWorld.markDirty();
            }
        } else if (windowId == 0 && player.getGameMode() == GameMode.CREATIVE) {
            // The player's own inventory (PE window 0: 0-8 hotbar, 9-35 main). Only trust the client's
            // report in CREATIVE, where the inventory is client-authoritative and we merely mirror it so a
            // chest deposit knows the held item. In SURVIVAL the server owns the inventory (mining, placing
            // and chest transfers all flow through it), so a client echo must be IGNORED — otherwise the
            // 1.1.5 client's ContainerSetSlot echo right after a chest deposit re-adds the just-moved stack,
            // duplicating it (the item ends up in the chest AND back in the inventory).
            Container inv = player.getInventory();
            if (slot < 36) {
                inv.set(slot, state, count);
            }
        }
    }

    @Override
    public void onCreativeSetSlot(PlayerConnection connection, int coreSlot, int state, int count) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null || player.getGameMode() != GameMode.CREATIVE) {
            return; // only a creative client sets slots this way — ignore an unexpected survival sender
        }
        Container inv = player.getInventory();
        if (coreSlot >= 0 && coreSlot < inv.size()) {
            inv.set(coreSlot, state, count); // mirror only; the creative client already shows it
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
        // Let listeners veto the interaction before anything acts on it — no damage, no puppet callback.
        if (eventBus.hasListeners(PlayerInteractEntityEvent.class)
                && eventBus.post(new PlayerInteractEntityEvent(attacker, targetEntityId)).isCancelled()) {
            return;
        }
        CorePlayer victim = playerRegistry.getByEntityIdOrNull(targetEntityId);
        if (victim == null) {
            // Not a player — maybe a puppet. Fire its interaction hook (the seam the API subscribes to)
            // and, as a visible demo, flash the puppet red on every client. A puppet has no health/damage.
            CorePuppet puppet = puppets.get(targetEntityId);
            if (puppet != null) {
                puppet.fireInteract(attacker);
                for (CorePlayer p : playerRegistry.online()) {
                    p.getConnection().playHurtAnimation(targetEntityId);
                }
            }
            return;
        }
        if (victim == attacker) {
            return; // a self-hit — nothing to do
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
        // Let listeners edit or veto the line before it goes out. Cancelling suppresses it entirely.
        PlayerChatEvent event = eventBus.post(new PlayerChatEvent(sender, message));
        if (event.isCancelled()) {
            LOGGER.debug(() -> "[chat] cancelled <" + sender.getName() + "> " + message);
            return;
        }
        // The name is escaped so an untrusted username (a 0.14 client picks its own; a '_' would
        // otherwise italicise) can't inject markup. The message body is intentionally left raw —
        // rendering it is the documented "players can use {color} / Markdown in chat" feature.
        String line = event.getFormat()
                .replace("%name%", ChatText.escape(sender.getName()))
                .replace("%s", event.getMessage());
        LOGGER.info("[chat] <" + sender.getName() + "> " + event.getMessage());
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

    /** Broadcast a system line to every online player (used by {@code /say} and the console {@code say}). */
    public void broadcast(String message) {
        broadcast(message, null);
    }

    /**
     * Kill a survival player through the normal damage path — a silent respawn at spawn with a death
     * message, exactly like a lethal fall. A no-op in creative (which takes no damage); the caller is
     * expected to tell the player so.
     *
     * @return {@code true} if the player was killed (survival), {@code false} if immune (creative)
     */
    public boolean kill(CorePlayer player) {
        if (player.getGameMode() != GameMode.SURVIVAL) {
            return false;
        }
        hurt(player, CorePlayer.MAX_HEALTH, "{gray}" + ChatText.escape(player.getName()) + " died");
        return true;
    }

    /**
     * Restore a survival player to full health and push it to their HUD. A no-op in creative (which has
     * no health to restore); the caller is expected to say so.
     *
     * @return {@code true} if the player was healed (survival), {@code false} if not applicable (creative)
     */
    public boolean heal(CorePlayer player) {
        if (player.getGameMode() != GameMode.SURVIVAL) {
            return false;
        }
        player.setHealth(CorePlayer.MAX_HEALTH);
        player.getConnection().setHealth(CorePlayer.MAX_HEALTH);
        return true;
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
