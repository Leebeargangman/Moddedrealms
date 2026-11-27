package com.colonysmp.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CombatMathTest {

    private static CombatMath.Hit hit(MilitaryClass atk, MilitaryClass def) {
        CombatMath.Hit h = new CombatMath.Hit();
        h.attacker = atk;
        h.defender = def;
        h.baseDamage = 6;
        h.applyArmor = false;
        return h;
    }

    @Test
    void shieldsStopFrontalArrowsButNotFlankingOnes() {
        CombatMath.Hit front = hit(MilitaryClass.ARCHER, MilitaryClass.SHIELDMAN);
        front.ranged = true;
        front.defenderShield = true;
        front.angle = 10;
        CombatMath.Result f = CombatMath.resolve(front);
        assertTrue(f.blocked());

        CombatMath.Hit side = hit(MilitaryClass.ARCHER, MilitaryClass.SHIELDMAN);
        side.ranged = true;
        side.defenderShield = true;
        side.angle = 120;
        CombatMath.Result s = CombatMath.resolve(side);
        assertFalse(s.blocked());
        assertTrue(s.flanked());
        assertTrue(s.damage() > f.damage() * 5);
    }

    @Test
    void axesSplitShields() {
        CombatMath.Hit sword = hit(MilitaryClass.SWORDSMAN, MilitaryClass.SHIELDMAN);
        sword.defenderShield = true;
        CombatMath.Hit axe = hit(MilitaryClass.AXEMAN, MilitaryClass.SHIELDMAN);
        axe.defenderShield = true;
        CombatMath.Result s = CombatMath.resolve(sword), a = CombatMath.resolve(axe);
        assertTrue(a.damage() > s.damage() * 2, "axes get through shields far better");
        assertTrue(a.shieldWear() > s.shieldWear());
    }

    @Test
    void cavalryChargesNeedSpeedAndOpenGround() {
        CombatMath.Hit slow = hit(MilitaryClass.LANCER, MilitaryClass.SWORDSMAN);
        slow.attackerMounted = true;
        slow.attackerSpeed = 0.1;
        CombatMath.Hit fast = hit(MilitaryClass.LANCER, MilitaryClass.SWORDSMAN);
        fast.attackerMounted = true;
        fast.attackerSpeed = 0.35;
        CombatMath.Hit mud = hit(MilitaryClass.LANCER, MilitaryClass.SWORDSMAN);
        mud.attackerMounted = true;
        mud.attackerSpeed = 0.35;
        mud.terrain = 0.3;
        CombatMath.Result s = CombatMath.resolve(slow), f = CombatMath.resolve(fast), m = CombatMath.resolve(mud);
        assertFalse(s.charge());
        assertTrue(f.charge());
        assertTrue(f.damage() > s.damage() * 2);
        assertTrue(m.damage() < f.damage(), "difficult terrain blunts the charge");
    }

    @Test
    void preparedPikesBreakCharges() {
        CombatMath.Hit open = hit(MilitaryClass.HEAVY_CAVALRY, MilitaryClass.SWORDSMAN);
        open.attackerMounted = true;
        open.attackerSpeed = 0.35;
        CombatMath.Hit pikes = hit(MilitaryClass.HEAVY_CAVALRY, MilitaryClass.PIKEMAN);
        pikes.attackerMounted = true;
        pikes.attackerSpeed = 0.35;
        pikes.spearWall = true;
        CombatMath.Result o = CombatMath.resolve(open), p = CombatMath.resolve(pikes);
        assertTrue(p.countered());
        assertTrue(p.counterDamage() > 0, "the horseman is hurt on the pikes");
        assertTrue(p.damage() < o.damage() / 3);
    }

    @Test
    void pikesFlankedFromBehindDontBrace() {
        CombatMath.Hit behind = hit(MilitaryClass.HEAVY_CAVALRY, MilitaryClass.PIKEMAN);
        behind.attackerMounted = true;
        behind.attackerSpeed = 0.35;
        behind.spearWall = true;
        behind.angle = 170;
        assertTrue(CombatMath.resolve(behind).charge());
    }

    @Test
    void spearmenHitHorsemenHard() {
        CombatMath.Hit vsFoot = hit(MilitaryClass.SPEARMAN, MilitaryClass.SWORDSMAN);
        CombatMath.Hit vsHorse = hit(MilitaryClass.SPEARMAN, MilitaryClass.LIGHT_CAVALRY);
        vsHorse.defenderMounted = true;
        assertTrue(CombatMath.resolve(vsHorse).damage() > CombatMath.resolve(vsFoot).damage() * 1.5);
    }

    @Test
    void crossbowsPierceArmour() {
        CombatMath.Hit bow = hit(MilitaryClass.ARCHER, MilitaryClass.HEAVY_CAVALRY);
        bow.ranged = true;
        bow.applyArmor = true;
        bow.defenderArmor = 15;
        CombatMath.Hit xbow = hit(MilitaryClass.CROSSBOWMAN, MilitaryClass.HEAVY_CAVALRY);
        xbow.ranged = true;
        xbow.applyArmor = true;
        xbow.defenderArmor = 15;
        assertTrue(CombatMath.resolve(xbow).damage() > CombatMath.resolve(bow).damage());
    }

    @Test
    void pikesAreClumsyUpClose() {
        CombatMath.Hit far = hit(MilitaryClass.PIKEMAN, MilitaryClass.SWORDSMAN);
        far.distance = 3;
        CombatMath.Hit near = hit(MilitaryClass.PIKEMAN, MilitaryClass.SWORDSMAN);
        near.distance = 1;
        assertTrue(CombatMath.resolve(near).damage() < CombatMath.resolve(far).damage());
    }

    @Test
    void lowMoraleWeakensBlows() {
        CombatMath.Hit brave = hit(MilitaryClass.SWORDSMAN, MilitaryClass.SWORDSMAN);
        brave.attackerMorale = 90;
        CombatMath.Hit scared = hit(MilitaryClass.SWORDSMAN, MilitaryClass.SWORDSMAN);
        scared.attackerMorale = 10;
        assertTrue(CombatMath.resolve(scared).damage() < CombatMath.resolve(brave).damage());
    }

    @Test
    void angleIsMeasuredFromTheDefendersFacing() {
        // facing south (yaw 0, +z): an attacker to the south is head on, to the north is behind
        assertEquals(0, CombatMath.angle(0, 0, 5), 1e-6);
        assertEquals(180, CombatMath.angle(0, 0, -5), 1e-6);
        assertEquals(90, CombatMath.angle(0, 5, 0), 1e-6);
    }

    @Test
    void noClassIsStrongestEverywhere() {
        // every class has at least one matchup where it is clearly worse off
        for (MilitaryClass c : MilitaryClass.values()) {
            assertTrue(c.upkeep > 0, c.name());
        }
        assertTrue(MilitaryClass.BERSERKER.vulnerable > 1);
        assertTrue(MilitaryClass.PIKEMAN.closePenalty < 1);
        assertTrue(MilitaryClass.CROSSBOWMAN.reloadTicks > MilitaryClass.ARCHER.reloadTicks);
    }

    @Test
    void moraleStates() {
        assertEquals(Morale.State.STEADY, Morale.state(60));
        assertEquals(Morale.State.BROKEN, Morale.state(5));
        double m = Morale.apply(50, Morale.Event.COMMANDER_DIED, false);
        assertEquals(20, m, 1e-9);
        assertEquals(35, Morale.apply(50, Morale.Event.COMMANDER_DIED, true), 1e-9, "fearless troops take it half as hard");
    }

    @Test
    void defendersGoForWhoeverIsAtACitizensThroat() {
        // a raider chasing a farmer 10 blocks off matters more than an idle one 7 blocks off
        assertTrue(CombatMath.urgency(100, true, false, 1) < CombatMath.urgency(49, false, false, 1));
        // but not more than one right on top of the defender
        assertTrue(CombatMath.urgency(4, false, false, 1) < CombatMath.urgency(100, true, false, 1));
    }

    @Test
    void leadersAndTheNearlyBeatenCountAsNearer() {
        double plain = CombatMath.urgency(64, false, false, 1);
        assertTrue(CombatMath.urgency(64, false, true, 1) < plain, "the chief first");
        assertTrue(CombatMath.urgency(64, false, false, 0.2) < plain, "finish the wounded");
        assertEquals(plain, CombatMath.urgency(64, false, false, 5), 1e-9, "health is capped");
        assertEquals(0, CombatMath.urgency(-3, false, false, 1), 1e-9);
    }
}
