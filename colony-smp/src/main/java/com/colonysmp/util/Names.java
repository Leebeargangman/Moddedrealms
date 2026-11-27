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

    /** A first name with a family's surname (children). */
    public static String child(String parentName) {
        String last = parentName == null || !parentName.contains(" ") ? LAST[ThreadLocalRandom.current().nextInt(LAST.length)]
                : parentName.substring(parentName.lastIndexOf(' ') + 1);
        return FIRST[ThreadLocalRandom.current().nextInt(FIRST.length)] + " " + last;
    }
}
