package com.jedrock.api.config;

import com.jedrock.api.player.GameMode;

/**
 * Immutable, protocol-agnostic server settings — the file-backed knobs the core and the network
 * layer both read (bind addresses, world seed, the server-list advertisement). Pure data: it holds
 * no file-IO logic (that lives in the core's config loader), so the {@code api} module stays a
 * dependency-free contract. Obtain the built-in defaults via {@link #defaults()}.
 *
 * @param name         human-readable server name
 * @param bindHost     address to bind both listeners to (e.g. {@code 0.0.0.0})
 * @param javaPort     TCP port for Java Edition
 * @param bedrockPort  UDP port for Bedrock / RakNet
 * @param maxPlayers   advertised player cap (server-list ping + JE Join Game)
 * @param motd         message-of-the-day shown in the server list
 * @param seed         world-generation seed
 * @param tickRate     game-loop rate in ticks per second
 * @param viewDistance chunk streaming radius around each player
 * @param judgeEnabled whether the "blind judge" lazy anti-cheat validation is on
 * @param maxReach     max block-interaction distance (blocks) from the player
 * @param maxMoveDelta max position change (blocks) between two movement reports
 * @param bedrock014Port    UDP port for the experimental MCPE 0.14 listener (own RakNet version)
 * @param bedrock014Enabled whether the MCPE 0.14 listener is bound at all
 * @param defaultGameMode   game mode a player joins in (survival / creative / …)
 */
public record ServerProperties(
        String name,
        String bindHost,
        int javaPort,
        int bedrockPort,
        int maxPlayers,
        String motd,
        long seed,
        int tickRate,
        int viewDistance,
        boolean judgeEnabled,
        double maxReach,
        double maxMoveDelta,
        int bedrock014Port,
        boolean bedrock014Enabled,
        GameMode defaultGameMode
) {

    /** The built-in defaults, used when no config file is present or a key is missing/invalid. */
    public static ServerProperties defaults() {
        return new ServerProperties(
                "Jedrock",       // name
                "0.0.0.0",       // bindHost
                25565,           // javaPort
                19132,           // bedrockPort
                20,              // maxPlayers
                "Jedrock",       // motd
                0x5EED1EAFL,     // seed — fixed so restarts reproduce the same terrain
                20,              // tickRate
                6,               // viewDistance
                true,            // judgeEnabled
                7.0,             // maxReach — creative reach (~6) plus margin
                16.0,            // maxMoveDelta — generous; catches teleport/speed, not lag/falls
                19133,           // bedrock014Port — experimental MCPE 0.14, its own UDP port
                true,            // bedrock014Enabled
                GameMode.CREATIVE // defaultGameMode — creative preserves the current join behaviour
        );
    }
}
