package com.colonysmp.npc;

import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.util.BlockPos;
import org.bukkit.Location;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** "Is this animal inside the fence?" is what decides whether a keeper goes after it, so it has to be right at the edges. */
class PensTest {

    private Building pen;

    @BeforeEach
    void setUp() {
        // an L-shaped yard: tiles x 7..15, z 13..21, without the corner x 12..15, z 18..21. The fence stands on x = 6 and x = 16.
        pen = new Building(4, BuildingType.ANIMAL_PEN);
        for (int x = 7; x <= 15; x++) {
            for (int z = 13; z <= 21; z++) if (!(x >= 12 && z >= 18)) pen.tiles.add(new BlockPos(x, 63, z));
        }
        pen.min = new BlockPos(7, 63, 13);
        pen.max = new BlockPos(15, 64, 21);
        pen.doors.add(new BlockPos(11, 63, 12));
    }

    private boolean in(double x, double z) {
        return Pens.inside(pen, new Location(null, x, 63, z));
    }

    @Test
    void animalsOnTheYardsGroundAreInside() {
        assertTrue(in(11.5, 17.5));
        assertTrue(in(7.5, 13.5));
    }

    @Test
    void theCutOutCornerIsOutsideEvenThoughTheBoundingBoxCoversIt() {
        assertTrue(pen.inside(14.5, 63, 20.5), "the old test (the bounding box) calls this inside");
        assertFalse(in(14.5, 20.5), "the fence runs round the corner: this is outside");
    }

    @Test
    void aChickenPressedAgainstTheInsideOfTheFenceIsStillInside() {
        // a chicken is 0.4 wide and a fence post 0.125 from the block's middle: its centre can be at 6.76, in the fence's own column
        assertTrue(in(6.76, 17.5));
        assertTrue(in(6.9, 17.5));
        assertTrue(in(11.5, 12.8), "and against the gate");
    }

    @Test
    void anAnimalOutsideTheFenceIsOutsideHoweverCloseItStands() {
        // the nearest an animal outside can get to the fence column at x = 6 is a centre of about 6.24
        assertFalse(in(6.24, 17.5));
        assertFalse(in(5.5, 17.5));
        assertFalse(in(11.5, 12.2), "just outside the gate");
        assertFalse(in(11.5, 11.5));
        assertFalse(in(16.8, 15.5));
    }

    @Test
    void aBigAnimalStandingInAnOpenGatewayCountsAsOut() {
        // the middle of the gate's block: it is on its way out, and the keeper should fetch it
        assertFalse(in(11.5, 12.5));
    }

    @Test
    void theKeeperWaitsWellAwayFromTheGate() {
        for (int i = 0; i < 200; i++) {
            Location l = Pens.interiorSpot(null, pen);
            assertNotNull(l);
            assertTrue(Pens.inside(pen, l));
            assertTrue(Math.abs(l.getX() - 11.5) > 2 || Math.abs(l.getZ() - 12.5) > 2, "not within two blocks of the gate: " + l);
        }
    }
}
