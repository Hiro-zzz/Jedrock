package com.jedrock.core.plugin;

import com.jedrock.api.config.ServerProperties;
import com.jedrock.utils.JLogger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one thing a script can do that reaches outside this process: an outbound HTTP request.
 *
 * <p><b>Asynchronous, with no synchronous form offered at all.</b> That is the whole design and it is
 * worth saying why, because a blocking `http.get` would be one line shorter to use and a genuinely bad
 * idea. Every script callback in this server runs under one shared lock ({@code PluginManager}), and
 * {@code ServerTickEvent} is posted from the game-loop thread — so a blocking call inside a listener
 * holds up <em>every other plugin</em>, and inside a tick handler it holds up the tick. A webhook that is
 * having a slow afternoon would show up as a server that lags, with nothing in the logs pointing at the
 * cause. Given the choice between an API that is pleasant and one that cannot do that, this takes the
 * second.
 *
 * <p>So requests run on a small pool of their own, and the callback is handed back through the same lock
 * every other script callback goes through. What a script never gets is a way to wait for one.
 *
 * <p><b>Bounded like the packet guards are</b>, and for the same reason: an outside party decides the
 * timing and the size of what comes back. A host allowlist, a timeout, a ceiling on the response body,
 * and a cap on how many requests may be in flight at once — past that cap a request is refused rather
 * than queued, because a queue with no limit is just a slower way to run out of memory.
 */
public final class ScriptHttpClient implements AutoCloseable {

    private static final JLogger LOGGER = JLogger.getLogger("http");

    private final ServerProperties.Http settings;
    private final List<String> allowedHosts;
    private final Semaphore inFlight;
    private final ExecutorService pool;
    private final HttpClient client;

    public ScriptHttpClient(ServerProperties.Http settings) {
        this.settings = settings;
        this.allowedHosts = parseHosts(settings.allowedHosts());
        this.inFlight = new Semaphore(Math.max(1, settings.maxConcurrent()));
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "jedrock-http-" + counter.incrementAndGet());
            thread.setDaemon(true); // a pending webhook must never hold the server open at shutdown
            return thread;
        };
        this.pool = Executors.newFixedThreadPool(Math.max(1, settings.maxConcurrent()), threads);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.timeoutMillis()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .executor(pool)
                .build();
        if (allowedHosts.isEmpty()) {
            LOGGER.warn("Scripts may reach ANY host — set plugins.http.allowed-hosts to narrow that");
        } else {
            LOGGER.info("Scripts may reach: " + String.join(", ", allowedHosts));
        }
    }

    /** What a request came back as — or didn't. A failure is a result here, not an exception to catch. */
    public record Result(int status, String body, Map<String, String> headers, String error) {

        static Result of(int status, String body, Map<String, String> headers) {
            return new Result(status, body, headers, null);
        }

        static Result failed(String error) {
            return new Result(0, "", Map.of(), error);
        }

        public boolean ok() {
            return error == null && status >= 200 && status < 300;
        }
    }

    /**
     * Send a request and hand the result to {@code whenDone} on a pool thread. Never throws for a network
     * failure — a refusal, a timeout and a 500 are all results a script has to handle anyway, and making
     * two of the three exceptions would only mean a try/catch around every call.
     *
     * @param method  GET / POST / PUT / DELETE …
     * @param url     the absolute URL
     * @param body    the request body, or {@code null}
     * @param headers extra headers, may be empty
     */
    public void send(String method, String url, String body, Map<String, String> headers,
                     java.util.function.Consumer<Result> whenDone) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            whenDone.accept(Result.failed("not a url: " + url));
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            whenDone.accept(Result.failed("only http and https are allowed, not '" + scheme + "'"));
            return;
        }
        if (!hostAllowed(uri.getHost())) {
            // Named explicitly: an operator reading this line should be able to fix it without guessing.
            whenDone.accept(Result.failed("host '" + uri.getHost()
                    + "' is not in plugins.http.allowed-hosts"));
            return;
        }
        if (!inFlight.tryAcquire()) {
            whenDone.accept(Result.failed("too many requests in flight (plugins.http.max-concurrent="
                    + settings.maxConcurrent() + ")"));
            return;
        }

        HttpRequest.BodyPublisher payload = body == null || body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(settings.timeoutMillis()))
                .method(method.toUpperCase(Locale.ROOT), payload);
        headers.forEach((name, value) -> {
            // The JDK refuses a handful of headers outright; one bad header must not lose the request.
            try {
                request.header(name, value);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Ignoring header '" + name + "': " + e.getMessage());
            }
        });

        client.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, failure) -> {
                    inFlight.release();
                    try {
                        whenDone.accept(failure != null ? Result.failed(describe(failure))
                                : Result.of(response.statusCode(), truncate(response.body()),
                                        firstHeaders(response)));
                    } catch (RuntimeException e) {
                        LOGGER.error("An http callback threw", e);
                    }
                });
    }

    /**
     * Whether {@code host} is one a script may reach. An empty allowlist means "anywhere", which is the
     * default only because a default of "nowhere" and an on switch would be two settings saying one thing;
     * the on switch is {@code plugins.http.enabled}, and it is off.
     *
     * <p>A listed host covers its subdomains, so {@code discord.com} allows {@code api.discord.com} —
     * matched on a label boundary, so it does not also allow {@code notdiscord.com}.
     */
    public boolean hostAllowed(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        if (allowedHosts.isEmpty()) {
            return true;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String allowed : allowedHosts) {
            if (lower.equals(allowed) || lower.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    /** A body past the ceiling is cut rather than refused: a truncated answer still tells a script something. */
    private String truncate(String body) {
        if (body == null) {
            return "";
        }
        if (body.length() <= settings.maxResponseBytes()) {
            return body;
        }
        LOGGER.warn("Truncating a " + body.length() + "-char response to plugins.http.max-response-bytes="
                + settings.maxResponseBytes());
        return body.substring(0, settings.maxResponseBytes());
    }

    private static Map<String, String> firstHeaders(HttpResponse<String> response) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        response.headers().map().forEach((name, values) -> {
            if (!values.isEmpty()) {
                out.put(name, values.get(0));
            }
        });
        return out;
    }

    /** Exception text a script can act on, rather than a stack trace it can't. */
    private static String describe(Throwable failure) {
        Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return "timed out";
        }
        if (cause instanceof java.net.UnknownHostException) {
            return "unknown host";
        }
        if (cause instanceof java.net.ConnectException) {
            return "could not connect";
        }
        if (cause instanceof IOException) {
            return "network error: " + cause.getMessage();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static List<String> parseHosts(String spec) {
        if (spec == null || spec.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(spec.split(","))
                .map(String::trim)
                .filter(host -> !host.isEmpty())
                .map(host -> host.toLowerCase(Locale.ROOT))
                .toList();
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
