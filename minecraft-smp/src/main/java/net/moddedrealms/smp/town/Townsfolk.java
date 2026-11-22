package net.moddedrealms.smp.town;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.moddedrealms.smp.Rewards;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Townsfolk wander the town around spawn: merchants with a few rotating deals, gossips who talk about what really
 * happened on the server, hungry travellers, pickpockets (who look like everyone else until they strike) and, at
 * night, a Fence who only deals with rogues and outlaws.
 */
public final class Townsfolk implements Listener {
    static final int WANDER = 0, FLEE = 1, APPROACH = 2, ESCAPE = 3;

    private final TownManager t;
    private final Random r = new Random();

    Townsfolk(TownManager t) {
        this.t = t;
    }

    void reload() {
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    private boolean civilian(Walker w) {
        return w.role != Walker.Role.OFFICER;
    }

    // ------------------------------------------------------------------ spawning

    void spawnTick() {
        World w = t.world();
        if (w == null || !t.canSpawnWalkers(w)) return;
        boolean night = t.isNight(w);
        int max = cfg().getInt("town.max-townsfolk", 16);
        int total = 0;
        for (Walker x : t.walkers()) if (civilian(x)) total++;
        for (Player p : w.getPlayers()) {
            if (total >= max) return;
            if (t.exempt(p) || !t.inTown(p.getLocation())) continue;
            int need = cfg().getInt("town.townsfolk-per-player", 3) - (night ? 1 : 0);
            if (t.countNear(p.getLocation(), 40, false) >= need) continue;
            Location at = t.spawnSpot(p, 14, 28);
            if (at == null || !t.inTown(at)) continue;
            spawn(at, pickRole(p, night));
            total++;
        }
    }

    private Walker.Role pickRole(Player near, boolean night) {
        boolean fenceAround = !t.walkers(Walker.Role.FENCE).isEmpty();
        if (night && !fenceAround && t.data(near).rep <= cfg().getInt("town.reputation.rogue", -50)) return Walker.Role.FENCE;
        boolean thieves = t.money.ready() && cfg().getBoolean("town.pickpockets", true);
        int merchant = night ? 10 : 25, gossip = 35, traveller = night ? 20 : 25, thief = thieves ? (night ? 25 : 12) : 0;
        int roll = r.nextInt(merchant + gossip + traveller + thief);
        if ((roll -= merchant) < 0) return Walker.Role.MERCHANT;
        if ((roll -= gossip) < 0) return Walker.Role.GOSSIP;
        if ((roll -= traveller) < 0) return Walker.Role.TRAVELLER;
        return Walker.Role.THIEF;
    }

    public Walker spawn(Location at, Walker.Role role) {
        List<String> names = cfg().getStringList("town.names.townsfolk");
        String first = names.isEmpty() ? "Pip" : names.get(r.nextInt(names.size()));
        String[] colors = {"#8B3A3A", "#3A5F8B", "#6B8B3A", "#8B6B3A", "#5A3A8B", "#2F6F6F", "#9C7A4A", "#7A7A7A", "#B5651D", "#4F4F2F"};
        Map<EquipmentSlot, ItemStack> gear = new EnumMap<>(EquipmentSlot.class);
        gear.put(EquipmentSlot.CHEST, Walker.dyed(Material.LEATHER_CHESTPLATE, colors[r.nextInt(colors.length)]));
        gear.put(EquipmentSlot.LEGS, Walker.dyed(Material.LEATHER_LEGGINGS, colors[r.nextInt(colors.length)]));
        if (r.nextInt(3) == 0) gear.put(EquipmentSlot.HEAD, Walker.dyed(Material.LEATHER_HELMET, colors[r.nextInt(colors.length)]));
        String name = "<white>" + first, title = null;
        switch (role) {
            case MERCHANT -> {
                title = "<gold>Travelling Merchant";
                gear.put(EquipmentSlot.HAND, new ItemStack(Material.BUNDLE));
                gear.put(EquipmentSlot.HEAD, Walker.dyed(Material.LEATHER_HELMET, "#C9A13B"));
            }
            case GOSSIP -> gear.put(EquipmentSlot.HAND, new ItemStack(r.nextBoolean() ? Material.POPPY : Material.BREAD));
            case TRAVELLER -> {
                title = "<gray>Weary Traveller";
                gear.put(EquipmentSlot.HAND, new ItemStack(Material.STICK));
                gear.put(EquipmentSlot.FEET, Walker.dyed(Material.LEATHER_BOOTS, "#5A4632"));
            }
            case THIEF -> { } // looks like everyone else
            case FENCE -> {
                name = "<dark_gray>Shady Figure";
                title = "<dark_gray>...psst";
                gear.put(EquipmentSlot.HEAD, Walker.dyed(Material.LEATHER_HELMET, "#1A1A1A"));
                gear.put(EquipmentSlot.CHEST, Walker.dyed(Material.LEATHER_CHESTPLATE, "#1A1A1A"));
                gear.put(EquipmentSlot.LEGS, Walker.dyed(Material.LEATHER_LEGGINGS, "#262626"));
            }
            default -> { }
        }
        Walker w = Walker.spawn(t.key, at, role, name, title, 0.23, cfg().getDouble("town.townsfolk-health", 12), gear);
        w.lifetime = (8 + r.nextInt(7)) * 60_000L;
        w.nextWander = System.currentTimeMillis() + 1500;
        if (role == Walker.Role.MERCHANT) {
            List<String> pool = new ArrayList<>(cfg().getStringList("town.merchant-offers"));
            Collections.shuffle(pool, r);
            w.offers = new ArrayList<>(pool.subList(0, Math.min(cfg().getInt("town.merchant-offer-count", 5), pool.size())));
        }
        return t.register(w);
    }

    // ------------------------------------------------------------------ brain

    void think() {
        long now = System.currentTimeMillis();
        World world = t.world();
        boolean night = world != null && t.isNight(world);
        for (Walker w : new ArrayList<>(t.walkers())) {
            if (!civilian(w) || !w.valid()) continue;
            Location at = w.loc();
            Player near = t.nearestPlayer(at, 72);
            if (near == null) {
                if (w.mode == ESCAPE) thiefEscaped(w);
                t.unregister(w, false);
                continue;
            }
            double nd = near.getLocation().distance(at);
            boolean old = now - w.born > w.lifetime || w.role == Walker.Role.FENCE && !night;
            if (old && nd > 16 && w.mode != ESCAPE) {
                t.unregister(w, false);
                continue;
            }
            switch (w.mode) {
                case FLEE -> {
                    if (now - w.modeSince > 6000) w.mode = WANDER;
                }
                case ESCAPE -> escape(w, now);
                case APPROACH -> approach(w, now);
                default -> wander(w, near, nd, now);
            }
        }
    }

    private void wander(Walker w, Player near, double nd, long now) {
        if (w.role != Walker.Role.FENCE && w.role != Walker.Role.THIEF && nd < 5 && t.tier(near) == TownManager.Tier.OUTLAW) {
            flee(w, near.getLocation(), 1.25);
            if (r.nextInt(3) == 0) w.say(near, "Stay away from me!");
            return;
        }
        if (w.role == Walker.Role.THIEF && now - w.modeSince > 12_000 && r.nextInt(12) == 0) {
            Player v = pickVictim(w);
            if (v != null) {
                w.mode = APPROACH;
                w.target = v.getUniqueId();
                w.modeSince = now;
                return;
            }
        }
        if (!w.moving() && now > w.nextWander) {
            Location base = w.loc();
            if (nd > 26) base = near.getLocation();
            for (int i = 0; i < 5; i++) {
                double a = r.nextDouble() * Math.PI * 2, d = 4 + r.nextDouble() * 9;
                Location g = t.surface(base.getWorld(), (int) Math.floor(base.getX() + Math.cos(a) * d), (int) Math.floor(base.getZ() + Math.sin(a) * d), base.getY(), 3);
                if (g != null && t.inTown(g)) {
                    w.moveTo(g, w.role == Walker.Role.FENCE ? 0.5 : 0.6);
                    break;
                }
            }
            w.nextWander = now + 4000 + r.nextInt(9000);
        }
        if (!w.moving() && nd < 6) w.lookAt(near.getEyeLocation());
    }

    private void flee(Walker w, Location threat, double speed) {
        Location away = t.awayFrom(w.loc(), threat, 12);
        if (away != null) w.moveTo(away, speed);
        w.mode = FLEE;
        w.modeSince = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ pickpockets (NPC thieves)

    private Player pickVictim(Walker w) {
        long now = System.currentTimeMillis();
        int minPlay = cfg().getInt("town.thief.min-playtime-minutes", 30) * 60 * 20;
        for (Player p : w.world().getPlayers()) {
            if (w.dist(p.getLocation()) > 20 || t.exempt(p)) continue;
            TownData d = t.data(p);
            if (d.stars > 0 || now - d.lastRobbed < cfg().getLong("town.thief.victim-cooldown-minutes", 30) * 60_000) continue;
            if (p.getStatistic(Statistic.PLAY_ONE_MINUTE) < minPlay) continue;
            if (t.money.balance(p) < cfg().getDouble("town.thief.min-balance", 100)) continue;
            return p;
        }
        return null;
    }

    private void approach(Walker w, long now) {
        Player v = w.target == null ? null : Bukkit.getPlayer(w.target);
        if (v == null || v.getWorld() != w.world() || now - w.modeSince > 40_000 || w.dist(v.getLocation()) > 26 || t.exempt(v)) {
            w.mode = WANDER;
            w.modeSince = now;
            w.target = null;
            w.stop();
            return;
        }
        double d = w.dist(v.getLocation());
        if (d > 1.6) {
            w.moveTo(v, d > 6 ? 0.95 : 0.8);
            return;
        }
        double bal = t.money.balance(v);
        double amount = Math.floor(Math.min(bal * cfg().getDouble("town.thief.steal-fraction", 0.03), cfg().getDouble("town.thief.max-steal", 150)));
        amount = Math.max(amount, Math.min(5, bal));
        double took = t.money.take(v, amount);
        if (took <= 0) {
            w.mode = WANDER;
            return;
        }
        TownData vd = t.data(v);
        vd.lastRobbed = now;
        w.robbed = v.getUniqueId();
        w.loot = took;
        w.mode = ESCAPE;
        w.modeSince = now;
        w.rename("<red>Thief!", "<gray>Catch them!");
        w.body.setGlowing(true);
        Location away = t.awayFrom(w.loc(), v.getLocation(), 20);
        if (away != null) w.moveTo(away, 1.6);
        Bukkit.getScheduler().runTaskLater(t.plugin(), () -> {
            if (!v.isOnline()) return;
            v.sendMessage(SmpPlugin.mm("<red>Hey! Your coin purse feels lighter... <white>(-" + Money.fmt(took) + ")</white> <gold>Catch the thief!"));
            v.playSound(v.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 0.6f);
        }, 15L);
    }

    private void escape(Walker w, long now) {
        if (now - w.modeSince > cfg().getLong("town.thief.escape-seconds", 40) * 1000) {
            thiefEscaped(w);
            t.unregister(w, true);
            return;
        }
        if (!w.moving() || r.nextInt(4) == 0) {
            Player chaser = t.nearestPlayer(w.loc(), 24);
            Location from = chaser != null ? chaser.getLocation() : w.loc().clone().add(r.nextInt(3) - 1, 0, r.nextInt(3) - 1);
            Location away = t.awayFrom(w.loc(), from, 14);
            if (away != null) w.moveTo(away, 1.6);
        }
    }

    private void thiefEscaped(Walker w) {
        Player v = w.robbed == null ? null : Bukkit.getPlayer(w.robbed);
        if (v != null) v.sendMessage(SmpPlugin.mm("<gray>The pickpocket got away with your <white>" + Money.fmt(w.loot) + "</white>. Keep your eyes open next time."));
        w.loot = 0;
    }

    /** Hit by a player, or tackled by an officer. */
    void thiefCaught(Walker w, Player by, Walker officer) {
        Player v = w.robbed == null ? null : Bukkit.getPlayer(w.robbed);
        if (w.loot > 0 && w.robbed != null) t.money.give(Bukkit.getOfflinePlayer(w.robbed), w.loot);
        if (officer != null) {
            officer.shout("Got you, you little sneak!", 20);
            if (v != null) v.sendMessage(SmpPlugin.mm(officer.name + " <green>caught the pickpocket. Your <white>" + Money.fmt(w.loot) + "</white> has been returned."));
        } else if (by != null) {
            double reward = cfg().getDouble("town.thief.catch-reward", 25);
            t.money.give(by, reward);
            t.addRep(by, cfg().getInt("town.reputation.catch-thief", 15));
            by.sendMessage(SmpPlugin.mm("<green>You caught the pickpocket! <yellow>+" + Money.fmt(reward) + "<green> reward."));
            by.playSound(by.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
            if (v != null && v != by) v.sendMessage(SmpPlugin.mm("<green>" + by.getName() + " caught the pickpocket. Your <white>" + Money.fmt(w.loot) + "</white> has been returned."));
            else if (v != null) v.sendMessage(SmpPlugin.mm("<green>You got your <white>" + Money.fmt(w.loot) + "</white> back."));
            t.news(by.getName() + " caught a pickpocket red-handed!");
        }
        w.loot = 0;
        t.unregister(w, true);
    }

    // ------------------------------------------------------------------ witnesses

    /** A townsperson or plaza NPC who saw `p` at `where`: a Walker or a fixed NPC entity, or null. */
    Object witness(Player p, Location where, Walker unaware) {
        for (Walker w : t.walkers()) {
            if (w == unaware || !civilian(w) || w.role == Walker.Role.THIEF || w.role == Walker.Role.FENCE || !w.valid()) continue;
            if (w.dist(where) <= t.witnessRange && w.sees(p)) return w;
        }
        for (var d : t.plugin().npcs().all()) {
            Entity e = t.plugin().npcs().entity(d.id);
            if (e instanceof LivingEntity le && e.getWorld() == where.getWorld() && e.getLocation().distance(where) <= t.witnessRange && le.hasLineOfSight(p)) return d;
        }
        return null;
    }

    void report(Object who, Player p, Crime c) {
        String line = "<yellow>Help! Police! " + p.getName() + " is " + switch (c) {
            case ASSAULT, ASSAULT_NPC -> "attacking people";
            case MURDER, MANSLAUGHTER -> "a murderer";
            case ARSON -> "setting fires";
            case EXPLOSIVES -> "blowing things up";
            case PICKPOCKET, ROBBERY -> "a thief";
            case PET_KILLING -> "killing pets";
            default -> "up to no good";
        } + "!";
        if (who instanceof Walker w && w.valid()) {
            w.shout(line, 32);
            flee(w, p.getLocation(), 1.4);
        } else if (who instanceof net.moddedrealms.smp.npc.NpcDef d) {
            Entity e = t.plugin().npcs().entity(d.id);
            if (e == null) return;
            for (Player x : e.getWorld().getPlayers()) {
                if (x.getLocation().distance(e.getLocation()) <= 32) {
                    x.sendMessage(SmpPlugin.mm(d.name + " <dark_gray>» " + line));
                }
            }
        }
    }

    // ------------------------------------------------------------------ hits and clicks

    void hit(Walker w, Player p, double damage) {
        if (w.role == Walker.Role.THIEF) {
            if (w.mode == ESCAPE) {
                thiefCaught(w, p, null);
            } else if (w.mode == APPROACH && p.getUniqueId().equals(w.target)) {
                p.sendMessage(SmpPlugin.mm("<green>You caught " + w.name + " <green>reaching for your purse! They run off empty-handed."));
                t.addRep(p, 5);
                t.unregister(w, true);
            } else {
                assault(w, p, damage);
            }
            return;
        }
        assault(w, p, damage);
    }

    private void assault(Walker w, Player p, double damage) {
        w.health -= damage;
        w.hurt();
        if (w.health <= 0) {
            String plain = w.name.replaceAll("<[^>]+>", "");
            t.unregister(w, true);
            t.commit(p, Crime.MANSLAUGHTER, p.getLocation(), "npc" + w.legs.getUniqueId(), null, false);
            t.news(p.getName() + " killed poor " + plain + " in the street!");
            return;
        }
        t.commit(p, Crime.ASSAULT_NPC, p.getLocation(), "npc" + w.legs.getUniqueId(), w, false);
        if (w.mode != ESCAPE) flee(w, p.getLocation(), 1.45);
    }

    void clicked(Walker w, Player p) {
        if (p.isSneaking() && p.getInventory().getItemInMainHand().getType().isAir()) {
            pickpocket(w, p);
            return;
        }
        if (w.mode == ESCAPE || w.mode == FLEE) return;
        w.stop();
        w.lookAt(p.getEyeLocation());
        TownManager.Tier tier = t.tier(p);
        if (tier == TownManager.Tier.OUTLAW && w.role != Walker.Role.FENCE && w.role != Walker.Role.THIEF) {
            w.say(p, "I know who you are. Leave me alone!");
            flee(w, p.getLocation(), 1.2);
            return;
        }
        switch (w.role) {
            case MERCHANT -> merchant(w, p);
            case TRAVELLER -> traveller(w, p);
            case FENCE -> fence(w, p);
            default -> gossip(w, p);
        }
    }

    private void gossip(Walker w, Player p) {
        List<String> lines = new ArrayList<>();
        List<Player> wanted = t.wantedOnline();
        wanted.remove(p);
        List<TownManager.News> news = t.recentNews(3 * 3600_000L);
        if (!wanted.isEmpty() && r.nextInt(3) == 0) {
            Player x = wanted.get(r.nextInt(wanted.size()));
            lines.add("Watch yourself. " + x.getName() + " is wanted for " + t.data(x).reason + ".");
        } else if (!news.isEmpty() && r.nextBoolean()) {
            lines.add("Did you hear? " + news.get(r.nextInt(Math.min(6, news.size()))).text());
        }
        List<String> tips = cfg().getStringList("town.gossip");
        if (!tips.isEmpty()) lines.add(tips.get(r.nextInt(tips.size())));
        if (lines.isEmpty()) lines.add("Lovely day, isn't it?");
        if (t.tier(p) == TownManager.Tier.HERO && r.nextInt(3) == 0) lines.add(0, "Oh! You're " + p.getName() + ", aren't you? Everyone's talking about you!");
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            Bukkit.getScheduler().runTaskLater(t.plugin(), () -> {
                if (p.isOnline() && w.valid()) w.say(p, l);
            }, i * 30L);
        }
    }

    private void traveller(Walker w, Player p) {
        if (w.helped.contains(p.getUniqueId())) {
            w.say(p, "Thank you again, friend. I won't forget it.");
            return;
        }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isEdible()) {
            hand.setAmount(hand.getAmount() - 1);
            w.helped.add(p.getUniqueId());
            t.addRep(p, cfg().getInt("town.reputation.help-traveller", 3));
            w.body.getWorld().spawnParticle(Particle.HEART, w.body.getEyeLocation().add(0, 0.5, 0), 4, 0.3, 0.2, 0.3);
            int roll = r.nextInt(100);
            if (roll < 5) {
                w.say(p, "Bless you! Here, I found this on the road. Maybe you know what it opens.");
                t.plugin().rewards().give(p, List.of("key:common:1"));
            } else if (roll < 35) {
                int coins = 20 + r.nextInt(41);
                w.say(p, "Bless you! Take these coins, it's all I have.");
                t.money.give(p, coins);
                p.sendMessage(SmpPlugin.mm("<yellow>+" + Money.fmt(coins)));
            } else {
                w.say(p, "Bless you, friend! I'll tell everyone in town how kind you were.");
            }
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.7f, 1.2f);
            return;
        }
        w.say(p, "Spare a bite to eat, friend? I've walked a long way. <gray>(talk to them holding some food)");
    }

