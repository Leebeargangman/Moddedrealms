package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Ledger;
import com.colonysmp.core.Reservation;
import com.colonysmp.core.Txn;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.BuildOrder;
import com.colonysmp.model.Building;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.CustomBlueprint;
import com.colonysmp.model.Role;
import com.colonysmp.model.Squad;
import com.colonysmp.store.Snapshot;
import com.colonysmp.util.ItemCodec;
import com.colonysmp.util.Territory;
import com.colonysmp.util.Text;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/** Every colony on the server, indexed by id, land, member and citizen; and their mapping to saved rows. */
public final class ColonyManager {

    private final ColonySMP plugin;
    private final Map<String, Colony> colonies = new LinkedHashMap<>();
    /** world -> chunk key -> colony */
    private final Map<String, Map<Long, Colony>> land = new HashMap<>();
    private final Map<UUID, Citizen> citizenIndex = new HashMap<>();

    public ColonyManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── lookups ─────────────

    public Collection<Colony> all() {
        return Collections.unmodifiableCollection(colonies.values());
    }

    public List<Colony> list() {
        return new ArrayList<>(colonies.values());
    }

    public Colony get(String id) {
        return id == null ? null : colonies.get(id);
    }

    public Colony byName(String name) {
        if (name == null) return null;
        String n = name.trim();
        for (Colony c : colonies.values()) if (c.name.equalsIgnoreCase(n) || c.id.equalsIgnoreCase(n)) return c;
        String u = n.toLowerCase(Locale.ROOT).replace('_', ' ');
        for (Colony c : colonies.values()) if (c.name.toLowerCase(Locale.ROOT).replace('_', ' ').equals(u)) return c;
        return null;
    }

    public Colony at(Location l) {
        if (l == null || l.getWorld() == null) return null;
        return at(l.getWorld().getName(), l.getBlockX(), l.getBlockZ());
    }

    public Colony at(Block b) {
        return at(b.getWorld().getName(), b.getX(), b.getZ());
    }

    public Colony at(String world, int x, int z) {
        return chunk(world, x >> 4, z >> 4);
    }

    public Colony chunk(String world, int cx, int cz) {
        Map<Long, Colony> m = land.get(world);
        return m == null ? null : m.get(Territory.key(cx, cz));
    }

    /** The colony a player belongs to (the one they own first, else the first they joined). */
    public Colony of(UUID player) {
        Colony first = null;
        for (Colony c : colonies.values()) {
            Role r = c.role(player);
            if (r == null) continue;
            if (r == Role.OWNER) return c;
            if (first == null) first = c;
        }
        return first;
    }

    public Colony of(Player p) {
        return of(p.getUniqueId());
    }

    public List<Colony> coloniesOf(UUID player) {
        List<Colony> out = new ArrayList<>();
        for (Colony c : colonies.values()) if (c.isMember(player)) out.add(c);
        return out;
    }

    // ───────────── citizens ─────────────

    public Citizen citizen(UUID id) {
        return id == null ? null : citizenIndex.get(id);
    }

    public Colony colonyOf(Citizen c) {
        return c == null ? null : get(c.colonyId);
    }

    public void addCitizen(Colony col, Citizen c) {
        c.colonyId = col.id;
        col.citizens.put(c.id, c);
        citizenIndex.put(c.id, c);
    }

    public void removeCitizen(Citizen c) {
        Colony col = get(c.colonyId);
        if (col != null) {
            col.citizens.remove(c.id);
            for (Ledger l : List.of(col.storage)) l.releaseOwner("c:" + c.id);
        }
        citizenIndex.remove(c.id);
    }

    /** Moves a citizen to another colony (prisoners, defectors). Their home, work and squad stay behind. */
    public void transferCitizen(Citizen c, Colony to) {
        Colony from = get(c.colonyId);
        if (from != null) from.citizens.remove(c.id);
        c.home = 0;
        c.bed = null;
        c.workplace = 0;
        c.squad = 0;
        addCitizen(to, c);
    }

    public int totalCitizens() {
        return citizenIndex.size();
    }

    // ───────────── land ─────────────

    public void index(Colony c, int cx, int cz) {
        land.computeIfAbsent(c.world, k -> new HashMap<>()).put(Territory.key(cx, cz), c);
    }

    public void unindex(Colony c, int cx, int cz) {
        Map<Long, Colony> m = land.get(c.world);
        if (m != null) m.remove(Territory.key(cx, cz), c);
    }

    // ───────────── creating and dissolving ─────────────

    public String checkName(String name, Colony ignore) {
        String n = name == null ? "" : name.trim();
        if (n.length() < 3 || n.length() > 24) return "Colony names must be 3 to 24 characters.";
        if (!n.matches("[A-Za-z0-9 '_\\-]+")) return "Use letters, numbers, spaces and ' _ - only.";
        for (Colony c : colonies.values()) if (c != ignore && c.name.equalsIgnoreCase(n)) return "A colony called that already exists.";
        return null;
    }

