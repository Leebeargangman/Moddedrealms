package com.colonysmp.diplomacy;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Reservation;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Contract;
import com.colonysmp.model.Perm;
import com.colonysmp.model.Proposal;
import com.colonysmp.model.Relation;
import com.colonysmp.model.Shipment;
import com.colonysmp.model.War;
import com.colonysmp.store.Snapshot;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relations between colonies: a score moved by trade, gifts, raids and broken promises; alliances and treaties
 * (non-aggression, trade agreements, defensive pacts, open borders); offers waiting for an answer; recurring
 * supply contracts; and goods travelling between colonies as caravans. Nothing changes hands until both sides
 * agree, and goods offered are held back (reserved) until the answer comes.
 */
public final class DiplomacyManager {

    private final ColonySMP plugin;
    private final Map<String, Relation> relations = new LinkedHashMap<>();
    private final Map<String, Proposal> proposals = new LinkedHashMap<>();
    private final Map<String, Contract> contracts = new LinkedHashMap<>();
    private final Map<String, Shipment> shipments = new LinkedHashMap<>();
    private int seq;

    public DiplomacyManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    private String id(String prefix) {
        return prefix + (++seq) + "_" + Long.toString(System.currentTimeMillis(), 36);
    }

    // ───────────── relations ─────────────

    public Relation relation(Colony a, Colony b) {
        return relations.computeIfAbsent(Relation.key(a.id, b.id), k -> new Relation(a.id, b.id));
    }

    public Relation existing(Colony a, Colony b) {
        return relations.get(Relation.key(a.id, b.id));
    }

    public Collection<Relation> relations() {
        return relations.values();
    }

    public List<Relation> relationsOf(Colony c) {
        List<Relation> out = new ArrayList<>();
        for (Relation r : relations.values()) if (r.a.equals(c.id) || r.b.equals(c.id)) out.add(r);
        return out;
    }

    public boolean allied(Colony a, Colony b) {
        if (a == null || b == null || a == b) return false;
        Relation r = existing(a, b);
        return r != null && r.allied;
    }

    public boolean openBorders(Colony a, Colony b) {
        Relation r = a == null || b == null || a == b ? null : existing(a, b);
        return r != null && r.treaties.contains(Relation.Treaty.OPEN_BORDERS);
    }

    public boolean treaty(Colony a, Colony b, Relation.Treaty t) {
        Relation r = a == null || b == null || a == b ? null : existing(a, b);
        return r != null && (r.treaties.contains(t) || t == Relation.Treaty.DEFENSIVE_PACT && r.allied);
    }

    /** Colonies bound to defend this one (allies and defensive pacts). */
    public List<Colony> defenders(Colony c) {
        List<Colony> out = new ArrayList<>();
        for (Relation r : relationsOf(c)) {
            if (!r.allied && !r.treaties.contains(Relation.Treaty.DEFENSIVE_PACT)) continue;
            Colony o = plugin.colonies().get(r.other(c.id));
            if (o != null) out.add(o);
        }
        return out;
    }

    public Relation.Status status(Colony a, Colony b) {
        Relation r = existing(a, b);
        return r == null ? Relation.Status.NEUTRAL : r.status(plugin.sim().day(a));
    }

    /** Trade with a colony is possible: not at war, not hostile beyond the policy. */
    public String canTrade(Colony a, Colony b) {
        if (plugin.wars().atWar(a, b)) return "You are at war with " + Text.esc(b.name) + ".";
        Relation.Status s = status(a, b);
        if (s == Relation.Status.HOSTILE) return "Relations with " + Text.esc(b.name) + " are too hostile to trade.";
        if (s == Relation.Status.NEUTRAL && !b.policies.tradeWithNeutrals) return Text.esc(b.name) + " only trades with friends and allies.";
        return null;
    }

    /** May a player shop at a colony's market? Not if their colony is at war or hostile with it. */
    public boolean canTradeWith(Player p, Colony c) {
        if (c.isMember(p.getUniqueId())) return true;
        List<Colony> theirs = plugin.colonies().coloniesOf(p.getUniqueId());
        for (Colony pc : theirs) if (canTrade(pc, c) != null) return false;
        return !theirs.isEmpty() || c.policies.tradeWithNeutrals;
    }