    // ------------------------------------------------------------------ pickpocketing townsfolk

    private void pickpocket(Walker w, Player p) {
        if (t.exempt(p)) return;
        TownData d = t.data(p);
        long now = System.currentTimeMillis();
        if (now - d.lastPickpocketTry < 3000) return;
        d.lastPickpocketTry = now;
        if (w.role == Walker.Role.THIEF || w.role == Walker.Role.FENCE) {
            w.say(p, "Ha! Nice try. You can't fool a professional.");
            return;
        }
        if (d.pickedNpcs.containsKey(w.legs.getUniqueId())) {
            p.sendMessage(SmpPlugin.mm("<gray>Their pockets are empty now."));
            return;
        }
        long hour = now / 3_600_000L;
        if (d.pickpocketHour != hour) {
            d.pickpocketHour = hour;
            d.pickpocketsThisHour = 0;
        }
        if (d.pickpocketsThisHour >= cfg().getInt("town.pickpocket.max-per-hour", 8)) {
            p.sendMessage(SmpPlugin.mm("<gray>You've pushed your luck enough for one hour."));
            return;
        }
        Vector facing = w.body.getLocation().getDirection().setY(0);
        Vector toP = p.getLocation().toVector().subtract(w.loc().toVector()).setY(0);
        boolean behind = facing.lengthSquared() > 0 && toP.lengthSquared() > 0 && facing.normalize().dot(toP.normalize()) < -0.2;
        double chance = behind ? cfg().getDouble("town.pickpocket.chance-behind", 0.75) : cfg().getDouble("town.pickpocket.chance-front", 0.3);
        TownManager.Tier tier = t.tier(p);
        if (tier == TownManager.Tier.ROGUE || tier == TownManager.Tier.OUTLAW) chance += 0.1;
        if (r.nextDouble() < chance) {
            d.pickedNpcs.put(w.legs.getUniqueId(), now);
            d.pickpocketsThisHour++;
            int coins = 5 + r.nextInt(36);
            t.money.give(p, coins);
            String got = Money.fmt(coins);
            if (r.nextInt(100) < cfg().getInt("town.pickpocket.trinket-chance", 30)) {
                ItemStack loot = trinket();
                if (loot != null) {
                    for (ItemStack left : p.getInventory().addItem(loot).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
                    got += " and " + plain(loot);
                }
            }
            p.sendMessage(SmpPlugin.mm("<dark_green>You lifted <white>" + got + "</white> from " + w.name + "<dark_green>."));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.6f);
            t.commit(p, Crime.PICKPOCKET, p.getLocation(), "npc" + w.legs.getUniqueId(), null, false, w);
        } else {
            w.shout("<yellow>Thief! Get your hands off me! Police!", 24);
            p.sendMessage(SmpPlugin.mm("<red>They felt your hand in their pocket!"));
            flee(w, p.getLocation(), 1.4);
            t.commit(p, Crime.PICKPOCKET, p.getLocation(), "npc" + w.legs.getUniqueId(), w, false);
        }
    }

