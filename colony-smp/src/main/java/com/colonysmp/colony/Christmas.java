package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.command.ColonyCommand;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Season;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Modifier;
import com.colonysmp.model.Perm;
import com.colonysmp.npc.Npc;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.TreeType;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Christmas. For a few weeks around the turn of the year a colony can plant a Christmas tree; while it stands
 * everyone is a little merrier, the citizens gather at it of an evening, the children throw snowballs, the bard
 * plays carols, people ask for festive things, feast days become Christmas feasts, and Father Christmas calls
 * with presents for the players and treats for the colony.
 */
public final class Christmas implements Listener {

    /** Mood everyone gains while the colony's tree stands in season. */
    public static final double CHEER = 3;

    private final ColonySMP plugin;
    private long checkedAt;
    private boolean season;
    private int year;
    private int seconds;

    public Christmas(ColonySMP plugin) {
        this.plugin = plugin;
    }

    // ───────────── the season ─────────────

    private void refresh() {
        long now = System.currentTimeMillis();
        if (now - checkedAt < 60_000 && checkedAt != 0) return;
        checkedAt = now;
        Settings s = plugin.settings();
        LocalDate today = LocalDate.now();
        year = Season.year(today, s.christmasFrom, s.christmasTo);
        season = switch (s.christmasMode) {
            case "always" -> true;
            case "never" -> false;
            default -> Season.within(MonthDay.from(today), s.christmasFrom, s.christmasTo);
        };
        if (s.christmasMode.equals("always")) year = today.getYear();
    }

    /** Settings changed: look at the calendar again. */
    public void reload() {
        checkedAt = 0;
    }

    /** Is it the Christmas season? */
    public boolean season() {
        refresh();
        return season;
    }

    /** The year this season began in (a tree is planted for one season). */
    public int year() {
        refresh();
        return year;
    }

