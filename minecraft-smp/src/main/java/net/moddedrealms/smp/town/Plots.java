package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
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
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;

/**
 * Plots in generated towns. Each plot is either bare land or comes with a building, and can be bought or rented
 * (paid daily from your balance; miss a day and there's one day of grace, then the plot goes back on the market).
 * Inside a generated town nobody can build except on their own (or trusted) plots; town buildings, other people's
 * plots and NPC businesses are protected, containers included.
 */
public final class Plots implements Listener, TabExecutor {
    public enum Kind { BUSINESS, HOME, INDUSTRY }

    public static final class Plot {
        public String id, town, world, label = "";
        public int x0, z0, x1, z1, floor;
        public Kind kind;
        public boolean built, npc, rented;
        public double price, rent;
        public UUID owner;
        public long paidUntil;
        public final Set<UUID> trusted = new HashSet<>();
        public UUID sign;
        public String business;

        public boolean contains(Location l) {
            return l.getWorld() != null && l.getWorld().getName().equals(world) && l.getBlockX() >= x0 && l.getBlockX() <= x1
                    && l.getBlockZ() >= z0 && l.getBlockZ() <= z1 && l.getBlockY() >= floor - 6 && l.getBlockY() <= floor + 40;
        }

        public Location centre(World w) {
            return new Location(w, (x0 + x1) / 2.0 + 0.5, floor + 1, (z0 + z1) / 2.0 + 0.5);
        }

        public boolean free() {
            return owner == null && !npc;
        }
    }

    private final TownManager t;
    private final File file;
    private final NamespacedKey key;
    private final Map<String, Plot> plots = new LinkedHashMap<>();
    /** Whole generated towns: protected outside plots. */
    private final Map<String, BoundingBox> towns = new LinkedHashMap<>();
    private final Map<String, String> townWorld = new LinkedHashMap<>();
    private int tick;

    Plots(TownManager t) {
        this.t = t;
        this.file = new File(t.plugin().getDataFolder(), "plots.yml");
        this.key = new NamespacedKey(t.plugin(), "plot");
        load();
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ts = y.getConfigurationSection("towns");
        if (ts != null) for (String id : ts.getKeys(false)) {
            List<Double> b = ts.getDoubleList(id + ".box");
            if (b.size() == 6) towns.put(id, new BoundingBox(b.get(0), b.get(1), b.get(2), b.get(3), b.get(4), b.get(5)));
            townWorld.put(id, ts.getString(id + ".world", "world"));
        }
        ConfigurationSection ps = y.getConfigurationSection("plots");
        if (ps == null) return;
        for (String id : ps.getKeys(false)) {
            ConfigurationSection s = ps.getConfigurationSection(id);
            Plot p = new Plot();
            p.id = id;
            p.town = s.getString("town");
            p.world = s.getString("world");
            p.label = s.getString("label", "");
            p.x0 = s.getInt("x0");
            p.z0 = s.getInt("z0");
            p.x1 = s.getInt("x1");
            p.z1 = s.getInt("z1");
            p.floor = s.getInt("floor");
            p.kind = Kind.valueOf(s.getString("kind", "BUSINESS"));
            p.built = s.getBoolean("built");
            p.npc = s.getBoolean("npc");
            p.rented = s.getBoolean("rented");
            p.price = s.getDouble("price");
            p.rent = s.getDouble("rent");
            p.paidUntil = s.getLong("paid-until");
            p.business = s.getString("business");
            String o = s.getString("owner");
            p.owner = o == null ? null : UUID.fromString(o);
            for (String u : s.getStringList("trusted")) p.trusted.add(UUID.fromString(u));
            String sg = s.getString("sign");
            p.sign = sg == null ? null : UUID.fromString(sg);
            plots.put(id, p);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : towns.entrySet()) {
            BoundingBox b = e.getValue();
            y.set("towns." + e.getKey() + ".box", List.of(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ()));
            y.set("towns." + e.getKey() + ".world", townWorld.get(e.getKey()));
        }
        for (Plot p : plots.values()) {
            ConfigurationSection s = y.createSection("plots." + p.id);
            s.set("town", p.town);
            s.set("world", p.world);
            s.set("label", p.label);
            s.set("x0", p.x0);
            s.set("z0", p.z0);
            s.set("x1", p.x1);
            s.set("z1", p.z1);
            s.set("floor", p.floor);
            s.set("kind", p.kind.name());
            s.set("built", p.built);
            s.set("npc", p.npc);
            s.set("rented", p.rented);
            s.set("price", p.price);
            s.set("rent", p.rent);
            s.set("paid-until", p.paidUntil);
            s.set("business", p.business);
            s.set("owner", p.owner == null ? null : p.owner.toString());
            List<String> tr = new ArrayList<>();
            for (UUID u : p.trusted) tr.add(u.toString());
            s.set("trusted", tr);
            s.set("sign", p.sign == null ? null : p.sign.toString());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            t.plugin().getLogger().warning("Could not save plots.yml: " + e.getMessage());
        }
    }

