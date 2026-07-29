package com.jedrock.core;

import com.jedrock.api.event.EventBus;
import com.jedrock.api.event.block.BlockBreakEvent;
import com.jedrock.api.event.block.BlockPlaceEvent;
import com.jedrock.api.event.player.PlayerChatEvent;
import com.jedrock.api.event.player.PlayerCommandEvent;
import com.jedrock.api.event.player.PlayerJoinEvent;
import com.jedrock.api.event.player.PlayerLoginEvent;
import com.jedrock.api.event.player.PlayerMoveEvent;
import com.jedrock.api.event.player.PlayerPickupItemEvent;
import com.jedrock.api.event.player.PlayerQuitEvent;
import com.jedrock.api.event.player.PlayerToggleSneakEvent;
import com.jedrock.api.event.player.PlayerToggleSprintEvent;
import com.jedrock.api.event.player.PlayerUseItemEvent;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Blocks;
import com.jedrock.api.world.Location;
import com.jedrock.core.command.CommandManager;
import com.jedrock.core.entity.EntityDirector;
import com.jedrock.core.inventory.ContainerService;
import com.jedrock.core.net.PacketDirection;
import com.jedrock.core.net.PacketEvent;
import com.jedrock.core.net.PacketTapRegistry;
import com.jedrock.core.permission.OpList;
import com.jedrock.core.permission.PermissionManager;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.player.PlayerBroadcast;
import com.jedrock.core.player.PlayerRegistry;
import com.jedrock.core.player.PlayerTracker;
import com.jedrock.core.world.CoreWorld;
import com.jedrock.network.ConnectionListener;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.text.ChatText;

import java.util.List;
import java.util.UUID;

/**
 * The network → core bridge: everything the protocol layer reports, turned into core state changes.
 *
 * <p>The network handler has already done the protocol-mandatory work (LoginSuccess, JoinGame, the first
 * chunks, the spawn position) by the time a call lands here, so this class is free to do game logic —
 * fire events, consult the {@link BlindJudge}, write the world, relay to other players. It is the one
 * place that knows a packet arrived; nothing below it does.
 *
 * <p>It was carved out of {@code JedrockServer}, which had grown to own both the server's own life
 * (config, bootstrap, the loop, the api surface) and every inbound decision — two responsibilities that
 * never share a line of code. The split is what {@link ConnectionListener} was always shaped for: the
 * network layer holds a listener, not a server, so it now holds exactly this.
 *
 * <p>Where a decision is genuinely the server's — which mode a returning player joins in, what commands
 * exist to advertise — the call is forwarded to {@link JedrockServer} rather than duplicating the state
 * here. Everything else is delegated to the collaborator that owns it: {@link ContainerService} for
 * anything that moves items, {@link CombatService} for anything that takes health, {@link EntityDirector}
 * for anything shown that is neither a player nor a block.
 */
final class ConnectionBridge implements ConnectionListener {

    private static final JLogger LOGGER = JLogger.getLogger(ConnectionBridge.class);

    private final JedrockServer server;
    private final EventBus eventBus;
    private final PlayerRegistry playerRegistry;
    private final PlayerBroadcast broadcast;
    private final PlayerTracker tracker;
    private final CoreWorld defaultWorld;
    private final BlindJudge judge;
    private final CombatService combat;
    private final ContainerService containers;
    private final EntityDirector entities;
    private final CommandManager commandManager;
    private final PacketTapRegistry packetTaps;
    private final OpList opList;
    private final PermissionManager permissions;
    private final com.jedrock.core.region.RegionManager regions;

    ConnectionBridge(JedrockServer server, EventBus eventBus, PlayerRegistry playerRegistry,
                     PlayerBroadcast broadcast, PlayerTracker tracker, CoreWorld defaultWorld,
                     BlindJudge judge,
                     CombatService combat, ContainerService containers, EntityDirector entities,
                     CommandManager commandManager, PacketTapRegistry packetTaps,
                     OpList opList, PermissionManager permissions,
                     com.jedrock.core.region.RegionManager regions) {
        this.server = server;
        this.eventBus = eventBus;
        this.playerRegistry = playerRegistry;
        this.broadcast = broadcast;
        this.tracker = tracker;
        this.defaultWorld = defaultWorld;
        this.judge = judge;
        this.combat = combat;
        this.containers = containers;
        this.entities = entities;
        this.commandManager = commandManager;
        this.packetTaps = packetTaps;
        this.opList = opList;
        this.permissions = permissions;
        this.regions = regions;
    }

