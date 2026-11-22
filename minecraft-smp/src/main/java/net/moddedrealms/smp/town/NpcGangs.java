package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Two rival NPC gangs, the Crimson Vipers and the Iron Jackals, roam the wilds in groups. They fight each other on
 * sight and attack players who aren't their friends. Taking down a member lowers your standing with that gang and
 * raises it with their rivals; at 50 you're an ally: they leave you alone and come to your aid when someone attacks
 * you. Each gang has a strength: bring it to zero and the gang is wiped out for a couple of days, and whoever took down
 * the most members gets a big reward.
 */
public final class NpcGangs implements Listener {
    record Def(String id, String name, String text, String color, String rival, String[] names) {}

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();
    static {
        DEFS.put("vipers", new Def("vipers", "Crimson Vipers", "<red>", "#8B1A1A", "jackals",
                new String[]{"Fang", "Scales", "Red", "Hiss", "Venom", "Coil", "Ruby", "Sly"}));
        DEFS.put("jackals", new Def("jackals", "Iron Jackals", "<gray>", "#4A4A4A", "vipers",
                new String[]{"Rust", "Howl", "Grit", "Bones", "Scrap", "Flint", "Mange", "Spike"}));
    }

    private final TownManager t;
    private final File file;
    private final Random r = new Random();
    private final Map<String, Integer> strength = new HashMap<>();
    private final Map<String, Long> wipedUntil = new HashMap<>();
    private final Map<String, Map<UUID, Integer>> kills = new HashMap<>();

