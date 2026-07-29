package com.jedrock.core.config;

import com.jedrock.api.config.PipelineSettings;
import com.jedrock.utils.JLogger;
import com.jedrock.utils.yaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads {@link PipelineSettings} from {@code pipeline.yml}, the same way {@link JedrockConfig} loads
 * {@code jedrock.properties}: written from a bundled template on first run, read on every later one, every
 * key optional and every bad key a warning rather than a failure to start.
 *
 * <p>It is a separate file, in a different format, on purpose. A properties file is a flat list of names
 * and this isn't flat data — it is four subsystems, two of which have two eras. Writing that as
 * {@code bedrock.v1_1_5.max-view-radius=4} would be a nested file pretending not to be one. And the split
 * says something true about the two: {@code jedrock.properties} is the file you edit to run a server;
 * this is the file you edit when you already know why.
 */
public final class PipelineConfig {

    private static final JLogger LOGGER = JLogger.getLogger("Pipeline");

    static final String FILE_NAME = "pipeline.yml";

    private PipelineConfig() {}

    /** Load (and on first run create) {@code pipeline.yml} beside the server. Never throws. */
    public static PipelineSettings load(Path file) {
        if (!Files.isRegularFile(file)) {
            writeDefaultTemplate(file);
            return PipelineSettings.defaults();
        }
        try {
            PipelineSettings settings = parse(Yaml.load(file));
            LOGGER.info("Loaded pipeline settings from " + file.toAbsolutePath());
            return settings;
        } catch (IOException e) {
            LOGGER.warn("Failed to read " + FILE_NAME + " (" + e.getMessage() + "); using pipeline defaults");
            return PipelineSettings.defaults();
        }
    }

    /** Package-visible for tests: turn a parsed document into typed settings. */
    static PipelineSettings parse(Yaml.Section y) {
        PipelineSettings def = PipelineSettings.defaults();
        return new PipelineSettings(
                netty(y.section("netty"), def.netty()),
                new PipelineSettings.JavaEdition(
                        y.getInt("java.keep-alive-seconds", def.java().keepAliveSeconds(), 1, 300)),
                new PipelineSettings.Bedrock(
                        era(y.section("bedrock.v1_1_5"), def.bedrock().v1_1_5()),
                        era(y.section("bedrock.v0_14"), def.bedrock().v0_14())),
                guard(y.section("guard"), def.guard()));
    }

    private static PipelineSettings.Netty netty(Yaml.Section y, PipelineSettings.Netty def) {
        return new PipelineSettings.Netty(
                y.getInt("boss-threads", def.bossThreads(), 1, 64),
                y.getInt("worker-threads", def.workerThreads(), 0, 512),
                y.getBool("tcp-nodelay", def.tcpNoDelay()),
                y.getBool("so-keepalive", def.soKeepAlive()),
                y.getBool("reuse-address", def.reuseAddress()),
                y.getInt("backlog", def.backlog(), 1, 65535));
    }

    private static PipelineSettings.Era era(Yaml.Section y, PipelineSettings.Era def) {
        return new PipelineSettings.Era(
                // The floor is 2 because a client that can't see the chunk it stands in falls through it,
                // and the ceiling is where a full-column era stops being able to keep up.
                y.getInt("max-view-radius", def.maxViewRadius(), 2, 32),
                y.getInt("sidebar-repaint-ticks", def.sidebarRepaintTicks(), 1, 1200),
                y.getInt("max-particle-burst", def.maxParticleBurst(), 1, 512),
                y.getLong("resync-delay-millis", def.resyncDelayMillis()),
                y.getBool("announce-dimension", def.announceDimension()));
    }

    private static PipelineSettings.Guard guard(Yaml.Section y, PipelineSettings.Guard def) {
        return new PipelineSettings.Guard(
                // A guard that can be raised without limit is not a guard: 64 MiB is already far past any
                // batch a real client sends, and past it the setting would only be a way to be OOMed.
                y.getInt("max-inflated-batch-bytes", def.maxInflatedBatchBytes(), 64 * 1024, 64 * 1024 * 1024),
                y.getInt("max-packets-per-batch", def.maxPacketsPerBatch(), 8, 65536),
                y.getInt("max-list-entries", def.maxListEntries(), 8, 65536));
    }

    private static void writeDefaultTemplate(Path path) {
        try (InputStream in = PipelineConfig.class.getResourceAsStream("/" + FILE_NAME)) {
            if (in == null) {
                LOGGER.warn("No bundled " + FILE_NAME + " template found; running with pipeline defaults");
                return;
            }
            Files.copy(in, path);
            LOGGER.info("No " + FILE_NAME + " found — wrote a default one to " + path.toAbsolutePath());
        } catch (IOException e) {
            LOGGER.warn("Could not write default " + FILE_NAME + " (" + e.getMessage()
                    + "); using pipeline defaults");
        }
    }
}
