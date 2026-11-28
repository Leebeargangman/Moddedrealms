package net.moddedrealms.minigames;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Murder Mystery: one murderer with a knife, one detective with a bow, everyone else innocent. Innocents collect
 * gold for a bow of their own. The murderer wins by killing everyone; the others win by shooting the murderer
 * or surviving until time runs out. Shoot an innocent and you die too.
 */
final class Murder extends Match {

    final Map<Participant, MurderBrain> brains = new HashMap<>();
    Participant murderer;
    Participant detective;
    boolean knifeOut;
    long knifeAt;
    long roundEnd;
    final List<Item> gold = new ArrayList<>();
    Item bowDrop;
    boolean bowPickedUp;
    private final List<Entity> corpses = new ArrayList<>();
    private final List<Entity> projectiles = new ArrayList<>();
    private long nextGoldAt;
    long lastKillAt = -1;
    Location lastKillLoc;
    private final Map<Participant, Location> lastPos = new HashMap<>();
    private final Map<Participant, Vector> motion = new HashMap<>();

    Murder(MinigamesPlugin plugin, int slot) {
        super(plugin, GameType.MURDER, slot);
    }

    // ------------------------------------------------------------ the arena

    @Override
    void prepareArena() {
        clearEntities();
    }

    @Override
    Location waitingSpot(int index) {
        int[] c = Mansion.ROOMS[index % Mansion.ROOMS.length];
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int[] cell = Mansion.nearestWalkable(c[0] + r.nextInt(-1, 2), c[1] + r.nextInt(-1, 2));
        if (cell == null) cell = c;
        Location l = Mansion.center(origin, cell[0], cell[1]);
        l.setYaw(r.nextFloat() * 360 - 180);
        return l;
    }

    @Override
    boolean nameTagsVisible() {
        return false;
    }

    @Override
    void onBotJoined(Participant p) {
        brains.put(p, new MurderBrain(this, p));
    }

    // ------------------------------------------------------------ the game

