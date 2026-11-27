package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.npc.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rail;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.Wall;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.EntityTransformer;
import org.bukkit.util.Vector;

/**
 * The Meadowbrook Line: a straight railway along one x, from a station by the spawn plaza (south end) to
 * Meadowbrook's south gate (north end), on a viaduct over land, a tunnel through hills and a bridge over the sea.
 * One train shuttles between the two stations on a timetable. Its minecarts only exist while players are around;
 * otherwise it's just a clock. Riding needs a ticket from a station clerk (first ticket 60% off).
 */
public final class Railway implements Listener {
    private static final int Y = 80; // rail level; deck is Y-1
    enum State { BOARDING, RUNNING }

    private final TownManager t;
    private final NamespacedKey key, carKey;
    private final File file;
    private final Random r = new Random();
    // line
    private World w;
    private int lx, southZ, northZ; // southZ > northZ; rails run between them
    private boolean ready;
    // train
    private State state = State.BOARDING;
    private boolean atSouth = true;
    private long stateSince = System.currentTimeMillis();
    private double pos; // distance travelled on this run
    private double speed;
    private int n = 4;
    private double boardFront, runLength;
    private final List<RideableMinecart> cars = new ArrayList<>();
    /** The engine and carriage models (resource pack), one per car, following it every tick. */
    private final List<org.bukkit.entity.ItemDisplay> bodies = new ArrayList<>();
    private final List<Entity> npcRiders = new ArrayList<>();
    private int announced;

    Railway(TownManager t) {
        this.t = t;
        this.key = new NamespacedKey(t.plugin(), "railway");
        this.carKey = new NamespacedKey(t.plugin(), "traincar");
        this.file = new File(t.plugin().getDataFolder(), "railway.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        w = Bukkit.getWorld(y.getString("world", ""));
        if (w != null && y.contains("x")) {
            lx = y.getInt("x");
            southZ = y.getInt("south-z");
            northZ = y.getInt("north-z");
            ready = true;
        }
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    public boolean ready() {
        return ready;
    }

    // ------------------------------------------------------------------ geometry

    private int length() {
        return southZ - northZ;
    }

    /** Where the front car (the engine) stands while boarding: the train fills the platform back from the bumper. */
    private double boardFront(boolean south, int cars) {
        return south ? southZ - 0.5 - pad() - (cars - 1) * gap() : northZ + 0.5 + pad() + (cars - 1) * gap();
    }

    /** Distance between cars: the pack's engine and carriages are longer than a minecart. */
    private double gap() {
        return net.moddedrealms.smp.Pack.on() ? cfg().getDouble("town.railway.car-gap", 2.6) : 2.0;
    }

    /** How far the models stick out past the end cars, kept clear of the buffers. */
    private double pad() {
        return net.moddedrealms.smp.Pack.on() ? 0.8 : 0;
    }

    /** Where the engine stops on arrival: right at the bumper of the other end. */
    private double arriveFront(boolean fromSouth) {
        return fromSouth ? northZ + 0.5 + pad() : southZ - 0.5 - pad();
    }

    private int carCount() {
        int fits = (int) ((22 - 2 * pad()) / gap()) + 1; // the platform is 23 blocks long
        return Math.min(Math.min(fits, cfg().getInt("town.railway.max-cars", 10)), Math.max(4, playersAtStation(atSouth) + 3));
    }

    private int playersAtStation(boolean south) {
        int n = 0;
        Location p = platform(south);
        for (Player pl : w.getPlayers()) if (pl.getLocation().distance(p) < 24) n++;
        return n;
    }

    Location platform(boolean south) {
        double z = south ? southZ - 10 : northZ + 10;
        return new Location(w, lx + 4.5, Y, z);
    }

    // ------------------------------------------------------------------ tickets

    public double price(Player p, String kind) {
        double base = switch (kind) {
            case "return" -> cfg().getDouble("town.railway.return", 350);
            case "day" -> cfg().getDouble("town.railway.day-pass", 600);
            default -> cfg().getDouble("town.railway.single", 200);
        };
        return t.data(p).firstTicketBought ? base : Math.ceil(base * (1 - cfg().getDouble("town.railway.first-discount", 0.6)));
    }

    static final class Office implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    public void ticketOffice(Player p) {
        TownData d = t.data(p);
        if (!ready) {
            p.sendMessage(SmpPlugin.mm("<gray>The railway isn't built yet."));
            return;
        }
        Office o = new Office();
        o.inv = Bukkit.createInventory(o, 27, SmpPlugin.mm("<dark_green>Meadowbrook Line · Tickets"));
        String first = d.firstTicketBought ? "" : "<green>First-time traveller: 60% off!";
        o.inv.setItem(11, icon(Material.PAPER, "<white><bold>Single", "<gray>One trip, either way.", "", "<gold>" + Money.fmt(price(p, "single")), first));
        o.inv.setItem(13, icon(Material.MAP, "<white><bold>Return", "<gray>Two trips.", "", "<gold>" + Money.fmt(price(p, "return")), first));
        o.inv.setItem(15, icon(Material.CLOCK, "<white><bold>Day pass", "<gray>Unlimited trips for 24 hours.", "", "<gold>" + Money.fmt(price(p, "day")), first));
        long pass = d.dayPassUntil - System.currentTimeMillis();
        o.inv.setItem(22, icon(Material.NAME_TAG, "<gray>Your tickets", "<white>" + d.rides + " trip" + (d.rides == 1 ? "" : "s") + " left",
                pass > 0 ? "<white>Day pass: " + pass / 3_600_000 + "h " + pass / 60_000 % 60 + "m left" : "<dark_gray>No day pass"));
        p.openInventory(o.inv);
    }

    @EventHandler
    public void onOffice(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Office)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        String kind = switch (e.getRawSlot()) {
            case 11 -> "single";
            case 13 -> "return";
            case 15 -> "day";
            default -> null;
        };
        if (kind == null) return;
        TownData d = t.data(p);
        if (d.stars >= 3) {
            p.sendMessage(SmpPlugin.mm("<red>\"Sorry, I can't sell you a ticket. Your face is on every poster in town.\""));
            return;
        }
        double cost = price(p, kind);
        if (!t.money.pay(p, cost)) {
            p.sendMessage(SmpPlugin.mm("<red>That's " + Money.fmt(cost) + "."));
            return;
        }
        boolean wasFirst = !d.firstTicketBought;
        d.firstTicketBought = true;
        switch (kind) {
            case "return" -> d.rides += 2;
            case "day" -> d.dayPassUntil = Math.max(d.dayPassUntil, System.currentTimeMillis()) + 86_400_000L;
            default -> d.rides += 1;
        }
        t.markDirty();
        t.townTreasury(Meadowbrook.ID, cost);
        p.closeInventory();
        p.sendMessage(SmpPlugin.mm("<green>Ticket bought" + (wasFirst ? " with your first-trip discount" : "") + " (" + Money.fmt(cost) + "). <gray>Board any car when the train is in."));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.7f, 1.2f);
    }

