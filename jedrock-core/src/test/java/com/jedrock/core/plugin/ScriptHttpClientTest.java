package com.jedrock.core.plugin;

import com.jedrock.api.config.ServerProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one capability that reaches outside this process, and therefore the one whose refusals matter as
 * much as its successes.
 *
 * <p>The round trip runs against a real HTTP server on loopback rather than a mock, because what is worth
 * proving is that an asynchronous request actually comes back — a mock would only confirm this class
 * agrees with itself. Every refusal is checked separately, since each is somebody's server not being
 * reached and the message is all they will have to go on.
 */
class ScriptHttpClientTest {

    private static ServerProperties.Http settings(String allowedHosts) {
        return new ServerProperties.Http(true, allowedHosts, 5_000L, 1024 * 1024, 4);
    }

    /** Fire one request and wait for its result. Returns null if nothing came back in time. */
    private static ScriptHttpClient.Result await(ScriptHttpClient client, String method, String url,
                                                 String body, Map<String, String> headers)
            throws InterruptedException {
        AtomicReference<ScriptHttpClient.Result> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        client.send(method, url, body, headers, result -> {
            seen.set(result);
            done.countDown();
        });
        return done.await(10, TimeUnit.SECONDS) ? seen.get() : null;
    }

    // ===== The round trip =====

    @Test
    void aRequestGoesOutAndTheAnswerComesBack() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            String received = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] reply = ("{\"echo\":" + received + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Test", "yes");
            exchange.sendResponseHeaders(200, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        try (ScriptHttpClient client = new ScriptHttpClient(settings("127.0.0.1"))) {
            ScriptHttpClient.Result result = await(client, "POST",
                    "http://127.0.0.1:" + port + "/hook", "{\"content\":\"hi\"}", Map.of());

            assertNotNull(result, "the callback never fired");
            assertTrue(result.ok(), "expected 2xx, got " + result.status() + " / " + result.error());
            assertEquals("{\"echo\":{\"content\":\"hi\"}}", result.body(), "the body went out and came back");
            assertNull(result.error());
            // Through the accessor a script actually uses: the JDK lower-cases header names on the way
            // in, so anyone reading them by the casing they were sent with would find nothing.
            assertEquals("yes", new ScriptHttp.Response(result, null).getHeader("X-Test"));
            assertEquals("yes", new ScriptHttp.Response(result, null).getHeader("x-test"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void anErrorStatusIsAResultRatherThanAFailure() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/boom", exchange -> {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();

        try (ScriptHttpClient client = new ScriptHttpClient(settings("127.0.0.1"))) {
            ScriptHttpClient.Result result = await(client, "GET",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/boom", null, Map.of());

            assertNotNull(result);
            assertEquals(500, result.status());
            assertFalse(result.ok());
            assertNull(result.error(), "the request succeeded; the server said no — those are different");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aBodyPastTheCeilingIsCutRatherThanRefused() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/big", exchange -> {
            byte[] reply = "x".repeat(5000).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();

        ServerProperties.Http small = new ServerProperties.Http(true, "127.0.0.1", 5_000L, 1024, 4);
        try (ScriptHttpClient client = new ScriptHttpClient(small)) {
            ScriptHttpClient.Result result = await(client, "GET",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/big", null, Map.of());

            assertNotNull(result);
            assertEquals(1024, result.body().length(), "a truncated answer still tells a script something");
        } finally {
            server.stop(0);
        }
    }

    // ===== The refusals, none of which touch the network =====

    @Test
    void aHostOutsideTheAllowlistIsRefusedAndTheMessageNamesTheSetting() throws Exception {
        try (ScriptHttpClient client = new ScriptHttpClient(settings("example.com"))) {
            ScriptHttpClient.Result result =
                    await(client, "GET", "https://evil.test/steal", null, Map.of());

            assertNotNull(result);
            assertFalse(result.ok());
            assertTrue(result.error().contains("plugins.http.allowed-hosts"),
                    "an operator reading this should be able to fix it without guessing: " + result.error());
        }
    }

    @Test
    void onlyHttpAndHttpsGoAnywhere() throws Exception {
        try (ScriptHttpClient client = new ScriptHttpClient(settings(""))) {
            assertTrue(await(client, "GET", "file:///etc/passwd", null, Map.of())
                    .error().contains("only http and https"));
            assertNotNull(await(client, "GET", "ftp://example.com/x", null, Map.of()).error());
        }
    }

    @Test
    void somethingThatIsNotAUrlIsAResultToo() throws Exception {
        try (ScriptHttpClient client = new ScriptHttpClient(settings(""))) {
            ScriptHttpClient.Result result = await(client, "GET", "not a url at all", null, Map.of());
            assertNotNull(result);
            assertFalse(result.ok());
        }
    }

    // ===== The allowlist itself =====

    @Test
    void aListedHostCoversItsSubdomainsAndNothingElse() {
        try (ScriptHttpClient client = new ScriptHttpClient(settings("discord.com, example.org"))) {
            assertTrue(client.hostAllowed("discord.com"));
            assertTrue(client.hostAllowed("api.discord.com"), "a subdomain is the same service");
            assertTrue(client.hostAllowed("DISCORD.COM"), "hosts are not case-sensitive");
            assertTrue(client.hostAllowed("example.org"));

            assertFalse(client.hostAllowed("notdiscord.com"),
                    "matched on a label boundary — this is the one that would quietly be a hole");
            assertFalse(client.hostAllowed("discord.com.evil.test"));
            assertFalse(client.hostAllowed("example.com"));
            assertFalse(client.hostAllowed(null));
            assertFalse(client.hostAllowed(""));
        }
    }

    @Test
    void anEmptyAllowlistMeansAnywhere() {
        try (ScriptHttpClient client = new ScriptHttpClient(settings(""))) {
            assertTrue(client.hostAllowed("anything.test"),
                    "the off switch is plugins.http.enabled, not an empty list");
        }
    }

    @Test
    void theClientShutsItsPoolDown() throws IOException {
        ScriptHttpClient client = new ScriptHttpClient(settings(""));
        client.close();
        client.close(); // twice is harmless — shutdown paths get called from more than one place
    }
}