    // ───────────── proposals ─────────────

    public Collection<Proposal> proposals() {
        return proposals.values();
    }

    public List<Proposal> incoming(Colony c) {
        List<Proposal> out = new ArrayList<>();
        for (Proposal p : proposals.values()) if (p.to.equals(c.id)) out.add(p);
        return out;
    }

    public List<Proposal> outgoing(Colony c) {
        List<Proposal> out = new ArrayList<>();
        for (Proposal p : proposals.values()) if (p.from.equals(c.id)) out.add(p);
        return out;
    }

    public Proposal proposal(String id) {
        return proposals.get(id);
    }

    public Proposal newProposal(Proposal.Type type, Colony from, Colony to) {
        Proposal p = new Proposal(id("p"), type, from.id, to.id);
        p.expires = System.currentTimeMillis() + plugin.settings().proposalMinutes * 60_000L;
        return p;
    }

    /** Sends an offer (after checking it and holding back what it gives). Returns why it couldn't be sent, or null. */
    public String propose(Player by, Colony from, Colony to, Proposal p) {
        if (from == to) return "That's your own colony.";
        Perm perm = p.type == Proposal.Type.TRADE || p.type == Proposal.Type.CONTRACT ? Perm.TRADE : Perm.DIPLOMACY;
        if (by != null && !plugin.access().can(by, from, perm)) return "You may not do that for " + Text.esc(from.name) + ".";
        for (Proposal o : proposals.values()) {
            if (o.from.equals(from.id) && o.to.equals(to.id) && o.type == p.type && o.type != Proposal.Type.TRADE) return "You already have a " + p.type.display.toLowerCase() + " offer waiting with " + Text.esc(to.name) + ".";
        }
        Relation r = relation(from, to);
        switch (p.type) {
            case ALLIANCE -> {
                if (r.allied) return "You are already allied.";
                if (plugin.wars().atWar(from, to)) return "You are at war.";
            }
            case TREATY -> {
                if (p.treaty == null) return "Which treaty?";
                if (r.treaties.contains(p.treaty)) return "That treaty is already in force.";
                if (plugin.wars().atWar(from, to)) return "You are at war.";
            }
            case PEACE -> {
                War w = plugin.wars().warOf(from);
                if (w == null || !w.enemies(from.id, to.id)) return "You aren't at war with " + Text.esc(to.name) + ".";
                p.war = w.id;
            }
            case TRADE, CONTRACT -> {
                String why = canTrade(from, to);
                if (why != null) return why;
            }
            case JOIN_WAR -> {
                War w = plugin.wars().warOf(from);
                if (w == null) return "Your colony isn't at war.";
                if (!r.allied) return "Only allies can be called to arms.";
                p.war = w.id;
            }
            default -> {
            }
        }
        // what's offered is held back now, so it's there if they accept
        if (!p.give.isEmpty()) {
            for (Map.Entry<String, Long> e : p.give.entrySet()) {
                if (plugin.sim().finances().spare(from, e.getKey()) < e.getValue()) return cantSpare(from, e.getKey(), e.getValue(), "to offer");
            }
            Reservation res = from.storage.reserve("trade:" + p.id, p.give, System.currentTimeMillis());
            if (res == null) return "Those goods couldn't be set aside.";
        }
        if (p.money > 0 && !from.treasury.canAfford(p.money)) return "Your treasury can't cover " + Text.value(p.money) + ".";
        p.by = by == null ? "" : by.getName();
        proposals.put(p.id, p);
        String what = describe(p);
        from.log(ColonyEvent.Type.DIPLOMACY, plugin.sim().day(from), "Offered " + to.name + ": " + what);
        to.log(ColonyEvent.Type.DIPLOMACY, plugin.sim().day(to), from.name + " offers: " + what);
        for (Player m : to.onlineMembers()) {
            Text.send(m, "<blue>" + Text.esc(from.name) + "</blue> <gray>offers: <white>" + Text.esc(what) + "</white>. "
                    + "<click:run_command:'/colony diplomacy accept " + p.id + "'><green>[Accept]</green></click> "
                    + "<click:run_command:'/colony diplomacy decline " + p.id + "'><red>[Decline]</red></click>");
        }
        plugin.requestSave();
        return null;
    }

