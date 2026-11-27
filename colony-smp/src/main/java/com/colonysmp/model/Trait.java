package com.colonysmp.model;

import java.util.concurrent.ThreadLocalRandom;

/** A personal quirk. Travellers advertise theirs; it shapes what a citizen is good at and enjoys. */
public enum Trait {
    ORDINARY("Ordinary", "No special talents."),
    HARDWORKING("Hardworking", "+20% work speed."),
    LAZY("Lazy", "-20% work speed."),
    FRUGAL("Frugal", "Eats 25% less."),
    GLUTTON("Glutton", "Eats 50% more."),
    GREEN_THUMB("Green Thumb", "+35% farming; loves farm work."),
    WOODSMAN("Woodsman", "+35% forestry and building."),
    PROSPECTOR("Prospector", "+35% mining."),
    ANIMAL_LOVER("Animal Lover", "+35% with animals; loves the pens."),
    ARTISAN("Artisan", "+25% at crafts, smithing and cooking."),
    BRAVE("Brave", "+25% combat damage, steadier morale."),
    COWARD("Coward", "-20% combat damage, breaks easily."),
    ROBUST("Robust", "Rarely falls ill; heals faster."),
    SICKLY("Sickly", "Falls ill twice as often."),
    SOCIABLE("Sociable", "Needs company and enjoys it more."),
    LONER("Loner", "Doesn't mind being alone."),
    QUICK_LEARNER("Quick Learner", "+50% experience and schooling."),
    CHARISMATIC("Charismatic", "Better prices; a natural leader."),
    LOYAL("Loyal", "Never emigrates or commits crimes."),
    GREEDY("Greedy", "Wants good wages; tempted by theft."),
    STUBBORN("Stubborn", "+25 resistance when imprisoned.");

    public final String display, description;

    Trait(String display, String description) {
        this.display = display;
        this.description = description;
    }

    public double work(Job job) {
        return switch (this) {
            case HARDWORKING -> 1.2;
            case LAZY -> 0.8;
            case GREEN_THUMB -> job == Job.FARMER ? 1.35 : 1;
            case WOODSMAN -> job == Job.FORESTER || job == Job.BUILDER || job == Job.CARPENTER ? 1.35 : 1;
            case PROSPECTOR -> job == Job.MINER || job == Job.MASON ? 1.35 : 1;
            case ANIMAL_LOVER -> job == Job.ANIMAL_KEEPER ? 1.35 : 1;
            case ARTISAN -> switch (job) {
                case BLACKSMITH, CARPENTER, MASON, CRAFTSWORKER, COOK, BAKER, BUTCHER, MILLER -> 1.25;
                default -> 1;
            };
            case CHARISMATIC -> job == Job.MERCHANT || job == Job.SHOPKEEPER || job == Job.INNKEEPER ? 1.2 : 1;
            default -> 1;
        };
    }

    /** True if this trait makes the citizen enjoy the job (mood). */
    public boolean likes(Job job) {
        return work(job) > 1.05 && this != HARDWORKING;
    }

    public double food() {
        return switch (this) {
            case FRUGAL -> 0.75;
            case GLUTTON -> 1.5;
            default -> 1;
        };
    }

    public double combat() {
        return switch (this) {
            case BRAVE -> 1.25;
            case COWARD -> 0.8;
            default -> 1;
        };
    }

    public double morale() {
        return switch (this) {
            case BRAVE -> 10;
            case COWARD -> -15;
            default -> 0;
        };
    }

    public double illness() {
        return switch (this) {
            case ROBUST -> 0.5;
            case SICKLY -> 2;
            default -> 1;
        };
    }

    public double learning() {
        return this == QUICK_LEARNER ? 1.5 : 1;
    }

    public double social() {
        return switch (this) {
            case SOCIABLE -> 1.5;
            case LONER -> 0;
            default -> 1;
        };
    }

    public double crime() {
        return switch (this) {
            case LOYAL -> 0;
            case GREEDY -> 2.5;
            default -> 1;
        };
    }

    public int resistance() {
        return this == STUBBORN ? 25 : this == LOYAL ? 15 : 0;
    }

    public static Trait random() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        if (r.nextDouble() < 0.3) return ORDINARY;
        Trait[] all = values();
        return all[1 + r.nextInt(all.length - 1)];
    }

    public static Trait parse(String s) {
        try {
            return s == null ? ORDINARY : valueOf(s);
        } catch (IllegalArgumentException e) {
            return switch (s) {
                case "DOCILE" -> ORDINARY;
                case "BRAVE" -> BRAVE;
                default -> ORDINARY;
            };
        }
    }
}
