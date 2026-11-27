package com.colonysmp.util;

import java.util.concurrent.ThreadLocalRandom;

/** Names for citizens and travellers. */
public final class Names {

    private static final String[] FIRST = {
            "Ada", "Aldric", "Amara", "Anselm", "Aoife", "Arin", "Astrid", "Bastian", "Bea", "Bram", "Brigid", "Cael", "Cora",
            "Darius", "Della", "Edda", "Elias", "Elin", "Emeric", "Esme", "Ezra", "Faye", "Finn", "Freya", "Gideon", "Greta",
            "Hale", "Hana", "Idris", "Ines", "Isolde", "Ivo", "Jonah", "Juno", "Kael", "Kaya", "Lars", "Leona", "Lio", "Lucan",
            "Mabel", "Mara", "Matteo", "Mira", "Nadia", "Niko", "Noor", "Oren", "Orla", "Otto", "Pia", "Quill", "Rafe", "Rhea",
            "Rowan", "Runa", "Sami", "Sela", "Silas", "Sora", "Tamsin", "Teo", "Thea", "Tobin", "Ulla", "Vera", "Viggo", "Wren",
            "Xan", "Yara", "Yusuf", "Zara", "Zeno", "Alma", "Benedikt", "Celeste", "Dorian", "Elodie", "Fennick", "Gwen"
    };
    private static final String[] LAST = {
            "Ashford", "Barrow", "Blackwood", "Bramble", "Brook", "Carver", "Cooper", "Dale", "Draper", "Elm", "Fairweather",
            "Fenwick", "Fletcher", "Ford", "Gale", "Greenhill", "Hale", "Harrow", "Hawthorne", "Holt", "Ironside", "Kettle",
            "Lark", "Linden", "Marsh", "Mason", "Meadows", "Miller", "Moss", "Oakes", "Pike", "Quarry", "Reed", "Rook", "Rowe",
            "Sawyer", "Shepherd", "Smith", "Stone", "Swift", "Tanner", "Thatcher", "Thorne", "Underhill", "Vale", "Wainwright",
            "Weaver", "Wells", "Whitlock", "Wilde", "Winter", "Wood", "Wright", "Yarrow", "Baker", "Fisher", "Hunter", "Archer"
    };

    private Names() {}

    public static String random() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return FIRST[r.nextInt(FIRST.length)] + " " + LAST[r.nextInt(LAST.length)];
    }

    /**
     * {@code wanted} if nobody has it, otherwise a free name: a new first name with the same surname (so families
     * stay families), then any free name, and if the colony is so big that every one is used, a numbered one.
     */
    public static String unique(String wanted, java.util.function.Predicate<String> taken) {
        if (wanted != null && !wanted.isBlank() && !taken.test(wanted)) return wanted;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String last = wanted == null || !wanted.contains(" ") ? null : wanted.substring(wanted.lastIndexOf(' ') + 1);
        if (last != null) {
            for (int i = 0; i < 40; i++) {
                String n = FIRST[r.nextInt(FIRST.length)] + " " + last;
                if (!taken.test(n)) return n;
            }
        }
        for (int i = 0; i < 400; i++) {
            String n = random();
            if (!taken.test(n)) return n;
        }
        String base = wanted == null || wanted.isBlank() ? random() : wanted;
        for (int i = 2; ; i++) {
            String n = base + " " + i;
            if (!taken.test(n)) return n;
        }
    }

    /** A first name with a family's surname (children). */
    public static String child(String parentName) {
        String last = parentName == null || !parentName.contains(" ") ? LAST[ThreadLocalRandom.current().nextInt(LAST.length)]
                : parentName.substring(parentName.lastIndexOf(' ') + 1);
        return FIRST[ThreadLocalRandom.current().nextInt(FIRST.length)] + " " + last;
    }
}
