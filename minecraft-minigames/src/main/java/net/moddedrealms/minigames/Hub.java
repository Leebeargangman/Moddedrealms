package net.moddedrealms.minigames;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Lantern;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;

/** The hub: a plaza with an NPC per game, the game menu, the hub sidebar and the leaderboard. */
final class Hub {

    static final int Y = 64;
    private static final int R = 18;

    private final MinigamesPlugin plugin;
    private final List<Entity> npcs = new ArrayList<>();
    private TextDisplay leaderboard;
    private int ticks;

    Hub(MinigamesPlugin plugin) {
        this.plugin = plugin;
    }

    Location spawn() {
        Location l = plugin.at(0.5, Y + 1, 0.5);
        l.setYaw(180);
        return l;
    }

    // ------------------------------------------------------------ building

    void build() {
        World w = plugin.world();
        for (int x = -R - 2; x <= R + 2; x++) {
            for (int z = -R - 2; z <= R + 2; z++) {
                for (int y = Y - 3; y <= Y + 6; y++) w.getBlockAt(x, y, z).setType(Material.AIR, false);
                double d = Math.sqrt(x * x + z * z);
                if (d > R + 0.5) continue;
                Material floor;
                if (d < 2.5) floor = Material.CHISELED_STONE_BRICKS;
                else if (d < 3.5) floor = Material.GOLD_BLOCK;
                else if ((int) d == 7 || (int) d == 13) floor = Material.POLISHED_ANDESITE;
                else if (Math.abs(x) <= 1 || Math.abs(z) <= 1) floor = Material.STONE_BRICKS;
                else floor = ((x + z) & 1) == 0 ? Material.SMOOTH_STONE : Material.POLISHED_DIORITE;
                w.getBlockAt(x, Y, z).setType(floor, false);
                w.getBlockAt(x, Y - 1, z).setType(Material.STONE, false);
                if (d > R - 1.5) w.getBlockAt(x, Y - 2, z).setType(Material.COBBLESTONE, false);
                if (d > R - 0.5) {
                    w.getBlockAt(x, Y + 1, z).setType(Material.STONE_BRICK_WALL, false);
                }
            }
        }
        w.getBlockAt(0, Y, 0).setType(Material.SEA_LANTERN, false);
        // Lamp posts and planters around the plaza.
        int[][] posts = {{11, 11}, {-11, 11}, {11, -11}, {-11, -11}, {15, 0}, {-15, 0}};
        for (int[] p : posts) {
            for (int y = 1; y <= 3; y++) w.getBlockAt(p[0], Y + y, p[1]).setType(Material.DARK_OAK_FENCE, false);
            Block lantern = w.getBlockAt(p[0], Y + 4, p[1]);
            lantern.setType(Material.LANTERN, false);
            if (lantern.getBlockData() instanceof Lantern l) {
                l.setHanging(false);
                lantern.setBlockData(l, false);
            }
            for (BlockFace f : new BlockFace[] {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block b = w.getBlockAt(p[0], Y + 1, p[1]).getRelative(f);
                b.setType(Material.FLOWERING_AZALEA_LEAVES, false);
            }
        }
        // Podiums for the game NPCs and the way back.
        podium(w, -5, -10, Material.RED_CONCRETE, Material.TNT);
        podium(w, 5, -10, Material.BLACK_CONCRETE, Material.REDSTONE_BLOCK);
        podium(w, 0, 10, Material.GREEN_CONCRETE, Material.EMERALD_BLOCK);
    }

    private void podium(World w, int cx, int cz, Material top, Material centre) {
        for (int x = cx - 1; x <= cx + 1; x++) {
            for (int z = cz - 1; z <= cz + 1; z++) w.getBlockAt(x, Y + 1, z).setType(x == cx && z == cz ? centre : top, false);
        }
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int z = cz - 2; z <= cz + 2; z++) {
                if (Math.abs(x - cx) == 2 || Math.abs(z - cz) == 2) w.getBlockAt(x, Y + 1, z).setType(Material.SMOOTH_STONE_SLAB, false);
            }
        }
    }

    // ------------------------------------------------------------ NPCs and signs

    void spawnNpcs() {
        removeNpcs();
        npc(-4.5, -9.5, "tntrun", Component.text("TNT Run", NamedTextColor.RED, TextDecoration.BOLD), "entity/player/wide/kai");
        npc(5.5, -9.5, "murder", Component.text("Murder Mystery", NamedTextColor.DARK_RED, TextDecoration.BOLD), "entity/player/wide/noor");
        npc(0.5, 10.5, "back", Component.text("Back to " + label(), NamedTextColor.GREEN, TextDecoration.BOLD), "entity/player/slim/sunny");
        World w = plugin.world();
        Location title = plugin.at(0.5, Y + 4.2, -14.5);
        npcs.add(w.spawn(title, TextDisplay.class, d -> {
            tag(d, "title");
            d.text(Component.text("MODDEDREALMS", NamedTextColor.GOLD, TextDecoration.BOLD).appendNewline()
                    .append(Component.text("MINIGAMES", NamedTextColor.YELLOW, TextDecoration.BOLD)).appendNewline()
                    .append(Component.text("Right-click an NPC or use the compass to play", NamedTextColor.GRAY)));
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
            d.setPersistent(false);
        }));
        leaderboard = w.spawn(plugin.at(-12.5, Y + 3.2, 0.5), TextDisplay.class, d -> {
            tag(d, "top");
            d.setBillboard(Display.Billboard.CENTER);
            d.setBackgroundColor(Color.fromARGB(90, 0, 0, 0));
            d.setPersistent(false);
        });
        npcs.add(leaderboard);
        updateLeaderboard();
    }

    private String label() {
        return plugin.getConfig().getString("network.back-label", "the SMP");
    }

    private void npc(double x, double z, String id, Component name, String skin) {
        Location at = plugin.at(x, Y + 2, z);
        at.setYaw(z < 0 ? 0 : 180);
        Mannequin m = plugin.world().spawn(at, Mannequin.class, e -> {
            tag(e, id);
            e.customName(name);
            e.setCustomNameVisible(true);
            e.setDescription(Component.text("Click to play!", NamedTextColor.GRAY));
            e.setInvulnerable(true);
            e.setImmovable(true);
            e.setGravity(false);
            e.setSilent(true);
            e.setPersistent(false);
            e.setRemoveWhenFarAway(false);
            e.setProfile(io.papermc.paper.datacomponent.item.ResolvableProfile.resolvableProfile()
                    .name("NPC").uuid(java.util.UUID.nameUUIDFromBytes(id.getBytes()))
                    .skinPatch(p -> p.body(net.kyori.adventure.key.Key.key("minecraft", skin))).build());
            if (id.equals("tntrun")) e.getEquipment().setItemInMainHand(new ItemStack(Material.TNT));
            if (id.equals("murder")) e.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
            if (id.equals("back")) e.getEquipment().setItemInMainHand(new ItemStack(Material.RED_BED));
        });
        npcs.add(m);
    }

    private void tag(Entity e, String id) {
        e.getPersistentDataContainer().set(plugin.npcKey, PersistentDataType.STRING, id);
    }

    String npcId(Entity e) {
        return e.getPersistentDataContainer().get(plugin.npcKey, PersistentDataType.STRING);
    }

    void removeNpcs() {
        for (Entity e : npcs) if (e.isValid()) e.remove();
        npcs.clear();
        leaderboard = null;
    }

    void updateLeaderboard() {
        if (leaderboard == null || !leaderboard.isValid()) return;
        Component c = Component.text("TOP PLAYERS", NamedTextColor.GOLD, TextDecoration.BOLD);
        List<String[]> top = plugin.stats().top(null, 10);
        if (top.isEmpty()) c = c.appendNewline().append(Component.text("No winners yet: be the first!", NamedTextColor.GRAY));
        for (int i = 0; i < top.size(); i++) {
            c = c.appendNewline().append(Component.text((i + 1) + ". ", NamedTextColor.YELLOW))
                    .append(Component.text(top.get(i)[0], NamedTextColor.WHITE))
                    .append(Component.text("  " + top.get(i)[1] + " pts", NamedTextColor.GRAY));
        }
        leaderboard.text(c);
    }

    // ------------------------------------------------------------ players in the hub

    void send(Player p) {
        p.setGameMode(GameMode.ADVENTURE);
        p.getInventory().clear();
        p.setItemOnCursor(null);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        var hp = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(hp == null ? 20 : hp.getValue());
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setLevel(0);
        p.setExp(0);
        p.setInvisible(false);
        p.getInventory().setItem(0, Items.games());
        p.getInventory().setItem(4, Items.stats());
        p.getInventory().setItem(8, Items.back(label()));
        p.getInventory().setHeldItemSlot(0);
        plugin.board().hideNames(p, false, List.of());
        p.teleport(spawn());
        board(p);
    }

    boolean inHub(Player p) {
        return plugin.manager().matchOf(p) == null;
    }

    void tick() {
        ticks++;
        for (Entity e : npcs) {
            if (!(e instanceof Mannequin m) || !m.isValid()) continue;
            String id = npcId(m);
            GameType type = id == null ? null : GameType.parse(id);
            if (type == null) continue;
            int playing = plugin.manager().playing(type);
            m.setDescription(Component.text(playing == 1 ? "1 playing · click to play!" : playing + " playing · click to play!", NamedTextColor.GRAY));
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!inHub(p)) continue;
            board(p);
            if (p.getWorld() != plugin.world() || p.getLocation().getY() < Y - 20) p.teleport(spawn());
        }
        if (ticks % 30 == 0) updateLeaderboard();
    }

    private void board(Player p) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        lines.add(Component.text("Online: ", NamedTextColor.GRAY).append(Component.text(String.valueOf(Bukkit.getOnlinePlayers().size()), NamedTextColor.GREEN)));
        lines.add(Component.empty());
        for (GameType t : GameType.values()) {
            lines.add(Component.text(t.title + ": ", t.color).append(Component.text(plugin.manager().playing(t) + " playing", NamedTextColor.WHITE)));
        }
        lines.add(Component.empty());
        lines.add(Component.text("Your points: ", NamedTextColor.GRAY).append(Component.text(String.valueOf(plugin.stats().points(p.getUniqueId())), NamedTextColor.GOLD)));
        lines.add(Component.empty());
        lines.add(Component.text("play.moddedrealms.net", NamedTextColor.YELLOW));
        plugin.board().show(p, Component.text("MINIGAMES", NamedTextColor.GOLD, TextDecoration.BOLD), lines);
    }

    // ------------------------------------------------------------ the game menu

    static final class Menu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    void openMenu(Player p) {
        Menu h = new Menu();
        Inventory inv = Bukkit.createInventory(h, 27, Component.text("Pick a game"));
        h.inv = inv;
        int[] slots = {11, 15};
        GameType[] types = GameType.values();
        for (int i = 0; i < types.length && i < slots.length; i++) {
            GameType t = types[i];
            ItemStack it = new ItemStack(t.icon);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(Component.text(t.title, t.color, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(t.blurb, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text(plugin.manager().playing(t) + " playing", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.text(t.min(plugin) + " to " + t.max(plugin) + " players, bots fill empty spots", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(Component.text("Click to play!", NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            meta.getPersistentDataContainer().set(plugin.itemKey, PersistentDataType.STRING, "menu:" + t.id);
            it.setItemMeta(meta);
            inv.setItem(slots[i], it);
        }
        p.openInventory(inv);
    }
}
