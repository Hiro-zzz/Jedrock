package com.jedrock.core.moderation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reading {@code 2d} off a command line — and, more importantly, refusing to read {@code 30}.
 *
 * <p>{@code /ban alice 30 spam} is a moderator whose reason starts with a number, not somebody asking for
 * thirty of an unnamed unit. Guessing there would ban a person for a length nobody chose, and it would do
 * it silently, which is why the bare-number case has a test of its own.
 */
class DurationsTest {

    @Test
    void unitsRead() {
        assertEquals(30_000L, Durations.parse("30s"));
        assertEquals(600_000L, Durations.parse("10m"));
        assertEquals(7_200_000L, Durations.parse("2h"));
        assertEquals(172_800_000L, Durations.parse("2d"));
        assertEquals(604_800_000L, Durations.parse("1w"));
    }

    @Test
    void longFormsAndCasingRead() {
        assertEquals(Durations.parse("2h"), Durations.parse("2HOURS"));
        assertEquals(Durations.parse("3d"), Durations.parse(" 3days "));
    }

    @Test
    void permanentHasWords() {
        assertEquals(Durations.PERMANENT, Durations.parse("perm"));
        assertEquals(Durations.PERMANENT, Durations.parse("permanent"));
        assertEquals(Durations.PERMANENT, Durations.parse("forever"));
    }

    @Test
    void aBareNumberIsNotADuration() {
        assertEquals(Durations.NOT_A_DURATION, Durations.parse("30"),
                "it is the first word of the reason, and guessing a unit bans somebody for a made-up time");
    }

    @Test
    void nonsenseIsNotADuration() {
        assertEquals(Durations.NOT_A_DURATION, Durations.parse("spam"));
        assertEquals(Durations.NOT_A_DURATION, Durations.parse("2y"), "no unit for years");
        assertEquals(Durations.NOT_A_DURATION, Durations.parse("d"));
        assertEquals(Durations.NOT_A_DURATION, Durations.parse(""));
        assertEquals(Durations.NOT_A_DURATION, Durations.parse(null));
    }

    @Test
    void zeroIsNotAWayOfSayingForever() {
        assertEquals(Durations.NOT_A_DURATION, Durations.parse("0d"), "say perm if you mean perm");
    }

    @Test
    void anAbsurdLengthBecomesPermanentRatherThanOverflowing() {
        assertEquals(Durations.PERMANENT, Durations.parse("9999999999999999w"));
    }

    @Test
    void describingReadsLikeAPersonWroteIt() {
        assertEquals("permanent", Durations.describe(-1));
        assertEquals("45s", Durations.describe(45_000));
        assertEquals("2m", Durations.describe(120_000));
        assertEquals("1h 1m", Durations.describe(3_660_000));
        assertEquals("2d 3h", Durations.describe(183_600_000));
    }
}
