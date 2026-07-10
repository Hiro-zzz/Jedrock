package com.jedrock.network.pe;

import com.jedrock.network.ConnectionListener.CommandInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Bedrock client drops any command missing from this manifest, so its shape is load-bearing —
 * pin it against PocketMine's {@code command_default.json} template.
 */
class McpeAvailableCommandsTest {

    private static final String OVERLOADS =
            "\"overloads\":{\"default\":{\"input\":{\"parameters\":"
                    + "[{\"name\":\"args\",\"type\":\"rawtext\",\"optional\":true}]},\"output\":{}}}";

    @Test
    void emptyListYieldsEmptyObject() {
        assertEquals("{}", McpeAvailableCommands.buildJson(List.of()));
    }

    @Test
    void oneCommandWithAliasMatchesTheTemplate() {
        String json = McpeAvailableCommands.buildJson(
                List.of(new CommandInfo("gamemode", "Change a player's game mode", List.of("gm"))));

        // Protocol 113: each command wraps its data in a one-entry "versions" list (Nukkit
        // CommandDataVersions), each version being the command_default.json shape.
        assertEquals("{\"gamemode\":{\"versions\":[{"
                + "\"aliases\":[\"gm\"],"
                + "\"description\":\"Change a player's game mode\","
                + OVERLOADS + ",\"permission\":\"any\"}]}}", json);
    }

    @Test
    void commandWithoutAliasesHasEmptyAliasArray() {
        String json = McpeAvailableCommands.buildJson(
                List.of(new CommandInfo("spawn", "Teleport to the world spawn", List.of())));
        assertTrue(json.contains("\"aliases\":[]"), json);
        assertTrue(json.startsWith("{\"spawn\":{\"versions\":[{"), json);
    }

    @Test
    void multipleCommandsAreCommaSeparated() {
        String json = McpeAvailableCommands.buildJson(List.of(
                new CommandInfo("help", "List", List.of()),
                new CommandInfo("spawn", "Go", List.of())));
        assertTrue(json.contains("}]},\"spawn\":{\"versions\":[{"), json);
    }

    @Test
    void descriptionIsJsonEscaped() {
        String json = McpeAvailableCommands.buildJson(
                List.of(new CommandInfo("say", "Say \"hi\"", List.of())));
        assertTrue(json.contains("\"description\":\"Say \\\"hi\\\"\""), json);
    }
}
