package com.colonysmp.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;

/** How the colony is run. Every policy changes what citizens and systems actually do. */
public final class Policies {

    public enum Economy {
        WAGES("Wages", "Workers are paid daily and buy their meals and goods (their pay in valuables flows back through shops and restaurants)."),
        COMMUNAL("Communal", "No wages: everyone eats from the common stores. Cheaper, but skilled workers are less content.");

        public final String display, description;

        Economy(String display, String description) {
            this.display = display;
            this.description = description;
        }
    }

    public enum Wages {
        LOW("Low", 0.75, -4), NORMAL("Normal", 1, 0), HIGH("High", 1.3, 4);

        public final String display;
        public final double factor, mood;

        Wages(String display, double factor, double mood) {
            this.display = display;
            this.factor = factor;
            this.mood = mood;
        }
    }

    public enum Hours {
        SHORT("Short (8h)", 2000, 10000, 0.0), NORMAL("Normal (10h)", 1000, 11000, 0.0), LONG("Long (12h)", 0, 12000, 1.0);

        public final String display;
        public final long start, end;
        public final double strain;

        Hours(String display, long start, long end, double strain) {
            this.display = display;
            this.start = start;
            this.end = end;
            this.strain = strain;
        }
    }

    public enum Recruitment {
        OPEN("Open", "Travellers may settle and settlers can be recruited."),
        LIMITED("Limited", "Only skilled or recommended travellers are taken in."),
        CLOSED("Closed", "No newcomers; the population only grows by births.");

        public final String display, description;

        Recruitment(String display, String description) {
            this.display = display;
            this.description = description;
        }
    }

    public enum Military {
        VOLUNTEERS("Volunteers", "Only willing citizens join the army; happier soldiers."),
        CONSCRIPTION("Conscription", "Any adult can be drafted; faster recruitment, unhappier citizens.");

        public final String display, description;

        Military(String display, String description) {
            this.display = display;
            this.description = description;
        }
    }

    public enum Posture {
        PASSIVE("Passive", "Guards only fight when attacked."),
        DEFENSIVE("Defensive", "Guards attack enemies and monsters inside the territory."),
        AGGRESSIVE("Aggressive", "Guards attack enemies near the territory and pursue them.");

        public final String display, description;

        Posture(String display, String description) {
            this.display = display;
            this.description = description;
        }
    }

    public enum Emergency {
        SHELTER("Shelter", "Civilians shelter at home or in the Town Hall when danger comes close."),
        MILITIA("Militia", "Able workers take up arms when the colony is attacked."),
        CARRY_ON("Carry on", "Civilians keep working unless danger is right on top of them.");

        public final String display, description;

        Emergency(String display, String description) {
            this.display = display;
            this.description = description;
        }
    }

    public enum Priority {
        BALANCED("Balanced"), FOOD("Food first"), MATERIALS("Materials first"), MILITARY("Military first"), TRADE("Trade goods first");

        public final String display;

        Priority(String display) {
            this.display = display;
        }
    }

    public Economy economy = Economy.WAGES;
    public Wages wages = Wages.NORMAL;
    /** 0 to 0.3 of wages taken as tax. */
    public double taxRate = 0.1;
    public Hours hours = Hours.NORMAL;
    public Recruitment recruitment = Recruitment.OPEN;
    /** 0 = only the server limit. */
    public int populationLimit;
    public Military military = Military.VOLUNTEERS;
    public Posture posture = Posture.DEFENSIVE;
    public Emergency emergency = Emergency.SHELTER;
    public Priority priority = Priority.BALANCED;
    /** Days of food kept back from sales and exports. */
    public int foodReserveDays = 3;
    public boolean autoSell;
    public boolean autoBuy;
    public boolean tradeWithNeutrals = true;
    public boolean quarantine;
    public boolean rationing;
    public boolean births = true;

    public void save(ConfigurationSection s) {
        s.set("economy", economy.name());
        s.set("wages", wages.name());
        s.set("tax", taxRate);
        s.set("hours", hours.name());
        s.set("recruitment", recruitment.name());
        s.set("population-limit", populationLimit);
        s.set("military", military.name());
        s.set("posture", posture.name());
        s.set("emergency", emergency.name());
        s.set("priority", priority.name());
        s.set("food-reserve-days", foodReserveDays);
        s.set("auto-sell", autoSell);
        s.set("auto-buy", autoBuy);
        s.set("trade-with-neutrals", tradeWithNeutrals);
        s.set("quarantine", quarantine);
        s.set("rationing", rationing);
        s.set("births", births);
    }

    public void load(ConfigurationSection s) {
        if (s == null) return;
        economy = parse(Economy.class, s.getString("economy"), economy);
        wages = parse(Wages.class, s.getString("wages"), wages);
        taxRate = Math.max(0, Math.min(0.3, s.getDouble("tax", taxRate)));
        hours = parse(Hours.class, s.getString("hours"), hours);
        recruitment = parse(Recruitment.class, s.getString("recruitment"), recruitment);
        populationLimit = Math.max(0, s.getInt("population-limit"));
        military = parse(Military.class, s.getString("military"), military);
        posture = parse(Posture.class, s.getString("posture"), posture);
        emergency = parse(Emergency.class, s.getString("emergency"), emergency);
        priority = parse(Priority.class, s.getString("priority"), priority);
        foodReserveDays = Math.max(0, Math.min(30, s.getInt("food-reserve-days", foodReserveDays)));
        autoSell = s.getBoolean("auto-sell");
        autoBuy = s.getBoolean("auto-buy");
        tradeWithNeutrals = s.getBoolean("trade-with-neutrals", true);
        quarantine = s.getBoolean("quarantine");
        rationing = s.getBoolean("rationing");
        births = s.getBoolean("births", true);
    }

    public static <E extends Enum<E>> E parse(Class<E> type, String s, E fallback) {
        if (s == null) return fallback;
        try {
            return Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** The next value of an enum, wrapping (menus cycle through options). */
    public static <E extends Enum<E>> E next(E e) {
        E[] all = e.getDeclaringClass().getEnumConstants();
        return all[(e.ordinal() + 1) % all.length];
    }
}
