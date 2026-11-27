package com.colonysmp.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RaidPlanTest {

    @Test
    void noRaidsDuringThePeaceAfterOne() {
        assertEquals(0, RaidPlan.chance(0, 4, 0.25));
        assertEquals(0, RaidPlan.chance(3, 4, 0.25));
        assertEquals(0.25, RaidPlan.chance(4, 4, 0.25), 1e-9);
    }

    @Test
    void theLongerTheQuietTheLikelierTheRaid() {
        assertTrue(RaidPlan.chance(8, 4, 0.25) > RaidPlan.chance(5, 4, 0.25));
        assertEquals(0.9, RaidPlan.chance(1000, 4, 0.25), 1e-9, "never a certainty");
        assertEquals(0, RaidPlan.chance(1000, 4, 0), "turned off");
    }

    @Test
    void biggerColoniesDrawBiggerRaids() {
        int small = RaidPlan.size(6, 1, 0, 0, 3, 24);
        int big = RaidPlan.size(40, 4, 3, 0, 3, 24);
        assertTrue(big > small);
        assertEquals(4, small, "2 + 6/4, rounded");
        assertEquals(3, RaidPlan.size(1, 1, 0, 0, 3, 24), "never under the minimum");
        assertEquals(24, RaidPlan.size(500, 5, 10, 0, 3, 24), "never over the maximum");
    }

    @Test
    void aWatchSystemThinsTheRaid() {
        int open = RaidPlan.size(40, 3, 0, 0, 3, 60);
        int watched = RaidPlan.size(40, 3, 0, 0.25, 3, 60);
        assertEquals(Math.round(open * 0.75), watched, 1);
    }

    @Test
    void barbariansBringAChiefOnceThereAreEnough() {
        List<RaidPlan.Role> few = RaidPlan.roster(false, 4);
        assertEquals(4, few.size());
        assertFalse(few.contains(RaidPlan.Role.CHIEF));
        List<RaidPlan.Role> many = RaidPlan.roster(false, 9);
        assertEquals(9, many.size());
        assertEquals(RaidPlan.Role.CHIEF, many.get(0));
        assertEquals(1, many.stream().filter(r -> r == RaidPlan.Role.CHIEF).count());
        assertTrue(many.contains(RaidPlan.Role.ARCHER) && many.contains(RaidPlan.Role.BRUTE));
        assertFalse(many.contains(RaidPlan.Role.ZOMBIE));
    }

    @Test
    void theDeadFollowANecromancer() {
        List<RaidPlan.Role> horde = RaidPlan.roster(true, 10);
        assertEquals(10, horde.size());
        assertEquals(RaidPlan.Role.NECROMANCER, horde.get(0));
        assertFalse(horde.contains(RaidPlan.Role.BRUTE));
        assertFalse(RaidPlan.roster(true, 4).contains(RaidPlan.Role.NECROMANCER), "a handful of dead have no master");
        assertTrue(RaidPlan.roster(true, 0).isEmpty());
    }

    @Test
    void tributeAndBounty() {
        assertEquals(96, RaidPlan.tribute(8, 12));
        assertEquals(0, RaidPlan.tribute(8, 0));
        assertEquals(30, RaidPlan.bounty(5, 6));
        assertEquals(0, RaidPlan.bounty(-1, 6));
    }
}
