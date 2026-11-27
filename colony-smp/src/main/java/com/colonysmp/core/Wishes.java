package com.colonysmp.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What citizens ask the colony's players for. Everyone has small wants (a pie, a blanket, flowers for the
 * table); people with a trade also ask for the things their work runs short of.
 */
public final class Wishes {

    /**
     * @param good   the good they want (a vanilla item or a colony good)
     * @param min    fewest they'd ask for
     * @param max    most they'd ask for
     * @param reason why, in their words
     */
    public record Wish(String good, int min, int max, String reason) {}

    private static final List<Wish> ANYONE = new ArrayList<>(), CHILD = new ArrayList<>(), ELDER = new ArrayList<>();
    private static final List<Wish> FESTIVE = new ArrayList<>(), FESTIVE_CHILD = new ArrayList<>();
    private static final Map<String, List<Wish>> BY_JOB = new HashMap<>();

    private Wishes() {}

    private static void job(String job, String good, int min, int max, String reason) {
        BY_JOB.computeIfAbsent(job, k -> new ArrayList<>()).add(new Wish(good, min, max, reason));
    }

    static {
        ANYONE.add(new Wish("bread", 6, 12, "I've a craving for fresh bread."));
        ANYONE.add(new Wish("apple", 4, 8, "I haven't tasted an apple in weeks."));
        ANYONE.add(new Wish("cooked_beef", 4, 8, "A proper roast would do me the world of good."));
        ANYONE.add(new Wish("pumpkin_pie", 1, 3, "My mother used to bake pumpkin pie."));
        ANYONE.add(new Wish("cake", 1, 1, "It's my name day soon."));
        ANYONE.add(new Wish("cookie", 6, 12, "Something sweet for after work."));
        ANYONE.add(new Wish("honey_bottle", 1, 3, "Honey for my throat."));
        ANYONE.add(new Wish("melon_slice", 6, 12, "It's been so hot in the fields."));
        ANYONE.add(new Wish("torch", 8, 16, "My house is dark at night."));
        ANYONE.add(new Wish("white_wool", 3, 6, "I'd like to make a new blanket."));
        ANYONE.add(new Wish("poppy", 3, 6, "Flowers for the table."));
        ANYONE.add(new Wish("dandelion", 3, 6, "Something bright for the windowsill."));
        ANYONE.add(new Wish("glass", 4, 8, "A window pane has cracked."));
        ANYONE.add(new Wish("leather", 2, 4, "My boots need mending."));
        ANYONE.add(new Wish("coal", 6, 12, "The stove at home is cold."));
        ANYONE.add(new Wish("lantern", 1, 2, "A lantern by the door would be a comfort."));
        ANYONE.add(new Wish("painting", 1, 1, "The wall at home is so bare."));
        ANYONE.add(new Wish("flower_pot", 1, 2, "I'd like to grow something of my own."));

        CHILD.add(new Wish("cookie", 4, 8, "Please? I've been good all week!"));
        CHILD.add(new Wish("sweet_berries", 4, 8, "They're my favourite."));
        CHILD.add(new Wish("cake", 1, 1, "I've never had a whole cake."));
        CHILD.add(new Wish("apple", 3, 6, "For me and my friends."));
        CHILD.add(new Wish("feather", 2, 4, "I'm making a hat like the bard's."));
        CHILD.add(new Wish("bone", 1, 3, "For the dog I'm going to have one day."));
        CHILD.add(new Wish("pumpkin", 1, 2, "I want to carve a face in it."));

        ELDER.add(new Wish("book", 1, 2, "Something to read by the fire."));
        ELDER.add(new Wish("honey_bottle", 1, 2, "It eases these old bones."));
        ELDER.add(new Wish("white_wool", 3, 5, "The nights are cold at my age."));
        ELDER.add(new Wish("baked_potato", 4, 8, "Soft food suits me best now."));
        ELDER.add(new Wish("poppy", 3, 6, "For someone I still miss."));

        FESTIVE.add(new Wish("cookie", 8, 16, "For the Christmas table."));
        FESTIVE.add(new Wish("cake", 1, 1, "It isn't Christmas without a cake."));
        FESTIVE.add(new Wish("pumpkin_pie", 2, 4, "A pie for Christmas dinner."));
        FESTIVE.add(new Wish("cooked_chicken", 2, 4, "A roast bird for the feast."));
        FESTIVE.add(new Wish("white_wool", 4, 8, "Stockings to hang by the fire."));
        FESTIVE.add(new Wish("red_dye", 2, 4, "Ribbons for the door."));
        FESTIVE.add(new Wish("spruce_sapling", 1, 2, "A little tree for my own window."));
        FESTIVE.add(new Wish("lantern", 1, 2, "Lights for the long nights."));
        FESTIVE.add(new Wish("sweet_berries", 8, 16, "To string up with the holly."));
        FESTIVE.add(new Wish("honey_bottle", 1, 2, "For mulling, on a cold evening."));
        FESTIVE_CHILD.add(new Wish("snowball", 8, 16, "For the biggest snowball fight ever!"));
        FESTIVE_CHILD.add(new Wish("cookie", 6, 12, "Christmas cookies! Please!"));
        FESTIVE_CHILD.add(new Wish("carrot", 2, 4, "For the reindeer, when they come."));
        FESTIVE_CHILD.add(new Wish("cake", 1, 1, "A Christmas cake, just this once?"));

        job("FARMER", "bone_meal", 8, 16, "The fields are tired.");
        job("FARMER", "wheat_seeds", 16, 32, "I'd sow another row if I had the seed.");
        job("ANIMAL_KEEPER", "wheat", 16, 32, "The animals are eating me out of feed.");
        job("ANIMAL_KEEPER", "lead", 1, 2, "One of them keeps wandering off.");
        job("FISHER", "string", 3, 6, "My line is more knot than line.");
        job("FORESTER", "oak_sapling", 4, 8, "I'd like to plant more than I fell.");
        job("MINER", "torch", 16, 32, "It's black as pitch down there.");
        job("BLACKSMITH", "coal", 8, 16, "The forge eats coal faster than I can find it.");
        job("BLACKSMITH", "iron_ingot", 3, 6, "I've an idea for a better hinge.");
        job("CARPENTER", "oak_log", 8, 16, "Good timber is hard to come by.");
        job("MASON", "clay_ball", 8, 16, "I want to try a new kind of brick.");
        job("MASON", "sand", 8, 16, "For glass, if I can get the heat right.");
        job("CRAFTSWORKER", "string", 4, 8, "I'm always short of string.");
        job("CRAFTSWORKER", "feather", 4, 8, "Arrows don't fletch themselves.");
        job("CRAFTSWORKER", "flint", 4, 8, "For arrowheads.");
        job("BUILDER", "ladder", 4, 8, "I'm tired of climbing scaffolds by my fingernails.");
        job("BUILDER", "cobblestone", 32, 64, "There's never enough stone.");
        job("COOK", "sugar", 4, 8, "I want to try something sweet.");
        job("COOK", "egg", 6, 12, "You can't cook without eggs.");
        job("BAKER", "sugar", 4, 8, "For a recipe I've been dreaming about.");
        job("BAKER", "egg", 6, 12, "The best loaves want an egg wash.");
        job("DOCTOR", "glass_bottle", 3, 6, "I've nothing to keep tinctures in.");
        job("DOCTOR", "sweet_berries", 8, 16, "They make a good poultice.");
        job("TEACHER", "book", 2, 4, "The children have read everything we own twice.");
        job("TEACHER", "paper", 6, 12, "For the pupils to write on.");
        job("RESEARCHER", "paper", 6, 12, "I've run out of margins to write in.");
        job("RESEARCHER", "ink_sac", 1, 3, "My inkwell is dry.");
        job("RESEARCHER", "book", 2, 4, "I need to check something in the old texts.");
        job("ENCHANTER", "lapis_lazuli", 4, 8, "The stones are the heart of the work.");
        job("ENCHANTER", "book", 1, 3, "The old words are written down somewhere.");
        job("BARD", "string", 2, 4, "I've broken another string.");
        job("BARD", "note_block", 1, 1, "I want to try something new at the tavern.");
        job("DRUID", "oak_sapling", 4, 8, "The grove wants company.");
        job("DRUID", "bone_meal", 8, 16, "The earth gives more when it's given to.");
        job("BEEKEEPER", "glass_bottle", 4, 8, "The honey has nowhere to go.");
        job("BEEKEEPER", "poppy", 4, 8, "The bees want flowers near the hives.");
        job("GUARD", "arrow", 16, 32, "A guard can't have too many arrows.");
        job("GUARD", "leather", 3, 6, "My jerkin is falling apart.");
        job("SOLDIER", "arrow", 16, 32, "For practice at the butts.");
        job("SOLDIER", "leather", 3, 6, "The straps on my kit are worn through.");
        job("MERCHANT", "paper", 4, 8, "Ledgers, ledgers, ledgers.");
        job("SHOPKEEPER", "paper", 4, 8, "For price tags.");
        job("ADMINISTRATOR", "paper", 6, 12, "The colony runs on paper.");
        job("INNKEEPER", "white_wool", 4, 8, "The guest beds need new covers.");
        job("WARDEN", "bread", 6, 12, "Even prisoners must eat.");
    }

