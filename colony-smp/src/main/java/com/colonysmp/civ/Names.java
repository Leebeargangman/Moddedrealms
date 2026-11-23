package com.colonysmp.civ;

import java.util.Random;

/** Names for peoples, places, rulers and their houses, put together from syllables so no two worlds read alike. */
public final class Names {

    private static final String[] START = {"Al", "Bren", "Cal", "Dor", "El", "Fen", "Gar", "Hal", "Ist", "Kor", "Lor", "Mar", "Nor", "Os", "Pel", "Quen",
            "Rav", "Sel", "Tor", "Ul", "Var", "Wyn", "Yar", "Zan", "Ash", "Bel", "Cor", "Dun", "Esk", "Thorn", "Storm", "Grey", "Red", "Black", "High", "Oak"};
    private static final String[] MID = {"a", "e", "i", "o", "u", "an", "en", "or", "ar", "el", "il", "ra", "de", "ne", "", "", ""};
    private static final String[] PLACE = {"moor", "ford", "wick", "holt", "haven", "stead", "mere", "fell", "dale", "burgh", "gard", "mouth", "crest", "hollow",
            "field", "watch", "reach", "march", "ton", "by", "thorpe", "combe"};
    private static final String[] PEOPLE = {"ia", "or", "eth", "ar", "en", "is", "and", "ost", "ir", "heim", "mark", "wald"};
    private static final String[] FIRST = {"Aldric", "Brenna", "Cedric", "Dalia", "Edmund", "Freya", "Garrick", "Helena", "Ivo", "Jorun", "Kael", "Lysa",
            "Magnus", "Nessa", "Osric", "Petra", "Quill", "Rowan", "Sigrid", "Tobias", "Una", "Viktor", "Wilhelmina", "Ysolde", "Bram", "Corwin", "Elspeth",
            "Hadrian", "Isolde", "Leofric", "Maren", "Odo", "Rosalind", "Theron"};
    private static final String[] HOUSE = {"Ashford", "Blackwood", "Corvane", "Dunmere", "Everard", "Falkenrath", "Greymantle", "Harrow", "Ironside",
            "Kestrel", "Lorne", "Mournhold", "Northcott", "Oakenshaw", "Pellam", "Ravensworth", "Stormont", "Thorne", "Underhill", "Vance", "Wolfe", "Yarrow"};

    private Names() {}

    private static String pick(Random r, String[] a) {
        return a[r.nextInt(a.length)];
    }

    public static String place(Random r) {
        return pick(r, START) + pick(r, MID) + pick(r, PLACE);
    }

    public static String people(Random r) {
        String mid = pick(r, MID);
        return pick(r, START) + (mid.isEmpty() ? "a" : mid) + pick(r, PEOPLE);
    }

    public static String person(Random r) {
        return pick(r, FIRST);
    }

    public static String house(Random r) {
        return pick(r, HOUSE);
    }

    public static String camp(Random r) {
        String[] a = {"Crow", "Wolf", "Viper", "Jackal", "Rat", "Raven", "Boar", "Hound"}, b = {"'s Nest", " Hollow", " Den", "'s Roost", " Gulch", "'s Rest"};
        return pick(r, a) + pick(r, b);
    }
}
