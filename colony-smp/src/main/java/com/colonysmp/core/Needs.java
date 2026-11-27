package com.colonysmp.core;

/**
 * A citizen's needs, all 0-100 except health (hit points). The rates come from config ({@link Params}); this
 * class only holds the numbers and the arithmetic, so the same rules run for walking citizens and for
 * citizens simulated in the background.
 */
public final class Needs {

    /** Rates per in-game hour. */
    public record Params(double hungerAwake, double hungerAsleep, double eatBelow, double eatUntil, double satietyPerPoint,
                         double energyAwake, double energyBed, double energyNoBed, double tired, double exhausted,
                         double starveDamage, double healFed, double healBed, double socialDecay, double socialGain,
                         double heavyWorkFactor) {

        public static Params defaults() {
            return new Params(3.5, 1.5, 55, 92, 6, 5, 12, 6, 30, 12, 1.5, 0.4, 1.5, 2, 8, 1.3);
        }
    }

    /** How full they are. Below {@code eatBelow} they want food; at 0 they starve. */
    public double satiety = 80;
    /** Rested: drops while awake, refilled by sleep. */
    public double energy = 90;
    public double health = 20;
    public double maxHealth = 20;
    /** Mood follows a target computed from everything else. */
    public double mood = 60;
    /** Company: drops when alone, rises with family and friends around. */
    public double social = 60;
    /** Quality (0-3) of the last few meals, for mood. */
    public double mealQuality = 1;

    public boolean hungry(Params p) {
        return satiety < p.eatBelow;
    }

    public boolean starving() {
        return satiety <= 5;
    }

    public boolean tired(Params p) {
        return energy < p.tired;
    }

    public boolean exhausted(Params p) {
        return energy < p.exhausted;
    }

    public double healthFraction() {
        return maxHealth <= 0 ? 0 : Math.max(0, Math.min(1, health / maxHealth));
    }

    /**
     * Advances hunger and tiredness.
     *
     * @param asleep sleeping (and whether in a real bed)
     * @param heavy  doing heavy work (mining, building, fighting)
     */
    public void decay(double hours, boolean asleep, boolean inBed, boolean heavy, Params p) {
        if (hours <= 0) return;
        double hunger = (asleep ? p.hungerAsleep : p.hungerAwake) * (heavy ? p.heavyWorkFactor : 1);
        satiety = clamp(satiety - hunger * hours);
        if (asleep) energy = clamp(energy + (inBed ? p.energyBed : p.energyNoBed) * hours);
        else energy = clamp(energy - p.energyAwake * (heavy ? p.heavyWorkFactor : 1) * hours);
    }

    /** Eating something worth this many hunger points. Returns how much satiety it gave. */
    public double eat(double points, int quality, Params p) {
        double before = satiety;
        satiety = clamp(satiety + points * p.satietyPerPoint);
        mealQuality = mealQuality * 0.7 + quality * 0.3;
        return satiety - before;
    }

    /** How many hunger points it takes to eat until full. */
    public double appetite(Params p) {
        return Math.max(0, (p.eatUntil - satiety) / p.satietyPerPoint);
    }

    /** Natural healing when fed, damage when starving. Returns the change in health. */
    public double heal(double hours, boolean inBed, double extraHealPerHour, Params p) {
        double before = health;
        if (satiety <= 0) health -= p.starveDamage * hours;
        else if (satiety > 20) health += ((inBed ? p.healBed : p.healFed) + extraHealPerHour) * hours;
        health = Math.max(0, Math.min(maxHealth, health));
        return health - before;
    }

    public void socialize(double hours, double company, Params p) {
        if (company > 0) social = clamp(social + p.socialGain * company * hours);
        else social = clamp(social - p.socialDecay * hours);
    }

    /** Mood moves toward its target by up to 6 points an hour. */
    public void driftMood(double target, double hours) {
        double step = 6 * hours;
        if (Math.abs(target - mood) <= step) mood = target;
        else mood += Math.signum(target - mood) * step;
        mood = clamp(mood);
    }

    /**
     * Work speed from how they're doing: mood (0.75-1.2), rest, hunger and injury. Skill, education and
     * equipment are applied by the caller.
     */
    public double productivity(Params p) {
        double f = 0.75 + Math.min(1, mood / 100) * 0.45;
        if (energy < p.tired) f *= 0.7 + 0.3 * energy / Math.max(1, p.tired);
        if (satiety < 20) f *= 0.8;
        double hp = healthFraction();
        if (hp < 0.5) f *= 0.5 + hp;
        return Math.max(0.05, f);
    }

    static double clamp(double v) {
        return Math.max(0, Math.min(100, v));
    }
}
