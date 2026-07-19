package com.jedrock.network;

import com.jedrock.api.entity.EntityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the entity-type "palette" — the canonical → per-edition numeric id mapping. Java uses the classic
 * pre-1.13 SpawnMob ids (1.8 / 1.12.2); Bedrock uses the legacy MCPE ids (1.1.5 / 0.14, Zombie anchored to
 * PMMP {@code 1.7dev-27} NETWORK_ID = 32).
 */
class EntityTypeIdsTest {

    @Test
    void javaIdsAreTheClassicMobIds() {
        assertEquals(54, EntityTypeIds.javaId(EntityType.ZOMBIE));
        assertEquals(90, EntityTypeIds.javaId(EntityType.PIG));
        assertEquals(93, EntityTypeIds.javaId(EntityType.CHICKEN));
        assertEquals(92, EntityTypeIds.javaId(EntityType.COW));
        assertEquals(51, EntityTypeIds.javaId(EntityType.SKELETON));
        assertEquals(50, EntityTypeIds.javaId(EntityType.CREEPER));
    }

    @Test
    void bedrockIdsAreTheLegacyMcpeIds() {
        assertEquals(32, EntityTypeIds.bedrockId(EntityType.ZOMBIE));
        assertEquals(12, EntityTypeIds.bedrockId(EntityType.PIG));
        assertEquals(10, EntityTypeIds.bedrockId(EntityType.CHICKEN));
        assertEquals(11, EntityTypeIds.bedrockId(EntityType.COW));
        assertEquals(34, EntityTypeIds.bedrockId(EntityType.SKELETON));
        assertEquals(33, EntityTypeIds.bedrockId(EntityType.CREEPER));
    }

    @Test
    void everyMobTypeMapsOnBothEditions() {
        for (EntityType type : EntityType.values()) {
            if (type.isPlayer()) {
                continue; // PLAYER renders via the avatar path — it has no mob id
            }
            assertTrue(EntityTypeIds.javaId(type) > 0, type + " has a Java id");
            assertTrue(EntityTypeIds.bedrockId(type) > 0, type + " has a Bedrock id");
        }
    }

    @Test
    void playerTypeHasNoMobId() {
        // PLAYER must never reach the spawn-mob path (the server routes it through the avatar path instead);
        // asking for its mob id is a bug, so it throws rather than returning a bogus number.
        assertThrows(IllegalArgumentException.class, () -> EntityTypeIds.javaId(EntityType.PLAYER));
        assertThrows(IllegalArgumentException.class, () -> EntityTypeIds.bedrockId(EntityType.PLAYER));
    }
}
