package com.colonysmp.building;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Recipes;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Feature;
import com.colonysmp.model.Perm;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Text;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers buildings, checks them against their requirements, upgrades and removes them, and works out how
 * much the colony can store. A building only works while it passes validation: the right structure, enough
 * floor space, its required blocks, an entrance people can reach, inside the colony and not overlapping
 * another building.
 */
public final class BuildingManager {

    public record Result(Building building, String error) {
        static Result ok(Building b) {
            return new Result(b, null);
        }

        static Result fail(String e) {
            return new Result(null, e);
        }
    }

    /** What a scan found where a building stands. */
    public static final class Scan {
        String error;
        BlockPos min, max, anchor;
        int floor;
        final Map<Feature, Integer> features = new EnumMap<>(Feature.class);
        final Map<Feature, List<BlockPos>> found = new EnumMap<>(Feature.class);
        final List<BlockPos> beds = new ArrayList<>(), doors = new ArrayList<>(), tiles = new ArrayList<>();
        /** Every block of the building's footprint, for the territory check. */
        final Set<BlockPos> area = new HashSet<>();
        BlockFace facing = BlockFace.NORTH;
        int height;

        static Scan fail(String e) {
            Scan s = new Scan();
            s.error = e;
            return s;
        }
    }

    private final ColonySMP plugin;

    public BuildingManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── scanning ─────────────

    /** Scans the building of a type around a block. */
    public Scan scan(BuildingType type, Block at, BlockFace facing, Colony c) {
        return switch (type.shape) {
            case ROOM -> room(at);
            case FIELD -> field(at, c);
            case PEN -> pen(at);
            case TOWER -> tower(at);
            case MINE -> mine(at, facing);
            case CORE -> core(c);
        };
    }

    private Scan room(Block start) {
        RoomScanner.Room r = RoomScanner.scan(start);
        if (!r.enclosed()) return Scan.fail(r.error());
        Scan s = new Scan();
        s.min = r.min();
        s.max = r.max();
        s.anchor = BlockPos.of(start);
        s.floor = r.floor();
        s.features.putAll(r.features());
        s.found.putAll(r.found());
        s.beds.addAll(r.beds());
        s.doors.addAll(r.doors());
        s.doors.addAll(r.ironDoors());
        s.area.addAll(r.interior());
        return s;
    }