    public String describe(Proposal p) {
        StringBuilder sb = new StringBuilder(p.type.display);
        switch (p.type) {
            case TREATY -> sb.append(": ").append(p.treaty == null ? "?" : p.treaty.display);
            case PEACE -> sb.append(p.truceDays > 0 ? " with a " + p.truceDays + "-day truce" : "");
            case CONTRACT -> sb.append(": ").append(p.qty).append(" ").append(Goods.get(p.good).name()).append(" every ").append(p.everyDays)
                    .append(" day(s) for ").append(p.days).append(" days at ").append(Text.value(p.money)).append(" each");
            default -> {
            }
        }
        if (!p.give.isEmpty()) sb.append("; giving ").append(goods(p.give));
        if (!p.want.isEmpty()) sb.append("; asking ").append(goods(p.want));
        if (p.type != Proposal.Type.CONTRACT && p.money > 0) sb.append("; paying ").append(Text.value(p.money));
        if (p.type != Proposal.Type.CONTRACT && p.money < 0) sb.append("; asking ").append(Text.value(-p.money));
        if (!p.release.isEmpty()) sb.append("; releasing ").append(p.release.size()).append(" prisoner(s)");
        if (!p.demand.isEmpty()) sb.append("; asking the release of ").append(p.demand.size()).append(" prisoner(s)");
        return sb.toString();
    }

    private static String goods(Map<String, Long> g) {
        List<String> out = new ArrayList<>();
        g.forEach((k, v) -> out.add(v + " " + Goods.get(k).name()));
        return String.join(", ", out);
    }

    public String decline(Player by, Proposal p) {
        Colony to = plugin.colonies().get(p.to), from = plugin.colonies().get(p.from);
        if (to == null) return "That offer is no longer valid.";
        if (by != null && !to.isMember(by.getUniqueId()) && (from == null || !from.isMember(by.getUniqueId()))) return "That offer isn't for you.";
        drop(p);
        if (from != null) {
            for (Player m : from.onlineMembers()) Text.send(m, "<gray>" + Text.esc(to.name) + " declined your " + p.type.display.toLowerCase() + " offer.");
        }
        return null;
    }

    /** Removes an offer and frees what it held back. */
    private void drop(Proposal p) {
        proposals.remove(p.id);
        Colony from = plugin.colonies().get(p.from);
        if (from != null) from.storage.releaseOwner("trade:" + p.id);
    }

