package net.moddedrealms.smp;

import java.time.Duration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Title, sound and pointers on join; a longer greeting the first time. */
final class Welcome implements Listener {
    private final SmpPlugin plugin;

    Welcome(SmpPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean("welcome.enabled", true)) return;
        Player p = e.getPlayer();
        boolean first = !p.hasPlayedBefore();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            var ph = Placeholder.unparsed("player", p.getName());
            p.showTitle(Title.title(SmpPlugin.MM.deserialize(cfg.getString("welcome.title", "")),
                    SmpPlugin.MM.deserialize(cfg.getString("welcome.subtitle", "")),
                    Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(3500), Duration.ofMillis(1000))));
            p.playSound(p.getLocation(), first ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_NOTE_BLOCK_CHIME, 0.7f, 1.2f);
            int online = Bukkit.getOnlinePlayers().size();
            var colony = plugin.town() == null ? null : plugin.town().colonyName(p);
            var ph2 = Placeholder.unparsed("online", String.valueOf(online));
            var ph3 = Placeholder.unparsed("colony", colony == null ? "" : colony);
            for (String line : cfg.getStringList(first ? "welcome.first-join-message" : "welcome.join-message")) {
                p.sendMessage(SmpPlugin.MM.deserialize(line, ph, ph2, ph3));
            }
            if (first) starterKit(p);
            if (first && cfg.getBoolean("welcome.open-guide-on-first-join", true) && plugin.guide() != null) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline() && p.getOpenInventory().getTopInventory().getType() == org.bukkit.event.inventory.InventoryType.CRAFTING) plugin.guide().open(p);
                }, cfg.getLong("welcome.guide-delay-ticks", 80L));
            }
        }, 40L);
    }

    /** Plain tools, food and a bed on a first join, so nobody starts the night empty-handed. */
    private void starterKit(Player p) {
        java.util.List<org.bukkit.inventory.ItemStack> kit = new java.util.ArrayList<>();
        for (String s : plugin.getConfig().getStringList("welcome.starter-kit")) {
            String[] f = s.split(":");
            org.bukkit.Material m = org.bukkit.Material.matchMaterial(f[0].trim());
            if (m == null || !m.isItem()) continue;
            int n = 1;
            try {
                if (f.length > 1) n = Math.max(1, Integer.parseInt(f[1].trim()));
            } catch (NumberFormatException ignored) {
            }
            while (n > 0) {
                int st = Math.min(n, m.getMaxStackSize());
                kit.add(new org.bukkit.inventory.ItemStack(m, st));
                n -= st;
            }
        }
        if (kit.isEmpty()) return;
        for (var left : p.getInventory().addItem(kit.toArray(new org.bukkit.inventory.ItemStack[0])).values()) p.getWorld().dropItem(p.getLocation(), left);
        p.sendMessage(SmpPlugin.mm("<green>A starter kit is in your inventory: tools, food, torches and a bed."));
    }
}
