package com.colonysmp.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Locale;

/**
 * How two colonies stand with each other: a score that trade, gifts, raids and broken promises move; the
 * status that follows from it (or from an alliance or a war); the treaties between them; and a record of why.
 */
public final class Relation {

    public enum Status {
        NEUTRAL("Neutral", "<gray>"), FRIENDLY("Friendly", "<green>"), ALLIED("Allied", "<aqua>"), HOSTILE("Hostile", "<gold>"),
        AT_WAR("At war", "<red>"), TRUCE("Truce", "<yellow>");

        public final String display, color;

        Status(String display, String color) {
            this.display = display;
            this.color = color;
        }
    }

    public enum Treaty {
        NON_AGGRESSION("Non-aggression pact", "Neither side may declare war while it lasts (breaking it is a grave betrayal)."),
        TRADE_AGREEMENT("Trade agreement", "Cheaper trade between the two and recurring supply contracts."),
        DEFENSIVE_PACT("Defensive pact", "Each joins the other's defensive wars."),
        OPEN_BORDERS("Open borders", "Members may use doors and gates in each other's territory.");

        public final String display, description;

        Treaty(String display, String description) {
            this.display = display;
            this.description = description;
        }

        public static Treaty parse(String s) {
            if (s == null) return null;
            String u = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            for (Treaty t : values()) if (t.name().equals(u) || t.display.equalsIgnoreCase(s.trim())) return t;
            return switch (u) {
                case "NAP", "PEACE_PACT" -> NON_AGGRESSION;
                case "TRADE" -> TRADE_AGREEMENT;
                case "DEFENSE", "DEFENCE", "DEFENSIVE" -> DEFENSIVE_PACT;
                case "BORDERS", "OPEN" -> OPEN_BORDERS;
                default -> null;
            };
        }
    }

    public final String a, b;
    public double score;
    public boolean allied;
    public boolean atWar;
    public long truceUntilDay = -1;
    public final EnumSet<Treaty> treaties = EnumSet.noneOf(Treaty.class);
    public final Deque<String> history = new ArrayDeque<>();

    public Relation(String a, String b) {
        boolean swap = a.compareTo(b) > 0;
        this.a = swap ? b : a;
        this.b = swap ? a : b;
    }

    public static String key(String x, String y) {
        return x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x;
    }

    public String key() {
        return key(a, b);
    }

    public String other(String id) {
        return id.equals(a) ? b : a;
    }

    public Status status(long day) {
        if (atWar) return Status.AT_WAR;
        if (allied) return Status.ALLIED;
        if (truceUntilDay >= day) return Status.TRUCE;
        if (score >= 30) return Status.FRIENDLY;
        if (score <= -30) return Status.HOSTILE;
        return Status.NEUTRAL;
    }

    public void change(double delta) {
        score = Math.max(-100, Math.min(100, score + delta));
    }

    public void note(long day, String what) {
        history.addFirst("Day " + day + ": " + what);
        while (history.size() > 25) history.removeLast();
    }

    public void save(ConfigurationSection s) {
        s.set("a", a);
        s.set("b", b);
        s.set("score", score);
        s.set("allied", allied);
        s.set("at-war", atWar);
        s.set("truce-until", truceUntilDay);
        java.util.List<String> t = new ArrayList<>();
        for (Treaty tr : treaties) t.add(tr.name());
        s.set("treaties", t);
        s.set("history", new ArrayList<>(history));
    }

    public static Relation load(ConfigurationSection s) {
        String a = s.getString("a"), b = s.getString("b");
        if (a == null || b == null) return null;
        Relation r = new Relation(a, b);
        r.score = s.getDouble("score");
        r.allied = s.getBoolean("allied");
        r.atWar = s.getBoolean("at-war");
        r.truceUntilDay = s.getLong("truce-until", -1);
        for (String t : s.getStringList("treaties")) {
            Treaty tr = Treaty.parse(t);
            if (tr != null) r.treaties.add(tr);
        }
        for (String h : s.getStringList("history")) r.history.addLast(h);
        return r;
    }
}
