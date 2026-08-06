package com.jedrock.core;

import com.jedrock.api.Jedrock;
import com.jedrock.api.Server;
import com.jedrock.api.ServerStatus;
import com.jedrock.api.config.ServerProperties;
import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.Hologram;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.player.GameModeChangeEvent;
import com.jedrock.api.event.player.PlayerTeleportEvent;
import com.jedrock.api.event.server.ServerStartEvent;
import com.jedrock.api.event.server.ServerStopEvent;
import com.jedrock.api.event.server.ServerTickEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.core.command.AboutCommand;
import com.jedrock.core.command.BanCommand;
import com.jedrock.core.command.BanIpCommand;
import com.jedrock.core.command.BanListCommand;
import com.jedrock.core.command.KickCommand;
import com.jedrock.core.command.MuteCommand;
import com.jedrock.core.command.PardonCommand;
import com.jedrock.core.command.PlayerInfoCommand;
import com.jedrock.core.command.SeenCommand;
import com.jedrock.core.command.WhitelistCommand;
import com.jedrock.core.command.ClearCommand;
import com.jedrock.core.command.CommandManager;
import com.jedrock.core.command.GameModeCommand;
import com.jedrock.core.command.GiveCommand;
import com.jedrock.core.command.EffectCommand;
import com.jedrock.core.command.EnchantCommand;
import com.jedrock.core.command.WeatherCommand;
import com.jedrock.core.command.WorldCommand;
import com.jedrock.core.command.HealCommand;
import com.jedrock.core.command.HelpCommand;
import com.jedrock.core.command.KillCommand;
import com.jedrock.core.command.ListCommand;
import com.jedrock.core.command.MeCommand;
import com.jedrock.core.command.MsgCommand;
import com.jedrock.core.command.OpCommand;
import com.jedrock.core.command.DeopCommand;
import com.jedrock.core.command.PermCommand;
import com.jedrock.core.command.PoseCommand;
import com.jedrock.core.command.PickCommand;
import com.jedrock.core.command.RegionCommand;
import com.jedrock.core.command.SayCommand;
import com.jedrock.core.command.SpawnCommand;
import com.jedrock.core.command.TeleportCommand;
import com.jedrock.core.command.TimeCommand;
import com.jedrock.core.command.TpAllCommand;
import com.jedrock.core.command.TpHereCommand;
import com.jedrock.core.command.TpsCommand;
import com.jedrock.core.command.HologramCommand;
import com.jedrock.core.command.PuppetCommand;
import com.jedrock.core.config.JedrockConfig;
import com.jedrock.core.config.PipelineConfig;
import com.jedrock.core.config.ServerLayout;
import com.jedrock.core.entity.CoreHologram;
import com.jedrock.core.entity.EntityDirector;
import com.jedrock.core.entity.PuppetRegistry;
import com.jedrock.core.inventory.ContainerService;
import com.jedrock.core.permission.OpList;
import com.jedrock.core.permission.PermissionManager;
import com.jedrock.core.plugin.PluginManager;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerBroadcast;
import com.jedrock.core.net.PacketTapRegistry;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.player.PlayerTracker;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.core.world.WorldManager;
import com.jedrock.gameloop.GameLoop;
import com.jedrock.gameloop.Scheduler;
import com.jedrock.network.ConnectionListener;
import com.jedrock.network.NetworkServer;
import com.jedrock.network.NettyNetworkServer;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.TickUtil;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reference implementation of the Server interface: the server's own life — config, the collaborators it
 * owns, bootstrap and shutdown, the tick, and the api surface a script or a command sees.
 *
 * <p>What it deliberately is <em>not</em> is the thing the network talks to. Every inbound report — a
 * login, a move, a block edit, a chat line — lands on {@link ConnectionBridge}, which is what the network
 * layer holds as its {@code ConnectionListener}. The two responsibilities never shared a line of code,
 * and keeping them apart is what stops this class regrowing into the 1800-line version it once was.
 *
 * Design principles applied:
 * - As little state as possible
 * - Delegates to specialized modules (gameloop, network)
 * - Lazy-friendly (no eager world/player loading)
 */
