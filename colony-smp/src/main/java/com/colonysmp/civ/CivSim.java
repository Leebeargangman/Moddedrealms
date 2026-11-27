package com.colonysmp.civ;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * One day in the life of every civilisation. Nothing here knows about the Minecraft server: it is arithmetic on
 * {@link CivWorld}, the same whether a settlement's chunks are loaded or a thousand blocks from anyone, which is
 * what lets the world go on living while nobody is watching (and lets it be tested without a server).
 *
 * <p>A day, in order: every settlement works, eats, and grows or shrinks; caravans arrive and new ones set out;
 * scholars study; rulers age; neighbours warm or cool to each other, sign and tear up treaties, and fight their
 * wars; and now and then something happens that nobody planned.
 *
 * <p>Everything is made from something. Goods enter the world only through an industry's daily output (bounded
 * by workers, inputs and full stores) and leave it by being eaten, used, lost or traded. Events, treaties and
 * technology change rates; they never conjure items.
 */
public final class CivSim {

    /** The share of a settlement's people who work. */
    public static final double WORKERS = 0.55;
    /** How much food (in nutrition) one person eats a day. */
    public static final double FOOD_PER_HEAD = 0.6;
    /**
     * The share of its food any settlement finds for itself without it ever passing through the stores: kitchen
     * gardens, foraging, the odd rabbit. Enough to keep a hungry place alive, not enough to keep it content.
     */
    public static final double FORAGE = 0.45;
    public static final int DAYS_PER_YEAR = 96;

    /** The knobs an administrator can turn (see the civilisations section of config.yml). */
    public static final class Config {
        public double production = 1, growth = 1, events = 1;
        public int maxPopulation = 220, maxSoldiers = 60, maxSettlementsPerCiv = 4;
        public double tradeRange = 2000, caravanSpeed = 500;
        public boolean wars = true, contracts = true;
    }

    private final Config cfg;
    /** Civilisations that want to found an outpost (the server finds them a site). */
    public final List<String> wantOutpost = new ArrayList<>();

    public CivSim(Config cfg) {
        this.cfg = cfg;
    }

    public Config config() {
        return cfg;
    }

    public static boolean winter(long day) {
        return Math.floorMod(day, DAYS_PER_YEAR) >= DAYS_PER_YEAR * 3 / 4;
    }

    public static String season(long day) {
        return switch ((int) (Math.floorMod(day, DAYS_PER_YEAR) / (DAYS_PER_YEAR / 4))) {
            case 0 -> "spring";
            case 1 -> "summer";
            case 2 -> "autumn";
            default -> "winter";
        };
    }

    /** Advances the world by whole days. */
    public void advance(CivWorld w, int days) {
        for (int i = 0; i < days; i++) step(w);
    }

    public void step(CivWorld w) {
        w.day++;
        Random rng = new Random(w.seed * 1_000_003L + w.day);
        long day = w.day;
        for (Settlement s : w.settlements.values()) s.conditions.removeIf(c -> c.untilDay() < day);
        w.treatyUntil.entrySet().removeIf(e -> {
            if (e.getValue() >= day) return false;
            String[] k = e.getKey().split("\\|");
            if (k.length == 3) {
                var set = w.treaties.get(k[0] + "|" + k[1]);
                if (set != null) {
                    set.remove(Treaty.valueOf(k[2]));
                    if (set.isEmpty()) w.treaties.remove(k[0] + "|" + k[1]);
                }
            }
            return true;
        });
        for (Settlement s : new ArrayList<>(w.settlements.values())) {
            if (!s.alive()) continue;
            work(w, s);
            live(w, s, rng);
            s.lastDay = day;
        }
        arrivals(w, rng);
        for (Settlement s : new ArrayList<>(w.settlements.values())) if (s.alive()) dispatch(w, s, rng);
        for (Civ c : new ArrayList<>(w.civs.values())) {
            if (w.of(c.id).isEmpty()) continue;
            study(w, c);
            rule(w, c, rng);
        }
        diplomacy(w, rng);
        if (cfg.wars) wars(w, rng);
        if (w.day % 7 == 0) tribute(w);
        for (Settlement s : new ArrayList<>(w.settlements.values())) if (s.alive()) happen(w, s, rng);
        if (cfg.contracts) contracts(w, rng);
    }

    // ───────────── work ─────────────

    private static Tech field(TradeGoods.Cat cat) {
        return switch (cat) {
            case FOOD, CROP, ANIMAL, FISH -> Tech.AGRICULTURE;
            case ORE, STONE -> Tech.MINING;
            case INGOT, TOOL, WEAPON, ARMOUR -> Tech.METALWORKING;
            case BUILDING, WOOD -> Tech.CONSTRUCTION;
            default -> Tech.INFRASTRUCTURE;
        };
    }

    /** Takes a possibly fractional amount of an input, carrying the fraction to tomorrow. */
    private static void use(Settlement s, String good, double qty) {
        double owed = s.partial.getOrDefault("-" + good, 0.0) + qty;
        long whole = (long) Math.floor(owed);
        if (whole > 0) owed -= s.stock.take(good, whole);
        s.partial.put("-" + good, Math.min(owed, 1));
    }

    private void work(CivWorld w, Settlement s) {
        Civ civ = w.civs.get(s.civ);
        s.output.clear();
        if (civ == null) return;
        double workers = Math.max(0, s.population - s.soldiers) * WORKERS;
        double base = cfg.production * (0.55 + 0.45 * s.happiness / 100) * (0.85 + 0.15 * s.supplied) * (0.85 + 0.15 * civ.government.levy);
        if (s.has("plague", w.day)) base *= 0.6;
        if (s.besiegedBy != null) base *= 0.6;
        for (SettlementType.Industry ind : s.type.industries) {
            TradeGoods.Item item = TradeGoods.get(ind.out());
            if (item == null) continue;
            double amount = workers * ind.share() * ind.perWorker() * base * (1 + 0.1 * civ.tech(field(item.cat())))
                    * s.condition("output", "*", w.day) * s.condition("output", item.key(), w.day) * s.condition("output", item.cat().name(), w.day);
            // full stores: the workers on this line are idle
            double room = Prices.target(s, item.key()) * Prices.GLUT - s.stock.get(item.key());
            amount = Math.min(amount, Math.max(0, room));
            for (Map.Entry<String, Double> in : ind.inputs().entrySet()) {
                double have = s.stock.available(in.getKey()) - s.partial.getOrDefault("-" + in.getKey(), 0.0);
                amount = Math.min(amount, Math.max(0, have) / in.getValue());
            }
            if (amount <= 1e-6) continue;
            for (Map.Entry<String, Double> in : ind.inputs().entrySet()) use(s, in.getKey(), amount * in.getValue());
            double made = s.partial.getOrDefault(item.key(), 0.0) + amount;
            long whole = (long) Math.floor(made);
            if (whole > 0) s.stock.force(item.key(), whole);
            s.partial.put(item.key(), made - whole);
            s.output.merge(item.key(), amount, Double::sum);
        }
    }

