package com.colonysmp.civ;

import java.util.Map;

/**
 * What things are worth in one settlement, today. Worth is only an exchange rate between items: nothing is ever
 * paid in it and nobody holds any.
 *
 * <p>The worth of a good in a settlement is its base worth scaled by how much of it the settlement has against
 * how much it would like to have ({@link #target}): empty stores make a thing worth two and a half times its
 * base, full stores bring it to par, and stores four times over leave it at a third. On top of that come the
 * settlement's circumstances: war makes food and arms dear, a famine makes food dearer still, winter raises
 * food, a rich settlement pays more for luxuries, and passing conditions (a strike of ore, a blight) move the
 * goods they touch. The merchant then takes a cut each way: the settlement gives a little less than the worth
 * for what it takes in ({@link #bid}) and asks a little more for what it hands out ({@link #ask}).
 */
public final class Prices {

    /** A settlement stops taking a good once it holds this many times what it wants. */
    public static final double GLUT = 4.0;

    private Prices() {}

    /** How much of a good a settlement would like to keep in its stores. */
    public static double target(Settlement s, String good) {
        TradeGoods.Item item = TradeGoods.get(good);
        if (item == null) return 0;
        double pop = Math.max(1, s.population);
        double workers = pop * CivSim.WORKERS;
        double t = 0;
        for (SettlementType.Industry i : s.type.industries) {
            // six days of what it makes, ten of what its workshops burn through
            if (i.out().equals(good)) t = Math.max(t, workers * i.share() * i.perWorker() * 6);
            Double in = i.inputs().get(good);
            if (in != null) t = Math.max(t, workers * i.share() * i.perWorker() * in * 10);
        }
        Double want = s.type.wants.get(good);
        if (want != null) t = Math.max(t, pop * want * 20);
        if (item.nutrition() > 0) t = Math.max(t, pop * CivSim.FOOD_PER_HEAD * 4 / item.nutrition());
        // anything else: a little, less the dearer it is
        t = Math.max(t, Math.max(2, pop * 0.6 / Math.pow(Math.max(0.2, item.worth()), 0.7)));
        if (s.type == SettlementType.TRADING_TOWN) t *= 2.5;
        return t;
    }

    /** Stock against target: 0 empty, 1 as much as wanted, above 1 more than wanted. */
    public static double fill(Settlement s, String good, double stock) {
        double t = target(s, good);
        return t <= 0 ? GLUT : stock / t;
    }

    static double scarcity(double fill) {
        return 2.5 / (1 + 1.5 * Math.max(0, fill));
    }

    /** What one more of a good is worth here when the stores hold {@code stock} of it. */
    public static double worth(CivWorld w, Settlement s, String good, double stock) {
        TradeGoods.Item item = TradeGoods.get(good);
        if (item == null) return 0;
        double v = item.worth() * scarcity(fill(s, good, stock));
        boolean food = item.nutrition() > 0 || item.cat() == TradeGoods.Cat.CROP;
        boolean arms = item.cat() == TradeGoods.Cat.WEAPON || item.cat() == TradeGoods.Cat.ARMOUR;
        if (w != null && s.civ != null && w.atWar(s.civ)) {
            if (arms) v *= 1.4;
            else if (food) v *= 1.2;
            else if (good.equals("iron_ingot") || good.equals("leather")) v *= 1.25;
        }
        if (food) {
            if (s.fed < 0.9) v *= 1 + (0.9 - s.fed);
            if (w != null && CivSim.winter(w.day)) v *= 1.15;
        }
        if (item.cat() == TradeGoods.Cat.RARE && s.population > 0) {
            // the rich pay for luxuries, the poor can't
            double perHead = CivWorld.wealth(s) / s.population;
            v *= Math.max(0.7, Math.min(1.4, 0.7 + perHead / 400));
        }
        if (w != null) v *= s.condition("price", good, w.day) * s.condition("price", item.cat().name(), w.day);
        return v;
    }

    /** The share the merchant keeps each way, eased by good standing and by what the civilisation knows of trade. */
    public static double spread(CivWorld w, Settlement s, double reputation) {
        double sp = s.type.spread;
        Civ c = w == null ? null : w.civs.get(s.civ);
        if (c != null) sp *= 1 - 0.06 * c.tech(Tech.TRADE);
        sp *= 1 - Math.max(-0.5, Math.min(0.4, reputation / 250));
        return Math.max(0.03, Math.min(0.6, sp));
    }

