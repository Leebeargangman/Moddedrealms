package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Research;
import com.colonysmp.gui.MerchantMenu;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Modifier;
import com.colonysmp.model.Perm;
import com.colonysmp.model.Policies;
import com.colonysmp.npc.Mover;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Keys;
import com.colonysmp.util.Text;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.TraderLlama;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Things that happen to a colony out of the blue: a festival, a run of good growing weather, a merchant's
 * caravan, refugees at the gate, a lucky strike in the mine, stray animals, a travelling scholar, storm damage.
 * Something happens every few days while members are online; what can happen depends on what the colony has.
 */
public final class Happenings implements Listener {

    public enum Kind {
        FESTIVAL("Festival", 3), HARVEST("Good growing weather", 3), CARAVAN("Merchant caravan", 4), REFUGEES("Refugees", 2),
        LUCKY_FIND("Lucky strike", 2), STRAYS("Stray animals", 2), SCHOLAR("Travelling scholar", 2), STORM("Storm damage", 2),
        /** Only in the Christmas season, and only where there's a tree. */
        SANTA("Father Christmas", 6);

        public final String display;
        final int weight;

        Kind(String display, int weight) {
            this.display = display;
            this.weight = weight;
        }

        public static Kind parse(String s) {
            if (s == null) return null;
            String u = s.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_');
            for (Kind k : values()) if (k.name().equals(u) || k.name().startsWith(u)) return k;
            if (u.contains("CHRISTMAS") || u.contains("FATHER")) return SANTA;
            return u.startsWith("MERCHANT") ? CARAVAN : null;
        }
    }

    /** Something a visiting merchant sells: an item, or a live animal handed over on a lead. */
    public static final class Offer {
        public final String name;
        public final Material icon;
        /** The goods handed over (null for livestock). */
        public final ItemStack item;
        /** The animal handed over on a lead (null for goods). */
        public final Class<? extends Animals> animal;
        public final long price;
        public int stock;

        Offer(String name, Material icon, ItemStack item, Class<? extends Animals> animal, long price, int stock) {
            this.name = name;
            this.icon = icon;
            this.item = item;
            this.animal = animal;
            this.price = price;
            this.stock = stock;
        }
    }

    /** A merchant visiting a colony, with a llama in tow. */
    public static final class Caravan {
        public final String colonyId;
        public final String name;
        public final WanderingTrader trader;
        final Mover mover;
        TraderLlama llama;
        public final List<Offer> offers = new ArrayList<>();
        boolean arrived, leaving;
        long leaveAt, goneBy;
        /** Father Christmas: he gives rather than sells, and makes for the tree. */
        public boolean santa;
        Location goal;
        /** Who has had their present this visit. */
        public final java.util.Set<UUID> gifted = new java.util.HashSet<>();

        Caravan(String colonyId, String name, WanderingTrader trader) {
            this.colonyId = colonyId;
            this.name = name;
            this.trader = trader;
            this.mover = new Mover(trader);
        }
    }

    private static final String[] MERCHANTS = {"Old Tobin", "Sera of the Road", "Halvard the Pedlar", "Mistress Wren", "Yusuf Far-Walker", "Dame Olwen", "Pip Three-Carts"};
    private final ColonySMP plugin;
    private final Map<String, Caravan> caravans = new HashMap<>();
    private final Map<UUID, Caravan> byEntity = new HashMap<>();
    /** Refugees waiting for an answer: colony id -> how many, and until when (ms). */
    private final Map<String, long[]> refugees = new HashMap<>();
    private int seconds;

