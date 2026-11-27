package com.colonysmp.api;

import com.colonysmp.Settings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZoneTest {
    @Test
    void coversChunksTouchingTheCircle() {
        Settings.Zone z = new Settings.Zone("t", "world", -394, 463, 300, "Old Town");
        assertTrue(z.covers("world", -394 >> 4, 463 >> 4), "the centre");
        assertTrue(z.covers("WORLD", (-394 + 299) >> 4, 463 >> 4), "inside, other case of world name");
        assertFalse(z.covers("world", (-394 + 320) >> 4, 463 >> 4), "past the edge");
        assertFalse(z.covers("world_nether", -394 >> 4, 463 >> 4), "another world");
        // the corner chunk of the bounding square is outside a circle
        assertFalse(z.covers("world", (-394 + 290) >> 4, (463 + 290) >> 4));
    }

    @Test
    void apiZonesCanBeAddedMovedAndRemoved() {
        ColonyApi.protect("town", "world", 0, 0, 100, "Old Town");
        assertEquals("Old Town", ColonyApi.zoneAt("world", 0, 0));
        ColonyApi.protect("town", "world", 5000, 5000, 100, "Old Town");
        assertNull(ColonyApi.zoneAt("world", 0, 0));
        assertEquals("Old Town", ColonyApi.zoneAt("world", 5000 >> 4, 5000 >> 4));
        ColonyApi.unprotect("town");
        assertNull(ColonyApi.zoneAt("world", 5000 >> 4, 5000 >> 4));
    }
}
