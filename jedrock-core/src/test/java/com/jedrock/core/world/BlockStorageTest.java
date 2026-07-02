package com.jedrock.core.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlockStorageTest {

    @Test
    void everythingIsAirByDefault() {
        BlockStorage storage = new BlockStorage();
        assertEquals(BlockStorage.AIR, storage.getId(0, 64, 0));
        assertEquals(BlockStorage.AIR, storage.getId(1000, 200, -1000));
        assertEquals(0, storage.loadedSections());
    }

    @Test
    void storesAndReadsBackIds() {
        BlockStorage storage = new BlockStorage();
        storage.setId(10, 64, 20, 42);
        assertEquals(42, storage.getId(10, 64, 20));
        // Neighbours stay air.
        assertEquals(BlockStorage.AIR, storage.getId(11, 64, 20));
    }

    @Test
    void indexingIsCorrectAcrossChunkAndSectionBoundaries() {
        BlockStorage storage = new BlockStorage();
        // Two blocks that share the same in-section index but different chunk/section.
        storage.setId(15, 15, 15, 1);
        storage.setId(16, 16, 16, 2); // next chunk (x,z) and next section (y)
        assertEquals(1, storage.getId(15, 15, 15));
        assertEquals(2, storage.getId(16, 16, 16));
    }

    @Test
    void handlesNegativeCoordinates() {
        BlockStorage storage = new BlockStorage();
        storage.setId(-1, 64, -1, 7);
        assertEquals(7, storage.getId(-1, 64, -1));
        assertEquals(BlockStorage.AIR, storage.getId(-2, 64, -2));
    }

    @Test
    void outOfHeightRangeIsIgnored() {
        BlockStorage storage = new BlockStorage();
        storage.setId(0, -5, 0, 9);
        storage.setId(0, 300, 0, 9);
        assertEquals(BlockStorage.AIR, storage.getId(0, -5, 0));
        assertEquals(BlockStorage.AIR, storage.getId(0, 300, 0));
        assertEquals(0, storage.loadedSections());
    }

    @Test
    void airWritesNeverAllocate() {
        BlockStorage storage = new BlockStorage();
        storage.setId(5, 70, 5, BlockStorage.AIR);
        assertEquals(0, storage.loadedSections());

        storage.setId(5, 70, 5, 3);
        assertEquals(1, storage.loadedSections());
        // Clearing back to air keeps the (now-used) section but reads as air.
        storage.setId(5, 70, 5, BlockStorage.AIR);
        assertEquals(BlockStorage.AIR, storage.getId(5, 70, 5));
    }
}