    private Scan field(Block clicked, Colony c) {
        Block tile = clicked;
        if (!soil(tile.getType())) tile = clicked.getRelative(BlockFace.DOWN);
        if (!soil(tile.getType())) return Scan.fail("Click farmland (or a crop growing on it) to register a farm.");
        World w = clicked.getWorld();
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> tiles = new ArrayList<>();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        BlockPos s0 = BlockPos.of(tile);
        q.add(s0);
        seen.add(s0);
        BlockFace[] dirs = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        while (!q.isEmpty() && seen.size() < 1200) {
            BlockPos p = q.poll();
            Block b = p.block(w);
            if (soil(b.getType()) || tillable(b)) tiles.add(p);
            for (BlockFace f : dirs) {
                BlockPos n = p.relative(f);
                if (seen.contains(n) || !n.loaded(w) || c != null && !c.territory.contains(n)) continue;
                Material m = n.block(w).getType();
                if (soil(m) || m == Material.WATER) {
                    seen.add(n);
                    q.add(n);
                }
            }
        }
        if (tiles.size() > 800) tiles = tiles.subList(0, 800);
        Scan s = new Scan();
        s.tiles.addAll(tiles);
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (BlockPos t : tiles) {
            minX = Math.min(minX, t.x());
            maxX = Math.max(maxX, t.x());
            minZ = Math.min(minZ, t.z());
            maxZ = Math.max(maxZ, t.z());
            minY = Math.min(minY, t.y());
            maxY = Math.max(maxY, t.y());
            s.area.add(t);
        }
        s.min = new BlockPos(minX, minY, minZ);
        s.max = new BlockPos(maxX, maxY + 1, maxZ);
        s.anchor = tiles.get(0);
        s.floor = tiles.size();
        // composters by the field (level 2 and 3)
        for (int x = minX - 2; x <= maxX + 2; x++) {
            for (int z = minZ - 2; z <= maxZ + 2; z++) {
                for (int y = minY - 1; y <= maxY + 2; y++) {
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    if (w.getBlockAt(x, y, z).getType() == Material.COMPOSTER) {
                        s.features.merge(Feature.COMPOSTER, 1, Integer::sum);
                        s.found.computeIfAbsent(Feature.COMPOSTER, k -> new ArrayList<>()).add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return s;
    }

    private Scan pen(Block at) {
        PenScanner.Pen p = PenScanner.scan(at);
        if (!p.enclosed()) return Scan.fail(p.error());
        Scan s = new Scan();
        s.tiles.addAll(p.tiles());
        s.area.addAll(p.tiles());
        s.min = p.min();
        s.max = p.max();
        s.anchor = p.tiles().get(0);
        s.floor = p.tiles().size();
        s.doors.addAll(p.gates());
        s.features.putAll(p.features());
        s.found.putAll(p.found());
        return s;
    }

    private Scan tower(Block clicked) {
        Block stand = clicked.getRelative(BlockFace.UP);
        if (!stand.isPassable() || !stand.getRelative(BlockFace.UP).isPassable()) return Scan.fail("A guard needs two blocks of space to stand on top of the tower.");
        int h = column(clicked);
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) h = Math.max(h, column(clicked.getRelative(f)));
        if (h < 5) return Scan.fail("Too low: click the top of a tower at least 5 blocks tall (this is " + h + ").");
        Scan s = new Scan();
        BlockPos sp = BlockPos.of(stand);
        s.anchor = sp;
        s.min = new BlockPos(sp.x() - 2, sp.y() - h, sp.z() - 2);
        s.max = new BlockPos(sp.x() + 2, sp.y() + 1, sp.z() + 2);
        s.area.add(sp);
        s.height = h;
        return s;
    }

    private static int column(Block top) {
        int h = 0;
        Block b = top;
        while (h < 24 && !b.getType().isAir() && !b.isLiquid()) {
            h++;
            b = b.getRelative(BlockFace.DOWN);
        }
        return h;
    }

    private Scan mine(Block clicked, BlockFace facing) {
        if (!MineableBlocks.natural(clicked.getType())) return Scan.fail("Mines must start in natural ground: stone, dirt, grass, sand or gravel.");
        Scan s = new Scan();
        BlockPos at = BlockPos.of(clicked);
        s.anchor = at;
        s.min = at;
        s.max = at;
        s.area.add(at);
        s.facing = facing;
        return s;
    }

    private Scan core(Colony c) {
        if (c == null || c.core == null) return Scan.fail("The colony has no Town Hall Core.");
        World w = c.world();
        if (w != null && c.core.loaded(w) && c.core.block(w).getType() != Material.LODESTONE) return Scan.fail("The Town Hall Core is gone.");
        Scan s = new Scan();
        s.anchor = c.core;
        s.min = c.core;
        s.max = c.core;
        s.area.add(c.core);
        return s;
    }

    public static boolean soil(Material m) {
        return m == Material.FARMLAND || m == Material.SOUL_SAND;
    }

    public static boolean tillable(Block b) {
        Material m = b.getType();
        if (m != Material.DIRT && m != Material.GRASS_BLOCK && m != Material.DIRT_PATH) return false;
        Block up = b.getRelative(BlockFace.UP);
        return up.getType().isAir() || up.isReplaceable() && !up.isLiquid();
    }

    // ───────────── requirements ─────────────

    /** Why a scan doesn't meet a level's requirements, or null. */
    public String requirements(BuildingType type, int level, Scan s, World w) {
        BuildingType.Level req = type.level(level);
        List<String> missing = new ArrayList<>();
        for (Map.Entry<Feature, Integer> e : req.features().entrySet()) {
            int have = s.features.getOrDefault(e.getKey(), 0);
            if (have < e.getValue()) missing.add((e.getValue() - have) + " more " + e.getKey().display + (e.getValue() - have > 1 ? "s" : ""));
        }
        if (!missing.isEmpty()) return "Needs " + String.join(", ", missing) + " (level " + level + ").";
        if (s.floor < req.floor()) {
            String what = type.shape == BuildingType.Shape.FIELD ? "farmland tiles" : type.shape == BuildingType.Shape.PEN ? "tiles of fenced ground" : "blocks of floor";
            return "Too small: level " + level + " needs " + req.floor() + " " + what + " (has " + s.floor + ").";
        }
        if (type == BuildingType.FARM && s.tiles.size() < 9) return "Farms need at least 9 connected farmland tiles (this one has " + s.tiles.size() + ").";
        if (type.shape == BuildingType.Shape.PEN && s.doors.isEmpty()) return "The fence needs a gate so people and animals can get in.";
        if (type.shape == BuildingType.Shape.ROOM && (req.features().containsKey(Feature.DOOR) || req.features().containsKey(Feature.IRON_DOOR))) {
            if (!reachable(w, s)) return "Nobody can reach the door from outside: clear the way in front of it.";
        }
        if (type == BuildingType.FISHERY && water(w, s) < 20) return "A fishery must stand by open water (at least 20 water blocks within 8 blocks).";
        return null;
    }

    /** At least one door has standing room both inside and outside. */
    private static boolean reachable(World w, Scan s) {
        for (BlockPos d : s.doors) {
            if (!d.loaded(w)) return true;
            boolean in = false, out = false;
            for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                BlockPos n = d.relative(f);
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos p = n.add(0, dy, 0);
                    if (!RoomScanner.standable(w, p.x(), p.y(), p.z())) continue;
                    if (s.area.contains(p)) in = true;
                    else out = true;
                }
            }
            if (in && out) return true;
        }
        return false;
    }

    private static int water(World w, Scan s) {
        int n = 0;
        int cx = (s.min.x() + s.max.x()) / 2, cz = (s.min.z() + s.max.z()) / 2;
        for (int x = cx - 8; x <= cx + 8; x++) {
            for (int z = cz - 8; z <= cz + 8; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = s.min.y() - 3; y <= s.min.y() + 1; y++) {
                    if (w.getBlockAt(x, y, z).getType() == Material.WATER) {
                        n++;
                        break;
                    }
                }
            }
        }
        return n;
    }

