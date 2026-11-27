package com.colonysmp.command;

import com.colonysmp.ColonySMP;
import com.colonysmp.civ.Civ;
import com.colonysmp.civ.CivSim;
import com.colonysmp.civ.CivWorld;
import com.colonysmp.civ.Contract;
import com.colonysmp.civ.Prices;
import com.colonysmp.civ.Settlement;
import com.colonysmp.civ.SettlementType;
import com.colonysmp.civ.Shipment;
import com.colonysmp.civ.Tech;
import com.colonysmp.civ.TradeGoods;
import com.colonysmp.civ.Treaty;
import com.colonysmp.civ.War;
import com.colonysmp.gui.BarterMenu;
import com.colonysmp.gui.SettlementMenu;
import com.colonysmp.txn.Journal;
import com.colonysmp.txn.Line;
import com.colonysmp.txn.TxnState;
import com.colonysmp.util.Text;
import com.colonysmp.world.CivManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The living world's commands: {@code /civ ...} (also {@code /colony civ ...}) for players, {@code /colony audit}
 * for the transaction journal, and {@code /colony admin civ ...} and {@code /colony admin investigate} for
 * administrators. Every administrative action has its own permission under {@code colonysmp.admin}.
 */
public final class CivCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("help", "list", "info", "map", "relations", "diplomacy", "economy", "trade", "contracts", "missions",
            "reputation", "history", "military", "wanted");
    private static final List<String> ADMIN = List.of("create", "remove", "inspect", "setstock", "pop", "relation", "war", "peace", "event", "step", "status",
            "generate", "tp", "list");

    private final ColonySMP plugin;

    public CivCommand(ColonySMP plugin) {
        this.plugin = plugin;
    }

    private CivManager civs() {
        return plugin.civs();
    }

    private CivWorld world() {
        return plugin.civs().world();
    }

    private static boolean may(CommandSender s, String node) {
        if (s.hasPermission(node)) return true;
        Text.send(s, "<red>You need the permission <white>" + node + "</white>.");
        return false;
    }

    private static String nm(String good) {
        TradeGoods.Item i = TradeGoods.get(good);
        return i == null ? good : i.name();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        run(sender, args);
        return true;
    }

    // ───────────── players ─────────────

    public void run(CommandSender s, String[] a) {
        if (!civs().enabled()) {
            Text.send(s, "<gray>The living world is switched off on this server (civilisations.enabled).");
            return;
        }
        if (!s.hasPermission("colonysmp.play")) {
            Text.send(s, "<red>You don't have permission.");
            return;
        }
        String sub = a.length == 0 ? "list" : a[0].toLowerCase(Locale.ROOT);
        String arg = a.length > 1 ? String.join("_", Arrays.copyOfRange(a, 1, a.length)) : null;
        Player p = s instanceof Player pl ? pl : null;
        try {
            switch (sub) {
                case "help" -> help(s);
                case "list" -> list(s);
                case "info" -> info(s, p, arg);
                case "map" -> map(s, p);
                case "relations", "diplomacy" -> relations(s, p, arg);
                case "economy" -> economy(s, p, arg);
                case "trade" -> trade(s, p, arg);
                case "contracts", "missions" -> contracts(s, p);
                case "reputation" -> reputation(s, p);
                case "history" -> history(s, p, arg);
                case "military" -> military(s, p, arg);
                case "wanted" -> wanted(s, p);
                default -> help(s);
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "/civ " + String.join(" ", a) + " failed", ex);
            Text.send(s, "<red>Something went wrong: " + Text.esc(String.valueOf(ex.getMessage())));
        }
    }

    private void help(CommandSender s) {
        Text.raw(s, "<gold><bold>The living world</bold></gold> <gray>(/civ or /colony civ)");
        for (String l : List.of("list <dark_gray>- every people and settlement you could know of", "info [settlement] <dark_gray>- a settlement and its people (the nearest if none is named)",
                "map <dark_gray>- settlements around you, with distances", "relations [people] <dark_gray>- who is friends, rivals or at war",
                "economy [settlement] <dark_gray>- what it makes, lacks and has to spare", "trade <dark_gray>- barter with the merchant of the settlement you are in",
                "contracts <dark_gray>- deliveries settlements are asking for, and yours", "reputation <dark_gray>- how you stand with each people",
                "history [people] <dark_gray>- their chronicle", "military [people] <dark_gray>- soldiers, strength and wars", "wanted <dark_gray>- where you are unwelcome")) {
            Text.raw(s, " <yellow>/civ " + l);
        }
        Text.raw(s, " <gray>There is no money in this economy: you swap items for items. Right-click a settlement's merchant to barter.");
    }

    private void list(CommandSender s) {
        CivWorld w = world();
        if (w.settlements.isEmpty()) {
            Text.send(s, "<gray>No settlements have been found yet. They appear on explored land, out of sight, a few at a time.");
            return;
        }
        Text.raw(s, "<gold><bold>Peoples of the world</bold></gold> <gray>(day " + w.day + ", " + CivSim.season(w.day) + ")");
        for (Civ c : w.civs.values()) {
            List<Settlement> mine = w.of(c.id);
            if (mine.isEmpty()) {
                Text.raw(s, " <dark_gray>" + Text.esc(c.name) + " (fallen)");
                continue;
            }
            Text.raw(s, " <yellow>" + Text.esc(c.name) + " <gray>- " + c.government.display + " under " + Text.esc(c.ruler()) + (w.atWar(c.id) ? " <red>(at war)" : ""));
            for (Settlement x : mine) {
                Text.raw(s, "   <white>" + Text.esc(x.name) + " <gray>" + x.type.display + ", " + Math.round(x.population) + " people <dark_gray>(" + x.world + " " + x.x + ", " + x.z + ")");
            }
        }
    }

    private Settlement pick(CommandSender s, Player p, String arg) {
        Settlement x = arg != null ? civs().settlement(arg) : p != null ? civs().nearest(p, 100_000) : null;
        if (x == null && arg != null && civs().civ(arg) != null) x = world().capital(civs().civ(arg).id);
        if (x == null) Text.send(s, arg == null ? "<gray>Which settlement? (/civ list)" : "<red>No settlement called " + Text.esc(arg) + ".");
        return x;
    }

    private Civ pickCiv(CommandSender s, Player p, String arg) {
        Civ c = arg != null ? civs().civ(arg) : null;
        if (c == null && arg == null && p != null) {
            Settlement x = civs().nearest(p, 100_000);
            c = x == null ? null : world().civs.get(x.civ);
        }
        if (c == null) Text.send(s, arg == null ? "<gray>Which people? (/civ list)" : "<red>No people called " + Text.esc(arg) + ".");
        return c;
    }

    private void info(CommandSender s, Player p, String arg) {
        Settlement x = pick(s, p, arg);
        if (x == null) return;
        if (p != null) {
            new SettlementMenu(plugin, p, x).open();
            return;
        }
        CivWorld w = world();
        Civ c = w.civs.get(x.civ);
        Text.raw(s, "<gold>" + x.name + "</gold> <gray>" + x.type.display + " of " + (c == null ? "?" : c.name) + " [" + x.id + "] " + x.state + " at " + x.world + " " + x.x + "," + x.y + "," + x.z);
        Text.raw(s, " people " + Math.round(x.population) + " (soldiers " + Math.round(x.soldiers) + "), level " + x.level + ", mood " + Math.round(x.happiness) + ", order "
                + Math.round(x.stability) + ", fed " + Math.round(x.fed * 100) + "%, stores worth " + Math.round(CivWorld.wealth(x)));
        if (c != null) Text.raw(s, " " + c.government.display + ", " + c.ruler() + " (" + c.leader.personality.name().toLowerCase() + "), learning " + String.format(Locale.ROOT, "%.1f", c.techLevel()));
    }

    private void map(CommandSender s, Player p) {
        if (p == null) {
            list(s);
            return;
        }
        List<Settlement> near = new ArrayList<>();
        for (Settlement x : world().settlements.values()) if (x.world.equals(p.getWorld().getName())) near.add(x);
        Location l = p.getLocation();
        near.sort((a, b) -> Double.compare(Math.hypot(a.x - l.getX(), a.z - l.getZ()), Math.hypot(b.x - l.getX(), b.z - l.getZ())));
        if (near.isEmpty()) {
            Text.send(p, "<gray>No settlements are known in this world yet.");
            return;
        }
        Text.raw(p, "<gold><bold>Settlements around you</bold>");
        for (int i = 0; i < Math.min(12, near.size()); i++) {
            Settlement x = near.get(i);
            double dx = x.x - l.getX(), dz = x.z - l.getZ();
            String dir = Math.abs(dx) > Math.abs(dz) * 2 ? (dx > 0 ? "east" : "west") : Math.abs(dz) > Math.abs(dx) * 2 ? (dz > 0 ? "south" : "north")
                    : (dz > 0 ? "south" : "north") + "-" + (dx > 0 ? "east" : "west");
            Civ c = world().civs.get(x.civ);
            Text.raw(p, " " + (x.alive() ? "<white>" : "<dark_gray>") + Text.esc(x.name) + " <gray>" + (x.alive() ? x.type.display : "ruins") + (c == null ? "" : " of " + Text.esc(c.name))
                    + " <yellow>" + Math.round(Math.hypot(dx, dz)) + " blocks " + dir + " <dark_gray>(" + x.x + ", " + x.z + ")");
        }
    }

    private void relations(CommandSender s, Player p, String arg) {
        CivWorld w = world();
        if (arg == null) {
            Text.raw(s, "<gold><bold>How the peoples stand</bold>");
            List<Civ> live = new ArrayList<>();
            for (Civ c : w.civs.values()) if (!w.of(c.id).isEmpty()) live.add(c);
            int lines = 0;
            for (int i = 0; i < live.size(); i++) {
                for (int j = i + 1; j < live.size(); j++) {
                    String st = w.standing(live.get(i).id, live.get(j).id);
                    if (st.equals("neutral") && w.treaties(live.get(i).id, live.get(j).id).isEmpty()) continue;
                    if (lines++ < 20) Text.raw(s, " <white>" + Text.esc(live.get(i).name) + " <gray>and <white>" + Text.esc(live.get(j).name) + "<gray>: " + st + treaties(w, live.get(i).id, live.get(j).id));
                }
            }
            if (lines == 0) Text.raw(s, " <gray>Nobody has made friends or enemies yet. <dark_gray>(/civ relations <people> for one people's view)");
            for (War war : w.wars.values()) Text.raw(s, " <red>War: " + Text.esc(w.civs.get(war.attacker).name) + " against " + Text.esc(w.civs.get(war.defender).name) + " over " + Text.esc(war.cause)
                    + " <gray>(day " + (w.day - war.startDay) + ", " + (war.score > 10 ? "the attacker is winning" : war.score < -10 ? "the defender is winning" : "evenly matched") + ")");
            return;
        }
        Civ c = pickCiv(s, p, arg);
        if (c == null) return;
        Text.raw(s, "<gold><bold>" + Text.esc(c.name) + "</bold></gold> <gray>and its neighbours");
        for (Civ o : w.civs.values()) {
            if (o == c || w.of(o.id).isEmpty()) continue;
            Text.raw(s, " <white>" + Text.esc(o.name) + "<gray>: " + w.standing(c.id, o.id) + " <dark_gray>(" + Math.round(w.relation(c.id, o.id)) + ")" + treaties(w, c.id, o.id));
        }
    }

    private static String treaties(CivWorld w, String a, String b) {
        StringBuilder t = new StringBuilder();
        for (Treaty x : w.treaties(a, b)) t.append(t.isEmpty() ? " <dark_gray>- " : ", ").append(x.display);
        return t.toString();
    }

    private void economy(CommandSender s, Player p, String arg) {
        Settlement x = pick(s, p, arg);
        if (x == null) return;
        CivWorld w = world();
        Map<String, Double> shortages = new LinkedHashMap<>(), surpluses = new LinkedHashMap<>();
        Prices.sort(x, shortages, surpluses);
        Text.raw(s, "<gold><bold>" + Text.esc(x.name) + "</bold></gold> <gray>- " + x.type.display + ", known for " + x.type.speciality());
        Text.raw(s, " <gray>Stores worth <white>" + Math.round(CivWorld.wealth(x)) + "</white> for <white>" + Math.round(x.population) + "</white> people; fed <white>" + Math.round(x.fed * 100) + "%");
        StringBuilder made = new StringBuilder();
        x.output.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue() * TradeGoods.worth(b.getKey()), a.getValue() * TradeGoods.worth(a.getKey()))).limit(8)
                .forEach(e -> made.append(made.isEmpty() ? "" : ", ").append(nm(e.getKey())).append(" ").append(Text.num(e.getValue())));
        Text.raw(s, " <yellow>Makes each day: <gray>" + (made.isEmpty() ? "nothing" : made));
        StringBuilder sh = new StringBuilder(), su = new StringBuilder();
        shortages.keySet().stream().limit(8).forEach(g -> sh.append(sh.isEmpty() ? "" : ", ").append(nm(g)).append(String.format(Locale.ROOT, " ×%.1f", Prices.worth(w, x, g, x.stock.get(g)) / TradeGoods.worth(g))));
        surpluses.keySet().stream().limit(8).forEach(g -> su.append(su.isEmpty() ? "" : ", ").append(nm(g)).append(" ").append(x.stock.get(g)));
        Text.raw(s, " <red>Short of <dark_gray>(and what it is worth there against the usual)<red>: <gray>" + (sh.isEmpty() ? "nothing" : sh));
        Text.raw(s, " <green>Plenty of: <gray>" + (su.isEmpty() ? "nothing" : su));
        int n = 0;
        for (Shipment c : w.shipments.values()) {
            if (!c.from.equals(x.id) && !c.to.equals(x.id) || n++ >= 4) continue;
            Settlement a = w.settlements.get(c.from), b = w.settlements.get(c.to);
            if (a == null || b == null) continue;
            Text.raw(s, " <gray>" + (c.bySea ? "Ship" : "Caravan") + " " + (c.stage == Shipment.Stage.OUTBOUND ? Text.esc(a.name) + " → " + Text.esc(b.name) : Text.esc(b.name) + " → " + Text.esc(a.name))
                    + ", arrives day " + c.arriveDay + ", carrying " + Math.round(c.worth()) + " worth of goods");
        }
    }

    private void trade(CommandSender s, Player p, String arg) {
        if (p == null) {
            Text.send(s, "Players only.");
            return;
        }
        Settlement x = arg != null ? civs().settlement(arg) : civs().at(p.getLocation());
        if (x == null) {
            Text.send(p, "<gray>You have to be in a settlement to trade. <dark_gray>(/civ map)");
            return;
        }
        String closed = civs().closed(p, x);
        if (closed != null) Text.send(p, "<red>" + Text.esc(closed));
        else new BarterMenu(plugin, p, x).open();
    }

    private void contracts(CommandSender s, Player p) {
        CivWorld w = world();
        Text.raw(s, "<gold><bold>Contracts</bold></gold> <gray>(take and deliver them at the settlement's merchant)");
        int n = 0;
        for (Contract c : w.contracts.values()) {
            boolean mine = p != null && c.state == Contract.State.TAKEN && p.getUniqueId().equals(c.takenBy);
            if (c.state != Contract.State.OPEN && !mine) continue;
            Settlement x = w.settlements.get(c.settlement);
            if (x == null || n++ >= 16) continue;
            Text.raw(s, " " + (mine ? "<green>[yours] " : "<yellow>") + Text.esc(x.name) + "<gray>: bring <white>" + c.wantQty + " " + nm(c.want) + "</white> for <green>" + c.rewardQty + " " + nm(c.reward)
                    + " <dark_gray>" + "★".repeat(c.difficulty) + " until day " + c.expiresDay);
        }
        if (n == 0) Text.raw(s, " <gray>Nothing on offer today.");
    }

    private void reputation(CommandSender s, Player p) {
        if (p == null) {
            Text.send(s, "Players only.");
            return;
        }
        Text.raw(p, "<gold><bold>How you stand</bold>");
        for (Civ c : world().civs.values()) {
            if (world().of(c.id).isEmpty()) continue;
            double r = c.reputation(p.getUniqueId());
            String word = r >= 60 ? "honoured" : r >= 25 ? "trusted" : r >= 5 ? "known" : r > -5 ? "a stranger" : r > -50 ? "distrusted" : "unwelcome";
            Text.raw(p, " <white>" + Text.esc(c.name) + "<gray>: " + Text.signed(Math.round(r)) + " <gray>(" + word + ")");
        }
        Text.raw(p, " <dark_gray>Fair trade and finished contracts raise it; abandoned contracts lower it. Good standing means better rates.");
    }

    private void history(CommandSender s, Player p, String arg) {
        Civ c = pickCiv(s, p, arg);
        if (c == null) return;
        Text.raw(s, "<gold><bold>The chronicle of " + Text.esc(c.name) + "</bold>");
        int from = Math.max(0, c.history.size() - 25);
        for (int i = from; i < c.history.size(); i++) Text.raw(s, " <gray>" + Text.esc(c.history.get(i)));
    }

    private void military(CommandSender s, Player p, String arg) {
        Civ c = pickCiv(s, p, arg);
        if (c == null) return;
        CivWorld w = world();
        Text.raw(s, "<gold><bold>The arms of " + Text.esc(c.name) + "</bold>");
        Text.raw(s, " <gray>Soldiers: <white>" + Math.round(w.soldiers(c.id)) + "</white>, strength <white>" + Math.round(w.strength(c.id)) + "</white>, military learning <white>"
                + c.tech(Tech.MILITARY) + "</white>, defence <white>" + c.tech(Tech.DEFENCE));
        for (Settlement x : w.of(c.id)) {
            Text.raw(s, "  <white>" + Text.esc(x.name) + "<gray>: " + Math.round(x.soldiers) + " soldiers, " + x.stock.get("iron_sword") + " swords, " + x.stock.get("bow") + " bows"
                    + (x.besiegedBy != null ? " <red>(besieged)" : ""));
        }
        for (War war : w.wars.values()) if (war.involves(c.id)) Text.raw(s, " <red>At war with " + Text.esc(w.civs.get(war.other(c.id)).name) + " over " + Text.esc(war.cause) + ".");
        Text.raw(s, " <dark_gray>Wars between peoples are fought in their books; armies do not march through the world yet.");
    }

    private void wanted(CommandSender s, Player p) {
        if (p == null) {
            Text.send(s, "Players only.");
            return;
        }
        int n = 0;
        for (Civ c : world().civs.values()) {
            if (c.reputation(p.getUniqueId()) <= -50 && !world().of(c.id).isEmpty()) {
                Text.raw(p, " <red>" + Text.esc(c.name) + " <gray>will not trade with you.");
                n++;
            }
        }
        if (n == 0) Text.send(p, "<gray>Nobody has shut their gates to you. <dark_gray>(Bounties and crime come in a later version.)");
    }

    // ───────────── /colony audit ─────────────

    private static String when(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(ms));
    }

    private static String row(Journal.Record r) {
        String col = r.state() == TxnState.COMMITTED ? "<green>" : r.state() == TxnState.PREPARED ? "<yellow>" : "<gray>";
        return " <dark_gray>" + r.id().substring(0, 8) + " " + when(r.created()) + " " + col + r.state() + " <white>" + Text.esc(r.playerName()) + " <gray>" + r.kind() + " @" + r.counterparty()
                + ": gave " + Text.esc(Line.describe(r.gives())) + " for " + Text.esc(Line.describe(r.takes())) + String.format(Locale.ROOT, " <dark_gray>(%.0f → %.0f)", r.valueIn(), r.valueOut())
                + (r.note().isEmpty() ? "" : " <yellow>" + Text.esc(r.note()));
    }

    private UUID player(CommandSender s, String name) {
        Player on = Bukkit.getPlayerExact(name);
        if (on != null) return on.getUniqueId();
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
        if (off == null) {
            try {
                return UUID.fromString(name);
            } catch (IllegalArgumentException e) {
                Text.send(s, "<red>No player called " + Text.esc(name) + " has been seen here.");
                return null;
            }
        }
        return off.getUniqueId();
    }

    public void audit(CommandSender s, String[] a) {
        if (!may(s, "colonysmp.admin.audit")) return;
        Journal j = civs().txns().journal();
        long now = System.currentTimeMillis();
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "player" -> {
                if (a.length < 2) {
                    Text.send(s, "Usage: /colony audit player <player>");
                    return;
                }
                investigate(s, a[1]);
            }
            case "transactions", "txns" -> {
                int n = a.length > 1 ? Math.max(1, Math.min(60, (int) num(a[1], 20))) : 20;
                Text.raw(s, "<gold><bold>The last " + n + " transactions</bold> <dark_gray>(id, time, state, who, what; worth given → received)");
                for (Journal.Record r : j.recent(null, n)) Text.raw(s, row(r));
            }
            case "flags" -> {
                Text.raw(s, "<gold><bold>Audit notes</bold> <dark_gray>(evidence for a person to weigh; nothing is done automatically)");
                for (Journal.Flag f : j.flags(null, 30)) Text.raw(s, flag(f));
            }
            case "resolve" -> {
                if (!may(s, "colonysmp.admin.audit.resolve")) return;
                Journal.Record r = a.length < 3 ? null : j.get(a[1]);
                if (r == null || r.state() != TxnState.PREPARED) {
                    Text.send(s, "Usage: /colony audit resolve <id> commit|rollback <gray>(for a transaction still in doubt; see /colony audit)");
                    return;
                }
                boolean commit = a[2].equalsIgnoreCase("commit");
                String out = civs().txns().resolve(r, commit, "settled by " + s.getName(), now);
                Text.send(s, "<green>" + (commit ? "Carried through" : "Undone") + ". <gray>" + Text.esc(out));
            }
            default -> {
                Journal.Totals day = j.totals(null, now - 86_400_000L), hour = j.totals(null, now - 3_600_000L);
                List<Journal.Record> doubt = j.inDoubt();
                Text.raw(s, "<gold><bold>Transaction audit</bold>");
                Text.raw(s, " <gray>Last hour: <white>" + hour.committed() + "</white> committed, <white>" + hour.rolledBack() + "</white> rolled back. Last day: <white>" + day.committed()
                        + "</white> committed, <white>" + day.rolledBack() + "</white> rolled back, worth " + Math.round(day.valueIn()) + " given for " + Math.round(day.valueOut()) + " received.");
                Text.raw(s, " <gray>In doubt (cut short by a crash, waiting for their players): " + (doubt.isEmpty() ? "<green>none" : "<yellow>" + doubt.size()));
                for (Journal.Record r : doubt) Text.raw(s, row(r));
                Text.raw(s, " <gray>Journal: commit #" + j.lastCommit() + ", the books contain up to #" + world().journalMark + (j.lastError() == null ? "" : " <red>last error: " + Text.esc(j.lastError())));
                List<Journal.Flag> flags = j.flags(null, 8);
                Text.raw(s, " <gray>Latest notes:" + (flags.isEmpty() ? " <green>none" : ""));
                for (Journal.Flag f : flags) Text.raw(s, flag(f));
                Text.raw(s, " <dark_gray>/colony audit player <name> · transactions [n] · flags · resolve <id> commit|rollback");
            }
        }
    }

    private static String flag(Journal.Flag f) {
        String col = f.severity().equals("ALERT") ? "<red>" : f.severity().equals("WARN") ? "<gold>" : "<gray>";
        return " <dark_gray>" + when(f.time()) + " " + col + f.severity() + " " + f.code() + " <white>" + Text.esc(f.playerName()) + " <gray>" + Text.esc(f.detail())
                + (f.txn().isEmpty() ? "" : " <dark_gray>[" + f.txn().substring(0, Math.min(8, f.txn().length())) + "]");
    }

    /** Everything the journal knows about one player, laid out for a person to judge. */
    public void investigate(CommandSender s, String name) {
        if (!may(s, "colonysmp.admin.audit")) return;
        UUID id = player(s, name);
        if (id == null) return;
        Journal j = civs().txns().journal();
        long now = System.currentTimeMillis();
        Journal.Totals hour = j.totals(id, now - 3_600_000L), day = j.totals(id, now - 86_400_000L), all = j.totals(id, 0);
        Text.raw(s, "<gold><bold>Investigation: " + Text.esc(name) + "</bold> <dark_gray>" + id);
        Text.raw(s, " <gray>Trades committed: <white>" + hour.committed() + "</white> this hour, <white>" + day.committed() + "</white> today, <white>" + all.committed() + "</white> on record. Rolled back: <white>"
                + all.rolledBack() + "</white>. In doubt: " + (all.inDoubt() == 0 ? "<green>0" : "<yellow>" + all.inDoubt()));
        Text.raw(s, String.format(Locale.ROOT, " <gray>Worth given <white>%.0f</white>, received <white>%.0f</white> on record (net %s<gray>); today net %s<gray>.", all.valueIn(), all.valueOut(),
                Text.signed(Math.round(all.valueOut() - all.valueIn())), Text.signed(Math.round(day.valueOut() - day.valueIn()))));
        Text.raw(s, " <dark_gray>A net gain over many trades means better-than-fair rates somewhere; contract rewards account for up to a third over.");
        StringBuilder rep = new StringBuilder();
        for (Civ c : world().civs.values()) if (Math.abs(c.reputation(id)) >= 1) rep.append(rep.isEmpty() ? "" : ", ").append(c.name).append(" ").append(Math.round(c.reputation(id)));
        Text.raw(s, " <gray>Standing: " + (rep.isEmpty() ? "a stranger everywhere" : Text.esc(rep.toString())) + ". Contracts in hand: <white>" + civs().contractsOf(id));
        List<Journal.Flag> flags = j.flags(id, 12);
        Text.raw(s, " <gray>Notes:" + (flags.isEmpty() ? " <green>none" : ""));
        for (Journal.Flag f : flags) Text.raw(s, flag(f));
        Text.raw(s, " <gray>Latest transactions:");
        for (Journal.Record r : j.recent(id, 12)) Text.raw(s, row(r));
        Text.raw(s, " <dark_gray>This is evidence, not a verdict. Nothing has been done to the player.");
    }

    // ───────────── /colony admin civ ─────────────

    private static double num(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void admin(CommandSender s, String[] a) {
        String sub = a.length == 0 ? "status" : a[0].toLowerCase(Locale.ROOT);
        if (!ADMIN.contains(sub)) {
            Text.send(s, "/colony admin civ " + String.join("|", ADMIN));
            return;
        }
        if (!may(s, "colonysmp.admin.civ." + sub)) return;
        CivWorld w = world();
        switch (sub) {
            case "list" -> list(s);
            case "status" -> {
                Text.raw(s, "<gold><bold>The living world</bold> <gray>" + (civs().enabled() ? "running" : "<red>switched off"));
                Text.raw(s, " <gray>Day <white>" + w.day + "</white> (" + CivSim.season(w.day) + "); " + w.alive().size() + " settlements alive of " + w.settlements.size() + ", "
                        + w.civs.size() + " peoples, " + w.shipments.size() + " caravans on the road, " + w.wars.size() + " wars, " + w.contracts.size() + " contracts.");
                long people = 0;
                for (Settlement x : w.alive()) people += Math.round(x.population);
                Text.raw(s, " <gray>People in the books: <white>" + people + "</white>; villagers standing in the world: <white>" + civs().bodies().active() + "</white> of "
                        + plugin.settings().civs.maxActiveNpcs + " allowed.");
                Text.raw(s, " <gray>Last day took <white>" + civs().lastStepMicros() + " µs</white>; " + civs().stepsRun() + " days simulated since start; " + civs().builder().queued()
                        + " building jobs waiting; " + w.regions.size() + " map squares considered.");
                Text.raw(s, " <gray>Journal commit #" + civs().txns().journal().lastCommit() + ", books at #" + w.journalMark + ", " + civs().txns().journal().inDoubt().size() + " in doubt.");
            }
            case "create" -> {
                if (!(s instanceof Player) && a.length < 4) {
                    Text.send(s, "Console: /colony admin civ create <type> <x> <z> [world] [people]");
                    return;
                }
                SettlementType type;
                try {
                    type = SettlementType.valueOf(a.length > 1 ? a[1].toUpperCase(Locale.ROOT) : "");
                } catch (IllegalArgumentException e) {
                    Text.send(s, "<red>Types: " + Arrays.toString(SettlementType.values()).toLowerCase(Locale.ROOT));
                    return;
                }
                org.bukkit.World bw;
                int x, z;
                String people = null;
                if (s instanceof Player p && (a.length < 4 || Double.isNaN(num(a[2], Double.NaN)))) {
                    bw = p.getWorld();
                    x = p.getLocation().getBlockX();
                    z = p.getLocation().getBlockZ();
                    if (a.length > 2) people = String.join("_", Arrays.copyOfRange(a, 2, a.length));
                } else {
                    x = (int) num(a[2], 0);
                    z = (int) num(a[3], 0);
                    bw = a.length > 4 && Bukkit.getWorld(a[4]) != null ? Bukkit.getWorld(a[4]) : s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
                    if (a.length > 5) people = a[5];
                }
                bw.getChunkAt(x >> 4, z >> 4);
                for (int cx = (x - 24) >> 4; cx <= (x + 24) >> 4; cx++) for (int cz = (z - 24) >> 4; cz <= (z + 24) >> 4; cz++) bw.getChunkAt(cx, cz);
                String problem = civs().builder().siteProblem(bw, x, z, true);
                if (problem != null) {
                    Text.send(s, "<red>Not there: " + problem + ".");
                    return;
                }
                Civ civ = people == null ? null : civs().civ(people);
                if (people != null && civ == null) {
                    Text.send(s, "<red>No people called " + Text.esc(people) + " (leave it out to found a new one).");
                    return;
                }
                Settlement made = civs().found(civ, type, new Location(bw, x, com.colonysmp.world.SettlementBuilder.ground(bw, x, z) + 1, z), new Random());
                Text.send(s, "<green>Founded " + Text.esc(made.name) + " <gray>(" + type.display + " of " + Text.esc(w.civs.get(made.civ).name) + ", id " + made.id + ") at " + x + ", " + z
                        + ". Its buildings go up within a second or two.");
            }
            case "remove" -> {
                Settlement x = a.length > 1 ? civs().settlement(a[1]) : null;
                if (x == null) {
                    Text.send(s, "Usage: /colony admin civ remove <settlement> [keep] <gray>(keep leaves the buildings standing)");
                    return;
                }
                civs().remove(x, !(a.length > 2 && a[2].equalsIgnoreCase("keep")));
                Text.send(s, "<green>Removed " + Text.esc(x.name) + ".");
            }
            case "inspect" -> {
                Settlement x = a.length > 1 ? civs().settlement(a[1]) : s instanceof Player p ? civs().nearest(p, 100_000) : null;
                if (x == null) {
                    Text.send(s, "Usage: /colony admin civ inspect <settlement>");
                    return;
                }
                info(s, null, x.id);
                Text.raw(s, " built " + x.built + " (level " + x.builtLevel + "), radius " + x.radius + ", " + x.structures.size() + " structures, market " + x.marketX + "," + x.marketY + "," + x.marketZ
                        + ", last day " + x.lastDay + ", hungry days " + x.hungryDays + ", supplied " + Math.round(x.supplied * 100) + "%");
                StringBuilder st = new StringBuilder();
                x.stock.snapshot().forEach((g, q) -> st.append(st.isEmpty() ? "" : ", ").append(g).append(" ").append(q).append(x.stock.reserved(g) > 0 ? " (" + x.stock.reserved(g) + " held)" : ""));
                Text.raw(s, " <gray>stores: " + st);
                Text.raw(s, " <gray>conditions: " + x.conditions + "; reservations: " + x.stock.reservations());
            }
            case "setstock" -> {
                Settlement x = a.length > 3 ? civs().settlement(a[1]) : null;
                String g = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
                if (x == null || !TradeGoods.known(g)) {
                    Text.send(s, "Usage: /colony admin civ setstock <settlement> <good> <quantity>");
                    return;
                }
                long q = Math.max(0, Math.min(1_000_000, (long) num(a[3], 0))), have = x.stock.get(g);
                if (q > have) x.stock.force(g, q - have);
                else x.stock.take(g, have - q);
                civs().txns().journal().flag(s instanceof Player p ? p.getUniqueId() : null, s.getName(), "INFO", "ADMIN_STOCK", x.name + " " + g + " " + have + " → " + x.stock.get(g), null, System.currentTimeMillis());
                plugin.requestSave();
                Text.send(s, "<green>" + Text.esc(x.name) + " now holds " + x.stock.get(g) + " " + nm(g) + ". <gray>(Noted in the audit log.)");
            }
            case "pop" -> {
                Settlement x = a.length > 2 ? civs().settlement(a[1]) : null;
                if (x == null) {
                    Text.send(s, "Usage: /colony admin civ pop <settlement> <people>");
                    return;
                }
                x.population = Math.max(0, Math.min(plugin.settings().civs.maxPopulation, num(a[2], x.population)));
                x.soldiers = Math.min(x.soldiers, x.population);
                plugin.requestSave();
                Text.send(s, "<green>" + Text.esc(x.name) + " now has " + Math.round(x.population) + " people.");
            }
            case "relation" -> {
                Civ c1 = a.length > 3 ? civs().civ(a[1]) : null, c2 = a.length > 3 ? civs().civ(a[2]) : null;
                if (c1 == null || c2 == null || c1 == c2) {
                    Text.send(s, "Usage: /colony admin civ relation <people> <people> <-100..100>");
                    return;
                }
                w.relations.put(CivWorld.pair(c1.id, c2.id), Math.max(-100, Math.min(100, num(a[3], 0))));
                plugin.requestSave();
                Text.send(s, "<green>" + Text.esc(c1.name) + " and " + Text.esc(c2.name) + " now stand at " + Math.round(w.relation(c1.id, c2.id)) + " (" + w.standing(c1.id, c2.id) + ").");
            }
            case "war", "peace" -> {
                Civ c1 = a.length > 2 ? civs().civ(a[1]) : null, c2 = a.length > 2 ? civs().civ(a[2]) : null;
                if (c1 == null || c2 == null || c1 == c2) {
                    Text.send(s, "Usage: /colony admin civ " + sub + " <people> <people>" + (sub.equals("war") ? " [cause]" : ""));
                    return;
                }
                War war = w.war(c1.id, c2.id);
                if (sub.equals("war")) {
                    if (war != null) Text.send(s, "<gray>They are at war already.");
                    else {
                        civs().sim().declare(w, c1, c2, a.length > 3 ? String.join(" ", Arrays.copyOfRange(a, 3, a.length)) : "an administrator's decree");
                        Text.send(s, "<green>" + Text.esc(c1.name) + " is now at war with " + Text.esc(c2.name) + ".");
                    }
                } else if (war == null) {
                    Text.send(s, "<gray>They aren't at war.");
                } else {
                    civs().sim().end(w, war, null, null, null, false);
                    Text.send(s, "<green>Peace between " + Text.esc(c1.name) + " and " + Text.esc(c2.name) + ".");
                }
                plugin.requestSave();
            }
            case "event" -> {
                Settlement x = a.length > 2 ? civs().settlement(a[1]) : null;
                if (x == null) {
                    Text.send(s, "Usage: /colony admin civ event <settlement> <blight|plague|festival|golden|strike|clear>");
                    return;
                }
                long d = w.day;
                switch (a[2].toLowerCase(Locale.ROOT)) {
                    case "blight", "famine" -> {
                        x.add("blight", "*", 1, d + 8);
                        x.add("output", "CROP", 0.35, d + 8);
                        x.add("output", "FOOD", 0.6, d + 8);
                    }
                    case "plague" -> x.add("plague", "*", 1, d + 10);
                    case "festival" -> {
                        x.add("festival", "*", 1, d + 5);
                        x.add("mood", "*", 10, d + 5);
                    }
                    case "golden" -> {
                        x.add("golden", "*", 1, d + 20);
                        x.add("output", "*", 1.25, d + 20);
                    }
                    case "strike" -> {
                        x.add("strike", "*", 1, d + 20);
                        x.add("output", "ORE", 1.4, d + 20);
                    }
                    case "clear" -> x.conditions.clear();
                    default -> {
                        Text.send(s, "<red>Events: blight, plague, festival, golden, strike, clear");
                        return;
                    }
                }
                w.news(x.id, x.civ, 2, "By decree: " + a[2].toLowerCase(Locale.ROOT) + " in " + x.name + ".");
                plugin.requestSave();
                Text.send(s, "<green>Done: " + a[2].toLowerCase(Locale.ROOT) + " in " + Text.esc(x.name) + ".");
            }
            case "step" -> {
                int days = a.length > 1 ? Math.max(1, Math.min(365, (int) num(a[1], 1))) : 1;
                long t0 = System.nanoTime();
                for (int i = 0; i < days; i++) civs().step();
                plugin.requestSave();
                Text.send(s, "<green>The world moved on " + days + " day(s) in " + (System.nanoTime() - t0) / 1_000_000 + " ms. It is day " + w.day + ".");
            }
            case "generate" -> {
                int before = w.settlements.size();
                civs().generator().consider();
                Text.send(s, w.settlements.size() > before ? "<green>A settlement was founded (see the console)." : "<gray>No square near any player was fit this time (unexplored, in sight, unfit or already considered).");
            }
            case "tp" -> {
                Settlement x = a.length > 1 ? civs().settlement(a[1]) : null;
                Location l = x == null ? null : civs().location(x);
                if (!(s instanceof Player p) || l == null) {
                    Text.send(s, "Usage (players): /colony admin civ tp <settlement>");
                    return;
                }
                p.teleport(l.add(1, 0, 1));
            }
            default -> Text.send(s, "/colony admin civ " + String.join("|", ADMIN));
        }
    }

    // ───────────── tab completion ─────────────

    private List<String> names(boolean people) {
        List<String> out = new ArrayList<>();
        if (people) for (Civ c : world().civs.values()) out.add(c.name.replace(' ', '_'));
        else for (Settlement x : world().settlements.values()) out.add(x.name.replace(' ', '_'));
        return out;
    }

    public List<String> complete(String[] a) {
        List<String> options = new ArrayList<>();
        if (a.length <= 1) options.addAll(SUBS);
        else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "info", "economy", "trade" -> options.addAll(names(false));
                case "relations", "diplomacy", "history", "military" -> options.addAll(names(true));
                default -> {
                }
            }
        }
        return filter(options, a);
    }

    public List<String> completeAdmin(String[] a) {
        List<String> options = new ArrayList<>();
        if (a.length <= 1) options.addAll(ADMIN);
        else if (a.length == 2) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "create" -> {
                    for (SettlementType t : SettlementType.values()) options.add(t.name().toLowerCase(Locale.ROOT));
                }
                case "relation", "war", "peace" -> options.addAll(names(true));
                case "remove", "inspect", "setstock", "pop", "event", "tp" -> options.addAll(names(false));
                default -> {
                }
            }
        } else if (a.length == 3) {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "relation", "war", "peace" -> options.addAll(names(true));
                case "event" -> options.addAll(List.of("blight", "plague", "festival", "golden", "strike", "clear"));
                case "setstock" -> {
                    for (TradeGoods.Item i : TradeGoods.all()) options.add(i.key());
                }
                default -> {
                }
            }
        }
        return filter(options, a);
    }

    private static List<String> filter(List<String> options, String[] a) {
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(last)) out.add(o);
        return out;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return complete(args);
    }
}