    // ───────────── living ─────────────

    private void live(CivWorld w, Settlement s, Random rng) {
        Civ civ = w.civs.get(s.civ);
        if (civ == null) return;
        long day = w.day;
        // food: whatever there is most of first, so the diet follows the stores
        double need = s.population * FOOD_PER_HEAD * (w.atWar(s.civ) ? 1.1 : 1);
        double eaten = need * FORAGE;
        int kinds = 0;
        List<TradeGoods.Item> foods = new ArrayList<>();
        for (TradeGoods.Item i : TradeGoods.all()) if (i.nutrition() > 0 && s.stock.available(i.key()) > 0) foods.add(i);
        foods.sort(Comparator.comparingDouble((TradeGoods.Item i) -> -s.stock.available(i.key()) * i.nutrition()));
        for (TradeGoods.Item i : foods) {
            if (eaten >= need) break;
            long want = (long) Math.ceil((need - eaten) / i.nutrition());
            long got = s.stock.take(i.key(), want);
            if (got > 0) {
                eaten += got * i.nutrition();
                kinds++;
            }
        }
        s.fed = need <= 0 ? 1 : Math.min(1, eaten / need);
        s.hungryDays = s.fed < 0.75 ? s.hungryDays + 1 : 0;
        // wants: tools, fuel, comforts
        double met = 0;
        int wants = 0;
        for (Map.Entry<String, Double> e : s.type.wants.entrySet()) {
            double q = s.population * e.getValue();
            double before = s.stock.available(e.getKey());
            met += before >= Math.max(1, q) ? 1 : before > 0 ? 0.5 : 0;
            wants++;
            use(s, e.getKey(), q);
        }
        s.supplied = wants == 0 ? 1 : met / wants;

        boolean war = w.atWar(s.civ);
        double mood = 55 + civ.government.happiness + (s.fed - 0.8) * 60 + (s.supplied - 0.5) * 12 + Math.min(3, kinds) * 2
                - (civ.government.levy - 1) * 20 - (war ? 8 : 0) - (s.has("plague", day) ? 15 : 0) - (s.besiegedBy != null ? 12 : 0);
        for (Settlement.Condition c : s.conditions) if (c.kind().equals("mood")) mood += c.factor();
        s.happiness = clamp(s.happiness + (clamp(mood, 0, 100) - s.happiness) * 0.2, 0, 100);
        double steady = 50 + (s.happiness - 50) * 0.6 + (civ.government.stability - 1) * 25 + Math.min(10, (day - civ.leader.since) / (double) DAYS_PER_YEAR * 2)
                - civ.unrest * 0.5 - (war ? 5 : 0);
        s.stability = clamp(s.stability + (clamp(steady, 0, 100) - s.stability) * 0.15, 0, 100);

        int cap = s.capacity(civ.tech(Tech.CONSTRUCTION), cfg.maxPopulation);
        double pop = s.population;
        if (s.has("plague", day)) pop -= pop * 0.02 * (1 - 0.15 * civ.tech(Tech.MEDICINE));
        if (s.fed < 0.6) pop -= pop * 0.06 * (0.6 - s.fed) / 0.6;
        else if (s.fed >= 0.9 && s.happiness >= 45 && pop < cap) {
            pop += Math.max(0.05, pop * 0.012 * cfg.growth * (1 + 0.1 * civ.tech(Tech.MEDICINE)) * s.happiness / 60);
        }
        if (pop > cap) pop -= (pop - cap) * 0.05;
        if (s.happiness < 20) pop -= pop * 0.01; // they leave
        if (civ.outlaw && pop < 30) pop += 0.15; // there is always another drifter looking for a camp
        s.population = Math.max(0, pop);

        double garrison = Math.min(cfg.maxSoldiers, Math.min(s.population * 0.4, s.population * s.type.garrison * civ.government.recruitment * (war ? 2 : 1)));
        s.soldiers = Math.min(s.population, s.soldiers + clamp(garrison - s.soldiers, -Math.max(0.3, s.soldiers * 0.05), Math.max(0.3, s.population * 0.02)));

        if (s.level < 4 && s.population >= cap * 0.9) {
            long stone = s.stock.available("cobblestone") + s.stock.available("stone") + s.stock.available("stone_bricks");
            long wood = s.stock.available("oak_planks") + 4 * (s.stock.available("oak_log") + s.stock.available("spruce_log"));
            long needEach = 60L * s.level;
            if (stone >= needEach && wood >= needEach) {
                spend(s, needEach, "stone_bricks", "stone", "cobblestone");
                long left = needEach - s.stock.take("oak_planks", needEach);
                if (left > 0) left -= 4 * s.stock.take("oak_log", (left + 3) / 4);
                if (left > 0) s.stock.take("spruce_log", (left + 3) / 4);
                s.level++;
                civ.record(day, s.name + " grew into a " + s.title() + ".");
                w.news(s.id, s.civ, 2, s.name + " has grown into a " + s.title() + ".");
            }
        }
        if (s.population < 4) fall(w, s, s.hungryDays > 3 ? "starved" : "was deserted");
    }

    private static void spend(Settlement s, long qty, String... goods) {
        for (String g : goods) {
            if (qty <= 0) return;
            qty -= s.stock.take(g, qty);
        }
    }

    /** A settlement is emptied: it becomes a ruin, and a civilisation with nothing left falls. */
    public void fall(CivWorld w, Settlement s, String how) {
        if (!s.alive()) return;
        s.state = Settlement.State.RUINED;
        s.population = 0;
        s.soldiers = 0;
        s.besiegedBy = null;
        Civ civ = w.civs.get(s.civ);
        String whose = civ == null ? "" : " of " + civ.name;
        if (civ != null) civ.record(w.day, s.name + " " + how + " and lies in ruins.");
        w.news(s.id, s.civ, 3, "The " + s.type.noun + " of " + s.name + whose + " " + how + ". Only ruins remain.");
        for (Contract c : w.contracts.values()) if (c.settlement.equals(s.id) && (c.state == Contract.State.OPEN || c.state == Contract.State.TAKEN)) c.state = Contract.State.EXPIRED;
        if (civ != null && w.of(civ.id).isEmpty()) {
            civ.record(w.day, "The last hearth of " + civ.name + " went cold.");
            w.news(null, civ.id, 3, civ.name + " has fallen. Its halls stand empty.");
            w.wars.values().removeIf(x -> x.involves(civ.id));
        } else if (civ != null && s.capital) {
            s.capital = false;
            Settlement next = w.capital(civ.id);
            if (next != null) next.capital = true;
        }
    }

    // ───────────── caravans ─────────────

