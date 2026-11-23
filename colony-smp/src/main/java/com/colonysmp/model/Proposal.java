package com.colonysmp.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An offer from one colony to another, waiting for an authorised member of the other side to accept or
 * decline: an alliance or treaty, peace terms, a one-off trade, a supply contract, a prisoner exchange or a
 * ransom.
 */
public final class Proposal {

    public enum Type {
        ALLIANCE("Alliance"), TREATY("Treaty"), PEACE("Peace"), TRADE("Trade"), CONTRACT("Supply contract"),
        PRISONER_EXCHANGE("Prisoner exchange"), RANSOM("Ransom"), JOIN_WAR("Call to arms");

        public final String display;

        Type(String display) {
            this.display = display;
        }
    }

    public final String id;
    public final Type type;
    public final String from, to;
    public String by;
    public long expires;
    public Relation.Treaty treaty;
    /** Goods the proposer hands over / asks for. */
    public final Map<String, Long> give = new LinkedHashMap<>(), want = new LinkedHashMap<>();
    /** Money the proposer pays (negative: money they ask for). */
    public long money;
    // contracts
    public String good;
    public int qty, everyDays = 1, days = 7;
    // peace
    public int truceDays;
    /** Prisoners the proposer releases / asks to have released. */
    public final List<UUID> release = new ArrayList<>(), demand = new ArrayList<>();
    /** War id (peace, calls to arms). */
    public String war;

    public Proposal(String id, Type type, String from, String to) {
        this.id = id;
        this.type = type;
        this.from = from;
        this.to = to;
    }

    public void save(ConfigurationSection s) {
        s.set("type", type.name());
        s.set("from", from);
        s.set("to", to);
        s.set("by", by);
        s.set("expires", expires);
        s.set("treaty", treaty == null ? null : treaty.name());
        give.forEach((k, v) -> s.set("give." + k, v));
        want.forEach((k, v) -> s.set("want." + k, v));
        s.set("money", money);
        s.set("good", good);
        s.set("qty", qty);
        s.set("every", everyDays);
        s.set("days", days);
        s.set("truce-days", truceDays);
        s.set("release", release.stream().map(UUID::toString).toList());
        s.set("demand", demand.stream().map(UUID::toString).toList());
        s.set("war", war);
    }

    public static Proposal load(String id, ConfigurationSection s) {
        Type t;
        try {
            t = Type.valueOf(s.getString("type", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
        Proposal p = new Proposal(id, t, s.getString("from"), s.getString("to"));
        if (p.from == null || p.to == null) return null;
        p.by = s.getString("by");
        p.expires = s.getLong("expires");
        p.treaty = Relation.Treaty.parse(s.getString("treaty"));
        ConfigurationSection g = s.getConfigurationSection("give");
        if (g != null) for (String k : g.getKeys(false)) p.give.put(k, g.getLong(k));
        ConfigurationSection w = s.getConfigurationSection("want");
        if (w != null) for (String k : w.getKeys(false)) p.want.put(k, w.getLong(k));
        p.money = s.getLong("money");
        p.good = s.getString("good");
        p.qty = s.getInt("qty");
        p.everyDays = Math.max(1, s.getInt("every", 1));
        p.days = Math.max(1, s.getInt("days", 7));
        p.truceDays = s.getInt("truce-days");
        for (String u : s.getStringList("release")) uuid(u, p.release);
        for (String u : s.getStringList("demand")) uuid(u, p.demand);
        p.war = s.getString("war");
        return p;
    }

    private static void uuid(String s, List<UUID> out) {
        try {
            out.add(UUID.fromString(s));
        } catch (IllegalArgumentException ignored) {
            // skip
        }
    }
}
