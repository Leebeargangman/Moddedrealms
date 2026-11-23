package com.colonysmp.core;

import java.util.HashMap;
import java.util.Map;

/**
 * The server-wide market where colonies sell surplus and buy what they lack. Each good has a pressure between
 * -1 (flooded) and +1 (scarce) that selling and buying push around and that slowly returns to 0. Prices are the
 * base value scaled by pressure, with a spread between buying and selling that good merchants narrow.
 */
public final class Market {

    private final Map<String, Double> pressure = new HashMap<>();
    /** Fraction of pressure that fades per in-game hour. */
    private double recovery = 0.02;
    /** Base spread between the buy and sell price. */
    private double spread = 0.2;

    public void configure(double recoveryPerHour, double spread) {
        this.recovery = Math.max(0, Math.min(1, recoveryPerHour));
        this.spread = Math.max(0, Math.min(0.9, spread));
    }

    public double pressure(String key) {
        return pressure.getOrDefault(key, 0.0);
    }

    /** Price the market pays a colony per item (merchantSkill 0-10 narrows the spread). */
    public double sellPrice(String key, int merchantSkill) {
        return mid(key) * (1 - spread(merchantSkill));
    }

    /** Price a colony pays the market per item. */
    public double buyPrice(String key, int merchantSkill) {
        return mid(key) * (1 + spread(merchantSkill));
    }

    public double mid(String key) {
        return Goods.get(key).value() * (1 + 0.6 * pressure(key));
    }

    private double spread(int skill) {
        return spread * (1 - 0.05 * Math.max(0, Math.min(10, skill)));
    }

    /** How many items move the pressure by 0.1: big stacks of cheap goods barely dent it. */
    static double elasticity(String key) {
        Good g = Goods.get(key);
        return Math.max(4, g.stack() * 4.0 / Math.max(0.25, Math.min(4, g.value() / 2)));
    }

    /** Total paid for selling qty items one at a time down the falling price. */
    public long quoteSale(String key, long qty, int skill) {
        double p = pressure(key), total = 0, e = elasticity(key);
        double base = Goods.get(key).value(), mult = 1 - spread(skill);
        for (long i = 0; i < qty; i++) {
            total += base * (1 + 0.6 * p) * mult;
            p = Math.max(-1, p - 0.1 / e);
        }
        return (long) Math.floor(total);
    }

    /** Total cost of buying qty items as the price climbs. */
    public long quotePurchase(String key, long qty, int skill) {
        double p = pressure(key), total = 0, e = elasticity(key);
        double base = Goods.get(key).value(), mult = 1 + spread(skill);
        for (long i = 0; i < qty; i++) {
            total += base * (1 + 0.6 * p) * mult;
            p = Math.min(1, p + 0.1 / e);
        }
        return (long) Math.ceil(total);
    }

    public void recordSale(String key, long qty) {
        pressure.put(key, Math.max(-1, pressure(key) - 0.1 * qty / elasticity(key)));
    }

    public void recordPurchase(String key, long qty) {
        pressure.put(key, Math.min(1, pressure(key) + 0.1 * qty / elasticity(key)));
    }

    public void recover(double hours) {
        if (hours <= 0) return;
        double keep = Math.pow(1 - recovery, hours);
        pressure.replaceAll((k, v) -> v * keep);
        pressure.values().removeIf(v -> Math.abs(v) < 0.001);
    }

    public Map<String, Double> all() {
        return new HashMap<>(pressure);
    }

    public void load(Map<String, Double> saved) {
        pressure.clear();
        saved.forEach((k, v) -> pressure.put(k, Math.max(-1, Math.min(1, v))));
    }
}
