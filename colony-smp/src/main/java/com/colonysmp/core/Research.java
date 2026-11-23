package com.colonysmp.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a colony's researchers can work out at the University. Each project costs money and goods to begin,
 * takes researchers' hours to finish, and then changes how the colony works for good. Some need an earlier
 * project first, and the later ones need a bigger University.
 */
public enum Research {
    // farming
    CROP_ROTATION("Crop Rotation", Branch.FARMING, 1, 10, 60, null,
            "Farmers, animal keepers, fishers, beekeepers and druids work 15% faster.", Effect.WORK_FOOD, 0.15),
    IRRIGATION("Irrigation", Branch.FARMING, 2, 18, 140, "CROP_ROTATION",
            "Fields yield 20% more, and grow 20% faster while nobody is near.", Effect.HARVEST, 0.20),
    // industry
    IMPROVED_TOOLS("Improved Tools", Branch.INDUSTRY, 1, 10, 60, null,
            "Miners, foresters and builders work 15% faster.", Effect.WORK_INDUSTRY, 0.15),
    GUILDS("Guilds", Branch.INDUSTRY, 2, 18, 140, "IMPROVED_TOOLS",
            "Everyone who works a recipe (bakers, smiths, masons...) works 15% faster.", Effect.WORK_CRAFT, 0.15),
    STOREHOUSES("Storehouses", Branch.INDUSTRY, 2, 16, 120, "IMPROVED_TOOLS",
            "Colony storage and granaries hold 25% more.", Effect.STORAGE, 0.25),
    BOOKKEEPING("Bookkeeping", Branch.INDUSTRY, 1, 12, 80, null,
            "Building upkeep costs 15% less.", Effect.UPKEEP, 0.15),
    // society
    HERBAL_MEDICINE("Herbal Medicine", Branch.SOCIETY, 1, 12, 80, null,
            "Wounds heal faster and illness is 40% rarer.", Effect.HEALING, 0.40),
    HARDY_FOLK("Hardy Folk", Branch.SOCIETY, 2, 20, 160, "HERBAL_MEDICINE",
            "Every citizen has 4 more health.", Effect.VITALITY, 4),
    PEDAGOGY("Pedagogy", Branch.SOCIETY, 1, 12, 80, null,
            "Schools and libraries teach 30% faster.", Effect.LEARNING, 0.30),
    ARTS("Arts and Festivals", Branch.SOCIETY, 2, 18, 140, "PEDAGOGY",
            "Everyone is a little happier (+3 mood), and festivals come more often.", Effect.MOOD, 3),
    HOSPITALITY("Hospitality", Branch.SOCIETY, 1, 10, 60, null,
            "Travellers and merchant caravans visit half again as often.", Effect.VISITORS, 0.50),
    // knowledge
    SCHOLARSHIP("Scholarship", Branch.KNOWLEDGE, 2, 16, 120, "PEDAGOGY",
            "Researchers work 25% faster.", Effect.RESEARCH, 0.25),
    ARCANE_STUDIES("Arcane Studies", Branch.KNOWLEDGE, 3, 26, 240, "SCHOLARSHIP",
            "Enchanters lay stronger enchantments (up to level 3).", Effect.ENCHANTING, 1),
    // defence
    DRILL("Drill", Branch.DEFENCE, 1, 12, 80, null,
            "Soldiers train 50% faster.", Effect.TRAINING, 0.50),
    WEAPONCRAFT("Weaponcraft", Branch.DEFENCE, 2, 20, 160, "DRILL",
            "Guards and soldiers hit and shoot 20% harder.", Effect.DAMAGE, 0.20),
    WATCH_SYSTEM("Watch System", Branch.DEFENCE, 2, 18, 140, "DRILL",
            "Raiders are seen coming: raids are a quarter smaller.", Effect.RAID_DEFENCE, 0.25);

    public enum Branch {
        FARMING("Farming"), INDUSTRY("Industry"), SOCIETY("Society"), KNOWLEDGE("Knowledge"), DEFENCE("Defence");

        public final String display;

        Branch(String display) {
            this.display = display;
        }
    }

