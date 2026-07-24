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
import com.jedrock.api.event.player.GameModeChangeEvent;
import com.jedrock.api.event.player.PlayerChatEvent;
import com.jedrock.api.event.player.PlayerCommandEvent;
import com.jedrock.api.event.player.PlayerJoinEvent;
import com.jedrock.api.event.player.PlayerLoginEvent;
import com.jedrock.api.event.player.PlayerMoveEvent;
import com.jedrock.api.event.player.PlayerPickupItemEvent;
import com.jedrock.api.event.player.PlayerQuitEvent;
import com.jedrock.api.event.player.PlayerTeleportEvent;
import com.jedrock.api.event.player.PlayerToggleSneakEvent;
import com.jedrock.api.event.player.PlayerToggleSprintEvent;
import com.jedrock.api.event.player.PlayerUseItemEvent;
import com.jedrock.api.event.server.ServerStartEvent;
import com.jedrock.api.event.server.ServerStopEvent;
import com.jedrock.api.event.server.ServerTickEvent;
import com.jedrock.api.player.ArmorSlot;
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
import com.jedrock.core.command.WeatherCommand;
import com.jedrock.core.command.HealCommand;
import com.jedrock.core.command.HelpCommand;
import com.jedrock.core.command.KillCommand;
import com.jedrock.core.command.ListCommand;
import com.jedrock.core.command.MeCommand;
import com.jedrock.core.command.MsgCommand;
import com.jedrock.core.command.OpCommand;
import com.jedrock.core.command.DeopCommand;
import com.jedrock.core.command.PermCommand;
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
import com.jedrock.core.entity.EntityDirector;
import com.jedrock.core.entity.PuppetRegistry;
import com.jedrock.core.inventory.ContainerService;
import com.jedrock.core.permission.OpList;
import com.jedrock.core.permission.PermissionManager;
import com.jedrock.core.plugin.PluginManager;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerBroadcast;
import com.jedrock.core.net.PacketDirection;
import com.jedrock.core.net.PacketEvent;
import com.jedrock.core.net.PacketTapRegistry;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.core.world.LevelManager;
import com.jedrock.gameloop.GameLoop;
import com.jedrock.gameloop.Scheduler;
import com.jedrock.network.ConnectionListener;
import com.jedrock.network.NetworkServer;
import com.jedrock.network.NettyNetworkServer;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.TickUtil;
import com.jedrock.utils.text.ChatText;

