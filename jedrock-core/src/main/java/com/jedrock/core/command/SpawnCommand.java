package com.jedrock.core.command;

import com.jedrock.api.world.Location;
import com.jedrock.core.JedrockServer;
import com.jedrock.core.player.CorePlayer;

/** {@code /spawn} — teleport the sender to the world's spawn point. */
public final class SpawnCommand implements Command {

    @Override
    public String name() {
        return "spawn";
    }

    @Override
    public String description() {
        return "Teleport to the world spawn";
    }

    @Override
    public String usage() {
        return "/spawn";
    }

    @Override
    public void execute(JedrockServer server, CorePlayer sender, String[] args) {
        Location spawn = sender.getWorld().getSpawnLocation();
        server.teleport(sender, spawn);
        sender.sendMessage("{green}Teleported to spawn.");
    }
}
