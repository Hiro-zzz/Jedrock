package com.jedrock.core.config;

import com.jedrock.api.config.ServerProperties;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parsing behaviour of {@link JedrockConfig}: defaults fill the gaps, valid values are honoured,
 * and malformed/out-of-range values fall back instead of failing.
 */
class JedrockConfigTest {

    @Test
    void emptyPropertiesYieldsDefaults() {
        assertEquals(ServerProperties.defaults(), JedrockConfig.parse(new Properties()));
    }

    @Test
    void validValuesAreParsed() {
        Properties p = new Properties();
        p.setProperty("server.name", "My Server");
        p.setProperty("server.bind", "127.0.0.1");
        p.setProperty("server.port.je", "25566");
        p.setProperty("server.port.pe", "19133");
        p.setProperty("server.max-players", "100");
        p.setProperty("server.motd", "Welcome");
        p.setProperty("world.seed", "12345");
        p.setProperty("game.tick-rate", "40");
        p.setProperty("game.view-distance", "10");
        p.setProperty("judge.enabled", "false");
        p.setProperty("judge.max-reach", "5.5");
        p.setProperty("judge.max-move-delta", "12.0");

        ServerProperties c = JedrockConfig.parse(p);
        assertEquals("My Server", c.name());
        assertEquals("127.0.0.1", c.bindHost());
        assertEquals(25566, c.javaPort());
        assertEquals(19133, c.bedrockPort());
        assertEquals(100, c.maxPlayers());
        assertEquals("Welcome", c.motd());
        assertEquals(12345L, c.seed());
        assertEquals(40, c.tickRate());
        assertEquals(10, c.viewDistance());
        assertFalse(c.judgeEnabled());
        assertEquals(5.5, c.maxReach());
        assertEquals(12.0, c.maxMoveDelta());
    }

    @Test
    void malformedJudgeValuesFallBackToDefaults() {
        Properties p = new Properties();
        p.setProperty("judge.max-reach", "not-a-number");
        p.setProperty("judge.max-move-delta", "-3");   // non-positive

        ServerProperties def = ServerProperties.defaults();
        ServerProperties c = JedrockConfig.parse(p);
        assertEquals(def.maxReach(), c.maxReach());
        assertEquals(def.maxMoveDelta(), c.maxMoveDelta());
        assertTrue(c.judgeEnabled(), "unset judge.enabled keeps the default (true)");
    }

    @Test
    void blankValuesFallBackToDefaults() {
        Properties p = new Properties();
        p.setProperty("server.name", "   ");
        p.setProperty("world.seed", "");
        ServerProperties def = ServerProperties.defaults();

        ServerProperties c = JedrockConfig.parse(p);
        assertEquals(def.name(), c.name());
        assertEquals(def.seed(), c.seed());
    }

    @Test
    void malformedNumberFallsBackToDefault() {
        Properties p = new Properties();
        p.setProperty("server.port.je", "not-a-number");
        p.setProperty("game.tick-rate", "-5");        // non-positive
        p.setProperty("server.port.pe", "70000");      // out of port range

        ServerProperties def = ServerProperties.defaults();
        ServerProperties c = JedrockConfig.parse(p);
        assertEquals(def.javaPort(), c.javaPort());
        assertEquals(def.tickRate(), c.tickRate());
        assertEquals(def.bedrockPort(), c.bedrockPort());
    }

    @Test
    void nonNumericSeedIsHashed() {
        Properties p = new Properties();
        p.setProperty("world.seed", "hello");
        assertEquals("hello".hashCode(), JedrockConfig.parse(p).seed());
    }

    @Test
    void bundledTemplateIsOnTheClasspath() throws Exception {
        // The first-run auto-create copies this resource to disk; if it's unpackaged or renamed,
        // template creation silently no-ops. Guard that it ships and parses to the defaults.
        try (InputStream in = JedrockConfig.class.getResourceAsStream("/jedrock.properties")) {
            assertNotNull(in, "bundled jedrock.properties template must be on the classpath");
            Properties template = new Properties();
            template.load(in);
            assertEquals(ServerProperties.defaults(), JedrockConfig.parse(template),
                    "template values should match the built-in defaults");
        }
    }

    @Test
    void defaultGameModeIsParsedAndFallsBack() {
        Properties valid = new Properties();
        valid.setProperty("game.default-gamemode", "survival");
        assertEquals(com.jedrock.api.player.GameMode.SURVIVAL,
                JedrockConfig.parse(valid).defaultGameMode());

        Properties bad = new Properties();
        bad.setProperty("game.default-gamemode", "god-mode");
        assertEquals(ServerProperties.defaults().defaultGameMode(),
                JedrockConfig.parse(bad).defaultGameMode());
    }

    @Test
    void randomSeedIsNotTheDefault() {
        Properties p = new Properties();
        p.setProperty("world.seed", "random");
        // Astronomically unlikely to collide with the fixed default; guards the 'random' branch.
        assertNotEquals(ServerProperties.defaults().seed(), JedrockConfig.parse(p).seed());
    }
}