public class JedrockServer implements Server {

    private static final JLogger LOGGER = JLogger.getLogger(JedrockServer.class);

    private final ServerProperties config;
    private final String name;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final EventBus eventBus = new EventBus();
    private final GameLoop gameLoop = new GameLoop();
    private final Scheduler scheduler = new Scheduler();
    private final CommandManager commandManager = new CommandManager(this);
    /** Where everything this server writes lives — made once, asked for by name from then on. */
    private final ServerLayout layout;
    /** Server operators (name-based), persisted to {@code data/ops.txt}; an op holds every permission. */
    private final OpList opList;
    /** Group-based permissions (wildcards, deny, inheritance, prefixes), in {@code data/permissions.txt}. */
    private final PermissionManager permissions;
    /** Raw packet taps (the {@code packets} scripting hook); consulted by the network layer via this listener. */
    private final PacketTapRegistry packetTaps = new PacketTapRegistry();
    private final NetworkServer networkServer;
    /** Network → core: every inbound decision, kept out of this class (see {@link ConnectionBridge}). */
    private final ConnectionBridge bridge;
    /** The console over a socket, if {@code rcon.enabled} and it managed to bind. Null otherwise. */
    private volatile RconServer rcon;
    /** Where the small persistent facts go — files by default, a database if one was asked for. */
    private final com.jedrock.core.data.DataStore storage;
    /** Who may be here and who may speak: bans, ip-bans, mutes, the whitelist, and when people were last on. */
    private final com.jedrock.core.moderation.Moderation moderation;

    /** The scripting layer: JS plugins in {@code plugins/} that subscribe to events and register commands. */
    private final PluginManager plugins;
    /** Where script state lives between restarts — a runtime file, next to ops.txt and permissions.txt. */
    private final Path pluginStorageFile;

    // In-memory state layer
    private final PlayerRegistry playerRegistry = new PlayerRegistry();
    /** The one place that walks the roster and pushes something to every connection. */
    private final PlayerBroadcast broadcast = new PlayerBroadcast(playerRegistry);
    /** Who can see whom — the interest set the avatar relays iterate instead of the roster. */
    private final PlayerTracker tracker;
    /** Puppets and holograms: everything shown that is neither a player nor a block. */
    private final EntityDirector entities;
    /** Named arrangements of props that outlive the script that built them, and the process. */
    private final com.jedrock.core.entity.SceneManager scenes;
    private final com.jedrock.core.region.RegionManager regions;
    private final com.jedrock.core.item.ItemRegistry items;
    /** Windows, chests and the creative mirror — every item that moves between slots. */
    private final ContainerService containers;
    /** Fall, void and melee — the one path from a source of damage to a health bar. */
    private final CombatService combat;
    /** Who is under what, and for how long — scenery the client animates, mostly. */
    private final com.jedrock.core.effect.EffectService effects;
    /** Putting an enchantment on a stack — the one path, whoever asked. */
    private final com.jedrock.core.item.EnchantService enchants;
    /** The one path from a world to another world, for a player standing in the first. */
    private final WorldTravel travel;
    /** Every world the server has, and the only thing allowed to make one. */
    private final WorldManager levels;
    /** The world players join into — the manager owns it, this is the handle everything else was built on. */
    private final CoreWorld defaultWorld;
    private final BlindJudge judge;
    /** Remembers each player's last chosen game mode this run, so it survives a reconnect. */
    private final java.util.Map<UUID, GameMode> gameModes = new java.util.concurrent.ConcurrentHashMap<>();
    /** Which world each player was last in — the same idea as {@link #gameModes}, but across restarts. */
    private final com.jedrock.core.player.PlayerWorlds playerWorlds;

    private final AtomicLong tickCounter = new AtomicLong(0);

    public JedrockServer() {
        this(Path.of(System.getProperty("jedrock.home", ".")));
    }