    public Happenings(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public Caravan caravan(Colony c) {
        return caravans.get(c.id);
    }

    // ───────────── the day ─────────────

    public void daily(Colony c, long day) {
        Settings s = plugin.settings();
        if (!s.eventsEnabled || c.core == null || !c.anyMemberOnline()) return;
        // Father Christmas keeps his own calendar: when he's due, he comes, whatever else is going on
        if (eligible(c, Kind.SANTA, day) && fire(c, Kind.SANTA, day)) return;
        if (c.lastEventDay >= 0 && day - c.lastEventDay < s.eventMinDays) return;
        if (ThreadLocalRandom.current().nextDouble() >= s.eventChance) return;
        List<Kind> pool = new ArrayList<>();
        for (Kind k : Kind.values()) {
            if (k == Kind.SANTA || !eligible(c, k, day)) continue;
            int w = k.weight;
            if (k == Kind.FESTIVAL && c.bonus(Research.Effect.MOOD) > 0) w *= 2;
            if (k == Kind.CARAVAN) w = (int) Math.round(w * (1 + c.bonus(Research.Effect.VISITORS)));
            for (int i = 0; i < w; i++) pool.add(k);
        }
        if (pool.isEmpty()) return;
        Kind k = pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        if (fire(c, k, day)) c.lastEventDay = day;
    }

    private double averageMood(Colony c) {
        double mood = 0;
        int n = 0;
        for (Citizen ct : c.citizens.values()) {
            if (!ct.status.free()) continue;
            mood += ct.needs.mood;
            n++;
        }
        return n == 0 ? 0 : mood / n;
    }

    private Building staffedBy(Colony c, BuildingType type, Job job) {
        for (Building b : c.valid(type)) {
            for (Citizen ct : c.citizens.values()) if (ct.job == job && ct.workplace == b.id && ct.status.free()) return b;
        }
        return null;
    }

    /** Can this happen to this colony today? */
    public boolean eligible(Colony c, Kind k, long day) {
        World w = c.world();
        return switch (k) {
            case FESTIVAL -> c.population() >= 5 && averageMood(c) >= 55 && plugin.sim().finances().foodDays(c) >= 3
                    && (c.lastFestivalDay < 0 || day - c.lastFestivalDay >= (c.bonus(Research.Effect.MOOD) > 0 ? 4 : 7));
            case HARVEST -> !c.valid(BuildingType.FARM).isEmpty() && !c.boon("harvest", day);
            case CARAVAN -> c.active && !caravans.containsKey(c.id) && (c.lastCaravanDay < 0 || day - c.lastCaravanDay >= 3);
            case REFUGEES -> c.policies.recruitment != Policies.Recruitment.CLOSED && !refugees.containsKey(c.id)
                    && plugin.sim().population().freeBeds(c) >= 2 && plugin.sim().population().roomForOne(c) == null;
            case LUCKY_FIND -> staffedBy(c, BuildingType.MINE, Job.MINER) != null;
            case STRAYS -> c.active && strayPen(c, w) != null;
            case SCHOLAR -> c.researching != null && plugin.sim().academy().researchers(c) > 0 || plugin.sim().citizens().staffed(c, BuildingType.SCHOOL) != null;
            case STORM -> w != null && w.hasStorm() && c.buildings.size() >= 4;
            case SANTA -> c.active && plugin.christmas().decorated(c) && !caravans.containsKey(c.id)
                    && (c.lastSantaDay < 0 || day - c.lastSantaDay >= plugin.settings().santaDays);
        };
    }

    private Building strayPen(Colony c, World w) {
        if (w == null) return null;
        Building pen = staffedBy(c, BuildingType.ANIMAL_PEN, Job.ANIMAL_KEEPER);
        if (pen == null || pen.tiles.isEmpty() || pen.anchor == null || !pen.anchor.loaded(w)) return null;
        return pen;
    }

    /** Makes something happen now (also the admin command). False if it couldn't. */
    public boolean fire(Colony c, Kind k, long day) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        switch (k) {
            case FESTIVAL -> {
                c.lastFestivalDay = day;
                c.boons.put("festival", day);
                c.addModifier(new Modifier("festival", "Festival", 6, day + 2));
                c.addStability(2);
                for (Citizen ct : c.citizens.values()) if (ct.status.free()) ct.needs.social = Math.min(100, ct.needs.social + 25);
                if (plugin.christmas().season()) {
                    tell(c, ColonyEvent.Type.COLONY, day, "<red>✦ A Christmas feast!</red> <gray>The tables of " + Text.esc(c.name) + " are laid and nobody is in a hurry to work. There will be fireworks at the Town Hall at dusk.",
                            "The colony held a Christmas feast.", "minecraft:block.bell.use");
                } else {
                    tell(c, ColonyEvent.Type.COLONY, day, "<gold>✦ Festival!</gold> <gray>The people of " + Text.esc(c.name) + " have declared a feast day. Spirits are high, and there will be fireworks at the Town Hall at dusk.",
                            "The colony held a festival.", "minecraft:entity.firework_rocket.launch");
                }
            }
            case HARVEST -> {
                c.boons.put("harvest", day + 2);
                tell(c, ColonyEvent.Type.PRODUCTION, day, "<green>☀ Good growing weather.</green> <gray>Warm rain and long days: the fields give a third more for the next three days.",
                        "Good growing weather: the fields give a third more for three days.", "minecraft:weather.rain");
            }
            case CARAVAN -> {
                return summonCaravan(c, day, false);
            }
            case SANTA -> {
                return summonCaravan(c, day, true);
            }
            case REFUGEES -> {
                int n = 2 + r.nextInt(2);
                refugees.put(c.id, new long[]{n, System.currentTimeMillis() + 5 * 60_000L});
                c.log(ColonyEvent.Type.POPULATION, day, n + " refugees came to the gates asking for shelter.");
                for (Player p : c.onlineMembers()) {
                    Text.send(p, "<#c9a26b>" + n + " refugees stand at the gates of " + Text.esc(c.name) + ", driven from their homes.</#c9a26b> <gray>They need beds and food. "
                            + "<click:run_command:'/colony refugees accept'><green><bold>[Take them in]</bold></green></click> "
                            + "<click:run_command:'/colony refugees refuse'><red><bold>[Turn them away]</bold></red></click>");
                    Fx.sound(p, "minecraft:entity.wandering_trader.ambient", 0.9f, 0.8f);
                }
            }
            case LUCKY_FIND -> {
                Building mine = staffedBy(c, BuildingType.MINE, Job.MINER);
                String[] finds = {"diamond", "emerald", "raw_gold", "lapis_lazuli", "amethyst_shard", "redstone"};
                int[] most = {3, 5, 8, 12, 8, 16};
                int i = r.nextInt(finds.length);
                long got = Goods.known(finds[i]) ? c.storage.add(finds[i], 1 + r.nextInt(most[i])) : 0;
                if (got <= 0) return false;
                c.producedToday += got;
                String where = mine == null ? "the mine" : mine.label();
                tell(c, ColonyEvent.Type.PRODUCTION, day, "<aqua>⛏ A lucky strike!</aqua> <gray>The miners at " + Text.esc(where) + " broke into a pocket: <white>" + got + " " + Goods.get(finds[i]).name() + "</white> are in the stores.",
                        "The miners at " + where + " struck a pocket of " + Goods.get(finds[i]).name().toLowerCase() + " (" + got + ").", "minecraft:block.amethyst_block.chime");
            }
            case STRAYS -> {
                World w = c.world();
                Building pen = strayPen(c, w);
                if (pen == null) return false;
                List<Class<? extends Animals>> kinds = List.of(Cow.class, Sheep.class, Pig.class, Chicken.class);
                Class<? extends Animals> kind = kinds.get(r.nextInt(kinds.size()));
                int n = 0;
                for (int i = 0; i < 2; i++) {
                    BlockPos tile = pen.tiles.get(r.nextInt(pen.tiles.size()));
                    Location at = Mover.safeSpot(tile.center(w));
                    if (at == null || !pen.containsXZ(at.getX(), at.getZ(), 0)) continue;
                    try {
                        w.spawn(at, kind, CreatureSpawnEvent.SpawnReason.CUSTOM);
                        n++;
                    } catch (IllegalArgumentException ignored) {
                        // no room for it there
                    }
                }
                if (n == 0) return false;
                String what = kind.getSimpleName().toLowerCase() + (n == 1 ? "" : "s");
                tell(c, ColonyEvent.Type.PRODUCTION, day, "<green>" + n + " stray " + what + " wandered in from the wilds</green> <gray>and settled in " + Text.esc(pen.label()) + ".",
                        n + " stray " + what + " joined " + pen.label() + ".", "minecraft:entity.cow.ambient");
            }
            case SCHOLAR -> {
                if (c.researching != null && plugin.sim().academy().researchers(c) > 0) {
                    Research res = c.researching;
                    double hours = 3 + plugin.sim().academy().universityLevel(c) * 2;
                    tell(c, ColonyEvent.Type.COLONY, day, "<light_purple>A travelling scholar stopped at the University</light_purple> <gray>and spent the night arguing about " + res.display
                            + ". The researchers are " + Math.round(hours) + " hours further on.", "A travelling scholar helped the researchers with " + res.display + ".", "minecraft:item.book.page_turn");
                    plugin.sim().academy().boost(c, hours);
                } else {
                    int n = 0;
                    for (Citizen ct : c.citizens.values()) {
                        if (!ct.status.free()) continue;
                        if (ct.job == Job.STUDENT || plugin.sim().citizens().stage(ct) == LifeStage.CHILD) {
                            ct.education = Math.min(100, ct.education + 4);
                            n++;
                        }
                    }
                    if (n == 0) return false;
                    tell(c, ColonyEvent.Type.COLONY, day, "<light_purple>A travelling scholar gave a lesson at the school.</light_purple> <gray>" + n + " pupil" + (n == 1 ? "" : "s") + " learned a great deal.",
                            "A travelling scholar taught at the school.", "minecraft:item.book.page_turn");
                }
            }
            case STORM -> {
                List<Building> all = new ArrayList<>();
                for (Building b : c.buildings.values()) if (b.type != BuildingType.TOWN_HALL && b.valid && b.condition > 40) all.add(b);
                if (all.isEmpty()) return false;
                Building b = all.get(r.nextInt(all.size()));
                b.condition = Math.max(20, b.condition - (15 + r.nextInt(16)));
                tell(c, ColonyEvent.Type.CONSTRUCTION, day, "<red>⛈ The storm damaged " + Text.esc(b.label()) + "</red> <gray>(condition " + Math.round(b.condition) + "%). An engineer can mend it; a building in disrepair works worse.",
                        "A storm damaged " + b.label() + " (condition " + Math.round(b.condition) + "%).", "minecraft:entity.lightning_bolt.thunder");
            }
        }
        plugin.requestSave();
        return true;
    }