    /** Overlap with another building, or not inside the colony's land. */
    private String placement(Colony c, BuildingType type, Scan s, Building self) {
        for (BlockPos p : s.area) {
            if (!c.territory.contains(p)) return "Part of it is outside your colony's land.";
        }
        for (Building b : c.buildings.values()) {
            if (b == self || b.min == null || b.max == null) continue;
            if (b.type.shape != type.shape && !(type.shape == BuildingType.Shape.ROOM && b.type.shape == BuildingType.Shape.ROOM)) continue;
            if (type.shape == BuildingType.Shape.ROOM || type.shape == BuildingType.Shape.TOWER) {
                if (b.anchor != null && s.area.contains(b.anchor) || intersects(s.min, s.max, b.min, b.max)) return "Overlaps " + b.label() + ".";
            } else if (type.shape == BuildingType.Shape.FIELD || type.shape == BuildingType.Shape.PEN) {
                for (BlockPos t : b.tiles) if (s.area.contains(t)) return "Overlaps " + b.label() + ".";
            } else if (type.shape == BuildingType.Shape.MINE && b.anchor != null && b.anchor.distSq(s.anchor) < 16) {
                return "Too close to " + b.label() + ".";
            }
        }
        return null;
    }

    static boolean intersects(BlockPos a1, BlockPos a2, BlockPos b1, BlockPos b2) {
        return a1.x() <= b2.x() && a2.x() >= b1.x() && a1.y() <= b2.y() && a2.y() >= b1.y() && a1.z() <= b2.z() && a2.z() >= b1.z();
    }

    /** The existing building this scan is (a re-registration), if any. */
    private Building existing(Colony c, BuildingType type, Scan s) {
        for (Building b : c.buildings.values()) {
            if (b.type.shape != type.shape) continue;
            switch (type.shape) {
                case ROOM -> {
                    if (b.anchor != null && s.area.contains(b.anchor)) return b;
                    for (BlockPos bed : s.beds) if (b.beds.contains(bed)) return b;
                }
                case FIELD, PEN -> {
                    for (BlockPos t : b.tiles) if (s.area.contains(t)) return b;
                }
                case TOWER, MINE -> {
                    if (b.anchor != null && b.anchor.distSq(s.anchor) < 9) return b;
                }
                default -> {
                }
            }
        }
        return null;
    }

