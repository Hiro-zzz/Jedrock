package com.jedrock.core.plugin;

import com.jedrock.api.player.GameMode;
import com.jedrock.api.player.PlayerConnection;
import com.jedrock.api.protocol.ProtocolVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A player connection that only remembers what it was told to say. Script tests assert through chat
 * because that is what a script can reach without a client: the script prints its answer, this keeps it.
 */
final class RecordingConnection implements PlayerConnection {

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