    public Plot at(Location l) {
        for (Plot p : plots.values()) if (p.contains(l)) return p;
        return null;
    }

    public java.util.Collection<Plot> all() {
        return plots.values();
    }

    public Plot get(String id) {
        return plots.get(id);
    }

    public List<Plot> inTown(String town) {
        List<Plot> out = new ArrayList<>();
        for (Plot p : plots.values()) if (town.equals(p.town)) out.add(p);
        return out;
    }

    public List<Plot> ownedBy(UUID u) {
        List<Plot> out = new ArrayList<>();
        for (Plot p : plots.values()) if (u.equals(p.owner)) out.add(p);
        return out;
    }

    /** Called by a town builder. */
    void registerTown(String id, World w, BoundingBox box) {
        towns.put(id, box);
        townWorld.put(id, w.getName());
        save();
    }

    void addPlot(Plot p) {
        plots.put(p.id, p);
        refreshSign(p);
        save();
    }

    /** Called when a town is undone. */
    void removeTown(String id) {
        towns.remove(id);
        townWorld.remove(id);
        for (Plot p : new ArrayList<>(plots.values())) {
            if (!id.equals(p.town)) continue;
            removeSign(p);
            plots.remove(p.id);
        }
        save();
    }

    public String townAt(Location l) {
        if (l.getWorld() == null) return null;
        for (var e : towns.entrySet()) {
            if (l.getWorld().getName().equals(townWorld.get(e.getKey())) && e.getValue().contains(l.toVector())) return e.getKey();
        }
        return null;
    }

    // ------------------------------------------------------------------ signs

    private void removeSign(Plot p) {
        if (p.sign == null) return;
        Entity e = Bukkit.getEntity(p.sign);
        if (e != null) e.remove();
        p.sign = null;
    }

