package com.colonysmp.building;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.BuildOrder;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.CustomBlueprint;
import com.colonysmp.model.Job;
import com.colonysmp.model.Perm;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The Blueprint Book: pick a building type or a copied structure, register what's built, order what isn't. */
public final class BlueprintManager implements Listener {

    private static final Color GOOD = Color.fromRGB(90, 255, 120), BAD = Color.fromRGB(255, 70, 70),
            DOOR = Color.fromRGB(255, 220, 60), BED = Color.fromRGB(230, 40, 60), WATER = Color.fromRGB(60, 120, 255),
            LADDER = Color.fromRGB(150, 100, 50), KNOWN = Color.fromRGB(80, 220, 255), BROKEN = Color.fromRGB(255, 120, 40),
            QUEUED = Color.fromRGB(255, 170, 60);

    private final ColonySMP plugin;
    /** Selected building type, or a copy's key (as "copy:<key>"). */
    private final Map<UUID, String> mode = new HashMap<>();
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public BlueprintManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── selection ─────────────

    private List<String> options(Player p) {
        List<String> out = new ArrayList<>();
        for (BuildingType t : BuildingType.values()) if (t != BuildingType.TOWN_HALL) out.add(t.name());
        Colony c = plugin.colonies().of(p);
        if (c != null) for (String k : c.blueprints.keySet()) out.add("copy:" + k);
        return out;
    }

    public String selection(Player p) {
        String m = mode.get(p.getUniqueId());
        List<String> opts = options(p);
        if (m == null || !opts.contains(m)) m = BuildingType.HOUSE.name();
        return m;
    }

    public BuildingType type(Player p) {
        String s = selection(p);
        return s.startsWith("copy:") ? null : BuildingType.valueOf(s);
    }

    public CustomBlueprint copy(Player p) {
        String s = selection(p);
        if (!s.startsWith("copy:")) return null;
        Colony c = plugin.colonies().of(p);
        return c == null ? null : c.blueprints.get(s.substring(5));
    }

    public void select(Player p, BuildingType t) {
        mode.put(p.getUniqueId(), t.name());
    }

    public void selectCopy(Player p, CustomBlueprint cb) {
        mode.put(p.getUniqueId(), "copy:" + CustomBlueprint.key(cb.name));
    }

    private String step(Player p, int dir) {
        List<String> opts = options(p);
        int i = opts.indexOf(selection(p));
        String next = opts.get(Math.floorMod(i + dir, opts.size()));
        mode.put(p.getUniqueId(), next);
        return describe(p, next);
    }

    private String describe(Player p, String sel) {
        if (sel.startsWith("copy:")) {
            Colony c = plugin.colonies().of(p);
            CustomBlueprint cb = c == null ? null : c.blueprints.get(sel.substring(5));
            return cb == null ? "?" : "<white>" + Text.esc(cb.name) + "</white> <dark_gray>- <gray>your copy, " + cb.size() + ", " + cb.solid() + " blocks";
        }
        BuildingType t = BuildingType.valueOf(sel);
        return "<white>" + t.display + "</white> <dark_gray>(" + t.category.display + ")</dark_gray> <gray>" + t.description
                + (Blueprint.of(t) != null ? " <gold>[can be ordered]" : "");
    }

    public static BlockFace cardinal(float yaw) {
        int i = Math.round(((yaw % 360) + 360) % 360 / 90f) & 3;
        return switch (i) {
            case 0 -> BlockFace.SOUTH;
            case 1 -> BlockFace.WEST;
            case 2 -> BlockFace.NORTH;
            default -> BlockFace.EAST;
        };
    }