    private boolean mayTrade(CivWorld w, Settlement a, Settlement b) {
        if (a == b || !b.alive() || !a.world.equals(b.world)) return false;
        if (a.besiegedBy != null || b.besiegedBy != null) return false;
        if (a.civ.equals(b.civ)) return true;
        Civ ca = w.civs.get(a.civ), cb = w.civs.get(b.civ);
        if (ca == null || cb == null || ca.outlaw || cb.outlaw) return false;
        return w.war(a.civ, b.civ) == null && !w.has(a.civ, b.civ, Treaty.EMBARGO) && w.relation(a.civ, b.civ) > -40;
    }

    private void dispatch(CivWorld w, Settlement s, Random rng) {
        Civ civ = w.civs.get(s.civ);
        if (civ == null || civ.outlaw || s.has("plague", w.day)) return;
        for (Shipment sh : w.shipments.values()) if (sh.from.equals(s.id)) return;
        if (rng.nextDouble() > 0.6 * s.type.trade * civ.leader.personality.trade * civ.government.trade) return;
        Map<String, Double> shortages = new LinkedHashMap<>(), surpluses = new LinkedHashMap<>();
        Prices.sort(s, shortages, surpluses);
        if (surpluses.isEmpty()) return;
        Settlement best = null;
        double bestScore = 0;
        for (Settlement t : w.settlements.values()) {
            if (!mayTrade(w, s, t)) continue;
            double d = s.distance(t);
            if (d > cfg.tradeRange * s.type.trade) continue;
            double score = 0;
            for (String g : surpluses.keySet()) {
                double f = Prices.fill(t, g, t.stock.get(g));
                if (f < 1) score += (1 - f) * TradeGoods.worth(g) * Math.min(Prices.spare(s, g), Prices.accepts(t, g));
            }
            // a caravan that would come home empty isn't worth sending
            double back = 0;
            for (String g : shortages.keySet()) back += Prices.spare(t, g) * TradeGoods.worth(g);
            if (score <= 0 || back <= 0 && !s.civ.equals(t.civ)) continue;
            if (w.has(s.civ, t.civ, Treaty.TRADE_AGREEMENT)) score *= 1.5;
            if (s.civ.equals(t.civ)) score *= 1.3;
            score /= 1 + d / 800;
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        if (best == null) return;
        Settlement to = best;
        boolean sea = s.type.coastal() && to.type.coastal();
        double capacity = 160 * s.type.trade * (1 + 0.2 * civ.tech(Tech.TRADE)) * (sea ? 1.5 : 1) * (w.has(s.civ, to.civ, Treaty.TRADE_AGREEMENT) ? 1.5 : 1);
        List<String> goods = new ArrayList<>(surpluses.keySet());
        goods.sort(Comparator.comparingDouble((String g) -> Prices.fill(to, g, to.stock.get(g))));
        Shipment sh = new Shipment(w.newId("c"));
        for (String g : goods) {
            if (capacity <= 0) break;
            if (Prices.fill(to, g, to.stock.get(g)) >= 1) continue;
            long q = Math.min(Math.min(Prices.spare(s, g), Prices.accepts(to, g)), (long) (capacity / TradeGoods.worth(g)));
            q = s.stock.take(g, q);
            if (q <= 0) continue;
            sh.cargo.put(g, q);
            capacity -= q * TradeGoods.worth(g);
        }
        if (sh.cargo.isEmpty()) return;
        sh.from = s.id;
        sh.to = to.id;
        sh.bySea = sea;
        sh.departDay = w.day;
        sh.arriveDay = w.day + travel(civ, s.distance(to), sea);
        w.shipments.put(sh.id, sh);
    }

    private long travel(Civ civ, double distance, boolean sea) {
        double speed = cfg.caravanSpeed * (1 + 0.15 * civ.tech(Tech.TRANSPORT)) * (sea ? 2 : 1);
        return Math.max(1, (long) Math.ceil(distance / speed));
    }

    private void arrivals(CivWorld w, Random rng) {
        for (Shipment sh : new ArrayList<>(w.shipments.values())) {
            Settlement from = w.settlements.get(sh.from), to = w.settlements.get(sh.to);
            if (from == null || to == null) {
                w.shipments.remove(sh.id);
                continue;
            }
            if (sh.arriveDay > w.day) {
                waylay(w, sh, from, to, rng);
                continue;
            }
            if (sh.stage == Shipment.Stage.RETURNING) {
                if (from.alive()) sh.cargo.forEach(from.stock::force);
                w.shipments.remove(sh.id);
                continue;
            }
            Civ home = w.civs.get(from.civ);
            if (!to.alive() || !mayTrade(w, from, to) || home == null) {
                // the gates were shut: it turns for home with everything it brought
                sh.stage = Shipment.Stage.RETURNING;
                sh.arriveDay = w.day + Math.max(1, sh.arriveDay - sh.departDay);
                continue;
            }
            barter(w, sh, from, to);
            sh.stage = Shipment.Stage.RETURNING;
            sh.arriveDay = w.day + travel(home, from.distance(to), sh.bySea);
            if (!from.civ.equals(to.civ)) w.adjustRelation(from.civ, to.civ, 0.4);
        }
    }

    /** The caravan swaps what it brought for what its home lacks, at the far settlement's rates. */
    private void barter(CivWorld w, Shipment sh, Settlement from, Settlement to) {
        double credit = 0;
        Map<String, Long> left = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : sh.cargo.entrySet()) {
            String g = e.getKey();
            long q = Math.min(e.getValue(), Prices.accepts(to, g));
            if (q > 0) {
                credit += q * Prices.bid(w, to, g, to.stock.get(g) + q / 2.0, 0);
                to.stock.force(g, q);
            }
            if (e.getValue() > q) left.put(g, e.getValue() - q);
        }
        sh.cargo.clear();
        sh.cargo.putAll(left);
        Map<String, Double> shortages = new LinkedHashMap<>(), ignore = new LinkedHashMap<>();
        Prices.sort(from, shortages, ignore);
        List<String> wanted = new ArrayList<>();
        for (TradeGoods.Item i : TradeGoods.all()) {
            if (Prices.spare(to, i.key()) > 0 && Prices.fill(from, i.key(), from.stock.get(i.key())) < 1 && (from.type.uses(i.key()) || i.nutrition() > 0)) wanted.add(i.key());
        }
        wanted.sort(Comparator.comparingDouble((String g) -> Prices.fill(from, g, from.stock.get(g))));
        for (String g : wanted) {
            if (credit <= 0) break;
            double ask = Prices.ask(w, to, g, to.stock.get(g), 0);
            if (ask <= 0) continue;
            long q = Math.min(Math.min(Prices.spare(to, g), Prices.accepts(from, g)), (long) (credit / ask));
            q = to.stock.take(g, q);
            if (q <= 0) continue;
            sh.cargo.merge(g, q, Long::sum);
            credit -= q * ask;
        }
    }

