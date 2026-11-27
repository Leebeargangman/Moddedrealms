package com.colonysmp.core;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ResearchTest {

    @Test
    void needsAUniversity() {
        assertNotNull(Research.CROP_ROTATION.blocked(Set.of(), null, 0));
        assertNull(Research.CROP_ROTATION.blocked(Set.of(), null, 1));
    }

    @Test
    void laterProjectsNeedEarlierOnesAndABiggerUniversity() {
        assertNotNull(Research.IRRIGATION.blocked(Set.of(), null, 3), "needs crop rotation first");
        Set<Research> done = EnumSet.of(Research.CROP_ROTATION);
        assertNotNull(Research.IRRIGATION.blocked(done, null, 1), "tier 2 needs a level 2 university");
        assertNull(Research.IRRIGATION.blocked(done, null, 2));
    }

    @Test
    void oneProjectAtATimeAndNeverTwice() {
        assertNotNull(Research.DRILL.blocked(Set.of(), Research.PEDAGOGY, 3), "the researchers are busy");
        assertNotNull(Research.DRILL.blocked(Set.of(), Research.DRILL, 3), "already under way");
        assertNotNull(Research.DRILL.blocked(EnumSet.of(Research.DRILL), null, 3), "already known");
    }

    @Test
    void effectsAddUp() {
        assertEquals(0, Research.total(Set.of(), Research.Effect.STORAGE));
        Set<Research> done = EnumSet.of(Research.STOREHOUSES, Research.BOOKKEEPING, Research.HARDY_FOLK);
        assertEquals(0.25, Research.total(done, Research.Effect.STORAGE), 1e-9);
        assertEquals(0.15, Research.total(done, Research.Effect.UPKEEP), 1e-9);
        assertEquals(4, Research.total(done, Research.Effect.VITALITY), 1e-9);
        assertEquals(0, Research.total(done, Research.Effect.DAMAGE), 1e-9);
    }

    @Test
    void everyProjectCanBeReached() {
        // working through whatever is available, at the biggest university, learns everything
        Set<Research> done = new HashSet<>();
        for (int round = 0; round < Research.values().length; round++) {
            List<Research> open = Research.available(done, 3);
            if (open.isEmpty()) break;
            done.add(open.get(0));
        }
        assertEquals(Research.values().length, done.size());
    }

    @Test
    void theTableIsSound() {
        TestGoods.setUp();
        for (Research r : Research.values()) {
            assertTrue(r.tier >= 1 && r.tier <= 3, r + " tier");
            assertTrue(r.hours > 0 && r.cost > 0 && r.value > 0, r + " numbers");
            assertFalse(r.goods().isEmpty(), r + " costs goods");
            Research req = r.requires();
            if (req != null) {
                assertNotSame(r, req);
                assertTrue(req.tier <= r.tier, r + " can't need a later project");
            }
            assertSame(r, Research.parse(r.display), "parses by its display name");
            assertSame(r, Research.parse(r.name().toLowerCase()), "parses by its id");
        }
        assertNull(Research.parse("alchemy"));
    }
}
