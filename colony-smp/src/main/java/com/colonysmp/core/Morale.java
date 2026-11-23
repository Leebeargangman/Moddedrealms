package com.colonysmp.core;

/** Battlefield morale (0-100): what raises it, what breaks it, and what a unit does at each level. */
public final class Morale {

    public enum State {
        STEADY("Steady"), SHAKEN("Shaken"), WAVERING("Wavering"), BROKEN("Broken");

        public final String display;

        State(String display) {
            this.display = display;
        }
    }

    public enum Event {
        COMMANDER_DIED(-30), ALLY_KILLED_NEARBY(-6), SURROUNDED(-4), STRUCK_FROM_BEHIND(-3), HEAVY_LOSSES(-20),
        FORMATION_BROKEN(-10), ENEMY_KILLED(3), REINFORCED(10), VICTORY(15), DEFEAT(-15);

        public final double delta;

        Event(double delta) {
            this.delta = delta;
        }
    }

    private Morale() {}

    public static State state(double morale) {
        if (morale >= 40) return State.STEADY;
        if (morale >= 20) return State.SHAKEN;
        if (morale >= 10) return State.WAVERING;
        return State.BROKEN;
    }

    /** Morale a soldier settles back to when nothing happens: training, class, food and leadership. */
    public static double baseline(MilitaryClass c, double training, double mood, boolean fed) {
        double b = 55 + (c == null ? 0 : c.morale) + training * 15 + (mood - 50) / 5;
        if (!fed) b -= 15;
        return Math.max(5, Math.min(95, b));
    }

    /**
     * One second of morale: drift toward the baseline, plus the steadying presence of standard bearers and
     * commanders (auraSum is the sum of their aura strengths within reach).
     */
    public static double tick(double morale, double baseline, double auraSum, boolean inCombat) {
        double target = Math.min(100, baseline + auraSum * 15);
        double rate = inCombat ? 0.3 : 1.5;
        if (morale < target) morale = Math.min(target, morale + rate + auraSum);
        else morale = Math.max(target, morale - rate * 0.5);
        return Math.max(0, Math.min(100, morale));
    }

    public static double apply(double morale, Event e, boolean fearless) {
        double d = e.delta;
        if (fearless && d < 0) d *= 0.5;
        return Math.max(0, Math.min(100, morale + d));
    }
}