    private static String date(MonthDay d) {
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    public String dates() {
        Settings s = plugin.settings();
        return date(s.christmasFrom) + " to " + date(s.christmasTo);
    }

    /** Does the colony's tree stand, this season? */
    public boolean decorated(Colony c) {
        return season() && c.christmasStar != null && c.christmasYear == year();
    }

    /** The mood the season lends a colony with a tree. */
    public double cheer(Colony c) {
        return decorated(c) ? CHEER : 0;
    }

    /** The foot of the colony's tree, or the Town Hall if it has none. */
    public Location treeLocation(Colony c) {
        World w = c.world();
        if (w != null && c.christmasTree != null && decorated(c)) return c.christmasTree.center(w);
        return c.coreLocation();
    }

    // ───────────── the tree ─────────────

    /** Planting the Christmas Tree Sapling: it never becomes an ordinary sapling. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlant(BlockPlaceEvent e) {
        ItemStack hand = e.getItemInHand();
        if (!Items.is(hand, Items.CHRISTMAS_SAPLING)) return;
        boolean allowed = !e.isCancelled();
        e.setCancelled(true);
        if (!allowed) return;
        Player p = e.getPlayer();
        Block at = e.getBlockPlaced();
        EquipmentSlot slot = e.getHand();
        // the cancelled placement is undone after this event: grow the tree once the ground is clear again
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            String err = plant(p, at);
            if (err != null) {
                Text.send(p, "<red>" + err);
                return;
            }
            if (p.getGameMode() == GameMode.CREATIVE) return;
            ItemStack held = p.getInventory().getItem(slot);
            if (!Items.is(held, Items.CHRISTMAS_SAPLING)) return;
            held.setAmount(held.getAmount() - 1);
            p.getInventory().setItem(slot, held.getAmount() <= 0 ? null : held);
        });
    }

    /** Grows and dresses the colony's tree at a block. Returns an error, or null. */
    public String plant(Player p, Block at) {
        if (!season()) return "Christmas trees only take root in the Christmas season (" + dates() + ").";
        Colony c = plugin.colonies().at(at);
        if (c == null || p != null && !c.isMember(p.getUniqueId()) && !plugin.isBypassing(p)) return "Plant it on your own colony's land.";
        if (p != null && !plugin.access().can(p, c, Perm.BUILD)) return "You may not build in " + c.name + ".";
        World w = at.getWorld();
        if (decorated(c) && c.christmasStar.loaded(w) && c.christmasStar.block(w).getType() == Material.OCHRE_FROGLIGHT) {
            return c.name + " already has its Christmas tree (at " + c.christmasTree.x() + ", " + c.christmasTree.z() + ").";
        }
        if (!Tag.DIRT.isTagged(at.getRelative(BlockFace.DOWN).getType())) return "A tree needs earth under it: plant it on dirt or grass.";
        List<Block> leaves = new ArrayList<>();
        int[] top = {at.getY()};
        boolean grew;
        try {
            grew = w.generateTree(at.getLocation(), new Random(), TreeType.REDWOOD, state -> {
                top[0] = Math.max(top[0], state.getY());
                if (Tag.LEAVES.isTagged(state.getType())) leaves.add(w.getBlockAt(state.getX(), state.getY(), state.getZ()));
            });
        } catch (RuntimeException ex) {
            grew = false;
        }
        if (!grew) return "There isn't room for a tree here: it needs open sky above and space around.";
        // a star on top
        Block star = w.getBlockAt(at.getX(), top[0] + 1, at.getZ());
        if (!star.getType().isAir()) star = w.getBlockAt(at.getX(), top[0], at.getZ());
        star.setType(Material.OCHRE_FROGLIGHT, false);
        // lanterns hung in the branches
        Collections.shuffle(leaves);
        int hung = 0;
        for (Block leaf : leaves) {
            if (hung >= 8) break;
            Block below = leaf.getRelative(BlockFace.DOWN);
            if (!below.getType().isAir() || leaf.getY() < at.getY() + 2 || !Tag.LEAVES.isTagged(leaf.getType())) continue;
            below.setType(Material.LANTERN, false);
            if (below.getBlockData() instanceof Lantern l) {
                l.setHanging(true);
                below.setBlockData(l, false);
            }
            hung++;
        }
        c.christmasTree = BlockPos.of(at);
        c.christmasStar = BlockPos.of(star);
        c.christmasYear = year();
        long day = plugin.sim().day(c);
        c.log(ColonyEvent.Type.COLONY, day, (p == null ? "The colony" : p.getName()) + " raised the Christmas tree.");
        Location show = star.getLocation().add(0.5, 0.5, 0.5);
        w.spawnParticle(Particle.FIREWORK, show, 60, 1.5, 2.5, 1.5, 0.05);
        Fx.sound(show, "minecraft:block.bell.use", 1.5f, 1.2f);
        for (Player m : c.onlineMembers()) {
            Text.title(m, "<green>🎄 Merry Christmas!", "<gray>" + Text.esc(c.name) + " has its tree", 10, 70, 20);
            Text.send(m, "<green>🎄 The Christmas tree stands in " + Text.esc(c.name) + ".</green> <gray>Everyone is merrier while it does (+" + (int) CHEER
                    + " mood), the people gather at it in the evening, and <red>Father Christmas</red> will call.");
        }
        plugin.milestones().check(c);
        plugin.requestSave();
        return null;
    }

    /** Once a day: is the star still on the tree? A tree cut down is no tree. */
    public void daily(Colony c, long day) {
        if (c.christmasStar == null) return;
        World w = c.world();
        if (c.christmasYear != year() && !season()) {
            // last year's tree: it's just a spruce now
            c.christmasTree = null;
            c.christmasStar = null;
            return;
        }
        if (w == null || !c.christmasStar.loaded(w)) return;
        if (c.christmasStar.block(w).getType() != Material.OCHRE_FROGLIGHT) {
            c.christmasTree = null;
            c.christmasStar = null;
            c.log(ColonyEvent.Type.COLONY, day, "The Christmas tree has lost its star.");
            for (Player p : c.onlineMembers()) Text.send(p, "<gray>The Christmas tree has lost its star: it no longer counts. Plant another sapling to raise a new one.");
        }
    }

    // ───────────── Father Christmas ─────────────

    /** He has reached the tree: treats for the colony, and the children go wild. */
    public void arrived(Colony c, Happenings.Caravan cv) {
        long day = plugin.sim().day(c);
        int children = 0;
        for (Citizen ct : c.citizens.values()) {
            if (!ct.status.free()) continue;
            ct.needs.social = Math.min(100, ct.needs.social + 10);
            if (plugin.sim().citizens().stage(ct) == LifeStage.CHILD) {
                children++;
                ct.needs.mood = Math.min(100, ct.needs.mood + 10);
                ct.remember(day, "Got a present from Father Christmas");
            }
        }
        c.addModifier(new Modifier("presents", "Presents under the tree", 4, day + 3));
        // the sack holds something for the table too
        long treats = 0;
        for (String[] g : new String[][]{{"cookie", "24"}, {"pumpkin_pie", "4"}, {"cake", "1"}}) {
            if (Goods.known(g[0])) treats += c.storage.add(g[0], Long.parseLong(g[1]));
        }
        c.log(ColonyEvent.Type.COLONY, day, "Father Christmas left presents under the tree" + (children > 0 ? " for " + children + " child" + (children == 1 ? "" : "ren") : "")
                + (treats > 0 ? " and treats for the table" : "") + ".");
        Location at = cv.trader.getLocation();
        at.getWorld().spawnParticle(Particle.SNOWFLAKE, at.clone().add(0, 2, 0), 80, 2, 1.5, 2, 0.02);
        for (Player p : c.onlineMembers()) {
            Text.send(p, "<red>🎅 Father Christmas is at the tree!</red> <gray>" + (children > 0 ? "The children have their presents, and there " : "There ")
                    + "are treats in the stores. <white>Right-click him</white> for a present of your own. He won't stay long.");
            Fx.sound(p, "minecraft:block.bell.use", 1f, 1.6f);
        }
        plugin.requestSave();
    }

    /** A player asks Father Christmas for their present: one each, each visit. */
    public void gift(Player p, Happenings.Caravan cv) {
        if (!cv.arrived) {
            Text.bar(p, "<red>\"Ho, ho, ho! Let me get to the tree first!\"");
            return;
        }
        if (!cv.gifted.add(p.getUniqueId())) {
            Text.bar(p, "<red>\"One each, " + Text.esc(p.getName()) + "! Merry Christmas!\"");
            return;
        }
        ColonyCommand.give(p, plugin.items().present());
        Text.send(p, "<red>🎅 \"Merry Christmas, " + Text.esc(p.getName()) + "!\"</red> <gray>He hands you a present. Right-click it to unwrap it.");
        Fx.sound(p, "minecraft:entity.player.levelup", 0.8f, 1.4f);
        p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 4, 0.3, 0.2, 0.3, 0);
    }

