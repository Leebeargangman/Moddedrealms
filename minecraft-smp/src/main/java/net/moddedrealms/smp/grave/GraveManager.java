package net.moddedrealms.smp.grave;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Skull;
import org.bukkit.block.data.Rotatable;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/**
 * Graves: when a player dies, their drops and XP go into a player-head grave where they fell instead of spilling
 * on the ground. Only the owner can open it at first; after {@code unlock-minutes} anyone can. Clicking it puts
 * every item back in the slot it came from. Graves survive restarts (graves.yml) and are protected from
 * explosions, pistons, water, endermen and breaking.
 */
public final class GraveManager implements Listener, CommandExecutor, TabCompleter {
    private static final BlockFace[] ROTATIONS = {
            BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST, BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST,
            BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST,
            BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST,
            BlockFace.EAST, BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST};

    private final SmpPlugin plugin;
    private final NamespacedKey key;
    private final File file;
    private final Map<Integer, Grave> graves = new LinkedHashMap<>();
    /** block key "world:x:y:z" -> grave, for the protection listeners. */
    private final Map<String, Grave> byBlock = new HashMap<>();
    private final Map<UUID, Integer> justDied = new HashMap<>();
    private final Map<UUID, Long> clickCooldown = new HashMap<>();
    private final Set<Integer> announcedUnlock = new HashSet<>();
    private final Object writeLock = new Object();
    private long saveVersion, writtenVersion;
    private int nextId = 1;
    private BukkitTask task;
    private List<int[]> offsets = List.of();

    // settings
    private boolean enabled, storeXp, hologram;
    private long unlockMs, expireMs;
    private int radius;
    private Set<String> disabledWorlds = Set.of();

