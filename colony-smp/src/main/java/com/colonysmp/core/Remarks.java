package com.colonysmp.core;

import java.util.List;
import java.util.Random;

/**
 * What a citizen says to a player passing by. They speak of what is most on their mind: something stopping
 * their work first, then a pressing need, then the news of the day, then how they feel. A colony whose people
 * say what's wrong is far easier to run than one that suffers in silence.
 */
public final class Remarks {

    /** Everything about the speaker and their day that might be worth a remark. */
    public static final class Mind {
        /** Why they can't get on with their work ("Waiting for 6 flour"), or null. */
        public String blocked;
        public boolean starving, hungry, exhausted, homeless, ill, injured, unemployed, child;
        public int unpaidDays;
        /** What they have asked the players for ("8 cookies"), or null. */
        public String request;
        public boolean raidTonight, underAttack, festival, christmas, christmasTree, motivated, entertained;
        /** Mood 0-100 and the worst thing weighing on it (or null). */
        public double mood = 60;
        public String worst;
        /** What they do ("Baker") and where ("Bakery #3"), either may be null. */
        public String job, workplace;
        public String listener = "friend";
        /** 0 morning, 1 day, 2 evening, 3 night. */
        public int timeOfDay = 1;
    }

    private Remarks() {}

    private static String pick(Random r, String... lines) {
        return lines[r.nextInt(lines.length)];
    }

    private static String lowerFirst(String s) {
        return s == null || s.isEmpty() ? "" : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** The one thing this citizen would say right now. Never null. */
    public static String say(Mind m, Random r) {
        // danger outranks everything
        if (m.underAttack) return pick(r, "They're here! Get to the walls!", "Raiders! Where are the guards?", "Stay close to the Town Hall, " + m.listener + "!");
        // then whatever stops them doing their job: the player can usually fix it
        if (m.blocked != null && !m.child) return pick(r, "I can't get on with my work: ", "I'm stuck, " + m.listener + ": ", "Nothing I can do at the moment: ") + lowerFirst(m.blocked) + ".";
        if (m.starving) return pick(r, "I'm starving. Is there nothing in the stores?", "I haven't eaten properly in days.", "Please, " + m.listener + ", we need food.");
        if (m.injured) return pick(r, "This wound isn't healing. I need a doctor.", "I can barely stand. Is there no clinic?");
        if (m.ill) return pick(r, "I don't feel well at all.", "Keep back, " + m.listener + ", I think I'm coming down with something.");
        if (m.unpaidDays > 0 && !m.child) return m.unpaidDays == 1 ? "I wasn't paid yesterday. Is the treasury empty?" : "I haven't been paid in " + m.unpaidDays + " days. I won't work for nothing.";
        if (m.homeless) return pick(r, "I slept on the ground again last night. A bed would be something.", "There's no house for me yet. I do hope there will be.");
        // then, some of the time, the news
        if (m.raidTonight && r.nextInt(2) == 0) return pick(r, "They say raiders are coming tonight. I hope the guards are ready.", "I'll be barring my door at dusk. You've heard about the raiders?");
        if (m.request != null && r.nextInt(2) == 0) return pick(r, "You wouldn't happen to have " + m.request + "? I did ask.", "If only someone would bring me " + m.request + "...");
        if (m.christmas && r.nextInt(3) == 0) {
            if (m.child) return pick(r, "Is Father Christmas coming? Is he? Is he?", "I've been good all year, I promise!", "Merry Christmas!");
            return m.christmasTree ? pick(r, "Merry Christmas, " + m.listener + "!", "Have you seen the tree? It's a fine one this year.", "It smells of pine and baking everywhere. I love this time of year.")
                    : pick(r, "Merry Christmas, " + m.listener + "!", "It doesn't feel like Christmas without a tree by the Town Hall.", "A Christmas tree would cheer the place up no end.");
        }
        if (m.festival && r.nextInt(2) == 0) return pick(r, "What a feast day! Will you stay for the fireworks?", "No work worth the name today. It's a festival!");
        if (m.exhausted) return pick(r, "I'm dead on my feet.", "I could sleep where I stand.");
        if (m.hungry && r.nextInt(2) == 0) return pick(r, "I could do with a meal.", "Is it supper time yet?");
        if (m.unemployed && !m.child) return pick(r, "I've nothing to do. Is there no work for me?", "I'd take any job going, " + m.listener + ".");
        // and otherwise how they feel
        if (m.mood < 35 && m.worst != null) return pick(r, "I'm not happy here, " + m.listener + ". ", "Things could be better. ", "I'll be honest with you: ") + "What weighs on me most: " + lowerFirst(m.worst) + ".";
        if (m.child) return pick(r, "Want to see how fast I can run?", "Hello, " + m.listener + "!", "When I grow up I'm going to be a guard.", "Have you got any cookies?");
        if (m.motivated && r.nextInt(2) == 0) return pick(r, "I haven't forgotten your kindness. I'll work twice as hard.", "Thank you again, " + m.listener + ".");
        if (m.entertained && m.timeOfDay == 2 && r.nextInt(2) == 0) return pick(r, "Are you coming to the tavern? The bard's playing tonight.", "Nothing like a song after a day's work.");
        if (m.mood >= 75) {
            return pick(r, "Lovely day, isn't it?", "Life is good here, " + m.listener + ".", "I wouldn't live anywhere else.",
                    m.workplace != null ? "Busy day at the " + stripNumber(m.workplace) + ", but I don't mind." : "I've no complaints at all.");
        }
        String hello = switch (m.timeOfDay) {
            case 0 -> "Good morning, " + m.listener + ".";
            case 2 -> "Good evening, " + m.listener + ".";
            case 3 -> "You're up late, " + m.listener + ".";
            default -> "Good day, " + m.listener + ".";
        };
        if (m.job != null && m.workplace != null && r.nextInt(2) == 0) return hello + " " + pick(r, "Back to the " + stripNumber(m.workplace) + " for me.", "A " + m.job.toLowerCase() + "'s work is never done.");
        return hello;
    }

    /** "Bakery #3" -> "bakery". */
    static String stripNumber(String label) {
        int i = label.indexOf(" #");
        return (i < 0 ? label : label.substring(0, i)).toLowerCase();
    }

    /** Every flag a remark can be about (for tests that walk the table). */
    public static List<String> topics() {
        return List.of("underAttack", "blocked", "starving", "injured", "ill", "unpaid", "homeless", "raidTonight", "request", "christmas", "festival",
                "exhausted", "hungry", "unemployed", "unhappy", "child", "motivated", "entertained", "happy", "greeting");
    }
}