    private static String plain(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        if (m != null && m.hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(m.displayName());
        }
        return Rewards.pretty(it.getType().name());
    }

    /** "CLOCK:Silver Pocket Watch:120" -> a stolen item a fence buys for 120. */
    private ItemStack trinket() {
        List<String> pool = cfg().getStringList("town.stolen-goods");
        if (pool.isEmpty()) return null;
        String[] a = pool.get(r.nextInt(pool.size())).split(":", 3);
        Material m = Material.matchMaterial(a[0]);
        if (m == null || a.length < 3) return null;
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm("<gold>" + a[1]).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(SmpPlugin.mm("<gray>Stolen goods").decoration(TextDecoration.ITALIC, false),
                SmpPlugin.mm("<dark_gray>A fence would pay " + Money.fmt(Double.parseDouble(a[2])) + " for this").decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(t.stolenKey, PersistentDataType.INTEGER, Integer.parseInt(a[2]));
        it.setItemMeta(meta);
        return it;
    }

    // ------------------------------------------------------------------ menus

    static final class Menu implements InventoryHolder {
        final String kind;
        final Walker walker;
        Inventory inv;

        Menu(String kind, Walker walker) {
            this.kind = kind;
            this.walker = walker;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static ItemStack icon(Material m, int amount, String name, List<String> lore) {
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(m.getMaxStackSize(), amount)));
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    private record Offer(Material icon, int amount, String reward, double price, String label) {}

    private Offer offer(String line) {
        String[] a = line.split(":");
        try {
            if (a[0].equalsIgnoreCase("key") && a.length >= 4) {
                return new Offer(Material.TRIPWIRE_HOOK, Integer.parseInt(a[2]), "key:" + a[1] + ":" + a[2], Double.parseDouble(a[3]),
                        a[2] + " " + t.plugin().crates().keyName(a[1]));
            }
            Material m = Material.matchMaterial(a[0]);
            if (m == null || a.length < 3) return null;
            return new Offer(m, Integer.parseInt(a[1]), "item:" + m.name() + ":" + a[1], Double.parseDouble(a[2]), a[1] + " " + Rewards.pretty(m.name()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void merchant(Walker w, Player p) {
        double f = t.priceFactor(p);
        if (f <= 0) {
            w.say(p, "I don't deal with your kind. Go away before I call the watch.");
            return;
        }
        w.say(p, f < 1 ? "Ah, " + p.getName() + "! For you, a special price." : f > 1 ? "Hmph. Prices are... higher for some folk." : "Fresh from the road! Take a look.");
        Menu menu = new Menu("merchant", w);
        menu.inv = Bukkit.createInventory(menu, 27, SmpPlugin.mm("<gold>Travelling Merchant"));
        int slot = 10;
        for (String line : w.offers) {
            Offer o = offer(line);
            if (o == null) continue;
            double price = Math.ceil(o.price() * f);
            menu.inv.setItem(slot++, icon(o.icon(), o.amount(), "<yellow>" + o.label(), List.of("<gray>Price: <gold>" + Money.fmt(price)
                    + (f != 1 ? " <dark_gray>(" + (f < 1 ? "-" : "+") + Math.round(Math.abs(1 - f) * 100) + "% for your reputation)" : ""), "", "<green>Click to buy")));
            if (slot == 17) break;
        }
        menu.inv.setItem(22, icon(Material.GOLD_NUGGET, 1, "<gold>Your balance: " + Money.fmt(t.money.balance(p)), List.of("<gray>Stock changes with every merchant.")));
        p.openInventory(menu.inv);
    }

    private void fence(Walker w, Player p) {
        if (t.data(p).rep > cfg().getInt("town.reputation.rogue", -50)) {
            w.say(p, "Don't know you. Don't want to. Move along.");
            return;
        }
        w.say(p, "Keep your voice down. What've you got for me?");
        Menu menu = new Menu("fence", w);
        menu.inv = Bukkit.createInventory(menu, 27, SmpPlugin.mm("<dark_gray>The Fence"));
        int value = stolenValue(p, false);
        menu.inv.setItem(11, icon(Material.GOLD_INGOT, 1, "<gold>Sell stolen goods", List.of("<gray>Everything stolen in your inventory.",
                "<gray>Worth: <gold>" + Money.fmt(value), "", value > 0 ? "<green>Click to sell" : "<red>You have nothing I want")));
        double disguise = cfg().getDouble("town.fence.disguise-price", 750);
        menu.inv.setItem(13, icon(Material.LEATHER_HELMET, 1, "<white>Disguise kit", List.of("<gray>New clothes, new face.",
                "<gray>Clears your wanted stars, if no officer", "<gray>is watching you right now.", "", "<gray>Price: <gold>" + Money.fmt(disguise), "<green>Click to buy")));
        double keyPrice = cfg().getDouble("town.fence.key-price", 1500);
        String crate = cfg().getString("town.fence.key-crate", "blackmarket");
        if (!t.plugin().crates().all().containsKey(crate)) crate = "mythic";
        menu.inv.setItem(15, icon(Material.TRIPWIRE_HOOK, 1, "<dark_purple>" + t.plugin().crates().keyName(crate), List.of("<gray>Fell off the back of a cart.",
                "", "<gray>Price: <gold>" + Money.fmt(keyPrice), "<green>Click to buy")));
        p.openInventory(menu.inv);
    }

    private int stolenValue(Player p, boolean take) {
        int total = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !it.hasItemMeta()) continue;
            Integer v = it.getItemMeta().getPersistentDataContainer().get(t.stolenKey, PersistentDataType.INTEGER);
            if (v == null) continue;
            total += v * it.getAmount();
            if (take) inv.setItem(i, null);
        }
        return total;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        if (menu.kind.equals("merchant")) {
            int idx = slot - 10;
            if (idx < 0 || idx >= menu.walker.offers.size()) return;
            Offer o = offer(menu.walker.offers.get(idx));
            double f = t.priceFactor(p);
            if (o == null || f <= 0) return;
            double price = Math.ceil(o.price() * f);
            if (!t.money.pay(p, price)) {
                p.sendMessage(SmpPlugin.mm("<red>You can't afford that (" + Money.fmt(price) + ")."));
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
                return;
            }
            t.plugin().rewards().give(p, List.of(o.reward()));
            p.sendMessage(SmpPlugin.mm("<green>Bought " + o.label() + " for " + Money.fmt(price) + "."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.7f, 1.1f);
            menu.inv.setItem(22, icon(Material.GOLD_NUGGET, 1, "<gold>Your balance: " + Money.fmt(t.money.balance(p)), List.of("<gray>Stock changes with every merchant.")));
        } else if (menu.kind.equals("fence")) {
            switch (slot) {
                case 11 -> {
                    int v = stolenValue(p, true);
                    if (v <= 0) return;
                    t.money.give(p, v);
                    p.sendMessage(SmpPlugin.mm("<dark_green>The Fence counts out <gold>" + Money.fmt(v) + "<dark_green>. \"Pleasure doing business.\""));
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.7f);
                    p.closeInventory();
                }
                case 13 -> {
                    TownData d = t.data(p);
                    if (d.stars <= 0) {
                        p.sendMessage(SmpPlugin.mm("<gray>\"You're not even wanted. Save your money.\""));
                        return;
                    }
                    Walker o = t.officers().nearest(p.getLocation(), 24);
                    if (o != null && o.sees(p)) {
                        p.sendMessage(SmpPlugin.mm("<red>\"Not with the watch looking right at you!\""));
                        return;
                    }
                    if (!t.money.pay(p, cfg().getDouble("town.fence.disguise-price", 750))) {
                        p.sendMessage(SmpPlugin.mm("<red>You can't afford it."));
                        return;
                    }
                    t.clearWanted(d);
                    t.officers().forget(p);
                    p.sendMessage(SmpPlugin.mm("<dark_green>New coat, new hat, new walk. The watch won't recognise you."));
                    p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1f);
                    p.closeInventory();
                }
                case 15 -> {
                    String crate = cfg().getString("town.fence.key-crate", "blackmarket");
                    if (!t.plugin().crates().all().containsKey(crate)) crate = "mythic";
                    if (!t.money.pay(p, cfg().getDouble("town.fence.key-price", 1500))) {
                        p.sendMessage(SmpPlugin.mm("<red>You can't afford it."));
                        return;
                    }
                    t.plugin().rewards().give(p, List.of("key:" + crate + ":1"));
                    p.closeInventory();
                }
                default -> { }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
