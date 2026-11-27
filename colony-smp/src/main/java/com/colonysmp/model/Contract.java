package com.colonysmp.model;

import org.bukkit.configuration.ConfigurationSection;

/**
 * A recurring supply agreement: every few days the supplier ships a quantity of a good to the buyer, who pays
 * on dispatch. Deliveries only happen when the supplier really has the goods (beyond its own reserves) and the
 * buyer has the money and room; repeated failures break the contract and sour relations.
 */
public final class Contract {

    public final String id;
    public final String supplier, buyer;
    public final String good;
    public final int qty;
    /** Paid per delivery. */
    public final long price;
    public final int everyDays;
    public final long endDay;
    public long nextDay;
    public int deliveries, failures;
    public boolean active = true;
    public String lastProblem;

    public Contract(String id, String supplier, String buyer, String good, int qty, long price, int everyDays, long nextDay, long endDay) {
        this.id = id;
        this.supplier = supplier;
        this.buyer = buyer;
        this.good = good;
        this.qty = qty;
        this.price = price;
        this.everyDays = Math.max(1, everyDays);
        this.nextDay = nextDay;
        this.endDay = endDay;
    }

    public void save(ConfigurationSection s) {
        s.set("supplier", supplier);
        s.set("buyer", buyer);
        s.set("good", good);
        s.set("qty", qty);
        s.set("price", price);
        s.set("every", everyDays);
        s.set("end-day", endDay);
        s.set("next-day", nextDay);
        s.set("deliveries", deliveries);
        s.set("failures", failures);
        s.set("active", active);
        s.set("problem", lastProblem);
    }

    public static Contract load(String id, ConfigurationSection s) {
        String sup = s.getString("supplier"), buy = s.getString("buyer"), good = s.getString("good");
        if (sup == null || buy == null || good == null) return null;
        Contract c = new Contract(id, sup, buy, good, s.getInt("qty"), s.getLong("price"), s.getInt("every", 1), s.getLong("next-day"), s.getLong("end-day"));
        c.deliveries = s.getInt("deliveries");
        c.failures = s.getInt("failures");
        c.active = s.getBoolean("active", true);
        c.lastProblem = s.getString("problem");
        return c;
    }
}