    /** Accepts an offer: checks both sides can still do it, then carries it out. */
    public String accept(Player by, Proposal p) {
        Colony from = plugin.colonies().get(p.from), to = plugin.colonies().get(p.to);
        if (from == null || to == null || !proposals.containsKey(p.id)) return "That offer is no longer valid.";
        Perm perm = p.type == Proposal.Type.TRADE || p.type == Proposal.Type.CONTRACT ? Perm.TRADE : Perm.DIPLOMACY;
        if (by != null && !plugin.access().can(by, to, perm)) return "You may not accept that for " + Text.esc(to.name) + ".";
        long now = System.currentTimeMillis();
        long day = plugin.sim().day(to);
        Relation r = relation(from, to);
        // what's asked of the accepting side
        for (Map.Entry<String, Long> e : p.want.entrySet()) {
            if (plugin.sim().finances().spare(to, e.getKey()) < e.getValue()) return cantSpare(to, e.getKey(), e.getValue(), "to give");
        }
        if (p.type != Proposal.Type.CONTRACT && p.money < 0 && !to.treasury.canAfford(-p.money)) return "Your treasury can't cover " + Text.value(-p.money) + ".";
        if (p.type != Proposal.Type.CONTRACT && p.money > 0 && !from.treasury.canAfford(p.money)) return Text.esc(from.name) + " can no longer pay.";
        switch (p.type) {
            case ALLIANCE -> {
                r.allied = true;
                r.treaties.add(Relation.Treaty.DEFENSIVE_PACT);
                r.treaties.add(Relation.Treaty.OPEN_BORDERS);
                r.change(25);
            }
            case TREATY -> {
                r.treaties.add(p.treaty);
                r.change(10);
            }
            case PEACE -> {
                War w = plugin.wars().get(p.war);
                if (w == null || w.phase == War.Phase.ENDED) {
                    drop(p);
                    return "That war is already over.";
                }
                plugin.wars().makePeace(w, p.truceDays > 0 ? p.truceDays : plugin.settings().truceDays);
            }
            case JOIN_WAR -> {
                War w = plugin.wars().get(p.war);
                if (w == null || w.phase == War.Phase.ENDED) {
                    drop(p);
                    return "That war is already over.";
                }
                String why = plugin.wars().join(w, to, w.attackers.contains(from.id));
                if (why != null) return why;
                r.change(10);
            }
            case CONTRACT -> {
                Contract c = new Contract(id("k"), p.from, p.to, p.good, p.qty, Math.max(0, p.money), p.everyDays, day, day + p.days);
                contracts.put(c.id, c);
                r.change(5);
            }
            case PRISONER_EXCHANGE, RANSOM -> {
                String why = plugin.prison().settle(p, from, to);
                if (why != null) return why;
            }
            default -> {
            }
        }
        // goods and money change hands (goods travel as a caravan)
        if (!p.give.isEmpty()) {
            Reservation res = null;
            for (Reservation x : from.storage.reservations()) if (x.owner.equals("trade:" + p.id)) res = x;
            if (res == null || !from.storage.consume(res.id)) {
                drop(p);
                return Text.esc(from.name) + " no longer has the goods it offered.";
            }
            ship(from, to, p.give, p.type.display + " from " + from.name);
        }
        if (!p.want.isEmpty()) {
            for (Map.Entry<String, Long> e : p.want.entrySet()) to.storage.take(e.getKey(), e.getValue());
            ship(to, from, p.want, p.type.display + " from " + to.name);
        }
        if (p.type != Proposal.Type.CONTRACT && p.money > 0) {
            from.treasury.transfer(to.treasury, p.money, TxnType.TRADE_OUT, p.type.display + " with " + to.name, plugin.sim().day(from),
                    TxnType.TRADE_IN, p.type.display + " with " + from.name, day, now);
        }
        if (p.type != Proposal.Type.CONTRACT && p.money < 0) {
            to.treasury.transfer(from.treasury, -p.money, TxnType.TRADE_OUT, p.type.display + " with " + from.name, day,
                    TxnType.TRADE_IN, p.type.display + " with " + to.name, plugin.sim().day(from), now);
        }
        if (p.type == Proposal.Type.TRADE) r.change(3);
        proposals.remove(p.id);
        String what = describe(p);
        r.note(day, "Agreed: " + what);
        from.log(ColonyEvent.Type.DIPLOMACY, plugin.sim().day(from), to.name + " accepted: " + what);
        to.log(ColonyEvent.Type.DIPLOMACY, day, "Accepted " + from.name + "'s offer: " + what);
        for (Player m : from.onlineMembers()) Text.send(m, "<green>" + Text.esc(to.name) + " accepted: <white>" + Text.esc(what));
        for (Player m : to.onlineMembers()) Text.send(m, "<green>Agreed with " + Text.esc(from.name) + ": <white>" + Text.esc(what));
        if (p.type == Proposal.Type.ALLIANCE) Bukkit.broadcast(Text.mm(Text.PREFIX + "<aqua>" + Text.esc(from.name) + " and " + Text.esc(to.name) + " are now allies."));
        plugin.requestSave();
        return null;
    }

