package com.colonysmp.model;

import com.colonysmp.core.Ledger;
import com.colonysmp.util.BlockPos;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A registered building: where it is, what was found in it at the last validation, its level, its workers'
 * local stock and the state of its work.
 */
public final class Building {

    public final int id;
    public BuildingType type;
    public int level = 1;
    /** Optional name given by a player. */
    public String name;

    // where it is
    public BlockPos anchor, min, max;
    public BlockFace facing = BlockFace.NORTH;
    public final List<BlockPos> beds = new ArrayList<>();
    public final List<BlockPos> doors = new ArrayList<>();
    /** Farmland (farms) or ground tiles (pens and yards). */
    public final List<BlockPos> tiles = new ArrayList<>();
    /** The tile columns of a yard, for "is this inside the fence" (rebuilt when the tiles change; see Pens). */
    public transient Set<Long> tileColumns;
    public transient int tileColumnsFor = -1;
    /** Workstations NPCs stand at (furnaces, anvils, lecterns...). */
    public final List<BlockPos> stations = new ArrayList<>();
    public final List<BlockPos> containers = new ArrayList<>();
    public int floor;
    public final Map<Feature, Integer> features = new EnumMap<>(Feature.class);

    /** More people than beds, or under three floor tiles each. */
    public boolean crowded(int occupants) {
        return occupants > Math.max(1, beds.size()) || floor < occupants * 3;
    }

    // validation
    public boolean valid = true;
    public String problem;
    public long checkedDay = -1;
    /** Days in a row it failed validation (it is removed after the grace period). */
    public int invalidDays;

    /** The workers' inputs and outputs, waiting to be used or carried to storage. */
    public final Ledger stock;

    // production
    public String recipe;
    public double progress;
    /** Reservation in the local stock for the batch in progress. */
    public String reservation;
    public final Set<String> disabled = new LinkedHashSet<>();
    public transient String waiting;

    // farms
    /** Preferred crop ("wheat", "carrots", "potatoes", "beetroots") or null to follow demand. */
    public String crop;
    /** Background crop growth (0-1 of a full growth cycle). */
    public double growth;

    /** Pens: animals counted by kind ("cow", "sheep"...). Farms: crops counted by kind ("wheat", "carrots"...). */
    public final Map<String, Integer> counts = new TreeMap<>();
    public int herdTarget = 8;
    /** Background produce progress (eggs, wool, milk). */
    public double produce;

    // mines
    public int dug;
    public boolean exhausted;

    /** Wear: 100 is perfect. Siege damage and unpaid upkeep lower it; engineers repair it. */
    public double condition = 100;
    /** Hours its workers put in today (services use it: a restaurant with no staff serves nobody). */
    public transient double staffedHours;
    public transient long bookDay = Long.MIN_VALUE;
    /** Who works here (rebuilt from the citizens' workplace field). */
    public final transient List<UUID> workers = new ArrayList<>();

    public Building(int id, BuildingType type) {
        this.id = id;
        this.type = type;
        this.stock = new Ledger("b" + id + "-");
    }

    public boolean contains(BlockPos p) {
        return min != null && max != null && p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y()
                && p.z() >= min.z() && p.z() <= max.z();
    }

    public boolean containsXZ(double x, double z, int pad) {
        return min != null && max != null && x >= min.x() - pad && x < max.x() + 1 + pad && z >= min.z() - pad && z < max.z() + 1 + pad;
    }

    /** Inside the room or yard (feet position), a block of slack below. */
    public boolean inside(double x, double y, double z) {
        if (min == null || max == null) return false;
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        return bx >= min.x() && bx <= max.x() && bz >= min.z() && bz <= max.z() && by >= min.y() - 1 && by <= max.y() + 1;
    }

    public String label() {
        return (name != null ? name : type.display) + " #" + id;
    }

    public int slots(Job job) {
        if (type == BuildingType.FARM && job == Job.FARMER) {
            // small, medium and large farms
            int base = tiles.size() >= 150 ? 4 : tiles.size() >= 50 ? 2 : 1;
            return base + (level - 1);
        }
        return type.slots(job, level);
    }

    public String size() {
        if (type == BuildingType.FARM) return tiles.size() >= 150 ? "Large" : tiles.size() >= 50 ? "Medium" : "Small";
        return "";
    }