    /** Unwrapping a present. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUnwrap(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getItem();
        if (!Items.is(hand, Items.PRESENT)) return;
        e.setCancelled(true);
        if (e.getHand() == null) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItem(e.getHand());
        if (!Items.is(held, Items.PRESENT)) return;
        held.setAmount(held.getAmount() - 1);
        p.getInventory().setItem(e.getHand(), held.getAmount() <= 0 ? null : held);
        ItemStack gift = roll(ThreadLocalRandom.current());
        ColonyCommand.give(p, gift);
        boolean coal = gift.getType() == Material.COAL;
        Text.send(p, coal ? "<gray>You unwrap the present... a lump of <dark_gray>coal</dark_gray>. Somebody has been naughty."
                : "<green>You unwrap the present:</green> <white>" + gift.getAmount() + " " + Goods.nice(gift.getType().name()) + "</white><green>!");
        Fx.sound(p, coal ? "minecraft:entity.villager.no" : "minecraft:entity.item.pickup", 1f, coal ? 1f : 0.8f);
        p.getWorld().spawnParticle(coal ? Particle.SMOKE : Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1.2, 0), 12, 0.4, 0.4, 0.4, 0.01);
    }

    /** What's in a present. */
    static ItemStack roll(Random r) {
        int n = r.nextInt(100);
        if (n < 6) return new ItemStack(Material.COAL, 1);
        if (n < 20) return new ItemStack(Material.COOKIE, 16);
        if (n < 30) return new ItemStack(Material.EMERALD, 4 + r.nextInt(5));
        if (n < 40) return new ItemStack(Material.IRON_INGOT, 8 + r.nextInt(9));
        if (n < 48) return new ItemStack(Material.GOLD_INGOT, 4 + r.nextInt(5));
        if (n < 55) return new ItemStack(Material.DIAMOND, 1 + r.nextInt(2));
        if (n < 62) return new ItemStack(Material.CAKE, 1);
        if (n < 69) return new ItemStack(Material.GOLDEN_APPLE, 1 + r.nextInt(2));
        if (n < 75) return new ItemStack(Material.NAME_TAG, 1);
        if (n < 81) return new ItemStack(Material.SADDLE, 1);
        if (n < 87) return new ItemStack(Material.SNOWBALL, 16);
        if (n < 92) return new ItemStack(Material.LAPIS_LAZULI, 8 + r.nextInt(9));
        if (n < 96) return new ItemStack(Material.EXPERIENCE_BOTTLE, 8);
        // the rare one: a book of mending or unbreaking
        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
        if (book.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            if (r.nextBoolean()) meta.addStoredEnchant(Enchantment.MENDING, 1, true);
            else meta.addStoredEnchant(Enchantment.UNBREAKING, 3, true);
            book.setItemMeta(meta);
        }
        return book;
    }

