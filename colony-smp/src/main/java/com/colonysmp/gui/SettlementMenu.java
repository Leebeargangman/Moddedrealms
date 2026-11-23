package com.colonysmp.gui;

import com.colonysmp.ColonySMP;
import com.colonysmp.civ.Civ;
import com.colonysmp.civ.CivSim;
import com.colonysmp.civ.CivWorld;
import com.colonysmp.civ.Contract;
import com.colonysmp.civ.Prices;
import com.colonysmp.civ.Settlement;
import com.colonysmp.civ.Tech;
import com.colonysmp.civ.TradeGoods;
import com.colonysmp.civ.War;
import com.colonysmp.txn.TransactionManager;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything about one settlement and its people: who rules, how they fare, who their friends are, and what they are asking for. */
public final class SettlementMenu extends Menu {

    private final ColonySMP plugin;
    private final Settlement s;

    public SettlementMenu(ColonySMP plugin, Player viewer, Settlement s) {
        super(viewer, 6, "<dark_gray>" + Text.esc(s.name));
        this.plugin = plugin;
        this.s = s;
    }

    private static String name(String good) {
        TradeGoods.Item i = TradeGoods.get(good);
        return i == null ? good : i.name();
    }

    @Override
    protected void draw() {
        CivWorld w = plugin.civs().world();
        Civ civ = w.civs.get(s.civ);
        if (civ == null) return;
        double rep = civ.reputation(viewer.getUniqueId());

        List<String> l = new ArrayList<>();
        l.add("<gray>" + s.type.display + " <dark_gray>(" + s.title() + (s.capital ? ", seat of " + Text.esc(civ.name) : "") + ")");
        l.add("<gray>People: <white>" + Math.round(s.population) + "</white>, of them <white>" + Math.round(s.soldiers) + "</white> under arms");
        l.add("<gray>Mood: " + Text.meter(s.happiness));
        l.add("<gray>Order: " + Text.meter(s.stability));
        l.add("<gray>Fed: " + Text.meter(s.fed * 100));
        l.add("<gray>Stores worth: <white>" + Math.round(CivWorld.wealth(s)) + " <dark_gray>(its whole wealth)");
        if (!s.alive()) l.add("<red>In ruins.");
        if (s.has("plague", w.day)) l.add("<red>Plague.");
        if (s.has("blight", w.day)) l.add("<gold>Blight in the fields.");
        if (s.has("festival", w.day)) l.add("<green>Festival week.");
        if (s.has("golden", w.day)) l.add("<yellow>A golden age.");
        if (s.besiegedBy != null && w.civs.get(s.besiegedBy) != null) l.add("<red>Besieged by " + Text.esc(w.civs.get(s.besiegedBy).name) + ".");
        button(4, Material.BELL, "<gold>" + Text.esc(s.name), l, null);

        List<String> r = new ArrayList<>();
        r.add("<gray>" + civ.government.display);
        r.add("<gray>" + Text.esc(civ.ruler()) + " of House " + Text.esc(civ.leader.house) + ", aged " + (int) civ.leader.age);
        r.add("<gray><italic>" + civ.leader.personality.blurb);
        r.add(civ.leader.heir != null ? "<gray>Heir: <white>" + Text.esc(civ.leader.heir) : civ.government.hereditary() ? "<gold>No heir." : "<gray>The next will be chosen.");
        r.add("<gray>Settlements: <white>" + w.of(civ.id).size() + "</white>, people: <white>" + Math.round(w.population(civ.id)));
        r.add("<gray>Unrest: " + (civ.unrest > 40 ? "<red>" : civ.unrest > 15 ? "<gold>" : "<green>") + Math.round(civ.unrest));
        r.add("<gray>Your standing: " + Text.signed(Math.round(rep)));
        button(10, Material.GOLDEN_HELMET, "<yellow>" + Text.esc(civ.name), r, null);

        List<String> t = new ArrayList<>();
        t.add("<gray>Learning overall: <white>" + String.format(java.util.Locale.ROOT, "%.1f", civ.techLevel()) + " <dark_gray>/ " + Tech.MAX);
        for (Tech tech : Tech.values()) t.add("<gray>" + tech.display + ": <white>" + civ.tech(tech) + (tech == civ.researching ? " <aqua>(studying)" : ""));
        button(12, Material.BOOKSHELF, "<aqua>What they know", t, null);

        List<String> d = new ArrayList<>();
        for (Civ o : w.civs.values()) {
            if (o == civ || w.of(o.id).isEmpty()) continue;
            String st = w.standing(civ.id, o.id);
            String col = st.equals("at war") ? "<red>" : st.equals("allied") || st.equals("friendly") ? "<green>" : st.equals("neutral") ? "<gray>" : "<gold>";
            StringBuilder tr = new StringBuilder();
            for (var x : w.treaties(civ.id, o.id)) tr.append(tr.isEmpty() ? " <dark_gray>(" : ", ").append(x.display);
            if (!tr.isEmpty()) tr.append(")");
            if (d.size() < 14) d.add("<white>" + Text.esc(o.name) + "<gray>: " + col + st + " <dark_gray>" + Text.signed(Math.round(w.relation(civ.id, o.id))) + tr);
        }
        for (War war : w.wars.values()) if (war.involves(civ.id)) d.add("<red>At war with " + Text.esc(w.civs.get(war.other(civ.id)).name) + " over " + Text.esc(war.cause) + ".");
        if (d.isEmpty()) d.add("<gray>They know of no other people.");
        button(14, Material.WHITE_BANNER, "<white>Friends and enemies", d, null);

        List<String> h = new ArrayList<>();
        int from = Math.max(0, civ.history.size() - 12);
        for (int i = from; i < civ.history.size(); i++) h.add("<gray>" + Text.esc(shorten(civ.history.get(i))));
        if (h.isEmpty()) h.add("<gray>Nothing written yet.");
        h.add("<dark_gray>/civ history " + civ.name.replace(' ', '_') + " for all of it");
        button(16, Material.WRITTEN_BOOK, "<white>Chronicle <dark_gray>(day " + w.day + ", " + CivSim.season(w.day) + ")", h, null);

        Map<String, Double> shortages = new LinkedHashMap<>(), surpluses = new LinkedHashMap<>();
        Prices.sort(s, shortages, surpluses);
        List<String> e = new ArrayList<>();
        e.add("<yellow>Makes:");
        s.output.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue() * TradeGoods.worth(b.getKey()), a.getValue() * TradeGoods.worth(a.getKey()))).limit(6)
                .forEach(x -> e.add("<gray>- " + name(x.getKey()) + " <dark_gray>" + Text.num(x.getValue()) + " a day"));
        e.add("<yellow>Short of:");
        shortages.keySet().stream().limit(5).forEach(g -> e.add("<gray>- <red>" + name(g)));
        e.add("<yellow>Plenty of:");
        surpluses.keySet().stream().limit(5).forEach(g -> e.add("<gray>- <green>" + name(g) + " <dark_gray>" + s.stock.get(g)));
        int out = 0;
        for (var sh : w.shipments.values()) if (sh.from.equals(s.id) || sh.to.equals(s.id)) out++;
        e.add("<gray>Caravans on the road to or from here: <white>" + out);
        button(22, Material.CHEST, "<gold>Economy", e, null);

        // contracts: this settlement's offers, and the viewer's own
        int slot = 28;
        for (Contract c : w.contracts.values()) {
            if (slot > 43 || !c.settlement.equals(s.id)) continue;
            boolean mine = c.state == Contract.State.TAKEN && viewer.getUniqueId().equals(c.takenBy);
            if (c.state != Contract.State.OPEN && !mine) continue;
            List<String> cl = new ArrayList<>();
            cl.add("<gray>Bring: <white>" + c.wantQty + " " + name(c.want));
            cl.add("<gray>For: <green>" + c.rewardQty + " " + name(c.reward));
            cl.add("<gray>Difficulty: <white>" + "★".repeat(c.difficulty) + "<dark_gray>" + "☆".repeat(5 - c.difficulty));
            cl.add("<gray>Standing needed: <white>" + Math.round(c.minReputation) + " <dark_gray>(yours: " + Math.round(rep) + ")  <gray>gain: <green>+" + Math.round(c.reputation));
            cl.add(mine ? "<gray>Due by day <white>" + c.expiresDay + " <dark_gray>(today is " + w.day + ")" : "<gray>On offer until day <white>" + c.expiresDay);
            if (mine) {
                long have = TransactionManager.carried(viewer, c.want);
                cl.add(have >= c.wantQty ? "<green>Left-click to hand it over." : "<red>You carry " + have + " of " + c.wantQty + ".");
                cl.add("<dark_gray>Shift + right-click to give it up (costs standing).");
            } else {
                cl.add("<yellow>Click to take it.");
            }
            Material m = TransactionManager.material(c.want);
            button(slot++, mine ? Material.FILLED_MAP : m != null && m.isItem() ? m : Material.PAPER, (mine ? "<green>Your contract: " : "<gold>Contract: ") + name(c.want), cl, k -> {
                if (mine && k.shift() && k.right()) {
                    String err = plugin.civs().abandon(viewer, c);
                    Text.bar(viewer, err == null ? "<gray>You gave the contract up." : "<red>" + Text.esc(err));
                } else if (mine) {
                    TransactionManager.Result res = plugin.civs().deliver(viewer, c);
                    if (res.ok()) {
                        Text.send(viewer, "<green>Delivered. " + Text.esc(s.name) + " gives you <white>" + c.rewardQty + " " + name(c.reward) + "</white> with its thanks.");
                        Fx.sound(viewer, "minecraft:entity.player.levelup", 0.7f, 1.2f);
                    } else {
                        Text.bar(viewer, "<red>" + Text.esc(res.message()));
                    }
                } else {
                    String err = plugin.civs().take(viewer, c);
                    Text.bar(viewer, err == null ? "<green>The contract is yours. Bring the goods to the merchant here." : "<red>" + Text.esc(err));
                }
                refresh();
            });
        }
        if (slot == 28) button(31, Material.PAPER, "<gray>No contracts on offer here today", List.of("<dark_gray>They post one when their stores run short."), null);

        String closed = plugin.civs().closed(viewer, s);
        button(49, closed == null ? Material.EMERALD_BLOCK : Material.IRON_BARS, closed == null ? "<green>Barter with the merchant" : "<red>No trade",
                closed == null ? List.of("<gray>Items for items. No coin.") : List.of("<gray>" + Text.esc(closed)), c -> {
                    if (plugin.civs().closed(viewer, s) == null) new BarterMenu(plugin, viewer, s).open();
                });
    }

    private static String shorten(String line) {
        return line.length() > 70 ? line.substring(0, 67) + "..." : line;
    }
}
