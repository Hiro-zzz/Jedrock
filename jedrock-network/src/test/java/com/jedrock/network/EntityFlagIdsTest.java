package com.jedrock.network;

import com.jedrock.api.entity.PuppetFlag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The canonical → per-edition flag mapping. The two editions disagree on shape — Java uses a bitmask in a
 * flags byte, Bedrock bit positions in a DATA_FLAGS field — so each canonical flag is checked against the
 * real bit on both sides (Java verified via ViaVersion's pinned tables, Bedrock via PocketMine-MP).
 */
class EntityFlagIdsTest {

    @Test
    void javaBitsMatchTheSharedFlagsByte() {
        assertEquals(0x01, EntityFlagIds.javaBits(PuppetFlag.ON_FIRE.bit()), "on fire");
        assertEquals(0x02, EntityFlagIds.javaBits(PuppetFlag.SNEAKING.bit()), "crouched");
        assertEquals(0x20, EntityFlagIds.javaBits(PuppetFlag.INVISIBLE.bit()), "invisible");
        assertEquals(0, EntityFlagIds.javaBits(0), "no flags");
    }

    @Test
    void bedrockBitsMatchDataFlagPositions() {
        assertEquals(1L, EntityFlagIds.bedrockBits(PuppetFlag.ON_FIRE.bit()), "bit 0");
        assertEquals(1L << 1, EntityFlagIds.bedrockBits(PuppetFlag.SNEAKING.bit()), "bit 1");
        assertEquals(1L << 5, EntityFlagIds.bedrockBits(PuppetFlag.INVISIBLE.bit()), "bit 5");
        assertEquals(0L, EntityFlagIds.bedrockBits(0), "no flags");
    }

    @Test
    void flagsCombine() {
        int mask = PuppetFlag.mask(PuppetFlag.ON_FIRE, PuppetFlag.INVISIBLE);
        assertEquals(0x01 | 0x20, EntityFlagIds.javaBits(mask), "java: on fire + invisible");
        assertEquals(1L | (1L << 5), EntityFlagIds.bedrockBits(mask), "bedrock: bits 0 + 5");
    }

    /** 0.14 writes DATA_FLAGS as a single byte, so every mapped bit must fit in one. */
    @Test
    void everyBedrockBitFitsInAByte() {
        for (PuppetFlag flag : PuppetFlag.values()) {
            long bits = EntityFlagIds.bedrockBits(flag.bit());
            assertEquals(bits, bits & 0xFF, flag + " must fit in the 0.14 flags byte");
        }
    }
}