    /** What the settlement gives (in worth) for one more of a good brought to it. */
    public static double bid(CivWorld w, Settlement s, String good, double stock, double reputation) {
        return worth(w, s, good, stock) * (1 - spread(w, s, reputation));
    }

    /** What the settlement asks (in worth) for one of a good from its stores. */
    public static double ask(CivWorld w, Settlement s, String good, double stock, double reputation) {
        return worth(w, s, good, stock) * (1 + spread(w, s, reputation));
    }

    /** How many more of a good the settlement will take before its stores are glutted. */
    public static long accepts(Settlement s, String good) {
        if (!TradeGoods.known(good)) return 0;
        return Math.max(0, (long) Math.floor(target(s, good) * GLUT) - s.stock.get(good));
    }

    /** How many of a good the settlement will part with: what is free, less what it must keep for itself. */
    public static long spare(Settlement s, String good) {
        TradeGoods.Item item = TradeGoods.get(good);
        if (item == null) return 0;
        double keep = 0;
        if (item.nutrition() > 0) keep = s.population * CivSim.FOOD_PER_HEAD * 1.5 / item.nutrition() / 3;
        if (s.type.uses(good)) keep = Math.max(keep, target(s, good) * 0.25);
        return Math.max(0, s.stock.available(good) - (long) Math.ceil(keep));
    }

    /** The outcome of asking a settlement for a swap. */
    public record Quote(String error, String give, long giveQty, String want, long wantQty, double worthIn, double worthOut) {
        public boolean ok() {
            return error == null;
        }
    }

    private static Quote no(String why) {
        return new Quote(why, null, 0, null, 0, 0, 0);
    }

    /**
     * How many of {@code give} the settlement asks for {@code wantQty} of {@code want}. Every item is priced as
     * it changes hands: each one taken from the stores makes the next dearer, each one added makes the next
     * worth less, so a big swap costs more per item than a small one and buying a thing and selling it back
     * always loses.
     */
    public static Quote quote(CivWorld w, Settlement s, String give, String want, long wantQty, double reputation) {
        if (!TradeGoods.known(give) || !TradeGoods.known(want)) return no("They don't deal in that.");
        if (give.equals(want)) return no("That is the same thing.");
        if (wantQty <= 0) return no("How many?");
        if (wantQty > spare(s, want)) return no("They can only spare " + spare(s, want) + " " + TradeGoods.get(want).name() + ".");
        double cost = 0;
        long have = s.stock.get(want);
        for (long i = 0; i < wantQty; i++) cost += ask(w, s, want, have - i, reputation);
        long room = accepts(s, give);
        long stock = s.stock.get(give);
        double paid = 0;
        long n = 0;
        while (paid < cost) {
            if (n >= room) return no("They have no use for that much " + TradeGoods.get(give).name() + ".");
            double b = bid(w, s, give, stock + n, reputation);
            if (b <= 1e-9) return no("They have no use for " + TradeGoods.get(give).name() + ".");
            paid += b;
            n++;
            if (n > 100_000) return no("That would take more " + TradeGoods.get(give).name() + " than anyone could carry.");
        }
        return new Quote(null, give, n, want, wantQty, n * TradeGoods.worth(give), wantQty * TradeGoods.worth(want));
    }

    /** The most of {@code want} that {@code haveGive} of {@code give} will fetch (0 if not even one). */
    public static long affordable(CivWorld w, Settlement s, String give, long haveGive, String want, long upTo, double reputation) {
        long lo = 0, hi = Math.min(upTo, spare(s, want));
        while (lo < hi) {
            long mid = (lo + hi + 1) / 2;
            Quote q = quote(w, s, give, want, mid, reputation);
            if (q.ok() && q.giveQty() <= haveGive) lo = mid;
            else hi = mid - 1;
        }
        return lo;
    }

    /** A settlement's shortages (fill under 0.6) and surpluses (fill over 1.4 and spare), for caravans, contracts and menus. */
    public static void sort(Settlement s, Map<String, Double> shortages, Map<String, Double> surpluses) {
        for (TradeGoods.Item item : TradeGoods.all()) {
            String g = item.key();
            boolean cares = s.type.makes(g) || s.type.uses(g) || item.nutrition() > 0;
            double f = fill(s, g, s.stock.get(g));
            if (cares && f < 0.6 && !s.type.makes(g)) shortages.put(g, f);
            else if (f > 1.4 && spare(s, g) > 0) surpluses.put(g, f);
        }
    }
}
