package com.jedrock.network.pe;

import com.jedrock.network.ConnectionListener;
import com.jedrock.utils.text.JsonText;

import java.util.List;

/**
 * Builds the JSON manifest for the MCPE 1.1.5 {@code AvailableCommands} packet (0x4e).
 *
 * <p>A Bedrock client validates a typed slash command against this manifest <em>before</em> sending it:
 * a command it was never told about is rejected client-side and never reaches the server. So a server
 * with in-game commands must advertise them here, or nothing it does with {@code CommandStep} matters.
 *
 * <p>Shape, from PocketMine-MP @ {@code 1.7dev-27} ({@code Player::sendCommandData} +
 * {@code resources/command_default.json}) and Nukkit's {@code CommandDataVersions} — a map of command
 * name to a {@code {"versions":[<command data>]}} wrapper, each version being the
 * {@code command_default.json} shape ({@code aliases} array, {@code permission} string):
 * <pre>
 *   {"gamemode":{"versions":[{
 *      "aliases":["gm"],
 *      "description":"Change a player's game mode",
 *      "overloads":{"default":{"input":{"parameters":[
 *          {"name":"args","type":"rawtext","optional":true}]},"output":{}}},
 *      "permission":"any"}]}}
 * </pre>
 *
 * <p>Every command declares the same single optional {@code rawtext} parameter, so the client packs the
 * whole argument tail into one string. {@link McpeCommandStep} then rebuilds {@code "/name args…"} and
 * the core's command layer re-splits it on whitespace — no per-command parameter schema needed.
 *
 * <p>Written by hand (the project keeps no JSON dependency); strings go through {@link JsonText#escape}.
 */
final class McpeAvailableCommands {

    private McpeAvailableCommands() {}

    /** The one overload every Jedrock command declares: a single optional free-text argument blob. */
    private static final String OVERLOADS =
            "\"overloads\":{\"default\":{\"input\":{\"parameters\":"
                    + "[{\"name\":\"args\",\"type\":\"rawtext\",\"optional\":true}]},\"output\":{}}}";

    /** Serialize the manifest. An empty command list yields {@code "{}"}. */
    static String buildJson(List<ConnectionListener.CommandInfo> commands) {
        StringBuilder sb = new StringBuilder(160 * Math.max(1, commands.size()));
        sb.append('{');
        boolean first = true;
        for (ConnectionListener.CommandInfo command : commands) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(JsonText.escape(command.name())).append("\":{\"versions\":[{");
            sb.append("\"aliases\":[");
            List<String> aliases = command.aliases();
            for (int i = 0; i < aliases.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append('"').append(JsonText.escape(aliases.get(i))).append('"');
            }
            sb.append("],\"description\":\"").append(JsonText.escape(command.description())).append("\",");
            sb.append(OVERLOADS).append(",\"permission\":\"any\"}]}");
        }
        return sb.append('}').toString();
    }
}
