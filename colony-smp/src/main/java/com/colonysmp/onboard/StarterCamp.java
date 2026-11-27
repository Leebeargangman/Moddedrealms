package com.colonysmp.onboard;

import com.colonysmp.ColonySMP;
import com.colonysmp.building.BuildingManager;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Messages;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Orientable;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.block.data.type.Door;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * "Found a Colony" in one click: a free site from the {@link SitePool}, levelled, with a Town Hall core and its
 * storage chest, a tent with three beds (a registered house), a woodcutter's hut (a registered workplace) and a
 * campfire. Three settlers walk in from the woods and greet the founder by name, and the founder gets the Colony
 * Charter and a Starter Farm. Everything about it is in config.yml under onboarding.camp.
 */
public final class StarterCamp {

    private final ColonySMP plugin;
    /** Players whose camp is being set up, and since when (a stuck search frees them after a minute). */
    private final java.util.Map<UUID, Long> busy = new java.util.HashMap<>();

    public StarterCamp(ColonySMP plugin) {
        this.plugin = plugin;
    }

    /** Why this player can't have a starter camp now, or null. */
    public String problem(Player p) {
        if (!plugin.settings().feature("starter-camp")) return Messages.get("camp.off");
        Long since = busy.get(p.getUniqueId());
        if (since != null && System.currentTimeMillis() - since < 60_000) return Messages.get("camp.busy");
        if (plugin.colonies().of(p) != null) return Messages.get("camp.has-colony");
        // one ready camp per player: its wool, beds and planks would otherwise be free for the taking, again and again
        if (plugin.funnel().seen(p.getUniqueId(), "camp:used") && !plugin.isBypassing(p)) return Messages.get("camp.used");
        if (plugin.colonies().coloniesOf(p.getUniqueId()).size() >= plugin.settings().maxColoniesPerPlayer && !plugin.isBypassing(p)) {
            return Messages.get("camp.has-colony");
        }
        return null;
    }

    /**
     * Founds a colony for this player at a ready site. Returns at once with why it can't (or null when it has
     * started); {@code done} is called on the main thread later with null (founded) or an error.
     */
    public String start(Player p, Consumer<String> done) {
        String why = problem(p);
        if (why != null) return why;
        UUID id = p.getUniqueId();
        busy.put(id, System.currentTimeMillis());
        if (plugin.sites().size() == 0) Messages.send(p, "camp.searching");
        plugin.sites().take(site -> {
            busy.remove(id);
            Player now = Bukkit.getPlayer(id);
            String err;
            if (now == null) err = "offline";
            else if (site == null) err = Messages.get("camp.no-site");
            else {
                String again = problem(now);
                if (again != null) err = again;
                else {
                    try {
                        err = found(now, site);
                    } catch (RuntimeException ex) {
                        plugin.getLogger().log(Level.SEVERE, "Placing a starter camp for " + now.getName() + " at " + SitePool.describe(site) + " failed", ex);
                        err = Messages.get("camp.failed");
                    }
                }
            }
            if (done != null) done.accept(err);
        });
        return null;
    }

    // ───────────── founding ─────────────

