package com.colonysmp.command;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Ledger;
import com.colonysmp.core.Reservation;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Building;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.npc.Npc;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /colony admin: server tools for inspecting, repairing and testing colonies. */
final class AdminCommand {

    private static final List<String> SUBS = List.of("civ", "investigate", "register", "pen", "reload", "save", "bypass", "give", "delete", "tp", "inspect", "citizen", "building", "reservations",
            "production", "sim", "perf", "db", "stuck", "events", "simulate", "setstock", "treasury", "addcitizen", "endwar", "stability", "shield", "traveller",
            "repair", "bodies", "demo", "raid", "event", "research", "request");

    private final ColonySMP plugin;
    private final Info info;

    AdminCommand(ColonySMP plugin, Info info) {
        this.plugin = plugin;
        this.info = info;
    }

    void run(CommandSender s, String[] a) {
        if (!s.hasPermission("colonysmp.admin")) {
            Text.send(s, "<red>You don't have permission.");
            return;
        }
        if (a.length == 0) {
            Text.send(s, "Admin: " + String.join(", ", SUBS));
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        if (sub.equals("civ")) {
            plugin.civCommand().admin(s, Arrays.copyOfRange(a, 1, a.length));
            return;
        }
        if (sub.equals("investigate")) {
            if (a.length < 2) Text.send(s, "Usage: /colony admin investigate <player>");
            else plugin.civCommand().investigate(s, a[1]);
            return;
        }
        Colony c = a.length > 1 ? plugin.colonies().byName(a[1]) : null;
        if (c == null && a.length > 1) c = plugin.colonies().get(a[1]);
        switch (sub) {
            case "reload" -> {
                plugin.reload();
                Text.send(s, "Configuration reloaded.");
            }
            case "save" -> {
                plugin.save();
                Text.send(s, "Saving.");
            }
            case "bypass" -> {
                if (s instanceof Player p) Text.send(p, "Bypass " + (plugin.toggleBypass(p) ? "<green>on</green>: you act with every colony's full permissions." : "<red>off</red>."));
            }
            case "give" -> {
                Player to = a.length > 2 ? Bukkit.getPlayerExact(a[2]) : s instanceof Player p ? p : null;
                ItemStack it = a.length > 1 ? plugin.items().byId(a[1].toLowerCase(Locale.ROOT)) : null;
                if (it == null && a.length > 1 && a[1].equalsIgnoreCase("core") && to != null) {
                    Colony own = plugin.colonies().of(to);
                    if (own != null) it = plugin.items().core(own.id, own.name);
                }
                if (to == null || it == null) {
                    Text.send(s, "Usage: /colony admin give <wand|book|marker|rope|baton|war_banner|core|christmas_sapling|present> [player]");
                    return;
                }
                ColonyCommand.give(to, it);
                Text.send(s, "Given.");
            }
            case "delete" -> {
                if (missing(s, c)) return;
                plugin.colonies().delete(c, "deleted by an administrator");
                Text.send(s, "Deleted.");
            }
            case "tp" -> {
                if (missing(s, c) || !(s instanceof Player p)) return;
                Location l = c.coreLocation();
                if (l == null) {
                    Text.send(s, "<red>That colony has no Town Hall.");
                    return;
                }
                p.teleport(l.add(0, 1.5, 0));
            }
            case "inspect" -> {
                if (missing(s, c)) return;
                plugin.commands().sendInfo(s, c);
                Text.raw(s, " <gray>id " + c.id + " · world " + c.world + " · last step " + Text.num(c.lastStepMs) + " ms · bodies " + plugin.npcs().count(c) + "/"
                        + plugin.npcs().wantedBodies(c) + " · last sim tick " + c.lastSim + " · last day " + c.lastDay);
                Text.raw(s, " <gray>buildings " + c.buildings.size() + " · orders " + c.buildQueue.size() + " · squads " + c.squads.size() + " · events " + c.events.size());
                for (String l : info.construction(c)) Text.raw(s, " " + l);
                for (Citizen ct : c.citizens.values()) {
                    Npc n = plugin.npcs().npc(ct);
                    Text.raw(s, " <white>" + Text.esc(ct.name) + "</white> <gray>" + ct.title() + (n == null ? " (no body)" : " @" + n.loc().getBlockX() + "," + n.loc().getBlockY() + ","
                            + n.loc().getBlockZ()) + ": " + Text.esc(ct.activity) + (ct.waiting != null ? " <yellow>[" + Text.esc(ct.waiting) + "]" : "")
                            + " <dark_gray>hunger " + Math.round(ct.needs.satiety) + " energy " + Math.round(ct.needs.energy) + " mood " + Math.round(ct.needs.mood));
                }
            }
            case "citizen" -> {
                if (missing(s, c)) return;
                Citizen ct = null;
                String name = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : "";
                for (Citizen x : c.citizens.values()) if (x.name.equalsIgnoreCase(name) || x.id.toString().startsWith(name)) ct = x;
                if (ct == null) {
                    Text.send(s, "<red>No citizen called that.");
                    return;
                }
                Text.raw(s, "<aqua><bold>" + Text.esc(ct.name) + "</bold> <gray>" + ct.id);
                for (String l : info.citizen(c, ct)) Text.raw(s, " " + l);
                Npc n = plugin.npcs().npc(ct);
                Text.raw(s, " <gray>body: " + (n == null ? "none (background)" : n.body.getLocation().getBlockX() + ", " + n.body.getLocation().getBlockY() + ", "
                        + n.body.getLocation().getBlockZ() + (n.mover.moving() ? " moving" : "") + (n.mover.blocked() ? " BLOCKED" : "")));
                var h = plugin.sim().logistics().current(ct);
                if (h != null) Text.raw(s, " <gray>haul: " + h.describe(c) + (h.picked ? " (carrying)" : ""));
            }
            case "building" -> {
                if (missing(s, c)) return;
                Building b = null;
                try {
                    b = a.length > 2 ? c.building(Integer.parseInt(a[2])) : null;
                } catch (NumberFormatException ignored) {
                    // none
                }
                if (b == null) {
                    for (Building x : c.buildings.values()) Text.raw(s, " <white>#" + x.id + " " + Text.esc(x.label()) + "</white> <gray>" + (x.valid ? "ok" : "invalid: " + x.problem));
                    return;
                }
                Text.raw(s, "<aqua><bold>" + Text.esc(b.label()));
                for (String l : info.building(c, b)) Text.raw(s, " " + l);
                Text.raw(s, " <gray>stock " + b.stock.usedSlots() + "/" + b.stock.capacity() + " slots: " + info.goods(b.stock.contents(), 20));
                for (Reservation r : b.stock.reservations()) Text.raw(s, " <gray>hold " + r.id + " by " + r.owner + ": " + r.goods());
                Text.raw(s, " <gray>worked in body: " + plugin.npcs().worked(c, b) + " · staffed hours today " + Text.num(b.staffedHours));
            }
            case "reservations" -> {
                if (missing(s, c)) return;
                int n = 0;
                for (Ledger l : plugin.colonies().ledgers(c)) {
                    for (Reservation r : l.reservations()) {
                        Text.raw(s, " <gray>" + r.id + " <white>" + r.owner + "</white> " + r.goods() + " (" + Text.duration(System.currentTimeMillis() - r.created) + " old)");
                        n++;
                    }
                }
                Text.send(s, n + " reservation(s). Hauls in progress:");
                for (String l : plugin.sim().logistics().describe(c)) Text.raw(s, " <gray>" + l);
            }
            case "production" -> {
                if (missing(s, c)) return;
                for (String l : info.production(c)) Text.raw(s, " " + l);
            }
            case "sim" -> {
                if (missing(s, c)) return;
                long t0 = System.nanoTime();
                plugin.sim().step(c);
                Text.send(s, "Stepped " + Text.esc(c.name) + " in " + Text.num((System.nanoTime() - t0) / 1e6) + " ms. Game time " + plugin.sim().gameTime(c)
                        + ", day " + plugin.sim().day(c) + ", time of day " + com.colonysmp.core.GameTime.clock(plugin.sim().dayTime(c)) + ".");
            }
            case "perf" -> {
                Text.send(s, "Simulation: " + plugin.sim().perf() + ". Bodies: " + plugin.npcs().all().size() + "/" + plugin.settings().maxBodiesTotal + ".");
                for (Colony x : plugin.colonies().list()) {
                    Text.raw(s, " <white>" + Text.esc(x.name) + "</white> <gray>step " + Text.num(x.lastStepMs) + " ms · " + x.citizens.size() + " citizens · bodies "
                            + plugin.npcs().count(x) + (x.active ? " · active" : ""));
                }
            }
            case "db" -> {
                var db = plugin.database();
                Text.send(s, "Database: " + db.saves() + " saves (" + db.failures() + " failed), last " + (db.lastSaveAt() == 0 ? "never" : Text.duration(System.currentTimeMillis() - db.lastSaveAt()) + " ago")
                        + " in " + db.lastSaveMillis() + " ms, " + db.lastRows() + " rows." + (db.lastError() == null ? "" : " Last error: " + db.lastError()));
            }
            case "stuck" -> {
                if (missing(s, c)) return;
                int n = 0;
                Location core = c.coreLocation();
                for (Npc x : plugin.npcs().of(c)) {
                    if (!x.mover.blocked() && a.length < 3) continue;
                    x.resetWork();
                    x.mover.stop();
                    if (core != null) {
                        x.body.teleport(plugin.npcs().spotNear(core, 2, 5));
                        x.mover.sync();
                    }
                    n++;
                }
                Text.send(s, "Brought " + n + " stuck citizen(s) back to the Town Hall. (Add 'all' to move everyone.)");
            }
            case "events" -> {
                if (missing(s, c)) return;
                int i = 0;
                for (ColonyEvent e : c.events) {
                    if (i++ >= 20) break;
                    Text.raw(s, " <dark_gray>Day " + e.day() + " " + e.type().display + "</dark_gray> <gray>" + Text.esc(e.message()));
                }
            }
            case "simulate" -> {
                if (missing(s, c)) return;
                double hours = a.length > 2 ? parse(a[2], 24) : 24;
                hours = Math.max(0.1, Math.min(24 * 14, hours));
                long t0 = System.nanoTime();
                plugin.sim().fastForward(c, hours);
                plugin.requestSave();
                Text.send(s, "Simulated " + Text.num(hours) + " hours of " + Text.esc(c.name) + " in " + Text.num((System.nanoTime() - t0) / 1e6) + " ms. (/colony admin inspect " + c.id + ")");
            }
            case "setstock" -> {
                if (missing(s, c) || a.length < 4) {
                    if (c != null) Text.send(s, "Usage: /colony admin setstock <colony> <good> <quantity>");
                    return;
                }
                String g = Goods.key(a[2]);
                if (!Goods.known(g)) {
                    Text.send(s, "<red>Unknown good.");
                    return;
                }
                long want = (long) parse(a[3], 0);
                long have = c.storage.get(g);
                if (want > have) c.storage.force(g, want - have);
                else c.storage.take(g, Math.min(have - want, c.storage.available(g)));
                c.log(ColonyEvent.Type.SIMULATION, plugin.sim().day(c), "An administrator set " + Goods.get(g).name() + " to " + c.storage.get(g) + ".");
                plugin.requestSave();
                Text.send(s, Goods.get(g).name() + " in storage: " + c.storage.get(g) + ".");
            }
            case "treasury" -> {
                if (missing(s, c) || a.length < 4) {
                    if (c != null) Text.send(s, "Usage: /colony admin treasury <colony> set|add <amount>");
                    return;
                }
                long v = (long) parse(a[3], 0);
                long now = System.currentTimeMillis(), day = plugin.sim().day(c);
                if (a[2].equalsIgnoreCase("set")) c.treasury.set(v, "Set by an administrator", now, day);
                else if (v >= 0) c.treasury.credit(v, TxnType.ADMIN, "Added by an administrator", now, day);
                else c.treasury.debitUpTo(-v, TxnType.ADMIN, "Removed by an administrator", now, day);
                plugin.requestSave();
                Text.send(s, "Treasury: " + Text.value(c.treasury.balance()) + ".");
            }
            case "register" -> {
                // registers a building at coordinates (the console's way of doing what the Building Marker does)
                if (missing(s, c)) return;
                com.colonysmp.model.BuildingType type = a.length > 5 ? com.colonysmp.model.BuildingType.parse(a[2]) : null;
                org.bukkit.World w = c.world();
                if (type == null || w == null) {
                    Text.send(s, "Usage: /colony admin register <colony> <type> <x> <y> <z>");
                    return;
                }
                org.bukkit.block.Block at = w.getBlockAt((int) parse(a[3], 0), (int) parse(a[4], 64), (int) parse(a[5], 0));
                var res = plugin.buildings().register(null, c, type, at, org.bukkit.block.BlockFace.NORTH);
                if (res.error() != null) Text.send(s, "<red>" + res.error());
                else {
                    plugin.sim().jobs().refreshWorkplaces(c);
                    plugin.requestSave();
                    Text.send(s, "Registered " + Text.esc(res.building().label()) + " (" + res.building().tiles.size() + " tiles, " + res.building().doors.size() + " doors or gates).");
                }
            }
            case "pen" -> {
                // counts a pen's animals inside and outside its fence, wherever its keeper is
                if (missing(s, c)) return;
                Building pen = null;
                try {
                    pen = a.length > 2 ? c.building(Integer.parseInt(a[2])) : null;
                } catch (NumberFormatException ignored) {
                    // none
                }
                org.bukkit.World w = c.world();
                if (pen == null || w == null || pen.min == null) {
                    Text.send(s, "Usage: /colony admin pen <colony> <building id>");
                    return;
                }
                int in = 0, out = 0, strays = 0;
                String tag = c.id + ":" + pen.id;
                org.bukkit.util.BoundingBox box = new org.bukkit.util.BoundingBox(pen.min.x() - 32, pen.min.y() - 8, pen.min.z() - 32, pen.max.x() + 33, pen.max.y() + 12, pen.max.z() + 33);
                for (org.bukkit.entity.Entity e : w.getNearbyEntities(box, e -> e instanceof org.bukkit.entity.Animals && !com.colonysmp.npc.NpcManager.isBody(e))) {
                    boolean ours = tag.equals(e.getPersistentDataContainer().get(com.colonysmp.util.Keys.PEN, org.bukkit.persistence.PersistentDataType.STRING));
                    if (com.colonysmp.npc.Pens.inside(pen, e.getLocation())) in++;
                    else if (ours) strays++;
                    else out++;
                }
                int gatesOpen = 0;
                for (com.colonysmp.util.BlockPos g : pen.doors) if (g.block(w).getBlockData() instanceof org.bukkit.block.data.Openable o && o.isOpen()) gatesOpen++;
                long[] g = com.colonysmp.npc.Mover.GATE_STATS;
                Text.send(s, "PEN " + pen.label() + ": inside=" + in + " strays=" + strays + " (this pen's animals outside its fence) wild-nearby=" + out + " gates=" + pen.doors.size()
                        + " open-now=" + gatesOpen + " | since start: gate-opened=" + g[0] + " waited=" + g[1] + " slipped-through-shut=" + g[2] + " shut-for-animal=" + g[3]
                        + " put-back=" + g[4] + " counts=" + pen.counts);
            }
            case "addcitizen" -> {
                if (missing(s, c)) return;
                Job j = a.length > 2 ? Job.parse(a[2]) : Job.UNEMPLOYED;
                Citizen ct = plugin.sim().population().create(c, Job.UNEMPLOYED, false);
                plugin.sim().housing().assign(c);
                if (j != null && j != Job.UNEMPLOYED) plugin.sim().jobs().assign(c, ct, j, null);
                else plugin.sim().jobs().autoAssign(c, ct);
                plugin.requestSave();
                Text.send(s, "Added " + Text.esc(ct.name) + " (" + ct.job.display + ").");
            }
            case "endwar" -> {
                if (missing(s, c)) return;
                Text.send(s, plugin.wars().forceEnd(c) ? "War ended." : "That colony isn't at war.");
            }
            case "stability" -> {
                if (missing(s, c) || a.length < 3) return;
                c.stability = Math.max(0, Math.min(100, parse(a[2], c.stability)));
                Text.send(s, "Stability " + Math.round(c.stability) + ".");
            }
            case "shield" -> {
                if (missing(s, c)) return;
                double h = a.length > 2 ? parse(a[2], 24) : 24;
                c.shieldUntil = h <= 0 ? 0 : System.currentTimeMillis() + (long) (h * 3600_000L);
                Text.send(s, h <= 0 ? "Shield removed." : "Shielded for " + Text.num(h) + " hours.");
            }
            case "traveller", "traveler" -> {
                if (missing(s, c)) return;
                Text.send(s, plugin.travelers().summon(c) ? "A traveller is on the way." : "<red>Couldn't (one is already there, or the colony isn't loaded).");
            }
            case "repair" -> {
                if (missing(s, c)) return;
                int n = 0;
                for (Ledger l : plugin.colonies().ledgers(c)) for (String fix : l.repair()) {
                    Text.raw(s, " <gray>" + fix);
                    n++;
                }
                plugin.sim().logistics().recover(c);
                plugin.buildings().changed(c);
                Text.send(s, n + " problem(s) fixed; hauls reset; housing and jobs rechecked.");
            }
            case "bodies" -> {
                for (Colony x : plugin.colonies().list()) Text.raw(s, " <white>" + Text.esc(x.name) + "</white> <gray>" + plugin.npcs().count(x) + " bodies, wanted "
                        + plugin.npcs().wantedBodies(x) + ", " + x.citizens.size() + " citizens" + (x.active ? "" : " (not loaded)"));
            }
            case "demo" -> demo(s, a);
            case "raid" -> {
                if (c == null) {
                    Text.send(s, "Usage: /colony admin raid <colony> [barbarians|undead] [size]");
                    return;
                }
                var kind = a.length > 2 ? com.colonysmp.raid.RaidManager.Kind.parse(a[2]) : com.colonysmp.raid.RaidManager.Kind.BARBARIANS;
                if (kind == null) kind = com.colonysmp.raid.RaidManager.Kind.BARBARIANS;
                int size = 0;
                if (a.length > 3) {
                    try {
                        size = Math.max(1, Math.min(60, Integer.parseInt(a[3])));
                    } catch (NumberFormatException ignored) {
                        // the usual size
                    }
                }
                if (size == 0) size = com.colonysmp.core.RaidPlan.size(c.population(), c.level(), c.raidsRepelled, c.bonus(com.colonysmp.core.Research.Effect.RAID_DEFENCE),
                        plugin.settings().raidMinSize, plugin.settings().raidMaxSize);
                String err = plugin.raids().start(c, kind, size);
                Text.send(s, err != null ? "<red>" + err : "A " + kind.raid + " of " + size + " is attacking " + Text.esc(c.name) + ".");
            }
            case "event" -> {
                var kind = a.length > 2 ? com.colonysmp.colony.Happenings.Kind.parse(a[2]) : null;
                if (c == null || kind == null) {
                    List<String> kinds = new ArrayList<>();
                    for (var k : com.colonysmp.colony.Happenings.Kind.values()) kinds.add(k.name().toLowerCase(Locale.ROOT));
                    Text.send(s, "Usage: /colony admin event <colony> <" + String.join("|", kinds) + ">");
                    return;
                }
                boolean ok = plugin.happenings().fire(c, kind, plugin.sim().day(c));
                Text.send(s, ok ? kind.display + " in " + Text.esc(c.name) + "." : "<red>That can't happen there right now (" + kind.display.toLowerCase() + " needs something the colony lacks, or it isn't loaded).");
            }
            case "research" -> {
                if (c == null || a.length < 3) {
                    Text.send(s, "Usage: /colony admin research <colony> <project|all>");
                    return;
                }
                if (a[2].equalsIgnoreCase("all")) {
                    for (var r : com.colonysmp.core.Research.values()) plugin.sim().academy().grant(c, r);
                    Text.send(s, Text.esc(c.name) + " now knows everything.");
                    return;
                }
                var r = com.colonysmp.core.Research.parse(String.join("_", Arrays.copyOfRange(a, 2, a.length)));
                if (r == null) {
                    Text.send(s, "<red>No such research.");
                    return;
                }
                plugin.sim().academy().grant(c, r);
                Text.send(s, Text.esc(c.name) + " now knows " + r.display + ".");
            }
            case "request" -> {
                if (c == null) {
                    Text.send(s, "Usage: /colony admin request <colony>");
                    return;
                }
                var r = plugin.requests().create(c, plugin.sim().day(c));
                Text.send(s, r == null ? "<red>Nobody in " + Text.esc(c.name) + " wants anything the stores lack." : "Request #" + r.id() + ": " + r.amount() + " " + Goods.get(r.good()).name() + ".");
            }
            default -> Text.send(s, "Admin: " + String.join(", ", SUBS));
        }
    }

    /**
     * /colony admin demo <name> [x z] [owner]: founds a test colony from the console, stocked with materials and
     * with a house and a farm ordered from the standard plans, to watch (or fast-forward) on a test server.
     */
    private void demo(CommandSender s, String[] a) {
        if (a.length < 2) {
            Text.send(s, "Usage: /colony admin demo <name> [x z] [owner]");
            return;
        }
        String name = a[1];
        String err = plugin.colonies().checkName(name, null);
        if (err != null) {
            Text.send(s, "<red>" + err);
            return;
        }
        org.bukkit.World w = s instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
        Location spawn = s instanceof Player p ? p.getLocation() : w.getSpawnLocation();
        int x = a.length > 3 ? (int) parse(a[2], spawn.getX()) : spawn.getBlockX() + 40, z = a.length > 3 ? (int) parse(a[3], spawn.getZ()) : spawn.getBlockZ();
        java.util.UUID owner = s instanceof Player p ? p.getUniqueId() : a.length > 4 ? Bukkit.getOfflinePlayer(a[4]).getUniqueId() : java.util.UUID.nameUUIDFromBytes(("demo:" + name).getBytes());
        int y = w.getHighestBlockYAt(x, z) + 1;
        Colony c = plugin.colonies().create(owner, name, w.getName());
        c.shieldUntil = 0;
        org.bukkit.block.Block core = w.getBlockAt(x, y, z);
        core.setType(org.bukkit.Material.LODESTONE);
        plugin.townHall().raise(s instanceof Player p ? p : null, c, core);
        if (c.core == null) {
            plugin.colonies().delete(c, "the demo couldn't be founded here");
            Text.send(s, "<red>Couldn't found the colony there (see the console). Try other coordinates.");
            return;
        }
        // plenty to build and live with
        String[][] goods = {{"oak_planks", "256"}, {"oak_log", "64"}, {"cobblestone", "192"}, {"glass_pane", "32"}, {"white_bed", "4"}, {"oak_door", "4"},
                {"torch", "32"}, {"dirt", "64"}, {"wheat_seeds", "64"}, {"carrot", "32"}, {"bread", "64"}, {"coal", "32"}, {"oak_stairs", "64"}, {"oak_slab", "64"},
                {"water_bucket", "2"}};
        for (String[] g : goods) {
            if (Goods.known(g[0])) c.storage.force(g[0], Long.parseLong(g[1]));
        }
        for (org.bukkit.Material m : new org.bukkit.Material[]{org.bukkit.Material.IRON_HOE, org.bukkit.Material.IRON_HOE, org.bukkit.Material.IRON_AXE,
                org.bukkit.Material.IRON_PICKAXE, org.bukkit.Material.IRON_SWORD, org.bukkit.Material.IRON_SWORD, org.bukkit.Material.SHIELD, org.bukkit.Material.BOW,
                org.bukkit.Material.FISHING_ROD, org.bukkit.Material.SHEARS}) {
            c.equipment.force(new ItemStack(m));
        }
        c.treasury.credit(1000, com.colonysmp.core.TxnType.ADMIN, "Demo funds", System.currentTimeMillis(), plugin.sim().day(c));
        // a house and a farm from the standard plans, for the builder to build
        org.bukkit.block.BlockFace f = org.bukkit.block.BlockFace.SOUTH;
        int[][] spots = {{x + 10, z - 2}, {x - 12, z - 2}};
        com.colonysmp.model.BuildingType[] types = {com.colonysmp.model.BuildingType.HOUSE, com.colonysmp.model.BuildingType.FARM};
        for (int i = 0; i < types.length; i++) {
            int ox = spots[i][0], oz = spots[i][1];
            int oy = w.getHighestBlockYAt(ox, oz) + 1;
            c.buildQueue.add(new com.colonysmp.model.BuildOrder(c.newBuildingId(), types[i], null, new com.colonysmp.util.BlockPos(ox, oy, oz), f, System.currentTimeMillis()));
        }
        plugin.sim().jobs().refreshWorkplaces(c);
        plugin.requestSave();
        Text.send(s, "<green>Founded the demo colony " + Text.esc(c.name) + " (id " + c.id + ") at " + x + ", " + y + ", " + z + " with "
                + c.citizens.size() + " settlers, stocked storage, and a house and a farm ordered. Watch it with /colony admin inspect " + c.id + ".");
    }

    private boolean missing(CommandSender s, Colony c) {
        if (c != null) return false;
        Text.send(s, "<red>Name a colony (by name or id).");
        return true;
    }

    private static double parse(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    List<String> complete(CommandSender s, String[] a) {
        if (a.length > 1 && a[0].equalsIgnoreCase("civ")) return plugin.civCommand().completeAdmin(Arrays.copyOfRange(a, 1, a.length));
        List<String> out = new ArrayList<>();
        if (!s.hasPermission("colonysmp.admin")) return out;
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (a.length <= 1) options.addAll(SUBS);
        else if (a.length == 2 && a[0].equalsIgnoreCase("give")) options.addAll(List.of(Items.WAND, Items.BOOK, Items.MARKER, Items.ROPE, Items.BATON, Items.WAR_BANNER, Items.CHRISTMAS_SAPLING, Items.PRESENT, "core"));
        else if (a.length == 2) for (Colony c : plugin.colonies().list()) options.add(c.id);
        else if (a.length == 3 && a[0].equalsIgnoreCase("treasury")) options.addAll(List.of("set", "add"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("addcitizen")) for (Job j : Job.values()) options.add(j.name().toLowerCase(Locale.ROOT));
        else if (a.length == 3 && a[0].equalsIgnoreCase("raid")) options.addAll(List.of("barbarians", "undead"));
        else if (a.length == 3 && a[0].equalsIgnoreCase("event")) for (var k : com.colonysmp.colony.Happenings.Kind.values()) options.add(k.name().toLowerCase(Locale.ROOT));
        else if (a.length == 3 && a[0].equalsIgnoreCase("research")) {
            options.add("all");
            for (var r : com.colonysmp.core.Research.values()) options.add(r.name().toLowerCase(Locale.ROOT));
        }
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(last)) out.add(o);
        return out;
    }
}
