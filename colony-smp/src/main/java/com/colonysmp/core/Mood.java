package com.colonysmp.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a citizen's mood should be, and why. Each factor is kept separately so players can see what's wrong
 * ("Homeless -15, Unpaid wages -8") instead of a bare number.
 */
public final class Mood {

    /** Everything that moves mood. Fractions are 0-1; levels and counts are plain numbers. */
    public static final class Inputs {
        public double satiety = 70, energy = 70, health = 1, social = 50, mealQuality = 1;
        /** 0 homeless, 1-3 house comfort; overcrowded when more people than beds or too little floor. */
        public int housing = 1;
        public boolean overcrowded;
        public boolean ill;
        /** 0 safe, 1 monsters or raiders nearby, 2 the colony is under siege. */
        public int danger;
        public boolean employed, adult, suitsTrait, dislikesJob, skilledJob, longHours, unpaid;
        public boolean partnerNearby, familyTogether, bereaved;
        /** Colony stability 0-100. */
        public double stability = 60;
        public double taxRate;
        /** Services the citizen can use: restaurant, market, school, clinic, inn. */
        public int services;
        /** Colony-wide event modifier (victories, defeats, festivals), already summed. */
        public double events;
        /** Imprisoned, captive or rioting. */
        public boolean confined;
        /** A bard plays at the colony's tavern. */
        public boolean entertained;
    }

    public record Result(double target, Map<String, Double> factors) {
        /** The worst factor, for one-line summaries. */
        public String worst() {
            String w = null;
            double v = 0;
            for (Map.Entry<String, Double> e : factors.entrySet()) {
                if (e.getValue() < v) {
                    v = e.getValue();
                    w = e.getKey();
                }
            }
            return w;
        }
    }

    private Mood() {}

    public static Result target(Inputs in) {
        Map<String, Double> f = new LinkedHashMap<>();
        put(f, "Food", (Math.min(100, in.satiety) - 50) / 50 * 12);
        put(f, "Meal quality", (in.mealQuality - 1) * 3);
        put(f, "Rest", (in.energy - 50) / 50 * 8);
        if (in.housing <= 0) put(f, "Homeless", -15);
        else put(f, "Housing", in.housing * 2.5);
        if (in.overcrowded) put(f, "Overcrowded", -5);
        put(f, "Health", -(1 - in.health) * 20);
        if (in.ill) put(f, "Illness", -6);
        if (in.danger == 1) put(f, "Danger nearby", -4);
        if (in.danger >= 2) put(f, "Under siege", -12);
        put(f, "Company", (in.social - 50) / 50 * 6);
        if (in.partnerNearby) put(f, "Partner", 3);
        if (in.familyTogether) put(f, "Family together", 3);
        if (in.bereaved) put(f, "Grieving", -10);
        if (in.adult) {
            if (!in.employed) put(f, "No work", -6);
            if (in.employed && in.suitsTrait) put(f, "Likes the work", 5);
            if (in.employed && in.dislikesJob) put(f, "Dislikes the work", -5);
            if (in.skilledJob) put(f, "Skilled work", 3);
            if (in.longHours) put(f, "Long hours", -4);
            if (in.unpaid) put(f, "Unpaid wages", -8);
        }
        put(f, "Colony stability", (in.stability - 50) / 5);
        put(f, "Taxes", -in.taxRate * 25);
        put(f, "Services", Math.min(10, in.services * 2.0));
        if (in.entertained) put(f, "Music at the tavern", 4);
        put(f, "Recent events", in.events);
        if (in.confined) put(f, "Confined", -20);
        double t = 50;
        for (double v : f.values()) t += v;
        return new Result(Math.max(0, Math.min(100, t)), f);
    }

    private static void put(Map<String, Double> f, String k, double v) {
        if (Math.abs(v) >= 0.05) f.put(k, v);
    }

    public static String describe(double mood) {
        if (mood >= 80) return "joyful";
        if (mood >= 60) return "content";
        if (mood >= 40) return "so-so";
        if (mood >= 20) return "unhappy";
        return "miserable";
    }
}