    private String found(Player p, SitePool.Site site) {
        World w = Bukkit.getWorld(site.world());
        if (w == null) return Messages.get("camp.no-site");
        String name = name(p);
        Colony c = plugin.colonies().create(p.getUniqueId(), name, w.getName());
        int x = site.x(), g = site.y(), z = site.z();
        level(w, x, g, z);
        Block core = w.getBlockAt(x, g + 1, z);
        core.setType(Material.LODESTONE, false);
        // the settlers come with the camp, not with the core (their jobs and arrival are the camp's)
        c.founded = true;
        plugin.townHall().raise(null, c, core);
        if (c.core == null) {
            core.setType(Material.AIR, false);
            plugin.colonies().delete(c, null);
            return Messages.get("camp.failed");
        }
        Location base = new Location(w, x, g + 1, z);
        campfire(base);
        paths(base);
        lights(base);
        tent(base, BlockFace.EAST, -5, 0);
        hut(base, BlockFace.WEST, 6, 0);
        register(c, base, BuildingType.HOUSE, -7, 0, BlockFace.EAST);
        register(c, base, BuildingType.FORESTRY, 8, 0, BlockFace.WEST);

        List<Job> jobs = new ArrayList<>();
        for (String s : plugin.getConfig().getStringList("onboarding.camp.settler-jobs")) {
            Job j = Job.parse(s);
            if (j != null) jobs.add(j);
        }
        if (jobs.isEmpty()) jobs = List.of(Job.FARMER, Job.BUILDER, Job.UNEMPLOYED);
        plugin.sim().population().settlers(c, jobs, false);
        arrive(c, p, base);
        kit(p, c);

        Location stand = base.clone().add(0.5, 0, 7.5);
        stand.setYaw(180);
        stand.setPitch(8);
        // a death out here shouldn't send them back to spawn, a thousand blocks from their camp
        p.setRespawnLocation(stand, true);
        for (org.bukkit.entity.Entity e : w.getNearbyEntities(base, 40, 24, 40)) if (e instanceof org.bukkit.entity.Monster) e.remove();
        p.teleportAsync(stand).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> celebrate(p, c, base)));
        plugin.funnel().record(p.getUniqueId(), "colony_founded");
        plugin.funnel().firstTime(p.getUniqueId(), "camp:used");
        plugin.quests().refresh(c);
        plugin.requestSave();
        plugin.getLogger().info(p.getName() + " founded " + c.name + " with a starter camp at " + SitePool.describe(site) + ".");
        return null;
    }

    private String name(Player p) {
        java.util.Random r = ThreadLocalRandom.current();
        for (int i = 0; i < 30; i++) {
            String n = com.colonysmp.civ.Names.place(r);
            if (n.length() <= 20 && plugin.colonies().checkName(n, null) == null) return n;
        }
        String base = p.getName() + "'s Camp";
        if (base.length() > 24) base = base.substring(0, 24);
        String n = base;
        for (int i = 2; plugin.colonies().checkName(n, null) != null && i < 100; i++) n = (base.length() > 21 ? base.substring(0, 21) : base) + " " + i;
        return n;
    }

    private void celebrate(Player p, Colony c, Location base) {
        if (!p.isOnline()) return;
        Location at = base.clone().add(0.5, 1.2, 0.5);
        Fx.particle(at, Particle.TOTEM_OF_UNDYING, 80, 1.2);
        Fx.particle(base.clone().add(0.5, 0.6, 4.5), Particle.FLAME, 20, 0.4);
        Fx.sound(p, "minecraft:ui.toast.challenge_complete", 1f, 1f);
        Fx.sound(at, "minecraft:block.beacon.activate", 1.2f, 0.9f);
        Text.title(p, Messages.get("camp.title", "colony", c.name), Messages.get("camp.subtitle", "colony", c.name), 10, 80, 20);
        for (String l : Messages.lines("camp.founded", "colony", c.name, "player", p.getName())) Text.raw(p, l);
    }

    /** The settlers' bodies appear at the edge of the woods and walk over to greet the founder. */
    private void arrive(Colony c, Player p, Location base) {
        List<String> lines = Messages.lines("camp.greetings", "player", p.getName(), "colony", c.name);
        String jobless = Messages.get("camp.greeting-jobless", "player", p.getName());
        int i = 0;
        for (Citizen ct : c.citizens.values()) {
            Location from = base.clone().add(-4 + i * 4, 0, -13 - (i % 2) * 2);
            ct.hasLast = true;
            ct.lastX = from.getX() + 0.5;
            ct.lastY = from.getY();
            ct.lastZ = from.getZ() + 0.5;
            String line = ct.job == Job.UNEMPLOYED ? jobless : lines.isEmpty() ? "" : lines.get(i % lines.size());
            plugin.onboarding().greet(ct, p, line, i * 30L);
            i++;
        }
    }

    private void kit(Player p, Colony c) {
        List<ItemStack> give = new ArrayList<>();
        give.add(plugin.items().charter(c.id, c.name));
        give.add(plugin.items().starterFarm());
        give.add(plugin.items().book());
        give.add(plugin.items().marker());
        for (String s : plugin.getConfig().getStringList("onboarding.camp.kit")) {
            String[] f = s.split(":");
            Material m = Material.matchMaterial(f[0].trim());
            if (m == null || !m.isItem()) continue;
            int n = 1;
            try {
                if (f.length > 1) n = Math.max(1, Integer.parseInt(f[1].trim()));
            } catch (NumberFormatException ignored) {
                // one, then
            }
            give.add(new ItemStack(m, Math.min(n, m.getMaxStackSize())));
        }
        for (ItemStack it : give) com.colonysmp.command.ColonyCommand.give(p, it);
    }

    private void register(Colony c, Location base, BuildingType type, int dx, int dz, BlockFace facing) {
        Block inside = base.clone().add(dx, 0, dz).getBlock();
        BuildingManager.Result r = plugin.buildings().register(null, c, type, inside, facing);
        if (r.error() != null) plugin.getLogger().warning("Starter camp " + c.name + ": couldn't register its " + type.display + ": " + r.error());
    }

    // ───────────── building ─────────────

    /** Clears and levels the camp's footprint to the site's ground height. Only natural blocks are ever there. */
    private void level(World w, int x, int g, int z) {
        for (int dx = SitePool.MIN_X; dx <= SitePool.MAX_X; dx++) {
            for (int dz = SitePool.MIN_Z; dz <= SitePool.MAX_Z; dz++) {
                int bx = x + dx, bz = z + dz;
                for (int y = g + 1; y <= g + 14; y++) {
                    Block b = w.getBlockAt(bx, y, bz);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
                int col = SitePool.ground(w, bx, bz);
                if (col == Integer.MIN_VALUE) col = g - 1;
                for (int y = col + 1; y < g; y++) w.getBlockAt(bx, y, bz).setType(Material.DIRT, false);
                Block top = w.getBlockAt(bx, g, bz);
                Material t = top.getType();
                if (col < g || t == Material.DIRT || t == Material.COARSE_DIRT || t == Material.ROOTED_DIRT || t == Material.STONE || t == Material.GRAVEL
                        || t == Material.ANDESITE || t == Material.DIORITE || t == Material.GRANITE || t == Material.TUFF || t.isAir()) {
                    top.setType(Material.GRASS_BLOCK, false);
                }
            }
        }
    }

    private static void set(Location base, int dx, int dy, int dz, Material m) {
        base.clone().add(dx, dy, dz).getBlock().setType(m, false);
    }

    private static void campfire(Location base) {
        Block fire = base.clone().add(0, 0, 4).getBlock();
        fire.setType(Material.CAMPFIRE, false);
        if (fire.getBlockData() instanceof Campfire cf) {
            cf.setLit(true);
            cf.setSignalFire(false);
            fire.setBlockData(cf, false);
        }
        for (int dx : new int[]{-2, 2}) {
            Block bench = base.clone().add(dx, 0, 4).getBlock();
            bench.setType(Material.STRIPPED_SPRUCE_LOG, false);
            if (bench.getBlockData() instanceof Orientable o) {
                o.setAxis(org.bukkit.Axis.Z);
                bench.setBlockData(o, false);
            }
        }
    }

    /** Lanterns on posts round the camp, so nothing spawns in it at night. */
    private static void lights(Location base) {
        int[][] posts = {{-2, 8}, {2, 8}, {0, -4}, {-11, -4}, {11, -4}, {-11, 6}, {11, 6}, {-6, 13}, {6, 13}};
        for (int[] at : posts) {
            Block post = base.clone().add(at[0], 0, at[1]).getBlock();
            if (!post.getType().isAir()) continue;
            post.setType(Material.SPRUCE_FENCE, true);
            post.getRelative(0, 1, 0).setType(Material.LANTERN, false);
        }
    }

    private static void paths(Location base) {
        for (int dz = 1; dz <= 8; dz++) if (dz != 4) set(base, 0, -1, dz, Material.DIRT_PATH);
        for (int dx = -4; dx <= -1; dx++) set(base, dx, -1, 0, Material.DIRT_PATH);
        for (int dx = 2; dx <= 5; dx++) set(base, dx, -1, 0, Material.DIRT_PATH);
        set(base, -1, -1, 4, Material.DIRT_PATH);
        set(base, 1, -1, 4, Material.DIRT_PATH);
    }

    /** Places blocks of a small build in local coordinates: front (door side) toward -z, +x to the right. */
    private static final class Stamp {
        final Location origin;
        final BlockFace front;
        final StructureRotation rot;

        Stamp(Location origin, BlockFace front) {
            this.origin = origin;
            this.front = front;
            this.rot = switch (front) {
                case EAST -> StructureRotation.CLOCKWISE_90;
                case SOUTH -> StructureRotation.CLOCKWISE_180;
                case WEST -> StructureRotation.COUNTERCLOCKWISE_90;
                default -> StructureRotation.NONE;
            };
        }

        Block at(int lx, int ly, int lz) {
            int x, z;
            switch (front) {
                case EAST -> {
                    x = -lz;
                    z = lx;
                }
                case SOUTH -> {
                    x = -lx;
                    z = -lz;
                }
                case WEST -> {
                    x = lz;
                    z = -lx;
                }
                default -> {
                    x = lx;
                    z = lz;
                }
            }
            return origin.clone().add(x, ly, z).getBlock();
        }

        void set(int lx, int ly, int lz, Material m) {
            at(lx, ly, lz).setType(m, false);
        }

        void set(int lx, int ly, int lz, BlockData d) {
            BlockData r = d.clone();
            r.rotate(rot);
            at(lx, ly, lz).setBlockData(r, false);
        }

        void door(int lx, int lz, Material m) {
            for (Bisected.Half h : Bisected.Half.values()) {
                Door d = (Door) m.createBlockData();
                d.setFacing(BlockFace.SOUTH);
                d.setHalf(h);
                d.setHinge(Door.Hinge.LEFT);
                d.setOpen(false);
                set(lx, h == Bisected.Half.BOTTOM ? 0 : 1, lz, d);
            }
        }

        void bed(int lx, int footZ, Material m) {
            Bed foot = (Bed) m.createBlockData();
            foot.setFacing(BlockFace.SOUTH);
            foot.setPart(Bed.Part.FOOT);
            Bed head = (Bed) m.createBlockData();
            head.setFacing(BlockFace.SOUTH);
            head.setPart(Bed.Part.HEAD);
            set(lx, 0, footZ, foot);
            set(lx, 0, footZ + 1, head);
        }
    }

    /** A white canvas tent with a red ridge, three beds and a barrel: 7 wide, 6 deep, door in the middle of the front. */
    private static void tent(Location base, BlockFace front, int dx, int dz) {
        Stamp s = new Stamp(base.clone().add(dx, 0, dz), front);
        // local origin is the door's left corner: shift so lx 0..6 spans the front with the door at lx 3
        Stamp t = new Stamp(s.at(-3, 0, 0).getLocation(), front);
        for (int lx = 0; lx <= 6; lx++) for (int lz = 0; lz <= 5; lz++) t.set(lx, -1, lz, Material.SPRUCE_PLANKS);
        for (int lz = 0; lz <= 5; lz++) {
            for (int ly = 0; ly <= 1; ly++) {
                t.set(0, ly, lz, Material.WHITE_WOOL);
                t.set(6, ly, lz, Material.WHITE_WOOL);
            }
            t.set(1, 2, lz, Material.WHITE_WOOL);
            t.set(5, 2, lz, Material.WHITE_WOOL);
            t.set(2, 3, lz, Material.WHITE_WOOL);
            t.set(3, 3, lz, Material.RED_WOOL);
            t.set(4, 3, lz, Material.WHITE_WOOL);
        }
        for (int lz : new int[]{0, 5}) {
            for (int lx = 1; lx <= 5; lx++) for (int ly = 0; ly <= 1; ly++) t.set(lx, ly, lz, Material.WHITE_WOOL);
            for (int lx = 2; lx <= 4; lx++) t.set(lx, 2, lz, Material.WHITE_WOOL);
        }
        for (int lx = 1; lx <= 5; lx++) for (int lz = 1; lz <= 4; lz++) for (int ly = 0; ly <= 2; ly++) if (ly < 2 || lx >= 2 && lx <= 4) t.set(lx, ly, lz, Material.AIR);
        t.door(3, 0, Material.SPRUCE_DOOR);
        t.bed(1, 3, Material.RED_BED);
        t.bed(5, 3, Material.RED_BED);
        t.bed(1, 1, Material.WHITE_BED);
        t.set(5, 0, 1, Material.BARREL);
        Lantern l = (Lantern) Material.LANTERN.createBlockData();
        l.setHanging(true);
        t.set(3, 2, 2, l);
    }

    /** A spruce woodcutter's hut: 5 by 5, log corners, a barrel and a crafting table inside, door in the front. */
    private static void hut(Location base, BlockFace front, int dx, int dz) {
        Stamp s = new Stamp(base.clone().add(dx, 0, dz), front);
        Stamp h = new Stamp(s.at(-2, 0, 0).getLocation(), front);
        for (int lx = 0; lx <= 4; lx++) for (int lz = 0; lz <= 4; lz++) h.set(lx, -1, lz, Material.SPRUCE_PLANKS);
        for (int lx = 0; lx <= 4; lx++) {
            for (int lz = 0; lz <= 4; lz++) {
                boolean edgeX = lx == 0 || lx == 4, edgeZ = lz == 0 || lz == 4;
                for (int ly = 0; ly <= 2; ly++) {
                    if (edgeX && edgeZ) h.set(lx, ly, lz, Material.SPRUCE_LOG);
                    else if (edgeX || edgeZ) h.set(lx, ly, lz, Material.SPRUCE_PLANKS);
                    else h.set(lx, ly, lz, Material.AIR);
                }
                h.set(lx, 3, lz, Material.DARK_OAK_PLANKS);
            }
        }
        Slab top = (Slab) Material.DARK_OAK_SLAB.createBlockData();
        top.setType(Slab.Type.BOTTOM);
        for (int lx = 1; lx <= 3; lx++) for (int lz = 1; lz <= 3; lz++) h.set(lx, 4, lz, top);
        h.set(0, 1, 2, Material.GLASS_PANE);
        h.set(4, 1, 2, Material.GLASS_PANE);
        h.door(2, 0, Material.SPRUCE_DOOR);
        h.set(1, 0, 3, Material.BARREL);
        h.set(3, 0, 3, Material.CRAFTING_TABLE);
        Lantern l = (Lantern) Material.LANTERN.createBlockData();
        l.setHanging(true);
        h.set(2, 2, 2, l);
        // a woodpile by the door
        Block pile = h.at(-1, 0, 1);
        if (pile.getType().isAir()) {
            pile.setType(Material.SPRUCE_LOG, false);
            if (pile.getBlockData() instanceof Orientable o) {
                o.setAxis(org.bukkit.Axis.Y);
                pile.setBlockData(o, false);
            }
        }
    }

    // ───────────── the Starter Farm item ─────────────

    /** Why a 5x5 farm can't be stamped with its centre on this ground block, or null. */
    public String farmProblem(Colony c, Block ground) {
        World w = ground.getWorld();
        int g = ground.getY();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Block b = w.getBlockAt(ground.getX() + dx, g, ground.getZ() + dz);
                if (!c.territory.contains(b.getX(), b.getZ())) return Messages.get("farm.outside");
                if (plugin.buildings().at(c, com.colonysmp.util.BlockPos.of(b)) != null || plugin.buildings().at(c, com.colonysmp.util.BlockPos.of(b.getRelative(0, 1, 0))) != null) {
                    return Messages.get("farm.on-building");
                }
                Material m = b.getType();
                if (!(m == Material.GRASS_BLOCK || m == Material.DIRT || m == Material.COARSE_DIRT || m == Material.PODZOL || m == Material.ROOTED_DIRT
                        || m == Material.FARMLAND || m == Material.MYCELIUM || m == Material.DIRT_PATH || m == Material.SAND || m == Material.SNOW_BLOCK)) {
                    return Messages.get("farm.ground");
                }
                for (int dy = 1; dy <= 3; dy++) {
                    Block a = b.getRelative(0, dy, 0);
                    Material am = a.getType();
                    if (am.isAir() || a.isReplaceable() || Tag.LEAVES.isTagged(am) || Tag.FLOWERS.isTagged(am) || Tag.SAPLINGS.isTagged(am)) continue;
                    if (Tag.CROPS.isTagged(am)) continue;
                    return Messages.get("farm.blocked");
                }
            }
        }
        return null;
    }

    /** Where a farm fits nearest the clicked ground (within a few blocks, on the same level or one up or down), or null. */
    private Block fit(Colony c, Block ground) {
        int r = plugin.getConfig().getInt("onboarding.camp.farm-search-radius", 5);
        Block best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = dx * dx + dz * dz;
                if (d >= bd || d > r * r) continue;
                for (int dy = -1; dy <= 1; dy++) {
                    Block b = ground.getRelative(dx, dy, dz);
                    if (!b.getRelative(0, 1, 0).isPassable() || farmProblem(c, b) != null) continue;
                    best = b;
                    bd = d;
                    break;
                }
            }
        }
        return best;
    }

    /** Stamps a 5x5 field (water in the middle, wheat already sprouting) and registers it. Null when it worked. */
    public String stampFarm(Player p, Colony c, Block clicked) {
        String why = farmProblem(c, clicked);
        Block ground = why == null ? clicked : fit(c, clicked);
        if (ground == null) return why;
        World w = ground.getWorld();
        int g = ground.getY();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                Block b = w.getBlockAt(ground.getX() + dx, g, ground.getZ() + dz);
                for (int dy = 1; dy <= 3; dy++) b.getRelative(0, dy, 0).setType(Material.AIR, false);
                if (dx == 0 && dz == 0) {
                    b.setType(Material.WATER, false);
                    continue;
                }
                b.setType(Material.FARMLAND, false);
                if (b.getBlockData() instanceof org.bukkit.block.data.type.Farmland f) {
                    f.setMoisture(f.getMaximumMoisture());
                    b.setBlockData(f, false);
                }
                Block crop = b.getRelative(0, 1, 0);
                crop.setType(Material.WHEAT, false);
                if (crop.getBlockData() instanceof org.bukkit.block.data.Ageable a) {
                    a.setAge(Math.min(a.getMaximumAge(), 2 + r.nextInt(3)));
                    crop.setBlockData(a, false);
                }
            }
        }
        // a scarecrow at one corner, outside the field
        Block post = w.getBlockAt(ground.getX() - 3, g + 1, ground.getZ() - 3);
        if (post.getType().isAir() && !post.getRelative(0, -1, 0).getType().isAir()) {
            post.setType(Material.OAK_FENCE, false);
            post.getRelative(0, 1, 0).setType(Material.HAY_BLOCK, false);
            post.getRelative(0, 2, 0).setType(Material.CARVED_PUMPKIN, false);
        }
        Block tile = w.getBlockAt(ground.getX() + 1, g, ground.getZ());
        BuildingManager.Result res = plugin.buildings().register(null, c, BuildingType.FARM, tile, null);
        if (res.error() != null) return res.error();
        c.log(com.colonysmp.model.ColonyEvent.Type.CONSTRUCTION, plugin.sim().day(c), p.getName() + " laid out the first farm.");
        Fx.particle(ground.getLocation().add(0.5, 1.5, 0.5), Particle.HAPPY_VILLAGER, 50, 2.2);
        Fx.sound(ground.getLocation(), "minecraft:item.hoe.till", 1f, 1f);
        Fx.sound(ground.getLocation(), "minecraft:block.composter.fill_success", 1f, 1f);
        plugin.funnel().record(p.getUniqueId(), "first_building");
        plugin.sim().jobs().refreshWorkplaces(c);
        plugin.quests().check(c);
        plugin.requestSave();
        return null;
    }

    static String key(String s) {
        return s.toLowerCase(Locale.ROOT);
    }
}