import java.net.InetSocketAddress;
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
    private final CommandManager commandManager = new CommandManager(this);
    /** Server operators (name-based), persisted to {@code ops.txt}; an op holds every permission. */
    private final OpList opList = new OpList(Path.of("ops.txt"));
    /** Group-based permissions (wildcards, deny, inheritance, prefixes), persisted to {@code permissions.txt}. */
    private final PermissionManager permissions = new PermissionManager(Path.of("permissions.txt"));
    /** Raw packet taps (the {@code packets} scripting hook); consulted by the network layer via this listener. */
    private final PacketTapRegistry packetTaps = new PacketTapRegistry();
    private final NetworkServer networkServer;

    /** The scripting layer: JS plugins in {@code plugins/} that subscribe to events and register commands. */
    private final PluginManager plugins =
            new PluginManager(eventBus, this, scheduler, commandManager, packetTaps, Path.of("plugins"));
    /** How often (ms) the background watcher polls the plugins folder for changed files to hot-reload. */
    private static final long PLUGIN_RELOAD_MILLIS = 1000L;

    // In-memory state layer
    private final PlayerRegistry playerRegistry = new PlayerRegistry();
    /** The one place that walks the roster and pushes something to every connection. */
    private final PlayerBroadcast broadcast = new PlayerBroadcast(playerRegistry);
    /** Puppets and holograms: everything shown that is neither a player nor a block. */
    private final EntityDirector entities;
    /** Windows, chests and the creative mirror — every item that moves between slots. */
    private final ContainerService containers;
    /** Fall, void and melee — the one path from a source of damage to a health bar. */
    private final CombatService combat;
    /** The world's life on disk: the one-time bake, the autosave and the shutdown write. */
    private final LevelManager levels;
    private final CoreWorld defaultWorld;
    private final BlindJudge judge;
    /** Remembers each player's last chosen game mode this run, so it survives a reconnect. */
    private final java.util.Map<UUID, GameMode> gameModes = new java.util.concurrent.ConcurrentHashMap<>();

    private final AtomicLong tickCounter = new AtomicLong(0);

    public JedrockServer() {
        // Load configuration first — everything below is parameterized by it.
        this.config = JedrockConfig.load();
        this.name = config.name();
        this.defaultWorld = new CoreWorld("world", Dimension.OVERWORLD, config.seed());
        this.judge = new BlindJudge(config.judgeEnabled(), config.maxReach(), config.maxMoveDelta());
        this.entities = new EntityDirector(playerRegistry, defaultWorld);
        this.containers = new ContainerService(playerRegistry, defaultWorld, eventBus, broadcast);
        this.combat = new CombatService(playerRegistry, defaultWorld, eventBus, broadcast, entities, judge);
        this.levels = new LevelManager(defaultWorld, eventBus);

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
        commandManager.register(new WeatherCommand());
        commandManager.register(new TeleportCommand());
        commandManager.register(new TpHereCommand());
        commandManager.register(new TpAllCommand());
        commandManager.register(new SpawnCommand());
        commandManager.register(new HealCommand());
        commandManager.register(new KillCommand());
        commandManager.register(new ClearCommand());
        commandManager.register(new PuppetCommand());
        commandManager.register(new HologramCommand());
        commandManager.register(new OpCommand());
        commandManager.register(new DeopCommand());
        commandManager.register(new PermCommand());
    }

    /** The in-game command registry — used by commands (e.g. {@code /help}) to introspect. */
    public CommandManager getCommandManager() {
        return commandManager;
    }

    /** The server operator list — used by {@code /op} / {@code /deop} and permission checks. */
    public OpList getOpList() {
        return opList;
    }

    /** The group-based permission system — used by {@code /perm} and by permission checks. */
    public PermissionManager getPermissions() {
        return permissions;
    }

    @Override
    public List<ConnectionListener.CommandInfo> commands() {
        // Bedrock refuses to send a command it wasn't advertised; the PE session turns this into an
        // AvailableCommands manifest at spawn. Java clients need nothing.
        var registered = commandManager.commands();
        List<ConnectionListener.CommandInfo> out = new java.util.ArrayList<>(registered.size());
        for (com.jedrock.core.command.Command c : registered) {
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
    public boolean setGameMode(CorePlayer player, GameMode mode) {
        // Listeners may veto the switch or redirect it to a different mode.
        if (eventBus.hasListeners(GameModeChangeEvent.class)) {
            GameModeChangeEvent event = eventBus.post(
                    new GameModeChangeEvent(player, player.getGameMode(), mode));
            if (event.isCancelled()) {
                return false;
            }
            mode = event.getNewGameMode();
        }
        gameModes.put(player.getUniqueId(), mode);
        player.setGameMode(mode);
        // Entering survival, show the survival inventory (empty, or what's been mined) so any items the
        // creative menu left in hand don't linger.
        if (mode == GameMode.SURVIVAL) {
            player.syncInventory();
        }
        return true;
    }

    /**
     * Server-authoritative teleport: reposition {@code player} and relay the move to every other
     * player's avatar. Bypasses the blind judge (the server initiated it) and the edge wall; callers
     * are responsible for sane destinations.
     */
    public boolean teleport(CorePlayer player, Location to) {
        // Listeners may veto the teleport or redirect it elsewhere. A respawn is not routed here (it fires
        // PlayerRespawnEvent and repositions directly) so a cancelled teleport can never strand a dead player.
        if (eventBus.hasListeners(PlayerTeleportEvent.class)) {
            PlayerTeleportEvent event = eventBus.post(new PlayerTeleportEvent(player, player.getLocation(), to));
            if (event.isCancelled()) {
                return false;
            }
            to = event.getTo();
        }
        broadcast.teleport(player, to);
        return true;
    }

    private void serverTick(long currentTick) {
        // Core server-wide per-tick work goes here.
        // Keep this extremely lean.
        this.tickCounter.set(currentTick);

        // Tick network (keep-alives, etc.)
        networkServer.tick(currentTick);

        // Environmental damage runs on the loop (not per move) so it fires even for a player who has
        // stopped sending position updates. The service gates itself to a coarse interval.
        combat.environmentTick(currentTick);

        // The scriptable heartbeat: fire a tick event for listeners hanging periodic work on the loop.
        // Only built when something is listening, so an idle server pays nothing for it.
        if (eventBus.hasListeners(ServerTickEvent.class)) {
            eventBus.post(new ServerTickEvent(currentTick));
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
        levels.prepare();

        // From here on, every world write — a player's edit or a script/API call — is pushed to each
        // online client in its own protocol, so World.setBlockId is all an edit needs to be visible.
        // Registered after the bake so the one-time generation doesn't fire millions of callbacks.
        defaultWorld.setChangeListener((x, y, z, state) -> {
            // Iterates the registry's live view: a script `world.fill` walks this per changed cell,
            // so the loop must not mint a collection wrapper on every block it writes.
            for (CorePlayer p : playerRegistry.online()) {
                p.getConnection().sendBlockChange(x, y, z, state);
            }
        });

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
            scheduler.runTaskTimer(levels::autosave, periodTicks, periodTicks);
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

        // Load script plugins before ServerStartEvent, so a plugin can subscribe to it. A background
        // watcher (off the game-loop thread — the poll blocks on disk I/O) hot-reloads a saved edit.
        plugins.loadAll();
        plugins.startWatching(PLUGIN_RELOAD_MILLIS);

        // Everything is up — let plugins do their one-time setup.
        eventBus.post(new ServerStartEvent());
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

        // Tell plugins first, while the world and players are still alive, then tear the scripts down
        // (their onDisable runs, their listeners are removed) before the world and loop go away.
        eventBus.post(new ServerStopEvent());
        plugins.unloadAll();

        gameLoop.stop();
        networkServer.shutdown();

        // Save last, with the loop stopped and listeners closed, so no edit races the snapshot.
        levels.saveIfDirty();

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
    public Optional<Player> getPlayer(java.util.UUID uuid) {
        return playerRegistry.getById(uuid);
    }

    @Override
    public Collection<World> getWorlds() {
        return List.of(defaultWorld);
    }

    @Override
    public Optional<World> getWorld(String name) {
        return defaultWorld.getName().equalsIgnoreCase(name) ? Optional.of(defaultWorld) : Optional.empty();
    }

    @Override
    public World getDefaultWorld() {
        return defaultWorld;
    }

    // ===== Puppets and holograms — the EntityDirector does the work =====

    @Override
    public PuppetEntity spawnPuppet(EntityType type, Location at, String name) {
        return entities.spawnPuppet(type, at, name);
    }

    @Override
    public PuppetEntity spawnItem(Location at, int state) {
        return entities.spawnItem(at, state);
    }

    @Override
    public PuppetEntity spawnFallingBlock(Location at, int state) {
        return entities.spawnFallingBlock(at, state);
    }

    @Override
    public PuppetEntity spawnText(Location at, String text) {
        return entities.spawnText(at, text);
    }

    @Override
    public Hologram spawnHologram(Location at, String... lines) {
        return entities.spawnHologram(at, lines);
    }

    /** The puppeteer — puppets, holograms, and every relay that shows them cross-edition. */
    public EntityDirector getEntities() {
        return entities;
    }

    /** The live puppet roster — used by the {@code /puppet} command to list / resolve puppets. */
    public PuppetRegistry getPuppets() {
        return entities.getPuppets();
    }

    /** The live holograms — used by the {@code /hologram} command to list / resolve them. */
    public List<CoreHologram> getHolograms() {
        return entities.getHolograms();
    }

    /** The script plugin manager — used by the console {@code plugins} command. */
    public PluginManager getPlugins() {
        return plugins;
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

        // The gate: a whitelist / ban check runs here, before any state is created for the player.
        // Cancelling disconnects the client and nothing (registry, avatar, world entry) is set up.
        if (eventBus.hasListeners(PlayerLoginEvent.class)) {
            PlayerLoginEvent login = eventBus.post(
                    new PlayerLoginEvent(uuid, username, connection.getAddress()));
            if (login.isCancelled()) {
                connection.close(login.getKickReason() != null ? login.getKickReason() : "Connection refused");
                return;
            }
        }

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
        CorePlayer player = new CorePlayer(uuid, username, connection, defaultWorld, spawn,
                gameModeFor(uuid), eventBus);
        player.setPermissions(opList, permissions); // isOp/hasPermission/getPrefix resolve against these
        // Changed equipment redraws on every other client's copy of this avatar.
        player.setEquipmentListener(new CorePlayer.EquipmentListener() {
            @Override
            public void heldItemChanged(CorePlayer p) {
                broadcast.heldItem(p);
            }

            @Override
            public void armorChanged(CorePlayer p) {
                broadcast.armor(p);
            }
        });

        playerRegistry.add(player);
        defaultWorld.addPlayer(player);

        PlayerJoinEvent event = new PlayerJoinEvent(player);
        // Seed the default announcement so a listener sees it and can restyle, replace or suppress it
        // (null / empty = no broadcast) — the same contract as the death message.
        event.setJoinMessage("{yellow}" + ChatText.escape(username) + " joined the game");
        eventBus.post(event);

        if (event.isCancelled()) {
            // A listener refused the join — undo the state we just added and drop the client.
            defaultWorld.removePlayer(player);
            playerRegistry.removeByConnection(connection);
            player.kick("Connection refused");
            return;
        }

        player.sendMessage("{green}Welcome to **Jedrock**!");
        String joinMessage = event.getJoinMessage();
        if (joinMessage != null && !joinMessage.isEmpty()) {
            broadcast.message(joinMessage, player);
        }

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
            // …and whatever they're holding and wearing, so a spawned avatar isn't bare until it changes.
            int otherHeld = other.getHeldItem();
            if (otherHeld != Blocks.AIR) {
                connection.showHeldItem(other.getEntityId(), otherHeld);
            }
            if (other instanceof CorePlayer oc && oc.hasArmor()) {
                connection.showArmor(other.getEntityId(),
                        oc.getArmor(ArmorSlot.HELMET), oc.getArmor(ArmorSlot.CHESTPLATE),
                        oc.getArmor(ArmorSlot.LEGGINGS), oc.getArmor(ArmorSlot.BOOTS));
            }
            other.getConnection().showPlayer(uuid, username, player.getEntityId(),
                    spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
        }

        // Show every existing puppet and hologram to the newcomer (existing players already see them).
        entities.showAllTo(connection);

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
        PlayerQuitEvent event = new PlayerQuitEvent(player);
        event.setQuitMessage("{yellow}" + ChatText.escape(player.getName()) + " left the game");
        eventBus.post(event);
        String quitMessage = event.getQuitMessage();
        if (quitMessage != null && !quitMessage.isEmpty()) {
            broadcast.message(quitMessage, null);
        }
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
                combat.applyFallDamage(player, fell);
            }
        }

        // Relay at the sender's own rate; both clients interpolate between updates. Iterate the live
        // roster view directly and skip the Optional/capturing lambda, so a move packet allocates
        // only the immutable Location snapshot (kept immutable so getLocation stays an atomic swap).
        broadcast.move(player, x, y, z, yaw, pitch);
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

        // Apply to the shared world; the world's change listener pushes the edit to every client
        // (including the editor, so the server stays authoritative). {@code state} is the canonical
        // (id << 4 | meta) value; each connection serializes it in its own protocol.
        defaultWorld.setBlockId(x, y, z, state);
        // A broken chest drops its container (contents lost — no item entities in the illusion).
        if (state == Blocks.AIR && Blocks.idOf(previous) == Blocks.CHEST) {
            defaultWorld.removeChestContainer(x, y, z);
        }

        // Minimal survival inventory: mining a block drops it straight into the inventory, placing one
        // consumes it. Creative is untouched (the client has the creative menu). state 0 = air = a break.
        // Push just the changed slot so the hotbar HUD refreshes live (a full resend didn't).
        if (editor != null && editor.getGameMode() == GameMode.SURVIVAL) {
            int slot;
            if (state == Blocks.AIR) {
                // A break hands the mined block to the miner (no item entities) — a listener may veto the
                // pickup, leaving the block broken but uncollected.
                if (eventBus.hasListeners(PlayerPickupItemEvent.class)
                        && eventBus.post(new PlayerPickupItemEvent(editor, previous)).isCancelled()) {
                    return;
                }
                slot = editor.addToInventory(previous);
            } else {
                slot = editor.takeItem(state);
            }
            if (slot >= 0) {
                editor.syncSlot(slot);
            }
        }
    }

    @Override
    public void onFall(PlayerConnection connection, float fallDistance) {
        combat.onFall(connection, fallDistance);
    }

    @Override
    public int getOnlinePlayerCount() {
        return playerRegistry.size();
    }

    // ===== Packet taps: bridge the network layer's raw-packet hooks to the tap registry =====

    @Override
    public boolean hasPacketTaps() {
        return packetTaps.hasTaps();
    }

    @Override
    public boolean onInboundPacket(PlayerConnection connection, int packetId, byte[] payload) {
        return packetTaps.dispatch(new PacketEvent(connection.getProtocolVersion(), PacketDirection.INBOUND,
                packetId, payload, playerRegistry.getByConnectionOrNull(connection), connection));
    }

    @Override
    public boolean onOutboundPacket(PlayerConnection connection, int packetId, byte[] payload) {
        return packetTaps.dispatch(new PacketEvent(connection.getProtocolVersion(), PacketDirection.OUTBOUND,
                packetId, payload, playerRegistry.getByConnectionOrNull(connection), connection));
    }

    @Override
    public void onSneak(PlayerConnection connection, boolean sneaking) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null) {
            return;
        }
        // Cancelling makes the server ignore the toggle — the pose isn't recorded (so a late joiner and
        // the sneak-to-deposit chest check don't see it) and isn't relayed.
        if (eventBus.hasListeners(PlayerToggleSneakEvent.class)
                && eventBus.post(new PlayerToggleSneakEvent(player, sneaking)).isCancelled()) {
            return;
        }
        player.setSneaking(sneaking);
        broadcast.pose(player);
    }

    @Override
    public void onSprint(PlayerConnection connection, boolean sprinting) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null) {
            return;
        }
        if (eventBus.hasListeners(PlayerToggleSprintEvent.class)
                && eventBus.post(new PlayerToggleSprintEvent(player, sprinting)).isCancelled()) {
            return;
        }
        player.setSprinting(sprinting);
        broadcast.pose(player);
    }

    @Override
    public void onUseItem(PlayerConnection connection, boolean using) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null) {
            return;
        }
        if (eventBus.hasListeners(PlayerUseItemEvent.class)
                && eventBus.post(new PlayerUseItemEvent(player, using)).isCancelled()) {
            return;
        }
        player.setUsingItem(using);
        broadcast.pose(player);
    }

    @Override
    public void onSwingArm(PlayerConnection connection) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null) {
            return;
        }
        broadcast.swing(player);
    }

    // ===== Inventories, windows and chests — the ContainerService does the work =====

    @Override
    public void onWindowClick(PlayerConnection connection, int coreSlot, int button, boolean shift) {
        containers.onWindowClick(connection, coreSlot, button, shift);
    }

    @Override
    public void onWindowClose(PlayerConnection connection) {
        containers.onWindowClose(connection);
    }

    @Override
    public boolean onUseBlock(PlayerConnection connection, int x, int y, int z) {
        return containers.onUseBlock(connection, x, y, z);
    }

    @Override
    public boolean onChestInteract(PlayerConnection connection, int x, int y, int z, int heldSlot) {
        return containers.onChestInteract(connection, x, y, z, heldSlot);
    }

    @Override
    public void onChestClick(PlayerConnection connection, int windowSlot, int button, boolean shift) {
        containers.onChestClick(connection, windowSlot, button, shift);
    }

    @Override
    public void onContainerSetSlot(PlayerConnection connection, int windowId, int slot, int state, int count) {
        containers.onContainerSetSlot(connection, windowId, slot, state, count);
    }

    @Override
    public void onCreativeSetSlot(PlayerConnection connection, int coreSlot, int state, int count) {
        containers.onCreativeSetSlot(connection, coreSlot, state, count);
    }

    @Override
    public void onHeldSlotChange(PlayerConnection connection, int slot) {
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player != null) {
            player.setHeldItemSlot(slot);
            broadcast.heldItem(player);
        }
    }

    @Override
    public void onAttack(PlayerConnection connection, long targetEntityId) {
        combat.onAttack(connection, targetEntityId);
    }

    @Override
    public void onChat(PlayerConnection connection, String message) {
        CorePlayer sender = playerRegistry.getByConnectionOrNull(connection);
        if (sender == null) {
            return;
        }
        // A line starting with '/' is a command, not chat — dispatch it and never broadcast it. Listeners
        // may rewrite the line or cancel it (a plugin that fully handled the command cancels so the core
        // doesn't also try). The event carries the line without its leading slash.
        if (message.startsWith("/")) {
            String command = message.substring(1);
            if (eventBus.hasListeners(PlayerCommandEvent.class)) {
                PlayerCommandEvent event = eventBus.post(new PlayerCommandEvent(sender, command));
                if (event.isCancelled()) {
                    return;
                }
                command = event.getCommand();
            }
            commandManager.dispatch(sender, "/" + command);
            return;
        }
        // Let listeners edit or veto the line before it goes out (cancel suppresses it). Only built when a
        // listener wants it — with none, the default format applies and no event is allocated.
        String format = PlayerChatEvent.DEFAULT_FORMAT;
        String body = message;
        if (eventBus.hasListeners(PlayerChatEvent.class)) {
            PlayerChatEvent event = eventBus.post(new PlayerChatEvent(sender, message));
            if (event.isCancelled()) {
                LOGGER.debug(() -> "[chat] cancelled <" + sender.getName() + "> " + message);
                return;
            }
            format = event.getFormat();
            body = event.getMessage();
        }
        // The real name is escaped so an untrusted username (a 0.14 client picks its own; a '_' would
        // otherwise italicise) can't inject markup. A script-set display name, like the group prefix,
        // is authored text and renders raw — coloured nicknames are its whole point. The message body
        // is intentionally left raw — "players can use {color} / Markdown in chat" is a feature.
        String shown = sender.getDisplayName();
        String nameToken = shown.equals(sender.getName()) ? ChatText.escape(shown) : shown;
        String line = format.replace("%prefix%", sender.getPrefix())
                .replace("%name%", nameToken).replace("%s", body);
        LOGGER.info("[chat] <" + sender.getName() + "> " + body);
        broadcast.message(line, null); // relay to everyone, including the sender
    }

    /** Broadcast a system line to every online player (used by {@code /say} and the console {@code say}). */
    @Override
    public void broadcast(String message) {
        broadcast.message(message, null);
    }

    @Override
    public void dispatchCommand(Player player, String commandLine) {
        if (!(player instanceof CorePlayer sender) || commandLine == null) {
            return;
        }
        String line = commandLine.startsWith("/") ? commandLine : "/" + commandLine;
        commandManager.dispatch(sender, line);
    }

    /**
     * Kill a survival player through the normal damage path — a silent respawn at spawn with a death
     * message, exactly like a lethal fall. A no-op in creative (which takes no damage); the caller is
     * expected to tell the player so.
     *
     * @return {@code true} if the player was killed (survival), {@code false} if immune (creative)
     */
    public boolean kill(CorePlayer player) {
        return combat.kill(player);
    }

    /**
     * Restore a survival player to full health and push it to their HUD. A no-op in creative (which has
     * no health to restore); the caller is expected to say so.
     *
     * @return {@code true} if the player was healed (survival), {@code false} if not applicable (creative)
     */
    public boolean heal(CorePlayer player) {
        return combat.heal(player);
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