    /**
     * Bring the server up under {@code home} — the folder it lays itself out in. Everything it will ever
     * write is decided here, in this order and no other: the config says where the folders are, the layout
     * makes them, and only then is there anywhere for a world, a log or a script's storage to go.
     */
    public JedrockServer(Path home) {
        // Absolute from here on: every path this server ever prints or writes is derived from this one,
        // and "./worlds/./world" in a log is a small thing that makes a server look unfinished.
        Path root = home.toAbsolutePath().normalize();
        // Load configuration first — everything below is parameterized by it.
        this.config = JedrockConfig.load(root);
        this.name = config.name();
        // The folders, made (and an older flat install migrated into them) before anything writes.
        this.layout = ServerLayout.prepare(root, config);
        // The log file, opened before the first thing worth recording happens.
        if (config.logging().toFile()) {
            com.jedrock.utils.FileLog.install(layout.logs(), config.logging().keepFiles());
        }
        // Verbose subsystems — unless -Djedrock.debug already said so on the command line, which wins.
        if (System.getProperty("jedrock.debug") == null) {
            com.jedrock.utils.Debug.configure(config.logging().debug());
        }
        // The wire's own settings, installed before any listener exists: the transport reads them when it
        // builds its thread groups, which is the first thing binding does.
        com.jedrock.network.Pipeline.install(PipelineConfig.load(layout.pipelineFile()));

        // Where the server's small persistent facts live: the data/ folder, or a database if one was
        // configured and could be opened (it falls back to files and says so if not).
        this.storage = com.jedrock.core.data.DataStores.open(root, layout.data(), config.storage());

        this.opList = new OpList(layout.dataFile("ops.txt"));
        this.permissions = new PermissionManager(layout.dataFile("permissions.txt"));
        // Built here, before anything can connect: it registers the login gate, and a ban that only
        // starts applying once the server is fully up is not a ban.
        this.moderation = new com.jedrock.core.moderation.Moderation(storage, eventBus, opList);
        this.playerWorlds = new com.jedrock.core.player.PlayerWorlds(storage);
        this.pluginStorageFile = layout.dataFile("plugin-storage.jdb");
        this.plugins = new PluginManager(eventBus, this, scheduler, commandManager, packetTaps,
                layout.plugins());

        // Worlds come up here rather than in start(): every collaborator below is handed the default
        // one, and the bake has to have happened before any of them can read a block. The manager also
        // owns the per-world block relay, so an edit only reaches the clients standing in that world.
        this.levels = new WorldManager(eventBus, playerRegistry, layout.worlds());
        this.defaultWorld = levels.openDefault(config.worlds().defaultName(),
                defaultWorldTemplate().withSeed(config.seed()));
        this.judge = new BlindJudge(config.judgeEnabled(), config.maxReach(), config.maxMoveDelta());
        this.entities = new EntityDirector(playerRegistry, defaultWorld);
        this.scenes = new com.jedrock.core.entity.SceneManager(entities, defaultWorld);
        // Regions written before they recorded a world all belong to the default one — it was the only
        // world there was when that file was saved.
        this.regions = new com.jedrock.core.region.RegionManager(eventBus, defaultWorld.getName());
        this.items = new com.jedrock.core.item.ItemRegistry(eventBus);
        this.enchants = new com.jedrock.core.item.EnchantService(eventBus);
        this.containers = new ContainerService(playerRegistry, defaultWorld, eventBus, broadcast);
        this.containers.setItems(items);
        this.combat = new CombatService(playerRegistry, defaultWorld, eventBus, broadcast, entities, judge);
        this.combat.setItems(items);
        // An avatar is only worth showing to a client that has the terrain to stand it on, so the tracker
        // draws its range from the same view distance the chunk stream uses.
        this.tracker = new PlayerTracker(playerRegistry, config.viewDistance());
        this.travel = new WorldTravel(tracker, entities, eventBus);
        // Status effects. Built after the tracker because invisibility is done by withholding an avatar,
        // and cross-wired with combat: instant health and damage are a health change, and an attacker's
        // strength is the half of the damage arithmetic no damage event can carry.
        this.effects = new com.jedrock.core.effect.EffectService(playerRegistry, tracker, eventBus);
        this.effects.setCombat(combat);
        this.combat.setEffects(effects);
        this.travel.setEffects(effects);
        // Everything the protocol layer reports lands here, not on the server itself.
        this.bridge = new ConnectionBridge(this, eventBus, playerRegistry, broadcast, tracker, defaultWorld,
                judge, combat, containers, entities, commandManager, packetTaps, opList, permissions,
                regions);
        this.bridge.setEffects(effects);

        // Attach scheduler + core tick to game loop
        gameLoop.addTickable(scheduler);
        gameLoop.addTickable(this::serverTick);
        gameLoop.setTickRate(config.tickRate());

        // Default network impl (can be swapped). Register state listener + config before binding.
        this.networkServer = new NettyNetworkServer();
        this.networkServer.setConnectionListener(bridge);
        this.networkServer.setProperties(config);
        this.networkServer.setWorld(defaultWorld); // clients serialize chunks from this shared world

        registerCommands();
    }