    private void apply(Building b, Scan s) {
        b.min = s.min;
        b.max = s.max;
        b.anchor = s.anchor;
        b.floor = s.floor;
        b.features.clear();
        b.features.putAll(s.features);
        b.beds.clear();
        b.beds.addAll(s.beds);
        b.doors.clear();
        b.doors.addAll(s.doors);
        b.tiles.clear();
        b.tiles.addAll(s.tiles);
        b.stations.clear();
        b.containers.clear();
        for (Map.Entry<Feature, List<BlockPos>> e : s.found.entrySet()) {
            if (e.getKey() == Feature.CONTAINER || e.getKey() == Feature.BARREL) {
                for (BlockPos p : e.getValue()) if (!b.containers.contains(p)) b.containers.add(p);
            }
        }
        for (Feature f : b.type.level(b.type.maxLevel).features().keySet()) {
            if (!f.station()) continue;
            for (BlockPos p : s.found.getOrDefault(f, List.of())) if (!b.stations.contains(p)) b.stations.add(p);
        }
        if (b.type.shape == BuildingType.Shape.MINE) b.facing = s.facing;
    }

    // ───────────── registering ─────────────

    /**
     * Registers (or re-registers) a building of a type at a block. The player, if any, must be allowed to
     * manage buildings.
     */
    public Result register(Player p, Colony c, BuildingType type, Block at, BlockFace facing) {
        if (type == BuildingType.TOWN_HALL) return Result.fail("The Town Hall is the core block; it registers itself.");
        if (p != null && !plugin.access().can(p, c, Perm.MANAGE_BUILDINGS)) return Result.fail("You may not register buildings for " + Text.esc(c.name) + ".");
        if (type == BuildingType.MINE && c.buildings(BuildingType.MINE).size() >= plugin.settings().maxMines && existingMine(c, at) == null) {
            return Result.fail("Your colony already has " + plugin.settings().maxMines + " mines. Remove one first.");
        }
        Scan s = scan(type, at, facing, c);
        if (s.error != null) return Result.fail(s.error);
        World w = at.getWorld();
        Building b = existing(c, type, s);
        if (b != null && b.type != type) {
            return Result.fail("That's already registered as " + b.label() + ". Remove it first to use it as " + Text.a(type.display) + ".");
        }
        int level = b == null ? 1 : b.level;
        String req = requirements(type, level, s, w);
        if (req != null) return Result.fail(req);
        String place = placement(c, type, s, b);
        if (place != null) return Result.fail(place);
        if (b == null) {
            if (!c.territory.contains(s.anchor)) return Result.fail("It must be inside your colony's land.");
            b = new Building(c.newBuildingId(), type);
            c.buildings.put(b.id, b);
            c.log(ColonyEvent.Type.CONSTRUCTION, plugin.sim().day(c), (p == null ? "Registered " : p.getName() + " registered ") + b.label() + ".");
        }
        apply(b, s);
        b.valid = true;
        b.problem = null;
        b.invalidDays = 0;
        b.checkedDay = plugin.sim().day(c);
        b.stock.setCapacity(b.type.stockSlots(b.level), 0);
        changed(c);
        if (p != null) plugin.funnel().record(p.getUniqueId(), "first_building");
        return Result.ok(b);
    }

    // ───────────── detecting (the Building Marker) ─────────────

    /**
     * What stands around a Building Marker: the building it's already part of, or the types it could be
     * registered as (best match first), or why it's none of them.
     */
    public record Detection(Building existing, List<BuildingType> matches, boolean sure, String error) {
        static Detection fail(String e) {
            return new Detection(null, List.of(), false, e);
        }
    }