    public String newId(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "colony";
        if (base.length() > 24) base = base.substring(0, 24);
        String id = base;
        int n = 2;
        while (colonies.containsKey(id)) id = base + "_" + n++;
        return id;
    }

    /** A new colony with no land yet: it is founded where its Town Hall Core is placed. */
    public Colony create(Player owner, String name) {
        return create(owner.getUniqueId(), name, owner.getWorld().getName());
    }

    public Colony create(UUID owner, String name, String world) {
        Colony c = new Colony(newId(name));
        c.name = name.trim();
        c.owner = owner;
        c.members.put(owner, Role.OWNER);
        c.world = world;
        c.createdAt = System.currentTimeMillis();
        c.shieldUntil = c.createdAt + (long) (plugin.settings().shieldNewHours * 3600_000L);
        c.reputation = plugin.settings().reputationStart;
        long day = plugin.sim().day(c);
        c.treasury.credit(plugin.settings().startingTreasury, TxnType.ADMIN, "Founding grant", System.currentTimeMillis(), day);
        colonies.put(c.id, c);
        plugin.requestSave();
        return c;
    }

    /** Dissolves a colony: bodies removed, core and chest broken, storage dropped at the Town Hall when it's loaded. */
    public void delete(Colony c, String reason) {
        plugin.wars().colonyRemoved(c);
        plugin.diplomacy().colonyRemoved(c);
        plugin.npcs().despawnColony(c);
        plugin.travelers().colonyRemoved(c);
        plugin.quests().colonyRemoved(c);
        plugin.raids().colonyRemoved(c);
        plugin.happenings().colonyRemoved(c);
        plugin.townHall().dissolve(c);
        for (Citizen ct : new ArrayList<>(c.citizens.values())) citizenIndex.remove(ct.id);
        c.citizens.clear();
        for (long k : new ArrayList<>(c.territory.keys())) unindex(c, Territory.cx(k), Territory.cz(k));
        for (Player p : c.onlineMembers()) Text.send(p, "<red>" + Text.esc(c.name) + " has been dissolved" + (reason == null ? "." : ": " + reason));
        colonies.remove(c.id);
        plugin.requestSave();
    }

    // ───────────── saving ─────────────

    /** Adds every colony's rows to a snapshot (and drains their unsaved logs into it). */
    public void snapshot(Snapshot s) {
        for (Colony c : colonies.values()) {
            s.colonyIds.add(c.id);
            s.colonies.add(new Snapshot.Row("", c.id, yaml(c::save)));
            for (Map.Entry<UUID, Role> e : c.members.entrySet()) s.members.add(new Snapshot.Member(c.id, e.getKey().toString(), e.getValue().name()));
            for (long k : c.territory.keys()) s.claims.add(new Snapshot.Claim(c.world, Territory.cx(k), Territory.cz(k), c.id));
            for (Citizen ct : c.citizens.values()) {
                plugin.npcs().remember(ct);
                s.citizens.add(new Snapshot.Row(c.id, ct.id.toString(), yaml(ct::save)));
                for (Map.Entry<String, Long> e : ct.carried.contents().entrySet()) s.stock.add(new Snapshot.Stock(c.id, "c" + ct.id, e.getKey(), e.getValue()));
            }
            for (Building b : c.buildings.values()) {
                s.buildings.add(new Snapshot.Row(c.id, String.valueOf(b.id), yaml(b::save)));
                stock(s, c.id, "b" + b.id, b.stock);
            }
            stock(s, c.id, "storage", c.storage);
            int slot = 0;
            for (ItemStack it : c.equipment.contents()) {
                try {
                    s.equipment.add(new Snapshot.Item(c.id, slot++, ItemCodec.bytes(it)));
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("An item in " + c.name + "'s armoury couldn't be saved: " + it.getType());
                }
            }
            for (BuildOrder o : c.buildQueue) s.orders.add(new Snapshot.Row(c.id, String.valueOf(o.id), yaml(o::save)));
            for (Squad q : c.squads.values()) s.squads.add(new Snapshot.Row(c.id, String.valueOf(q.id), yaml(q::save)));
            for (CustomBlueprint cb : c.blueprints.values()) s.blueprints.add(new Snapshot.Row(c.id, CustomBlueprint.key(cb.name), yaml(cb::save)));
            for (Txn t : c.treasury.drainUnsaved()) {
                s.txns.add(new Snapshot.TxnRow(c.id, t.id(), t.time(), t.day(), t.type().name(), t.amount(), t.balance(), t.memo()));
            }
            for (ColonyEvent e : c.unsavedEvents) s.events.add(new Snapshot.EventRow(c.id, e.id(), e.time(), e.day(), e.type().name(), e.message()));
            c.unsavedEvents.clear();
        }
    }

