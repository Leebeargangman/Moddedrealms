package net.moddedrealms.smp.town;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.TextDecoration;
import net.moddedrealms.smp.SmpPlugin;
import net.moddedrealms.smp.npc.NpcDef;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
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
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.EntityTransformer;

/**
 * The Moddedrealms Bank: a generated building (saved first, reversible with /mrtown bank undo, like the jail) with a
 * vault. Right-clicking the vault starts a robbery: the alarm goes off, every robber becomes wanted for bank robbery
 * (4 stars, so the Bureau opens a case too) and the police rush in. Robbers must stay at the vault until it cracks;
 * then the money is split and everyone gets gold bars only the Fence will buy. One robbery per cooldown.
 */
public final class Bank implements Listener {
    private static final int HX = 8, HZ = 6, DOWN = 4, UP = 12;

    private final TownManager t;
    private final NamespacedKey key;
    private final File file;
    private final Random r = new Random();
    private Location vault;
    private BoundingBox box;
    private String boxWorld;
    private UUID status;
    private long lastRobbery;
    private Robbery robbery;
    private int tick;

    private World w;
    private int cx, cy, cz;
    private final List<Block> panes = new ArrayList<>();

    private static final class Robbery {
        final long started = System.currentTimeMillis();
        double progress;
        long lastPresent = System.currentTimeMillis();
        final Set<UUID> robbers = new HashSet<>();
        final BossBar bar = BossBar.bossBar(SmpPlugin.mm("<red><bold>Cracking the vault"), 0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_10);
    }