    /**
     * Works out what kind of building a block stands in: a room, a farm, a fenced yard or the top of a tower.
     * With {@code retype}, a building already registered there is listed as {@code existing} along with every
     * other type it could be instead; without, it is the only match.
     */
    public Detection detect(Colony c, Block at, boolean retype) {
        World w = at.getWorld();
        BuildingType.Shape shape;
        Scan s;
        if (soil(at.getRelative(BlockFace.DOWN).getType())) {
            s = field(at, c);
            shape = BuildingType.Shape.FIELD;
        } else if ((s = room(at)).error == null) {
            shape = BuildingType.Shape.ROOM;
        } else {
            // every yard needs a gate: walls and a door around the marker are a room that isn't closed, not a pen
            Scan pen = pen(at);
            if (pen.error == null && !pen.doors.isEmpty()) {
                s = pen;
                shape = BuildingType.Shape.PEN;
            } else if (elevated(at)) {
                s = tower(at.getRelative(BlockFace.DOWN));
                shape = BuildingType.Shape.TOWER;
            } else {
                return Detection.fail("No building found here. " + s.error
                        + " (A marker also works on a farm's farmland, inside a fenced yard with a gate, or on top of a tower.)");
            }
        }
        if (s.error != null) return Detection.fail(s.error);

        List<BuildingType> pool = new ArrayList<>();
        for (BuildingType t : BuildingType.values()) if (t.shape == shape && t != BuildingType.TOWN_HALL) pool.add(t);
        Building b = existing(c, pool.get(0), s);
        if (b != null && !retype) return new Detection(b, List.of(b.type), true, null);
        if (b == null && !c.territory.contains(s.anchor)) return Detection.fail("It must be inside your colony's land.");

        List<BuildingType> matches = new ArrayList<>();
        String placeErr = null;
        BuildingType closest = null;
        int closestMissing = Integer.MAX_VALUE;
        for (BuildingType t : pool) {
            if (requirements(t, 1, s, w) == null) {
                String place = placement(c, t, s, b);
                if (place == null) matches.add(t);
                else if (placeErr == null) placeErr = place;
                continue;
            }
            int m = missing(t, s);
            if (m < closestMissing) {
                closestMissing = m;
                closest = t;
            }
        }
        if (matches.isEmpty()) {
            if (placeErr != null) return Detection.fail(placeErr);
            return Detection.fail(closest == null ? "This doesn't meet any building's requirements."
                    : "Not a working building yet. Closest: " + closest.display + " - " + requirements(closest, 1, s, w));
        }
        final Scan scan = s;
        matches.sort((x, y) -> score(y, scan) - score(x, scan));
        boolean sure = matches.size() == 1 || score(matches.get(0), s) > score(matches.get(1), s);
        return new Detection(b, matches, sure, null);
    }

    /** How well a building type explains what's in a scan: the specific blocks it found that the type uses. */
    private static int score(BuildingType t, Scan s) {
        Set<Feature> uses = new HashSet<>();
        for (int lv = 1; lv <= t.maxLevel; lv++) uses.addAll(t.level(lv).features().keySet());
        int score = 0;
        for (Feature f : uses) {
            if (s.features.getOrDefault(f, 0) <= 0) continue;
            score += switch (f) {
                case DOOR -> 0;
                case CONTAINER, ARMOR_HOLDER -> 1;
                case BARREL, COOKER, SEAT, HAY -> 2;
                case BED -> 3;
                default -> 4;
            };
        }
        return score;
    }

    /** Roughly how much a scan lacks for a type's first level. */
    private static int missing(BuildingType t, Scan s) {
        BuildingType.Level req = t.level(1);
        int n = 0;
        for (Map.Entry<Feature, Integer> e : req.features().entrySet()) n += Math.max(0, e.getValue() - s.features.getOrDefault(e.getKey(), 0));
        if (s.floor < req.floor()) n++;
        return n;
    }

    /** Is this spot high above the ground around it and open to the sky, like the top of a tower (not an upper floor)? */
    private static boolean elevated(Block at) {
        Block top = at.getRelative(BlockFace.DOWN);
        if (top.getType().isAir() || top.isLiquid()) return false;
        for (int dy = 1; dy <= 6; dy++) if (!at.getRelative(0, dy, 0).isPassable()) return false;
        int open = 0;
        for (int dx = -2; dx <= 2; dx += 2) {
            for (int dz = -2; dz <= 2; dz += 2) {
                if (dx == 0 && dz == 0) continue;
                Block side = top.getRelative(dx, -2, dz);
                if (side.isPassable() && !side.isLiquid()) open++;
            }
        }
        return open >= 6;
    }