    public GraveManager(SmpPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "grave");
        this.file = new File(plugin.getDataFolder(), "graves.yml");
        loadSettings();
        load();
    }

    public void loadSettings() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("graves.enabled", true);
        unlockMs = Math.max(0, c.getLong("graves.unlock-minutes", 15)) * 60_000L;
        expireMs = Math.max(0, c.getLong("graves.expire-minutes", 0)) * 60_000L;
        storeXp = c.getBoolean("graves.store-xp", true);
        hologram = c.getBoolean("graves.hologram", true);
        int r = Math.max(1, Math.min(8, c.getInt("graves.search-radius", 4)));
        disabledWorlds = new HashSet<>();
        for (String w : c.getStringList("graves.disabled-worlds")) disabledWorlds.add(w.toLowerCase(Locale.ROOT));
        if (r != radius) {
            radius = r;
            List<int[]> list = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++)
                for (int dy = -r; dy <= r; dy++)
                    for (int dz = -r; dz <= r; dz++) list.add(new int[]{dx, dy, dz});
            // Closest first; at equal distance prefer above the death spot (out of lava pools and holes).
            list.sort(Comparator.<int[]>comparingInt(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2]).thenComparingInt(o -> -o[1]));
            offsets = list;
        }
        if (!hologram) graves.values().forEach(g -> { if (g.label != null) g.label.remove(); g.label = null; });
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 40L);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (Grave g : graves.values()) removeEntities(g);
        saveNow();
    }

    // ------------------------------------------------------------------ death

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (!enabled || e.getKeepInventory() || !p.hasPermission("mrsmp.graves")) return;
        if (disabledWorlds.contains(p.getWorld().getName().toLowerCase(Locale.ROOT))) return;
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack it : e.getDrops()) if (it != null && !it.getType().isAir() && it.getAmount() > 0) drops.add(it.clone());
        if (drops.isEmpty()) return;

        Block spot = findSpot(p.getLocation());
        if (spot == null) {
            p.sendMessage(SmpPlugin.mm("<red>☠ There was no room for a grave here, so your items dropped on the ground."));
            return;
        }

        Grave g = new Grave(nextId++, p.getUniqueId(), p.getName(), spot.getWorld().getName(), spot.getX(), spot.getY(), spot.getZ(), System.currentTimeMillis());
        // Remember which slot each drop came from so opening the grave puts armour back on and the hotbar back in place.
        ItemStack[] contents = p.getInventory().getContents();
        boolean[] used = new boolean[contents.length];
        for (ItemStack d : drops) {
            int slot = -1;
            for (int i = 0; i < contents.length; i++) {
                if (!used[i] && contents[i] != null && contents[i].equals(d)) { slot = i; used[i] = true; break; }
            }
            g.items.add(new Grave.Stored(slot, d));
        }
        if (storeXp) {
            g.xp = Math.max(0, e.getDroppedExp());
            e.setDroppedExp(0);
        }
        Component dm = e.deathMessage();
        if (dm != null) {
            String s = PlainTextComponentSerializer.plainText().serialize(dm);
            g.cause = s.length() > 100 ? s.substring(0, 100) : s;
        }
        e.getDrops().clear();

        graves.put(g.id, g);
        byBlock.put(blockKey(g), g);
        placeHead(g, spot, p.getLocation().getYaw(), p);
        if (g.loaded()) spawnEntities(g);
        justDied.put(p.getUniqueId(), g.id);
        save();

        p.sendMessage(SmpPlugin.mm("<gold>☠ <yellow>Your <white>" + g.itemCount() + "</white> items"
                + (g.xp > 0 ? " and <white>" + g.xp + "</white> XP" : "") + " are safe in a grave at <white>" + g.coords()
                + "</white>." + lockText()));
        plugin.getLogger().info("Grave #" + g.id + " for " + p.getName() + " at " + g.world + " " + g.coords()
                + " (" + g.items.size() + " stacks, " + g.xp + " xp)");
    }

    private String lockText() {
        if (unlockMs <= 0) return " <gray>Only you can open it.";
        return " <gray>Only you can open it for the next " + (unlockMs / 60_000L) + " minutes.";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Integer id = justDied.remove(e.getPlayer().getUniqueId());
        if (id == null) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Grave g = graves.get(id);
            if (g == null || !p.isOnline()) return;
            p.sendMessage(SmpPlugin.mm("<gold>☠ <yellow>Your grave is at <white>" + g.coords() + "</white>" + where(p, g)
                    + ". <gray>Right-click it to get everything back. <dark_gray>(/graves)"));
        }, 20L);
    }

    private Block findSpot(Location death) {
        World w = death.getWorld();
        int min = w.getMinHeight(), max = w.getMaxHeight() - 1;
        int bx = death.getBlockX(), bz = death.getBlockZ();
        int by = death.getBlockY();
        if (death.getY() < min + 1) {
            // Fell into the void: the first standable spot in that column, or a single obsidian block to rest on.
            for (int y = min + 1; y < max; y++) {
                Block b = w.getBlockAt(bx, y, bz);
                if (free(b, false) && b.getRelative(BlockFace.DOWN).getType().isSolid()) return b;
            }
            int y = Math.max(min + 1, Math.min(max - 1, w.getEnvironment() == World.Environment.THE_END ? 60 : w.getSeaLevel()));
            Block b = w.getBlockAt(bx, y, bz);
            if (free(b, true)) {
                Block below = b.getRelative(BlockFace.DOWN);
                if (below.getType().isAir()) below.setType(Material.OBSIDIAN, false);
                return b;
            }
            by = y;
        }
        by = Math.max(min, Math.min(max, by));
        for (int pass = 0; pass < 2; pass++) {
            for (int[] o : offsets) {
                int y = by + o[1];
                if (y < min || y > max) continue;
                Block b = w.getBlockAt(bx + o[0], y, bz + o[2]);
                if (free(b, pass == 1)) return b;
            }
        }
        return null;
    }

    private boolean free(Block b, boolean liquidOk) {
        if (byBlock.containsKey(blockKey(b))) return false;
        Material m = b.getType();
        if (m.isAir()) return true;
        if (b.isLiquid()) return liquidOk;
        return b.isReplaceable() && m != Material.PLAYER_HEAD && m != Material.PLAYER_WALL_HEAD;
    }

    private void placeHead(Grave g, Block b, float yaw, Player online) {
        b.setType(Material.PLAYER_HEAD, false);
        if (b.getBlockData() instanceof Rotatable r) {
            r.setRotation(ROTATIONS[Math.floorMod(Math.round(yaw / 22.5f), 16)]);
            b.setBlockData(r, false);
        }
        if (b.getState() instanceof Skull s) {
            s.setPlayerProfile(online != null ? online.getPlayerProfile() : Bukkit.createProfile(g.owner, g.ownerName));
            s.update(true, false);
        }
    }

    // ------------------------------------------------------------------ opening

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.LEFT_CLICK_BLOCK)) return;
        Grave g = byBlock.get(blockKey(b));
        if (g == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) open(e.getPlayer(), g);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Grave g = graveOf(e.getRightClicked());
        if (g == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) open(e.getPlayer(), g);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(EntityDamageByEntityEvent e) {
        Grave g = graveOf(e.getEntity());
        if (g == null) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) open(p, g);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        Grave g = byBlock.get(blockKey(e.getBlock()));
        if (g == null) return;
        e.setCancelled(true);
        open(e.getPlayer(), g);
    }

    private void open(Player p, Grave g) {
        if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || !graves.containsKey(g.id)) return;
        long now = System.currentTimeMillis();
        Long last = clickCooldown.get(p.getUniqueId());
        if (last != null && now - last < 400) return;
        clickCooldown.put(p.getUniqueId(), now);

        boolean own = p.getUniqueId().equals(g.owner);
        boolean admin = p.hasPermission("mrsmp.graves.admin");
        if (!own && !unlocked(g, now) && !admin) {
            String when = unlockMs <= 0 ? "Only they can open it." : "It opens to everyone in <white>" + duration(g.created + unlockMs - now) + "</white>.";
            p.sendMessage(SmpPlugin.mm("<red>☠ This is <white>" + g.ownerName + "</white>'s grave. <gray>" + when));
            p.playSound(p.getLocation(), Sound.BLOCK_CHEST_LOCKED, 0.8f, 1f);
            return;
        }
        int count = g.itemCount();
        removeGrave(g);
        int dropped = own ? restore(p, g.items) : give(p, g.items);
        if (g.xp > 0) p.giveExp(g.xp, true);

        Location c = g.center();
        if (c != null) {
            c.getWorld().spawnParticle(Particle.SOUL, c.clone().add(0, 0.5, 0), 25, 0.3, 0.4, 0.3, 0.02);
            c.getWorld().playSound(c, Sound.BLOCK_ENDER_CHEST_OPEN, 0.8f, 0.9f);
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.2f);
        String overflow = dropped > 0 ? " <gray>(" + dropped + " didn't fit and dropped at your feet)" : "";
        if (own) {
            p.sendMessage(SmpPlugin.mm("<green>☠ You got your <white>" + count + "</white> items" + (g.xp > 0 ? " and <white>" + g.xp + "</white> XP" : "") + " back." + overflow));
        } else {
            p.sendMessage(SmpPlugin.mm("<yellow>☠ You opened <white>" + g.ownerName + "</white>'s grave: <white>" + count + "</white> items." + overflow));
            Player o = Bukkit.getPlayer(g.owner);
            if (o != null) o.sendMessage(SmpPlugin.mm("<red>☠ <white>" + p.getName() + "</white> opened your grave at " + g.coords() + "."));
        }
        plugin.getLogger().info("Grave #" + g.id + " (" + g.ownerName + ") opened by " + p.getName());
    }

    /** Owner: back into the original slots where they're empty, the rest anywhere. Returns how many stacks dropped. */
    private int restore(Player p, List<Grave.Stored> items) {
        PlayerInventory inv = p.getInventory();
        List<ItemStack> rest = new ArrayList<>();
        for (Grave.Stored s : items) {
            ItemStack cur = s.slot() >= 0 && s.slot() < inv.getSize() ? inv.getItem(s.slot()) : null;
            if (s.slot() >= 0 && s.slot() < inv.getSize() && (cur == null || cur.getType().isAir())) inv.setItem(s.slot(), s.item());
            else rest.add(s.item());
        }
        return addOrDrop(p, rest);
    }

    private int give(Player p, List<Grave.Stored> items) {
        List<ItemStack> all = new ArrayList<>();
        for (Grave.Stored s : items) all.add(s.item());
        return addOrDrop(p, all);
    }

    private int addOrDrop(Player p, List<ItemStack> list) {
        if (list.isEmpty()) return 0;
        var left = p.getInventory().addItem(list.toArray(new ItemStack[0]));
        for (ItemStack it : left.values()) p.getWorld().dropItem(p.getLocation(), it);
        return left.size();
    }

    // ------------------------------------------------------------------ protection

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (!byBlock.isEmpty()) e.blockList().removeIf(b -> byBlock.containsKey(blockKey(b)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (!byBlock.isEmpty()) e.blockList().removeIf(b -> byBlock.containsKey(blockKey(b)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        if (byBlock.isEmpty()) return;
        if (byBlock.containsKey(blockKey(e.getBlock().getRelative(e.getDirection())))) { e.setCancelled(true); return; }
        for (Block b : e.getBlocks()) {
            if (byBlock.containsKey(blockKey(b)) || byBlock.containsKey(blockKey(b.getRelative(e.getDirection())))) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        if (byBlock.isEmpty()) return;
        for (Block b : e.getBlocks()) {
            if (byBlock.containsKey(blockKey(b))) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (!byBlock.isEmpty() && byBlock.containsKey(blockKey(e.getToBlock()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (!byBlock.isEmpty() && byBlock.containsKey(blockKey(e.getBlock()))) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ ticking

    private void tick() {
        long now = System.currentTimeMillis();
        for (Grave g : new ArrayList<>(graves.values())) {
            if (!g.loaded()) continue;
            if (expireMs > 0 && now - g.created >= expireMs) {
                crumble(g);
                continue;
            }
            Block b = g.block();
            // Something cleared the head (a plugin, /fill): put it back if the spot is empty again.
            if (b.getType() != Material.PLAYER_HEAD && (b.getType().isAir() || b.isLiquid())) placeHead(g, b, 0f, Bukkit.getPlayer(g.owner));
            spawnEntities(g);
            if (unlockMs > 0 && unlocked(g, now) && announcedUnlock.add(g.id) && now - g.created - unlockMs < 10_000L) {
                Player o = Bukkit.getPlayer(g.owner);
                if (o != null) o.sendMessage(SmpPlugin.mm("<yellow>☠ Your grave at <white>" + g.coords() + "</white> is now open to everyone. <gray>Hurry!"));
            }
            if (g.label != null) g.label.text(SmpPlugin.mm(labelText(g, now)));
        }
        if (clickCooldown.size() > 200) clickCooldown.clear();
    }

    private String labelText(Grave g, long now) {
        StringBuilder sb = new StringBuilder("<gold>☠ <white>").append(g.ownerName).append("</white>'s grave</gold>\n<gray>")
                .append(g.itemCount()).append(" items").append(g.xp > 0 ? " · " + g.xp + " XP" : "").append("</gray>\n");
        if (unlockMs <= 0) sb.append("<red>🔒 Owner only");
        else if (unlocked(g, now)) sb.append("<green>🔓 Open to everyone");
        else sb.append("<red>🔒 Owner only · ").append(duration(g.created + unlockMs - now));
        if (expireMs > 0) sb.append("\n<dark_gray>Crumbles in ").append(duration(g.created + expireMs - now));
        return sb.toString();
    }

    private void spawnEntities(Grave g) {
        Location c = g.center();
        if (c == null) return;
        if (hologram && (g.label == null || !g.label.isValid())) {
            g.label = c.getWorld().spawn(c.clone().add(0, 0.95, 0), TextDisplay.class, t -> {
                t.setPersistent(false);
                t.setBillboard(Display.Billboard.CENTER);
                t.setAlignment(TextDisplay.TextAlignment.CENTER);
                t.setViewRange(0.4f);
                t.text(SmpPlugin.mm(labelText(g, System.currentTimeMillis())));
                t.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, g.id);
            });
        }
        if (g.hitbox == null || !g.hitbox.isValid()) {
            g.hitbox = c.getWorld().spawn(c, Interaction.class, i -> {
                i.setPersistent(false);
                i.setInteractionWidth(0.7f);
                i.setInteractionHeight(0.6f);
                i.setResponsive(true);
                i.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, g.id);
            });
        }
    }

    private void removeEntities(Grave g) {
        if (g.label != null) g.label.remove();
        if (g.hitbox != null) g.hitbox.remove();
        g.label = null;
        g.hitbox = null;
    }

    private void removeGrave(Grave g) {
        graves.remove(g.id);
        byBlock.remove(blockKey(g));
        announcedUnlock.remove(g.id);
        removeEntities(g);
        Block b = g.block();
        if (b != null && (b.getType() == Material.PLAYER_HEAD || b.getType() == Material.PLAYER_WALL_HEAD)) b.setType(Material.AIR, false);
        save();
    }

    /** Spill the grave's items and XP where it stood (expiry or an admin removing it). */
    private void crumble(Grave g) {
        Location c = g.center();
        removeGrave(g);
        if (c == null) return;
        if (!c.getWorld().isChunkLoaded(g.x >> 4, g.z >> 4)) c.getChunk().load();
        Location at = c.clone().add(0, 0.5, 0);
        for (Grave.Stored s : g.items) c.getWorld().dropItemNaturally(at, s.item());
        if (g.xp > 0) c.getWorld().spawn(at, ExperienceOrb.class, o -> o.setExperience(g.xp));
        Player o = Bukkit.getPlayer(g.owner);
        if (o != null) o.sendMessage(SmpPlugin.mm("<red>☠ Your grave at <white>" + g.coords() + "</white> crumbled and spilled its items."));
        plugin.getLogger().info("Grave #" + g.id + " (" + g.ownerName + ") crumbled at " + g.world + " " + g.coords());
    }

    private boolean unlocked(Grave g, long now) {
        return unlockMs > 0 && now - g.created >= unlockMs;
    }

    private Grave graveOf(Entity e) {
        if (!(e instanceof Interaction) && !(e instanceof TextDisplay)) return null;
        Integer id = e.getPersistentDataContainer().get(key, PersistentDataType.INTEGER);
        return id == null ? null : graves.get(id);
    }

    private static String blockKey(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    private static String blockKey(Grave g) {
        return g.world + ":" + g.x + ":" + g.y + ":" + g.z;
    }

    static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 3600) return (s / 3600) + "h " + (s % 3600 / 60) + "m";
        if (s >= 60) return (s / 60) + "m " + (s % 60) + "s";
        return s + "s";
    }

    private static String where(Player p, Grave g) {
        if (!p.getWorld().getName().equals(g.world)) return " in <white>" + g.world + "</white>";
        Location l = p.getLocation();
        double dx = g.x + 0.5 - l.getX(), dz = g.z + 0.5 - l.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        if (dist < 3) return ", right here";
        String[] dirs = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        return ", <white>" + dist + "</white> blocks " + dirs[Math.floorMod((int) Math.round(yaw / 45.0), 8)];
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        boolean admin = s.hasPermission("mrsmp.graves.admin");
        String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
        long now = System.currentTimeMillis();
        switch (sub) {
            case "" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Usage: /graves <all|tp|return|remove> (players: /graves)"); return true; }
                List<Grave> mine = graves.values().stream().filter(g -> g.owner.equals(p.getUniqueId())).toList();
                if (mine.isEmpty()) { p.sendMessage(SmpPlugin.mm("<gray>☠ You have no graves. <dark_gray>When you die, your items wait for you in one.")); return true; }
                p.sendMessage(SmpPlugin.mm("<gold>☠ Your graves:"));
                for (Grave g : mine) p.sendMessage(SmpPlugin.mm(line(g, now, false) + where(p, g)));
                return true;
            }
            case "all" -> {
                if (!admin) return deny(s);
                if (graves.isEmpty()) { s.sendMessage(SmpPlugin.mm("<gray>No graves.")); return true; }
                s.sendMessage(SmpPlugin.mm("<gold>☠ " + graves.size() + " graves:"));
                for (Grave g : graves.values()) s.sendMessage(SmpPlugin.mm(line(g, now, true)));
                return true;
            }
            case "tp", "return", "remove" -> {
                if (!admin) return deny(s);
                Grave g = a.length > 1 ? parse(a[1]) : null;
                if (g == null) { s.sendMessage(SmpPlugin.mm("<red>No grave with that id. <gray>/graves all")); return true; }
                if (sub.equals("tp")) {
                    if (!(s instanceof Player p)) return true;
                    Location c = g.center();
                    if (c == null) { s.sendMessage(SmpPlugin.mm("<red>World " + g.world + " isn't loaded.")); return true; }
                    p.teleport(c.add(0, 1, 0));
                } else if (sub.equals("return")) {
                    Player o = Bukkit.getPlayer(g.owner);
                    if (o == null) { s.sendMessage(SmpPlugin.mm("<red>" + g.ownerName + " isn't online.")); return true; }
                    removeGrave(g);
                    int dropped = restore(o, g.items);
                    if (g.xp > 0) o.giveExp(g.xp, true);
                    o.sendMessage(SmpPlugin.mm("<green>☠ Staff returned the items from your grave at " + g.coords() + "."
                            + (dropped > 0 ? " <gray>(" + dropped + " dropped at your feet)" : "")));
                    s.sendMessage(SmpPlugin.mm("<green>Returned grave #" + g.id + " to " + g.ownerName + "."));
                } else {
                    crumble(g);
                    s.sendMessage(SmpPlugin.mm("<green>Grave #" + g.id + " removed; its items dropped where it stood."));
                }
                return true;
            }
            default -> {
                s.sendMessage(SmpPlugin.mm(admin ? "<gray>/graves, /graves all, /graves tp|return|remove <id>" : "<gray>/graves - list your graves"));
                return true;
            }
        }
    }

    private String line(Grave g, long now, boolean admin) {
        String lock = unlockMs <= 0 ? "<red>owner only" : unlocked(g, now) ? "<green>open" : "<red>locked " + duration(g.created + unlockMs - now);
        String id = admin ? "<click:run_command:/graves tp " + g.id + "><hover:show_text:'Teleport'><aqua>#" + g.id + "</aqua></hover></click> <white>" + g.ownerName + "</white> " : "";
        return " <dark_gray>•</dark_gray> " + id + "<white>" + g.world + " " + g.coords() + "</white> <gray>" + g.itemCount() + " items, "
                + duration(now - g.created) + " ago, " + lock + "</gray>";
    }

    private Grave parse(String s) {
        try { return graves.get(Integer.parseInt(s.replace("#", ""))); } catch (NumberFormatException e) { return null; }
    }

    private boolean deny(CommandSender s) {
        s.sendMessage(SmpPlugin.mm("<red>You can't do that."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("mrsmp.graves.admin")) return List.of();
        if (a.length == 1) return List.of("all", "tp", "return", "remove").stream().filter(x -> x.startsWith(a[0].toLowerCase(Locale.ROOT))).toList();
        if (a.length == 2) return graves.keySet().stream().map(String::valueOf).filter(x -> x.startsWith(a[1])).toList();
        return List.of();
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, y.getInt("next-id", 1));
        ConfigurationSection root = y.getConfigurationSection("graves");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(k);
            if (c == null) continue;
            try {
                Grave g = new Grave(Integer.parseInt(k), UUID.fromString(c.getString("owner")), c.getString("name", "?"),
                        c.getString("world"), c.getInt("x"), c.getInt("y"), c.getInt("z"), c.getLong("created"));
                g.xp = c.getInt("xp");
                g.cause = c.getString("cause");
                List<Integer> slots = c.getIntegerList("slots");
                List<String> data = c.getStringList("items");
                for (int i = 0; i < data.size(); i++) {
                    ItemStack it = ItemStack.deserializeBytes(Base64.getDecoder().decode(data.get(i)));
                    g.items.add(new Grave.Stored(i < slots.size() ? slots.get(i) : -1, it));
                }
                graves.put(g.id, g);
                byBlock.put(blockKey(g), g);
                nextId = Math.max(nextId, g.id + 1);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't load grave " + k + " from graves.yml (left in the file)", ex);
            }
        }
        plugin.getLogger().info("Loaded " + graves.size() + " graves.");
    }

    private String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Grave g : graves.values()) {
            String p = "graves." + g.id + ".";
            y.set(p + "owner", g.owner.toString());
            y.set(p + "name", g.ownerName);
            y.set(p + "world", g.world);
            y.set(p + "x", g.x);
            y.set(p + "y", g.y);
            y.set(p + "z", g.z);
            y.set(p + "created", g.created);
            y.set(p + "xp", g.xp);
            if (g.cause != null) y.set(p + "cause", g.cause);
            List<Integer> slots = new ArrayList<>();
            List<String> items = new ArrayList<>();
            for (Grave.Stored s : g.items) {
                slots.add(s.slot());
                items.add(Base64.getEncoder().encodeToString(s.item().serializeAsBytes()));
            }
            y.set(p + "slots", slots);
            y.set(p + "items", items);
        }
        return y.saveToString();
    }

    /** Snapshot on the main thread, write off it. Writes are ordered so an older snapshot never wins. */
    private void save() {
        String data = serialize();
        long v = ++saveVersion;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data, v));
    }

    private void saveNow() {
        write(serialize(), ++saveVersion);
    }

    private void write(String data, long version) {
        synchronized (writeLock) {
            if (version <= writtenVersion) return;
            try {
                File tmp = new File(file.getParentFile(), "graves.yml.tmp");
                Files.writeString(tmp.toPath(), data, StandardCharsets.UTF_8);
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                writtenVersion = version;
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "Couldn't save graves.yml", ex);
            }
        }
    }
}