    // ───────────── atmosphere ─────────────

    /** Every second: snow drifts round the trees of loaded colonies, and the star glitters. */
    public void tick() {
        seconds++;
        if (!season()) return;
        for (Colony c : plugin.colonies().list()) {
            if (!c.active || c.christmasStar == null || c.christmasYear != year) continue;
            World w = c.world();
            if (w == null || !c.christmasStar.loaded(w)) continue;
            Location star = c.christmasStar.center(w);
            boolean watched = false;
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(star) < 48 * 48) {
                    watched = true;
                    break;
                }
            }
            if (!watched) continue;
            w.spawnParticle(Particle.SNOWFLAKE, star.clone().add(0, -3, 0), 14, 3.5, 4, 3.5, 0.01);
            w.spawnParticle(Particle.END_ROD, star.clone().add(0, 0.8, 0), 2, 0.3, 0.3, 0.3, 0.01);
            if (seconds % 20 == 0) Fx.sound(star, "minecraft:block.note_block.chime", 0.6f, 0.9f + ThreadLocalRandom.current().nextFloat() * 0.6f);
        }
    }

    /** A child at the tree lobs a snowball at whoever is nearest (it hurts nobody). */
    public void snowball(Npc n, Colony c) {
        if (ThreadLocalRandom.current().nextInt(12) != 0) return;
        org.bukkit.entity.LivingEntity target = null;
        double bd = 12 * 12;
        for (org.bukkit.entity.Entity e : n.body.getNearbyEntities(10, 4, 10)) {
            if (!(e instanceof org.bukkit.entity.LivingEntity le) || e == n.body) continue;
            boolean friend = e instanceof Player || plugin.npcs().npc(e) != null;
            if (!friend || !n.body.hasLineOfSight(e)) continue;
            double d = e.getLocation().distanceSquared(n.loc());
            if (d < bd && d > 4) {
                bd = d;
                target = le;
            }
        }
        if (target == null) return;
        Location eye = n.body.getEyeLocation();
        org.bukkit.util.Vector dir = target.getEyeLocation().toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist < 0.1) return;
        dir.setY(dir.getY() + dist * 0.08);
        n.body.launchProjectile(org.bukkit.entity.Snowball.class, dir.normalize().multiply(1.1));
        n.body.swingMainHand();
        Fx.sound(eye, "minecraft:entity.snowball.throw", 0.6f, 1.2f);
    }

    /** For the command: how things stand. */
    public List<String> describe(Colony c) {
        List<String> out = new ArrayList<>();
        if (!season()) {
            out.add("<gray>It isn't the Christmas season (" + dates() + "). Come back then.");
            return out;
        }
        out.add("<green>🎄 It's the Christmas season</green> <gray>(" + dates() + ").");
        if (decorated(c)) {
            out.add("<gray>The tree stands at <white>" + c.christmasTree.x() + ", " + c.christmasTree.y() + ", " + c.christmasTree.z() + "</white>: +" + (int) CHEER
                    + " mood for everyone, carols at the tavern, and Father Christmas will call.");
            out.add(c.lastSantaDay < 0 ? "<gray>Father Christmas hasn't been yet." : "<gray>Father Christmas last came on day " + c.lastSantaDay + ".");
        } else {
            out.add("<gray>The colony has no Christmas tree. Plant a <green>Christmas Tree Sapling</green> on open ground in your land:");
            out.add("<gray>craft one from a spruce sapling, a gold nugget and red dye, or take the colony's free one with this command.");
        }
        return out;
    }

    /** The colony's free sapling, once a season. Returns an error, or null. */
    public String freeSapling(Player p, Colony c) {
        if (!season()) return "It isn't the Christmas season (" + dates() + ").";
        if (decorated(c)) return null;
        if (c.christmasSaplingYear == year()) return null;
        if (!plugin.access().can(p, c, Perm.BUILD)) return "You may not build in " + c.name + ".";
        c.christmasSaplingYear = year();
        ColonyCommand.give(p, plugin.items().christmasSapling());
        Text.send(p, "<green>Here is " + Text.esc(c.name) + "'s Christmas Tree Sapling.</green> <gray>Plant it on dirt or grass under open sky.");
        plugin.requestSave();
        return null;
    }
}