    /** Ends a treaty or alliance from one side. Breaking promises sours relations. */
    public String cancel(Player by, Colony from, Colony to, Relation.Treaty t, boolean alliance) {
        if (by != null && !plugin.access().can(by, from, Perm.DIPLOMACY)) return "You may not do that for " + Text.esc(from.name) + ".";
        Relation r = existing(from, to);
        if (r == null) return "You have no agreements with " + Text.esc(to.name) + ".";
        long day = plugin.sim().day(from);
        if (alliance) {
            if (!r.allied) return "You aren't allied with " + Text.esc(to.name) + ".";
            r.allied = false;
            r.treaties.remove(Relation.Treaty.DEFENSIVE_PACT);
            r.change(-25);
            r.note(day, from.name + " broke the alliance");
        } else {
            if (t == null || !r.treaties.remove(t)) return "No such treaty with " + Text.esc(to.name) + ".";
            r.change(t == Relation.Treaty.NON_AGGRESSION ? -30 : -10);
            r.note(day, from.name + " cancelled the " + t.display.toLowerCase());
        }
        for (Contract c : contracts.values()) {
            if (alliance || t != Relation.Treaty.TRADE_AGREEMENT) continue;
            if (c.supplier.equals(from.id) && c.buyer.equals(to.id) || c.supplier.equals(to.id) && c.buyer.equals(from.id)) c.active = false;
        }
        String what = alliance ? "the alliance" : "the " + t.display.toLowerCase();
        from.log(ColonyEvent.Type.DIPLOMACY, day, "Ended " + what + " with " + to.name + ".");
        to.log(ColonyEvent.Type.DIPLOMACY, plugin.sim().day(to), from.name + " ended " + what + ".");
        for (Player m : to.onlineMembers()) Text.send(m, "<gold>" + Text.esc(from.name) + " ended " + what + " with you.");
        plugin.requestSave();
        return null;
    }

    /** A gift of goods or money improves relations. */
    /** Why a colony can't part with some goods: it hasn't got them, or they're its food reserve. */
    private String cantSpare(Colony c, String good, long qty, String what) {
        String name = Goods.get(good).name();
        if (c.storage.available(good) < qty) return "Your storage doesn't have " + qty + " " + name + " " + what + ".";
        return "Only " + plugin.sim().finances().spare(c, good) + " " + name + " can go: the rest is the colony's food reserve ("
                + c.policies.foodReserveDays + " days, see the policies).";
    }

    public String gift(Player by, Colony from, Colony to, String good, long qty, long money) {
        if (by != null && !plugin.access().can(by, from, Perm.TRADE)) return "You may not do that for " + Text.esc(from.name) + ".";
        long now = System.currentTimeMillis();
        double value = 0;
        if (good != null && qty > 0) {
            if (plugin.sim().finances().spare(from, good) < qty) return cantSpare(from, good, qty, "to give");
            from.storage.take(good, qty);
            ship(from, to, Map.of(good, qty), "Gift from " + from.name);
            value += Goods.get(good).value() * qty;
        }
        if (money > 0) {
            long moved = from.treasury.transfer(to.treasury, money, TxnType.TRADE_OUT, "Gift to " + to.name, plugin.sim().day(from),
                    TxnType.TRADE_IN, "Gift from " + from.name, plugin.sim().day(to), now);
            if (moved <= 0) return "The treasury can't afford it.";
            value += moved;
        }
        Relation r = relation(from, to);
        r.change(Math.min(15, value / 20));
        r.note(plugin.sim().day(from), from.name + " sent a gift");
        for (Player m : to.onlineMembers()) Text.send(m, "<green>" + Text.esc(from.name) + " sent your colony a gift.");
        plugin.requestSave();
        return null;
    }

    // ───────────── caravans ─────────────

    public Collection<Shipment> shipments() {
        return shipments.values();
    }

