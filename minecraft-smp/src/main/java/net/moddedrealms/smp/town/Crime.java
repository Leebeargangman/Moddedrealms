package net.moddedrealms.smp.town;

/** What the police care about: wanted stars when seen, reputation lost either way. Overridable in config.yml. */
public enum Crime {
    CONTRABAND("carrying explosives", 0, 2),
    CASINO("illegal gambling", 1, 5),
    THEFT("carrying stolen goods", 1, 5),
    BRIBERY("attempted bribery", 1, 10),
    ASSAULT_NPC("assaulting a townsperson", 1, 10),
    PICKPOCKET("pickpocketing", 1, 8),
    RESISTING("resisting arrest", 1, 10),
    ARSON("arson", 2, 15),
    EXPLOSIVES("using explosives", 2, 20),
    ASSAULT("assault", 2, 15),
    ROBBERY("robbing a player", 2, 20),
    PET_KILLING("killing someone's pet", 2, 25),
    ASSAULT_OFFICER("assaulting an officer", 2, 25),
    MANSLAUGHTER("killing a townsperson", 3, 40),
    MURDER("murder", 4, 50),
    BANK_ROBBERY("robbing the bank", 4, 60),
    HEIST("armed heist", 5, 100),
    PRISON_BREAK("breaking someone out of jail", 3, 40),
    LOAN_DEFAULT("defaulting on a bank loan", 2, 30),
    ESCAPE("escaping from jail", 3, 30),
    COP_KILLING("killing an officer", 5, 80);

    public final String label;
    public final int defaultStars;
    public final int defaultRep;
    public int stars;
    public int rep;

    Crime(String label, int stars, int rep) {
        this.label = label;
        this.defaultStars = stars;
        this.defaultRep = rep;
        this.stars = stars;
        this.rep = rep;
    }

    public String key() {
        return name().toLowerCase().replace('_', '-');
    }
}