    public int feature(Feature f) {
        return features.getOrDefault(f, 0);
    }

    /** Containers inside (warehouses and granaries count their storage this way). */
    public int containerCount() {
        return Math.max(containers.size(), feature(Feature.CONTAINER));
    }

    // ───────────── persistence ─────────────

    public void save(ConfigurationSection s) {
        s.set("type", type.name());
        s.set("level", level);
        s.set("name", name);
        s.set("anchor", str(anchor));
        s.set("min", str(min));
        s.set("max", str(max));
        s.set("facing", facing.name());
        s.set("beds", strs(beds));
        s.set("doors", strs(doors));
        s.set("tiles", strs(tiles));
        s.set("stations", strs(stations));
        s.set("containers", strs(containers));
        s.set("floor", floor);
        for (Map.Entry<Feature, Integer> e : features.entrySet()) s.set("features." + e.getKey().name(), e.getValue());
        s.set("valid", valid);
        s.set("problem", problem);
        s.set("checked-day", checkedDay);
        s.set("invalid-days", invalidDays);
        s.set("recipe", recipe);
        s.set("progress", progress);
        s.set("reservation", reservation);
        s.set("disabled", new ArrayList<>(disabled));
        s.set("crop", crop);
        s.set("growth", growth);
        for (Map.Entry<String, Integer> e : counts.entrySet()) s.set("counts." + e.getKey(), e.getValue());
        s.set("herd-target", herdTarget);
        s.set("produce", produce);
        s.set("dug", dug);
        s.set("exhausted", exhausted);
        s.set("condition", condition);
    }

    public static Building load(int id, ConfigurationSection s) {
        BuildingType t = BuildingType.parse(s.getString("type"));
        if (t == null) return null;
        Building b = new Building(id, t);
        b.level = Math.max(1, Math.min(t.maxLevel, s.getInt("level", 1)));
        b.name = s.getString("name");
        b.anchor = BlockPos.parse(s.getString("anchor"));
        b.min = BlockPos.parse(s.getString("min"));
        b.max = BlockPos.parse(s.getString("max"));
        try {
            b.facing = BlockFace.valueOf(s.getString("facing", "NORTH"));
        } catch (IllegalArgumentException e) {
            b.facing = BlockFace.NORTH;
        }
        parse(s.getStringList("beds"), b.beds);
        parse(s.getStringList("doors"), b.doors);
        parse(s.getStringList("tiles"), b.tiles);
        parse(s.getStringList("stations"), b.stations);
        parse(s.getStringList("containers"), b.containers);
        b.floor = s.getInt("floor");
        ConfigurationSection f = s.getConfigurationSection("features");
        if (f != null) {
            for (String k : f.getKeys(false)) {
                try {
                    b.features.put(Feature.valueOf(k), f.getInt(k));
                } catch (IllegalArgumentException ignored) {
                    // a feature from another version
                }
            }
        }
        b.valid = s.getBoolean("valid", true);
        b.problem = s.getString("problem");
        b.checkedDay = s.getLong("checked-day", -1);
        b.invalidDays = s.getInt("invalid-days");
        b.recipe = s.getString("recipe");
        b.progress = s.getDouble("progress");
        b.reservation = s.getString("reservation");
        b.disabled.addAll(s.getStringList("disabled"));
        b.crop = s.getString("crop");
        b.growth = s.getDouble("growth");
        ConfigurationSection a = s.getConfigurationSection("counts");
        if (a != null) for (String k : a.getKeys(false)) b.counts.put(k, a.getInt(k));
        b.herdTarget = s.getInt("herd-target", 8);
        b.produce = s.getDouble("produce");
        b.dug = s.getInt("dug", s.getInt("progress-mine"));
        b.exhausted = s.getBoolean("exhausted");
        b.condition = s.getDouble("condition", 100);
        return b;
    }

    static String str(BlockPos p) {
        return p == null ? null : p.toString();
    }

    static List<String> strs(List<BlockPos> l) {
        List<String> out = new ArrayList<>(l.size());
        for (BlockPos p : l) out.add(p.toString());
        return out;
    }

    static void parse(List<String> in, List<BlockPos> out) {
        for (String s : in) {
            BlockPos p = BlockPos.parse(s);
            if (p != null) out.add(p);
        }
    }
}
