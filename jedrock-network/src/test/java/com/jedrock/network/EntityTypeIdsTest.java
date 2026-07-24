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
            if (!type.isMob()) {
                continue; // avatars and props spawn through their own packets, not spawn-mob — see below
            }
            assertTrue(EntityTypeIds.javaId(type) > 0, type + " has a Java id");
            assertTrue(EntityTypeIds.bedrockId(type) > 0, type + " has a Bedrock id");
        }
    }

    @Test
    void nonMobTypesHaveNoJavaMobId() {
        // Neither must ever reach the spawn-mob path: a PLAYER routes through the avatar path and an ITEM
        // through Spawn Object, so asking for a mob id is a bug — it throws rather than return a bogus one.
        assertThrows(IllegalArgumentException.class, () -> EntityTypeIds.javaId(EntityType.PLAYER));
        assertThrows(IllegalArgumentException.class, () -> EntityTypeIds.bedrockId(EntityType.PLAYER));
        assertThrows(IllegalArgumentException.class, () -> EntityTypeIds.javaId(EntityType.ITEM));
    }

    @Test
    void thePropTypesHaveBedrockEntityIdsButJavaObjectTypes() {
        // Bedrock's props are real entity types (the dropped item is the id holograms already hang on,
        // the falling block is PMMP's FallingSand); on Java both are objects, not mobs, so they carry a
        // Spawn Object type instead.
        assertEquals(64, EntityTypeIds.bedrockId(EntityType.ITEM), "MCPE dropped-item entity id");
        assertEquals(66, EntityTypeIds.bedrockId(EntityType.FALLING_BLOCK), "MCPE FallingSand id");
        assertEquals(2, EntityTypeIds.JAVA_OBJECT_ITEM_STACK, "JE Spawn Object type for an item stack");
        assertEquals(70, EntityTypeIds.JAVA_OBJECT_FALLING_BLOCK, "JE Spawn Object type for a falling block");
    }
}
