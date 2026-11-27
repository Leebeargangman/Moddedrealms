package com.colonysmp.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The arithmetic of raids from the wilds: how likely one is, how many come, who is among them, and what it
 * costs to pay them off. Bigger, older, more successful colonies draw bigger raids.
 */
public final class RaidPlan {

    /** Who a raider is. Barbarians are brutes, archers and a chief; the undead are led by a necromancer. */
    public enum Role { BRUTE, ARCHER, CHIEF, ZOMBIE, SKELETON, HUSK, NECROMANCER }

    private RaidPlan() {}

    /**
     * The chance that raiders set out for a colony today.
     *
     * @param daysSince days since the last raid (or since the colony became worth raiding)
     * @param minDays   days of peace after a raid
     * @param base      chance on the first day a raid is possible; it grows each quiet day after
     */
    public static double chance(long daysSince, int minDays, double base) {
        if (base <= 0 || daysSince < minDays) return 0;
        return Math.min(0.9, base + 0.08 * (daysSince - minDays));
    }

    /**
     * How many raiders come.
     *
     * @param population free citizens
     * @param level      settlement level (1-5)
     * @param repelled   raids the colony has already driven off (word gets round: stronger bands try next)
     * @param defence    share the colony's watch takes off (0-1, from research)
     */
    public static int size(int population, int level, int repelled, double defence, int min, int max) {
        double n = 2 + population / 4.0 + (Math.max(1, level) - 1) * 1.5 + Math.min(6, Math.max(0, repelled)) * 0.5;
        n *= Math.max(0.25, 1 - defence);
        int lo = Math.max(1, min), hi = Math.max(lo, max);
        return (int) Math.max(lo, Math.min(hi, Math.round(n)));
    }

    /** Who makes up a raid of a size, leader first. */
    public static List<Role> roster(boolean undead, int size) {
        List<Role> out = new ArrayList<>();
        if (size <= 0) return out;
        if (undead) {
            if (size >= 6) out.add(Role.NECROMANCER);
            Role[] pattern = {Role.ZOMBIE, Role.ZOMBIE, Role.SKELETON, Role.ZOMBIE, Role.HUSK};
            for (int i = 0; out.size() < size; i++) out.add(pattern[i % pattern.length]);
        } else {
            if (size >= 5) out.add(Role.CHIEF);
            Role[] pattern = {Role.BRUTE, Role.ARCHER, Role.BRUTE};
            for (int i = 0; out.size() < size; i++) out.add(pattern[i % pattern.length]);
        }
        return out;
    }

    /** What barbarians take to turn round and go home. */
    public static long tribute(int size, long perRaider) {
        return Math.max(0, size) * Math.max(0, perRaider);
    }

    /** What the colony earns for driving a raid off. */
    public static long bounty(int slain, long perRaider) {
        return Math.max(0, slain) * Math.max(0, perRaider);
    }
}