    private static void stock(Snapshot s, String colony, String owner, Ledger l) {
        for (Map.Entry<String, Long> e : l.contents().entrySet()) s.stock.add(new Snapshot.Stock(colony, owner, e.getKey(), e.getValue()));
        for (Reservation r : l.reservations()) {
            StringBuilder g = new StringBuilder();
            for (Map.Entry<String, Long> e : r.goods().entrySet()) {
                if (!g.isEmpty()) g.append(';');
                g.append(e.getKey()).append('=').append(e.getValue());
            }
            s.holds.add(new Snapshot.Hold(colony, owner, r.id, r.owner, r.created, g.toString()));
        }
    }

    /** A save failed: its log rows go back in the queue so they're written next time. */
    public void requeue(Snapshot s) {
        Map<String, List<Txn>> txns = new HashMap<>();
        for (Snapshot.TxnRow t : s.txns) {
            txns.computeIfAbsent(t.colony(), k -> new ArrayList<>()).add(new Txn(t.id(), t.time(), t.day(), TxnType.parse(t.type()), t.amount(), t.balance(), t.memo()));
        }
        txns.forEach((id, list) -> {
            Colony c = get(id);
            if (c != null) c.treasury.requeue(list);
        });
        for (Snapshot.EventRow e : s.events) {
            Colony c = get(e.colony());
            if (c != null) c.unsavedEvents.add(new ColonyEvent(e.id(), e.time(), e.day(), ColonyEvent.Type.parse(e.type()), e.message()));
        }
    }

    private static String yaml(java.util.function.Consumer<YamlConfiguration> writer) {
        YamlConfiguration y = new YamlConfiguration();
        writer.accept(y);
        return y.saveToString();
    }