    /** Bandits on the road: a caravan passing near an outlaw camp may lose its cargo to it. */
    private void waylay(CivWorld w, Shipment sh, Settlement from, Settlement to, Random rng) {
        if (sh.bySea || sh.cargo.isEmpty()) return;
        double mx = (from.x + to.x) / 2.0, mz = (from.z + to.z) / 2.0;
        for (Settlement b : w.settlements.values()) {
            Civ bc = w.civs.get(b.civ);
            if (!b.alive() || bc == null || !bc.outlaw || !b.world.equals(from.world)) continue;
            double dx = b.x - mx, dz = b.z - mz;
            if (dx * dx + dz * dz > 500 * 500) continue;
            Civ home = w.civs.get(from.civ);
            double guard = home == null ? 0 : 0.1 * home.tech(Tech.DEFENCE);
            if (rng.nextDouble() > 0.06 * (1 - guard) * Math.min(2, b.soldiers / 6 + 0.5)) continue;
            double worth = sh.worth();
            sh.cargo.forEach((g, q) -> b.stock.force(g, q / 2)); // half is spoiled or scattered in the fight
            w.shipments.remove(sh.id);
            if (home != null) home.record(w.day, "A caravan from " + from.name + " to " + to.name + " was robbed by " + b.name + ".");
            w.news(from.id, from.civ, 2, "A caravan from " + from.name + " bound for " + to.name + " was robbed on the road by the outlaws of " + b.name
                    + " (" + Math.round(worth) + " worth of goods).");
            from.add("mood", "*", -4, w.day + 4);
            return;
        }
    }

    // ───────────── learning ─────────────

    private void study(CivWorld w, Civ c) {
        if (c.outlaw) return;
        double points = 0;
        for (Settlement s : w.of(c.id)) points += s.population * 0.012 * (s.level >= 2 ? 1.5 : 1) * (s.type == SettlementType.RELIGIOUS_SETTLEMENT ? 2 : 1);
        if (c.researching == null || c.tech(c.researching) >= Tech.MAX) {
            Tech pick = null;
            double best = -1;
            for (Tech t : Tech.values()) {
                if (c.tech(t) >= Tech.MAX) continue;
                double want = 1.0 / (1 + c.tech(t));
                for (Settlement s : w.of(c.id)) {
                    want += switch (t) {
                        case AGRICULTURE -> s.type == SettlementType.FARMING_VILLAGE || s.fed < 0.9 ? 0.6 : 0;
                        case MINING -> s.type == SettlementType.MINING_SETTLEMENT ? 0.6 : 0;
                        case METALWORKING -> s.type == SettlementType.INDUSTRIAL_TOWN || s.type == SettlementType.MILITARY_SETTLEMENT ? 0.6 : 0;
                        case MILITARY, DEFENCE -> w.atWar(c.id) ? 0.8 : c.leader.personality.war > 1.2 ? 0.4 : 0;
                        case TRADE, TRANSPORT -> s.type == SettlementType.TRADING_TOWN || s.type.coastal() ? 0.5 : 0;
                        case MEDICINE -> s.has("plague", w.day) ? 1 : 0;
                        case CONSTRUCTION -> s.population > s.capacity(c.tech(Tech.CONSTRUCTION), cfg.maxPopulation) * 0.8 ? 0.6 : 0;
                        default -> 0.1;
                    };
                }
                if (want > best) {
                    best = want;
                    pick = t;
                }
            }
            c.researching = pick;
            c.research = 0;
            if (pick == null) return;
        }
        int next = c.tech(c.researching) + 1;
        c.research = Math.min(Tech.cost(next), c.research + points);
        if (c.research < Tech.cost(next)) return;
        // the last step needs books: learning is written down or it is lost
        Settlement cap = w.capital(c.id);
        int books = Tech.books(next);
        if (cap == null || cap.stock.available("book") < books) return;
        cap.stock.take("book", books);
        c.tech.put(c.researching, next);
        c.record(w.day, "Scholars mastered " + c.researching.display + " " + next + ": " + c.researching.effect + ".");
        w.news(cap.id, c.id, 1, "The scholars of " + c.name + " have advanced in " + c.researching.display + ".");
        c.researching = null;
        c.research = 0;
    }

    // ───────────── rulers ─────────────

    private void rule(CivWorld w, Civ c, Random rng) {
        Leader l = c.leader;
        l.age += 1.0 / DAYS_PER_YEAR;
        double stab = 0;
        List<Settlement> mine = w.of(c.id);
        for (Settlement s : mine) stab += s.stability;
        stab /= Math.max(1, mine.size());
        c.unrest = clamp(c.unrest + (stab < 35 ? (35 - stab) / 10 : -0.5), 0, 100);
        if (l.heir == null && c.government.hereditary() && l.age > 24 && rng.nextDouble() < 0.02) {
            l.heir = Names.person(rng);
            l.heirPersonality = rng.nextDouble() < 0.5 ? l.personality : Personality.values()[rng.nextInt(Personality.values().length)];
        }
        double death = l.age > 55 ? Math.pow(l.age - 55, 2) / 40000 : 0.00005;
        if (rng.nextDouble() < death) {
            succeed(w, c, rng, l.name + " died at " + (int) l.age, false);
            return;
        }
        if (!c.outlaw && rng.nextDouble() < 0.0004 * cfg.events * (100 - stab) / 50) {
            String worst = null;
            double low = -20;
            for (Civ o : w.civs.values()) {
                if (o == c || w.of(o.id).isEmpty()) continue;
                if (w.relation(c.id, o.id) < low) {
                    low = w.relation(c.id, o.id);
                    worst = o.id;
                }
            }
            succeed(w, c, rng, l.name + " was assassinated", false);
            if (worst != null) {
                w.adjustRelation(c.id, worst, -30);
                c.record(w.day, "Many blame " + w.civs.get(worst).name + " for the killing.");
            }
            return;
        }
        if (c.unrest > 40 && rng.nextDouble() < 0.08 * cfg.events) rebel(w, c, rng);
        // a thriving people look for new ground
        if (!c.outlaw && w.day % 12 == Math.floorMod(c.id.hashCode(), 12) && mine.size() < cfg.maxSettlementsPerCiv) {
            Settlement cap = w.capital(c.id);
            if (cap != null && cap.level >= 2 && cap.population >= cap.capacity(c.tech(Tech.CONSTRUCTION), cfg.maxPopulation) * 0.8
                    && rng.nextDouble() < 0.2 * l.personality.growth * c.government.expansion && !wantOutpost.contains(c.id)) {
                wantOutpost.add(c.id);
            }
        }
    }