    /** Sends goods (already taken from the sender) on the road; they arrive after a delay that depends on distance. */
    public void ship(Colony from, Colony to, Map<String, Long> goods, String reason) {
        double dist = 0;
        if (from.core != null && to.core != null && from.world != null && from.world.equals(to.world)) dist = Math.sqrt(from.core.distSq(to.core)) / 16;
        else dist = 32;
        long delay = (long) (dist * plugin.settings().shipmentSecondsPerChunk * 1000);
        Shipment s = new Shipment(id("s"), from.id, to.id, System.currentTimeMillis() + delay, reason);
        s.goods.putAll(goods);
        shipments.put(s.id, s);
        if (delay < 1000) deliver(s);
    }

    private boolean deliver(Shipment s) {
        Colony to = plugin.colonies().get(s.to);
        Colony back = plugin.colonies().get(s.from);
        Colony dest = to != null ? to : back;
        if (dest == null) {
            shipments.remove(s.id);
            return true;
        }
        boolean all = true;
        for (Map.Entry<String, Long> e : new ArrayList<>(s.goods.entrySet())) {
            long added = dest.storage.add(e.getKey(), e.getValue());
            if (added >= e.getValue()) s.goods.remove(e.getKey());
            else {
                s.goods.put(e.getKey(), e.getValue() - added);
                all = false;
            }
        }
        if (all) {
            shipments.remove(s.id);
            for (Player m : dest.onlineMembers()) Text.send(m, "<gray>A caravan arrived: " + Text.esc(s.reason) + ".");
        } else if (System.currentTimeMillis() - s.arrives > 3 * 24 * 3600_000L) {
            // nobody made room in three days: the rest is left at the gates (dropped from the books)
            shipments.remove(s.id);
            dest.log(ColonyEvent.Type.ECONOMY, plugin.sim().day(dest), "A caravan had to leave goods behind: storage was full.");
        }
        return all;
    }

    // ───────────── ticking ─────────────

    /** Every second: offers expire and caravans arrive. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Proposal p : new ArrayList<>(proposals.values())) {
            if (now < p.expires) continue;
            drop(p);
            Colony from = plugin.colonies().get(p.from);
            if (from != null) for (Player m : from.onlineMembers()) Text.send(m, "<gray>Your " + p.type.display.toLowerCase() + " offer expired.");
        }
        for (Shipment s : new ArrayList<>(shipments.values())) {
            if (now >= s.arrives && (now - s.arrives) % 60_000 < 1000) deliver(s);
        }
    }

    /** Once a day for each colony: contract deliveries it supplies, and relations cooling toward neutral. */
    public void daily(Colony c, long day) {
        for (Contract k : new ArrayList<>(contracts.values())) {
            if (!k.supplier.equals(c.id)) continue;
            if (!k.active || day > k.endDay) {
                contracts.remove(k.id);
                continue;
            }
            if (day < k.nextDay) continue;
            k.nextDay = day + k.everyDays;
            deliver(k, c, day);
        }
        for (Relation r : relationsOf(c)) {
            if (!r.a.equals(c.id)) continue;
            if (!r.allied && !r.atWar && Math.abs(r.score) > 0.5) r.change(-Math.signum(r.score) * 0.5);
        }
    }

    private void deliver(Contract k, Colony supplier, long day) {
        Colony buyer = plugin.colonies().get(k.buyer);
        if (buyer == null) {
            contracts.remove(k.id);
            return;
        }
        String problem = null;
        if (plugin.wars().atWar(supplier, buyer)) problem = "the colonies are at war";
        else if (supplier.storage.available(k.good) - plugin.sim().production().min(supplier, k.good) < k.qty) problem = Text.esc(supplier.name) + " didn't have " + k.qty + " " + Goods.get(k.good).name() + " to spare";
        else if (buyer.storage.room(k.good) < k.qty) problem = Text.esc(buyer.name) + " had no room";
        else if (k.price > 0 && !buyer.treasury.canAfford(k.price)) problem = Text.esc(buyer.name) + " couldn't pay";
        Relation r = relation(supplier, buyer);
        if (problem != null) {
            k.failures++;
            k.lastProblem = problem;
            r.change(-2);
            if (k.failures >= 3) {
                k.active = false;
                r.note(day, "A supply contract broke down: " + problem);
                for (Colony c : new Colony[]{supplier, buyer}) {
                    c.log(ColonyEvent.Type.DIPLOMACY, plugin.sim().day(c), "The contract for " + k.qty + " " + Goods.get(k.good).name() + " broke down: " + problem + ".");
                }
            }
            return;
        }
        supplier.storage.take(k.good, k.qty);
        long now = System.currentTimeMillis();
        if (k.price > 0) {
            buyer.treasury.transfer(supplier.treasury, k.price, TxnType.TRADE_OUT, "Contract: " + k.qty + " " + Goods.get(k.good).name() + " from " + supplier.name, plugin.sim().day(buyer),
                    TxnType.TRADE_IN, "Contract: " + k.qty + " " + Goods.get(k.good).name() + " to " + buyer.name, day, now);
        }
        ship(supplier, buyer, Map.of(k.good, (long) k.qty), "Contract delivery from " + supplier.name);
        k.deliveries++;
        k.failures = 0;
        k.lastProblem = null;
        r.change(0.5);
    }