    NpcGangs(TownManager t) {
        this.t = t;
        this.file = new File(t.plugin().getDataFolder(), "npc-gangs.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String id : DEFS.keySet()) {
            strength.put(id, y.getInt(id + ".strength", fullStrength()));
            wipedUntil.put(id, y.getLong(id + ".wiped-until"));
            Map<UUID, Integer> k = new HashMap<>();
            var ks = y.getConfigurationSection(id + ".kills");
            if (ks != null) for (String u : ks.getKeys(false)) k.put(UUID.fromString(u), ks.getInt(u));
            kills.put(id, k);
        }
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    private int fullStrength() {
        return cfg().getInt("town.npc-gangs.strength", 25);
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (String id : DEFS.keySet()) {
            y.set(id + ".strength", strength.get(id));
            y.set(id + ".wiped-until", wipedUntil.get(id));
            for (var e : kills.get(id).entrySet()) y.set(id + ".kills." + e.getKey(), e.getValue());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            t.plugin().getLogger().warning("Could not save npc-gangs.yml: " + e.getMessage());
        }
    }

    public List<String> ids() {
        return new ArrayList<>(DEFS.keySet());
    }

    private boolean active(String id) {
        long until = wipedUntil.getOrDefault(id, 0L);
        if (until != 0 && System.currentTimeMillis() >= until) {
            wipedUntil.put(id, 0L);
            strength.put(id, fullStrength());
            kills.get(id).clear();
            save();
            Def d = DEFS.get(id);
            for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(SmpPlugin.mm(d.text() + "The " + d.name() + " are back, with new blood and a grudge."));
        }
        return wipedUntil.getOrDefault(id, 0L) == 0;
    }

    public String status(String id) {
        Def d = DEFS.get(id);
        long until = wipedUntil.getOrDefault(id, 0L);
        String s = until > System.currentTimeMillis() ? "<dark_gray>wiped out, back in " + (until - System.currentTimeMillis()) / 3_600_000 + "h"
                : "<gray>strength " + strength.get(id) + "/" + fullStrength();
        String[] w = d.name().split(" ");
        return d.text() + "[" + w[0].charAt(0) + w[1].charAt(0) + "] " + d.name() + " <dark_gray>(NPC) " + s;
    }

    public int rep(Player p, String gang) {
        return t.data(p).npcGangRep.getOrDefault(gang, 0);
    }

    public boolean ally(Player p, String gang) {
        return rep(p, gang) >= cfg().getInt("town.npc-gangs.ally-at", 50);
    }

    private void changeRep(Player p, String gang, int delta) {
        TownData d = t.data(p);
        boolean was = ally(p, gang);
        d.npcGangRep.merge(gang, delta, Integer::sum);
        d.npcGangRep.put(gang, Math.max(-200, Math.min(200, d.npcGangRep.get(gang))));
        t.markDirty();
        Def def = DEFS.get(gang);
        if (!was && ally(p, gang)) p.sendMessage(SmpPlugin.mm(def.text() + "The " + def.name() + " count you as a friend now. <gray>They won't attack you, and they'll back you up."));
        if (was && !ally(p, gang)) p.sendMessage(SmpPlugin.mm(def.text() + "The " + def.name() + " don't trust you any more."));
    }

    public String standings(Player p) {
        List<String> out = new ArrayList<>();
        for (Def d : DEFS.values()) {
            int r = rep(p, d.id());
            out.add(d.text() + d.name() + " <gray>" + (ally(p, d.id()) ? "<green>ally" : r <= -50 ? "<red>enemy" : "neutral") + " <dark_gray>(" + r + ")");
        }
        return String.join("<gray>, ", out);
    }

    // ------------------------------------------------------------------ spawning

    void spawnTick() {
        World w = t.world();
        if (w == null || !cfg().getBoolean("town.npc-gangs.enabled", true) || !t.canSpawnWalkers(w)) return;
        int max = cfg().getInt("town.npc-gangs.max", 12);
        int total = t.walkers(Walker.Role.GANGSTER).size();
        double min = cfg().getDouble("town.npc-gangs.min-distance", 220), far = cfg().getDouble("town.npc-gangs.max-distance", 1500);
        for (Player p : w.getPlayers()) {
            if (total >= max || t.exempt(p)) continue;
            double dx = p.getLocation().getX() - t.cx, dz = p.getLocation().getZ() - t.cz, d = Math.sqrt(dx * dx + dz * dz);
            if (d < min || d > far || r.nextDouble() > cfg().getDouble("town.npc-gangs.chance", 0.2)) continue;
            boolean near = false;
            for (Walker g : t.walkers(Walker.Role.GANGSTER)) if (g.dist(p.getLocation()) < 90) near = true;
            if (near) continue;
            List<String> live = new ArrayList<>();
            for (String id : DEFS.keySet()) if (active(id)) live.add(id);
            if (live.isEmpty()) continue;
            String gang = live.get(r.nextInt(live.size()));
            Location at = t.spawnSpot(p, 28, 42);
            if (at == null) continue;
            int n = 3 + r.nextInt(2);
            for (int i = 0; i < n && total < max; i++) {
                Location spot = t.surface(w, at.getBlockX() + r.nextInt(5) - 2, at.getBlockZ() + r.nextInt(5) - 2, at.getY(), 3);
                spawn(spot != null ? spot : at, gang);
                total++;
            }
            // Sometimes the rivals are nearby too.
            String rival = DEFS.get(gang).rival();
            if (active(rival) && r.nextInt(100) < 35 && total + 3 <= max) {
                Location ra = t.spawnSpot(p, 30, 44);
                if (ra != null) for (int i = 0; i < 3; i++) {
                    spawn(ra, rival);
                    total++;
                }
            }
        }
    }

    public Walker spawn(Location at, String gang) {
        Def d = DEFS.get(gang);
        Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        gear.put(EquipmentSlot.HEAD, Walker.dyed(Material.LEATHER_HELMET, d.color()));
        gear.put(EquipmentSlot.CHEST, Walker.dyed(Material.LEATHER_CHESTPLATE, d.color()));
        gear.put(EquipmentSlot.LEGS, Walker.dyed(Material.LEATHER_LEGGINGS, "#1A1A1A"));
        gear.put(EquipmentSlot.FEET, Walker.dyed(Material.LEATHER_BOOTS, d.color()));
        gear.put(EquipmentSlot.HAND, new ItemStack(r.nextBoolean() ? Material.IRON_AXE : Material.IRON_SWORD));
        Walker w = Walker.spawn(t.key, at, Walker.Role.GANGSTER, d.text() + d.names()[r.nextInt(d.names().length)], d.text() + d.name(),
                0.26, cfg().getDouble("town.npc-gangs.health", 30), gear);
        w.gang = gang;
        w.nextWander = System.currentTimeMillis() + 2000;
        return t.register(w);
    }

    // ------------------------------------------------------------------ brain

    void think() {
        long now = System.currentTimeMillis();
        for (Walker g : new ArrayList<>(t.walkers(Walker.Role.GANGSTER))) {
            if (!g.valid()) continue;
            if (t.nearestPlayer(g.loc(), 100) == null || !active(g.gang)) {
                t.unregister(g, false);
                continue;
            }
            Walker foe = null;
            if (g.foe != null) for (Walker x : t.walkers()) if (x.legs.getUniqueId().equals(g.foe) && x.valid()) foe = x;
            if (foe == null) {
                for (Walker x : t.walkers(Walker.Role.GANGSTER)) {
                    if (x.gang.equals(DEFS.get(g.gang).rival()) && x.valid() && g.dist(x.loc()) < 18 && g.legs.hasLineOfSight(x.body)) {
                        foe = x;
                        g.foe = x.legs.getUniqueId();
                        break;
                    }
                }
            }
            if (foe != null) {
                if (g.dist(foe.loc()) > 24) {
                    g.foe = null;
                    continue;
                }
                fight(g, foe, now);
                continue;
            }
            Player target = g.target == null ? null : Bukkit.getPlayer(g.target);
            if (target != null && (target.getWorld() != g.world() || target.isDead() || g.dist(target.getLocation()) > 30 || t.exempt(target))) {
                g.target = null;
                target = null;
            }
            if (target == null) {
                for (Player p : g.world().getPlayers()) {
                    if (g.dist(p.getLocation()) < 13 && !t.exempt(p) && !ally(p, g.gang) && g.sees(p)) {
                        target = p;
                        g.target = p.getUniqueId();
                        g.say(p, rep(p, g.gang) <= -50 ? "It's that one! Get 'em!" : "Wrong part of town, friend.");
                        break;
                    }
                }
            }
            if (target != null) {
                double d = g.dist(target.getLocation());
                if (d > 2.2) g.moveTo(target, 1.3);
                if (d < 2.9 && now - g.lastAttack > 1000 && g.sees(target)) {
                    g.lastAttack = now;
                    g.lookAt(target.getEyeLocation());
                    g.swing();
                    target.damage(cfg().getDouble("town.npc-gangs.damage", 3.5), g.body);
                }
                continue;
            }
            if (!g.moving() && now > g.nextWander) {
                Location base = g.loc();
                double a = r.nextDouble() * Math.PI * 2, dist = 4 + r.nextDouble() * 8;
                Location goal = t.surface(base.getWorld(), (int) Math.floor(base.getX() + Math.cos(a) * dist), (int) Math.floor(base.getZ() + Math.sin(a) * dist), base.getY(), 4);
                if (goal != null) g.moveTo(goal, 0.6);
                g.nextWander = now + 5000 + r.nextInt(8000);
            }
        }
    }

    private void fight(Walker g, Walker foe, long now) {
        double d = g.dist(foe.loc());
        if (d > 2.2) g.moveTo(foe.legs, 1.35);
        if (d < 3 && now - g.lastAttack > 1000) {
            g.lastAttack = now;
            g.lookAt(foe.body.getEyeLocation());
            g.swing();
            if (foe.role == Walker.Role.GANGSTER) hitByWalker(foe, g, cfg().getDouble("town.npc-gangs.damage", 3.5) + 1);
            else if (foe.role == Walker.Role.BANDIT) t.bandits().hitByWalker(foe, g, 4);
            else if (foe.role == Walker.Role.OFFICER) {
                foe.health -= 4;
                foe.hurt();
                if (foe.health <= 0) t.unregister(foe, true);
            }
        }
    }

    // ------------------------------------------------------------------ damage

    void hit(Walker g, Player p, double dmg) {
        if (ally(p, g.gang)) {
            g.say(p, "Hey! Whose side are you on?");
            changeRep(p, g.gang, -20);
        }
        g.target = p.getUniqueId();
        g.foe = null;
        hurt(g, dmg, p);
    }

    void hitByWalker(Walker g, Walker by, double dmg) {
        if (g.foe == null) g.foe = by.legs.getUniqueId();
        hurt(g, dmg, null);
    }

    private void hurt(Walker g, double dmg, Player by) {
        g.health -= dmg;
        g.hurt();
        if (g.health > 0) return;
        g.shout("<gray>Ugh... tell the boss...", 16);
        String id = g.gang;
        Def d = DEFS.get(id);
        t.unregister(g, true);
        int left = Math.max(0, strength.get(id) - 1);
        strength.put(id, left);
        if (by != null) {
            changeRep(by, id, -10);
            changeRep(by, d.rival(), 5);
            double bounty = cfg().getDouble("town.npc-gangs.bounty", 100);
            t.money.give(by, bounty);
            by.sendActionBar(SmpPlugin.mm(d.text() + d.name() + " <gray>member down: <gold>+" + Money.fmt(bounty) + " <gray>(" + left + " left)"));
            kills.get(id).merge(by.getUniqueId(), 1, Integer::sum);
            t.gangs().addXp(by, 5);
        }
        if (left == 0) wipe(id);
        save();
    }

    private void wipe(String id) {
        Def d = DEFS.get(id);
        wipedUntil.put(id, System.currentTimeMillis() + cfg().getLong("town.npc-gangs.respawn-hours", 48) * 3_600_000L);
        UUID top = null;
        int best = 0;
        for (var e : kills.get(id).entrySet()) if (e.getValue() > best) {
            best = e.getValue();
            top = e.getKey();
        }
        String hero = "";
        if (top != null) {
            OfflinePlayer o = Bukkit.getOfflinePlayer(top);
            double reward = cfg().getDouble("town.npc-gangs.wipe-reward", 5000);
            t.money.give(o, reward);
            hero = " " + o.getName() + " took down " + best + " of them and collects " + Money.fmt(reward) + ".";
        }
        for (Player p : Bukkit.getOnlinePlayers()) p.sendMessage(SmpPlugin.mm(d.text() + "<bold>The " + d.name() + " have been wiped out!</bold><gray>" + hero));
        t.news("The " + d.name() + " were wiped out." + hero);
        for (Walker g : new ArrayList<>(t.walkers(Walker.Role.GANGSTER))) if (id.equals(g.gang)) t.unregister(g, true);
    }

    /** Allies come to your aid when another player attacks you. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = e.getDamager() instanceof Player p ? p : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player s ? s : null;
        if (attacker == null || attacker == victim) return;
        for (Walker g : t.walkers(Walker.Role.GANGSTER)) {
            if (g.target == null && g.foe == null && ally(victim, g.gang) && !ally(attacker, g.gang) && g.dist(victim.getLocation()) < 16) {
                g.target = attacker.getUniqueId();
                g.say(attacker, "Leave our friend alone!");
            }
        }
    }

    public String reset(String id) {
        if (!DEFS.containsKey(id)) return "NPC gangs: " + String.join(", ", DEFS.keySet());
        strength.put(id, fullStrength());
        wipedUntil.put(id, 0L);
        kills.get(id).clear();
        save();
        return DEFS.get(id).name() + " reset to full strength.";
    }
}