    /** The seat passes on: to the heir where rule is inherited, otherwise to whoever is chosen or seizes it. */
    public void succeed(CivWorld w, Civ c, Random rng, String why, boolean overthrown) {
        Leader old = c.leader;
        Leader next = new Leader();
        boolean dynasty = !overthrown && c.government.hereditary() && old.heir != null;
        if (dynasty) {
            next.name = old.heir;
            next.house = old.house;
            next.personality = old.heirPersonality == null ? old.personality : old.heirPersonality;
            next.generation = old.generation + 1;
            next.age = 18 + rng.nextInt(18);
        } else {
            next.name = Names.person(rng);
            next.house = Names.house(rng);
            next.personality = Personality.values()[rng.nextInt(Personality.values().length)];
            next.age = 28 + rng.nextInt(22);
        }
        next.since = w.day;
        c.leader = next;
        String how = dynasty ? next.name + " of House " + next.house + " inherits (the " + ordinal(next.generation) + " of that house to rule)"
                : c.government.hereditary() && !overthrown ? "there was no heir: " + next.name + " of House " + next.house + " took the seat after a dispute"
                : next.name + " was " + (overthrown ? "raised up" : "chosen") + " in their place";
        c.record(w.day, why + "; " + how + ".");
        w.news(null, c.id, 2, c.name + ": " + why + ". " + Character.toUpperCase(how.charAt(0)) + how.substring(1) + ".");
        // a new hand on the reins is an unsteady one, most of all when nobody agreed on it
        double shake = dynasty ? 4 : c.government.hereditary() && !overthrown ? 14 : 8;
        for (Settlement s : w.of(c.id)) s.stability = clamp(s.stability - shake, 0, 100);
    }

    private void rebel(CivWorld w, Civ c, Random rng) {
        List<Settlement> mine = w.of(c.id);
        if (mine.isEmpty()) return;
        if (mine.size() >= 3 && c.unrest > 60) {
            // civil war: the furthest settlement breaks away under its own banner
            Settlement cap = w.capital(c.id);
            Settlement far = null;
            for (Settlement s : mine) if (s != cap && (far == null || s.distance(cap) > far.distance(cap))) far = s;
            if (far != null) {
                Civ free = CivFactory.civ(w, far.type, rng);
                free.name = "Free " + far.name;
                free.adjective = far.name;
                far.civ = free.id;
                far.capital = true;
                w.adjustRelation(c.id, free.id, -50);
                c.record(w.day, far.name + " rose in revolt and broke away as " + free.name + ".");
                free.record(w.day, "Broke away from " + c.name + " in the civil war.");
                w.news(far.id, c.id, 3, "Civil war in " + c.name + ": " + far.name + " has broken away and calls itself " + free.name + ".");
                c.unrest = 20;
                return;
            }
        }
        Government old = c.government;
        Government[] all = Government.values();
        Government next = all[rng.nextInt(all.length)];
        if (next == old) next = all[(next.ordinal() + 1) % all.length];
        c.government = next;
        for (Settlement s : mine) {
            s.population *= 0.95;
            s.stability = 45;
        }
        c.unrest = 0;
        c.record(w.day, "The people rose against the " + old.display.toLowerCase() + " and set up a " + next.display.toLowerCase() + ".");
        succeed(w, c, rng, "Rebellion overthrew " + c.ruler(), true);
    }

    private static String ordinal(int n) {
        return n + (n % 100 >= 11 && n % 100 <= 13 ? "th" : switch (n % 10) { case 1 -> "st"; case 2 -> "nd"; case 3 -> "rd"; default -> "th"; });
    }

    // ───────────── neighbours ─────────────

    private void diplomacy(CivWorld w, Random rng) {
        List<Civ> civs = new ArrayList<>();
        for (Civ c : w.civs.values()) if (!c.outlaw && !w.of(c.id).isEmpty()) civs.add(c);
        for (int i = 0; i < civs.size(); i++) {
            for (int j = i + 1; j < civs.size(); j++) {
                Civ a = civs.get(i), b = civs.get(j);
                double near = Double.MAX_VALUE;
                for (Settlement x : w.of(a.id)) for (Settlement y : w.of(b.id)) near = Math.min(near, x.distance(y));
                if (near > cfg.tradeRange * 3) continue; // they have never met
                Personality pa = a.leader.personality, pb = b.leader.personality;
                double r = w.relation(a.id, b.id);
                // where they would settle left alone: peaceable rulers lean friendly, warlike ones cold
                double rest = (pa.peace + pb.peace - pa.war - pb.war) * 8 + (a.government == b.government ? 6 : 0);
                double drift = (rest - r) * 0.015;
                if (near < 700 && (pa.growth > 1.3 || pb.growth > 1.3 || pa.war > 1.3 || pb.war > 1.3)) drift -= 0.35; // crowded borders
                for (War war : w.wars.values()) {
                    if (war.involves(a.id) && !war.involves(b.id) && w.war(b.id, war.other(a.id)) != null) drift += 0.5; // the enemy of my enemy
                }
                w.adjustRelation(a.id, b.id, drift);
                if (Math.floorMod(w.day + (a.id + b.id).hashCode(), 4) != 0) continue;
                r = w.relation(a.id, b.id);
                double keen = (a.government.diplomacy + b.government.diplomacy) / 2;
                if (w.war(a.id, b.id) != null) continue;
                if (r >= 35 && !w.has(a.id, b.id, Treaty.TRADE_AGREEMENT) && !w.has(a.id, b.id, Treaty.EMBARGO) && rng.nextDouble() < 0.4 * keen) {
                    sign(w, a, b, Treaty.TRADE_AGREEMENT, 0, 2);
                } else if (r >= 55 && !w.has(a.id, b.id, Treaty.NON_AGGRESSION) && rng.nextDouble() < 0.3 * keen) {
                    sign(w, a, b, Treaty.NON_AGGRESSION, 0, 1);
                } else if (r >= 75 && !w.has(a.id, b.id, Treaty.ALLIANCE) && rng.nextDouble() < 0.2 * keen) {
                    sign(w, a, b, Treaty.ALLIANCE, 0, 3);
                    w.sign(a.id, b.id, Treaty.MILITARY_PACT, 0);
                } else if (r < 50 && w.has(a.id, b.id, Treaty.ALLIANCE)) {
                    w.tear(a.id, b.id, Treaty.ALLIANCE);
                    w.tear(a.id, b.id, Treaty.MILITARY_PACT);
                    note(w, a, b, 2, "The alliance between " + a.name + " and " + b.name + " has been dissolved.");
                } else if (r <= -35 && !w.has(a.id, b.id, Treaty.EMBARGO)) {
                    w.tear(a.id, b.id, Treaty.TRADE_AGREEMENT);
                    w.tear(a.id, b.id, Treaty.NON_AGGRESSION);
                    sign(w, a, b, Treaty.EMBARGO, 0, 2);
                } else if (r > -15 && w.has(a.id, b.id, Treaty.EMBARGO)) {
                    w.tear(a.id, b.id, Treaty.EMBARGO);
                    note(w, a, b, 1, a.name + " and " + b.name + " have lifted their embargo.");
                } else if (cfg.wars && r <= -60 && !w.has(a.id, b.id, Treaty.PEACE)) {
                    double sa = w.strength(a.id), sb = w.strength(b.id);
                    Civ agg = pa.war * sa >= pb.war * sb ? a : b, def = agg == a ? b : a;
                    double edge = w.strength(agg.id) / Math.max(1, w.strength(def.id));
                    if (w.atWar(agg.id) || rng.nextDouble() > 0.1 * agg.leader.personality.war * (edge >= 1.2 ? 1.5 : 0.4)) continue;
                    declare(w, agg, def, cause(w, agg, def, near, rng));
                }
            }
        }
    }