    private Building existingMine(Colony c, Block at) {
        BlockPos p = BlockPos.of(at);
        for (Building b : c.buildings(BuildingType.MINE)) if (b.anchor != null && b.anchor.distSq(p) < 9) return b;
        return null;
    }

    /** The Town Hall building record, made when the core is placed. */
    public Building createTownHall(Colony c) {
        Building hall = c.townHall();
        if (hall == null) {
            hall = new Building(c.newBuildingId(), BuildingType.TOWN_HALL);
            c.buildings.put(hall.id, hall);
        }
        hall.anchor = c.core;
        hall.min = c.core;
        hall.max = c.core;
        hall.valid = true;
        changed(c);
        return hall;
    }

    /** Re-checks a building where it stands. Returns the problem, or null if it's fine (or can't be checked now). */
    public String validate(Colony c, Building b) {
        World w = c.world();
        if (w == null || b.anchor == null || !b.anchor.loaded(w)) return null;
        Block at = b.anchor.block(w);
        if (b.type.shape == BuildingType.Shape.TOWER) at = at.getRelative(BlockFace.DOWN);
        Scan s = scan(b.type, at, b.facing, c);
        // a room whose anchor is now a block: try from its beds and doors
        if (s.error != null && b.type.shape == BuildingType.Shape.ROOM) {
            for (BlockPos bed : b.beds) {
                if (!bed.loaded(w)) continue;
                Scan t = room(bed.block(w).getRelative(BlockFace.UP));
                if (t.error == null) {
                    s = t;
                    break;
                }
            }
        }
        if (s.error != null && b.type.shape == BuildingType.Shape.FIELD) {
            for (BlockPos t : b.tiles) {
                if (!t.loaded(w)) continue;
                Block tb = t.block(w);
                if (soil(tb.getType()) || tillable(tb)) {
                    Scan f = field(tb, c);
                    if (f.error == null) {
                        s = f;
                        break;
                    }
                }
            }
        }
        if (s.error != null) return s.error;
        String req = requirements(b.type, b.level, s, w);
        if (req == null) req = placement(c, b.type, s, b);
        if (req != null) return req;
        if (b.type.shape == BuildingType.Shape.MINE) s.facing = b.facing;
        apply(b, s);
        return null;
    }

    /** Nightly check of every building; broken ones stop working and are removed after the grace period. */
    public void dailyCheck(Colony c, long day) {
        List<String> lost = new ArrayList<>(), broken = new ArrayList<>();
        for (Building b : new ArrayList<>(c.buildings.values())) {
            World w = c.world();
            if (w == null || b.anchor == null || !b.anchor.loaded(w)) continue;
            String problem = validate(c, b);
            b.checkedDay = day;
            if (problem == null) {
                if (!b.valid) c.log(ColonyEvent.Type.CONSTRUCTION, day, b.label() + " passes its inspection again.");
                b.valid = true;
                b.problem = null;
                b.invalidDays = 0;
                continue;
            }
            b.problem = problem;
            if (b.valid) broken.add(b.label() + ": " + problem);
            b.valid = false;
            b.invalidDays++;
            if (b.type != BuildingType.TOWN_HALL && b.invalidDays > plugin.settings().invalidGraceDays) {
                lost.add(b.label());
                remove(c, b, "it failed its inspection " + b.invalidDays + " days in a row: " + problem);
            }
        }
        for (String s : broken) c.log(ColonyEvent.Type.CONSTRUCTION, day, "Inspection failed: " + s);
        if (!broken.isEmpty() || !lost.isEmpty()) {
            for (Player p : c.onlineMembers()) {
                for (String s : broken) Text.send(p, "<yellow>Inspection failed - " + Text.esc(s) + " <gray>It stops working until fixed.");
                if (!lost.isEmpty()) Text.send(p, "<red>Unregistered: <white>" + String.join(", ", lost) + "</white>. Register them again once fixed.");
            }
        }
        changed(c);
    }

    // ───────────── upgrading and removing ─────────────

