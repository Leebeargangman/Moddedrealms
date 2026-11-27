package com.colonysmp.store;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.BuildOrder;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.CustomBlueprint;
import com.colonysmp.model.Job;
import com.colonysmp.model.Role;
import com.colonysmp.model.Status;
import com.colonysmp.model.Trait;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.ItemCodec;
import com.colonysmp.util.ItemGoods;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Brings colonies from a ColonySMP 1.x database into the new model: communes become colonies (founder ->
 * owner, commissars -> co-owners, comrades -> residents), rectangular claims become the chunks they covered,
 * the State Chest becomes colony storage, and citizens keep their names, traits, jobs, schooling and gear.
 * Wars in progress are not carried over. The old tables stay in the database (v1_*) as a record.
 */
public final class V1Importer {

    private final ColonySMP plugin;

    public V1Importer(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public int run(Database.V1 v1) {
        Map<String, Colony> made = new HashMap<>();
        for (String[] row : v1.colonies()) {
            if (plugin.colonies().get(row[0]) != null) continue;
            YamlConfiguration y = yaml(row[1]);
            if (y == null) continue;
            Colony c = colony(row[0], y);
            if (c != null) made.put(c.id, c);
        }
        for (String[] row : v1.citizens()) {
            Colony c = made.get(row[1]);
            YamlConfiguration y = c == null ? null : yaml(row[2]);
            if (y == null) continue;
            try {
                citizen(c, UUID.fromString(row[0]), y);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Skipped a 1.x citizen with a bad id: " + row[0]);
            }
        }
        for (Object[] row : v1.storage()) {
            Colony c = made.get((String) row[0]);
            ItemStack it = c == null ? null : ItemCodec.fromBytes((byte[]) row[1]);
            if (it != null) stash(c, it);
        }
        for (Colony c : made.values()) {
            plugin.colonies().put(c);
            c.log(com.colonysmp.model.ColonyEvent.Type.COLONY, plugin.sim().day(c), "Imported from ColonySMP 1.x with " + c.citizens.size() + " citizens.");
        }
        return made.size();
    }

    private static YamlConfiguration yaml(String data) {
        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(data);
            return y;
        } catch (InvalidConfigurationException e) {
            return null;
        }
    }

