package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * Player gangs. Members share a gang bank (10% of every member's crime income goes in automatically), buy perks,
 * claim territory (chunks) that pays the bank while a member is online, set a hideout, and declare war on other
 * gangs: during a war, fighting and killing rival members isn't a crime, and a rival who holds one of your chunks
 * alone for a minute takes it. Gang levels (from XP) raise the territory limit.
 */
public final class Gangs implements Listener, TabExecutor {
    public enum Rank { MEMBER, OFFICER, LEADER }

    public static final class Gang {
        public String name, tag, color = "<gold>";
        public final Map<UUID, Rank> members = new LinkedHashMap<>();
        public double bank;
        public int xp, kills, deaths;
        public final Set<String> perks = new HashSet<>();
        public final Set<String> chunks = new HashSet<>();
        public final Set<String> wars = new HashSet<>();
        public Location hideout;

        public String label() {
            return color + "[" + tag + "]";
        }
    }

    public static final Map<String, Double> PERKS = new LinkedHashMap<>();
    static {
        PERKS.put("bribes", 15000.0);
        PERKS.put("safecracker", 20000.0);
        PERKS.put("fence", 10000.0);
        PERKS.put("lookouts", 20000.0);
        PERKS.put("crew", 10000.0);
    }
    private static final Map<String, String> PERK_TEXT = Map.of(
            "bribes", "+15% chance officers take your bribes",
            "safecracker", "Vaults and heist drills crack 30% faster",
            "fence", "The Fence pays 20% more",
            "lookouts", "15s warning before casino raids, wanted stars fade 25% faster",
            "crew", "+4 member slots");
    private static final int[] LEVEL_XP = {0, 1000, 3000, 7000, 15000};
    private static final String[] COLORS = {"<red>", "<gold>", "<yellow>", "<green>", "<aqua>", "<blue>", "<light_purple>", "<dark_red>", "<dark_aqua>"};

    private final TownManager t;
    private final File file;
    private final Map<String, Gang> gangs = new LinkedHashMap<>();
    private final Map<UUID, String> invites = new HashMap<>();
    private final Map<String, Long> peaceOffers = new HashMap<>();
    private final Map<String, Integer> capture = new HashMap<>();
    private final Map<UUID, String> lastChunkOwner = new HashMap<>();
    private int tick;

    Gangs(TownManager t) {
        this.t = t;
        this.file = new File(t.plugin().getDataFolder(), "gangs.yml");
        load();
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection gs = y.getConfigurationSection("gangs");
        if (gs == null) return;
        for (String id : gs.getKeys(false)) {
            ConfigurationSection s = gs.getConfigurationSection(id);
            Gang g = new Gang();
            g.name = s.getString("name", id);
            g.tag = s.getString("tag", "GNG");
            g.color = s.getString("color", "<gold>");
            g.bank = s.getDouble("bank");
            g.xp = s.getInt("xp");
            g.kills = s.getInt("kills");
            g.deaths = s.getInt("deaths");
            g.perks.addAll(s.getStringList("perks"));
            g.chunks.addAll(s.getStringList("chunks"));
            g.wars.addAll(s.getStringList("wars"));
            ConfigurationSection ms = s.getConfigurationSection("members");
            if (ms != null) for (String u : ms.getKeys(false)) g.members.put(UUID.fromString(u), Rank.valueOf(ms.getString(u, "MEMBER")));
            if (s.contains("hideout.world") && Bukkit.getWorld(s.getString("hideout.world")) != null) {
                g.hideout = new Location(Bukkit.getWorld(s.getString("hideout.world")), s.getDouble("hideout.x"), s.getDouble("hideout.y"), s.getDouble("hideout.z"));
            }
            gangs.put(id, g);
        }
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (var e : gangs.entrySet()) {
            Gang g = e.getValue();
            ConfigurationSection s = y.createSection("gangs." + e.getKey());
            s.set("name", g.name);
            s.set("tag", g.tag);
            s.set("color", g.color);
            s.set("bank", g.bank);
            s.set("xp", g.xp);
            s.set("kills", g.kills);
            s.set("deaths", g.deaths);
            s.set("perks", new ArrayList<>(g.perks));
            s.set("chunks", new ArrayList<>(g.chunks));
            s.set("wars", new ArrayList<>(g.wars));
            for (var m : g.members.entrySet()) s.set("members." + m.getKey(), m.getValue().name());
            if (g.hideout != null) {
                s.set("hideout.world", g.hideout.getWorld().getName());
                s.set("hideout.x", g.hideout.getX());
                s.set("hideout.y", g.hideout.getY());
                s.set("hideout.z", g.hideout.getZ());
            }
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            t.plugin().getLogger().warning("Could not save gangs.yml: " + ex.getMessage());
        }
    }

    private static String id(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public Gang of(UUID p) {
        for (Gang g : gangs.values()) if (g.members.containsKey(p)) return g;
        return null;
    }

    public Gang of(Player p) {
        return of(p.getUniqueId());
    }

    public Gang byName(String n) {
        if (n == null) return null;
        Gang g = gangs.get(id(n));
        if (g != null) return g;
        for (Gang x : gangs.values()) if (x.tag.equalsIgnoreCase(n)) return x;
        return null;
    }

    public boolean hasPerk(Player p, String perk) {
        Gang g = of(p);
        return g != null && g.perks.contains(perk);
    }

    public int level(Gang g) {
        int l = 1;
        for (int i = 0; i < LEVEL_XP.length; i++) if (g.xp >= LEVEL_XP[i]) l = i + 1;
        return l;
    }

    private int maxChunks(Gang g) {
        return 2 + 2 * level(g);
    }

    private int maxMembers(Gang g) {
        return cfg().getInt("town.gangs.max-members", 10) + (g.perks.contains("crew") ? 4 : 0);
    }

    static String chunkKey(Location l) {
        return l.getWorld().getName() + ";" + (l.getBlockX() >> 4) + ";" + (l.getBlockZ() >> 4);
    }

    public Gang owner(Location l) {
        String k = chunkKey(l);
        for (Gang g : gangs.values()) if (g.chunks.contains(k)) return g;
        return null;
    }

    public boolean atWar(Player a, Player b) {
        Gang ga = of(a), gb = of(b);
        return ga != null && gb != null && ga != gb && ga.wars.contains(id(gb.name));
    }

    // ------------------------------------------------------------------ hooks from the town

    /** A member earned money from crime: the gang takes its cut into the bank (the member keeps the rest). */
    public void crimeIncome(Player p, double amount, int xp) {
        Gang g = of(p);
        if (g == null) return;
        double cut = Math.floor(amount * cfg().getDouble("town.gangs.crime-tax", 0.1));
        if (cut > 0 && t.money.take(p, cut) > 0) {
            g.bank += cut;
            p.sendActionBar(SmpPlugin.mm(g.label() + " <gray>" + Money.fmt(cut) + " to the gang bank"));
        }
        g.xp += xp;
        save();
    }

    public void addXp(Player p, int xp) {
        Gang g = of(p);
        if (g == null) return;
        int before = level(g);
        g.xp += xp;
        if (level(g) > before) broadcast(g, "<gold>The gang reached level " + level(g) + "! <gray>(" + maxChunks(g) + " territory chunks)");
        save();
    }

    /** A player died: war kill accounting. */
    public void warKill(Player killer, Player victim) {
        Gang a = of(killer), b = of(victim);
        if (a == null || b == null || !a.wars.contains(id(b.name))) return;
        a.kills++;
        b.deaths++;
        a.xp += 25;
        broadcast(a, "<green>" + killer.getName() + " took down " + victim.getName() + " " + b.label() + "<green>! <gray>(war kills: " + a.kills + ")");
        broadcast(b, "<red>" + victim.getName() + " was taken down by " + killer.getName() + " " + a.label() + "<red>.");
        save();
    }

    public void broadcast(Gang g, String msg) {
        for (UUID u : g.members.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(SmpPlugin.mm(g.label() + " " + msg));
        }
    }

    /** Every second: territory income, turf captures. */
    void second() {
        tick++;
        long hour = cfg().getLong("town.gangs.income-minutes", 60) * 60;
        if (tick % hour == 0) {
            for (Gang g : gangs.values()) {
                boolean online = false;
                for (UUID u : g.members.keySet()) if (Bukkit.getPlayer(u) != null) online = true;
                if (!online || g.chunks.isEmpty()) continue;
                double pay = g.chunks.size() * cfg().getDouble("town.gangs.income-per-chunk", 25);
                g.bank += pay;
                g.xp += g.chunks.size();
                broadcast(g, "<gray>Turf income: <yellow>+" + Money.fmt(pay) + "</yellow> to the gang bank.");
            }
            save();
        }
        // Turf capture during wars.
        Map<String, Integer> seen = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Gang mine = of(p);
            if (mine == null || p.isDead()) continue;
            Gang owner = owner(p.getLocation());
            if (owner == null || owner == mine || !mine.wars.contains(id(owner.name))) continue;
            String k = chunkKey(p.getLocation());
            boolean defended = false;
            for (Player d : p.getWorld().getPlayers()) if (owner.members.containsKey(d.getUniqueId()) && chunkKey(d.getLocation()).equals(k)) defended = true;
            if (defended) continue;
            int n = capture.merge(k + "|" + id(mine.name), 1, Integer::sum);
            seen.put(k + "|" + id(mine.name), n);
            p.sendActionBar(SmpPlugin.mm("<red>Taking " + owner.label() + " <red>turf: <white>" + n + "/60s"));
            if (n >= 60) {
                owner.chunks.remove(k);
                mine.chunks.add(k);
                mine.xp += 100;
                broadcast(mine, "<green>We took a chunk of " + owner.label() + " <green>turf!");
                broadcast(owner, "<red>" + mine.label() + " <red>took one of our chunks!");
                t.news(mine.name + " took turf from " + owner.name + "!");
                save();
            }
        }
        capture.keySet().retainAll(seen.keySet());
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getBlockX() >> 4 == e.getTo().getBlockX() >> 4 && e.getFrom().getBlockZ() >> 4 == e.getTo().getBlockZ() >> 4) return;
        Gang g = owner(e.getTo());
        String now = g == null ? "" : g.name;
        String before = lastChunkOwner.getOrDefault(e.getPlayer().getUniqueId(), "");
        if (now.equals(before)) return;
        lastChunkOwner.put(e.getPlayer().getUniqueId(), now);
        if (g != null) e.getPlayer().sendActionBar(SmpPlugin.mm("<gray>Entering the turf of " + g.label() + " " + g.name));
        else e.getPlayer().sendActionBar(SmpPlugin.mm("<gray>Leaving gang turf"));
    }

    // ------------------------------------------------------------------ commands

    private static boolean say(CommandSender s, String m) {
        s.sendMessage(SmpPlugin.mm(m));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        if (!(sender instanceof Player p)) return say(sender, "Players only.");
        if (command.getName().equalsIgnoreCase("gc")) {
            Gang g = of(p);
            if (g == null) return say(p, "<gray>You're not in a gang.");
            if (a.length == 0) return say(p, "<yellow>/gc <message>");
            String msg = String.join(" ", a).replace("<", "‹");
            broadcast(g, "<white>" + p.getName() + "<gray>: <white>" + msg);
            return true;
        }
        String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
        Gang g = of(p);
        Rank rank = g == null ? null : g.members.get(p.getUniqueId());
        boolean boss = rank == Rank.LEADER || rank == Rank.OFFICER;
        switch (sub) {
            case "create" -> {
                if (g != null) return say(p, "<red>Leave your gang first.");
                if (a.length < 3) return say(p, "<yellow>/gang create <name> <TAG>  <gray>(costs " + Money.fmt(cfg().getDouble("town.gangs.create-cost", 5000)) + ")");
                String name = a[1], tag = a[2].toUpperCase(Locale.ROOT);
                if (!name.matches("[A-Za-z0-9_]{3,16}") || !tag.matches("[A-Z0-9]{2,4}")) return say(p, "<red>Name: 3-16 letters/numbers. Tag: 2-4 letters/numbers.");
                if (byName(name) != null || byName(tag) != null) return say(p, "<red>That name or tag is taken.");
                if (!t.money.pay(p, cfg().getDouble("town.gangs.create-cost", 5000))) return say(p, "<red>Starting a gang costs " + Money.fmt(cfg().getDouble("town.gangs.create-cost", 5000)) + ".");
                Gang n = new Gang();
                n.name = name;
                n.tag = tag;
                n.color = COLORS[Math.abs(name.hashCode()) % COLORS.length];
                n.members.put(p.getUniqueId(), Rank.LEADER);
                gangs.put(id(name), n);
                save();
                for (Player o : Bukkit.getOnlinePlayers()) o.sendMessage(SmpPlugin.mm("<gray>A new gang rises: " + n.label() + " " + name + " <gray>led by " + p.getName() + "."));
                t.news("A new gang, " + name + ", was founded by " + p.getName() + ".");
            }
            case "invite" -> {
                if (!boss) return say(p, "<red>Only the leader and officers can invite.");
                Player o = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (o == null) return say(p, "<yellow>/gang invite <player>");
                if (of(o) != null) return say(p, "<red>They're already in a gang.");
                if (g.members.size() >= maxMembers(g)) return say(p, "<red>The gang is full (" + maxMembers(g) + ").");
                invites.put(o.getUniqueId(), id(g.name));
                say(p, "<green>Invited " + o.getName() + ".");
                o.sendMessage(Component.text().append(SmpPlugin.mm(g.label() + " <yellow>" + p.getName() + " invites you to join " + g.name + ". "))
                        .append(SmpPlugin.mm("<green><bold>[Join]").clickEvent(ClickEvent.runCommand("/gang join " + g.name))).build());
            }
            case "join" -> {
                if (g != null) return say(p, "<red>Leave your gang first.");
                Gang target = a.length > 1 ? byName(a[1]) : null;
                if (target == null || !id(target.name).equals(invites.get(p.getUniqueId()))) return say(p, "<red>You need an invite.");
                if (target.members.size() >= maxMembers(target)) return say(p, "<red>That gang is full.");
                invites.remove(p.getUniqueId());
                target.members.put(p.getUniqueId(), Rank.MEMBER);
                save();
                broadcast(target, "<green>" + p.getName() + " joined the gang!");
            }
            case "leave" -> {
                if (g == null) return say(p, "<gray>You're not in a gang.");
                if (rank == Rank.LEADER && g.members.size() > 1) return say(p, "<red>Promote someone to leader first (/gang leader <player>), or /gang disband.");
                g.members.remove(p.getUniqueId());
                if (g.members.isEmpty()) gangs.remove(id(g.name));
                save();
                say(p, "<gray>You left " + g.name + ".");
                broadcast(g, "<gray>" + p.getName() + " left the gang.");
            }
            case "kick" -> {
                if (!boss) return say(p, "<red>Only the leader and officers can kick.");
                OfflinePlayer o = a.length > 1 ? Bukkit.getOfflinePlayer(a[1]) : null;
                if (o == null || !g.members.containsKey(o.getUniqueId()) || g.members.get(o.getUniqueId()) == Rank.LEADER) return say(p, "<yellow>/gang kick <member>");
                g.members.remove(o.getUniqueId());
                save();
                broadcast(g, "<gray>" + o.getName() + " was kicked from the gang.");
                if (o.getPlayer() != null) o.getPlayer().sendMessage(SmpPlugin.mm("<red>You were kicked from " + g.name + "."));
            }
            case "promote", "demote", "leader" -> {
                if (rank != Rank.LEADER) return say(p, "<red>Only the leader can do that.");
                OfflinePlayer o = a.length > 1 ? Bukkit.getOfflinePlayer(a[1]) : null;
                if (o == null || !g.members.containsKey(o.getUniqueId()) || o.getUniqueId().equals(p.getUniqueId())) return say(p, "<yellow>/gang " + sub + " <member>");
                if (sub.equals("leader")) {
                    g.members.put(o.getUniqueId(), Rank.LEADER);
                    g.members.put(p.getUniqueId(), Rank.OFFICER);
                } else {
                    g.members.put(o.getUniqueId(), sub.equals("promote") ? Rank.OFFICER : Rank.MEMBER);
                }
                save();
                broadcast(g, "<gray>" + o.getName() + " is now " + g.members.get(o.getUniqueId()).name().toLowerCase() + ".");
            }
            case "disband" -> {
                if (rank != Rank.LEADER) return say(p, "<red>Only the leader can disband.");
                broadcast(g, "<red>The gang has been disbanded. The bank (" + Money.fmt(g.bank) + ") goes to the leader.");
                t.money.give(p, g.bank);
                gangs.remove(id(g.name));
                for (Gang o : gangs.values()) o.wars.remove(id(g.name));
                save();
            }
            case "deposit" -> {
                if (g == null || a.length < 2) return say(p, "<yellow>/gang deposit <amount>");
                double n = num(a[1]);
                if (n <= 0 || !t.money.pay(p, n)) return say(p, "<red>You can't deposit that.");
                g.bank += n;
                save();
                broadcast(g, "<gray>" + p.getName() + " deposited " + Money.fmt(n) + ". Bank: " + Money.fmt(g.bank));
            }
            case "withdraw" -> {
                if (!boss || a.length < 2) return say(p, "<red>Only the leader and officers can withdraw: /gang withdraw <amount>");
                double n = num(a[1]);
                if (n <= 0 || n > g.bank) return say(p, "<red>The bank has " + Money.fmt(g.bank) + ".");
                g.bank -= n;
                t.money.give(p, n);
                save();
                broadcast(g, "<gray>" + p.getName() + " withdrew " + Money.fmt(n) + ". Bank: " + Money.fmt(g.bank));
            }
            case "perks" -> {
                if (g == null) return say(p, "<gray>You're not in a gang.");
                say(p, g.label() + " <gold>Perks <gray>(bank " + Money.fmt(g.bank) + ")");
                for (var e : PERKS.entrySet()) {
                    boolean has = g.perks.contains(e.getKey());
                    p.sendMessage(Component.text().append(SmpPlugin.mm((has ? " <green>✔ " : " <gray>• ") + "<white>" + e.getKey() + " <gray>" + PERK_TEXT.get(e.getKey()) + " "))
                            .append(has ? Component.empty() : SmpPlugin.mm("<gold>[Buy " + Money.fmt(e.getValue()) + "]").clickEvent(ClickEvent.runCommand("/gang buy " + e.getKey()))).build());
                }
            }
            case "buy" -> {
                if (!boss) return say(p, "<red>Only the leader and officers can spend the bank.");
                String perk = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
                Double cost = PERKS.get(perk);
                if (cost == null) return say(p, "<red>Perks: " + String.join(", ", PERKS.keySet()));
                if (g.perks.contains(perk)) return say(p, "<gray>You already have that.");
                if (g.bank < cost) return say(p, "<red>The bank needs " + Money.fmt(cost) + " (has " + Money.fmt(g.bank) + ").");
                g.bank -= cost;
                g.perks.add(perk);
                save();
                broadcast(g, "<gold>New perk: <white>" + perk + "</white> <gray>(" + PERK_TEXT.get(perk) + ")");
            }
            case "claim" -> {
                if (!boss) return say(p, "<red>Only the leader and officers can claim turf.");
                Location l = p.getLocation();
                double dx = l.getX() - t.cx, dz = l.getZ() - t.cz;
                if (l.getWorld().getName().equals(t.worldName) && Math.sqrt(dx * dx + dz * dz) < cfg().getDouble("town.gangs.no-claim-radius", 200)) {
                    return say(p, "<red>Too close to spawn: turf must be at least " + (int) cfg().getDouble("town.gangs.no-claim-radius", 200) + " blocks from the fountain.");
                }
                if (t.nearOtherTown(l, 40)) return say(p, "<red>Too close to a town. Turf has to be out in the wilds.");
                if (owner(l) != null) return say(p, "<red>This chunk belongs to " + owner(l).label() + "<red>. Take it in a war.");
                if (g.chunks.size() >= maxChunks(g)) return say(p, "<red>Level " + level(g) + " gangs can hold " + maxChunks(g) + " chunks.");
                double cost = cfg().getDouble("town.gangs.claim-cost", 1000);
                if (g.bank < cost) return say(p, "<red>Claiming costs " + Money.fmt(cost) + " from the gang bank.");
                g.bank -= cost;
                g.chunks.add(chunkKey(l));
                save();
                broadcast(g, "<green>" + p.getName() + " claimed turf at " + l.getBlockX() + ", " + l.getBlockZ() + ". <gray>(" + g.chunks.size() + "/" + maxChunks(g) + ")");
            }
            case "unclaim" -> {
                if (!boss) return say(p, "<red>Only the leader and officers can do that.");
                if (!g.chunks.remove(chunkKey(p.getLocation()))) return say(p, "<gray>This chunk isn't yours.");
                save();
                say(p, "<gray>Chunk released.");
            }
            case "sethideout" -> {
                if (!boss) return say(p, "<red>Only the leader and officers can do that.");
                if (owner(p.getLocation()) != g) return say(p, "<red>The hideout has to be on your own turf.");
                g.hideout = p.getLocation().clone();
                save();
                broadcast(g, "<green>Hideout set. <gray>/gang hideout to go there.");
            }
            case "hideout", "home" -> {
                if (g == null || g.hideout == null) return say(p, "<gray>Your gang has no hideout.");
                TownData d = t.data(p);
                if (d.stars > 0 || d.jailed() || d.inService()) return say(p, "<red>Not while the law is after you.");
                long wait = 300_000 - (System.currentTimeMillis() - d.lastGangHome);
                if (wait > 0) return say(p, "<gray>Wait " + (wait / 1000) + "s.");
                if (owner(g.hideout) != g) return say(p, "<red>You lost the hideout's turf.");
                d.lastGangHome = System.currentTimeMillis();
                p.teleport(g.hideout);
            }
            case "war" -> {
                if (!boss) return say(p, "<red>Only the leader and officers can declare war.");
                Gang o = a.length > 1 ? byName(a[1]) : null;
                if (o == null || o == g) return say(p, "<yellow>/gang war <gang>");
                if (g.wars.contains(id(o.name))) return say(p, "<gray>You're already at war with them.");
                double cost = cfg().getDouble("town.gangs.war-cost", 2000);
                if (g.bank < cost) return say(p, "<red>Declaring war costs " + Money.fmt(cost) + " from the gang bank.");
                g.bank -= cost;
                g.wars.add(id(o.name));
                o.wars.add(id(g.name));
                save();
                for (Player x : Bukkit.getOnlinePlayers()) x.sendMessage(SmpPlugin.mm("<dark_red><bold>⚔ GANG WAR:</bold> " + g.label() + " " + g.name + " <red>declared war on " + o.label() + " " + o.name + "<red>!"));
                t.news(g.name + " declared war on " + o.name + "!");
            }
            case "peace" -> {
                if (!boss) return say(p, "<red>Only the leader and officers can offer peace.");
                Gang o = a.length > 1 ? byName(a[1]) : null;
                if (o == null || !g.wars.contains(id(o.name))) return say(p, "<yellow>/gang peace <gang you're at war with>");
                String theirs = id(o.name) + ">" + id(g.name);
                Long offered = peaceOffers.get(theirs);
                if (offered != null && System.currentTimeMillis() - offered < 300_000) {
                    g.wars.remove(id(o.name));
                    o.wars.remove(id(g.name));
                    peaceOffers.remove(theirs);
                    save();
                    for (Player x : Bukkit.getOnlinePlayers()) x.sendMessage(SmpPlugin.mm("<green>Peace: " + g.label() + " <green>and " + o.label() + " <green>ended their war."));
                } else {
                    peaceOffers.put(id(g.name) + ">" + id(o.name), System.currentTimeMillis());
                    broadcast(g, "<gray>Peace offered to " + o.label() + "<gray>.");
                    broadcast(o, "<yellow>" + g.label() + " <yellow>offers peace. <gray>/gang peace " + g.name + " within 5 minutes to accept.");
                }
            }
            case "list", "top" -> {
                List<Gang> all = new ArrayList<>(gangs.values());
                all.sort((x, y) -> y.xp - x.xp);
                say(p, "<gold><bold>Gangs</bold>");
                for (Gang x : all) {
                    say(p, " " + x.label() + " <white>" + x.name + " <gray>lvl " + level(x) + " · " + x.members.size() + " members · " + x.chunks.size()
                            + " turf · " + x.kills + " war kills" + (x.wars.isEmpty() ? "" : " <red>at war"));
                }
                for (String npc : t.npcGangs().ids()) say(p, " " + t.npcGangs().status(npc));
            }
            default -> {
                Gang x = a.length > 1 ? byName(a[1]) : g;
                if (x == null) {
                    say(p, "<gray>You're not in a gang. <yellow>/gang create <name> <TAG></yellow> or ask for an invite.");
                    return say(p, "<gray>/gang list · invite · join · leave · deposit · perks · claim · hideout · war · peace · /gc <message>");
                }
                say(p, x.label() + " <white><bold>" + x.name + "</bold> <gray>level " + level(x) + " (" + x.xp + " xp)");
                List<String> names = new ArrayList<>();
                for (var m : x.members.entrySet()) {
                    OfflinePlayer o = Bukkit.getOfflinePlayer(m.getKey());
                    names.add((m.getValue() == Rank.LEADER ? "★" : m.getValue() == Rank.OFFICER ? "☆" : "") + o.getName());
                }
                say(p, " <gray>Members: <white>" + String.join(", ", names));
                say(p, " <gray>Bank: <gold>" + Money.fmt(x.bank) + "</gold> · Turf: <white>" + x.chunks.size() + "/" + maxChunks(x) + "</white> · Perks: <white>"
                        + (x.perks.isEmpty() ? "none" : String.join(", ", x.perks)));
                if (!x.wars.isEmpty()) say(p, " <red>At war with: " + String.join(", ", x.wars) + " <gray>(kills " + x.kills + ", deaths " + x.deaths + ")");
            }
        }
        return true;
    }

    private static double num(String s) {
        try {
            return Math.floor(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("gc")) return out;
        if (args.length == 1) {
            for (String s : List.of("info", "create", "invite", "join", "leave", "kick", "promote", "demote", "leader", "disband", "deposit", "withdraw",
                    "perks", "buy", "claim", "unclaim", "sethideout", "hideout", "war", "peace", "list")) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && List.of("war", "peace", "join", "info").contains(args[0].toLowerCase())) {
            for (Gang g : gangs.values()) out.add(g.name);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            out.addAll(PERKS.keySet());
        } else if (args.length == 2) {
            return null;
        }
        return out;
    }
}