    /** Why a building can't be upgraded (null if it can), without doing it. */
    public String canUpgrade(Colony c, Building b) {
        int next = b.level + 1;
        if (next > b.type.maxLevel) return b.label() + " is already at its highest level.";
        if (b.type == BuildingType.TOWN_HALL) {
            int need = townHallPopulation(next);
            if (c.population() < need) return "A level " + next + " Town Hall needs a population of " + need + " (you have " + c.population() + ").";
        } else if (next > c.level()) {
            return "Buildings can't be a higher level than the Town Hall (level " + c.level() + "). Upgrade the Town Hall first.";
        }
        if (b.type != BuildingType.TOWN_HALL) {
            World w = c.world();
            if (w == null || b.anchor == null || !b.anchor.loaded(w)) return "Stand near " + b.label() + " so it can be inspected.";
            Block at = b.anchor.block(w);
            if (b.type.shape == BuildingType.Shape.TOWER) at = at.getRelative(BlockFace.DOWN);
            Scan s = scan(b.type, at, b.facing, c);
            if (s.error != null) return s.error;
            String req = requirements(b.type, next, s, w);
            if (req != null) return req;
        }
        long money = b.type.upgradeMoney(next);
        if (c.treasury.balance() < money) return "The upgrade costs " + Text.value(money) + "; the treasury has " + Text.value(c.treasury.balance()) + ".";
        String goods = missingGoods(c, b.type.upgradeGoods(next));
        if (goods != null) return "The upgrade needs " + goods + " in storage.";
        return null;
    }

    public static int townHallPopulation(int level) {
        return switch (level) {
            case 2 -> 5;
            case 3 -> 12;
            case 4 -> 25;
            default -> 45;
        };
    }

    public String upgrade(Player p, Colony c, Building b) {
        if (p != null && !plugin.access().can(p, c, Perm.MANAGE_BUILDINGS)) return "You may not upgrade buildings.";
        String why = canUpgrade(c, b);
        if (why != null) return why;
        int next = b.level + 1;
        long day = plugin.sim().day(c);
        Map<String, Long> goods = concreteGoods(c, b.type.upgradeGoods(next));
        if (goods == null) return "The materials just left storage.";
        var res = c.storage.reserve("upgrade:" + b.id, goods, System.currentTimeMillis());
        if (res == null) return "The materials are promised to other work.";
        if (c.treasury.debit(b.type.upgradeMoney(next), TxnType.CONSTRUCTION, "Upgraded " + b.label() + " to level " + next, System.currentTimeMillis(), day) == null) {
            c.storage.release(res.id);
            return "The treasury can't pay for it.";
        }
        c.storage.consume(res.id);
        b.level = next;
        b.stock.setCapacity(b.type.stockSlots(b.level), 0);
        c.log(ColonyEvent.Type.CONSTRUCTION, day, (p == null ? "" : p.getName() + " upgraded ") + b.label() + " to level " + next + ".");
        if (b.type == BuildingType.TOWN_HALL) {
            for (Player m : c.onlineMembers()) Text.title(m, "<gold>Settlement Level " + next, "<gray>" + Text.esc(c.name) + " grows", 10, 60, 20);
        }
        changed(c);
        return null;
    }

