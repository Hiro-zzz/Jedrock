package com.jedrock.core.player;

import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;
import com.jedrock.api.world.Dimension;
import com.jedrock.core.world.CoreWorld;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerRegistryTest {

    private final CoreWorld world = new CoreWorld("world", Dimension.OVERWORLD);

    private CorePlayer newPlayer(String name, PlayerConnection connection) {
        UUID uuid = UUID.nameUUIDFromBytes(name.getBytes());
        return new CorePlayer(uuid, name, connection, world, world.getSpawnLocation());
    }

    @Test
    void addAndLookupByIdAndName() {
        PlayerRegistry registry = new PlayerRegistry();
        CorePlayer alice = newPlayer("Alice", new FakeConnection());
        registry.add(alice);

        assertEquals(1, registry.size());
        assertSame(alice, registry.getById(alice.getUniqueId()).orElseThrow());
        // Name lookup is case-insensitive.
        assertSame(alice, registry.getByName("alice").orElseThrow());
        assertSame(alice, registry.getByName("ALICE").orElseThrow());
        assertTrue(registry.getByName("bob").isEmpty());
    }

    @Test
    void removeByConnection() {
        PlayerRegistry registry = new PlayerRegistry();
        FakeConnection conn = new FakeConnection();
        CorePlayer bob = newPlayer("Bob", conn);
        registry.add(bob);

        assertSame(bob, registry.removeByConnection(conn));
        assertEquals(0, registry.size());
        assertTrue(registry.getById(bob.getUniqueId()).isEmpty());
        assertTrue(registry.getByName("bob").isEmpty());
    }

    @Test
    void entityIdsAreUniqueAndAboveSelfIds() {
        CorePlayer a = newPlayer("Alice", new FakeConnection());
        CorePlayer b = newPlayer("Bob", new FakeConnection());
        // Protocol handlers hardcode entity id 1 for the client's own player;
        // avatar ids must never collide with it or with each other.
        assertTrue(a.getEntityId() >= 1000);
        assertTrue(b.getEntityId() >= 1000);
        assertTrue(a.getEntityId() != b.getEntityId());
    }

    @Test
    void removeByUnknownConnectionReturnsNull() {
        PlayerRegistry registry = new PlayerRegistry();
        assertNull(registry.removeByConnection(new FakeConnection()));
    }

    /** Minimal stand-in connection: only identity matters for the registry. */
    private static final class FakeConnection implements PlayerConnection {
        @Override public ProtocolVersion getProtocolVersion() { return ProtocolVersion.JE_1_12_2; }
        @Override public String getAddress() { return "test"; }
        @Override public void sendPacket(Object packet) { }
        @Override public void sendMessage(String message) { }
        @Override public void addToTab(java.util.UUID uuid, String name) { }
        @Override public void removeFromTab(java.util.UUID uuid) { }
        @Override public void showPlayer(java.util.UUID uuid, String name, long entityId,
                                         double x, double y, double z, float yaw, float pitch) { }
        @Override public void hidePlayer(java.util.UUID uuid, long entityId) { }
        @Override public void moveAvatar(long entityId, double x, double y, double z, float yaw, float pitch) { }
        @Override public void teleport(double x, double y, double z, float yaw, float pitch) { }
        @Override public void swingArm(long entityId) { }
        @Override public void setPose(long entityId, boolean sneaking, boolean sprinting, boolean usingItem) { }
        @Override public void sendBlockChange(int x, int y, int z, int blockId) { }
        @Override public void close(String reason) { }
        @Override public boolean isActive() { return true; }
    }
}
