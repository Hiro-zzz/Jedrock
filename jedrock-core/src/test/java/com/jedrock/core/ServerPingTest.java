package com.jedrock.core;

import com.jedrock.api.ServerPing;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The answer to a server-list ping, as a thing that can be rewritten before it is serialized.
 *
 * <p>Small enough to have no behaviour worth testing except the clamping and the read-only half, which is
 * the point: the network fills this in, the core hands it round, the network reads it back. What is worth
 * pinning is that a listener cannot put nonsense in it — this is answered on an I/O thread and whatever
 * comes out goes straight onto the wire.
 */
class ServerPingTest {

    private ServerPing ping() {
        return new ServerPing("127.0.0.1:51234", 340, false, "A server", 3, 20);
    }

    @Test
    void itStartsAsWhatTheConfigSaid() {
        ServerPing ping = ping();
        assertEquals("A server", ping.getMotd());
        assertEquals(3, ping.getOnlinePlayers());
        assertEquals(20, ping.getMaxPlayers());
    }

    @Test
    void whoIsAskingIsReadOnly() {
        ServerPing ping = ping();
        assertEquals("127.0.0.1:51234", ping.getAddress());
        assertEquals(340, ping.getProtocol(), "the client's own number on Java — 1.12.2 here");
        assertFalse(ping.isBedrock());
    }

    @Test
    void aListenerRewritesWhatIsShown() {
        ServerPing ping = ping();

        ping.setMotd("{gold}Maintenance");
        ping.setOnlinePlayers(0);
        ping.setMaxPlayers(1);

        assertEquals("{gold}Maintenance", ping.getMotd());
        assertEquals(0, ping.getOnlinePlayers());
        assertEquals(1, ping.getMaxPlayers());
    }

    @Test
    void nonsenseIsClampedRatherThanSerialized() {
        ServerPing ping = ping();

        ping.setOnlinePlayers(-5);
        ping.setMaxPlayers(-1);
        ping.setMotd(null);

        assertEquals(0, ping.getOnlinePlayers());
        assertEquals(0, ping.getMaxPlayers());
        assertEquals("", ping.getMotd(), "an empty line, not the word 'null' in somebody's server list");
    }

    @Test
    void theBedrockQueryReportsItself() {
        ServerPing ping = new ServerPing("10.0.0.2:19132", 113, true, "PE", 0, 20);
        assertTrue(ping.isBedrock());
        assertEquals(113, ping.getProtocol(), "that query carries no client version — this is ours");
    }
}
