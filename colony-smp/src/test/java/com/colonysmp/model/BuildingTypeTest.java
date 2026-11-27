package com.colonysmp.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BuildingTypeTest {

    @Test
    void everyTradeHasSomewhereToWork() {
        for (Job j : Job.values()) {
            if (!j.needsWorkplace()) continue;
            assertFalse(j.workplaces().isEmpty(), j + " has no workplace");
        }
    }

    @Test
    void theNewTradesWorkWhereTheyShould() {
        assertEquals(1, BuildingType.UNIVERSITY.slots(Job.RESEARCHER, 1));
        assertEquals(3, BuildingType.UNIVERSITY.slots(Job.RESEARCHER, 3));
        assertEquals(1, BuildingType.TAVERN.slots(Job.BARD, 1));
        assertEquals(1, BuildingType.DRUID_GROVE.slots(Job.DRUID, 1));
        assertEquals(1, BuildingType.ENCHANTERS_TOWER.slots(Job.ENCHANTER, 2));
        assertEquals(2, BuildingType.APIARY.slots(Job.BEEKEEPER, 3));
        assertFalse(BuildingType.LIBRARY.workplace(), "a library needs no staff");
    }

    @Test
    void levelsOnlyEverAskForMore() {
        for (BuildingType t : BuildingType.values()) {
            for (int lv = 2; lv <= t.maxLevel; lv++) {
                BuildingType.Level lower = t.level(lv - 1), upper = t.level(lv);
                assertTrue(upper.floor() >= lower.floor(), t + " level " + lv + " floor");
                for (Map.Entry<Feature, Integer> e : lower.features().entrySet()) {
                    assertTrue(upper.features().getOrDefault(e.getKey(), 0) >= e.getValue(), t + " level " + lv + " " + e.getKey());
                }
            }
        }
    }

    @Test
    void namesParse() {
        for (BuildingType t : BuildingType.values()) assertSame(t, BuildingType.parse(t.display), t.display);
        assertSame(BuildingType.DRUID_GROVE, BuildingType.parse("grove"));
        assertSame(BuildingType.ENCHANTERS_TOWER, BuildingType.parse("enchanter"));
        for (Job j : Job.values()) assertSame(j, Job.parse(j.display), j.display);
        assertSame(Job.BARD, Job.parse("minstrel"));
    }
}
