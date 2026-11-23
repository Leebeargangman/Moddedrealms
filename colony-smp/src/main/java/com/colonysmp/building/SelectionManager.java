package com.colonysmp.building;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.CustomBlueprint;
import com.colonysmp.model.Perm;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The Colony Wand: two corners of a box, copied as a blueprint the builders can build again. */
public final class SelectionManager implements Listener {

    public static final class Selection {
        public String world;
        public BlockPos p1, p2;
        public long showUntil;
    }

    private static final Color GREEN = Color.fromRGB(80, 255, 80), WHITE = Color.fromRGB(255, 255, 255), RED = Color.fromRGB(255, 60, 60);

    private final ColonySMP plugin;
    private final Map<UUID, Selection> selections = new HashMap<>();

    public SelectionManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    private Selection sel(Player p) {
        Selection s = selections.computeIfAbsent(p.getUniqueId(), k -> new Selection());
        String w = p.getWorld().getName();
        if (s.world != null && !s.world.equals(w)) {
            s.p1 = null;
            s.p2 = null;
        }
        s.world = w;
        return s;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !Items.is(e.getItem(), Items.WAND)) return;
        Player p = e.getPlayer();
        Action a = e.getAction();
        e.setCancelled(true);
        if ((a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) && p.isSneaking()) {
            copy(p);
            return;
        }
        if (a == Action.LEFT_CLICK_BLOCK && e.getClickedBlock() != null) {
            Selection s = sel(p);
            s.p1 = BlockPos.of(e.getClickedBlock());
            mark(p, e.getClickedBlock(), true);
            report(p, s, "<green>Corner 1</green> at <white>" + s.p1.x() + ", " + s.p1.y() + ", " + s.p1.z());
        } else if (a == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock() != null) {
            Selection s = sel(p);
            s.p2 = BlockPos.of(e.getClickedBlock());
            mark(p, e.getClickedBlock(), false);
            report(p, s, "<white>Corner 2</white> at <white>" + s.p2.x() + ", " + s.p2.y() + ", " + s.p2.z());
        }
    }

    private void copy(Player p) {
        Colony c = plugin.colonies().of(p);
        if (c == null) {
            Text.send(p, "<gray>Join or found a colony first (<white>/colony create <name></white>); copies belong to a colony.");
            return;
        }
        if (!plugin.access().can(p, c, Perm.MANAGE_BUILDINGS)) {
            Text.send(p, "<red>Only members who manage buildings can make blueprints.");
            return;
        }
        Selection s = selections.get(p.getUniqueId());
        if (s == null || s.p1 == null || s.p2 == null || !p.getWorld().getName().equals(s.world)) {
            Text.send(p, "<gray>Left-click one corner and right-click the opposite corner first (top and bottom of what you want).");
            return;
        }
        Copier.Box box = Copier.box(s.p1, s.p2);
        String why = Copier.check(plugin, p.getWorld(), box);
        if (why != null) {
            Text.send(p, "<red>" + why);
            return;
        }
        plugin.prompt().ask(p, "Name this blueprint (stand on the side that should be its front):", 60, name -> {
            String err = Copier.checkName(plugin, c, name);
            if (err != null) {
                Text.send(p, "<red>" + err);
                return;
            }
            String[] problem = {null};
            CustomBlueprint cb = Copier.copy(plugin, p.getWorld(), box, BlueprintManager.cardinal(p.getLocation().getYaw()), name, problem);
            if (cb == null) {
                Text.send(p, "<red>" + problem[0]);
                return;
            }
            cb.author = p.getName();
            c.blueprints.put(CustomBlueprint.key(cb.name), cb);
            c.log(ColonyEvent.Type.CONSTRUCTION, plugin.sim().day(c), p.getName() + " copied a blueprint: " + cb.name + " (" + cb.size() + ").");
            plugin.blueprints().selectCopy(p, cb);
            Fx.sound(p, "minecraft:entity.villager.work_cartographer", 1f, 1.2f);
            Text.send(p, "<green>Copied <white>" + Text.esc(cb.name) + "</white> (" + cb.size() + ", " + cb.solid() + " blocks). "
                    + "It's selected in your Blueprint Book: look where it should go and <gold>Shift + Left-Click</gold> to order it.");
            plugin.requestSave();
        });
    }

    /** No breaking blocks with the wand in creative. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (Items.is(e.getPlayer().getInventory().getItemInMainHand(), Items.WAND)) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        selections.remove(e.getPlayer().getUniqueId());
    }

    private void mark(Player p, Block b, boolean first) {
        if (first) {
            p.spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 1.1, 0.5), 25, 0.4, 0.3, 0.4, 0);
            Fx.sound(p, "minecraft:block.note_block.chime", 0.8f, 1.4f);
        } else {
            p.spawnParticle(Particle.END_ROD, b.getLocation().add(0.5, 1.1, 0.5), 20, 0.3, 0.3, 0.3, 0.01);
            Fx.sound(p, "minecraft:block.note_block.chime", 0.8f, 1.8f);
        }
    }

    private void report(Player p, Selection s, String head) {
        if (s.p1 == null || s.p2 == null) {
            Text.send(p, head + "<gray>. Now set the other corner.");
            return;
        }
        s.showUntil = System.currentTimeMillis() + 15_000;
        Copier.Box box = Copier.box(s.p1, s.p2);
        String why = Copier.check(plugin, p.getWorld(), box);
        Text.send(p, head + "<gray>. Box <white>" + box.size() + "</white>" + (why == null
                ? ". <yellow>Shift + Right-Click</yellow> to copy it as a blueprint." : ": <red>" + why));
    }

    /** Draws selections for players holding the wand (every 5 ticks). */
    public void render() {
        long now = System.currentTimeMillis();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            boolean holding = Items.is(p.getInventory().getItemInMainHand(), Items.WAND);
            Selection s = selections.get(p.getUniqueId());
            if (s == null || !holding && s.showUntil < now) continue;
            World w = p.getWorld();
            if (!w.getName().equals(s.world)) continue;
            if (s.p1 != null && s.p2 != null) {
                Copier.Box box = Copier.box(s.p1, s.p2);
                boolean ok = Copier.check(plugin, w, box) == null;
                if (Math.abs(box.minX() - p.getX()) < 64 && Math.abs(box.minZ() - p.getZ()) < 64) {
                    Fx.box(p, box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(), ok ? GREEN : RED, 1.0, 1.0f, true);
                }
            }
            if (s.p1 != null) Fx.dust(p, s.p1.x() + 0.5, s.p1.y() + 1.2, s.p1.z() + 0.5, GREEN, 2f);
            if (s.p2 != null) Fx.dust(p, s.p2.x() + 0.5, s.p2.y() + 1.2, s.p2.z() + 0.5, WHITE, 2f);
        }
    }
}
