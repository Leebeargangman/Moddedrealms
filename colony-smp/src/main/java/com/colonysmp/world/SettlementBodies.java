package com.colonysmp.world;

import com.colonysmp.ColonySMP;
import com.colonysmp.civ.Civ;
import com.colonysmp.civ.CivSim;
import com.colonysmp.civ.Prices;
import com.colonysmp.civ.Settlement;
import com.colonysmp.civ.TradeGoods;
import com.colonysmp.npc.Mover;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The few villagers that stand for a settlement's people while a player is near: a merchant at the market and a
 * handful of residents who walk between the houses and the market by day and go indoors at night. They are only
 * bodies. The settlement's hundreds of people live in its books; these exist while someone is within sight,
 * never tick an AI of their own, are capped across the whole server, and are never saved with the world.
 */
public final class SettlementBodies {

    private static final class Body {
        final Villager v;
        final String settlement;
        final boolean merchant;
        final Mover mover;
        long nextMoveAt;

        Body(Villager v, String settlement, boolean merchant) {
            this.v = v;
            this.settlement = settlement;
            this.merchant = merchant;
            this.mover = new Mover(v);
        }
    }

    private final ColonySMP plugin;
    private final CivManager civs;
    private final Map<String, List<Body>> bodies = new HashMap<>();

    SettlementBodies(ColonySMP plugin, CivManager civs) {
        this.plugin = plugin;
        this.civs = civs;
    }

    public int active() {
        int n = 0;
        for (List<Body> l : bodies.values()) n += l.size();
        return n;
    }

    public static String settlementOf(Entity e) {
        String tag = e.getPersistentDataContainer().get(Keys.CIV_NPC, PersistentDataType.STRING);
        return tag == null ? null : tag.substring(0, tag.indexOf(':') < 0 ? tag.length() : tag.indexOf(':'));
    }

    public static boolean merchant(Entity e) {
        String tag = e.getPersistentDataContainer().get(Keys.CIV_NPC, PersistentDataType.STRING);
        return tag != null && tag.endsWith(":merchant");
    }

    /** Every second: who should be standing where. */
    public void tick() {
        var cfg = plugin.settings().civs;
        int budget = cfg.maxActiveNpcs;
        for (Settlement s : civs.world().settlements.values()) {
            World w = Bukkit.getWorld(s.world);
            boolean want = false;
            if (w != null && s.alive() && s.built && w.isChunkLoaded(s.marketX >> 4, s.marketZ >> 4)) {
                double r = cfg.activationDistance;
                for (Player p : w.getPlayers()) if (CivManager.dist2(p.getLocation(), s) < r * r) want = true;
            }
            List<Body> have = bodies.get(s.id);
            if (!want) {
                if (have != null) despawn(s);
                continue;
            }
            if (have == null) bodies.put(s.id, have = new ArrayList<>());
            have.removeIf(b -> !b.v.isValid());
            boolean night = w.getTime() > 13000 && w.getTime() < 23000;
            int residents = night || s.has("plague", civs.world().day) ? 0 : Math.min(cfg.residents, (int) (s.population / 10));
            boolean hasMerchant = false;
            int walking = 0;
            for (Body b : new ArrayList<>(have)) {
                if (b.merchant) hasMerchant = true;
                else if (++walking > residents) {
                    b.mover.closeAll();
                    b.v.remove();
                    have.remove(b);
                }
            }
            budget -= have.size();
            if (!hasMerchant && budget > 0) {
                Body b = spawn(w, s, true, new Location(w, s.marketX + 0.5, s.marketY, s.marketZ + 0.5));
                if (b != null) {
                    have.add(b);
                    budget--;
                }
            }
            while (walking < residents && budget > 0) {
                Location at = spot(w, s);
                Body b = at == null ? null : spawn(w, s, false, at);
                if (b == null) break;
                have.add(b);
                walking++;
                budget--;
            }
        }
    }

    /** Every tick: residents walk. */
    public void move(long tick) {
        for (List<Body> l : bodies.values()) {
            for (Body b : l) {
                if (b.merchant || !b.v.isValid()) continue;
                if (!b.mover.moving() && tick >= b.nextMoveAt) {
                    Settlement s = civs.world().settlements.get(b.settlement);
                    Location to = s == null ? null : spot(b.v.getWorld(), s);
                    if (to != null) b.mover.moveTo(to, plugin.settings().walkSpeed * 0.7, 1.5);
                    b.nextMoveAt = tick + 200 + ThreadLocalRandom.current().nextInt(400);
                }
                b.mover.tick(plugin.npcs(), tick);
            }
        }
    }

