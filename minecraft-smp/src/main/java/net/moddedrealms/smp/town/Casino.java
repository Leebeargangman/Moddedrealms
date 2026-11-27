package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.title.Title;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.npc.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.TrapDoor;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.EntityTransformer;

/**
 * "The Golden Rat": an illegal casino dug out under the wilds. A ruined shack hides a ladder shaft down to the hall;
 * an emergency tunnel comes up 25 blocks away. Inside: slot machines, blackjack and roulette with NPC dealers, dice
 * gamblers, a bar, and the heist planner's back room. NPC patrons fill the hall while players are inside.
 * Every so often the Town Watch raids it: everyone inside gets a wanted star, and the patrons scatter or fight the
 * police, which keeps officers busy and gives players a chance to run. Saved first and reversible, like the bank.
 */
public final class Casino implements Listener {
    static final int HX = 13, HZ = 10, TUNNEL = 36;

    private final TownManager t;
    private final NamespacedKey key;
    private final File file;
    private final Random r = new Random();
    private final List<Location> slots = new ArrayList<>();
    private final Map<Location, Integer> slotPrice = new HashMap<>();
    private Location centre;
    private BoundingBox hall;
    private BoundingBox area;
    private String areaWorld;
    private UUID rouletteBoard;
    // raids
    private long nextRaid;
    private long raidUntil;
    private boolean warned;
    private int tick;

    private World w;
    private int cx, cy, cz;

    Casino(TownManager t) {
        this.t = t;
        this.key = new NamespacedKey(t.plugin(), "casino");
        this.file = new File(t.plugin().getDataFolder(), "casino.yml");
        load();
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        World vw = Bukkit.getWorld(y.getString("world", ""));
        if (vw == null) return;
        centre = new Location(vw, y.getInt("x"), y.getInt("y"), y.getInt("z"));
        areaWorld = vw.getName();
        hall = box(y, "hall");
        area = box(y, "area");
        for (String s : y.getStringList("slots")) {
            String[] a = s.split(",");
            Location l = new Location(vw, Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
            slots.add(l);
            slotPrice.put(l, Integer.parseInt(a[3]));
        }
        String b = y.getString("roulette-board");
        rouletteBoard = b == null ? null : UUID.fromString(b);
    }

    private static BoundingBox box(YamlConfiguration y, String k) {
        List<Double> v = y.getDoubleList(k);
        return v.size() == 6 ? new BoundingBox(v.get(0), v.get(1), v.get(2), v.get(3), v.get(4), v.get(5)) : null;
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        if (centre != null) {
            y.set("world", centre.getWorld().getName());
            y.set("x", centre.getBlockX());
            y.set("y", centre.getBlockY());
            y.set("z", centre.getBlockZ());
            y.set("hall", List.of(hall.getMinX(), hall.getMinY(), hall.getMinZ(), hall.getMaxX(), hall.getMaxY(), hall.getMaxZ()));
            y.set("area", List.of(area.getMinX(), area.getMinY(), area.getMinZ(), area.getMaxX(), area.getMaxY(), area.getMaxZ()));
            List<String> ss = new ArrayList<>();
            for (Location l : slots) ss.add(l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ() + "," + slotPrice.get(l));
            y.set("slots", ss);
            y.set("roulette-board", rouletteBoard == null ? null : rouletteBoard.toString());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            t.plugin().getLogger().warning("Could not save casino.yml: " + e.getMessage());
        }
    }

    public boolean ready() {
        return centre != null;
    }

    /** Inside the casino hall (raids, games). */
    public boolean inside(Location l) {
        return hall != null && l.getWorld() != null && l.getWorld().getName().equals(areaWorld) && hall.contains(l.toVector());
    }

    public boolean protects(Location l) {
        return area != null && l.getWorld() != null && l.getWorld().getName().equals(areaWorld) && area.contains(l.toVector());
    }

    public Location centre() {
        return centre == null ? null : centre.clone();
    }

    public UUID rouletteBoard() {
        return rouletteBoard;
    }

    public boolean raiding() {
        return System.currentTimeMillis() < raidUntil;
    }

    List<Player> playersInside() {
        List<Player> out = new ArrayList<>();
        if (centre == null) return out;
        for (Player p : centre.getWorld().getPlayers()) if (inside(p.getLocation()) && !t.exempt(p)) out.add(p);
        return out;
    }

    // ------------------------------------------------------------------ slot machines

    @EventHandler(priority = EventPriority.HIGH)
    public void onMachine(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND || slots.isEmpty()) return;
        Location l = e.getClickedBlock().getLocation();
        Integer price = slotPrice.get(l);
        if (price == null) return;
        e.setCancelled(true);
        t.games().slots(e.getPlayer(), price);
    }

    // ------------------------------------------------------------------ patrons and raids