    private void tell(Colony c, ColonyEvent.Type type, long day, String chat, String log, String sound) {
        c.log(type, day, log);
        for (Player p : c.onlineMembers()) {
            Text.send(p, chat);
            Fx.sound(p, sound, 0.8f, 1f);
        }
    }

    // ───────────── refugees ─────────────

    public String refugees(Player by, Colony c, boolean accept) {
        long[] waiting = refugees.get(c.id);
        if (waiting == null) return "No refugees are waiting at the gates.";
        if (!plugin.access().can(by, c, Perm.MANAGE_CITIZENS)) return "You may not take in newcomers for " + Text.esc(c.name) + ".";
        refugees.remove(c.id);
        long day = plugin.sim().day(c);
        if (!accept) {
            c.reputation = Math.max(0, c.reputation - 2);
            c.log(ColonyEvent.Type.POPULATION, day, by.getName() + " turned the refugees away.");
            for (Player p : c.onlineMembers()) Text.send(p, "<gray>" + Text.esc(by.getName()) + " turned the refugees away. They trudge on down the road.");
            return null;
        }
        int taken = 0;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < waiting[0]; i++) {
            if (plugin.sim().population().roomForOne(c) != null) break;
            Citizen ct = plugin.sim().population().create(c, Job.UNEMPLOYED, false);
            // they arrive with nothing, hungry and grateful
            ct.needs.satiety = 35;
            ct.needs.mood = 70;
            ct.wallet = 0;
            ct.remember(day, "Arrived as a refugee and was taken in by " + by.getName());
            plugin.sim().housing().assign(c);
            plugin.sim().jobs().autoAssign(c, ct);
            names.add(ct.name);
            taken++;
        }
        if (taken == 0) return "There's no room for them: " + plugin.sim().population().roomForOne(c);
        c.reputation = Math.min(100, c.reputation + 2 * taken);
        c.addStability(1);
        c.log(ColonyEvent.Type.POPULATION, day, taken + " refugees were taken in: " + String.join(", ", names) + ".");
        for (Player p : c.onlineMembers()) {
            Text.send(p, "<green>" + taken + " refugee" + (taken == 1 ? "" : "s") + " settled in " + Text.esc(c.name) + ":</green> <white>" + Text.esc(String.join(", ", names)) + "</white>."
                    + (taken < waiting[0] ? " <gray>There was no room for the rest." : ""));
        }
        plugin.milestones().check(c);
        plugin.requestSave();
        return null;
    }

    // ───────────── the merchant's caravan ─────────────

    /** Admin and events: a merchant (or Father Christmas) sets out for the colony now. */
    public boolean summonCaravan(Colony c, long day, boolean santa) {
        World w = c.world();
        Location core = c.coreLocation();
        if (w == null || core == null || !c.active || caravans.containsKey(c.id)) return false;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Location at = null;
        var tr = c.territory;
        for (int i = 0; i < 16 && at == null; i++) {
            int x, z;
            switch (r.nextInt(4)) {
                case 0 -> {
                    x = tr.minX() - 3;
                    z = r.nextInt(tr.minZ(), tr.maxZ() + 1);
                }
                case 1 -> {
                    x = tr.maxX() + 3;
                    z = r.nextInt(tr.minZ(), tr.maxZ() + 1);
                }
                case 2 -> {
                    z = tr.minZ() - 3;
                    x = r.nextInt(tr.minX(), tr.maxX() + 1);
                }
                default -> {
                    z = tr.maxZ() + 3;
                    x = r.nextInt(tr.minX(), tr.maxX() + 1);
                }
            }
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Location l = Mover.safeSpot(new Location(w, x + 0.5, w.getHighestBlockYAt(x, z) + 1, z + 0.5));
            if (l != null && !l.getBlock().isLiquid()) at = l;
        }
        if (at == null) at = plugin.npcs().spotNear(core, 6, 12);
        String name = santa ? "Father Christmas" : MERCHANTS[r.nextInt(MERCHANTS.length)];
        WanderingTrader body;
        try {
            body = w.spawn(at, WanderingTrader.class, CreatureSpawnEvent.SpawnReason.CUSTOM, wt -> {
                wt.setPersistent(false);
                wt.setRemoveWhenFarAway(false);
                wt.setAware(false);
                wt.setCollidable(false);
                wt.setInvulnerable(true);
                wt.setDespawnDelay(0);
                wt.setCanDrinkMilk(false);
                wt.setCanDrinkPotion(false);
                wt.getPersistentDataContainer().set(Keys.MERCHANT, PersistentDataType.STRING, c.id);
                AttributeInstance fr = wt.getAttribute(Attribute.FOLLOW_RANGE);
                if (fr != null) fr.setBaseValue(96);
                wt.customName(Text.mm(santa ? "<red>🎅 <white>Father Christmas" : "<gold>⚖ <white>" + name + " <gray>— Merchant"));
                wt.setCustomNameVisible(true);
            });
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (body == null || !body.isValid()) return false;
        Caravan cv = new Caravan(c.id, name, body);
        cv.santa = santa;
        if (santa) cv.goal = plugin.christmas().treeLocation(c);
        else stock(cv);
        try {
            Location side = plugin.npcs().spotNear(at, 1, 3);
            cv.llama = w.spawn(side, TraderLlama.class, CreatureSpawnEvent.SpawnReason.CUSTOM, l -> {
                l.setPersistent(false);
                l.setRemoveWhenFarAway(false);
                l.setInvulnerable(true);
                l.getPersistentDataContainer().set(Keys.MERCHANT, PersistentDataType.STRING, c.id);
                if (santa) {
                    l.customName(Text.mm("<#c9a26b>Reindeer"));
                    l.setCustomNameVisible(true);
                }
            });
            if (cv.llama != null && cv.llama.isValid()) cv.llama.setLeashHolder(body);
        } catch (IllegalArgumentException | IllegalStateException e) {
            cv.llama = null;
        }
        caravans.put(c.id, cv);
        byEntity.put(body.getUniqueId(), cv);
        if (santa) {
            c.lastSantaDay = day;
            c.log(ColonyEvent.Type.COLONY, day, "Father Christmas came to the colony.");
            for (Player p : c.onlineMembers()) {
                Text.title(p, "<red>🎅 Ho, ho, ho!", "<gray>Father Christmas is coming to " + Text.esc(c.name), 10, 70, 20);
                Text.send(p, "<red>🎅 Sleigh bells!</red> <gray>Father Christmas is on his way to the Christmas tree. Right-click him for your present.");
                Fx.sound(p, "minecraft:block.bell.use", 1f, 1.4f);
            }
            plugin.requestSave();
            return true;
        }
        c.lastCaravanDay = day;
        c.log(ColonyEvent.Type.ECONOMY, day, "The merchant " + name + " came to trade.");
        for (Player p : c.onlineMembers()) {
            Text.send(p, "<gold>⚖ A merchant's caravan is coming up the road.</gold> <gray><white>" + name + "</white> will set out their wares at the Town Hall: livestock on the lead, seeds and rarities. Right-click them to trade.");
            Fx.sound(p, "minecraft:entity.llama.ambient", 0.9f, 1f);
        }
        plugin.requestSave();
        return true;
    }

    private static ItemStack stack(Material m, int n) {
        return new ItemStack(m, n);
    }

    private static void animal(List<Offer> to, String name, Material icon, Class<? extends Animals> type, long price) {
        to.add(new Offer(name, icon, null, type, price, 1 + ThreadLocalRandom.current().nextInt(3)));
    }

    private static void goods(List<Offer> to, Material m, int n, long price) {
        to.add(new Offer(n + " " + Goods.nice(m.name()), m, stack(m, n), null, price, 1 + ThreadLocalRandom.current().nextInt(3)));
    }

    /** What this merchant has brought: a few animals and a handful of things the colony can't make. */
    private void stock(Caravan cv) {
        List<Offer> beasts = new ArrayList<>();
        animal(beasts, "A cow", Material.COW_SPAWN_EGG, Cow.class, 60);
        animal(beasts, "A sheep", Material.SHEEP_SPAWN_EGG, Sheep.class, 50);
        animal(beasts, "A pig", Material.PIG_SPAWN_EGG, Pig.class, 50);
        animal(beasts, "A chicken", Material.CHICKEN_SPAWN_EGG, Chicken.class, 30);
        animal(beasts, "A horse", Material.HORSE_SPAWN_EGG, Horse.class, 140);
        animal(beasts, "A bee", Material.BEE_SPAWN_EGG, Bee.class, 70);
        List<Offer> wares = new ArrayList<>();
        goods(wares, Material.SUGAR_CANE, 16, 20);
        goods(wares, Material.OAK_SAPLING, 8, 12);
        goods(wares, Material.BONE_MEAL, 16, 16);
        goods(wares, Material.BOOK, 4, 40);
        goods(wares, Material.PAPER, 16, 24);
        goods(wares, Material.LAPIS_LAZULI, 8, 48);
        goods(wares, Material.GLASS_BOTTLE, 8, 16);
        goods(wares, Material.MOSSY_COBBLESTONE, 4, 12);
        goods(wares, Material.BEEHIVE, 1, 40);
        goods(wares, Material.NOTE_BLOCK, 1, 20);
        goods(wares, Material.BOOKSHELF, 4, 60);
        goods(wares, Material.ENCHANTING_TABLE, 1, 220);
        goods(wares, Material.SADDLE, 1, 60);
        goods(wares, Material.NAME_TAG, 1, 40);
        goods(wares, Material.LEAD, 2, 16);
        goods(wares, Material.COCOA_BEANS, 8, 16);
        goods(wares, Material.PUMPKIN_SEEDS, 4, 8);
        goods(wares, Material.MELON_SEEDS, 4, 8);
        goods(wares, Material.SWEET_BERRIES, 16, 12);
        goods(wares, Material.DIAMOND, 1, 90);
        goods(wares, Material.EXPERIENCE_BOTTLE, 4, 60);
        goods(wares, Material.POPPY, 8, 8);
        Collections.shuffle(beasts);
        Collections.shuffle(wares);
        cv.offers.addAll(beasts.subList(0, 3));
        cv.offers.addAll(wares.subList(0, 7));
    }

    /** A player buys one of an offer. Returns an error, or null. */
    public String buy(Player p, Caravan cv, Offer o) {
        if (!cv.trader.isValid() || cv.leaving) return cv.name + " has packed up.";
        if (o.stock <= 0) return "Sold out.";
        if (p.getWorld() != cv.trader.getWorld() || p.getLocation().distanceSquared(cv.trader.getLocation()) > 10 * 10) return "You're too far from " + cv.name + ".";
        java.util.Map<String, Long> paid = plugin.economy().chargeItems(p, o.price);
        if (paid == null) return "That costs " + Text.value(o.price) + ". You have " + plugin.economy().money(p) + ".";
        o.stock--;
        if (o.animal != null) {
            Location at = plugin.npcs().spotNear(p.getLocation(), 1, 2);
            try {
                Animals a = p.getWorld().spawn(at, o.animal, CreatureSpawnEvent.SpawnReason.CUSTOM);
                // handed over on a lead, to be walked home
                try {
                    a.setLeashHolder(p);
                } catch (IllegalStateException | IllegalArgumentException ignored) {
                    // an animal that can't be led follows nobody; it's theirs all the same
                }
            } catch (IllegalArgumentException e) {
                o.stock++;
                plugin.economy().give(p, paid);
                return "There's no room here to hand the animal over.";
            }
            Text.send(p, "<gold>" + Text.esc(cv.name) + "</gold> <gray>hands you a lead: <white>" + o.name.toLowerCase() + "</white> for " + Text.value(o.price) + ". Walk it home before you let go.");
        } else {
            com.colonysmp.command.ColonyCommand.give(p, o.item.clone());
            Text.send(p, "<gray>Bought <white>" + o.name + "</white> from " + Text.esc(cv.name) + " for " + Text.value(o.price) + ".");
        }
        Fx.sound(p, "minecraft:entity.wandering_trader.yes", 1f, 1f);
        return null;
    }

    // ───────────── ticking ─────────────

    /** Every tick: the merchant walks. */
    public void move(long now) {
        for (Caravan cv : new ArrayList<>(caravans.values())) {
            if (cv.trader.isValid()) cv.mover.tick(plugin.npcs(), now);
        }
    }

    /** Every second: caravans arrive, wait and leave; refugees lose patience; festivals light the sky. */
    public void tick() {
        seconds++;
        long now = System.currentTimeMillis();
        Settings s = plugin.settings();
        for (Caravan cv : new ArrayList<>(caravans.values())) {
            Colony c = plugin.colonies().get(cv.colonyId);
            if (c == null || !cv.trader.isValid()) {
                remove(cv);
                continue;
            }
            Location core = cv.goal != null && cv.goal.getWorld() == cv.trader.getWorld() ? cv.goal : c.coreLocation();
            if (cv.llama != null && cv.llama.isValid() && !cv.llama.isLeashed()) {
                // the lead slipped: catch up and take it again
                if (cv.llama.getLocation().distanceSquared(cv.trader.getLocation()) > 64) cv.llama.teleport(plugin.npcs().spotNear(cv.trader.getLocation(), 1, 3));
                try {
                    cv.llama.setLeashHolder(cv.trader);
                } catch (IllegalStateException | IllegalArgumentException ignored) {
                    // try again next second
                }
            }
            if (cv.leaving) {
                if (now > cv.goneBy || c.territory.distanceTo(cv.trader.getX(), cv.trader.getZ()) > 8) remove(cv);
            } else if (!cv.arrived) {
                if (core == null || !c.active) {
                    remove(cv);
                } else if (cv.mover.near(core, 5)) {
                    cv.mover.stop();
                    cv.arrived = true;
                    cv.leaveAt = now + s.caravanMinutes * 60_000L;
                    if (cv.santa) {
                        cv.leaveAt = now + Math.max(3, s.caravanMinutes / 2) * 60_000L;
                        plugin.christmas().arrived(c, cv);
                        continue;
                    }
                    for (Player p : c.onlineMembers()) {
                        Text.send(p, "<gold>⚖ " + Text.esc(cv.name) + " has set out their wares at the Town Hall.</gold> <gray>They stay " + s.caravanMinutes + " minutes. Right-click them to trade.");
                    }
                } else {
                    cv.mover.moveTo(core, s.walkSpeed * 0.85, 4);
                }
            } else {
                if (core != null && !cv.mover.near(core, 7)) cv.mover.moveTo(core, s.walkSpeed * 0.8, 4);
                if (now > cv.leaveAt || !c.active) leave(c, cv);
            }
        }
        refugees.entrySet().removeIf(e -> {
            if (now < e.getValue()[1]) return false;
            Colony c = plugin.colonies().get(e.getKey());
            if (c != null) for (Player p : c.onlineMembers()) Text.send(p, "<gray>The refugees waited at the gates, then moved on.");
            return true;
        });
        if (seconds % 3 == 0) fireworks();
    }

    private void leave(Colony c, Caravan cv) {
        cv.leaving = true;
        cv.goneBy = System.currentTimeMillis() + 90_000;
        Location here = cv.trader.getLocation();
        double cx = c.territory.centerX(), cz = c.territory.centerZ();
        double dx = here.getX() - cx, dz = here.getZ() - cz;
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        double reach = Math.max(c.territory.maxX() - c.territory.minX(), c.territory.maxZ() - c.territory.minZ()) / 2.0 + 14;
        Location out = Mover.safeSpot(new Location(here.getWorld(), cx + dx / len * reach, here.getY(), cz + dz / len * reach));
        if (out == null) out = Mover.safeSpot(here.clone().add(dx / len * 20, 0, dz / len * 20));
        if (out != null) cv.mover.moveTo(out, plugin.settings().walkSpeed, 2);
        for (Player p : c.onlineMembers()) {
            Text.send(p, cv.santa ? "<red>🎅 \"Merry Christmas to all!\"</red> <gray>Father Christmas leads his reindeer away down the road."
                    : "<gray>" + Text.esc(cv.name) + " packs up and takes to the road again.");
        }
        for (Player p : cv.trader.getWorld().getPlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof MerchantMenu) p.closeInventory();
        }
    }

    private void remove(Caravan cv) {
        caravans.remove(cv.colonyId, cv);
        byEntity.remove(cv.trader.getUniqueId());
        if (cv.llama != null && cv.llama.isValid()) {
            cv.llama.setLeashHolder(null);
            cv.llama.remove();
        }
        if (cv.trader.isValid()) cv.trader.remove();
    }

    /** On a feast day, rockets go up over the Town Hall for a while after dusk. */
    private void fireworks() {
        for (Colony c : plugin.colonies().list()) {
            if (!c.active || c.boons.isEmpty()) continue;
            World w = c.world();
            Location core = c.coreLocation();
            if (w == null || core == null || !c.boon("festival", plugin.sim().day(c))) continue;
            long t = w.getTime();
            if (t < 12500 || t > 14200) continue;
            ThreadLocalRandom r = ThreadLocalRandom.current();
            Location at = core.clone().add(r.nextInt(-6, 7), 10 + r.nextInt(8), r.nextInt(-6, 7));
            Color[] colors = plugin.christmas().season() ? new Color[]{Color.RED, Color.GREEN, Color.WHITE, Color.RED, Color.LIME}
                    : new Color[]{Color.RED, Color.YELLOW, Color.AQUA, Color.LIME, Color.FUCHSIA, Color.ORANGE, Color.WHITE};
            FireworkEffect fx = FireworkEffect.builder().with(r.nextBoolean() ? FireworkEffect.Type.BALL_LARGE : FireworkEffect.Type.STAR)
                    .withColor(colors[r.nextInt(colors.length)]).withFade(colors[r.nextInt(colors.length)]).flicker(r.nextBoolean()).trail(true).build();
            try {
                Firework fw = w.spawn(at, Firework.class, CreatureSpawnEvent.SpawnReason.CUSTOM, f -> {
                    FireworkMeta meta = f.getFireworkMeta();
                    meta.addEffect(fx);
                    meta.setPower(0);
                    f.setFireworkMeta(meta);
                    f.getPersistentDataContainer().set(Keys.MERCHANT, PersistentDataType.STRING, "festival");
                });
                // high above the roofs, at once
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (fw.isValid()) fw.detonate();
                }, 2L);
            } catch (IllegalArgumentException ignored) {
                // no fireworks tonight
            }
        }
    }

    public void colonyRemoved(Colony c) {
        Caravan cv = caravans.get(c.id);
        if (cv != null) remove(cv);
        refugees.remove(c.id);
    }

    public void shutdown() {
        for (Caravan cv : new ArrayList<>(caravans.values())) remove(cv);
        refugees.clear();
    }

    /** For the status command: what's going on today. */
    public List<String> describe(Colony c) {
        List<String> out = new ArrayList<>();
        long day = plugin.sim().day(c);
        Map<String, String> names = new LinkedHashMap<>();
        names.put("festival", "<gold>A festival is under way: fireworks at dusk.");
        names.put("harvest", "<green>Good growing weather: fields give a third more.");
        for (Map.Entry<String, String> e : names.entrySet()) {
            if (!c.boon(e.getKey(), day)) continue;
            long left = c.boons.get(e.getKey()) - day;
            out.add(e.getValue() + (left > 0 ? " <gray>(" + left + " more day" + (left == 1 ? "" : "s") + ")" : " <gray>(today)"));
        }
        Caravan cv = caravans.get(c.id);
        if (cv != null && !cv.leaving) {
            out.add(cv.santa ? "<red>🎅 Father Christmas is " + (cv.arrived ? "at the Christmas tree, handing out presents." : "on his way to the Christmas tree.")
                    : "<gold>The merchant " + Text.esc(cv.name) + " is " + (cv.arrived ? "trading at the Town Hall." : "on the way to the Town Hall."));
        }
        long[] waiting = refugees.get(c.id);
        if (waiting != null) {
            out.add("<#c9a26b>" + waiting[0] + " refugees wait at the gates.</#c9a26b> <click:run_command:'/colony refugees accept'><green>[Take them in]</green></click> "
                    + "<click:run_command:'/colony refugees refuse'><red>[Turn them away]</red></click>");
        }
        if (out.isEmpty()) out.add("<gray>Nothing out of the ordinary today.");
        return out;
    }

    // ───────────── events ─────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEntityEvent e) {
        Entity clicked = e.getRightClicked();
        Caravan cv = byEntity.get(clicked.getUniqueId());
        if (cv == null) {
            // a merchant or llama left over from a crash, or the llama itself
            if (clicked.getPersistentDataContainer().has(Keys.MERCHANT, PersistentDataType.STRING)) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (cv.leaving) {
            Text.bar(e.getPlayer(), "<gray>" + Text.esc(cv.name) + " has packed up for the road.");
            return;
        }
        if (cv.santa) {
            plugin.christmas().gift(e.getPlayer(), cv);
            return;
        }
        new MerchantMenu(plugin, e.getPlayer(), cv).open();
    }

    /** The merchant's llama keeps its lead, and festival rockets hurt nobody. */
    @EventHandler(ignoreCancelled = true)
    public void onUnleash(EntityUnleashEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(Keys.MERCHANT, PersistentDataType.STRING)) e.setDropLeash(false);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFirework(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Firework f && f.getPersistentDataContainer().has(Keys.MERCHANT, PersistentDataType.STRING)) e.setCancelled(true);
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (!(en instanceof LivingEntity) || !en.getPersistentDataContainer().has(Keys.MERCHANT, PersistentDataType.STRING)) continue;
            if (byEntity.containsKey(en.getUniqueId())) continue;
            boolean ours = false;
            for (Caravan cv : caravans.values()) if (cv.llama == en) ours = true;
            if (!ours) en.remove();
        }
    }
}