    private void sign(CivWorld w, Civ a, Civ b, Treaty t, long until, int weight) {
        w.sign(a.id, b.id, t, until);
        note(w, a, b, weight, a.name + " and " + b.name + " have " + (t == Treaty.EMBARGO ? "declared an embargo on each other" : "signed " + article(t.display) + ": " + t.meaning) + ".");
    }

    private static String article(String s) {
        return ("aeiou".indexOf(s.charAt(0)) >= 0 ? "an " : "a ") + s;
    }

    private void note(CivWorld w, Civ a, Civ b, int weight, String text) {
        a.record(w.day, text);
        b.record(w.day, text);
        w.news(null, a.id, weight, text);
    }

    private String cause(CivWorld w, Civ agg, Civ def, double near, Random rng) {
        Settlement cap = w.capital(agg.id);
        if (cap != null && cap.fed < 0.8) return "hunger: " + agg.name + " wants " + def.name + "'s granaries";
        if (near < 700) return "a disputed border";
        if (agg.government == Government.THEOCRACY || def.government == Government.THEOCRACY) return "a quarrel of faiths";
        return switch (rng.nextInt(5)) {
            case 0 -> "old insults never forgiven";
            case 1 -> "competition for the same trade routes";
            case 2 -> "a broken promise";
            case 3 -> "raids along the frontier";
            default -> agg.ruler() + "'s ambition";
        };
    }

    /** One civilisation goes to war with another (also used by administrators). */
    public War declare(CivWorld w, Civ agg, Civ def, String cause) {
        if (w.war(agg.id, def.id) != null) return w.war(agg.id, def.id);
        War war = new War(w.newId("w"));
        war.attacker = agg.id;
        war.defender = def.id;
        war.cause = cause;
        war.startDay = w.day;
        w.wars.put(war.id, war);
        for (Treaty t : Treaty.values()) w.tear(agg.id, def.id, t);
        w.relations.put(CivWorld.pair(agg.id, def.id), Math.min(-70, w.relation(agg.id, def.id)));
        note(w, agg, def, 3, agg.name + " has declared war on " + def.name + " over " + cause + ".");
        // those who break their word are remembered for it
        for (Civ o : w.civs.values()) if (o != agg && o != def && w.relation(o.id, def.id) > 40) w.adjustRelation(o.id, agg.id, -15);
        return war;
    }

    private double side(CivWorld w, String civ, String against) {
        double s = w.strength(civ);
        for (Civ o : w.civs.values()) {
            if (o.id.equals(civ) || o.id.equals(against)) continue;
            if (w.has(civ, o.id, Treaty.ALLIANCE) || w.has(civ, o.id, Treaty.MILITARY_PACT)) s += 0.3 * w.strength(o.id);
        }
        return s;
    }

    private void wars(CivWorld w, Random rng) {
        for (War war : new ArrayList<>(w.wars.values())) {
            Civ a = w.civs.get(war.attacker), d = w.civs.get(war.defender);
            if (a == null || d == null || w.of(a.id).isEmpty() || w.of(d.id).isEmpty()) {
                w.wars.remove(war.id);
                continue;
            }
            double sa = side(w, a.id, d.id), sd = side(w, d.id, a.id) * (1 + 0.1 * d.tech(Tech.DEFENCE));
            double la = sd * 0.03 * (0.5 + rng.nextDouble()), ld = sa * 0.03 * (0.5 + rng.nextDouble());
            bleed(w, a.id, la);
            bleed(w, d.id, ld);
            war.attackerLosses += la;
            war.defenderLosses += ld;
            war.score += (sa - sd) / (sa + sd + 1) * 5 + (rng.nextDouble() - 0.5) * 3;
            // the front line: the defender's nearest settlement is cut off once the attacker has the upper hand
            Settlement front = null, base = w.capital(a.id);
            for (Settlement s : w.of(d.id)) if (base != null && (front == null || s.distance(base) < front.distance(base))) front = s;
            for (Settlement s : w.of(d.id)) if (a.id.equals(s.besiegedBy) && (war.score < 15 || s != front)) s.besiegedBy = null;
            if (front != null && war.score > 25 && front.besiegedBy == null) {
                front.besiegedBy = a.id;
                note(w, a, d, 2, "The army of " + a.name + " has laid siege to " + front.name + ".");
            }
            long days = w.day - war.startDay;
            double tired = (a.leader.personality.peace + d.leader.personality.peace) / 2;
            if (days < 4) continue; // armies take time to march
            if (war.score > 55 || w.strength(d.id) < 0.15 * Math.max(1, sa)) end(w, war, a, d, front, war.score > 75);
            else if (war.score < -40) end(w, war, d, a, null, false);
            else if (days > 40 / Math.max(0.5, 2 - tired) && rng.nextDouble() < 0.1 * tired) end(w, war, null, null, null, false);
        }
    }

    /** Soldiers lost in a day's fighting (they are people too), and the arms and rations a war eats. */
    private void bleed(CivWorld w, String civ, double losses) {
        double total = Math.max(1, w.soldiers(civ));
        for (Settlement s : w.of(civ)) {
            double share = losses * s.soldiers / total;
            s.soldiers = Math.max(0, s.soldiers - share);
            s.population = Math.max(0, s.population - share);
            use(s, "iron_sword", share * 0.4);
            use(s, "arrow", s.soldiers * 0.2);
            use(s, "bread", s.soldiers * 0.15);
        }
    }