    // ===== Server-owned facts the network layer asks for =====

    @Override
    public List<ConnectionListener.CommandInfo> commands() {
        return server.commandManifest();
    }

    @Override
    public GameMode gameModeFor(UUID uuid) {
        // The join sequence reads this (on an I/O thread) to pick the mode a client spawns in.
        return server.rememberedGameMode(uuid);
    }

    @Override
    public com.jedrock.api.world.World worldFor(UUID uuid) {
        // And this, in the same breath, to pick the world it spawns in: where they logged out, or null
        // for the default. Answered before the first chunk, so nobody has to be walked anywhere after.
        return server.rememberedWorld(uuid);
    }

    @Override
    public GameMode gameModeOf(PlayerConnection connection) {
        CorePlayer p = playerRegistry.getByConnectionOrNull(connection);
        return p != null ? p.getGameMode() : server.defaultGameMode();
    }

    @Override
    public int getOnlinePlayerCount() {
        return playerRegistry.size();
    }

    // ===== Lifecycle =====

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

        // The world the client was actually joined into — the one it logged out in, if the server
        // remembers players that way, else the default. Read back off the connection rather than decided
        // again here: that client has already been shown this world's terrain, and a second opinion at
        // this point would put the player in a world their own screen disagrees with.
        CoreWorld world = connection.getWorld() instanceof CoreWorld joined ? joined : defaultWorld;
        Location spawn = world.getSpawnLocation();
        // Match the mode the client actually joined in (the same value the join packets used): the
        // remembered choice from earlier this run, or the config default for a first join.
        CorePlayer player = new CorePlayer(uuid, username, connection, world, spawn,
                gameModeFor(uuid), eventBus);
        player.setPermissions(opList, permissions); // isOp/hasPermission/getPrefix resolve against these
        player.setItems(server.getItems());          // turns a stack's custom-item key into a shown name
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
        // Every teleport a command, script or the api asks for goes through the server from here on —
        // which is what makes player.teleport() move the client, and cross worlds when it has to.
        player.setTeleporter(server::teleport);
        world.addPlayer(player);

        PlayerJoinEvent event = new PlayerJoinEvent(player);
        // Seed the default announcement so a listener sees it and can restyle, replace or suppress it
        // (null / empty = no broadcast) — the same contract as the death message.
        event.setJoinMessage("{yellow}" + ChatText.escape(username) + " joined the game");
        eventBus.post(event);

        if (event.isCancelled()) {
            // A listener refused the join — undo the state we just added and drop the client.
            world.removePlayer(player);
            playerRegistry.removeByConnection(connection);
            player.kick("Connection refused");
            return;
        }

        // Seed region membership from where they actually spawned, so a player who logs in standing inside
        // one is already a member and gets its enter event — rather than being told they entered it the
        // first time they take a step. Refusal has nowhere to snap them back to, so it only means the
        // membership isn't recorded (see PlayerRegionEnterEvent).
        if (!regions.isEmpty()) {
            regions.updateMembership(player, spawn.x(), spawn.y(), spawn.z());
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

        // Avatars: the tab list above stays server-wide (it is a roster, not a view), but an avatar is a
        // thing in a place — so the tracker decides who is close enough in this world to be worth showing,
        // in both directions, and dresses each one it spawns.
        tracker.refresh(player);

        // Show every existing puppet and hologram to the newcomer (existing players already see them).
        entities.showAllTo(connection, player.getWorld());

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
        player.getCoreWorld().removePlayer(player); // whichever world they were standing in, not the default
        // The roster entry leaves every client; the avatar only leaves the ones that had it.
        for (Player other : playerRegistry.all()) {
            other.getConnection().removeFromTab(player.getUniqueId());
        }
        tracker.forget(player);
    }