    // ───────────── using the book ─────────────

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !Items.is(e.getItem(), Items.BOOK)) return;
        Player p = e.getPlayer();
        Action a = e.getAction();
        long now = System.currentTimeMillis();
        Long last = lastClick.get(p.getUniqueId());
        e.setCancelled(true);
        if (last != null && now - last < 200) return;
        lastClick.put(p.getUniqueId(), now);
        if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) {
            if (p.isSneaking() && a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
                register(p, e.getClickedBlock(), e.getBlockFace());
                return;
            }
            String what = step(p, p.isSneaking() ? -1 : 1);
            Fx.sound(p, "minecraft:item.book.page_turn", 1f, 1.1f);
            Text.send(p, "<yellow>Plan:</yellow> " + what);
        } else if ((a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) && p.isSneaking()) {
            order(p);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        mode.remove(e.getPlayer().getUniqueId());
        lastClick.remove(e.getPlayer().getUniqueId());
    }

    // ───────────── registering ─────────────

    public void register(Player p, Block clicked, BlockFace face) {
        Colony c = plugin.colonies().at(clicked);
        if (c == null || !plugin.access().can(p, c, Perm.MANAGE_BUILDINGS)) {
            Text.send(p, "<red>You can only register buildings in a colony where you manage buildings.");
            return;
        }
        BuildingType t = type(p);
        if (t == null) {
            Text.send(p, "<yellow>Copies are registered automatically when they're built. Turn the book to a building type to register one by hand.");
            return;
        }
        Block start = clicked;
        if (t.shape == BuildingType.Shape.ROOM) {
            Block feet = p.getLocation().getBlock();
            if (RoomScanner.open(feet) && feet.getLocation().distanceSquared(clicked.getLocation()) < 64) start = feet;
            else {
                start = clicked.getRelative(face);
                if (!RoomScanner.open(start)) start = clicked.getRelative(BlockFace.UP);
            }
        }
        BuildingManager.Result r = plugin.buildings().register(p, c, t, start, cardinal(p.getLocation().getYaw()));
        if (r.error() != null) {
            Text.send(p, "<red>" + r.error());
            Fx.sound(p, "minecraft:block.note_block.bass", 1f, 0.6f);
            return;
        }
        Building b = r.building();
        Fx.sound(p, "minecraft:entity.villager.work_cartographer", 1f, 1f);
        p.spawnParticle(Particle.HAPPY_VILLAGER, clicked.getLocation().add(0.5, 1.2, 0.5), 30, 0.6, 0.4, 0.6, 0);
        Text.send(p, "<green>Registered <white>" + b.label() + "</white> " + summary(b) + ".");
        String jobs = jobs(c, b);
        if (!jobs.isEmpty()) Text.raw(p, "<dark_gray>  Workers: " + jobs);
        plugin.quests().check(c);
    }

    String summary(Building b) {
        return switch (b.type) {
            case HOUSE, BARRACKS, INN -> "with <white>" + b.beds.size() + "</white> bed" + (b.beds.size() == 1 ? "" : "s");
            case PRISON -> "holding <white>" + b.beds.size() + "</white> prisoner" + (b.beds.size() == 1 ? "" : "s");
            case FARM -> "(" + b.size().toLowerCase() + ", <white>" + b.tiles.size() + "</white> tiles)";
            case ANIMAL_PEN, STABLES, TRAINING_GROUND, ARCHERY_RANGE -> "with <white>" + b.tiles.size() + "</white> tiles of ground";
            case WAREHOUSE, GRANARY -> "with <white>" + b.containerCount() + "</white> containers";
            case MINE -> "heading <white>" + b.facing.name().toLowerCase() + "</white>";
            default -> "(level " + b.level + ", " + b.floor + " floor)";
        };
    }

    String jobs(Colony c, Building b) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<Job, Integer> e : b.type.jobs(b.level).entrySet()) {
            int n = b.slots(e.getKey());
            int have = 0;
            for (Citizen ct : c.citizens.values()) if (ct.workplace == b.id && ct.job == e.getKey()) have++;
            out.add("<white>" + have + "/" + n + "</white> <gray>" + e.getKey().display);
        }
        return String.join("<dark_gray>, ", out);
    }

    // ───────────── ordering construction ─────────────

    private record Placement(BuildingType type, CustomBlueprint copy, BlockPos origin, BlockFace facing, Blueprint bp) {
        String label() {
            return copy != null ? copy.name : type.display;
        }
    }

    private Placement placement(Player p) {
        Block target = p.getTargetBlockExact(32);
        if (target == null) return null;
        BlockFace f = cardinal(p.getLocation().getYaw());
        BlockPos origin = BlockPos.of(target.getRelative(BlockFace.UP));
        CustomBlueprint cb = copy(p);
        if (cb != null) return new Placement(null, cb, origin, f, Blueprint.custom(cb));
        BuildingType t = type(p);
        Blueprint bp = t == null ? null : Blueprint.of(t);
        if (bp == null) return null;
        return new Placement(t, null, origin, f, bp);
    }

    /** Why this plan can't go here, or null. */
    private String check(Colony c, Placement pl) {
        if (c == null) return "Outside your colony";
        BlockPos[] bb = pl.bp.bounds(pl.origin, pl.facing);
        for (int x = bb[0].x(); x <= bb[1].x(); x++) {
            for (int z = bb[0].z(); z <= bb[1].z(); z++) {
                if (!c.territory.contains(x, z)) return "Doesn't fit inside your land";
            }
        }
        if (inside(bb, c.core) || inside(bb, c.chest)) return "Overlaps the Town Hall";
        for (Building b : c.buildings.values()) {
            if (b.min == null || b.max == null || b.type == BuildingType.MINE || b.type == BuildingType.TOWN_HALL) continue;
            if (BuildingManager.intersects(bb[0], bb[1], b.min, b.max)) return "Overlaps " + b.label();
        }
        for (BuildOrder j : c.buildQueue) {
            Blueprint o = Blueprint.of(c, j);
            if (o == null) continue;
            BlockPos[] ob = o.bounds(j.origin, j.facing);
            if (BuildingManager.intersects(bb[0], bb[1], ob[0], ob[1])) return "Overlaps a building already ordered";
        }
        return null;
    }

    private static boolean inside(BlockPos[] bb, BlockPos p) {
        return p != null && p.x() >= bb[0].x() && p.x() <= bb[1].x() && p.y() >= bb[0].y() - 1 && p.y() <= bb[1].y() && p.z() >= bb[0].z() && p.z() <= bb[1].z();
    }

    public void order(Player p) {
        Placement pl = placement(p);
        if (pl == null) {
            BuildingType t = type(p);
            if (t != null && Blueprint.of(t) == null) {
                Text.send(p, "<yellow>There's no standard plan for a " + t.display + ": build it yourself (or copy one with the wand) and register it.");
            } else {
                Text.send(p, "<red>Look at the ground where it should be built.");
            }
            return;
        }
        Colony c = plugin.colonies().at(p.getWorld().getName(), pl.origin.x(), pl.origin.z());
        if (c == null || !plugin.access().can(p, c, Perm.MANAGE_BUILDINGS)) {
            Text.send(p, "<red>You can only order construction in a colony where you manage buildings.");
            return;
        }
        String err = check(c, pl);
        if (err != null) {
            Text.send(p, "<red>Can't build here: " + err + ".");
            return;
        }
        if (c.buildQueue.size() >= 8) {
            Text.send(p, "<red>The builders already have 8 orders. Cancel one in the Construction menu.");
            return;
        }
        BuildOrder j = new BuildOrder(c.newBuildingId(), pl.type, pl.copy == null ? null : pl.copy.name, pl.origin, pl.facing, System.currentTimeMillis());
        c.buildQueue.add(j);
        c.log(ColonyEvent.Type.CONSTRUCTION, plugin.sim().day(c), p.getName() + " ordered a " + j.label() + ".");
        Fx.sound(p, "minecraft:entity.villager.work_mason", 1f, 1f);
        Text.send(p, "<green>Ordered " + (pl.copy != null ? "a copy of <white>" + Text.esc(pl.copy.name) : "a <white>" + pl.type.display)
                + "</white>. The builders will carry from storage:");
        List<Map.Entry<String, Integer>> needs = new ArrayList<>(pl.bp.needs().entrySet());
        needs.sort((a, b) -> b.getValue() - a.getValue());
        for (int i = 0; i < needs.size() && i < 14; i++) {
            Text.raw(p, "<dark_gray>  • <white>" + needs.get(i).getValue() + "x</white> <gray>" + needs.get(i).getKey());
        }
        if (needs.size() > 14) Text.raw(p, "<dark_gray>  • <gray>...and " + (needs.size() - 14) + " more kinds");
        boolean builders = false;
        for (Citizen ct : c.citizens.values()) if (ct.job == Job.BUILDER && ct.status.free()) builders = true;
        if (!builders) Text.send(p, "<yellow>You have no Builder! Register a Builder's Hut and assign one.");
        plugin.requestSave();
    }

    // ───────────── outlines ─────────────

    /** Every 5 ticks: outlines for players holding the book. */
    public void render() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!Items.is(p.getInventory().getItemInMainHand(), Items.BOOK)) continue;
            Colony c = plugin.colonies().at(p.getLocation());
            if (c != null && c.isMember(p.getUniqueId())) drawKnown(p, c);
            Placement pl = placement(p);
            String sel = selection(p);
            String name = sel.startsWith("copy:") ? (copy(p) == null ? "?" : Text.esc(copy(p).name)) : BuildingType.valueOf(sel).display;
            if (pl == null) {
                Text.bar(p, "<yellow>" + name + " <dark_gray>|</dark_gray> <white>Shift+Right-Click</white><gray>: register <dark_gray>|</dark_gray> <white>Right-Click</white><gray>: next plan");
                continue;
            }
            Colony here = plugin.colonies().at(p.getWorld().getName(), pl.origin.x(), pl.origin.z());
            boolean mine = here != null && plugin.access().can(p, here, Perm.MANAGE_BUILDINGS);
            String err = mine ? check(here, pl) : "Outside your colony";
            draw(p, pl, err == null);
            Text.bar(p, "<yellow>" + Text.esc(pl.label()) + "</yellow> " + (err == null ? "<green>✔ fits" : "<red>✘ " + err)
                    + " <dark_gray>|</dark_gray> <gold>Shift+Left-Click</gold><gray>: order <dark_gray>|</dark_gray> <white>Shift+Right-Click</white><gray>: register");
        }
    }

    private void draw(Player p, Placement pl, boolean ok) {
        Blueprint bp = pl.bp;
        BlockPos[] bb = bp.bounds(pl.origin, pl.facing);
        Color c = ok ? GOOD : BAD;
        boolean flat = pl.type == BuildingType.FARM;
        double y = bb[0].y() + 0.05;
        double x1 = bb[0].x(), z1 = bb[0].z(), x2 = bb[1].x() + 1, z2 = bb[1].z() + 1;
        Fx.line(p, x1, y, z1, x2, y, z1, c, 0.5, 1.1f);
        Fx.line(p, x1, y, z2, x2, y, z2, c, 0.5, 1.1f);
        Fx.line(p, x1, y, z1, x1, y, z2, c, 0.5, 1.1f);
        Fx.line(p, x2, y, z1, x2, y, z2, c, 0.5, 1.1f);
        if (!flat) {
            double top = bb[1].y() + 1;
            Fx.line(p, x1, y, z1, x1, top, z1, c, 0.5, 0.9f);
            Fx.line(p, x2, y, z1, x2, top, z1, c, 0.5, 0.9f);
            Fx.line(p, x1, y, z2, x1, top, z2, c, 0.5, 0.9f);
            Fx.line(p, x2, y, z2, x2, top, z2, c, 0.5, 0.9f);
        }
        for (Blueprint.Step s : bp.steps) {
            Color mark = switch (s.spec()) {
                case DOOR, IRON_DOOR -> DOOR;
                case BED -> BED;
                case WATER -> WATER;
                case LADDER -> LADDER;
                default -> null;
            };
            if (mark == null) continue;
            BlockPos w = bp.pos(pl.origin, pl.facing, s);
            Fx.dust(p, w.x() + 0.5, w.y() + 0.3, w.z() + 0.5, mark, 1.6f);
        }
    }

    private void drawKnown(Player p, Colony c) {
        double px = p.getX(), pz = p.getZ();
        for (Building b : c.buildings.values()) {
            if (b.min == null || b.max == null || b.type == BuildingType.TOWN_HALL) continue;
            if (Math.abs(b.min.x() - px) > 40 || Math.abs(b.min.z() - pz) > 40) continue;
            Color col = b.valid ? KNOWN : BROKEN;
            if ((b.type.shape == BuildingType.Shape.TOWER || b.type.shape == BuildingType.Shape.MINE) && b.anchor != null) {
                Fx.dust(p, b.anchor.x() + 0.5, b.anchor.y() + 1.2, b.anchor.z() + 0.5, col, 1.5f);
                continue;
            }
            boolean flat = b.type.shape == BuildingType.Shape.FIELD || b.type.shape == BuildingType.Shape.PEN;
            Fx.box(p, b.min.x(), b.min.y(), b.min.z(), b.max.x(), flat ? b.min.y() : b.max.y(), b.max.z(), col, 1.0, 0.8f, !flat);
        }
        for (BuildOrder j : c.buildQueue) {
            Blueprint bp = Blueprint.of(c, j);
            if (bp == null) continue;
            BlockPos[] bb = bp.bounds(j.origin, j.facing);
            if (Math.abs(bb[0].x() - px) > 40 || Math.abs(bb[0].z() - pz) > 40) continue;
            Fx.box(p, bb[0].x(), bb[0].y(), bb[0].z(), bb[1].x(), bb[1].y(), bb[1].z(), QUEUED, 1.0, 0.8f, true);
        }
    }

    public static World world(Colony c) {
        return c.world();
    }
}
