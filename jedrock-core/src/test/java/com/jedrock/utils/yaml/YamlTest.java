package com.jedrock.utils.yaml;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The YAML subset this project reads: nesting, scalars, sequences, comments — and, just as much, what it
 * does with a line it doesn't understand, since a config reader that throws is a server that won't start.
 */
class YamlTest {

    private static Yaml.Section parse(String text) {
        return Yaml.parse(text, "test.yml");
    }

    @Test
    void readsNestedMappingsByDottedPath() {
        Yaml.Section y = parse("""
                netty:
                  boss-threads: 1
                  worker-threads: 0
                bedrock:
                  v1_1_5:
                    max-view-radius: 6
                    announce-dimension: false
                """);

        assertEquals(1, y.getInt("netty.boss-threads", -1));
        assertEquals(0, y.getInt("netty.worker-threads", -1));
        assertEquals(6, y.getInt("bedrock.v1_1_5.max-view-radius", -1));
        assertFalse(y.getBool("bedrock.v1_1_5.announce-dimension", true));
    }

    @Test
    void aSectionCanBeTakenAndReadFromRelatively() {
        Yaml.Section y = parse("""
                guard:
                  max-packets-per-batch: 512
                """);
        Yaml.Section guard = y.section("guard");
        assertEquals(512, guard.getInt("max-packets-per-batch", -1));
        assertTrue(y.section("nothing-here").isEmpty(), "a missing section is empty, never null");
        assertEquals(7, y.section("nothing-here").getInt("whatever", 7), "and reads as all defaults");
    }

    @Test
    void understandsTheScalarsAConfigActuallyUses() {
        Yaml.Section y = parse("""
                yes-word: yes
                off-word: off
                number: 42
                negative: -7
                decimal: 1.5
                text: hello world
                quoted: "42"
                empty: ~
                """);

        assertTrue(y.getBool("yes-word", false));
        assertFalse(y.getBool("off-word", true));
        assertEquals(42, y.getInt("number", -1));
        assertEquals(-7, y.getInt("negative", 0));
        assertEquals(1.5, y.getDouble("decimal", 0), 1e-9);
        assertEquals("hello world", y.getString("text", ""));
        assertEquals("42", y.getString("quoted", ""), "quoting is how you say 'this is text'");
        assertEquals(9, y.getInt("empty", 9), "an explicit null is the same as absent: the default");
    }

    @Test
    void readsSequencesBothWaysTheyAreWritten() {
        Yaml.Section y = parse("""
                block:
                  - one
                  - two
                inline: [three, four]
                """);
        assertEquals(List.of("one", "two"), y.getList("block"));
        assertEquals(List.of("three", "four"), y.getList("inline"));
        assertEquals(List.of(), y.getList("missing"));
    }

    @Test
    void commentsAndBlankLinesAreNotData() {
        Yaml.Section y = parse("""
                # a leading comment

                netty:
                  # about the threads
                  boss-threads: 2   # trailing, too

                  worker-threads: 4
                text: 'a # inside quotes is not a comment'
                """);
        assertEquals(2, y.getInt("netty.boss-threads", -1));
        assertEquals(4, y.getInt("netty.worker-threads", -1));
        assertEquals("a # inside quotes is not a comment", y.getString("text", ""));
    }

    @Test
    void aValueOfTheWrongShapeFallsBackInsteadOfThrowing() {
        Yaml.Section y = parse("""
                threads: not-a-number
                flag: perhaps
                """);
        assertEquals(4, y.getInt("threads", 4));
        assertTrue(y.getBool("flag", true));
    }

    @Test
    void anOutOfRangeNumberIsRefusedRatherThanClamped() {
        Yaml.Section y = parse("radius: 900\n");
        assertEquals(4, y.getInt("radius", 4, 2, 32), "a nonsense radius takes the default, not the ceiling");
        assertEquals(900, y.getInt("radius", 4, 2, 1000), "…and a legal one is simply used");
    }

    @Test
    void aFileThatIsNotAMappingIsSurvived() {
        assertTrue(parse("- just\n- a list\n").isEmpty());
        assertTrue(parse("").isEmpty());
        assertEquals(3, parse("").getInt("anything", 3));
    }

    @Test
    void anIndentedLineUnderAScalarIsIgnoredNotMisread() {
        Yaml.Section y = parse("""
                key: value
                    stray: line
                other: 2
                """);
        assertEquals("value", y.getString("key", ""));
        assertEquals(2, y.getInt("other", -1), "the file keeps parsing after the bad line");
    }
}