    /** Every second. */
    void second() {
        if (centre == null || !t.enabled) return;
        tick++;
        long now = System.currentTimeMillis();
        List<Player> in = playersInside();
        if (in.isEmpty()) {
            if (raiding() && now > raidUntil - cfg().getLong("town.casino.raid-seconds", 180) * 1000L + 20_000) endRaid();
            nextRaid = 0;
            warned = false;
            return;
        }
        if (nextRaid == 0) nextRaid = now + minutes(cfg().getInt("town.casino.raid-minutes-min", 8), cfg().getInt("town.casino.raid-minutes-max", 20));
        if (raiding()) {
            if (now >= raidUntil) endRaid();
            return;
        }
        if (!warned && now >= nextRaid - 15_000) {
            warned = true;
            for (Player p : in) {
                Gangs.Gang g = t.gangs().of(p);
                if (g != null && g.perks.contains("lookouts")) {
                    p.sendMessage(SmpPlugin.mm("<gold>[" + g.tag + "] <yellow>Lookout: the Watch is heading for the gambling den! You have 15 seconds."));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 0.5f);
                }
            }
        }
        if (now >= nextRaid) raid(in);
        if (tick % 5 == 0) patrons(in.size());
    }

    private long minutes(int min, int max) {
        return (min + r.nextInt(Math.max(1, max - min + 1))) * 60_000L;
    }

    private void patrons(int players) {
        if (!t.canSpawnWalkers(w())) return;
        int have = 0;
        for (Walker p : t.walkers(Walker.Role.PATRON)) if (p.valid()) have++;
        int want = Math.min(cfg().getInt("town.casino.patrons", 8), 3 + players * 2);
        if (have >= want) return;
        Location spot = floorSpot();
        if (spot != null) spawnPatron(spot);
    }

    private World w() {
        return centre.getWorld();
    }

    private Location floorSpot() {
        for (int i = 0; i < 10; i++) {
            int x = centre.getBlockX() + r.nextInt(2 * HX - 3) - HX + 2, z = centre.getBlockZ() + r.nextInt(2 * HZ - 3) - HZ + 2;
            Block feet = w().getBlockAt(x, centre.getBlockY() + 1, z);
            if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable() && !feet.getRelative(0, -1, 0).isPassable()) {
                return feet.getLocation().add(0.5, 0, 0.5);
            }
        }
        return null;
    }

    private Walker spawnPatron(Location at) {
        String[] first = {"Vinnie", "Rosa", "Big Sal", "Lefty", "Dolores", "Tiny", "Mickey", "Velma", "Sly", "Duke", "Goldie", "Frankie"};
        String[] cols = {"#7A1F1F", "#1F3A7A", "#5A1F7A", "#1F1F1F", "#7A5A1F", "#2F2F2F"};
        Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        gear.put(EquipmentSlot.CHEST, Walker.dyed(Material.LEATHER_CHESTPLATE, cols[r.nextInt(cols.length)]));
        gear.put(EquipmentSlot.LEGS, Walker.dyed(Material.LEATHER_LEGGINGS, "#1A1A1A"));
        if (r.nextBoolean()) gear.put(EquipmentSlot.HEAD, Walker.dyed(Material.LEATHER_HELMET, "#222222"));
        gear.put(EquipmentSlot.HAND, new ItemStack(r.nextBoolean() ? Material.GOLD_NUGGET : Material.POTION));
        Walker p = Walker.spawn(t.key, at, Walker.Role.PATRON, "<white>" + first[r.nextInt(first.length)], "<dark_gray>Gambler", 0.23, 20, gear);
        p.fights = r.nextDouble() < cfg().getDouble("town.casino.patrons-who-fight", 0.5);
        p.nextWander = System.currentTimeMillis() + 2000;
        return t.register(p);
    }

    void thinkPatrons() {
        long now = System.currentTimeMillis();
        for (Walker p : new ArrayList<>(t.walkers(Walker.Role.PATRON))) {
            if (!p.valid()) continue;
            if (centre == null || t.nearestPlayer(p.loc(), 64) == null) {
                t.unregister(p, false);
                continue;
            }
            if (p.mode == 1) { // fleeing to an exit
                Location exit = exitPoint();
                if (exit == null || p.dist(exit) < 2.5 || now - p.modeSince > 30_000) {
                    t.unregister(p, true);
                    continue;
                }
                if (!p.moving()) p.moveTo(exit, 1.4);
                continue;
            }
            if (p.mode == 2) { // fighting an officer
                Walker cop = null;
                for (Walker o : t.walkers(Walker.Role.OFFICER)) if (o.legs.getUniqueId().equals(p.foe)) cop = o;
                if (cop == null || !cop.valid()) {
                    cop = nearestRaider(p.loc());
                    if (cop == null) {
                        p.mode = 0;
                        continue;
                    }
                    p.foe = cop.legs.getUniqueId();
                }
                double d = p.dist(cop.loc());
                if (d > 2.2) p.moveTo(cop.legs, 1.3);
                if (d < 3 && now - p.lastAttack > 1100) {
                    p.lastAttack = now;
                    p.lookAt(cop.body.getEyeLocation());
                    p.swing();
                    cop.health -= 3;
                    cop.hurt();
                    // The officer turns on whoever is hitting him: one less on the players.
                    cop.mode = Officers.FIGHT;
                    cop.foe = p.legs.getUniqueId();
                    cop.modeSince = now;
                    if (cop.health <= 0) {
                        cop.shout("<red>Guard down!", 24);
                        t.unregister(cop, true);
                    }
                }
                continue;
            }
            if (!p.moving() && now > p.nextWander) {
                Location g = floorSpot();
                if (g != null) p.moveTo(g, 0.55);
                p.nextWander = now + 4000 + r.nextInt(8000);
            }
            if (!p.moving()) {
                Player near = t.nearestPlayer(p.loc(), 5);
                if (near != null) p.lookAt(near.getEyeLocation());
            }
        }
    }

    private Walker nearestRaider(Location l) {
        Walker best = null;
        double bd = 30;
        for (Walker o : t.walkers(Walker.Role.OFFICER)) {
            if (!o.raid) continue;
            double d = o.dist(l);
            if (d < bd) {
                bd = d;
                best = o;
            }
        }
        return best;
    }

    /** The bottom of the entrance shaft or the far end of the emergency tunnel, whichever is closer to most. */
    private Location exitPoint() {
        if (centre == null) return null;
        return r.nextBoolean() ? centre.clone().add(0.5, 1, -12.5) : centre.clone().add(0.5, 1, TUNNEL - 0.5);
    }

    /** Officers hit a patron (they're busy with them). */
    void patronHit(Walker p, double dmg) {
        p.health -= dmg;
        p.hurt();
        if (p.health <= 0) {
            p.shout("<gray>Ugh... I'm out...", 12);
            t.unregister(p, true);
        }
    }

    void patronClicked(Walker p, Player pl) {
        String[] lines = {"Feeling lucky? The slots on the west wall are hot tonight.", "Don't play blackjack with Vito. He counts cards. So do I.",
                "If you hear the siren, run for the emergency tunnel at the back.", "The Planner in the back room has... work, for the right people.",
                "Roulette's honest. Mostly.", "Keep your voice down. This place doesn't exist."};
        p.say(pl, lines[r.nextInt(lines.length)]);
    }

    void patronAttacked(Walker p, Player by, double dmg) {
        patronHit(p, dmg);
        if (p.valid() && p.fights) {
            p.say(by, "Hey! Watch it!");
        }
    }

    public void raid(List<Player> in) {
        long now = System.currentTimeMillis();
        raidUntil = now + cfg().getLong("town.casino.raid-seconds", 180) * 1000L;
        nextRaid = now + minutes(cfg().getInt("town.casino.raid-minutes-min", 8), cfg().getInt("town.casino.raid-minutes-max", 20))
                + cfg().getLong("town.casino.raid-seconds", 180) * 1000L;
        warned = false;
        // Officers pour in at the bottom of the entrance shaft.
        int n = Math.min(cfg().getInt("town.casino.raid-officers-max", 8), 3 + in.size());
        List<Walker> cops = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Location at = centre.clone().add(0.5 + (i % 3) - 1, 1, -HZ + 0.5 + i / 3);
            Walker o = t.officers().spawn(at);
            o.raid = true;
            o.corrupt = false;
            cops.add(o);
        }
        int i = 0;
        for (Player p : in) {
            p.showTitle(Title.title(SmpPlugin.mm("<red><bold>RAID!"), SmpPlugin.mm("<white>The Town Watch is raiding the gambling den!"),
                    Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2000), Duration.ofMillis(400))));
            p.playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.6f, 1.6f);
            p.sendMessage(SmpPlugin.mm("<red><bold>RAID!</bold> <gray>Everyone in here is going down. Run for the shaft or the emergency tunnel at the back!"));
            if (!cops.isEmpty()) t.wanted(p, Crime.CASINO, cops.get(i % cops.size()));
            t.addRep(p, -Crime.CASINO.rep);
            if (!cops.isEmpty()) t.officers().pursue(cops.get(i++ % cops.size()), p);
        }
        // Patrons scatter or fight back.
        for (Walker p : t.walkers(Walker.Role.PATRON)) {
            p.modeSince = now;
            if (p.fights && !cops.isEmpty()) {
                p.mode = 2;
                p.foe = cops.get(r.nextInt(cops.size())).legs.getUniqueId();
                p.shout("<red>Cops! Get 'em!", 20);
            } else {
                p.mode = 1;
                p.stop();
                p.shout("<yellow>It's a raid! Run!", 20);
            }
        }
        t.news("The Town Watch raided the underground gambling den!");
    }

    private void endRaid() {
        raidUntil = 0;
        for (Walker o : new ArrayList<>(t.walkers(Walker.Role.OFFICER))) if (o.raid) t.unregister(o, true);
        for (Player p : playersInside()) p.sendMessage(SmpPlugin.mm("<gray>The raid is over. The croupier dusts off the table..."));
    }

    // ------------------------------------------------------------------ the generated casino

    private File structureFile() {
        return new File(t.plugin().getDataFolder(), "casino-undo.nbt");
    }

    private File metaFile() {
        return new File(t.plugin().getDataFolder(), "casino-undo.yml");
    }

    public boolean built() {
        return structureFile().exists();
    }

    private int ground(int x, int z) {
        return w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private int[] area(int topEntrance, int topExit) {
        return new int[]{cx - HX - 3, cy - 1, cz - HZ - 7, cx + HX + 3, Math.max(topEntrance + 7, topExit + 4), cz + TUNNEL + 3};
    }

    private static BoundingBox boxOf(int[] a) {
        return new BoundingBox(a[0], a[1], a[2], a[3] + 1, a[4] + 1, a[5] + 1);
    }

    public String check(World world, int x, int floorY, int z) {
        this.w = world;
        this.cx = x;
        this.cy = floorY;
        this.cz = z;
        int top = ground(cx, cz - 13), exit = ground(cx, cz + TUNNEL);
        int[] a = area(top, exit);
        int tiles = 0, water = 0, ents = 0;
        for (int bx = a[0] >> 4; bx <= a[3] >> 4; bx++) {
            for (int bz = a[2] >> 4; bz <= a[5] >> 4; bz++) {
                for (var s : w.getChunkAt(bx, bz).getTileEntities()) {
                    Location l = s.getLocation();
                    if (l.getBlockX() >= a[0] && l.getBlockX() <= a[3] && l.getBlockZ() >= a[2] && l.getBlockZ() <= a[5] && l.getBlockY() >= a[1] && l.getBlockY() <= a[4]) tiles++;
                }
            }
        }
        for (int dx = -HX - 1; dx <= HX + 1; dx++) for (int dz = -HZ - 1; dz <= HZ + 1; dz++) for (int dy = 0; dy <= 8; dy++) if (at(dx, dy, dz).isLiquid()) water++;
        for (Entity e : w.getNearbyEntities(boxOf(a))) if (!(e instanceof Player) && !t.isOurs(e)) ents++;
        double dist = Math.sqrt(Math.pow(x - t.cx, 2) + Math.pow(z - t.cz, 2));
        return "Gambling Den site " + x + " " + floorY + " " + z + " (" + (int) dist + " blocks from the fountain" + (dist > t.policeRadius ? ", OUTSIDE the Watch zone: no raids" : "")
                + "): surface " + top + " at the entrance, " + exit + " at the emergency exit, hall " + (top - floorY) + " blocks down; "
                + tiles + " block entities, " + water + " liquid blocks in the hall space, " + ents + " entities in the area.";
    }

    public String build(World world, int x, int floorY, int z) throws IOException {
        if (built()) throw new IOException("A gambling den is already built. Run /mrtown casino undo first.");
        this.w = world;
        this.cx = x;
        this.cy = floorY;
        this.cz = z;
        int top = ground(cx, cz - 13), exit = ground(cx, cz + TUNNEL);
        if (top - floorY < 10) throw new IOException("The hall must be at least 10 blocks under the surface (surface " + top + ").");
        int[] a = area(top, exit);
        Structure st = Bukkit.getStructureManager().createStructure();
        st.fill(new Location(w, a[0], a[1], a[2]), new BlockVector(a[3] - a[0] + 1, a[4] - a[1] + 1, a[5] - a[2] + 1), true);
        Bukkit.getStructureManager().saveStructure(structureFile(), st);
        YamlConfiguration meta = new YamlConfiguration();
        meta.set("world", w.getName());
        meta.set("area", List.of(a[0], a[1], a[2], a[3], a[4], a[5]));
        meta.set("created", System.currentTimeMillis());
        meta.save(metaFile());
        for (Entity e : w.getNearbyEntities(boxOf(a))) if (loose(e)) e.remove();
        slots.clear();
        slotPrice.clear();
        hallShell();
        slotsRow();
        tables();
        barAndRooms();
        entrance(top);
        tunnel(exit);
        centre = new Location(w, cx, cy, cz);
        areaWorld = w.getName();
        hall = new BoundingBox(cx - HX, cy, cz - HZ - 3, cx + HX + 1, cy + 7, cz + HZ + 1);
        area = boxOf(a);
        save();
        return "Gambling Den built: hall at " + cx + " " + cy + " " + cz + " (" + (top - cy) + " blocks under the shack at " + cx + " " + top + " " + (cz - 13)
                + "), emergency exit at " + cx + " " + exit + " " + (cz + TUNNEL) + ". Undo with /mrtown casino undo.";
    }

    private Material wall(int dx, int dy, int dz) {
        int h = Math.abs((dx * 73856093) ^ (dy * 19349663) ^ (dz * 83492791)) % 100;
        return h < 10 ? Material.CRACKED_POLISHED_BLACKSTONE_BRICKS : Material.POLISHED_BLACKSTONE_BRICKS;
    }

    private void hallShell() {
        for (int dx = -HX - 1; dx <= HX + 1; dx++) {
            for (int dz = -HZ - 1; dz <= HZ + 1; dz++) {
                boolean edge = Math.abs(dx) == HX + 1 || Math.abs(dz) == HZ + 1;
                for (int dy = 0; dy <= 7; dy++) {
                    Material m;
                    if (dy == 0) m = edge ? Material.POLISHED_BLACKSTONE_BRICKS : Material.POLISHED_BLACKSTONE;
                    else if (dy == 7) m = edge ? Material.POLISHED_BLACKSTONE_BRICKS : (dx % 4 == 0 && dz % 4 == 0 ? Material.SHROOMLIGHT : Material.DARK_OAK_PLANKS);
                    else if (edge) m = (Math.abs(dx) % 4 == 0 && Math.abs(dz) == HZ + 1 || Math.abs(dz) % 4 == 0 && Math.abs(dx) == HX + 1) ? Material.GILDED_BLACKSTONE : wall(dx, dy, dz);
                    else m = Material.AIR;
                    set(dx, dy, dz, m);
                }
                // seal the outside so caves and water can't leak in
                set(dx, -1, dz, Material.DEEPSLATE_BRICKS);
                set(dx, 8, dz, Material.DEEPSLATE_BRICKS);
            }
        }
        for (int dz = -HZ; dz <= HZ; dz++) set(0, 1, dz, Material.RED_CARPET);
        for (int dx = -HX + 2; dx <= HX - 2; dx++) set(dx, 1, 0, Material.RED_CARPET);
        for (int[] c : new int[][]{{-6, -5}, {6, -5}, {-6, 5}, {6, 5}, {0, -6}, {0, 6}}) hanging(c[0], 6, c[1]);
    }

    private void slotsRow() {
        int i = 0;
        int cheap = cfg().getInt("town.casino.slot-price", 50), high = cfg().getInt("town.casino.slot-price-high", 250);
        for (int dz = -8; dz <= 8; dz += 2) {
            BlockData disp = Material.DISPENSER.createBlockData();
            ((Directional) disp).setFacing(BlockFace.EAST);
            at(-HX, 1, dz).setBlockData(disp, false);
            BlockData lamp = Material.REDSTONE_LAMP.createBlockData();
            ((Lightable) lamp).setLit(true);
            at(-HX, 2, dz).setBlockData(lamp, false);
            set(-HX, 3, dz, Material.GOLD_BLOCK);
            int price = dz < 0 ? cheap : high;
            Location l = at(-HX, 1, dz).getLocation();
            slots.add(l);
            slotPrice.put(l, price);
            hologram(cx - HX + 1.0, cy + 2.3, cz + dz + 0.5, (price == cheap ? "<yellow>" : "<light_purple>") + "SLOTS\n<white>" + Money.fmt(price), 0.6f);
            i++;
        }
        hologram(cx - HX + 1.6, cy + 4.6, cz + 0.5, "<gold><bold>♦ SLOT MACHINES ♦</bold>\n<gray>Right-click a machine", 1f);
    }

    private void tables() {
        // Blackjack: two tables on the east side with dealers.
        for (int tz : new int[]{-5, 4}) {
            for (int dx = 8; dx <= 10; dx++) {
                for (int dz = tz; dz <= tz + 1; dz++) {
                    set(dx, 1, dz, Material.DARK_OAK_PLANKS);
                    set(dx, 2, dz, Material.GREEN_CARPET);
                }
            }
            String id = tz < 0 ? "dealer_vito" : "dealer_lena";
            npc(id, tz < 0 ? "<gold>Vito" : "<gold>Lena", "<gray>Blackjack dealer", cx + 11.5, cz + tz + 1.0, 90, "#1A1A1A",
                    Map.of(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE, EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS, EquipmentSlot.HAND, Material.PAPER),
                    "command:casino blackjack");
            hologram(cx + 9.5, cy + 3.4, cz + tz + 1.0, "<green><bold>BLACKJACK</bold>\n<gray>Talk to the dealer", 0.8f);
        }
        // Roulette in the middle.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(dx, 1, dz, Material.DARK_OAK_PLANKS);
                set(dx, 2, dz, dx == 0 && dz == 0 ? Material.AIR : (dx + dz) % 2 == 0 ? Material.RED_CARPET : Material.BLACK_CARPET);
            }
        }
        set(0, 2, 0, Material.LODESTONE);
        npc("croupier", "<gold>Marcel", "<gray>Roulette croupier", cx + 0.5, cz - 2.0, 0, "#1A1A1A",
                Map.of(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE, EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS, EquipmentSlot.HEAD, Material.LEATHER_HELMET),
                "command:casino roulette");
        rouletteBoard = w.spawn(new Location(w, cx + 0.5, cy + 4.0, cz + 0.5), TextDisplay.class, d -> {
            d.text(SmpPlugin.mm("<red><bold>ROULETTE</bold>\n<gray>Talk to Marcel to bet"));
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(140, 0, 0, 0));
            d.setPersistent(true);
            d.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        }).getUniqueId();
        // Dice gamblers.
        npc("dice_lou", "<yellow>Lucky Lou", "<gray>Dice, any stake", cx - 5.5, cz + 6.5, 180, "#5A3A1A",
                Map.of(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE, EquipmentSlot.HAND, Material.BONE), "command:casino dice");
        npc("dice_mae", "<yellow>One-Eyed Mae", "<gray>Dice, any stake", cx + 4.5, cz + 7.5, 180, "#3A1A3A",
                Map.of(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE, EquipmentSlot.HAND, Material.BONE), "command:casino dice");
        for (int[] c : new int[][]{{-5, 8}, {4, 9}}) slab(c[0], 1, c[1], Material.DARK_OAK_SLAB, Slab.Type.TOP);
    }

    private void barAndRooms() {
        // Bar in the north-east corner.
        for (int dx = 6; dx <= HX - 1; dx++) set(dx, 1, -7, Material.DARK_OAK_PLANKS);
        for (int dx = 6; dx <= HX; dx++) {
            set(dx, 1, -HZ, Material.BARREL);
            set(dx, 2, -HZ, Material.BOOKSHELF);
        }
        set(8, 2, -7, Material.BREWING_STAND);
        npc("bartender", "<white>Sal", "<gray>Bartender", cx + 9.5, cz - 8.5, 0, "#5A1F1F",
                Map.of(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE, EquipmentSlot.HAND, Material.GLASS_BOTTLE), null);
        // The Planner's back room (south-west corner).
        for (int dz = 6; dz <= HZ; dz++) for (int dy = 1; dy <= 6; dy++) set(-8, dy, dz, dz == 8 && dy <= 2 ? Material.AIR : Material.POLISHED_BLACKSTONE_BRICKS);
        for (int dx = -HX; dx <= -8; dx++) for (int dy = 1; dy <= 6; dy++) set(dx, dy, 6, Material.POLISHED_BLACKSTONE_BRICKS);
        set(-12, 1, 9, Material.CARTOGRAPHY_TABLE);
        set(-10, 1, 10, Material.CHISELED_BOOKSHELF);
        hanging(-10, 6, 8);
        npc("planner", "<dark_red>The Planner", "<gray>Heists · Tools", cx - 10.5, cz + 8.5, -90, "#1A1A1A",
                Map.of(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE, EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS, EquipmentSlot.HAND, Material.FILLED_MAP),
                "command:heist");
        hologram(cx - 7.5, cy + 3.6, cz + 8.5, "<dark_red><bold>BACK ROOM</bold>\n<gray>Heists", 0.7f);
        npc("bouncer", "<dark_gray>Bruno", "<gray>Bouncer", cx + 1.5, cz - HZ + 0.5, 0, "#111111",
                Map.of(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE, EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS), null);
        hologram(cx + 0.5, cy + 5.2, cz - HZ + 1.0, "<gold><bold>♣ THE GOLDEN RAT ♣</bold>\n<gray>Underground gambling den · No guards", 1f);
    }

    /** Corridor north out of the hall, ladder shaft up to a ruined shack. */
    private void entrance(int top) {
        for (int dz = -HZ - 1; dz >= -HZ - 4; dz--) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 3; dy++) {
                    boolean air = dx == 0 && dy >= 1 && dy <= 2 && dz >= -HZ - 3;
                    set(dx, dy, dz, air ? Material.AIR : Material.DEEPSLATE_BRICKS);
                }
            }
        }
        int sx = 0, sz = -13;
        for (int y = cy + 1; y <= top + 4; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = sz - 1; dz <= sz + 1; dz++) {
                    if (dx == 0 && dz == sz) continue;
                    if (y < top - 1) w.getBlockAt(cx + dx, y, cz + dz).setType(Material.DEEPSLATE_BRICKS, false);
                }
            }
            if (y >= cy + 1 && y < top) {
                Block b = w.getBlockAt(cx + sx, y, cz + sz);
                if (y <= cy + 2) b.setType(Material.AIR, false);
                BlockData ladder = Material.LADDER.createBlockData();
                ((Directional) ladder).setFacing(BlockFace.SOUTH);
                b.setBlockData(ladder, false);
            }
        }
        // The shack: 5x5, mossy and broken, on a flattened patch.
        int fy = top;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = sz - 3; dz <= sz + 3; dz++) {
                for (int y = fy - 3; y < fy; y++) {
                    Block b = w.getBlockAt(cx + dx, y, cz + dz);
                    if (b.isPassable() || b.isLiquid()) {
                        if (!(dx == 0 && dz == sz)) b.setType(Material.DIRT, false);
                    }
                }
                for (int y = fy + 1; y <= fy + 6; y++) w.getBlockAt(cx + dx, y, cz + dz).setType(Material.AIR, false);
                if (!(dx == 0 && dz == sz)) w.getBlockAt(cx + dx, fy, cz + dz).setType(Math.abs(dx) <= 2 && Math.abs(dz - sz) <= 2 ? Material.SPRUCE_PLANKS : Material.GRASS_BLOCK, false);
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = sz - 2; dz <= sz + 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz - sz) == 2;
                if (!edge) continue;
                boolean door = dx == 0 && dz == sz - 2;
                for (int y = fy + 1; y <= fy + 3; y++) {
                    if (door && y <= fy + 2) continue;
                    boolean corner = Math.abs(dx) == 2 && Math.abs(dz - sz) == 2;
                    int h = Math.abs((dx * 31) ^ (y * 17) ^ (dz * 13)) % 7;
                    Material m = corner ? Material.STRIPPED_SPRUCE_LOG : h == 0 ? Material.AIR : h < 3 ? Material.MOSSY_COBBLESTONE : Material.SPRUCE_PLANKS;
                    if (y == fy + 2 && !corner && (dx == 0 || dz == sz)) m = Material.AIR; // broken windows
                    w.getBlockAt(cx + dx, y, cz + dz).setType(m, false);
                }
            }
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = sz - 2; dz <= sz + 2; dz++) {
                if ((dx * 7 + dz * 3) % 5 == 0) continue; // holes in the roof
                Slab s = (Slab) Material.SPRUCE_SLAB.createBlockData();
                s.setType(Slab.Type.BOTTOM);
                w.getBlockAt(cx + dx, fy + 4, cz + dz).setBlockData(s, false);
            }
        }
        w.getBlockAt(cx + 1, fy + 1, cz + sz + 1).setType(Material.BARREL, false);
        w.getBlockAt(cx - 1, fy + 1, cz + sz + 1).setType(Material.COBWEB, false);
        w.getBlockAt(cx + 1, fy + 3, cz + sz - 1).setType(Material.COBWEB, false);
        trapdoor(cx + sx, fy, cz + sz, BlockFace.SOUTH);
    }

    /** Emergency tunnel south from the hall, up a ladder to a trapdoor hidden in some bushes. */
    private void tunnel(int exitTop) {
        for (int dz = HZ + 1; dz <= TUNNEL; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 4; dy++) {
                    boolean air = dx == 0 && dy >= 1 && dy <= 3;
                    set(dx, dy, dz, air ? Material.AIR : dy == 0 ? Material.COBBLESTONE : Material.MOSSY_STONE_BRICKS);
                }
            }
            if ((dz - HZ) % 7 == 0) hanging(0, 3, dz);
        }
        hologram(cx + 0.5, cy + 3.3, cz + HZ + 0.6, "<red><bold>EMERGENCY EXIT</bold>\n<gray>for when the guards come", 0.7f);
        int ez = TUNNEL;
        for (int y = cy + 1; y < exitTop; y++) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = ez - 1; dz <= ez + 1; dz++) {
                if (dx == 0 && dz == ez) continue;
                if (y > cy + 3 || dz == ez + 1) w.getBlockAt(cx + dx, y, cz + dz).setType(Material.MOSSY_STONE_BRICKS, false);
            }
            BlockData ladder = Material.LADDER.createBlockData();
            ((Directional) ladder).setFacing(BlockFace.NORTH);
            w.getBlockAt(cx, y, cz + ez).setBlockData(ladder, false);
        }
        for (int y = exitTop + 1; y <= exitTop + 3; y++) w.getBlockAt(cx, y, cz + ez).setType(Material.AIR, false);
        trapdoor(cx, exitTop, cz + ez, BlockFace.NORTH);
        for (int[] b : new int[][]{{-1, 0}, {1, 0}, {0, 1}, {1, 1}, {-1, 1}}) {
            Leaves leaves = (Leaves) Material.SPRUCE_LEAVES.createBlockData();
            leaves.setPersistent(true);
            w.getBlockAt(cx + b[0], exitTop + 1, cz + ez + b[1]).setBlockData(leaves, false);
        }
    }

    private void trapdoor(int x, int y, int z, BlockFace facing) {
        TrapDoor td = (TrapDoor) Material.SPRUCE_TRAPDOOR.createBlockData();
        td.setFacing(facing);
        td.setHalf(Bisected.Half.TOP);
        td.setOpen(false);
        w.getBlockAt(x, y, z).setBlockData(td, false);
    }

    private static boolean loose(Entity e) {
        if (e instanceof Player) return false;
        return !(e instanceof LivingEntity) || e instanceof ArmorStand;
    }

    public String undo() throws IOException {
        if (!built()) throw new IOException("No generated gambling den to undo.");
        YamlConfiguration meta = YamlConfiguration.loadConfiguration(metaFile());
        World uw = Bukkit.getWorld(meta.getString("world", ""));
        if (uw == null) throw new IOException("World not loaded.");
        if (raiding()) endRaid();
        for (Walker p : new ArrayList<>(t.walkers(Walker.Role.PATRON))) t.unregister(p, false);
        List<Integer> a = meta.getIntegerList("area");
        int[] area = {a.get(0), a.get(1), a.get(2), a.get(3), a.get(4), a.get(5)};
        Structure st = Bukkit.getStructureManager().loadStructure(structureFile());
        for (Player p : uw.getPlayers()) {
            if (boxOf(area).contains(p.getLocation().toVector()) && p.getLocation().getY() < area[4] - 8) {
                Location up = p.getLocation();
                up.setY(uw.getHighestBlockYAt(up) + 1);
                t.teleport(p, up);
            }
        }
        for (Entity e : uw.getNearbyEntities(boxOf(area))) if (e.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) e.remove();
        t.plugin().npcs().removeGroup("casino");
        for (int y = area[4]; y >= area[1]; y--) for (int x = area[0]; x <= area[3]; x++) for (int z = area[2]; z <= area[5]; z++) uw.getBlockAt(x, y, z).setType(Material.AIR, false);
        EntityTransformer onlyLoose = (region, x, y, z, entity, allowed) -> loose(entity);
        st.place(new Location(uw, area[0], area[1], area[2]), true, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random(), List.of(), List.of(onlyLoose));
        File done = new File(t.plugin().getDataFolder(), "casino-undone-" + System.currentTimeMillis() + ".nbt");
        structureFile().renameTo(done);
        metaFile().delete();
        centre = null;
        hall = null;
        area = null;
        slots.clear();
        slotPrice.clear();
        rouletteBoard = null;
        file.delete();
        return "Gambling Den removed and the area restored exactly as before. Snapshot kept as " + done.getName() + ".";
    }

    // ------------------------------------------------------------------ helpers

    private Block at(int dx, int dy, int dz) {
        return w.getBlockAt(cx + dx, cy + dy, cz + dz);
    }

    private void set(int dx, int dy, int dz, Material m) {
        at(dx, dy, dz).setType(m, false);
    }

    private void slab(int dx, int dy, int dz, Material m, Slab.Type type) {
        Slab s = (Slab) m.createBlockData();
        s.setType(type);
        at(dx, dy, dz).setBlockData(s, false);
    }

    private void hanging(int dx, int dy, int dz) {
        Lantern l = (Lantern) Material.LANTERN.createBlockData();
        l.setHanging(true);
        at(dx, dy, dz).setBlockData(l, false);
    }

    private void npc(String id, String name, String title, double x, double z, float yaw, String leather, Map<EquipmentSlot, Material> gear, String action) {
        NpcDef d = new NpcDef();
        d.id = id;
        d.name = name;
        d.title = title;
        d.world = w.getName();
        d.x = x;
        d.y = cy + 1;
        d.z = z;
        d.yaw = yaw;
        d.group = "casino";
        d.action = action;
        d.equipment.putAll(gear);
        if (leather != null) d.leatherColor = Color.fromRGB(Integer.parseInt(leather.substring(1), 16));
        t.plugin().npcs().put(d);
    }

    private void hologram(double x, double y, double z, String text, float scale) {
        w.spawn(new Location(w, x, y, z), TextDisplay.class, d -> {
            d.text(SmpPlugin.mm(text));
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            d.setPersistent(true);
            if (scale != 1f) {
                var tr = d.getTransformation();
                d.setTransformation(new org.bukkit.util.Transformation(tr.getTranslation(), tr.getLeftRotation(), new org.joml.Vector3f(scale), tr.getRightRotation()));
            }
            d.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
    }

    // ------------------------------------------------------------------ protection

    private boolean protectedHere(Player p, Location l) {
        return protects(l) && !p.hasPermission("mrsmp.admin");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (protectedHere(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (protectedHere(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (protectedHere(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }
}
