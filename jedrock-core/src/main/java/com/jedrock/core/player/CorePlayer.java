package com.jedrock.core.player;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.Player;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.world.Location;
import com.jedrock.api.world.World;
import com.jedrock.core.world.CoreWorld;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory player state. A thin wrapper over the abstract {@link PlayerConnection};
 * it holds no Netty or protocol details, only game-facing state.
 */
public final class CorePlayer implements Player {

    /**
     * Avatar entity ids start above the self-ids the protocol handlers hardcode for the
     * client's own player (JE JoinGame / PE StartGame both use 1), so they never collide.
     */
    private static final AtomicLong ENTITY_IDS = new AtomicLong(1000);

    private final UUID uniqueId;
    private final String name;
    private final PlayerConnection connection;
    private final long entityId = ENTITY_IDS.getAndIncrement();

    private volatile CoreWorld world;
    private volatile Location location;
    private volatile GameMode gameMode = GameMode.CREATIVE;
    private volatile boolean sneaking = false;
    private volatile boolean sprinting = false;
    private volatile boolean usingItem = false;

    public CorePlayer(UUID uniqueId, String name, PlayerConnection connection,
                      CoreWorld world, Location location) {
        this.uniqueId = uniqueId;
        this.name = name;
        this.connection = connection;
        this.world = world;
        this.location = location;
    }

    @Override
    public UUID getUniqueId() {
        return uniqueId;
    }

    @Override
    public long getEntityId() {
        return entityId;
    }

    /** Whether the player is currently crouching — used to sync pose to players who join later. */
    public boolean isSneaking() {
        return sneaking;
    }

    public void setSneaking(boolean sneaking) {
        this.sneaking = sneaking;
    }

    /** Whether the player is currently sprinting — synced (with sneak) to players who join later. */
    public boolean isSprinting() {
        return sprinting;
    }

    public void setSprinting(boolean sprinting) {
        this.sprinting = sprinting;
    }

    /** Whether the player is currently using an item (eat / drink / block / draw bow). */
    public boolean isUsingItem() {
        return usingItem;
    }

    public void setUsingItem(boolean usingItem) {
        this.usingItem = usingItem;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public World getWorld() {
        return world;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public void setLocation(Location location) {
        this.location = location;
    }

    @Override
    public void teleport(Location location) {
        // State-only for now; sending the position packet to the client is a later step.
        this.location = location;
        if (location.world() instanceof CoreWorld cw) {
            this.world = cw;
        }
    }

    @Override
    public GameMode getGameMode() {
        return gameMode;
    }

    @Override
    public void setGameMode(GameMode gameMode) {
        this.gameMode = gameMode;
    }

    @Override
    public void kick(String reason) {
        connection.close(reason);
    }

    @Override
    public void sendMessage(String message) {
        connection.sendMessage(message);
    }

    @Override
    public boolean isOnline() {
        return connection.isActive();
    }

    @Override
    public PlayerConnection getConnection() {
        return connection;
    }

    // ===== Entity =====

    @Override
    public void remove() {
        connection.close("removed");
    }

    @Override
    public boolean isAlive() {
        return isOnline();
    }

    @Override
    public String getType() {
        return "minecraft:player";
    }
}