    // ===== Movement and the world =====

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
        // "The world" is the one this player is standing in: worlds may differ in size, so the wall is
        // wherever THEIR world ends, and home is their world's spawn, not the default one's.
        CoreWorld world = player.getCoreWorld();
        if (!world.isInsideBounds(x, z)) {
            if (world.isInsideBounds(from.x(), from.z())) {
                connection.teleport(from.x(), from.y(), from.z(), yaw, pitch);
            } else {
                Location spawn = world.getSpawnLocation();
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

        // Regions: the one rule not enforced through a listener, because a permanent PlayerMoveEvent
        // listener would defeat the fast path above for every server that has no regions. Gated on the
        // same kind of check instead — with none registered this is one array-length read. A refusal (a
        // denied ENTRY flag, or a cancelled enter/leave) snaps the client back exactly as a cancelled
        // move does.
        if (!regions.isEmpty() && !regions.updateMembership(player, x, y, z)) {
            connection.teleport(from.x(), from.y(), from.z(), from.yaw(), from.pitch());
            return;
        }
        player.setLocation(new Location(player.getWorld(), x, y, z, yaw, pitch));

        // Who is close enough to be worth an avatar can only have changed if this step crossed a chunk
        // line — the same condition the chunk stream itself no-ops on. Standing still cannot change it,
        // and if the other party is the one who moved, their own crossing updates the pair from their side.
        if ((from.getBlockX() >> 4) != (((int) Math.floor(x)) >> 4)
                || (from.getBlockZ() >> 4) != (((int) Math.floor(z)) >> 4)) {
            tracker.refresh(player);
        }

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
        // Every coordinate below belongs to the editor's world — the one they are standing in. An edit
        // is otherwise a position with no world, and would land in whichever world the server was built
        // around, which is exactly the bug that makes a nether dig into the overworld.
        CorePlayer editor = playerRegistry.getByConnectionOrNull(connection);
        CoreWorld world = editor != null ? editor.getCoreWorld() : defaultWorld;
        // Edge wall: an edit outside the finite world is refused and the client corrected with the
        // real (void = air) block, so the world can't grow past its bounds.
        if (!world.isInsideBounds(x, z)) {
            LOGGER.debug(() -> "[edit] REFUSED (out of bounds) " + x + "," + y + "," + z);
            connection.sendBlockChange(x, y, z, world.getBlockId(x, y, z));
            return;
        }
        // Blind judge: reject an edit outside the editor's reach sphere and correct their client by
        // re-sending the real (unchanged) block, so a reach hack can't touch distant blocks.
        if (editor != null) {
            Location loc = editor.getLocation();
            if (!judge.allowsInteraction(loc.x(), loc.y(), loc.z(), x, y, z)) {
                LOGGER.debug(() -> "[edit] REFUSED (reach) " + x + "," + y + "," + z + " from "
                        + String.format("%.1f,%.1f,%.1f", loc.x(), loc.y(), loc.z()));
                connection.sendBlockChange(x, y, z, world.getBlockId(x, y, z));
                return;
            }
        }
        // The block that was here, captured before the edit — a survival miner picks it up.
        int previous = world.getBlockId(x, y, z);

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
        world.setBlockId(x, y, z, state);
        // A broken chest drops its container (contents lost — no item entities in the illusion).
        if (state == Blocks.AIR && Blocks.idOf(previous) == Blocks.CHEST) {
            world.removeChestContainer(x, y, z);
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

    // ===== Packet taps: the network layer's raw-packet hooks, handed to the tap registry =====

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

    // ===== Poses and animation =====

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
        containers.onHeldSlotChange(connection, slot);
    }

    @Override
    public void onAttack(PlayerConnection connection, long targetEntityId) {
        combat.onAttack(connection, targetEntityId);
    }

    // ===== Chat and commands =====

    @Override
    public void onTabComplete(PlayerConnection connection, String partialLine) {
        // Only complete an actual command line — a bare chat word gets no command suggestions. The sender
        // may be the console-equivalent nobody (a not-yet-registered connection) — resolve to a player, or
        // offer nothing rather than leak commands to an unauthenticated socket.
        if (partialLine == null || !partialLine.startsWith("/")) {
            return;
        }
        CorePlayer player = playerRegistry.getByConnectionOrNull(connection);
        if (player == null) {
            return;
        }
        List<String> matches = commandManager.complete(player, partialLine);
        if (!matches.isEmpty()) {
            connection.sendTabComplete(matches);
        }
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
}