    Bank(TownManager t) {
        this.t = t;
        this.key = new NamespacedKey(t.plugin(), "bank");
        this.file = new File(t.plugin().getDataFolder(), "bank.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        World vw = Bukkit.getWorld(y.getString("vault.world", ""));
        if (vw != null) vault = new Location(vw, y.getInt("vault.x"), y.getInt("vault.y"), y.getInt("vault.z"));
        if (y.contains("box.world")) {
            boxWorld = y.getString("box.world");
            box = new BoundingBox(y.getDouble("box.min-x"), y.getDouble("box.min-y"), y.getDouble("box.min-z"),
                    y.getDouble("box.max-x"), y.getDouble("box.max-y"), y.getDouble("box.max-z"));
        }
        String s = y.getString("status");
        status = s == null ? null : UUID.fromString(s);
        lastRobbery = y.getLong("last-robbery");
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        if (vault != null) {
            y.set("vault.world", vault.getWorld().getName());
            y.set("vault.x", vault.getBlockX());
            y.set("vault.y", vault.getBlockY());
            y.set("vault.z", vault.getBlockZ());
        }
        if (box != null) {
            y.set("box.world", boxWorld);
            y.set("box.min-x", box.getMinX());
            y.set("box.min-y", box.getMinY());
            y.set("box.min-z", box.getMinZ());
            y.set("box.max-x", box.getMaxX());
            y.set("box.max-y", box.getMaxY());
            y.set("box.max-z", box.getMaxZ());
        }
        y.set("status", status == null ? null : status.toString());
        y.set("last-robbery", lastRobbery);
        try {
            y.save(file);
        } catch (IOException e) {
            t.plugin().getLogger().warning("Could not save bank.yml: " + e.getMessage());
        }
    }

    public boolean contains(Location l) {
        return box != null && l.getWorld() != null && l.getWorld().getName().equals(boxWorld) && box.contains(l.toVector());
    }

    public boolean ready() {
        return vault != null;
    }

    public Location vault() {
        return vault == null ? null : vault.clone();
    }

    /** A heist emptied the vault: the quick robbery has to wait too. */
    void markRobbed() {
        lastRobbery = System.currentTimeMillis();
        save();
        updateStatus();
    }

    private long cooldownLeft() {
        long cd = cfg().getLong("town.bank.cooldown-minutes", 180) * 60_000L;
        return Math.max(0, lastRobbery + cd - System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ robbery

    @EventHandler(priority = EventPriority.HIGH)
    public void onVault(PlayerInteractEvent e) {
        if (vault == null || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != EquipmentSlot.HAND) return;
        if (!e.getClickedBlock().getLocation().equals(vault.getBlock().getLocation())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!t.enabled) return;
        if (t.heists().vaultClicked(p)) return;
        if (t.exempt(p)) {
            p.sendMessage(SmpPlugin.mm("<gray>You can't rob the bank right now."));
            return;
        }
        if (robbery == null) {
            long left = cooldownLeft();
            if (left > 0) {
                p.sendMessage(SmpPlugin.mm("<gray>The vault was emptied recently. It's restocked in <white>" + (left / 3_600_000) + "h " + (left / 60_000 % 60) + "m</white>."));
                return;
            }
            if (Bukkit.getOnlinePlayers().size() < cfg().getInt("town.bank.min-players-online", 2)) {
                p.sendMessage(SmpPlugin.mm("<gray>The vault's time lock won't budge. <dark_gray>(needs " + cfg().getInt("town.bank.min-players-online", 2) + " players online)"));
                return;
            }
            robbery = new Robbery();
            for (Player o : Bukkit.getOnlinePlayers()) {
                o.sendMessage(SmpPlugin.mm("<red><bold>⚠ ALARM!</bold> The Moddedrealms Bank is being robbed! <gray>Police are on their way."));
                o.playSound(o.getLocation(), Sound.BLOCK_BELL_USE, 0.6f, 0.6f);
            }
            t.news("Someone tried to rob the Moddedrealms Bank!");
        }
        if (robbery.robbers.add(p.getUniqueId())) {
            p.showBossBar(robbery.bar);
            p.sendMessage(SmpPlugin.mm("<red>You start working on the vault. <gray>Stay close to it until it cracks: more robbers, faster work."));
            t.commit(p, Crime.BANK_ROBBERY, p.getLocation(), "bank", null, true);
            t.officers().dispatch(p, 3);
        }
    }

    void second() {
        tick++;
        if (robbery == null) {
            if (tick % 10 == 0) updateStatus();
            return;
        }
        long now = System.currentTimeMillis();
        List<Player> present = new ArrayList<>();
        for (UUID id : robbery.robbers) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || p.getWorld() != vault.getWorld() || p.isDead()) continue;
            TownData d = t.data(p);
            if (d.jailed() || d.inService()) {
                p.hideBossBar(robbery.bar);
                continue;
            }
            if (p.getLocation().distance(vault.clone().add(0.5, 0, 0.5)) <= 5) present.add(p);
        }
        if (present.isEmpty()) {
            if (now - robbery.lastPresent > 8000) {
                end(false, present);
                return;
            }
        } else {
            robbery.lastPresent = now;
            double speed = Math.min(3, present.size());
            for (Player p : present) if (t.gangs().hasPerk(p, "safecracker")) {
                speed *= 1.43;
                break;
            }
            robbery.progress += speed;
        }
        double need = cfg().getDouble("town.bank.crack-seconds", 60);
        robbery.bar.progress((float) Math.min(1, robbery.progress / need));
        robbery.bar.name(SmpPlugin.mm("<red><bold>Cracking the vault</bold> <white>" + (int) (100 * Math.min(1, robbery.progress / need)) + "%"
                + (present.isEmpty() ? " <yellow>(get back to the vault!)" : "")));
        if (tick % 3 == 0) vault.getWorld().playSound(vault, Sound.BLOCK_BELL_USE, 2f, 0.7f);
        updateStatus();
        if (robbery.progress >= need) end(true, present);
    }

    private void end(boolean cracked, List<Player> present) {
        Robbery rb = robbery;
        robbery = null;
        for (UUID id : rb.robbers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(rb.bar);
        }
        if (!cracked) {
            lastRobbery = System.currentTimeMillis() - cfg().getLong("town.bank.cooldown-minutes", 180) * 60_000L + 10 * 60_000L;
            save();
            for (Player o : Bukkit.getOnlinePlayers()) o.sendMessage(SmpPlugin.mm("<green>The bank robbery failed. The vault holds!"));
            t.news("The bank robbers were driven off before they cracked the vault.");
            updateStatus();
            return;
        }
        double total = Math.floor(cfg().getDouble("town.bank.loot-min", 4000)
                + r.nextDouble() * (cfg().getDouble("town.bank.loot-max", 9000) - cfg().getDouble("town.bank.loot-min", 4000)));
        double each = Math.floor(total / Math.max(1, present.size()));
        List<String> names = new ArrayList<>();
        for (Player p : present) {
            t.money.give(p, each);
            t.gangs().crimeIncome(p, each, 200);
            int bars = cfg().getInt("town.bank.gold-bars", 2);
            ItemStack gold = stolenGold(bars);
            for (ItemStack left : p.getInventory().addItem(gold).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
            p.sendMessage(SmpPlugin.mm("<gold><bold>The vault is open!</bold> <yellow>+" + Money.fmt(each) + "</yellow> and " + bars
                    + " gold bars (only a fence will buy those). <red>Now get away!"));
            p.playSound(p.getLocation(), Sound.BLOCK_VAULT_OPEN_SHUTTER, 1f, 1f);
            names.add(p.getName());
        }
        lastRobbery = System.currentTimeMillis();
        save();
        String who = String.join(" and ", names);
        for (Player o : Bukkit.getOnlinePlayers()) o.sendMessage(SmpPlugin.mm("<red><bold>The bank has been robbed!</bold> <white>" + who + "</white> got away with " + Money.fmt(total) + "."));
        t.news(who + " robbed the bank of " + Money.fmt(total) + "!");
        updateStatus();
    }

    private ItemStack stolenGold(int n) {
        ItemStack it = new ItemStack(Material.GOLD_INGOT, n);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm("<gold>Bank Gold Bar").decoration(TextDecoration.ITALIC, false));
        int value = cfg().getInt("town.bank.gold-bar-value", 400);
        meta.lore(List.of(SmpPlugin.mm("<gray>Stolen goods. Stamped with the bank's mark.").decoration(TextDecoration.ITALIC, false),
                SmpPlugin.mm("<dark_gray>A fence would pay " + Money.fmt(value) + " for this").decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(t.stolenKey, PersistentDataType.INTEGER, value);
        it.setItemMeta(meta);
        return it;
    }

    private void updateStatus() {
        if (status == null) return;
        Entity e = Bukkit.getEntity(status);
        if (!(e instanceof TextDisplay td)) return;
        String text;
        if (robbery != null) {
            double need = cfg().getDouble("town.bank.crack-seconds", 60);
            text = "<red><bold>⚠ ROBBERY IN PROGRESS ⚠</bold>\n<white>" + (int) (100 * Math.min(1, robbery.progress / need)) + "% cracked";
        } else if (cooldownLeft() > 0) {
            long left = cooldownLeft();
            text = "<gray>Vault empty\n<dark_gray>restocked in " + (left / 3_600_000) + "h " + (left / 60_000 % 60) + "m";
        } else {
            text = "<green><bold>VAULT SECURED</bold>\n<gray>Protected by the Town Watch";
        }
        td.text(SmpPlugin.mm(text));
    }

    void shutdown() {
        if (robbery != null) {
            for (UUID id : robbery.robbers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.hideBossBar(robbery.bar);
            }
            robbery = null;
        }
    }

    // ------------------------------------------------------------------ manual set-up

    public String setVault(Player p) {
        Block b = p.getTargetBlockExact(6);
        if (b == null || b.getType().isAir()) return "Look at the block that should be the vault (within 6 blocks).";
        vault = b.getLocation();
        save();
        return "Vault set to the " + b.getType().name().toLowerCase() + " at " + b.getX() + " " + b.getY() + " " + b.getZ() + ".";
    }

    public String info() {
        long left = cooldownLeft();
        return "Bank: " + (vault == null ? "no vault" : "vault at " + vault.getBlockX() + " " + vault.getBlockY() + " " + vault.getBlockZ())
                + ", generated building: " + (built() ? "yes" : "no") + ", " + (robbery != null ? "robbery in progress"
                : left > 0 ? "restocked in " + left / 60_000 + " min" : "ready to be robbed") + ".";
    }

    // ------------------------------------------------------------------ the generated building

    private File structureFile() {
        return new File(t.plugin().getDataFolder(), "bank-undo.nbt");
    }

    private File metaFile() {
        return new File(t.plugin().getDataFolder(), "bank-undo.yml");
    }

    public boolean built() {
        return structureFile().exists();
    }

    private int[] plazaGate() {
        File pf = new File(t.plugin().getDataFolder(), "plaza-undo.yml");
        if (!pf.exists()) return null;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pf);
        if (!y.getString("world", "").equals(w.getName())) return null;
        return new int[]{y.getInt("x"), y.getInt("y"), y.getInt("z") + 26};
    }

    private int[] area(int[] gate) {
        int x0 = cx - HX - 2, x1 = cx + HX + 2, z0 = cz - HZ - 4, z1 = cz + HZ + 2;
        if (gate != null) {
            x0 = Math.min(x0, gate[0] - 2);
            x1 = Math.max(x1, gate[0] + 2);
            z0 = Math.min(z0, gate[2]);
            z1 = Math.max(z1, gate[2]);
        }
        return new int[]{x0, cy - DOWN, z0, x1, cy + UP, z1};
    }

    private static BoundingBox boxOf(int[] a) {
        return new BoundingBox(a[0], a[1], a[2], a[3] + 1, a[4] + 1, a[5] + 1);
    }

    public String check(World world, int x, int y, int z) {
        this.w = world;
        this.cx = x;
        this.cy = y;
        this.cz = z;
        int[] a = area(plazaGate());
        int tiles = 0, built = 0;
        for (int bx = a[0] >> 4; bx <= a[3] >> 4; bx++) {
            for (int bz = a[2] >> 4; bz <= a[5] >> 4; bz++) {
                for (var s : w.getChunkAt(bx, bz).getTileEntities()) {
                    Location l = s.getLocation();
                    if (l.getBlockX() >= a[0] && l.getBlockX() <= a[3] && l.getBlockZ() >= a[2] && l.getBlockZ() <= a[5] && l.getBlockY() >= a[1] && l.getBlockY() <= a[4]) tiles++;
                }
            }
        }
        for (int dx = -HX - 2; dx <= HX + 2; dx++) {
            for (int dz = -HZ - 2; dz <= HZ + 2; dz++) {
                for (int dy = -1; dy <= UP; dy++) {
                    String n = at(dx, dy, dz).getType().name();
                    if (n.contains("PLANKS") || n.contains("BRICK") || n.contains("GLASS") || n.contains("WOOL") || n.contains("CONCRETE") || n.contains("CHEST")) built++;
                }
            }
        }
        int ents = 0;
        for (Entity e : w.getNearbyEntities(boxOf(a))) if (!(e instanceof Player) && !t.isOurs(e)) ents++;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int dx = -HX; dx <= HX; dx += 3) {
            for (int dz = -HZ; dz <= HZ; dz += 3) {
                int top = w.getHighestBlockYAt(cx + dx, cz + dz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) - cy;
                lo = Math.min(lo, top);
                hi = Math.max(hi, top);
            }
        }
        return "Bank area " + a[0] + ".." + a[3] + ", " + a[2] + ".." + a[5] + ": " + tiles + " block entities, " + built
                + " built-looking blocks under the building, " + ents + " entities. Ground relative to the floor: " + lo + " to +" + hi + ".";
    }

    /** Centre = the floor block in the middle; the front door faces north (towards -z). */
    public String build(World world, int x, int y, int z) throws IOException {
        if (built()) throw new IOException("A bank is already built. Run /mrtown bank undo first.");
        this.w = world;
        this.cx = x;
        this.cy = y;
        this.cz = z;
        int[] gate = plazaGate();
        int[] a = area(gate);
        Structure st = Bukkit.getStructureManager().createStructure();
        st.fill(new Location(w, a[0], a[1], a[2]), new BlockVector(a[3] - a[0] + 1, a[4] - a[1] + 1, a[5] - a[2] + 1), true);
        Bukkit.getStructureManager().saveStructure(structureFile(), st);
        YamlConfiguration meta = new YamlConfiguration();
        meta.set("world", w.getName());
        meta.set("centre", List.of(x, y, z));
        meta.set("area", List.of(a[0], a[1], a[2], a[3], a[4], a[5]));
        meta.set("created", System.currentTimeMillis());
        meta.save(metaFile());
        for (Entity e : w.getNearbyEntities(boxOf(a))) if (loose(e)) e.remove();
        panes.clear();
        if (gate != null) path(gate);
        shell();
        lobby();
        vaultRoom();
        for (Block b : panes) connect(b);
        vault = at(0, 2, 2).getLocation();
        box = new BoundingBox(cx - HX - 2, cy - DOWN, cz - HZ - 2, cx + HX + 3, cy + UP, cz + HZ + 2);
        boxWorld = w.getName();
        save();
        updateStatus();
        return "Bank built at " + w.getName() + " " + x + " " + y + " " + z + (gate != null ? " with a path to the plaza's south gate" : "")
                + ". The vault is the block behind the counter. Undo with /mrtown bank undo (if the jail is also built, undo in reverse order).";
    }

    private Material wall(int dx, int dy, int dz) {
        int h = Math.abs((dx * 73856093) ^ (dy * 19349663) ^ (dz * 83492791)) % 100;
        return h < 15 ? Material.SMOOTH_QUARTZ : Material.QUARTZ_BRICKS;
    }

    private void shell() {
        for (int dx = -HX - 2; dx <= HX + 2; dx++) {
            for (int dz = -HZ - 2; dz <= HZ + 2; dz++) {
                for (int dy = 1; dy <= UP; dy++) set(dx, dy, dz, Material.AIR);
                for (int dy = -DOWN; dy < 0; dy++) {
                    Block b = at(dx, dy, dz);
                    if (b.isPassable() || b.isLiquid()) b.setType(Material.STONE, false);
                }
                boolean inside = Math.abs(dx) <= HX && Math.abs(dz) <= HZ;
                if (!inside) set(dx, 0, dz, dz < -HZ ? Material.SMOOTH_STONE : Material.GRASS_BLOCK);
            }
        }
        for (int dx = -HX; dx <= HX; dx++) {
            for (int dz = -HZ; dz <= HZ; dz++) {
                boolean edge = Math.abs(dx) == HX || Math.abs(dz) == HZ;
                boolean pillar = Math.abs(dx) == HX && Math.abs(dz) == HZ || Math.abs(dz) == HZ && Math.abs(dx) == 4 || Math.abs(dx) == HX && dz == 2;
                set(dx, 0, dz, (dx + dz & 1) == 0 ? Material.SMOOTH_QUARTZ : Material.POLISHED_DEEPSLATE);
                if (edge) {
                    for (int dy = 1; dy <= 5; dy++) set(dx, dy, dz, pillar ? Material.QUARTZ_PILLAR : dy == 1 ? Material.POLISHED_DEEPSLATE : wall(dx, dy, dz));
                    set(dx, 6, dz, Material.QUARTZ_BRICKS);
                    if (Math.abs(dx) == HX && Math.abs(dz) == HZ) {
                        set(dx, 7, dz, Material.CHISELED_QUARTZ_BLOCK);
                        set(dx, 8, dz, Material.LANTERN);
                    } else {
                        slab(dx, 7, dz, Material.SMOOTH_QUARTZ_SLAB, Slab.Type.BOTTOM);
                    }
                } else {
                    set(dx, 6, dz, Material.SMOOTH_QUARTZ);
                }
            }
        }
        // Pediment over the front with a gold coin.
        for (int row = 0; row < 4; row++) for (int dx = -4 + row; dx <= 4 - row; dx++) set(dx, 7 + row, -HZ, Material.QUARTZ_BRICKS);
        set(0, 8, -HZ, Material.GOLD_BLOCK);
        // Portico: columns in front, a slab roof over them.
        for (int dx : new int[]{-5, -2, 2, 5}) for (int dy = 1; dy <= 5; dy++) set(dx, dy, -HZ - 1, Material.QUARTZ_PILLAR);
        for (int dx = -6; dx <= 6; dx++) slab(dx, 6, -HZ - 1, Material.SMOOTH_QUARTZ_SLAB, Slab.Type.BOTTOM);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 1; dy <= 3; dy++) set(dx, dy, -HZ, Material.AIR);
            set(dx, 4, -HZ, Material.CHISELED_QUARTZ_BLOCK);
        }
        for (int dx : new int[]{-6, 6}) for (int dy = 2; dy <= 3; dy++) pane(dx, dy, -HZ, Material.GLASS_PANE);
        for (int side : new int[]{-HX, HX}) for (int dz = -4; dz <= -2; dz++) for (int dy = 2; dy <= 3; dy++) pane(side, dy, dz, Material.GLASS_PANE);
        hologram(cx + 0.5, cy + 11.6, cz - HZ + 0.5, "<gold><bold>$ MODDEDREALMS BANK $</bold>\n<gray>Est. 2026");
    }

    private void lobby() {
        for (int dz = -HZ + 1; dz <= -1; dz++) set(0, 1, dz, Material.RED_CARPET);
        for (int dx = -HX + 1; dx <= HX - 1; dx++) {
            if (Math.abs(dx) <= 1) continue;
            set(dx, 1, 0, Material.DARK_OAK_PLANKS);
            pane(dx, 2, 0, Material.IRON_BARS);
        }
        hanging(-4, 5, -3);
        hanging(4, 5, -3);
        hanging(0, 5, -3);
        for (int dx : new int[]{-7, 7}) set(dx, 1, -5, Material.POTTED_FERN);
        for (int dz = -5; dz <= -3; dz++) {
            BlockData s = Material.DARK_OAK_STAIRS.createBlockData();
            ((Directional) s).setFacing(BlockFace.WEST);
            at(-7, 1, dz).setBlockData(s, false);
        }
        npc("banker", "<gold>Banker Ellis", "<gray>Moddedrealms Bank", cx - 4.5, cz + 1.5, 180);
    }

    private void vaultRoom() {
        for (int dx = -HX + 1; dx <= HX - 1; dx++) {
            for (int dy = 1; dy <= 5; dy++) {
                if (Math.abs(dx) <= 1 && dy <= 3) set(dx, dy, 2, Material.IRON_BLOCK);
                else if (Math.abs(dx) == 4 && (dy == 2 || dy == 3)) pane(dx, dy, 2, Material.IRON_BARS);
                else set(dx, dy, 2, Material.DEEPSLATE_BRICKS);
            }
            for (int dz = 3; dz <= HZ - 1; dz++) set(dx, 0, dz, Material.POLISHED_DEEPSLATE);
        }
        BlockData v = Material.VAULT.createBlockData();
        if (v instanceof Directional dv) dv.setFacing(BlockFace.NORTH);
        at(0, 2, 2).setBlockData(v, false);
        for (int[] g : new int[][]{{-6, 4}, {-5, 5}, {-6, 5}, {5, 5}, {6, 5}, {6, 4}, {-3, 5}, {3, 5}}) set(g[0], 1, g[1], Material.GOLD_BLOCK);
        set(-6, 2, 5, Material.GOLD_BLOCK);
        set(6, 2, 5, Material.GOLD_BLOCK);
        for (int dx : new int[]{-1, 1}) set(dx, 1, 5, Material.BARREL);
        hanging(-4, 5, 4);
        hanging(4, 5, 4);
        hanging(0, 5, 0);
        status = w.spawn(new Location(w, cx + 0.5, cy + 4.3, cz + 1.4), TextDisplay.class, d -> {
            d.text(SmpPlugin.mm("<green><bold>VAULT SECURED"));
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(120, 0, 0, 0));
            d.setPersistent(true);
            d.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        }).getUniqueId();
    }

    /** 3-wide path from the plaza's south gate to the front door, following the ground, with slabs on steps. */
    private void path(int[] gate) {
        int gx = gate[0], gz = gate[2], turnZ = cz - HZ - 3;
        List<int[]> pts = new ArrayList<>();
        int stepZ = turnZ >= gz ? 1 : -1;
        for (int z = gz; z != turnZ + stepZ; z += stepZ) pts.add(new int[]{gx, z, 0});
        int stepX = cx >= gx ? 1 : -1;
        for (int x = gx + stepX; x != cx + stepX; x += stepX) pts.add(new int[]{x, turnZ, 1});
        for (int z = turnZ + 1; z <= cz - HZ - 2; z++) pts.add(new int[]{cx, z, 0});
        int n = 0;
        for (int[] p : pts) {
            for (int o = -1; o <= 1; o++) {
                int x = p[2] == 0 ? p[0] + o : p[0], z = p[2] == 0 ? p[1] : p[1] + o;
                pave(x, z, o == 0 ? Material.SMOOTH_STONE : Material.POLISHED_DEEPSLATE);
            }
            if (++n % 9 == 0 && p[2] == 1) {
                int x = p[0], z = p[1] - 2;
                Integer g = ground(x, z);
                if (g != null) {
                    Block b = w.getBlockAt(x, g + 1, z);
                    b.setType(Material.POLISHED_DEEPSLATE_WALL, false);
                    b.getRelative(0, 1, 0).setType(Material.DARK_OAK_FENCE, false);
                    b.getRelative(0, 2, 0).setType(Material.DARK_OAK_FENCE, false);
                    b.getRelative(0, 3, 0).setType(Material.LANTERN, false);
                }
            }
        }
    }

    private Integer ground(int x, int z) {
        for (int y = cy + 5; y >= cy - 5; y--) {
            Block b = w.getBlockAt(x, y, z);
            if (b.getType().isSolid() && !b.getType().name().contains("LEAVES") && !b.getType().name().contains("LOG")
                    && !b.getType().name().contains("WALL") && !b.getType().name().contains("FENCE") && b.getType() != Material.LANTERN) return y;
        }
        return null;
    }

    private void pave(int x, int z, Material m) {
        Integer g = ground(x, z);
        if (g == null) return;
        Material now = w.getBlockAt(x, g, z).getType();
        if (now == Material.SMOOTH_STONE || now == Material.POLISHED_DEEPSLATE) return; // already a path (the jail's)
        w.getBlockAt(x, g, z).setType(m, false);
        for (int dy = 1; dy <= 3; dy++) {
            Block b = w.getBlockAt(x, g + dy, z);
            if (!b.getType().isAir() && (b.isPassable() || dy <= 2 && !b.getType().name().contains("LEAVES"))) b.setType(Material.AIR, false);
        }
    }

    private static boolean loose(Entity e) {
        if (e instanceof Player) return false;
        return !(e instanceof LivingEntity) || e instanceof ArmorStand;
    }

    public String undo() throws IOException {
        if (!built()) throw new IOException("No generated bank to undo. (A vault set by hand stays; it's only a marked block.)");
        shutdown();
        YamlConfiguration meta = YamlConfiguration.loadConfiguration(metaFile());
        w = Bukkit.getWorld(meta.getString("world", ""));
        if (w == null) throw new IOException("World not loaded.");
        List<Integer> a = meta.getIntegerList("area");
        int[] area = {a.get(0), a.get(1), a.get(2), a.get(3), a.get(4), a.get(5)};
        Structure st = Bukkit.getStructureManager().loadStructure(structureFile());
        for (Entity e : w.getNearbyEntities(boxOf(area))) if (e.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) e.remove();
        t.plugin().npcs().removeGroup("bank");
        for (int y = area[4]; y >= area[1]; y--) {
            for (int x = area[0]; x <= area[3]; x++) for (int z = area[2]; z <= area[5]; z++) w.getBlockAt(x, y, z).setType(Material.AIR, false);
        }
        EntityTransformer onlyLoose = (region, x, y, z, entity, allowed) -> loose(entity);
        st.place(new Location(w, area[0], area[1], area[2]), true, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random(), List.of(), List.of(onlyLoose));
        File done = new File(t.plugin().getDataFolder(), "bank-undone-" + System.currentTimeMillis() + ".nbt");
        structureFile().renameTo(done);
        metaFile().delete();
        vault = null;
        box = null;
        boxWorld = null;
        status = null;
        save();
        return "Bank removed and the area restored exactly as before. Snapshot kept as " + done.getName() + ".";
    }

    // ------------------------------------------------------------------ helpers

    private Block at(int dx, int dy, int dz) {
        return w.getBlockAt(cx + dx, cy + dy, cz + dz);
    }

    private void set(int dx, int dy, int dz, Material m) {
        at(dx, dy, dz).setType(m, false);
    }

    private void pane(int dx, int dy, int dz, Material m) {
        set(dx, dy, dz, m);
        panes.add(at(dx, dy, dz));
    }

    private void slab(int dx, int dy, int dz, Material m, Slab.Type type) {
        Slab s = (Slab) m.createBlockData();
        s.setType(type);
        at(dx, dy, dz).setBlockData(s, false);
    }

    private void hanging(int dx, int dy, int dz) {
        Lantern l = (Lantern) Material.LANTERN.createBlockData();
        l.setHanging(true);
        at(dx, dy, dz).setBlockData(l, false);
    }

    private static void connect(Block b) {
        if (!(b.getBlockData() instanceof MultipleFacing mf)) return;
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST}) {
            Material m = b.getRelative(f).getType();
            boolean join = m.isOccluding() || m == Material.IRON_BARS || m.name().endsWith("GLASS_PANE");
            if (mf.getAllowedFaces().contains(f)) mf.setFace(f, join);
        }
        b.setBlockData(mf, false);
    }

    private void npc(String id, String name, String title, double x, double z, float yaw) {
        NpcDef d = new NpcDef();
        d.id = id;
        d.name = name;
        d.title = title;
        d.world = w.getName();
        d.x = x;
        d.y = cy + 1;
        d.z = z;
        d.yaw = yaw;
        d.group = "bank";
        d.equipment.putAll(Map.of(EquipmentSlot.HEAD, Material.LEATHER_HELMET, EquipmentSlot.CHEST, Material.LEATHER_CHESTPLATE,
                EquipmentSlot.LEGS, Material.LEATHER_LEGGINGS, EquipmentSlot.HAND, Material.GOLD_INGOT));
        d.leatherColor = Color.fromRGB(0x2E4A2E);
        t.plugin().npcs().put(d);
    }

    private void hologram(double x, double y, double z, String text) {
        w.spawn(new Location(w, x, y, z), TextDisplay.class, d -> {
            d.text(SmpPlugin.mm(text));
            d.setBillboard(Display.Billboard.CENTER);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
            d.setPersistent(true);
            d.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
    }

    // ------------------------------------------------------------------ protection

    private boolean protectedHere(Player p, Location l) {
        return contains(l) && !p.hasPermission("mrsmp.admin");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (protectedHere(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (protectedHere(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        if (protectedHere(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true);
    }
}
