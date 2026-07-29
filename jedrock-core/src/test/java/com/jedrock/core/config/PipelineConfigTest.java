package com.jedrock.core.config;

import com.jedrock.api.config.PipelineSettings;
import com.jedrock.utils.yaml.Yaml;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading {@code pipeline.yml}: what an operator writes becomes settings, what they leave out stays the
 * compiled-in default, and what they get wrong is refused rather than obeyed — this is the file where an
 * obeyed nonsense value means a memory limit someone else chose.
 */
class PipelineConfigTest {

    private static PipelineSettings parse(String yaml) {
        return PipelineConfig.parse(Yaml.parse(yaml, "pipeline.yml"));
    }

    @Test
    void anEmptyFileIsEveryDefault() {
        assertEquals(PipelineSettings.defaults(), parse(""));
    }

    @Test
    void aSectionThatIsWrittenIsRead() {
        PipelineSettings s = parse("""
                netty:
                  boss-threads: 2
                  worker-threads: 8
                  tcp-nodelay: false
                  backlog: 512
                java:
                  keep-alive-seconds: 10
                bedrock:
                  v1_1_5:
                    max-view-radius: 6
                    announce-dimension: false
                    resync-delay-millis: 250
                  v0_14:
                    max-view-radius: 3
                guard:
                  max-packets-per-batch: 256
                """);

        assertEquals(2, s.netty().bossThreads());
        assertEquals(8, s.netty().workerThreads());
        assertFalse(s.netty().tcpNoDelay());
        assertEquals(512, s.netty().backlog());
        assertEquals(10, s.java().keepAliveSeconds());
        assertEquals(6, s.bedrock().v1_1_5().maxViewRadius());
        assertFalse(s.bedrock().v1_1_5().announceDimension());
        assertEquals(250L, s.bedrock().v1_1_5().resyncDelayMillis());
        assertEquals(3, s.bedrock().v0_14().maxViewRadius());
        assertEquals(256, s.guard().maxPacketsPerBatch());
    }

    @Test
    void theTwoBedrockErasAreSeparateSettings() {
        PipelineSettings s = parse("""
                bedrock:
                  v1_1_5:
                    max-view-radius: 8
                """);
        assertEquals(8, s.bedrock().v1_1_5().maxViewRadius());
        assertEquals(PipelineSettings.Era.defaults014().maxViewRadius(), s.bedrock().v0_14().maxViewRadius(),
                "tuning one era must not move the other");
    }

    @Test
    void keysLeftOutOfAWrittenSectionKeepTheirDefaults() {
        PipelineSettings s = parse("""
                netty:
                  boss-threads: 3
                """);
        assertEquals(3, s.netty().bossThreads());
        assertEquals(PipelineSettings.Netty.defaults().backlog(), s.netty().backlog());
        assertTrue(s.netty().soKeepAlive());
    }

    @Test
    void aValueOutsideItsRangeIsRefused() {
        PipelineSettings def = PipelineSettings.defaults();
        PipelineSettings s = parse("""
                netty:
                  boss-threads: 0
                bedrock:
                  v1_1_5:
                    max-view-radius: 1
                guard:
                  max-inflated-batch-bytes: 999999999
                """);

        assertEquals(def.netty().bossThreads(), s.netty().bossThreads(), "zero accept threads accepts nothing");
        assertEquals(def.bedrock().v1_1_5().maxViewRadius(), s.bedrock().v1_1_5().maxViewRadius(),
                "a radius of 1 drops the client through the chunk it stands in");
        assertEquals(def.guard().maxInflatedBatchBytes(), s.guard().maxInflatedBatchBytes(),
                "a guard raised past a gigabyte is not a guard");
    }

    @Test
    void nonsenseInTheFileIsNotFatal() {
        PipelineSettings s = parse("""
                netty:
                  boss-threads: plenty
                java:
                  keep-alive-seconds: "15"
                nonsense
                """);
        assertEquals(PipelineSettings.Netty.defaults().bossThreads(), s.netty().bossThreads());
        assertEquals(PipelineSettings.JavaEdition.defaults().keepAliveSeconds(), s.java().keepAliveSeconds(),
                "a quoted number is text, and text is not a setting");
    }
}
