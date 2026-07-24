package com.jedrock.core.command;

import com.jedrock.api.Server;
import com.jedrock.api.ServerStatus;
import com.jedrock.api.command.CommandSender;
import com.jedrock.api.entity.EntityType;
import com.jedrock.api.entity.Hologram;
import com.jedrock.api.entity.PuppetEntity;
import com.jedrock.api.event.EventBus;
import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.core.permission.OpList;
import com.jedrock.core.player.CorePlayer;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typed-argument framework and tab-completion, exercised without a live server. Parsing and
 * completion take only the api {@link Server}, so a small roster double is all they need; the two paths
 * that would need a real {@code JedrockServer} (PLAYER through {@link ArgCommand#execute}) are covered at
 * the {@link ArgType} level instead, which is where that logic actually lives.
 */
class CommandArgumentsTest {

    private final CoreWorld world = new CoreWorld("world", Dimension.OVERWORLD);

    private CorePlayer player(String name) {
        return new CorePlayer(UUID.randomUUID(), name, new NoopConnection(), world,
                world.getSpawnLocation(), GameMode.CREATIVE);
    }

    // ===== ArgType parsing =====

    @Test
    void integerAndNumberParseAndReject() throws Exception {
        assertEquals(42, ArgType.INTEGER.parse(null, null, "42"));
        assertEquals(1.5, ArgType.NUMBER.parse(null, null, "1.5"));
        assertThrows(() -> ArgType.INTEGER.parse(null, null, "twelve"));
        assertThrows(() -> ArgType.NUMBER.parse(null, null, "x"));
    }

    @Test
    void choiceIsCaseInsensitiveAndReturnsTheCanonicalSpelling() throws Exception {
        ArgType<String> weather = ArgType.choice("clear", "rain", "thunder");
        assertEquals("thunder", weather.parse(null, null, "THUNDER"));
        assertThrows(() -> weather.parse(null, null, "snow"));
        assertEquals(List.of("rain"), weather.complete(null, null, "r"));
        assertEquals(List.of("clear", "rain", "thunder"), weather.complete(null, null, ""));
    }

    @Test
    void gameModeParsesEveryFormAndCompletesNames() throws Exception {
        assertEquals(GameMode.CREATIVE, ArgType.GAME_MODE.parse(null, null, "c"));
        assertEquals(GameMode.SURVIVAL, ArgType.GAME_MODE.parse(null, null, "0"));
        assertEquals(List.of("survival", "spectator"), ArgType.GAME_MODE.complete(null, null, "s"));
    }

    @Test
    void playerResolvesAgainstTheRosterAndCompletesNames() throws Exception {
        RosterServer server = new RosterServer(player("Alice"), player("Alina"), player("Bob"));

        assertEquals("Alice", ArgType.PLAYER.parse(server, null, "Alice").getName());
        assertThrows(() -> ArgType.PLAYER.parse(server, null, "Nobody"));
        assertEquals(List.of("Alice", "Alina"), ArgType.PLAYER.complete(server, null, "Al"));
        assertEquals(3, ArgType.PLAYER.complete(server, null, "").size());
    }

    // ===== ArgCommand end to end (server-free types, so execute(null, ...) is honest) =====

    /** A tiny ArgCommand: give <name:word> <amount:int> [reason:text...]. Records what it received. */
    private static final class GiveCommand extends ArgCommand {
        String who;
        int amount = -1;
        String reason;

        @Override public String name() { return "give"; }
        @Override public String description() { return "test"; }
        @Override public List<CommandArg> arguments() {
            return List.of(
                    CommandArg.required("who", ArgType.WORD),
                    CommandArg.required("amount", ArgType.INTEGER),
                    CommandArg.optional("reason", ArgType.GREEDY));
        }
        @Override protected void run(com.jedrock.core.JedrockServer s, CommandSender sender, CommandContext ctx) {
            who = ctx.getString("who");
            amount = ctx.getInt("amount", -1);
            reason = ctx.has("reason") ? ctx.getString("reason") : null;
        }
    }

    @Test
    void argCommandParsesRequiredAndGreedyOptional() {
        GiveCommand give = new GiveCommand();
        NoopConnection conn = new NoopConnection();
        CorePlayer sender = new CorePlayer(UUID.randomUUID(), "S", conn, world,
                world.getSpawnLocation(), GameMode.CREATIVE);

        give.execute(null, sender, new String[]{"Alice", "5", "for", "the", "road"});

        assertEquals("Alice", give.who);
        assertEquals(5, give.amount);
        assertEquals("for the road", give.reason, "the greedy tail is joined back into one value");
        assertTrue(conn.messages.isEmpty(), "a clean parse sends no error");
    }

    @Test
    void argCommandOmitsAnAbsentOptional() {
        GiveCommand give = new GiveCommand();
        CorePlayer sender = new CorePlayer(UUID.randomUUID(), "S", new NoopConnection(), world,
                world.getSpawnLocation(), GameMode.CREATIVE);

        give.execute(null, sender, new String[]{"Alice", "5"});

        assertEquals(5, give.amount);
        assertEquals(null, give.reason, "an absent optional is left out");
    }

    @Test
    void argCommandReportsMissingRequiredAndNeverRuns() {
        GiveCommand give = new GiveCommand();
        NoopConnection conn = new NoopConnection();
        CorePlayer sender = new CorePlayer(UUID.randomUUID(), "S", conn, world,
                world.getSpawnLocation(), GameMode.CREATIVE);

        give.execute(null, sender, new String[]{"Alice"}); // amount missing

        assertEquals(-1, give.amount, "the body did not run");
        assertEquals(1, conn.messages.size());
        assertTrue(conn.messages.get(0).toLowerCase().contains("missing"), conn.messages.get(0));
    }

    @Test
    void argCommandRejectsABadTokenWithTheTypesMessage() {
        GiveCommand give = new GiveCommand();
        NoopConnection conn = new NoopConnection();
        CorePlayer sender = new CorePlayer(UUID.randomUUID(), "S", conn, world,
                world.getSpawnLocation(), GameMode.CREATIVE);

        give.execute(null, sender, new String[]{"Alice", "lots"});

        assertEquals(-1, give.amount);
        assertTrue(conn.messages.get(0).toLowerCase().contains("whole number"), conn.messages.get(0));
    }

    @Test
    void argCommandRejectsExtraTokensWhenNotGreedy() {
        ArgCommand cmd = new ArgCommand() {
            @Override public String name() { return "ping"; }
            @Override public String description() { return "t"; }
            @Override public List<CommandArg> arguments() {
                return List.of(CommandArg.optional("who", ArgType.WORD));
            }
            @Override protected void run(com.jedrock.core.JedrockServer s, CommandSender snd, CommandContext c) {
                snd.sendMessage("ran");
            }
        };
        NoopConnection conn = new NoopConnection();
        CorePlayer sender = new CorePlayer(UUID.randomUUID(), "S", conn, world,
                world.getSpawnLocation(), GameMode.CREATIVE);

        cmd.execute(null, sender, new String[]{"one", "two"});

        assertEquals(1, conn.messages.size());
        assertTrue(conn.messages.get(0).toLowerCase().contains("too many"), conn.messages.get(0));
    }

    @Test
    void usageIsBuiltFromTheSignature() {
        assertEquals("/give <who> <amount> [reason]", new GiveCommand().usage());
    }

    // ===== Manager-level completion =====

    @Test
    void completesCommandLabelsWithLeadingSlash() {
        CommandManager cm = new CommandManager(null);
        cm.register(new HelpCommand());      // name help, no permission, not player-only
        cm.register(new GameModeCommand());  // name gamemode, alias gm

        List<String> labels = cm.complete(ConsoleSender.INSTANCE, "/h");
        assertTrue(labels.contains("/help"), labels.toString());
        assertFalse(labels.contains("/gamemode"), "only labels starting with the partial");

        List<String> g = cm.complete(ConsoleSender.INSTANCE, "/g");
        assertTrue(g.contains("/gamemode") && g.contains("/gm"), g.toString());
    }

    @Test
    void completesArgumentsOnceTheLabelIsTyped() {
        CommandManager cm = new CommandManager(null);
        cm.register(new GameModeCommand());

        // A trailing space means we're on the (empty) first argument, not the label.
        List<String> modes = cm.complete(ConsoleSender.INSTANCE, "/gamemode ");
        assertEquals(List.of("survival", "creative", "adventure", "spectator"), modes);

        List<String> c = cm.complete(ConsoleSender.INSTANCE, "/gamemode c");
        assertEquals(List.of("creative"), c);
    }

    @Test
    void completionHidesCommandsTheSenderCannotUse() {
        CommandManager cm = new CommandManager(null);
        cm.register(new GameModeCommand()); // guarded by jedrock.command.gamemode

        CorePlayer plain = new CorePlayer(UUID.randomUUID(), "Plain", new NoopConnection(), world,
                world.getSpawnLocation(), GameMode.CREATIVE);
        plain.setPermissions(new OpList(Path.of("ops.txt")), null);

        assertTrue(cm.complete(plain, "/gam").isEmpty(), "a non-op is offered nothing it can't run");
        assertTrue(cm.complete(ConsoleSender.INSTANCE, "/gam").contains("/gamemode"), "the console can");
    }

    // ===== Doubles =====

    private static void assertThrows(ThrowingRunnable action) {
        try {
            action.run();
            throw new AssertionError("expected an ArgParseException");
        } catch (ArgParseException expected) {
            // wanted
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws ArgParseException;
    }

    /** A no-op connection that records the messages a command sends back. */
    private static final class NoopConnection implements PlayerConnection {
        final List<String> messages = new ArrayList<>();
        @Override public void sendMessage(String message) { messages.add(message); }
        @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.JE_1_12_2; }
        @Override public String getAddress() { return "test"; }
        @Override public void sendPacket(Object packet) { }
        @Override public void addToTab(UUID uuid, String name) { }
        @Override public void removeFromTab(UUID uuid) { }
        @Override public void showPlayer(UUID uuid, String name, long entityId,
                                         double x, double y, double z, float yaw, float pitch) { }
        @Override public void hidePlayer(UUID uuid, long entityId) { }
        @Override public void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch) { }
        @Override public void teleport(double x, double y, double z, float yaw, float pitch) { }
        @Override public void setGameMode(GameMode mode) { }
        @Override public void swingArm(long entityId) { }
        @Override public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) { }
        @Override public void sendBlockChange(int x, int y, int z, int state) { }
        @Override public void close(String reason) { }
        @Override public boolean isActive() { return true; }
    }

    /**
     * A minimal api {@link Server} that knows only its roster — enough for {@link ArgType#PLAYER} to
     * resolve and complete names. Everything else throws, so a test that leans on more fails loudly.
     */
    private static final class RosterServer implements Server {
        private final Map<String, Player> byName = new LinkedHashMap<>();

        RosterServer(Player... players) {
            for (Player p : players) {
                byName.put(p.getName().toLowerCase(java.util.Locale.ROOT), p);
            }
        }

        @Override public Collection<Player> getPlayers() { return byName.values(); }
        @Override public Optional<Player> getPlayer(String name) {
            return Optional.ofNullable(byName.get(name.toLowerCase(java.util.Locale.ROOT)));
        }
        @Override public Optional<Player> getPlayer(UUID uuid) {
            return getPlayers().stream().filter(p -> p.getUniqueId().equals(uuid)).findFirst();
        }

        @Override public String getName() { return "test"; }
        @Override public String getVersion() { return "test"; }
        @Override public void start() { }
        @Override public void shutdown() { }
        @Override public boolean isRunning() { return true; }
        @Override public EventBus getEventBus() { throw new UnsupportedOperationException(); }
        @Override public void broadcast(String message) { }
        @Override public void dispatchCommand(Player player, String commandLine) { }
        @Override public Collection<World> getWorlds() { return List.of(); }
        @Override public Optional<World> getWorld(String name) { return Optional.empty(); }
        @Override public PuppetEntity spawnPuppet(EntityType type, Location at, String name) {
            throw new UnsupportedOperationException();
        }
        @Override public PuppetEntity spawnItem(Location at, int state) {
            throw new UnsupportedOperationException();
        }
        @Override public PuppetEntity spawnFallingBlock(Location at, int state) {
            throw new UnsupportedOperationException();
        }
        @Override public PuppetEntity spawnText(Location at, String text) {
            throw new UnsupportedOperationException();
        }
        @Override public Hologram spawnHologram(Location at, String... lines) {
            throw new UnsupportedOperationException();
        }
        @Override public long getCurrentTick() { return 0; }
        @Override public ServerStatus getStatus() { throw new UnsupportedOperationException(); }
    }
}