    /** What a finished project changes. Values of the same effect add up. */
    public enum Effect {
        WORK_FOOD, HARVEST, WORK_INDUSTRY, WORK_CRAFT, STORAGE, UPKEEP, HEALING, VITALITY, LEARNING, MOOD, VISITORS,
        RESEARCH, ENCHANTING, TRAINING, DAMAGE, RAID_DEFENCE
    }

    public final String display;
    public final Branch branch;
    /** University level needed. */
    public final int tier;
    /** Researcher hours at normal speed. */
    public final double hours;
    /** Money to begin. */
    public final long cost;
    private final String requires;
    public final String description;
    public final Effect effect;
    public final double value;
    private final Map<String, Integer> goods = new LinkedHashMap<>();

    Research(String display, Branch branch, int tier, double hours, long cost, String requires, String description, Effect effect, double value) {
        this.display = display;
        this.branch = branch;
        this.tier = tier;
        this.hours = hours;
        this.cost = cost;
        this.requires = requires;
        this.description = description;
        this.effect = effect;
        this.value = value;
    }

    static {
        CROP_ROTATION.needs("wheat", 32, "paper", 4);
        IRRIGATION.needs("bucket", 2, "book", 2);
        IMPROVED_TOOLS.needs("iron_ingot", 6, "paper", 4);
        GUILDS.needs("iron_ingot", 8, "book", 2);
        STOREHOUSES.needs("chest", 4, "book", 2);
        BOOKKEEPING.needs("paper", 12);
        HERBAL_MEDICINE.needs("sweet_berries", 16, "paper", 4);
        HARDY_FOLK.needs("bread", 32, "book", 2);
        PEDAGOGY.needs("book", 3);
        ARTS.needs("book", 3, "paper", 8);
        HOSPITALITY.needs("bread", 16, "paper", 4);
        SCHOLARSHIP.needs("book", 6);
        ARCANE_STUDIES.needs("book", 8, "lapis_lazuli", 16);
        DRILL.needs("iron_ingot", 4, "paper", 4);
        WEAPONCRAFT.needs("iron_ingot", 12, "book", 2);
        WATCH_SYSTEM.needs("torch", 32, "book", 2);
    }

    private void needs(Object... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) goods.put((String) pairs[i], (Integer) pairs[i + 1]);
    }

    /** Goods handed over to begin (taken from colony storage). */
    public Map<String, Integer> goods() {
        return Collections.unmodifiableMap(goods);
    }

    /** The project that must be finished first, or null. */
    public Research requires() {
        return requires == null ? null : valueOf(requires);
    }

    /**
     * Why a colony can't begin this project, or null if it can. Money and goods are checked by the caller, which
     * knows the treasury and the stores.
     *
     * @param done            projects already finished
     * @param current         the project under way, or null
     * @param universityLevel the best working University's level (0 = none)
     */
    public String blocked(Collection<Research> done, Research current, int universityLevel) {
        if (done.contains(this)) return display + " is already known.";
        if (current == this) return display + " is already being researched.";
        if (current != null) return "The researchers are busy with " + current.display + ".";
        if (universityLevel <= 0) return "Research needs a University.";
        Research req = requires();
        if (req != null && !done.contains(req)) return "Needs " + req.display + " first.";
        if (universityLevel < tier) return "Needs a level " + tier + " University.";
        return null;
    }

    /** The summed value of an effect over the finished projects. */
    public static double total(Collection<Research> done, Effect effect) {
        double v = 0;
        for (Research r : done) if (r.effect == effect) v += r.value;
        return v;
    }

    /** Projects that could be begun now (nothing under way, requirements met). */
    public static List<Research> available(Collection<Research> done, int universityLevel) {
        List<Research> out = new ArrayList<>();
        for (Research r : values()) if (r.blocked(done, null, universityLevel) == null) out.add(r);
        return out;
    }

    public static Research parse(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        try {
            return valueOf(u);
        } catch (IllegalArgumentException e) {
            for (Research r : values()) if (r.display.equalsIgnoreCase(s.trim())) return r;
            return null;
        }
    }
}