    private YamlConfiguration parse(String what, String data) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(data);
            return y;
        } catch (InvalidConfigurationException e) {
            plugin.getLogger().severe(what + " is corrupt and was skipped: " + e.getMessage());
            return null;
        }
    }

    // ───────────── loading ─────────────

    public void load(Snapshot s) {
        colonies.clear();
        land.clear();
        citizenIndex.clear();
        for (Snapshot.Row r : s.colonies) {
            YamlConfiguration y = parse("Colony " + r.id(), r.data());
            if (y == null) continue;
            Colony c = Colony.load(r.id(), y);
            if (c == null) {
                plugin.getLogger().severe("Colony " + r.id() + " is missing its owner or world and was skipped");
                continue;
            }
            List<Txn> recent = new ArrayList<>();
            for (Snapshot.TxnRow t : s.txns) {
                if (t.colony().equals(c.id)) recent.add(new Txn(t.id(), t.time(), t.day(), TxnType.parse(t.type()), t.amount(), t.balance(), t.memo()));
            }
            c.treasury.load(Colony.savedCoins(y), Colony.savedNextTxn(y), recent);
            colonies.put(c.id, c);
        }
        for (Snapshot.Member m : s.members) {
            Colony c = colonies.get(m.colony());
            Role role = Role.parse(m.role());
            if (c == null || role == null) continue;
            try {
                c.members.put(UUID.fromString(m.player()), role);
            } catch (IllegalArgumentException ignored) {
                // a corrupt member row
            }
        }
        for (Colony c : colonies.values()) c.members.put(c.owner, Role.OWNER);
        for (Snapshot.Claim cl : s.claims) {
            Colony c = colonies.get(cl.colony());
            if (c == null || !cl.world().equals(c.world)) continue;
            if (chunk(cl.world(), cl.cx(), cl.cz()) != null) continue;
            c.territory.add(cl.cx(), cl.cz());
            index(c, cl.cx(), cl.cz());
        }
        for (Snapshot.Row r : s.citizens) {
            Colony c = colonies.get(r.colony());
            if (c == null) continue;
            YamlConfiguration y = parse("Citizen " + r.id(), r.data());
            if (y == null) continue;
            try {
                addCitizen(c, Citizen.load(UUID.fromString(r.id()), y));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Citizen " + r.id() + " has a bad id and was skipped");
            }
        }
        for (Snapshot.Row r : s.buildings) {
            Colony c = colonies.get(r.colony());
            if (c == null) continue;
            YamlConfiguration y = parse("Building " + r.colony() + "/" + r.id(), r.data());
            if (y == null) continue;
            try {
                Building b = Building.load(Integer.parseInt(r.id()), y);
                if (b != null) {
                    c.buildings.put(b.id, b);
                    c.nextBuildingId = Math.max(c.nextBuildingId, b.id + 1);
                }
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Building " + r.id() + " of " + c.name + " has a bad id and was skipped");
            }
        }
        for (Snapshot.Stock st : s.stock) {
            Colony c = colonies.get(st.colony());
            if (c == null) continue;
            Ledger l = ledger(c, st.owner());
            if (l != null) l.force(st.good(), st.qty());
            else plugin.getLogger().warning("Dropped " + st.qty() + " " + st.good() + " of " + c.name + " (owner " + st.owner() + " is gone)");
        }
        for (Snapshot.Hold h : s.holds) {
            Colony c = colonies.get(h.colony());
            Ledger l = c == null ? null : ledger(c, h.ledger());
            if (l == null) continue;
            Map<String, Long> goods = new LinkedHashMap<>();
            for (String part : h.goods().split(";")) {
                int i = part.indexOf('=');
                if (i <= 0) continue;
                try {
                    goods.put(part.substring(0, i), Long.parseLong(part.substring(i + 1)));
                } catch (NumberFormatException ignored) {
                    // skip
                }
            }
            l.restore(h.id(), h.holder(), h.created(), goods);
        }
        for (Snapshot.Item it : s.equipment) {
            Colony c = colonies.get(it.colony());
            ItemStack item = ItemCodec.fromBytes(it.item());
            if (c == null) continue;
            if (item == null) {
                plugin.getLogger().warning("Dropped an unreadable item in " + c.name + "'s armoury");
                continue;
            }
            c.equipment.force(item);
        }
        for (Snapshot.Row r : s.orders) {
            Colony c = colonies.get(r.colony());
            YamlConfiguration y = c == null ? null : parse("Build order " + r.id(), r.data());
            if (y == null) continue;
            try {
                BuildOrder o = BuildOrder.load(Integer.parseInt(r.id()), y);
                if (o != null) c.buildQueue.add(o);
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        for (Snapshot.Row r : s.squads) {
            Colony c = colonies.get(r.colony());
            YamlConfiguration y = c == null ? null : parse("Squad " + r.id(), r.data());
            if (y == null) continue;
            try {
                Squad q = Squad.load(Integer.parseInt(r.id()), y);
                c.squads.put(q.id, q);
                c.nextSquadId = Math.max(c.nextSquadId, q.id + 1);
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        for (Snapshot.Row r : s.blueprints) {
            Colony c = colonies.get(r.colony());
            YamlConfiguration y = c == null ? null : parse("Blueprint " + r.id(), r.data());
            if (y == null) continue;
            CustomBlueprint cb = CustomBlueprint.load(y);
            if (cb != null) c.blueprints.put(CustomBlueprint.key(cb.name), cb);
        }
        for (Snapshot.EventRow e : s.events) {
            Colony c = colonies.get(e.colony());
            if (c == null) continue;
            c.events.addLast(new ColonyEvent(e.id(), e.time(), e.day(), ColonyEvent.Type.parse(e.type()), e.message()));
            c.nextEventId = Math.max(c.nextEventId, e.id() + 1);
        }
        // every reservation must point at real stock
        for (Colony c : colonies.values()) {
            for (Ledger l : ledgers(c)) {
                for (String fix : l.repair()) plugin.getLogger().warning(c.name + ": " + fix);
            }
        }
        plugin.getLogger().info("Loaded " + colonies.size() + " colonies and " + citizenIndex.size() + " citizens");
    }

    /** The ledger a saved stock row belongs to. */
    public Ledger ledger(Colony c, String owner) {
        if (owner.equals("storage")) return c.storage;
        try {
            if (owner.startsWith("b")) {
                Building b = c.buildings.get(Integer.parseInt(owner.substring(1)));
                return b == null ? null : b.stock;
            }
            if (owner.startsWith("c")) {
                Citizen ct = c.citizens.get(UUID.fromString(owner.substring(1)));
                return ct == null ? null : ct.carried;
            }
        } catch (IllegalArgumentException ignored) {
            // a corrupt owner
        }
        return null;
    }

    public List<Ledger> ledgers(Colony c) {
        List<Ledger> out = new ArrayList<>();
        out.add(c.storage);
        for (Building b : c.buildings.values()) out.add(b.stock);
        for (Citizen ct : c.citizens.values()) out.add(ct.carried);
        return out;
    }

    /** Adds a loaded or imported colony (the importer). */
    public void put(Colony c) {
        colonies.put(c.id, c);
        for (long k : c.territory.keys()) index(c, Territory.cx(k), Territory.cz(k));
        for (Citizen ct : c.citizens.values()) citizenIndex.put(ct.id, ct);
    }

    public void logError(String what, Throwable t) {
        plugin.getLogger().log(Level.WARNING, what, t);
    }
}