    @EventHandler
    public void onOfficeDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Office) e.setCancelled(true);
    }

    private boolean hasTicket(TownData d) {
        return d.rides > 0 || d.dayPassUntil > System.currentTimeMillis();
    }

    private void useTicket(Player p) {
        TownData d = t.data(p);
        if (d.dayPassUntil > System.currentTimeMillis()) return;
        if (d.rides > 0) d.rides--;
        t.markDirty();
    }

    // ------------------------------------------------------------------ the train

    private boolean isCar(Entity e) {
        return e.getPersistentDataContainer().has(carKey, PersistentDataType.BYTE);
    }

    private void spawnTrain(double frontZ, int dir) {
        despawnTrain();
        for (int i = 0; i < n; i++) {
            double z = frontZ - dir * i * gap();
            Location l = new Location(w, lx + 0.5, Y + 0.0625, z, dir < 0 ? 180 : 0, 0);
            if (!w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            final boolean loco = i == 0;
            RideableMinecart c = w.spawn(l, RideableMinecart.class, m -> {
                m.setPersistent(false);
                m.setMaxSpeed(1.2);
                m.setSlowWhenEmpty(false);
                m.setInvulnerable(true);
                m.getPersistentDataContainer().set(carKey, PersistentDataType.BYTE, (byte) (loco ? 1 : 0));
                if (loco && !net.moddedrealms.smp.Pack.on()) {
                    m.setDisplayBlockData(Material.BLAST_FURNACE.createBlockData());
                    m.setDisplayBlockOffset(6);
                }
                if (loco) {
                    m.customName(SmpPlugin.mm("<gold>The Meadowbrook Express"));
                    m.setCustomNameVisible(true);
                }
            });
            cars.add(c);
            if (net.moddedrealms.smp.Pack.on()) bodies.add(body(c, loco));
        }
    }

    private org.bukkit.entity.ItemDisplay body(RideableMinecart c, boolean loco) {
        return w.spawn(c.getLocation(), org.bukkit.entity.ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(net.moddedrealms.smp.Pack.model(new org.bukkit.inventory.ItemStack(Material.PAPER), loco ? "train_engine" : "train_carriage"));
            d.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0, 0.5f, 0), new org.joml.Quaternionf(),
                    new org.joml.Vector3f(1, 1, 1), new org.joml.Quaternionf()));
            d.setTeleportDuration(2);
            d.setViewRange(2.5f);
            d.getPersistentDataContainer().set(carKey, PersistentDataType.BYTE, (byte) 2);
        });
    }

    /** Keep each model on its car, facing the way the train goes. */
    private void moveBodies() {
        float yaw = (float) (cfg().getDouble("town.railway.model-yaw", 0) + ((atSouth ? -1 : 1) < 0 ? 180 : 0));
        for (int i = 0; i < Math.min(bodies.size(), cars.size()); i++) {
            var d = bodies.get(i);
            var c = cars.get(i);
            if (!d.isValid() || !c.isValid()) continue;
            Location l = c.getLocation();
            l.setYaw(yaw);
            l.setPitch(0);
            d.teleport(l);
        }
    }

    private void despawnTrain() {
        for (Entity b : bodies) b.remove();
        bodies.clear();
        for (Entity e : npcRiders) e.remove();
        npcRiders.clear();
        for (RideableMinecart c : cars) {
            for (Entity p : c.getPassengers()) c.removePassenger(p);
            c.remove();
        }
        cars.clear();
    }

    private boolean playersAboard() {
        for (RideableMinecart c : cars) for (Entity e : c.getPassengers()) if (e instanceof Player) return true;
        return false;
    }

    private List<Player> riders() {
        List<Player> out = new ArrayList<>();
        for (RideableMinecart c : cars) for (Entity e : c.getPassengers()) if (e instanceof Player p) out.add(p);
        return out;
    }

    private void seatNpcs() {
        int want = Math.min(cfg().getInt("town.railway.npc-passengers", 3), cars.size() - 1 - riders().size());
        String[] names = {"Agnes", "Barnaby", "Clem", "Delphine", "Ezra", "Florence", "Gus", "Hattie", "Ike", "Josie", "Ned", "Ottilie"};
        String[] cols = {"#7A1F1F", "#1F3A7A", "#5A4A2A", "#2F5F2F", "#5A1F5A"};
        for (int i = cars.size() - 1; i >= 1 && want > 0; i--) {
            RideableMinecart c = cars.get(i);
            if (!c.getPassengers().isEmpty()) continue;
            Mannequin m = w.spawn(c.getLocation(), Mannequin.class, mq -> {
                mq.setPersistent(false);
                mq.setInvulnerable(true);
                mq.setSilent(true);
                mq.setAI(false);
                mq.customName(SmpPlugin.mm("<white>" + names[r.nextInt(names.length)]));
                mq.setCustomNameVisible(true);
                mq.setDescription(SmpPlugin.mm("<gray>Passenger"));
                mq.getEquipment().setChestplate(Walker.dyed(Material.LEATHER_CHESTPLATE, cols[r.nextInt(cols.length)]));
                if (r.nextBoolean()) mq.getEquipment().setHelmet(Walker.dyed(Material.LEATHER_HELMET, "#3A2A1A"));
                mq.getPersistentDataContainer().set(t.key, PersistentDataType.STRING, "PASSENGER");
                net.moddedrealms.smp.Pack.skin(mq, net.moddedrealms.smp.Pack.pick(r.nextBoolean() ? "resident_m" : "resident_f"));
            });
            npcRiders.add(m);
            c.addPassenger(m);
            want--;
        }
    }

    private void say(String msg, boolean south) {
        Location p = platform(south);
        for (Player pl : w.getPlayers()) {
            if (pl.getLocation().distance(p) < 40 || riders().contains(pl)) pl.sendMessage(SmpPlugin.mm("<dark_green>[Meadowbrook Line] <white>" + msg));
        }
    }

    /** Every tick. */
    void tick() {
        if (!ready || w == null) return;
        long now = System.currentTimeMillis();
        long boardMs = cfg().getLong("town.railway.board-seconds", 45) * 1000L;
        if (state == State.BOARDING) {
            String here = atSouth ? "the Old Town" : "Meadowbrook";
            String there = atSouth ? "Meadowbrook" : "the Old Town";
            boolean someone = playersAtStation(atSouth) > 0;
            if (someone && cars.isEmpty()) {
                n = carCount();
                spawnTrain(boardFront(atSouth, n), atSouth ? -1 : 1);
                say("The train to " + there + " is boarding. <gray>Tickets from the clerk; right-click a car to board.", atSouth);
            }
            long left = boardMs - (now - stateSince);
            if (left <= 15_000 && announced == 0 && someone) {
                announced = 1;
                say("<yellow>15 seconds to departure for " + there + "!", atSouth);
                w.playSound(platform(atSouth), Sound.BLOCK_BELL_USE, 2f, 1.2f);
            }
            if (left <= 0) {
                if (!playersAboard()) despawnTrain();
                else {
                    seatNpcs();
                    say("<green>All aboard! Next stop: " + there + ".", atSouth);
                    w.playSound(cars.get(0).getLocation(), Sound.ENTITY_GOAT_SCREAMING_LONG_JUMP, 2f, 0.5f);
                }
                state = State.RUNNING;
                stateSince = now;
                boardFront = boardFront(atSouth, cars.isEmpty() ? 4 : cars.size());
                runLength = Math.abs(arriveFront(atSouth) - boardFront);
                pos = 0;
                speed = 0;
                announced = 0;
            }
            for (RideableMinecart c : cars) c.setVelocity(new Vector());
            moveBodies();
            return;
        }
        // RUNNING
        double max = cfg().getDouble("town.railway.speed", 1.0), acc = 0.02;
        double total = runLength;
        double remaining = total - pos;
        speed = remaining < speed * speed / (2 * acc) + 2 ? Math.max(0.15, speed - acc) : Math.min(max, speed + acc);
        pos = Math.min(total, pos + speed);
        int dir = atSouth ? -1 : 1;
        double frontZ = boardFront + dir * pos;
        if (!cars.isEmpty()) {
            if (!playersAboard()) {
                despawnTrain();
            } else {
                for (int i = 0; i < cars.size(); i++) {
                    RideableMinecart c = cars.get(i);
                    if (!c.isValid()) continue;
                    double want = frontZ - dir * i * gap();
                    double drift = want - c.getLocation().getZ();
                    c.setVelocity(new Vector(0, 0, dir * speed + Math.max(-0.3, Math.min(0.3, drift * 0.5))));
                }
                if (now % 1000 < 50) {
                    Location smoke = cars.get(0).getLocation().add(0, 1.6, 0);
                    w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, smoke, 2, 0.1, 0.1, 0.1, 0.02);
                }
            }
        }
        moveBodies();
        if (pos >= total) arrive();
    }

    private void arrive() {
        boolean nowSouth = !atSouth;
        String name = nowSouth ? "the Old Town" : "Meadowbrook";
        Location platform = platform(nowSouth);
        for (Player p : riders()) {
            for (RideableMinecart c : cars) c.removePassenger(p);
            useTicket(p);
            t.teleport(p, platform.clone().add(0, 0, nowSouth ? 2 : -2));
            p.sendMessage(SmpPlugin.mm("<dark_green>[Meadowbrook Line] <white>Welcome to " + name + "! <gray>Mind the gap."));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.4f);
        }
        despawnTrain();
        atSouth = nowSouth;
        state = State.BOARDING;
        stateSince = System.currentTimeMillis();
        announced = 0;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBoard(VehicleEnterEvent e) {
        if (!isCar(e.getVehicle())) return;
        if (!(e.getEntered() instanceof Player p)) {
            if (!npcRiders.contains(e.getEntered())) e.setCancelled(true);
            return;
        }
        if (e.getVehicle().getPersistentDataContainer().getOrDefault(carKey, PersistentDataType.BYTE, (byte) 0) == 1) {
            e.setCancelled(true);
            p.sendActionBar(SmpPlugin.mm("<gray>That's the engine. Pick a passenger car."));
            return;
        }
        if (state != State.BOARDING) {
            e.setCancelled(true);
            return;
        }
        if (!hasTicket(t.data(p))) {
            e.setCancelled(true);
            p.sendMessage(Component.text().append(SmpPlugin.mm("<red>You need a ticket. "))
                    .append(SmpPlugin.mm("<green>[Buy one]").clickEvent(ClickEvent.runCommand("/ticket"))).build());
            return;
        }
        p.sendActionBar(SmpPlugin.mm("<dark_green>Ticket checked. <gray>Departing in " + Math.max(0,
                (cfg().getLong("town.railway.board-seconds", 45) * 1000L - (System.currentTimeMillis() - stateSince)) / 1000) + "s"));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLeave(VehicleExitEvent e) {
        if (isCar(e.getVehicle()) && state == State.RUNNING && e.getExited() instanceof Player p) {
            e.setCancelled(true);
            p.sendActionBar(SmpPlugin.mm("<gray>Not while the train is moving!"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCarDamage(VehicleDamageEvent e) {
        if (isCar(e.getVehicle())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCarDestroy(VehicleDestroyEvent e) {
        if (isCar(e.getVehicle())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCarBump(VehicleEntityCollisionEvent e) {
        if (isCar(e.getVehicle())) {
            e.setCancelled(true);
            e.setCollisionCancelled(true);
        }
    }

    void shutdown() {
        for (Player p : riders()) t.teleport(p, platform(atSouth));
        despawnTrain();
    }

    public String status() {
        if (!ready) return "Railway: not built.";
        long left = cfg().getLong("town.railway.board-seconds", 45) * 1000L - (System.currentTimeMillis() - stateSince);
        return "Railway: line x=" + lx + " from z " + southZ + " to " + northZ + ". Train " + (state == State.BOARDING
                ? "boarding at " + (atSouth ? "the Old Town" : "Meadowbrook") + ", leaves in " + Math.max(0, left / 1000) + "s"
                : "running towards " + (atSouth ? "Meadowbrook" : "the Old Town") + ", " + (int) pos + "/" + length() + " blocks") + ", " + cars.size() + " cars spawned"
                + (cars.isEmpty() ? "." : ", engine at z " + String.format("%.1f", cars.get(0).getLocation().getZ()) + " v " + String.format("%.2f", cars.get(0).getVelocity().getZ())
                + " max " + cars.get(0).getMaxSpeed() + " block " + cars.get(0).getLocation().getBlock().getType());
    }

    // ------------------------------------------------------------------ the generated line

    private File structureFile() {
        return new File(t.plugin().getDataFolder(), "railway-undo.nbt");
    }

    private File metaFile() {
        return new File(t.plugin().getDataFolder(), "railway-undo.yml");
    }

    public boolean built() {
        return structureFile().exists();
    }

    private int ground(int x, int z) {
        int y = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.OCEAN_FLOOR);
        return y;
    }

    public String check(World world, int x, int sZ, int nZ) {
        this.w = world;
        int tiles = 0, built = 0, high = 0, sea = 0, low = 999;
        for (int z = nZ - 8; z <= sZ + 12; z++) {
            for (int dx = -6; dx <= 8; dx++) {
                Block top = w.getHighestBlockAt(x + dx, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
                String n = top.getType().name();
                if (dx == 0 && top.getY() >= Y - 1) high++;
                if (dx == 0 && top.isLiquid()) sea++;
                if (dx == 0) low = Math.min(low, ground(x, z));
                if (n.contains("PLANKS") || n.contains("BRICK") || n.contains("GLASS") || n.contains("WOOL") || n.contains("CONCRETE")) built++;
            }
        }
        for (int cxk = (x - 6) >> 4; cxk <= (x + 8) >> 4; cxk++)
            for (int czk = (nZ - 8) >> 4; czk <= (sZ + 12) >> 4; czk++)
                for (var s : w.getChunkAt(cxk, czk).getTileEntities()) if (s.getX() >= x - 6 && s.getX() <= x + 8 && s.getY() > 40) tiles++;
        return "Railway x=" + x + " z " + sZ + " -> " + nZ + " (" + (sZ - nZ) + " blocks): " + high + " blocks of ground at/above the deck (tunnel), "
                + sea + " over water, lowest ground " + low + ", " + built + " built-looking blocks on top, " + tiles + " block entities above y 40 near the line.";
    }

    public String build(World world, int x, int sZ, int nZ) throws IOException {
        if (built()) throw new IOException("The railway is already built. /mrtown railway undo first.");
        this.w = world;
        this.lx = x;
        this.southZ = sZ;
        this.northZ = nZ;
        int minY = Y - 1;
        for (int z = nZ - 8; z <= sZ + 12; z++) minY = Math.min(minY, ground(x, z) - 1);
        minY = Math.max(minY, w.getMinHeight());
        // The spawn-side stairs and path go east of the line, the Meadowbrook stairs go north of it.
        int gateZ = sZ + 3;
        File pf = new File(t.plugin().getDataFolder(), "plaza-undo.yml");
        if (pf.exists()) gateZ = Math.max(gateZ, YamlConfiguration.loadConfiguration(pf).getInt("z") - 26);
        int[] a = {x - 6, minY, nZ - 16, x + 40, Y + 8, gateZ};
        Structure st = Bukkit.getStructureManager().createStructure();
        st.fill(new Location(w, a[0], a[1], a[2]), new BlockVector(a[3] - a[0] + 1, a[4] - a[1] + 1, a[5] - a[2] + 1), true);
        Bukkit.getStructureManager().saveStructure(structureFile(), st);
        YamlConfiguration meta = new YamlConfiguration();
        meta.set("world", w.getName());
        meta.set("area", List.of(a[0], a[1], a[2], a[3], a[4], a[5]));
        net.moddedrealms.smp.SafeYaml.save(meta, metaFile());
        try {
            line();
            stations();
        } catch (RuntimeException ex) {
            t.plugin().getLogger().log(java.util.logging.Level.SEVERE, "Railway build failed, undoing", ex);
            undo();
            throw new IOException("Building failed (" + ex + "), so everything was put back as it was.");
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", w.getName());
        y.set("x", x);
        y.set("south-z", sZ);
        y.set("north-z", nZ);
        net.moddedrealms.smp.SafeYaml.save(y, file);
        ready = true;
        state = State.BOARDING;
        atSouth = true;
        stateSince = System.currentTimeMillis();
        return "Meadowbrook Line built: x=" + x + ", " + (sZ - nZ) + " blocks from z " + sZ + " to " + nZ + ". Undo with /mrtown railway undo.";
    }

    private void set(int x, int y, int z, Material m) {
        w.getBlockAt(x, y, z).setType(m, false);
    }

    private void wallZ(int x, int y, int z) {
        Wall wl = (Wall) Material.STONE_BRICK_WALL.createBlockData();
        wl.setHeight(BlockFace.NORTH, Wall.Height.LOW);
        wl.setHeight(BlockFace.SOUTH, Wall.Height.LOW);
        wl.setUp(false);
        w.getBlockAt(x, y, z).setBlockData(wl, false);
    }

    private void line() {
        for (int z = northZ - 1; z <= southZ + 1; z++) {
            int g = ground(lx, z);
            boolean tunnel = false;
            for (int dx = -2; dx <= 2; dx++) if (w.getHighestBlockYAt(lx + dx, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) >= Y + 4) tunnel = true;
            // deck and track
            for (int dx = -2; dx <= 2; dx++) {
                set(lx + dx, Y - 1, z, Math.abs(dx) == 2 ? Material.POLISHED_ANDESITE : Material.STONE_BRICKS);
                for (int y = Y; y <= Y + 4; y++) set(lx + dx, y, z, Material.AIR);
            }
            if (z >= northZ && z <= southZ) {
                Rail rail = (Rail) Material.RAIL.createBlockData();
                rail.setShape(Rail.Shape.NORTH_SOUTH);
                w.getBlockAt(lx, Y, z).setBlockData(rail, false);
            }
            wallZ(lx - 2, Y, z);
            wallZ(lx + 2, Y, z);
            if (tunnel) {
                for (int dx = -3; dx <= 3; dx++) set(lx + dx, Y + 5, z, Material.STONE_BRICKS);
                for (int y = Y - 1; y <= Y + 4; y++) {
                    set(lx - 3, y, z, Material.STONE_BRICKS);
                    set(lx + 3, y, z, Material.STONE_BRICKS);
                }
                if (Math.floorMod(z, 8) == 0) {
                    Lantern l = (Lantern) Material.LANTERN.createBlockData();
                    l.setHanging(true);
                    w.getBlockAt(lx, Y + 4, z).setBlockData(l, false);
                }
            } else {
                // clear anything sticking up above the deck nearby (trees, ridges)
                for (int dx = -3; dx <= 3; dx++) for (int y = Y + 5; y <= Y + 8; y++) {
                    Block b = w.getBlockAt(lx + dx, y, z);
                    if (!b.getType().isAir() && Math.abs(dx) <= 2) b.setType(Material.AIR, false);
                }
                if (Math.floorMod(z, 16) == 0) {
                    set(lx + 2, Y + 1, z, Material.LANTERN);
                    set(lx - 2, Y + 1, z, Material.LANTERN);
                }
            }
            // pillars every 10 blocks down to solid ground or the sea floor, with an arch cap
            if (Math.floorMod(z, 10) == 0 && g < Y - 2) {
                for (int dx = -2; dx <= 2; dx++) set(lx + dx, Y - 2, z, Material.POLISHED_ANDESITE);
                for (int y = Y - 3; y >= g; y--) {
                    for (int dx = -1; dx <= 1; dx++) {
                        Block b = w.getBlockAt(lx + dx, y, z);
                        if (y < g && !b.isPassable() && !b.isLiquid()) break;
                        b.setType(dx == 0 ? Material.STONE_BRICKS : Material.STONE_BRICKS, false);
                    }
                }
            }
        }
        // bumpers
        set(lx, Y, southZ + 1, Material.POLISHED_BLACKSTONE_WALL);
        set(lx, Y + 1, southZ + 1, Material.REDSTONE_LAMP);
        set(lx, Y, northZ - 1, Material.POLISHED_BLACKSTONE_WALL);
        set(lx, Y + 1, northZ - 1, Material.REDSTONE_LAMP);
    }

    private void stations() {
        station(true);
        station(false);
        // Old Town: stairs east from the south end of the platform down to the ground, then a path to the plaza.
        int sz = southZ - 2;
        int x = lx + 6, y = Y - 1;
        while (y > ground(x, sz) && x < lx + 40) {
            for (int dz = -1; dz <= 1; dz++) {
                Stairs s = (Stairs) Material.STONE_BRICK_STAIRS.createBlockData();
                s.setFacing(BlockFace.WEST);
                w.getBlockAt(x, y, sz + dz).setBlockData(s, false);
                for (int yy = y - 1; yy >= ground(x, sz + dz); yy--) set(x, yy, sz + dz, Material.STONE_BRICKS);
                for (int yy = y + 1; yy <= y + 3; yy++) set(x, yy, sz + dz, Material.AIR);
            }
            x++;
            y--;
        }
        path(x, sz);
        // Meadowbrook: stairs north from the bumper down through the south gate.
        int nz = northZ - 2, yy = Y - 1;
        while (yy > ground(lx, nz) && nz > northZ - 12) {
            for (int dx = -1; dx <= 1; dx++) {
                Stairs s = (Stairs) Material.STONE_BRICK_STAIRS.createBlockData();
                s.setFacing(BlockFace.SOUTH);
                w.getBlockAt(lx + dx, yy, nz).setBlockData(s, false);
                for (int y2 = yy - 1; y2 >= ground(lx + dx, nz); y2--) set(lx + dx, y2, nz, Material.STONE_BRICKS);
                for (int y2 = yy + 1; y2 <= yy + 4; y2++) set(lx + dx, y2, nz, Material.AIR);
            }
            nz--;
            yy--;
        }
    }

    /** 3-wide path from the bottom of the Old Town stairs to the plaza's north gate, following the ground. */
    private void path(int fromX, int z) {
        File pf = new File(t.plugin().getDataFolder(), "plaza-undo.yml");
        if (!pf.exists()) return;
        YamlConfiguration py = YamlConfiguration.loadConfiguration(pf);
        int gx = py.getInt("x"), gz = py.getInt("z") - 26;
        for (int x = fromX; x <= gx + 1; x++) for (int dz = -1; dz <= 1; dz++) pave(x, z + dz, dz == 0 ? Material.SMOOTH_STONE : Material.POLISHED_DEEPSLATE);
        for (int zz = z + 2; zz <= gz; zz++) for (int dx = -1; dx <= 1; dx++) pave(gx + dx, zz, dx == 0 ? Material.SMOOTH_STONE : Material.POLISHED_DEEPSLATE);
    }

    private void pave(int x, int z, Material m) {
        int g = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Block b = w.getBlockAt(x, g, z);
        if (b.getType().name().contains("LOG") || b.getType().name().contains("STAIRS") || g > Y) return;
        b.setType(m, false);
        for (int y = g + 1; y <= g + 3; y++) {
            Block a = w.getBlockAt(x, y, z);
            if (!a.getType().isAir() && a.isPassable()) a.setType(Material.AIR, false);
        }
    }

    /** Platform east of the track (3 wide) with a canopy, benches, a clerk and a sign. */
    private void station(boolean south) {
        int z0 = south ? southZ - 22 : northZ - 1, z1 = south ? southZ : northZ + 21;
        for (int z = z0; z <= z1; z++) {
            for (int dx = 1; dx <= 5; dx++) {
                set(lx + dx, Y - 1, z, dx >= 3 ? Material.SMOOTH_STONE : Material.STONE_BRICKS);
                for (int y = Y; y <= Y + 4; y++) set(lx + dx, y, z, Material.AIR);
            }
            set(lx + 6, Y - 1, z, Material.POLISHED_ANDESITE);
            wallZ(lx + 6, Y, z);
            for (int y = Y - 2; y >= ground(lx + 4, z); y--) {
                if (Math.floorMod(z, 6) != 0) break;
                set(lx + 4, y, z, Material.STONE_BRICKS);
            }
            if (Math.floorMod(z, 6) == 0) {
                for (int y = Y; y <= Y + 2; y++) set(lx + 5, y, z, Material.DARK_OAK_FENCE);
                Lantern l = (Lantern) Material.LANTERN.createBlockData();
                l.setHanging(true);
                w.getBlockAt(lx + 3, Y + 2, z).setBlockData(l, false);
            }
            for (int dx = 1; dx <= 6; dx++) {
                Slab s = (Slab) Material.DARK_OAK_SLAB.createBlockData();
                s.setType(Slab.Type.BOTTOM);
                w.getBlockAt(lx + dx, Y + 3, z).setBlockData(s, false);
            }
            if (Math.floorMod(z, 6) == 3) {
                Stairs b = (Stairs) Material.SPRUCE_STAIRS.createBlockData();
                b.setFacing(BlockFace.EAST);
                w.getBlockAt(lx + 5, Y, z).setBlockData(b, false);
            }
        }
        // The west railing stays; the east side of the track is open along the platform.
        for (int z = z0; z <= z1; z++) set(lx + 2, Y, z, Material.AIR);
        if (south) set(lx + 6, Y, southZ - 2, Material.AIR); // the way to the stairs
        Location p = platform(south);
        String name = south ? "Old Town Station" : "Meadowbrook Station";
        hologram(p.clone().add(0, 3.8, 0), "<dark_green><bold>" + name + "</bold>\n<gray>Meadowbrook Line · tickets from the clerk");
        NpcDef d = new NpcDef();
        d.id = south ? "rail_clerk_south" : "rail_clerk_north";
        d.name = "<dark_green>" + (south ? "Clerk Hobbs" : "Clerk Winnie");
        d.title = "<gray>Tickets · /ticket";
        d.world = w.getName();
        d.x = lx + 5.5;
        d.y = Y;
        d.z = south ? southZ - 5.5 : northZ + 5.5;
        d.yaw = 90;
        d.group = "railway";
        d.action = "command:ticket";
        d.equipment.put(EquipmentSlot.HAND, Material.PAPER);
        d.equipment.put(EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE);
        d.leatherColor = Color.fromRGB(0x2F5F2F);
        t.plugin().npcs().put(d);
    }

    private void hologram(Location l, String text) {
        w.spawn(l, TextDisplay.class, d -> {
            d.text(SmpPlugin.mm(text));
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            d.setPersistent(true);
            d.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
    }

    public String undo() throws IOException {
        if (!built()) throw new IOException("The railway isn't built.");
        YamlConfiguration meta = YamlConfiguration.loadConfiguration(metaFile());
        World uw = Bukkit.getWorld(meta.getString("world", ""));
        if (uw == null) throw new IOException("World not loaded.");
        shutdown();
        ready = false;
        List<Integer> a = meta.getIntegerList("area");
        BoundingBox box = new BoundingBox(a.get(0), a.get(1), a.get(2), a.get(3) + 1, a.get(4) + 1, a.get(5) + 1);
        Structure st = Bukkit.getStructureManager().loadStructure(structureFile());
        for (Entity e : uw.getNearbyEntities(box)) if (e.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) e.remove();
        t.plugin().npcs().removeGroup("railway");
        for (int y = a.get(4); y >= a.get(1); y--) for (int x = a.get(0); x <= a.get(3); x++) for (int z = a.get(2); z <= a.get(5); z++) uw.getBlockAt(x, y, z).setType(Material.AIR, false);
        EntityTransformer onlyLoose = (region, x, y, z, entity, allowed) -> !(entity instanceof LivingEntity);
        st.place(new Location(uw, a.get(0), a.get(1), a.get(2)), true, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random(), List.of(), List.of(onlyLoose));
        File done = new File(t.plugin().getDataFolder(), "railway-undone-" + System.currentTimeMillis() + ".nbt");
        structureFile().renameTo(done);
        metaFile().delete();
        file.delete();
        return "Railway removed and the land restored. Snapshot kept as " + done.getName() + ".";
    }

    // ------------------------------------------------------------------ protection

    private boolean onLine(Location l) {
        return ready && l.getWorld() == w && Math.abs(l.getBlockX() - lx - 2) <= 5 && l.getBlockZ() >= northZ - 12 && l.getBlockZ() <= southZ + 3
                && l.getBlockY() >= Y - 3 && l.getBlockY() <= Y + 6;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (onLine(e.getBlock().getLocation()) && !e.getPlayer().hasPermission("mrsmp.admin")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (onLine(e.getBlock().getLocation()) && !e.getPlayer().hasPermission("mrsmp.admin")) e.setCancelled(true);
    }
}