    /** A war ends: with a winner taking reparations (and perhaps the besieged settlement), or with both sides spent. */
    public void end(CivWorld w, War war, Civ winner, Civ loser, Settlement prize, boolean capture) {
        w.wars.remove(war.id);
        Civ a = w.civs.get(war.attacker), d = w.civs.get(war.defender);
        if (a == null || d == null) return;
        for (Settlement s : w.settlements.values()) if (a.id.equals(s.besiegedBy) || d.id.equals(s.besiegedBy)) s.besiegedBy = null;
        w.sign(a.id, d.id, Treaty.PEACE, w.day + 40);
        w.relations.put(CivWorld.pair(a.id, d.id), -35.0);
        if (winner == null || loser == null) {
            note(w, a, d, 3, "The war between " + a.name + " and " + d.name + " has ended in exhaustion. Neither side won.");
            return;
        }
        Settlement from = w.capital(loser.id), to = w.capital(winner.id);
        StringBuilder took = new StringBuilder();
        if (from != null && to != null) {
            List<String> goods = new ArrayList<>(from.stock.contents().keySet());
            goods.sort(Comparator.comparingDouble((String g) -> -TradeGoods.worth(g) * from.stock.available(g)));
            for (int i = 0; i < Math.min(5, goods.size()); i++) {
                long q = from.stock.take(goods.get(i), from.stock.available(goods.get(i)) / 4);
                if (q <= 0) continue;
                to.stock.force(goods.get(i), q);
                if (took.length() < 60) took.append(took.isEmpty() ? "" : ", ").append(q).append(' ').append(goods.get(i).replace('_', ' '));
            }
        }
        String text = winner.name + " has defeated " + loser.name + (took.isEmpty() ? "" : " and carried off " + took) + ".";
        if (capture && prize != null && prize.alive() && prize.civ.equals(loser.id) && w.of(loser.id).size() > 1) {
            prize.civ = winner.id;
            prize.capital = false;
            prize.stability = 30;
            prize.happiness = Math.min(prize.happiness, 35);
            text += " " + prize.name + " now flies " + winner.name + "'s banner.";
            Settlement next = w.capital(loser.id);
            if (next != null) next.capital = true;
        } else {
            w.sign(winner.id, loser.id, Treaty.TRIBUTE, w.day + 28);
            w.tributePayer.put(CivWorld.pair(winner.id, loser.id), loser.id);
        }
        note(w, winner, loser, 3, text);
        loser.unrest += 15;
        // the beaten flee: some of them turn up at a neighbour's gate
        Settlement lost = prize != null ? prize : from;
        if (lost != null) {
            for (Settlement s : w.settlements.values()) {
                if (!s.alive() || s.civ.equals(loser.id) || s.civ.equals(winner.id) || s.distance(lost) > cfg.tradeRange) continue;
                double n = Math.min(8, lost.population * 0.05);
                if (n < 1) break;
                lost.population -= n;
                s.population += n;
                w.news(s.id, s.civ, 1, Math.round(n) + " refugees from the war have arrived at " + s.name + ".");
                break;
            }
        }
    }

    /** Each week the beaten send a share of what they can spare to those who beat them, while the treaty lasts. */
    private void tribute(CivWorld w) {
        w.tributePayer.entrySet().removeIf(e -> {
            String[] p = e.getKey().split("\\|");
            return p.length != 2 || !w.has(p[0], p[1], Treaty.TRIBUTE);
        });
        for (Map.Entry<String, String> e : w.tributePayer.entrySet()) {
            String[] p = e.getKey().split("\\|");
            String payer = e.getValue(), taker = p[0].equals(payer) ? p[1] : p[0];
            Settlement from = w.capital(payer), to = w.capital(taker);
            if (from == null || to == null) continue;
            for (TradeGoods.Item i : TradeGoods.all()) {
                long q = Prices.spare(from, i.key()) / 20;
                if (q > 0) to.stock.force(i.key(), from.stock.take(i.key(), q));
            }
        }
    }

    // ───────────── things that happen ─────────────

    private void happen(CivWorld w, Settlement s, Random rng) {
        Civ c = w.civs.get(s.civ);
        if (c == null) return;
        long day = w.day;
        double f = cfg.events;
        double roll = rng.nextDouble();
        boolean farms = s.type.makes("wheat") || s.type.makes("carrot") || s.type.makes("potato");
        boolean mines = s.type.makes("raw_iron") || s.type.makes("coal");
        int cap = s.capacity(c.tech(Tech.CONSTRUCTION), cfg.maxPopulation);
        if ((roll -= 0.004 * f * (winter(day) ? 2 : 1)) < 0 && farms && !s.has("blight", day)) {
            s.add("blight", "*", 1, day + 8);
            s.add("output", "CROP", 0.35, day + 8);
            s.add("output", "FOOD", 0.6, day + 8);
            tell(w, s, c, 2, "Blight has struck the fields of " + s.name + ". The harvest is failing and food grows dear.");
        } else if ((roll -= 0.002 * f * s.population / 60 * (1 - 0.15 * c.tech(Tech.MEDICINE))) < 0 && !s.has("plague", day)) {
            s.add("plague", "*", 1, day + 10);
            tell(w, s, c, 3, "Plague has broken out in " + s.name + ". Its gates are shut to caravans.");
        } else if ((roll -= 0.02 * f) < 0 && s.happiness > 65 && s.fed >= 0.99 && !s.has("festival", day)) {
            for (TradeGoods.Item i : TradeGoods.all()) if (i.nutrition() > 0) s.stock.take(i.key(), s.stock.available(i.key()) / 12);
            s.add("festival", "*", 1, day + 5);
            s.add("mood", "*", 10, day + 5);
            tell(w, s, c, 1, s.name + " is holding a festival. The tables are full and the mood is high.");
        } else if ((roll -= 0.004 * f) < 0 && s.happiness > 72 && s.stability > 65 && !s.has("golden", day)) {
            s.add("golden", "*", 1, day + 20);
            s.add("output", "*", 1.25, day + 20);
            tell(w, s, c, 2, "A golden age has dawned in " + s.name + ": its workshops have never been so busy.");
        } else if ((roll -= 0.006 * f) < 0 && mines && !s.has("strike", day)) {
            s.add("strike", "*", 1, day + 20);
            s.add("output", "ORE", 1.4, day + 20);
            tell(w, s, c, 2, "The miners of " + s.name + " have struck a rich new seam.");
        } else if ((roll -= 0.008 * f) < 0) {
            raid(w, s, c, rng);
        } else if ((roll -= 0.002 * f) < 0) {
            for (String g : new ArrayList<>(s.stock.contents().keySet())) s.stock.take(g, s.stock.available(g) * 15 / 100);
            s.add("mood", "*", -6, day + 6);
            tell(w, s, c, 2, (s.type.coastal() ? "A storm has battered " : "Fire has swept through ") + s.name + ". Part of its stores are lost.");
        } else if ((roll -= 0.01 * f) < 0 && s.happiness > 60 && s.population < cap * 0.8) {
            double n = Math.max(2, s.population * (0.04 + rng.nextDouble() * 0.05));
            s.population += n;
            tell(w, s, c, 1, Math.round(n) + " newcomers have settled in " + s.name + ", drawn by word of good living.");
        } else if ((roll -= 0.004 * f) < 0 && mines) {
            s.population = Math.max(0, s.population - 1 - rng.nextInt(3));
            s.add("output", "ORE", 0.7, day + 4);
            s.add("mood", "*", -5, day + 4);
            tell(w, s, c, 1, "A gallery has collapsed in the mines of " + s.name + ".");
        } else if ((roll -= 0.003 * f) < 0 && !c.outlaw) {
            c.unrest += 10;
            s.stability = clamp(s.stability - 10, 0, 100);
            tell(w, s, c, 1, "Scandal in " + s.name + ": an official of " + c.ruler() + " is accused of emptying the granary for his own table.");
        } else if ((roll -= 0.002 * f) < 0 && c.government.hereditary() && s.capital && !c.outlaw) {
            Civ friend = null;
            for (Civ o : w.civs.values()) if (o != c && !o.outlaw && !w.of(o.id).isEmpty() && w.relation(c.id, o.id) > 0 && (friend == null || rng.nextBoolean())) friend = o;
            if (friend != null) {
                w.adjustRelation(c.id, friend.id, 15);
                s.add("mood", "*", 6, day + 6);
                if (c.leader.heir == null) c.leader.heir = Names.person(rng);
                tell(w, s, c, 2, "A wedding joins House " + c.leader.house + " of " + c.name + " with the rulers of " + friend.name + ".");
            }
        } else if ((roll -= 0.008 * f) < 0 && (s.type == SettlementType.TRADING_TOWN || s.level >= 3) && !c.outlaw) {
            c.research += 15;
            tell(w, s, c, 1, "Travelling scholars are lecturing in " + s.name + ".");
        }
    }

