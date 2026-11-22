package net.moddedrealms.smp.town;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Breaking a friend out: with bolt cutters (from the Planner or the Fence), work on a cell's barred window from
 * outside the police station. It takes 20 seconds next to the wall, the alarm goes off at once (3 stars, Bureau case)
 * and the police come. When the cutting is done the wall under the window opens for 40 seconds: whoever is in that
 * cell can run. Getting 8 blocks from the cell is an escape (3 stars too). The wall closes again by itself.
 */
public final class PrisonBreak implements Listener {
    private final TownManager t;
    private UUID breaker;
    private int cell = -1;
    private double progress;
    private long lastNear;
    private final BossBar bar = BossBar.bossBar(Component.text("Cutting"), 0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
    private final List<Object[]> openHoles = new ArrayList<>();

    PrisonBreak(TownManager t) {
        this.t = t;
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    /** The barred window behind cell i (generated jail: middle of the back wall, two blocks above the floor). */
    private Block window(int i) {
        Location c = t.jail().cells().get(i);
        return c.getWorld().getBlockAt(c.getBlockX(), c.getBlockY() + 2, c.getBlockZ() + 3);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCut(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        if (e.getClickedBlock().getType() != Material.IRON_BARS || !t.jail().built()) return;
        Player p = e.getPlayer();
        if (!t.heists().isCutters(p.getInventory().getItemInMainHand())) return;
        int idx = -1;
        for (int i = 0; i < t.jail().cells().size(); i++) if (window(i).equals(e.getClickedBlock())) idx = i;
        if (idx < 0) return;
        e.setCancelled(true);
        TownData d = t.data(p);
        if (d.jailed() || t.exempt(p)) return;
        if (breaker != null) {
            p.sendMessage(SmpPlugin.mm("<gray>Someone's already working on a window."));
            return;
        }
        breaker = p.getUniqueId();
        cell = idx;
        progress = 0;
        lastNear = System.currentTimeMillis();
        p.showBossBar(bar);
        p.sendMessage(SmpPlugin.mm("<red>You start cutting the bars. <gray>Stay right here. The alarm's going off!"));
        for (Player o : Bukkit.getOnlinePlayers()) {
            o.sendMessage(SmpPlugin.mm("<red><bold>⚠ PRISON BREAK!</bold> <gray>Someone is cutting into the jail."));
        }
        t.commit(p, Crime.PRISON_BREAK, p.getLocation(), "break" + System.currentTimeMillis(), null, true);
        t.officers().dispatch(p, 2);
        for (Player in : prisoners(idx)) in.sendMessage(SmpPlugin.mm("<green>Someone is cutting through your cell wall! Get ready to run."));
    }

    private List<Player> prisoners(int idx) {
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            TownData d = t.data(p);
            if (d.jailed() && "spawn".equals(d.jailTown) && d.cell == idx) out.add(p);
        }
        return out;
    }

    /** Every second. */
    void second() {
        long now = System.currentTimeMillis();
        for (Object[] h : new ArrayList<>(openHoles)) {
            if (now >= (long) h[0]) {
                ((Block) h[1]).setBlockData((BlockData) h[2], false);
                ((Block) h[3]).setBlockData((BlockData) h[4], false);
                openHoles.remove(h);
            }
        }
        if (breaker == null) return;
        Player p = Bukkit.getPlayer(breaker);
        Block win = cell >= 0 && cell < t.jail().cells().size() ? window(cell) : null;
        if (p == null || win == null || t.data(p).jailed()) {
            stop(p, null);
            return;
        }
        boolean near = p.getWorld() == win.getWorld() && p.getLocation().distance(win.getLocation().add(0.5, -1, 0.5)) <= 3.5;
        if (near) {
            lastNear = now;
            progress++;
            win.getWorld().spawnParticle(Particle.CRIT, win.getLocation().add(0.5, 0.5, 0.5), 6, 0.2, 0.2, 0.2, 0.1);
            win.getWorld().playSound(win.getLocation(), Sound.BLOCK_CHAIN_HIT, 1f, 0.7f);
        } else if (now - lastNear > 6000) {
            stop(p, "<gray>You left the window. The bars hold.");
            return;
        }
        double need = cfg().getDouble("town.prison-break.seconds", 20);
        bar.name(SmpPlugin.mm("<red>Cutting the bars: <white>" + (int) progress + "/" + (int) need + "s" + (near ? "" : " <yellow>(get back to the window!)")));
        bar.progress((float) Math.min(1, progress / need));
        if (progress < need) return;
        // Open the wall under the window for a while.
        Block a = win.getRelative(0, -2, 0), b = win.getRelative(0, -1, 0);
        int secs = cfg().getInt("town.prison-break.open-seconds", 40);
        openHoles.add(new Object[]{now + secs * 1000L, a, a.getBlockData().clone(), b, b.getBlockData().clone()});
        a.setType(Material.AIR, false);
        b.setType(Material.AIR, false);
        win.getWorld().playSound(win.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.4f);
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (t.heists().isCutters(hand)) hand.setAmount(hand.getAmount() - 1);
        p.sendMessage(SmpPlugin.mm("<green>The wall is open! <gray>Your friends have " + secs + " seconds."));
        for (Player in : prisoners(cell)) {
            TownData d = t.data(in);
            d.breakoutUntil = now + secs * 1000L;
            in.sendMessage(SmpPlugin.mm("<green><bold>The back wall is open! RUN!</bold> <gray>Get out through the hole under the window."));
            in.playSound(in.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.6f);
        }
        t.news(p.getName() + " broke into the jail to free a prisoner!");
        stop(p, null);
    }

    private void stop(Player p, String msg) {
        if (p != null) {
            p.hideBossBar(bar);
            if (msg != null) p.sendMessage(SmpPlugin.mm(msg));
        }
        breaker = null;
        cell = -1;
        progress = 0;
    }

    /** Jail asks: is this prisoner out of their cell through a break? */
    boolean escaping(Player p, TownData d, Location cellLoc) {
        if (d.breakoutUntil < System.currentTimeMillis()) return false;
        if (p.getWorld() == cellLoc.getWorld() && p.getLocation().distance(cellLoc) > 8) {
            d.jailLeft = 0;
            d.cell = -1;
            d.breakoutUntil = 0;
            t.markDirty();
            p.sendMessage(SmpPlugin.mm("<gold><bold>You're out!</bold> <red>Now the whole town is looking for you."));
            t.wanted(p, Crime.ESCAPE, null);
            t.addRep(p, -Crime.ESCAPE.rep);
            t.news(p.getName() + " escaped from jail!");
        }
        return true;
    }

    void shutdown() {
        for (Object[] h : openHoles) {
            ((Block) h[1]).setBlockData((BlockData) h[2], false);
            ((Block) h[3]).setBlockData((BlockData) h[4], false);
        }
        openHoles.clear();
    }
}
