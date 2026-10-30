package net.moddedrealms.testplayers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Ready-made personalities for test players with an AI model. Anything else is used as written. */
final class Personalities {

    static final Map<String, String> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("builder", "A proud, practical builder. Always planning the next build, talks about materials and good spots, likes to show off finished work.");
        PRESETS.put("explorer", "A restless explorer. Always wants to see what's over the next hill, gets excited about new places and tells everyone about them.");
        PRESETS.put("leader", "A bossy natural leader. Organises everyone, hands out jobs, wants the group to build a proper village together.");
        PRESETS.put("joker", "The class clown. Makes jokes and terrible puns, teases friends, never takes anything seriously.");
        PRESETS.put("nervous", "Nervous and cautious. Worries about mobs, the dark and heights, sticks close to friends and asks lots of questions.");
        PRESETS.put("grumpy", "A grumpy old-timer. Complains about everything in short sentences, but secretly cares about friends and helps them.");
        PRESETS.put("friendly", "Warm and chatty. Greets everyone by name, asks how they're doing, loves company and compliments other people's builds.");
    }

    private Personalities() { }

    /** A preset name, "random", or the player's own words (trimmed to 300 characters). */
    static String resolve(String input) {
        String t = input.replaceAll("\\s+", " ").trim();
        String key = t.toLowerCase(Locale.ROOT);
        if (key.equals("random")) {
            List<String> all = new ArrayList<>(PRESETS.values());
            return all.get(ThreadLocalRandom.current().nextInt(all.size()));
        }
        if (PRESETS.containsKey(key)) return PRESETS.get(key);
        if (t.isEmpty()) throw new IllegalArgumentException("Describe the personality, or use a preset: " + String.join(", ", PRESETS.keySet()) + ", random.");
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    /** Short label for lists: the preset's name, or the start of a custom personality. */
    static String label(String personality) {
        if (personality == null) return null;
        for (Map.Entry<String, String> e : PRESETS.entrySet()) if (e.getValue().equals(personality)) return e.getKey();
        return personality.length() > 40 ? personality.substring(0, 40) + "..." : personality;
    }
}