    /** Group goods ("#planks") resolved to what's actually in storage. */
    private Map<String, Long> concreteGoods(Colony c, Map<String, Integer> wanted) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : wanted.entrySet()) {
            long need = e.getValue();
            if (Recipes.isGroup(e.getKey())) {
                for (String m : Recipes.members(e.getKey())) {
                    if (need <= 0) break;
                    long take = Math.min(need, c.storage.available(m) - out.getOrDefault(m, 0L));
                    if (take <= 0) continue;
                    out.merge(m, take, Long::sum);
                    need -= take;
                }
                if (need > 0) return null;
            } else {
                if (c.storage.available(e.getKey()) < need) return null;
                out.merge(e.getKey(), need, Long::sum);
            }
        }
        return out;
    }

    private String missingGoods(Colony c, Map<String, Integer> wanted) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : wanted.entrySet()) {
            long have = 0;
            if (Recipes.isGroup(e.getKey())) for (String m : Recipes.members(e.getKey())) have += c.storage.available(m);
            else have = c.storage.available(e.getKey());
            if (have < e.getValue()) {
                out.add((e.getValue() - have) + " " + (Recipes.isGroup(e.getKey()) ? Recipes.groupName(e.getKey()) : Goods.get(e.getKey()).name()));
            }
        }
        return out.isEmpty() ? null : String.join(", ", out);
    }

    public static String costText(BuildingType t, int toLevel) {
        List<String> parts = new ArrayList<>();
        parts.add(Text.value(t.upgradeMoney(toLevel)));
        for (Map.Entry<String, Integer> e : t.upgradeGoods(toLevel).entrySet()) {
            parts.add(e.getValue() + " " + (Recipes.isGroup(e.getKey()) ? Recipes.groupName(e.getKey()) : Goods.get(e.getKey()).name()));
        }
        return String.join(", ", parts);
    }

    /** Unregisters a building: its workers and residents lose it, its local stock goes to storage. */
    public void remove(Colony c, Building b, String reason) {
        if (b.type == BuildingType.TOWN_HALL) return;
        c.buildings.remove(b.id);
        if (b.reservation != null) b.stock.release(b.reservation);
        for (Map.Entry<String, Long> e : b.stock.snapshot().entrySet()) c.storage.force(e.getKey(), e.getValue());
        b.stock.clear();
        c.storage.releaseOwner("haul:" + b.id);
        c.storage.releaseOwner("prod:" + b.id);
        plugin.sim().logistics().forget(c, b);
        long day = plugin.sim().day(c);
        for (Citizen ct : c.citizens.values()) {
            if (ct.workplace == b.id) {
                ct.workplace = 0;
                ct.remember(day, "Lost their workplace, " + b.label());
            }
            if (ct.home == b.id) {
                ct.home = 0;
                ct.bed = null;
            }
        }
        c.log(ColonyEvent.Type.CONSTRUCTION, day, b.label() + " was unregistered" + (reason == null ? "." : ": " + reason));
        plugin.npcs().buildingRemoved(c, b);
        changed(c);
    }

    // ───────────── capacity ─────────────

    /** Storage, granary and armoury space from the Town Hall level and the storage buildings. */
    public void applyCapacity(Colony c) {
        Settings s = plugin.settings();
        int general = s.hallSlotsPerLevel * c.level();
        int granary = 0, equipment = s.equipmentBase;
        for (Building b : c.buildings.values()) {
            if (!b.valid) continue;
            double levelBonus = 1 + s.warehouseLevelBonus * (b.level - 1);
            switch (b.type) {
                case WAREHOUSE -> general += (int) (b.containerCount() * s.slotsPerContainer * levelBonus);
                case GRANARY -> granary += (int) (b.containerCount() * s.slotsPerContainer * s.granaryFactor * levelBonus);
                case ARMOURY -> equipment += b.feature(Feature.ARMOR_HOLDER) * s.armourySlotsPerContainer * b.level;
                default -> {
                }
            }
        }
        double research = 1 + c.bonus(com.colonysmp.core.Research.Effect.STORAGE);
        c.storage.setCapacity((int) Math.round(general * research), (int) Math.round(granary * research));
        c.equipment.setCapacity(equipment);
    }

    /** Something about the colony's buildings changed. */
    public void changed(Colony c) {
        applyCapacity(c);
        for (Building b : c.buildings.values()) b.stock.setCapacity(b.type.stockSlots(b.level), 0);
        plugin.sim().housing().assign(c);
        plugin.sim().jobs().refreshWorkplaces(c);
        plugin.requestSave();
    }

    // ───────────── lookups ─────────────

    /** Beds in valid houses. */
    public int houseBeds(Colony c) {
        int n = 0;
        for (Building b : c.valid(BuildingType.HOUSE)) n += b.beds.size();
        return n;
    }

    public Building at(Colony c, BlockPos p) {
        for (Building b : c.buildings.values()) if (b.contains(p)) return b;
        return null;
    }

    /** Buildings of the given types that are working (valid). */
    public List<Building> working(Colony c, Collection<BuildingType> types) {
        List<Building> out = new ArrayList<>();
        for (Building b : c.buildings.values()) if (b.valid && types.contains(b.type)) out.add(b);
        return out;
    }

    public static boolean bedExists(World w, BlockPos bed) {
        return bed != null && bed.loaded(w) && Tag.BEDS.isTagged(bed.block(w).getType());
    }
}
