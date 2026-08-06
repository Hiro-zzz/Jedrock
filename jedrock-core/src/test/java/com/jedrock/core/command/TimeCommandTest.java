package com.jedrock.core.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Reading a time off a command line, and printing one back in terms a person recognises.
 *
 * <p>{@code 13000} is a number nobody has an intuition for, which is the reason the command answers in
 * hours and phases as well. Tick 0 being 06:00 is the fact all of that rests on.
 */
class TimeCommandTest {

    @Test
    void theNamedHoursAreTheOnesPeopleType() {
        assertEquals(1000L, TimeCommand.parse("day"));
        assertEquals(6000L, TimeCommand.parse("noon"));
        assertEquals(13000L, TimeCommand.parse("night"));
        assertEquals(18000L, TimeCommand.parse("MIDNIGHT"));
        assertEquals(12000L, TimeCommand.parse(" sunset "));
        assertEquals(4200L, TimeCommand.parse("4200"), "a raw tick count still works");
    }

    @Test
    void somethingThatIsNotATimeIsRefusedRatherThanGuessed() {
        assertNull(TimeCommand.parse("elevenish"));
        assertNull(TimeCommand.parse(""));
    }

    @Test
    void aTickCountReadsBackAsAnHourAnybodyRecognises() {
        assertEquals("06:00", TimeCommand.clock(0), "tick 0 is sunrise, not midnight");
        assertEquals("12:00", TimeCommand.clock(6000));
        assertEquals("18:00", TimeCommand.clock(12000));
        assertEquals("00:00", TimeCommand.clock(18000));
        assertEquals("06:00", TimeCommand.clock(24000), "a whole day later is the same hour");
    }

    @Test
    void thePhaseIsTheOneTheGameBehavesBy() {
        assertEquals("day", TimeCommand.phase(1000));
        assertEquals("sunset", TimeCommand.phase(12500));
        assertEquals("night", TimeCommand.phase(18000));
        assertEquals("sunrise", TimeCommand.phase(23500));
        assertEquals("day", TimeCommand.phase(25000), "past a full day it wraps");
    }

    @Test
    void aBlockStateIsWrittenEveryWayPeopleWriteOne() {
        assertEquals(35 << 4, PoseCommand.state("35"));
        assertEquals((35 << 4) | 14, PoseCommand.state("35:14"), "red wool");
        // Named, since /pose now parses a prop's block the same way /give parses an item.
        assertEquals((35 << 4) | 14, PoseCommand.state("red_wool"));
        assertEquals(35 << 4, PoseCommand.state("wool"), "a family word is its meta-0 member");
        assertNull(PoseCommand.state("35:16"), "meta is four bits");
        assertNull(PoseCommand.state("not_a_block"));
    }
}