    /**
     * The recipe the default world is created from — the very first time only, since a world that exists
     * is loaded from its own file and its terrain is the file's, not the config's. An unknown template
     * name falls back to the overworld rather than refusing to start: the alternative is a server that
     * won't come up because of a typo in a key that stopped mattering after the first boot.
     */
    private com.jedrock.api.world.WorldTemplate defaultWorldTemplate() {
        String wanted = config.worlds().defaultTemplate();
        return levels.template(wanted).orElseGet(() -> {
            LOGGER.warn("world.default-template names '" + wanted + "', which isn't a registered template "
                    + levels.templates().stream().map(com.jedrock.api.world.WorldTemplate::name).toList()
                    + "; using 'overworld'");
            return com.jedrock.api.world.WorldTemplate.overworld();
        });
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
        commandManager.register(new TimeCommand());
        commandManager.register(new AboutCommand());
        commandManager.register(new TeleportCommand());
        commandManager.register(new TpHereCommand());
        commandManager.register(new TpAllCommand());
        commandManager.register(new SpawnCommand());
        commandManager.register(new HealCommand());
        commandManager.register(new KillCommand());
        commandManager.register(new ClearCommand());
        commandManager.register(new GiveCommand());
        commandManager.register(new EffectCommand());
        commandManager.register(new EnchantCommand());
        commandManager.register(new PuppetCommand());
        commandManager.register(new HologramCommand());
        commandManager.register(new PoseCommand());
        commandManager.register(new OpCommand());
        commandManager.register(new DeopCommand());
        commandManager.register(new BanCommand());
        commandManager.register(new BanIpCommand());
        commandManager.register(new KickCommand());
        commandManager.register(new MuteCommand());
        commandManager.register(new PardonCommand());
        commandManager.register(new BanListCommand());
        commandManager.register(new WhitelistCommand());
        commandManager.register(new SeenCommand());
        commandManager.register(new PlayerInfoCommand());
        commandManager.register(new PermCommand());
        commandManager.register(new PickCommand());
        commandManager.register(new RegionCommand());
        commandManager.register(new WorldCommand());
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

    /** Bans, ip-bans, mutes, the whitelist and last-seen — used by the moderation commands and scripts. */
    public com.jedrock.core.moderation.Moderation getModeration() {
        return moderation;
    }

    /**
     * The player cap this server advertises. A number to show, not a gate — nothing here refuses a login
     * for being over it, which is why it lives next to the ping rather than next to the ban list.
     */
    public int getMaxPlayers() {
        return config.maxPlayers();
    }

    /**
     * The commands to advertise to a client that needs a manifest. Bedrock refuses to send a command it
     * wasn't told about; the PE session turns this list into its AvailableCommands blob at spawn. Java
     * clients need nothing.
     */
    List<ConnectionListener.CommandInfo> commandManifest() {
        var registered = commandManager.commands();
        List<ConnectionListener.CommandInfo> out = new java.util.ArrayList<>(registered.size());
        for (com.jedrock.core.command.Command c : registered) {
            out.add(new ConnectionListener.CommandInfo(c.name(), c.description(), c.aliases()));
        }
        return out;
    }

    /** The mode this uuid joins in: their choice from earlier this run, else the configured default. */
    GameMode rememberedGameMode(UUID uuid) {
        return gameModes.getOrDefault(uuid, config.defaultGameMode());
    }

    /** The mode a first-time player joins in. */
    GameMode defaultGameMode() {
        return config.defaultGameMode();
    }

    /**
     * The world this uuid should join into — the one they were last standing in — or {@code null} for the
     * default world, which is what "no opinion" means to a join sequence. Asked on an I/O thread, before
     * the client is shown any terrain, so the answer has to be here rather than after the join.
     *
     * <p>A remembered world that is no longer loaded (its folder went away, or a script unloaded it while
     * that player was offline) is forgotten rather than mourned: they join the default world, and the
     * fallback is written down so it isn't re-decided on every login.
     */
    CoreWorld rememberedWorld(UUID uuid) {
        if (!config.rememberWorld() || uuid == null) {
            return null;
        }
        String name = playerWorlds.worldOf(uuid);
        if (name == null) {
            return null; // never left the default world
        }
        CoreWorld world = levels.get(name).orElse(null);
        if (world == null) {
            LOGGER.info("Player " + uuid + " was last in world '" + name
                    + "', which is not loaded — joining them into '" + defaultWorld.getName() + "'");
            playerWorlds.forget(uuid);
            return null;
        }
        return world == defaultWorld ? null : world;
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
        // A destination in another world is a different operation entirely — the terrain changes, not just
        // the coordinates. Routed here rather than at every call site, so /tp, /spawn, a script and the api
        // all cross worlds for free and none of them has to know they did.
        if (WorldTravel.isCrossWorld(player, to)) {
            if (!travel.travel(player, to)) {
                return false;
            }
            // Crossing is the only moment the answer changes, so it is the only moment worth a write —
            // and writing it here rather than at quit means a client that crashes still comes back to the
            // world it was actually in. The player's own world, not the destination asked for: a listener
            // may have redirected the journey somewhere else entirely.
            if (config.rememberWorld()) {
                playerWorlds.remember(player.getUniqueId(), player.getWorld().getName());
            }
            return true;
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

        // Retire effects that have run out. Also gated to a coarse interval, and it visits only players
        // who are actually under something — an unaffected server pays a handful of empty-map checks.
        effects.expiryTick(currentTick);

        // Repaint the sidebar for clients that can't hold one (Bedrock borrows a HUD line that fades).
        // Free for everyone else: a player with no sidebar is one field read.
        broadcast.repaintSidebars(currentTick);

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
        LOGGER.info("Server folder: " + layout.summary());
        LOGGER.info("Storage: " + storage.describe());

        // The default world came up with the constructor; every OTHER world folder is picked up here,
        // before any listener accepts logins, so a world that existed last run is back before the first
        // player can be sent to it.
        int others = config.worlds().loadAll() ? levels.discover() : 0;
        if (others > 0) {
            LOGGER.info("Loaded " + others + " additional world(s): " + levels.all().stream()
                    .filter(w -> w != defaultWorld)
                    .map(w -> w.getName() + " (" + w.getDimension() + ")")
                    .reduce((a, b) -> a + ", " + b).orElse(""));
        }

        // Saved scenes are world decoration, so they come back with the world and before anyone can see
        // it — no script involved, which is the whole point of having saved them.
        try {
            scenes.load(sceneFile());
            int props = scenes.spawnAll();
            if (props > 0) {
                LOGGER.info("Restored " + scenes.names().size() + " scene(s), " + props + " prop(s)");
            }
        } catch (java.io.IOException e) {
            LOGGER.error("Failed to load scenes from " + sceneFile().toAbsolutePath(), e);
        }

        // Regions are rules about that same world, and they have to be in force before the first login —
        // a spawn nobody can dig up is no use if it only starts protecting itself once a script says so.
        try {
            regions.load(regionFile());
            if (regions.size() > 0) {
                LOGGER.info("Loaded " + regions.size() + " region(s)");
            }
        } catch (java.io.IOException e) {
            LOGGER.error("Failed to load regions from " + regionFile().toAbsolutePath(), e);
        }

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
        ConsoleCommands console = new ConsoleCommands(this);
        console.start();

        // The same console over a socket, for the tools that speak RCON. Off unless asked for, and it
        // refuses to start without a password — see RconServer.
        if (config.rcon().enabled()) {
            RconServer remote = new RconServer(console::execute, config.rcon().password());
            if (remote.start(config.rcon().bind(), config.rcon().port())) {
                this.rcon = remote;
            }
        }

        // Periodic world autosave (edits are otherwise only in memory). Off with 0; the save runs on
        // a background thread so it never stalls a tick, guarded so two saves can't overlap.
        long saveSeconds = config.worlds().autosaveSeconds();
        if (saveSeconds > 0) {
            long periodTicks = saveSeconds * TickUtil.TPS;
            scheduler.runTaskTimer(levels::autosaveAll, periodTicks, periodTicks);
            // Script state rides the same cadence — it is small, and a dirty flag skips an idle store.
            scheduler.runTaskTimer(() -> plugins.storage().saveIfDirty(pluginStorageFile),
                    periodTicks, periodTicks);
            scheduler.runTaskTimer(() -> scenes.saveIfDirty(sceneFile()), periodTicks, periodTicks);
            scheduler.runTaskTimer(() -> regions.saveIfDirty(regionFile()), periodTicks, periodTicks);
            LOGGER.info("World autosave enabled (every " + saveSeconds + "s)");
        }

        // Optional periodic status line — logging.status-seconds (0 = off).
        long statusSeconds = config.logging().statusSeconds();
        if (statusSeconds > 0) {
            long periodTicks = statusSeconds * TickUtil.TPS;
            scheduler.runTaskTimer(() -> LOGGER.info(getStatus().summary()), periodTicks, periodTicks);
            LOGGER.info("Periodic status logging enabled (every " + statusSeconds + "s)");
        }

        LOGGER.info("Jedrock server started successfully. Type 'help' for console commands.");

        // What plugins remembered last run, read before any script can ask for it.
        try {
            plugins.storage().load(pluginStorageFile);
            if (plugins.storage().totalKeys() > 0) {
                LOGGER.info("Loaded plugin storage from " + pluginStorageFile.toAbsolutePath()
                        + " (" + plugins.storage().buckets().size() + " bucket(s), "
                        + plugins.storage().totalKeys() + " key(s))");
            }
        } catch (java.io.IOException e) {
            LOGGER.error("Failed to load plugin storage from " + pluginStorageFile.toAbsolutePath()
                    + " — scripts start with empty storage and the file is left untouched", e);
        }

        // Load script plugins before ServerStartEvent, so a plugin can subscribe to it. A background
        // watcher (off the game-loop thread — the poll blocks on disk I/O) hot-reloads a saved edit.
        if (config.plugins().enabled()) {
            // Before loadAll: a plugin that wants to call out on its very first line must find the global
            // already there. Does nothing unless plugins.http.enabled says otherwise.
            plugins.enableHttp(config.plugins().http());
            plugins.loadAll();
            if (config.plugins().hotReload()) {
                plugins.startWatching(config.plugins().reloadMillis());
            }
        } else {
            LOGGER.info("Script plugins are disabled (plugins.enabled=false); "
                    + layout.plugins().toAbsolutePath() + " is not read");
        }

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
        // After unloadAll, so an onDisable may still fire its last webhook; the pool is daemon-threaded,
        // so an in-flight request that outlives this cannot hold the process open either way.
        plugins.closeHttp();
        // After onDisable, so a script's last-moment write is included.
        plugins.storage().saveIfDirty(pluginStorageFile);
        scenes.saveIfDirty(sceneFile());
        regions.saveIfDirty(regionFile());

        if (rcon != null) {
            rcon.stop(); // before the loop, so a remote command can't arrive into a half-stopped server
        }
        gameLoop.stop();
        networkServer.shutdown();

        // Save last, with the loop stopped and listeners closed, so no edit races the snapshot.
        levels.saveAllIfDirty();

        storage.close(); // after every store that writes through it has had its last say

        LOGGER.info("Shutdown complete.");
        com.jedrock.utils.FileLog.close(); // after the last line worth writing down
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
        return levels.asApi();
    }

    @Override
    public Optional<World> getWorld(String name) {
        return levels.get(name).map(w -> (World) w);
    }

    @Override
    public World getDefaultWorld() {
        return defaultWorld;
    }

    @Override
    public World createWorld(String name, String template, Long seed) {
        com.jedrock.api.world.WorldTemplate recipe = levels.template(template)
                .orElseThrow(() -> new IllegalArgumentException("No world template named '" + template
                        + "' — registered: " + levels.templates().stream()
                        .map(com.jedrock.api.world.WorldTemplate::name).toList()));
        return levels.create(name, recipe, seed);
    }

    @Override
    public boolean unloadWorld(String name) {
        return levels.unload(name);
    }

    @Override
    public Collection<com.jedrock.api.world.WorldTemplate> getWorldTemplates() {
        return levels.templates();
    }

    @Override
    public void registerWorldTemplate(com.jedrock.api.world.WorldTemplate template) {
        levels.registerTemplate(template);
    }

    /** The world registry — used by {@code /world} and the script {@code worlds} global. */
    public WorldManager getWorldManager() {
        return levels;
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

    /** Saved scenes: authored by scripts, owned by the server, restored at boot. */
    public com.jedrock.core.entity.SceneManager getScenes() {
        return scenes;
    }

    /** Where scenes live — next to the level file, since they decorate that world. */
    private Path sceneFile() {
        return layout.worldFolder(defaultWorld.getName()).resolve("scenes.jdb");
    }

    /** Custom items: a name, lore and behaviour hung on a vanilla item state, declared by scripts. */
    /** Who is under what — the effect service, for commands and the script API. */
    public com.jedrock.core.effect.EffectService getEffects() {
        return effects;
    }

    /** Enchanting — for commands and the script API. */
    public com.jedrock.core.item.EnchantService getEnchants() {
        return enchants;
    }

    public com.jedrock.core.item.ItemRegistry getItems() {
        return items;
    }

    /** Regions: named boxes with rules, in force from boot and owned by the server like scenes are. */
    public com.jedrock.core.region.RegionManager getRegions() {
        return regions;
    }

    /** Where regions live — next to the level file, since they are rules about that world. */
    private Path regionFile() {
        return layout.worldFolder(defaultWorld.getName()).resolve("regions.jdb");
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

    /**
     * Open a script-owned virtual menu (a chest window backed by {@code container}, no world block) to a
     * player. A non-null {@code onClick} makes it a read-only button menu; null makes it a storage
     * container. Java only — see {@link ContainerService#openMenu}.
     *
     * @return {@code true} if it opened, {@code false} if the player is offline or on an edition that can't show it
     */
    public boolean openMenu(Player player, String title, com.jedrock.core.inventory.Container container,
                            String[] labels, com.jedrock.core.inventory.MenuClick onClick) {
        if (!(player instanceof CorePlayer cp)) {
            return false;
        }
        return containers.openMenu(cp, title, container, labels, onClick);
    }

    /** Re-send {@code container} to every player who currently has it open (a script edited it). */
    public void refreshContainer(com.jedrock.core.inventory.Container container) {
        containers.refreshViewers(container);
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

    /**
     * Run a server in the current folder — or in the one named as the first argument, which is how one jar
     * runs two servers without two copies of it ({@code java -jar jedrock.jar ./survival}).
     */
    public static void main(String[] args) {
        Path home = args.length > 0 && !args[0].startsWith("-") ? Path.of(args[0])
                : Path.of(System.getProperty("jedrock.home", "."));
        JedrockServer server = new JedrockServer(home);
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown));

        server.start();

        // Keep the main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException ignored) {}
    }
}