    private Colony colony(String id, ConfigurationSection s) {
        UUID founder;
        try {
            founder = UUID.fromString(s.getString("founder", ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String world = s.getString("world");
        String region = s.getString("region");
        if (world == null || region == null) return null;
        String[] r = region.split(",");
        if (r.length != 4) return null;
        Colony c = new Colony(id);
        c.name = s.getString("name", "Colony");
        c.owner = founder;
        c.world = world;
        ConfigurationSection m = s.getConfigurationSection("members");
        if (m != null) {
            for (String k : m.getKeys(false)) {
                try {
                    String old = m.getString(k, "MEMBER");
                    Role role = old.equals("FOUNDER") ? Role.OWNER : old.equals("OFFICER") ? Role.CO_OWNER : Role.RESIDENT;
                    c.members.put(UUID.fromString(k), role);
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
        c.members.put(founder, Role.OWNER);
        try {
            int minX = Integer.parseInt(r[0]), minZ = Integer.parseInt(r[1]), maxX = Integer.parseInt(r[2]), maxZ = Integer.parseInt(r[3]);
            for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
                for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    if (plugin.colonies().chunk(world, cx, cz) == null) c.territory.add(cx, cz);
                }
            }
        } catch (NumberFormatException e) {
            return null;
        }
        c.core = BlockPos.parse(s.getString("core"));
        c.chest = BlockPos.parse(s.getString("chest"));
        c.createdAt = s.getLong("created", System.currentTimeMillis());
        c.shieldUntil = s.getLong("shield-until");
        c.stability = s.getDouble("stability", 60);
        c.reputation = s.getInt("reputation", 50);
        c.questStage = Math.min(4, s.getInt("quest-stage", 1));
        c.founded = s.getBoolean("founded", c.core != null);
        long day = plugin.sim().day(c);
        c.treasury.credit(plugin.settings().startingTreasury, TxnType.ADMIN, "Imported colony grant", System.currentTimeMillis(), day);
        if (c.core != null) {
            Building hall = new Building(c.newBuildingId(), BuildingType.TOWN_HALL);
            hall.anchor = c.core;
            hall.min = c.core;
            hall.max = c.core;
            c.buildings.put(hall.id, hall);
        }
        ConfigurationSection bs = s.getConfigurationSection("buildings");
        if (bs != null) {
            for (String k : bs.getKeys(false)) {
                ConfigurationSection b = bs.getConfigurationSection(k);
                if (b == null) continue;
                BuildingType t = switch (b.getString("type", "")) {
                    case "HOUSE" -> BuildingType.HOUSE;
                    case "FARM" -> BuildingType.FARM;
                    case "TOWER" -> BuildingType.WATCHTOWER;
                    case "PRISON" -> BuildingType.PRISON;
                    case "SCHOOL" -> BuildingType.SCHOOL;
                    case "MINE" -> BuildingType.MINE;
                    default -> null;
                };
                if (t == null) continue;
                Building nb = new Building(c.newBuildingId(), t);
                nb.min = BlockPos.parse(b.getString("min"));
                nb.max = BlockPos.parse(b.getString("max"));
                nb.anchor = BlockPos.parse(b.getString("anchor"));
                for (String p : b.getStringList("beds")) add(nb.beds, p);
                for (String p : b.getStringList("doors")) add(nb.doors, p);
                if (t == BuildingType.SCHOOL) for (String p : b.getStringList("tiles")) add(nb.stations, p);
                else for (String p : b.getStringList("tiles")) add(nb.tiles, p);
                try {
                    nb.facing = BlockFace.valueOf(b.getString("facing", "NORTH"));
                } catch (IllegalArgumentException e) {
                    nb.facing = BlockFace.NORTH;
                }
                if (t == BuildingType.MINE) {
                    nb.dug = b.getInt("progress");
                    nb.exhausted = b.getBoolean("exhausted");
                }
                // checked again on the first night
                nb.checkedDay = -1;
                c.buildings.put(nb.id, nb);
            }
        }
        ConfigurationSection q = s.getConfigurationSection("build-queue");
        if (q != null) {
            for (String k : q.getKeys(false)) {
                ConfigurationSection b = q.getConfigurationSection(k);
                if (b == null) continue;
                BuildOrder o = BuildOrder.load(c.newBuildingId(), b);
                if (o != null) c.buildQueue.add(o);
            }
        }
        ConfigurationSection bps = s.getConfigurationSection("blueprints");
        if (bps != null) {
            for (String k : bps.getKeys(false)) {
                ConfigurationSection b = bps.getConfigurationSection(k);
                CustomBlueprint cb = b == null ? null : CustomBlueprint.load(b);
                if (cb != null) c.blueprints.put(CustomBlueprint.key(cb.name), cb);
            }
        }
        for (String enc : s.getStringList("spoils")) {
            ItemStack it = ItemCodec.decode(enc);
            if (it != null) stash(c, it);
        }
        return c;
    }

    private static void add(java.util.List<BlockPos> list, String s) {
        BlockPos p = BlockPos.parse(s);
        if (p != null) list.add(p);
    }

    private void citizen(Colony col, UUID id, ConfigurationSection s) {
        Citizen c = new Citizen(id);
        c.name = s.getString("name", "Settler");
        Job job = switch (s.getString("job", "NONE")) {
            case "FARMER" -> Job.FARMER;
            case "BUILDER" -> Job.BUILDER;
            case "LUMBERJACK" -> Job.FORESTER;
            case "MINER" -> Job.MINER;
            case "FISHER" -> Job.FISHER;
            case "HERDER" -> Job.ANIMAL_KEEPER;
            case "COOK" -> Job.COOK;
            case "SMITH" -> Job.BLACKSMITH;
            case "DOCTOR" -> Job.DOCTOR;
            case "TEACHER" -> Job.TEACHER;
            case "STUDENT" -> Job.STUDENT;
            case "GUARD" -> Job.GUARD;
            case "WARDEN" -> Job.WARDEN;
            default -> Job.UNEMPLOYED;
        };
        c.job = job;
        String status = s.getString("status", "CITIZEN");
        switch (status) {
            case "CHILD" -> {
                c.status = Status.CITIZEN;
                c.ageDays = 1;
                c.job = Job.UNEMPLOYED;
            }
            case "CAPTIVE" -> c.status = Status.CAPTIVE;
            case "PRISONER", "SLAVE", "REBEL" -> c.status = Status.PRISONER;
            default -> c.status = Status.CITIZEN;
        }
        if (c.status != Status.CITIZEN) c.job = Job.UNEMPLOYED;
        c.trait = Trait.parse(s.getString("trait"));
        c.skin = s.getString("skin", "plains");
        c.needs.maxHealth = plugin.settings().citizenHealth;
        c.needs.health = Math.min(c.needs.maxHealth, s.getDouble("health", c.needs.maxHealth));
        c.education = s.getDouble("education", 20);
        c.needs.energy = s.getDouble("rest", 90);
        c.needs.mood = s.getDouble("happiness", 60);
        c.needs.satiety = 40 + 50 * Math.max(0, Math.min(1, s.getDouble("fed", 1)));
        c.resistance = s.getDouble("resistance");
        c.origin = s.getString("origin", null);
        c.tool = ItemCodec.decode(s.getString("tool"));
        c.weapon = ItemCodec.decode(s.getString("weapon"));
        c.ranged = ItemCodec.decode(s.getString("bow"));
        c.ammo = s.getInt("arrows");
        for (int i = 0; i < 4; i++) c.armor[i] = ItemCodec.decode(s.getString("armor." + i));
        String last = s.getString("last");
        if (last != null) {
            String[] p = last.split(",");
            try {
                c.lastX = Double.parseDouble(p[0]);
                c.lastY = Double.parseDouble(p[1]);
                c.lastZ = Double.parseDouble(p[2]);
                c.hasLast = true;
            } catch (RuntimeException ignored) {
                // respawn at the Town Hall
            }
        }
        for (String enc : s.getStringList("carry")) {
            ItemStack it = ItemCodec.decode(enc);
            if (it != null) stash(col, it);
        }
        c.colonyId = col.id;
        c.remember(plugin.sim().day(col), "Came over from the old commune");
        col.citizens.put(c.id, c);
    }

    private static void stash(Colony c, ItemStack it) {
        String good = ItemGoods.goodOf(it);
        if (good != null) c.storage.force(good, it.getAmount());
        else c.equipment.force(it);
    }
}
