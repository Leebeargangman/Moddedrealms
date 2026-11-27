package com.colonysmp.model;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Goods on the road between two colonies (a caravan). They have left the sender and arrive after a delay that
 * depends on the distance; enemy raiders can intercept caravans near their own land.
 */
public final class Shipment {

    public final String id;
    public final String from, to;
    public final Map<String, Long> goods = new LinkedHashMap<>();
    /** Epoch ms of arrival. */
    public long arrives;
    public String reason;

    public Shipment(String id, String from, String to, long arrives, String reason) {
        this.id = id;
        this.from = from;
        this.to = to;
        this.arrives = arrives;
        this.reason = reason;
    }

    public void save(ConfigurationSection s) {
        s.set("from", from);
        s.set("to", to);
        s.set("arrives", arrives);
        s.set("reason", reason);
        goods.forEach((k, v) -> s.set("goods." + k, v));
    }

    public static Shipment load(String id, ConfigurationSection s) {
        String from = s.getString("from"), to = s.getString("to");
        if (from == null || to == null) return null;
        Shipment sh = new Shipment(id, from, to, s.getLong("arrives"), s.getString("reason", ""));
        ConfigurationSection g = s.getConfigurationSection("goods");
        if (g != null) for (String k : g.getKeys(false)) sh.goods.put(k, g.getLong(k));
        return sh;
    }
}
