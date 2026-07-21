package com.jedrock.core.command;

import com.jedrock.api.command.CommandSender;
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
    public boolean playerOnly() {
        return true;
    }

    @Override
    public void execute(JedrockServer server, CommandSender sender, String[] args) {
        CorePlayer self = (CorePlayer) sender; // playerOnly() guarantees a player
        Location spawn = self.getWorld().getSpawnLocation();
        server.teleport(self, spawn);
        self.sendMessage("{green}Teleported to spawn.");
    }
}