    /** Somewhere a resident might be going: the market, or the door of one of the buildings. */
    private Location spot(World w, Settlement s) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            int x, z;
            if (s.structures.isEmpty() || r.nextInt(3) == 0) {
                x = s.marketX + r.nextInt(-4, 5);
                z = s.marketZ + r.nextInt(-4, 5);
            } else {
                int[] a = SettlementBuilder.parse(s.structures.get(r.nextInt(s.structures.size())));
                if (a == null) continue;
                // just outside the building, on the side facing the market
                x = s.marketX > (a[0] + a[3]) / 2 ? a[3] + 1 : a[0] - 1;
                z = (a[2] + a[5]) / 2;
            }
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Location l = Mover.safeSpot(new Location(w, x + 0.5, SettlementBuilder.ground(w, x, z) + 1, z + 0.5));
            if (l != null) return l;
        }
        return null;
    }

    private Body spawn(World w, Settlement s, boolean merchant, Location at) {
        Civ civ = civs.world().civs.get(s.civ);
        try {
            Villager v = w.spawn(at, Villager.class, CreatureSpawnEvent.SpawnReason.CUSTOM, vil -> {
                vil.setPersistent(false);
                vil.setRemoveWhenFarAway(false);
                vil.setAware(false);
                vil.setCollidable(false);
                vil.setInvulnerable(true);
                vil.setSilent(!merchant);
                vil.setProfession(merchant ? Villager.Profession.CARTOGRAPHER : switch (s.type) {
                    case FARMING_VILLAGE, ISOLATED_COMMUNITY -> Villager.Profession.FARMER;
                    case MINING_SETTLEMENT, INDUSTRIAL_TOWN -> Villager.Profession.TOOLSMITH;
                    case FISHING_VILLAGE, COASTAL_SETTLEMENT -> Villager.Profession.FISHERMAN;
                    case MILITARY_SETTLEMENT, FORTIFIED_KINGDOM -> Villager.Profession.WEAPONSMITH;
                    case RELIGIOUS_SETTLEMENT -> Villager.Profession.CLERIC;
                    case BANDIT_CAMP -> Villager.Profession.NITWIT;
                    default -> Villager.Profession.SHEPHERD;
                });
                vil.setVillagerLevel(merchant ? 5 : 1);
                vil.getPersistentDataContainer().set(Keys.CIV_NPC, PersistentDataType.STRING, s.id + (merchant ? ":merchant" : ":resident"));
                vil.customName(Text.mm(merchant ? "<gold>Merchant of " + Text.esc(s.name) : "<gray>" + Text.esc(civ == null ? s.name : civ.adjective) + " " + s.type.noun + "er"));
                vil.setCustomNameVisible(merchant);
            });
            return new Body(v, s.id, merchant);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void despawn(Settlement s) {
        List<Body> l = bodies.remove(s.id);
        if (l == null) return;
        for (Body b : l) {
            b.mover.closeAll();
            if (b.v.isValid()) b.v.remove();
        }
    }

    public void despawnAll() {
        for (List<Body> l : bodies.values()) for (Body b : l) if (b.v.isValid()) b.v.remove();
        bodies.clear();
    }

    /** What a resident says when spoken to: whatever is most on the settlement's mind. */
    public String remark(Settlement s) {
        var world = civs.world();
        Civ civ = world.civs.get(s.civ);
        if (s.has("plague", world.day)) return "Keep your distance. There is plague in " + s.name + ".";
        if (s.besiegedBy != null && world.civs.get(s.besiegedBy) != null) return "The army of " + world.civs.get(s.besiegedBy).name + " is at our gates.";
        if (civ != null && world.atWar(civ.id)) return "We are at war. Every sword and every loaf counts now.";
        if (s.fed < 0.75) return "There isn't enough to eat. If you have bread, the merchant will make it worth your while.";
        if (s.has("festival", world.day)) return "It's festival week! Eat, drink, and mind the fiddler.";
        if (s.has("blight", world.day)) return "The blight took half the crop. It will be a thin season.";
        Map<String, Double> shortages = new LinkedHashMap<>(), surpluses = new LinkedHashMap<>();
        Prices.sort(s, shortages, surpluses);
        if (!shortages.isEmpty()) return "We are short of " + TradeGoods.get(shortages.keySet().iterator().next()).name().toLowerCase() + ". The merchant pays well for it.";
        if (!surpluses.isEmpty()) return "We have more " + TradeGoods.get(surpluses.keySet().iterator().next()).name().toLowerCase() + " than we know what to do with.";
        return civ == null ? "Fine " + CivSim.season(world.day) + " we're having." : "Life under " + civ.ruler() + " is " + (s.happiness > 60 ? "good" : s.happiness > 40 ? "bearable" : "hard") + ".";
    }
}