    public Collection<Contract> contracts() {
        return contracts.values();
    }

    public List<Contract> contractsOf(Colony c) {
        List<Contract> out = new ArrayList<>();
        for (Contract k : contracts.values()) if (k.supplier.equals(c.id) || k.buyer.equals(c.id)) out.add(k);
        return out;
    }

    public void colonyRemoved(Colony c) {
        relations.values().removeIf(r -> r.a.equals(c.id) || r.b.equals(c.id));
        for (Proposal p : new ArrayList<>(proposals.values())) if (p.from.equals(c.id) || p.to.equals(c.id)) drop(p);
        contracts.values().removeIf(k -> k.supplier.equals(c.id) || k.buyer.equals(c.id));
        // caravans to it go back; caravans from it are lost with it
        shipments.values().removeIf(s -> s.from.equals(c.id) && plugin.colonies().get(s.to) == null);
    }

    // ───────────── persistence ─────────────

    public void snapshot(Snapshot s) {
        for (Relation r : relations.values()) s.relations.add(new Snapshot.Row("", r.key(), yaml(r::save)));
        for (Proposal p : proposals.values()) s.proposals.add(new Snapshot.Row("", p.id, yaml(p::save)));
        for (Contract k : contracts.values()) s.contracts.add(new Snapshot.Row("", k.id, yaml(k::save)));
        for (Shipment sh : shipments.values()) s.shipments.add(new Snapshot.Row("", sh.id, yaml(sh::save)));
    }

    private static String yaml(java.util.function.Consumer<YamlConfiguration> w) {
        YamlConfiguration y = new YamlConfiguration();
        w.accept(y);
        return y.saveToString();
    }

    private YamlConfiguration parse(Snapshot.Row row) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(row.data());
            return y;
        } catch (InvalidConfigurationException e) {
            plugin.getLogger().warning("Diplomacy record " + row.id() + " is corrupt and was skipped");
            return null;
        }
    }

    public void load(Snapshot s) {
        relations.clear();
        proposals.clear();
        contracts.clear();
        shipments.clear();
        for (Snapshot.Row row : s.relations) {
            YamlConfiguration y = parse(row);
            Relation r = y == null ? null : Relation.load(y);
            if (r != null) relations.put(r.key(), r);
        }
        for (Snapshot.Row row : s.proposals) {
            YamlConfiguration y = parse(row);
            Proposal p = y == null ? null : Proposal.load(row.id(), y);
            if (p != null) proposals.put(p.id, p);
        }
        for (Snapshot.Row row : s.contracts) {
            YamlConfiguration y = parse(row);
            Contract k = y == null ? null : Contract.load(row.id(), y);
            if (k != null) contracts.put(k.id, k);
        }
        for (Snapshot.Row row : s.shipments) {
            YamlConfiguration y = parse(row);
            Shipment sh = y == null ? null : Shipment.load(row.id(), y);
            if (sh != null) shipments.put(sh.id, sh);
        }
        seq = relations.size() + proposals.size() + contracts.size() + shipments.size();
    }
}