    private void tell(CivWorld w, Settlement s, Civ c, int weight, String text) {
        c.record(w.day, text);
        w.news(s.id, c.id, weight, text);
    }

    /** Outlaws from a camp within reach fall on a settlement's stores; its garrison may see them off. */
    private void raid(CivWorld w, Settlement s, Civ c, Random rng) {
        if (c.outlaw) return;
        for (Settlement b : w.settlements.values()) {
            Civ bc = w.civs.get(b.civ);
            if (!b.alive() || bc == null || !bc.outlaw || b.distance(s) > 900) continue;
            double defence = s.soldiers * (1 + 0.15 * c.tech(Tech.DEFENCE)) * (s.level >= 3 ? 1.3 : 1);
            if (defence > b.soldiers * (0.6 + rng.nextDouble())) {
                double lost = rng.nextInt(3);
                b.soldiers = Math.max(0, b.soldiers - lost);
                b.population = Math.max(0, b.population - lost);
                tell(w, s, c, 1, "The garrison of " + s.name + " drove off raiders from " + b.name + ".");
                return;
            }
            List<String> goods = new ArrayList<>(s.stock.contents().keySet());
            goods.sort(Comparator.comparingDouble((String g) -> -TradeGoods.worth(g) * s.stock.available(g)));
            for (int i = 0; i < Math.min(6, goods.size()); i++) b.stock.force(goods.get(i), s.stock.take(goods.get(i), s.stock.available(goods.get(i)) / 8));
            s.add("mood", "*", -8, w.day + 5);
            tell(w, s, c, 2, "Raiders from " + b.name + " have plundered the stores of " + s.name + ".");
            return;
        }
    }

    // ───────────── contracts ─────────────

    private void contracts(CivWorld w, Random rng) {
        for (Contract c : w.contracts.values()) {
            Settlement s = w.settlements.get(c.settlement);
            if (c.state == Contract.State.OPEN && (c.expiresDay < w.day || s == null || !s.alive())) c.state = Contract.State.EXPIRED;
            if (c.state == Contract.State.TAKEN && c.expiresDay < w.day) {
                c.state = Contract.State.FAILED;
                if (s != null && c.hold != null) s.stock.release(c.hold);
                Civ civ = s == null ? null : w.civs.get(s.civ);
                if (civ != null && c.takenBy != null) civ.addReputation(c.takenBy, -3);
            }
        }
        w.contracts.values().removeIf(c -> (c.state == Contract.State.EXPIRED || c.state == Contract.State.FAILED || c.state == Contract.State.DONE) && c.expiresDay + 6 < w.day);
        for (Settlement s : w.settlements.values()) {
            if (!s.alive() || s.has("plague", w.day)) continue;
            int open = 0;
            for (Contract c : w.contracts.values()) if (c.settlement.equals(s.id) && (c.state == Contract.State.OPEN || c.state == Contract.State.TAKEN)) open++;
            if (open >= 2 || rng.nextDouble() > 0.5) continue;
            post(w, s, rng);
        }
    }

    /** A settlement asks for what it is shortest of, offering what it has most to spare. */
    public Contract post(CivWorld w, Settlement s, Random rng) {
        Map<String, Double> shortages = new LinkedHashMap<>(), surpluses = new LinkedHashMap<>();
        Prices.sort(s, shortages, surpluses);
        String want = null;
        double worst = 0.5;
        for (Map.Entry<String, Double> e : shortages.entrySet()) {
            boolean dup = false;
            for (Contract c : w.contracts.values()) if (c.settlement.equals(s.id) && c.want.equals(e.getKey()) && c.state != Contract.State.DONE) dup = true;
            if (!dup && e.getValue() < worst) {
                worst = e.getValue();
                want = e.getKey();
            }
        }
        if (want == null) return null;
        long qty = Math.round(Prices.target(s, want) * 0.6 - s.stock.get(want));
        qty = Math.max(TradeGoods.stack(want) == 1 ? 1 : 8, Math.min(TradeGoods.stack(want) == 1 ? 6 : 192, qty));
        double worth = qty * TradeGoods.worth(want);
        int difficulty = worth < 40 ? 1 : worth < 120 ? 2 : worth < 300 ? 3 : worth < 700 ? 4 : 5;
        String reward = null;
        double best = 0;
        for (String g : surpluses.keySet()) {
            double v = Prices.spare(s, g) * TradeGoods.worth(g);
            if (v > best && !g.equals(want)) {
                best = v;
                reward = g;
            }
        }
        if (reward == null) return null;
        // a fair price and a bonus for the trouble: never more than a third over, and never more than they can spare
        long rewardQty = (long) Math.floor(worth * (1.12 + 0.04 * difficulty) / TradeGoods.worth(reward));
        rewardQty = Math.min(rewardQty, Prices.spare(s, reward));
        if (rewardQty < 1) return null;
        Contract c = new Contract(w.newId("k"));
        c.settlement = s.id;
        c.want = want;
        c.wantQty = qty;
        c.reward = reward;
        c.rewardQty = rewardQty;
        c.difficulty = difficulty;
        c.minReputation = difficulty >= 5 ? 30 : difficulty == 4 ? 15 : difficulty == 3 ? 0 : -40;
        c.reputation = 1 + difficulty;
        c.postedDay = w.day;
        c.expiresDay = w.day + 6;
        w.contracts.put(c.id, c);
        return c;
    }

    static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