    @Override
    void onStart() {
        List<Participant> all = alive();
        double w = cfg().getDouble("human-role-weight", 2.0);
        murderer = weighted(all, w, null);
        detective = weighted(all, w, murderer);
        knifeAt = t + 20L * Math.max(3, cfg().getInt("knife-after-seconds", 10));
        roundEnd = t + 20L * Math.max(60, cfg().getInt("round-seconds", 270));
        nextGoldAt = t + 40;
        List<String> humanNames = new ArrayList<>();
        for (Participant p : players) if (!p.isBot()) humanNames.add(p.name);
        // Spread everyone around the mansion.
        List<int[]> spots = new ArrayList<>(List.of(Mansion.ROOMS));
        java.util.Collections.shuffle(spots);
        int i = 0;
        for (Participant p : all) {
            p.role = p == murderer ? Participant.Role.MURDERER : p == detective ? Participant.Role.DETECTIVE : Participant.Role.INNOCENT;
            if (p == detective) {
                p.bow = true;
                p.arrows = 1;
            }
            int[] c = spots.get(i++ % spots.size());
            int[] cell = Mansion.nearestWalkable(c[0] + rnd(3) - 1, c[1] + rnd(3) - 1);
            Location at = Mansion.center(origin, cell[0], cell[1]);
            at.setYaw(ThreadLocalRandom.current().nextFloat() * 360 - 180);
            LivingEntity e = p.entity();
            if (e != null) e.teleport(at);
            Player pl = p.player();
            if (pl != null) {
                pl.getInventory().clear();
                pl.getInventory().setItem(8, Items.leave());
                if (p == detective) {
                    pl.getInventory().setItem(1, Items.bow(true));
                    pl.getInventory().setItem(2, Items.arrow());
                }
                plugin.board().hideNames(pl, true, humanNames);
                Component title = switch (p.role) {
                    case MURDERER -> Component.text("MURDERER", NamedTextColor.RED, TextDecoration.BOLD);
                    case DETECTIVE -> Component.text("DETECTIVE", NamedTextColor.AQUA, TextDecoration.BOLD);
                    default -> Component.text("INNOCENT", NamedTextColor.GREEN, TextDecoration.BOLD);
                };
                Component sub = switch (p.role) {
                    case MURDERER -> Component.text("Kill everyone. You get your knife in " + secondsLeft(knifeAt) + "s", NamedTextColor.GRAY);
                    case DETECTIVE -> Component.text("Find the murderer and shoot them", NamedTextColor.GRAY);
                    default -> Component.text("Stay alive. Collect gold for a bow", NamedTextColor.GRAY);
                };
                pl.showTitle(Title.title(title, sub, Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1))));
                pl.sendMessage(Component.text("» ", NamedTextColor.GOLD).append(Component.text("You are ", NamedTextColor.GRAY)).append(title)
                        .append(Component.text(". ", NamedTextColor.GRAY)).append(sub));
            }
            MurderBrain b = brains.get(p);
            if (p.isBot() && b == null) brains.put(p, b = new MurderBrain(this, p));
            if (b != null) b.start();
        }
        broadcast(Component.text("» ", NamedTextColor.GOLD).append(Component.text("The murderer gets their knife in " + secondsLeft(knifeAt) + " seconds. Gold spawns around the mansion: 10 gold buys a bow.", NamedTextColor.YELLOW)));
    }

    private Participant weighted(List<Participant> from, double humanWeight, Participant not) {
        double total = 0;
        for (Participant p : from) if (p != not) total += p.isBot() ? 1 : humanWeight;
        double r = ThreadLocalRandom.current().nextDouble() * total;
        for (Participant p : from) {
            if (p == not) continue;
            r -= p.isBot() ? 1 : humanWeight;
            if (r <= 0) return p;
        }
        for (Participant p : from) if (p != not) return p;
        return null;
    }

    @Override
    void tickGame() {
        if (!knifeOut && t >= knifeAt) {
            knifeOut = true;
            Player pl = murderer == null ? null : murderer.player();
            if (pl != null) {
                pl.getInventory().setItem(1, Items.knife());
                pl.sendMessage(Component.text("» ", NamedTextColor.GOLD).append(Component.text("You have your knife. Hit to kill, right-click to throw.", NamedTextColor.RED)));
                pl.playSound(pl.getLocation(), Sound.ITEM_ARMOR_EQUIP_IRON, 1f, 0.8f);
            }
            for (Player p : humansOnline()) {
                if (murderer != null && p.getUniqueId().equals(murderer.id)) continue;
                p.sendActionBar(Component.text("The murderer has their knife!", NamedTextColor.RED));
                p.playSound(p.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.4f, 1.4f);
            }
        }
        spawnGold();
        if (t % 5 == 0) {
            for (Participant p : players) {
                Location l = p.alive && !p.gone ? p.loc() : null;
                if (l == null) continue;
                Location was = lastPos.put(p, l);
                if (was != null && was.getWorld() == l.getWorld()) motion.put(p, l.toVector().subtract(was.toVector()).multiply(0.2));
            }
        }
        // Detective's arrow comes back after a few seconds.
        for (Participant p : players) {
            if (!p.alive || p.gone || !p.bow || p.arrows > 0) continue;
            if (p.role == Participant.Role.DETECTIVE && p == detective && p.arrowAt > 0 && t >= p.arrowAt) {
                p.arrows = 1;
                p.arrowAt = 0;
                Player pl = p.player();
                if (pl != null && !pl.getInventory().contains(Material.ARROW)) pl.getInventory().setItem(2, Items.arrow());
            }
        }
        // Bots pick up gold they walk over.
        for (Participant p : players) {
            if (!p.isBot() || !p.alive || p.gone) continue;
            Location l = p.loc();
            if (l == null) continue;
            for (Iterator<Item> it = gold.iterator(); it.hasNext(); ) {
                Item g = it.next();
                if (!g.isValid()) {
                    it.remove();
                    continue;
                }
                if (g.getLocation().distanceSquared(l) < 1.6) {
                    it.remove();
                    g.remove();
                    collectGold(p);
                    break;
                }
            }
            if (bowDrop != null && bowDrop.isValid() && p.role != Participant.Role.MURDERER && !p.bow && bowDrop.getLocation().distanceSquared(l) < 2.0) {
                pickUpBow(p);
            }
        }
        if (bowDrop != null && bowDrop.isValid() && t % 10 == 0) {
            bowDrop.getWorld().spawnParticle(Particle.END_ROD, bowDrop.getLocation().add(0, 0.5, 0), 2, 0.1, 0.2, 0.1, 0.01);
        }
        for (MurderBrain b : new ArrayList<>(brains.values())) b.tick(t);
        projectiles.removeIf(e -> {
            if (!e.isValid()) return true;
            if (e.getTicksLived() > 40) {
                e.remove();
                return true;
            }
            return false;
        });
        if (state != State.PLAYING) return;
        if (t >= roundEnd) {
            innocentsWin(Component.text("Time's up! ", NamedTextColor.YELLOW).append(Component.text("The innocents survived.", NamedTextColor.GREEN)));
            return;
        }
        if (t % 20 == 0) {
            for (Player pl : humansOnline()) {
                Participant me = of(pl);
                if (me == null || !me.alive) continue;
                if (me.role == Participant.Role.MURDERER && knifeOut && me.throwAt > t) {
                    pl.sendActionBar(Component.text("Knife throw ready in " + secondsLeft(me.throwAt) + "s", NamedTextColor.RED));
                } else if (me.role == Participant.Role.DETECTIVE && me.arrows == 0 && me.arrowAt > t) {
                    pl.sendActionBar(Component.text("Next arrow in " + secondsLeft(me.arrowAt) + "s", NamedTextColor.AQUA));
                }
            }
        }
    }

    private void spawnGold() {
        if (t < nextGoldAt) return;
        nextGoldAt = t + 20L * Math.max(1, cfg().getInt("gold-every-seconds", 3));
        gold.removeIf(g -> !g.isValid());
        if (gold.size() >= cfg().getInt("max-gold-on-map", 14)) return;
        int[] c = Mansion.randomFloor();
        Location at = Mansion.center(origin, c[0], c[1]).add(0, 0.25, 0);
        Item it = at.getWorld().dropItem(at, Items.gold(1), i -> {
            i.setVelocity(new Vector(0, 0, 0));
            i.setPickupDelay(5);
            i.setUnlimitedLifetime(true);
            i.setCanMobPickup(false);
            i.setPersistent(false);
            i.getPersistentDataContainer().set(plugin.itemKey, PersistentDataType.STRING, "mmgold");
        });
        gold.add(it);
    }

    void collectGold(Participant p) {
        p.gold++;
        int need = Math.max(1, cfg().getInt("gold-for-bow", 10));
        Player pl = p.player();
        Location l = p.loc();
        if (l != null) l.getWorld().playSound(l, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        if (p.gold >= need && p.role != Participant.Role.MURDERER) {
            p.gold -= need;
            boolean had = p.bow;
            p.bow = true;
            p.arrows++;
            if (pl != null) {
                if (!had) pl.getInventory().setItem(1, Items.bow(false));
                ItemStack a = pl.getInventory().getItem(2);
                if (a != null && a.getType() == Material.ARROW) a.setAmount(Math.min(64, a.getAmount() + 1));
                else pl.getInventory().setItem(2, Items.arrow());
                pl.sendMessage(Component.text("» ", NamedTextColor.GOLD).append(Component.text(had ? "+1 arrow!" : "You bought a bow and an arrow!", NamedTextColor.AQUA)));
                pl.playSound(pl.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
            }
        }
        if (pl != null) {
            if (p.gold > 0) pl.getInventory().setItem(7, Items.gold(p.gold));
            else pl.getInventory().setItem(7, null);
        }
    }

    boolean isGold(Item item) {
        return "mmgold".equals(item.getPersistentDataContainer().get(plugin.itemKey, PersistentDataType.STRING));
    }

    boolean isBowDrop(Item item) {
        return "mmbow".equals(item.getPersistentDataContainer().get(plugin.itemKey, PersistentDataType.STRING));
    }

    void pickUpBow(Participant p) {
        if (bowDrop == null || !bowDrop.isValid() || p.role == Participant.Role.MURDERER || p.bow || !p.alive) return;
        bowDrop.remove();
        bowDrop = null;
        bowPickedUp = true;
        p.bow = true;
        p.arrows = Math.max(p.arrows, 1);
        if (p.role == Participant.Role.INNOCENT) {
            p.role = Participant.Role.DETECTIVE;
            detective = p;
        }
        Player pl = p.player();
        if (pl != null) {
            pl.getInventory().setItem(1, Items.bow(true));
            if (!pl.getInventory().contains(Material.ARROW)) pl.getInventory().setItem(2, Items.arrow());
            pl.sendMessage(Component.text("» ", NamedTextColor.GOLD).append(Component.text("You picked up the detective's bow. Find the murderer!", NamedTextColor.AQUA)));
        }
        broadcast(Component.text("» ", NamedTextColor.GOLD).append(Component.text("Someone picked up the detective's bow!", NamedTextColor.AQUA)));
    }

    /** Someone in the match hit someone. Only the murderer's knife does anything. */
    void melee(Participant attacker, Participant victim) {
        if (state != State.PLAYING || attacker == null || victim == null || !attacker.alive || !victim.alive || attacker == victim) return;
        if (attacker != murderer || !knifeOut || !holdingKnife(attacker)) return;
        Location a = attacker.loc(), v = victim.loc();
        if (a == null || v == null || a.distanceSquared(v) > 4.5 * 4.5) return;
        kill(victim, attacker, "knife");
    }

    /** How someone is moving, in blocks a tick (measured from where they were, so it works for real players too). */
    Vector motion(Participant p) {
        Vector v = motion.get(p);
        return v == null ? new Vector() : v.clone();
    }

    boolean holdingKnife(Participant p) {
        if (p.isBot()) return p.bot.holding() == Material.IRON_SWORD;
        Player pl = p.player();
        return pl != null && "knife".equals(Items.id(pl.getInventory().getItemInMainHand()));
    }

    void throwKnife(Participant p, Vector dir) {
        if (p != murderer || !knifeOut || !p.alive || t < p.throwAt) return;
        LivingEntity e = p.entity();
        if (e == null) return;
        p.throwAt = t + 20L * Math.max(1, cfg().getInt("knife-throw-cooldown-seconds", 6));
        Snowball s = e.launchProjectile(Snowball.class, dir.clone().normalize().multiply(1.6));
        s.setItem(new ItemStack(Material.IRON_SWORD));
        s.setGravity(false);
        s.setPersistent(false);
        s.getPersistentDataContainer().set(plugin.itemKey, PersistentDataType.STRING, "knife");
        projectiles.add(s);
        e.getWorld().playSound(e.getLocation(), Sound.ITEM_TRIDENT_THROW, 1f, 1.2f);
        if (p.isBot()) p.bot.swing();
    }

    /** A bot shoots an arrow at a point. */
    void botShoot(Participant p, Location aim) {
        LivingEntity e = p.entity();
        if (e == null || !p.bow || p.arrows <= 0) return;
        Location eye = e.getEyeLocation();
        Vector dir = aim.toVector().subtract(eye.toVector());
        Arrow a = e.launchProjectile(Arrow.class, dir.normalize().multiply(3.0));
        a.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
        a.setPersistent(false);
        projectiles.add(a);
        e.getWorld().playSound(e.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1f);
        shot(p);
    }

    /** After someone shoots: one arrow fewer, the detective's comes back later. */
    void shot(Participant p) {
        p.arrows = Math.max(0, p.arrows - 1);
        if (p == detective || p.role == Participant.Role.DETECTIVE) p.arrowAt = t + 20L * Math.max(1, cfg().getInt("detective-arrow-seconds", 5));
    }

    void trackProjectile(Entity e) {
        projectiles.add(e);
    }

    /** An arrow or a thrown knife hit someone. */
    void projectileHit(Projectile proj, Participant victim) {
        if (state != State.PLAYING || victim == null || !victim.alive) return;
        Participant shooter = proj.getShooter() instanceof Entity se ? ofEntity(se) : null;
        String tag = proj.getPersistentDataContainer().get(plugin.itemKey, PersistentDataType.STRING);
        proj.remove();
        if (shooter == null || shooter == victim) return;
        if ("knife".equals(tag)) {
            if (shooter == murderer) kill(victim, shooter, "thrown knife");
            return;
        }
        if (!(proj instanceof AbstractArrow)) return;
        if (victim == murderer) {
            shooter.hero = true;
            kill(victim, shooter, "arrow");
        } else if (shooter.role != Participant.Role.MURDERER) {
            kill(victim, shooter, "arrow");
            if (shooter.alive) {
                Player sp = shooter.player();
                if (sp != null) sp.sendMessage(Component.text("» ", NamedTextColor.GOLD).append(Component.text("You shot an innocent, so you die too!", NamedTextColor.RED)));
                kill(shooter, null, "shot an innocent");
            }
        }
    }

    void kill(Participant v, Participant killer, String how) {
        if (!v.alive || state != State.PLAYING) return;
        Location at = v.loc();
        v.alive = false;
        v.outAt = t;
        if (killer != null && killer != v) killer.kills++;
        plugin.getLogger().info("[Murder #" + slot + " " + (t - startedAt) / 20 + "s] " + (killer == null ? "-" : killer.name + (killer.isBot() ? " [bot]" : ""))
                + " killed " + v.name + (v.isBot() ? " [bot]" : "") + " (" + how + "), " + (alive().size() - 1) + " alive");
        boolean byMurderer = killer != null && killer == murderer;
        if (at != null) {
            corpse(v, at);
            World w = at.getWorld();
            w.playSound(at, Sound.ENTITY_PLAYER_HURT, 1.2f, 1f);
            w.playSound(at, Sound.ENTITY_PLAYER_DEATH, 1f, 0.9f);
            if (byMurderer) {
                lastKillAt = t;
                lastKillLoc = at.clone();
            }
            // Others: who saw it, who heard it.
            for (MurderBrain b : new ArrayList<>(brains.values())) {
                if (b.me == v || !b.me.alive) continue;
                b.event(at, killer, byMurderer);
            }
        }
        if (v == detective || (v.bow && v.role == Participant.Role.DETECTIVE)) {
            if (at != null && v != murderer && !(murderer != null && !murderer.alive)) dropBow(at);
        }
        brains.remove(v);
        MurderBrain kb = killer == null ? null : brains.get(killer);
        if (kb != null && byMurderer) kb.afterKill();
        if (v.isBot()) {
            v.bot.remove();
            if (chance(plugin.getConfig().getDouble("bots.chat-chance", 0.35) * 0.5)) {
                botSay(v, pick("rip", "noo", "bruh", "how", "wow", "lol", "ugh", "really"), 15 + rnd(60));
            }
        } else {
            Player pl = v.player();
            if (pl != null) {
                Component sub = killer == null ? Component.text("You shot an innocent", NamedTextColor.GRAY)
                        : byMurderer ? Component.text("The murderer was ", NamedTextColor.GRAY).append(killer.display(NamedTextColor.RED))
                        : Component.text("Shot by ", NamedTextColor.GRAY).append(killer.display(NamedTextColor.AQUA));
                pl.showTitle(Title.title(Component.text("YOU DIED!", NamedTextColor.RED, TextDecoration.BOLD), sub,
                        Title.Times.times(Duration.ZERO, Duration.ofSeconds(3), Duration.ofSeconds(1))));
                spectate(pl, at == null ? null : at.clone().add(0, 2, 0));
                pl.getInventory().setItem(0, Items.again());
                pl.getInventory().setItem(8, Items.leave());
            }
        }
        checkWin();
    }

    private void corpse(Participant v, Location at) {
        Location l = at.clone();
        l.setPitch(0);
        ResolvableProfile prof;
        Player pl = v.player();
        if (v.isBot()) prof = v.bot.profile();
        else if (pl != null) prof = ResolvableProfile.resolvableProfile(pl.getPlayerProfile());
        else prof = null;
        Mannequin m = at.getWorld().spawn(l, Mannequin.class, c -> {
            c.setPersistent(false);
            c.getPersistentDataContainer().set(plugin.corpseKey, PersistentDataType.STRING, v.name);
            c.setInvulnerable(true);
            c.setImmovable(true);
            c.setCollidable(false);
            c.setSilent(true);
            c.setDescription(null);
            c.setCustomNameVisible(false);
            if (prof != null) c.setProfile(prof);
            c.setPose(Pose.SLEEPING, true);
        });
        corpses.add(m);
    }

    private void dropBow(Location at) {
        if (bowDrop != null && bowDrop.isValid()) return;
        Location l = at.clone().add(0, 0.3, 0);
        bowDrop = l.getWorld().dropItem(l, Items.bow(true), i -> {
            i.setVelocity(new Vector(0, 0, 0));
            i.setPickupDelay(30);
            i.setUnlimitedLifetime(true);
            i.setCanMobPickup(false);
            i.setGlowing(true);
            i.setPersistent(false);
            i.getPersistentDataContainer().set(plugin.itemKey, PersistentDataType.STRING, "mmbow");
        });
        broadcast(Component.text("» ", NamedTextColor.GOLD).append(Component.text("The detective has been killed! Pick up their bow.", NamedTextColor.AQUA)));
    }

    private void checkWin() {
        if (state != State.PLAYING) return;
        if (murderer == null || murderer.gone || !murderer.alive) {
            Participant hero = null;
            for (Participant p : players) if (p.hero) hero = p;
            Component head = hero != null
                    ? hero.display(NamedTextColor.AQUA).append(Component.text(" shot the murderer!", NamedTextColor.GREEN))
                    : Component.text("The murderer is gone. ", NamedTextColor.GREEN).append(Component.text("Innocents win!", NamedTextColor.GREEN));
            innocentsWin(head);
            return;
        }
        int others = 0;
        for (Participant p : alive()) if (p != murderer) others++;
        if (others == 0) {
            finish(List.of(murderer), Component.text("The murderer wins!", NamedTextColor.RED, TextDecoration.BOLD));
        }
    }

    private void innocentsWin(Component head) {
        List<Participant> winners = new ArrayList<>();
        for (Participant p : players) if (!p.gone && p != murderer && (p.alive || p.hero)) winners.add(p);
        finish(winners, head);
    }

    @Override
    void onOut(Participant p, String why) {
        // Only for leaving: kills go through kill().
        brains.remove(p);
        if (p == detective && p.bow) {
            Location l = p.loc();
            if (l != null) dropBow(l);
        }
        broadcast(p.display(NamedTextColor.GRAY).append(Component.text(" left the game.", NamedTextColor.GRAY)));
        checkWin();
    }

    @Override
    void onFinish(List<Participant> winners) {
        if (murderer != null) {
            broadcast(Component.text("  Murderer: ", NamedTextColor.RED).append(murderer.display())
                    .append(Component.text(" (" + murderer.kills + " kill" + (murderer.kills == 1 ? "" : "s") + ")", NamedTextColor.GRAY)));
        }
        Participant firstDetective = null;
        for (Participant p : players) if (p.role == Participant.Role.DETECTIVE) firstDetective = firstDetective == null ? p : firstDetective;
        if (detective != null) broadcast(Component.text("  Detective: ", NamedTextColor.AQUA).append(detective.display()));
        for (Participant p : players) if (p.hero) broadcast(Component.text("  Hero: ", NamedTextColor.GOLD).append(p.display()));
        for (MurderBrain b : brains.values()) b.me.bot.stop();
        for (Player pl : humansOnline()) plugin.board().hideNames(pl, false, List.of());
        for (Participant p : players) if (p.isBot() && p.alive) p.bot.setNameVisible(true);
    }

    @Override
    void onCleanup() {
        brains.clear();
        clearEntities();
    }

    private void clearEntities() {
        for (Item g : gold) if (g.isValid()) g.remove();
        gold.clear();
        if (bowDrop != null && bowDrop.isValid()) bowDrop.remove();
        bowDrop = null;
        for (Entity c : corpses) if (c.isValid()) c.remove();
        corpses.clear();
        for (Entity e : projectiles) if (e.isValid()) e.remove();
        projectiles.clear();
        World w = origin.getWorld();
        for (Entity e : w.getNearbyEntities(origin.clone().add(Mansion.W / 2.0, 3, Mansion.H / 2.0), Mansion.W / 2.0 + 3, 8, Mansion.H / 2.0 + 3)) {
            if (e instanceof Item || e instanceof Projectile) e.remove();
            if (e.getPersistentDataContainer().has(plugin.corpseKey)) e.remove();
        }
    }

    @Override
    List<Player> audience(Participant speaker) {
        if (state != State.PLAYING || speaker.alive) return humansOnline();
        List<Player> dead = new ArrayList<>();
        for (Participant p : players) {
            if (p.isBot() || p.gone || p.alive) continue;
            Player pl = p.player();
            if (pl != null) dead.add(pl);
        }
        return dead;
    }

    @Override
    List<Component> sidebar(Participant viewer) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        Component role = switch (viewer.role) {
            case MURDERER -> Component.text("Murderer", NamedTextColor.RED);
            case DETECTIVE -> Component.text("Detective", NamedTextColor.AQUA);
            case INNOCENT -> Component.text("Innocent", NamedTextColor.GREEN);
            default -> Component.text("-", NamedTextColor.GRAY);
        };
        lines.add(Component.text("Role: ", NamedTextColor.GRAY).append(role).append(viewer.alive ? Component.empty() : Component.text(" (dead)", NamedTextColor.DARK_GRAY)));
        lines.add(Component.empty());
        int innocents = 0;
        for (Participant p : alive()) if (p != murderer) innocents++;
        lines.add(Component.text("Innocents left: ", NamedTextColor.GRAY).append(Component.text(String.valueOf(innocents), NamedTextColor.WHITE)));
        long s = secondsLeft(roundEnd);
        lines.add(Component.text("Time left: ", NamedTextColor.GRAY).append(Component.text(String.format("%d:%02d", s / 60, s % 60), NamedTextColor.GREEN)));
        lines.add(Component.empty());
        Component det;
        if (bowDrop != null && bowDrop.isValid()) det = Component.text("Bow dropped!", NamedTextColor.GOLD);
        else if (detective != null && detective.alive) det = Component.text(bowPickedUp ? "Bow picked up" : "Alive", NamedTextColor.AQUA);
        else det = Component.text("Dead", NamedTextColor.RED);
        lines.add(Component.text("Detective: ", NamedTextColor.GRAY).append(det));
        if (viewer.alive && viewer.role != Participant.Role.MURDERER) lines.add(Component.text("Gold: ", NamedTextColor.GRAY).append(Component.text(String.valueOf(viewer.gold), NamedTextColor.GOLD)));
        if (viewer.role == Participant.Role.MURDERER) lines.add(Component.text("Kills: ", NamedTextColor.GRAY).append(Component.text(String.valueOf(viewer.kills), NamedTextColor.RED)));
        lines.add(Component.empty());
        if (bots() > 0) lines.add(Component.text(bots() + " " + Bot.tag() + " in this game", NamedTextColor.DARK_GRAY));
        lines.add(Component.text("play.moddedrealms.net", NamedTextColor.YELLOW));
        return lines;
    }
}