    void refreshSign(Plot p) {
        World w = Bukkit.getWorld(p.world);
        if (w == null) return;
        String text;
        if (p.npc) {
            text = "<gold><bold>" + (p.label.isEmpty() ? "Town business" : p.label) + "</bold>";
        } else if (p.owner == null) {
            String what = p.built ? "with building" : "bare plot";
            text = "<green><bold>" + (p.kind == Kind.HOME ? "HOME" : p.kind == Kind.INDUSTRY ? "INDUSTRIAL" : "BUSINESS") + " PLOT " + p.id.toUpperCase(Locale.ROOT) + "</bold>\n<gray>" + what
                    + "\n<white>Buy " + Money.fmt(p.price) + " <gray>· <white>Rent " + Money.fmt(p.rent) + "/day\n<yellow>/plot buy <gray>or <yellow>/plot rent";
        } else {
            OfflinePlayer o = Bukkit.getOfflinePlayer(p.owner);
            text = (p.business != null ? "<gold><bold>" + p.business + "</bold>\n" : "") + "<gray>" + (p.kind == Kind.HOME ? "Home of " : "Owner: ") + "<white>" + o.getName();
        }
        Location at = p.centre(w).add(0, p.built ? 0 : 1.6, 0);
        if (p.built) {
            // On a built plot the sign floats out front (the edge nearest the street is stored as the label anchor).
            at = new Location(w, (p.x0 + p.x1) / 2.0 + 0.5, p.floor + 6.5, (p.z0 + p.z1) / 2.0 + 0.5);
        }
        Entity e = p.sign == null ? null : Bukkit.getEntity(p.sign);
        if (e instanceof TextDisplay td) {
            td.text(SmpPlugin.mm(text));
            return;
        }
        if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) return;
        final String txt = text;
        p.sign = w.spawn(at, TextDisplay.class, d -> {
            d.text(SmpPlugin.mm(txt));
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
            d.setPersistent(true);
            d.getPersistentDataContainer().set(key, PersistentDataType.STRING, p.id);
        }).getUniqueId();
    }

    // ------------------------------------------------------------------ rent

    /** Every second. */
    void second() {
        if (++tick % 60 != 0) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Plot p : plots.values()) {
            if (p.owner == null || !p.rented || now < p.paidUntil) continue;
            OfflinePlayer o = Bukkit.getOfflinePlayer(p.owner);
            if (t.money.bankPay(o, p.rent)) {
                p.paidUntil += 86_400_000L;
                if (o.getPlayer() != null) o.getPlayer().sendMessage(SmpPlugin.mm("<gray>Rent for plot " + p.id + ": <white>-" + Money.fmt(p.rent)));
                t.townTreasury(p.town, p.rent);
            } else if (now > p.paidUntil + 86_400_000L) {
                if (o.getPlayer() != null) o.getPlayer().sendMessage(SmpPlugin.mm("<red>You couldn't pay the rent on plot " + p.id + ". The landlord has taken it back."));
                t.news(o.getName() + " was evicted from plot " + p.id + " in " + p.town + ".");
                release(p);
            } else if (o.getPlayer() != null && tick % 600 == 0) {
                o.getPlayer().sendMessage(SmpPlugin.mm("<red>Rent for plot " + p.id + " is overdue (" + Money.fmt(p.rent) + "). Pay it within a day or lose the plot."));
            }
            changed = true;
        }
        if (changed) save();
    }

    private void release(Plot p) {
        p.owner = null;
        p.rented = false;
        p.paidUntil = 0;
        p.trusted.clear();
        p.business = null;
        t.businesses().plotReleased(p);
        refreshSign(p);
        save();
    }

    // ------------------------------------------------------------------ protection

    public boolean canBuild(Player p, Location l) {
        if (p.hasPermission("mrsmp.admin")) return true;
        if (townAt(l) == null) return true;
        Plot pl = at(l);
        return pl != null && !pl.npc && (p.getUniqueId().equals(pl.owner) || pl.trusted.contains(p.getUniqueId()));
    }

    /**
     * Someone who works here, but can't build: say how the job works instead of "not yours". Null when the player
     * doesn't work at the business on this spot.
     */
    private String jobHint(Player p, Location l) {
        Businesses.Biz here = t.businesses().at(l);
        if (here == null) return null;
        if (here == t.businesses().work().shiftBiz(p.getUniqueId())) {
            String target = t.businesses().work().targetName(p.getUniqueId());
            return target == null ? "<yellow>You're on shift here: <white>wait for the next task</white>, then work the glowing block."
                    : "<yellow>You're on shift: <white>right-click or hit the glowing " + target + "</white> to do the task.";
        }
        if (here.workers.contains(p.getUniqueId())) return "<yellow>You work at " + here.name + ": <white>/job clockin</white> to start your shift.";
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            String hint = jobHint(e.getPlayer(), e.getBlock().getLocation());
            e.getPlayer().sendActionBar(SmpPlugin.mm(hint != null ? hint : "<red>You can only build on your own plot here. <gray>/plot"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(SmpPlugin.mm("<red>You can only build on your own plot here. <gray>/plot"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onContainer(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (!(b.getState() instanceof InventoryHolder) && b.getType() != Material.ANVIL) return;
        // a business's lectern is its counter: the counter opens instead of the lectern (sneak-clicks stay protected)
        if (b.getType() == Material.LECTERN && !e.getPlayer().isSneaking() && t.businesses().at(b.getLocation()) != null) return;
        if (!canBuild(e.getPlayer(), b.getLocation())) {
            e.setCancelled(true);
            // a worker on shift here is cooking or doing a station task, not raiding the stores: no telling-off
            if (t.businesses().work().isTarget(e.getPlayer().getUniqueId(), b)) return;
            Businesses.Biz here = t.businesses().at(b.getLocation());
            if (here != null && here == t.businesses().work().shiftBiz(e.getPlayer().getUniqueId()) && Dining.handles(here)) return;
            String hint = jobHint(e.getPlayer(), b.getLocation());
            e.getPlayer().sendActionBar(SmpPlugin.mm(hint != null ? hint : "<red>That isn't yours."));
        }
    }

    // ------------------------------------------------------------------ commands

    private static boolean say(CommandSender s, String m) {
        s.sendMessage(SmpPlugin.mm(m));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (!(sender instanceof Player p)) return say(sender, "Players only.");
        String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
        Plot here = at(p.getLocation());
        switch (sub) {
            case "buy", "rent" -> {
                if (here == null) return say(p, "<gray>Stand on a plot first. <white>/plot list</white> shows them.");
                if (!here.free()) return say(p, "<red>This plot isn't available.");
                int max = cfg().getInt("town.plots.max-per-player", 3);
                if (ownedBy(p.getUniqueId()).size() >= max) return say(p, "<red>You can hold at most " + max + " plots.");
                if (t.data(p).stars > 0) return say(p, "<red>The land office doesn't deal with wanted people.");
                boolean renting = sub.equals("rent");
                double cost = renting ? here.rent : here.price;
                if (!t.money.pay(p, cost)) return say(p, "<red>You need " + Money.fmt(cost) + (renting ? " (the first day's rent)." : "."));
                here.owner = p.getUniqueId();
                here.rented = renting;
                here.paidUntil = renting ? System.currentTimeMillis() + 86_400_000L : 0;
                t.townTreasury(here.town, cost);
                refreshSign(here);
                save();
                say(p, "<green>Plot " + here.id + " is yours" + (renting ? " to rent at " + Money.fmt(here.rent) + " a day (taken from your balance)" : "") + "! "
                        + "<gray>You can build on it" + (here.kind == Kind.BUSINESS || here.kind == Kind.INDUSTRY ? ", and start a business with <white>/business start</white>" : "") + ".");
                t.news(p.getName() + (renting ? " rented " : " bought ") + "plot " + here.id + " in " + here.town + ".");
            }
            case "leave", "sell" -> {
                if (here == null || !p.getUniqueId().equals(here.owner)) return say(p, "<gray>Stand on a plot you own.");
                if (!here.rented) {
                    double back = Math.floor(here.price * cfg().getDouble("town.plots.sell-back", 0.5));
                    t.money.give(p, back);
                    say(p, "<gray>You sold plot " + here.id + " back to the town for " + Money.fmt(back) + ".");
                } else {
                    say(p, "<gray>You ended the lease on plot " + here.id + ".");
                }
                release(here);
            }
            case "trust", "untrust" -> {
                if (here == null || !p.getUniqueId().equals(here.owner)) return say(p, "<gray>Stand on a plot you own.");
                OfflinePlayer o = a.length > 1 ? Bukkit.getOfflinePlayer(a[1]) : null;
                if (o == null) return say(p, "<yellow>/plot " + sub + " <player>");
                if (sub.equals("trust")) here.trusted.add(o.getUniqueId());
                else here.trusted.remove(o.getUniqueId());
                save();
                say(p, "<green>" + o.getName() + (sub.equals("trust") ? " can now build here." : " can no longer build here."));
            }
            case "list" -> {
                String town = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : townAt(p.getLocation()) != null ? townAt(p.getLocation()) : "meadowbrook";
                List<Plot> list = inTown(town);
                if (list.isEmpty()) return say(p, "<gray>No plots in " + town + ".");
                say(p, "<gold><bold>Plots in " + cap(town) + "</bold> <gray>(stand on one and /plot buy or /plot rent)");
                for (Plot x : list) {
                    if (x.npc) continue;
                    String st = x.owner == null ? "<green>available <gray>" + Money.fmt(x.price) + " / " + Money.fmt(x.rent) + " a day"
                            : "<gray>taken by " + Bukkit.getOfflinePlayer(x.owner).getName();
                    say(p, " <white>" + x.id + " <gray>" + x.kind.name().toLowerCase() + (x.built ? " + building" : " bare") + " at " + (x.x0 + x.x1) / 2 + ", " + (x.z0 + x.z1) / 2 + " · " + st);
                }
            }
            case "mine" -> {
                List<Plot> mine = ownedBy(p.getUniqueId());
                if (mine.isEmpty()) return say(p, "<gray>You don't have any plots.");
                for (Plot x : mine) {
                    say(p, " <white>" + x.id + " <gray>in " + x.town + (x.rented ? " · rent " + Money.fmt(x.rent) + "/day, paid for "
                            + Math.max(0, (x.paidUntil - System.currentTimeMillis()) / 3_600_000) + "h" : " · owned"));
                }
            }
            default -> {
                if (here == null) return say(p, "<gray>You're not on a plot. <white>/plot list</white> · <white>/plot mine</white>");
                say(p, "<gold>Plot " + here.id + " <gray>(" + here.kind.name().toLowerCase() + ", " + (here.built ? "with building" : "bare") + ", "
                        + (here.x1 - here.x0 + 1) + "x" + (here.z1 - here.z0 + 1) + ")");
                if (here.npc) say(p, "<gray>Run by the town: " + here.label);
                else if (here.owner == null) {
                    p.sendMessage(Component.text().append(SmpPlugin.mm("<gray>Available: "))
                            .append(SmpPlugin.mm("<green>[Buy " + Money.fmt(here.price) + "]").clickEvent(ClickEvent.runCommand("/plot buy")))
                            .append(SmpPlugin.mm(" <yellow>[Rent " + Money.fmt(here.rent) + "/day]").clickEvent(ClickEvent.runCommand("/plot rent"))).build());
                } else say(p, "<gray>Owner: <white>" + Bukkit.getOfflinePlayer(here.owner).getName() + (here.rented ? " <gray>(renting)" : ""));
            }
        }
        return true;
    }

    static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("info", "buy", "rent", "leave", "trust", "untrust", "list", "mine")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            return null;
        }
        return out;
    }
}
