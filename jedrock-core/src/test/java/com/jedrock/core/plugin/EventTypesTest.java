package com.jedrock.core.plugin;

import com.jedrock.api.event.Event;
import com.jedrock.api.event.player.ContainerCloseEvent;
import com.jedrock.api.event.player.ContainerOpenEvent;
import com.jedrock.api.event.player.PlayerJoinEvent;
import com.jedrock.api.event.player.PlayerSwingArmEvent;
import com.jedrock.api.event.player.PuppetInteractEvent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The script-visible event vocabulary. This table is the only thing standing between a script writing
 * {@code events.on('PlayerSwingArm', …)} and it silently becoming a <em>custom</em> event that nothing
 * ever fires — the failure mode of a typo here is a listener that never runs and never complains, which
 * is why the whole list is walked rather than spot-checked.
 */
class EventTypesTest {

    @Test
    void everyDeclaredNameResolves() {
        List<String> names = EventTypes.names();
        assertNotNull(names);
        for (String name : names) {
            assertNotNull(EventTypes.byName(name), name + " is listed but does not resolve");
        }
    }

    @Test
    void aNameMatchesLooselyEnoughToBeGuessable() {
        Class<? extends Event> expected = PlayerJoinEvent.class;
        assertSame(expected, EventTypes.byName("PlayerJoin"));
        assertSame(expected, EventTypes.byName("playerjoin"));
        assertSame(expected, EventTypes.byName("PlayerJoinEvent"));
        assertSame(expected, EventTypes.byName("PLAYERJOINEVENT"));
    }

    @Test
    void anUnknownNameIsNotAnEvent() {
        assertNull(EventTypes.byName("shop:buy"), "so it becomes a custom event, which is the point");
        assertNull(EventTypes.byName(null));
    }

    @Test
    void theNewOnesAreReachableByName() {
        assertSame(PlayerSwingArmEvent.class, EventTypes.byName("PlayerSwingArm"));
        assertSame(ContainerOpenEvent.class, EventTypes.byName("ContainerOpen"));
        assertSame(ContainerCloseEvent.class, EventTypes.byName("ContainerClose"));
        assertSame(PuppetInteractEvent.class, EventTypes.byName("PuppetInteract"));
    }

    @Test
    void theListedNamesAreTheDeclaredOnes() {
        assertEquals(EventTypes.names().size(), EventTypes.names().stream().distinct().count(),
                "a duplicate name would quietly shadow whichever was registered first");
    }
}