    /**
     * Everything a citizen might ask for.
     *
     * @param job   the job's enum name ("FARMER")
     * @param child still a child
     * @param elder grown old
     */
    public static List<Wish> pool(String job, boolean child, boolean elder) {
        List<Wish> out = new ArrayList<>();
        if (child) {
            out.addAll(CHILD);
            return out;
        }
        // their trade's wants count double: that's what's on their mind
        List<Wish> own = BY_JOB.getOrDefault(job, List.of());
        out.addAll(own);
        out.addAll(own);
        out.addAll(elder ? ELDER : ANYONE);
        if (elder) out.addAll(ANYONE.subList(0, 4));
        return out;
    }

    /** What people ask for at Christmas. */
    public static List<Wish> festive(boolean child) {
        return new ArrayList<>(child ? FESTIVE_CHILD : FESTIVE);
    }

    /** Every wish there is (for checking the table against the goods list). */
    public static List<Wish> all() {
        List<Wish> out = new ArrayList<>(ANYONE);
        out.addAll(FESTIVE);
        out.addAll(FESTIVE_CHILD);
        out.addAll(CHILD);
        out.addAll(ELDER);
        for (List<Wish> l : BY_JOB.values()) out.addAll(l);
        return out;
    }

    /** What a citizen pays for a delivery: half again what the goods are worth, if they have it. */
    public static long reward(double value, int amount, long wallet) {
        long fair = Math.max(1, Math.round(value * amount * 1.5));
        return Math.max(0, Math.min(wallet, fair));
    }
}
