package net.moddedrealms.testplayers;

import io.papermc.paper.event.player.PlayerPurchaseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantRecipe;

/**
 * Trading with test players: right-click one and its trades open, like a villager's, built from what it really
 * carries. It sells what it has plenty of for emeralds, buys what it needs, and swaps things straight across.
 * Every trade really moves the items: what it sells leaves its inventory and what it gets goes in.
 */
final class Trading implements Listener {

    private final TestPlayersPlugin plugin;
    private final Map<UUID, TestPlayer> open = new ConcurrentHashMap<>();       // player -> who they're trading with
    private final Map<Merchant, TestPlayer> merchants = new WeakHashMap<>();
    private final Map<UUID, Long> thanked = new ConcurrentHashMap<>();

    Trading(TestPlayersPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onRightClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Mannequin m)) return;
        TestPlayer tp = plugin.players().of(m);
        if (tp == null || !plugin.getConfig().getBoolean("survival.trading", true)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        // A teammate gets its bag, like a shared chest (crouch to see its trades instead).
        if (plugin.life().teammates(p, tp) && !p.isSneaking()) {
            plugin.life().openBag(p, tp);
            return;
        }
        List<MerchantRecipe> recipes = recipes(tp);
        if (recipes.isEmpty()) {
            p.sendMessage(Component.text(tp.name + " has nothing to trade yet (carrying " + Items.describe(tp, 5) + ")."));
            return;
        }
        Merchant merchant = plugin.getServer().createMerchant(Component.text(tp.name + "'s trades"));
        merchant.setRecipes(recipes);
        merchants.put(merchant, tp);
        open.put(p.getUniqueId(), tp);
        m.lookAt(p.getEyeLocation().getX(), p.getEyeLocation().getY(), p.getEyeLocation().getZ(), io.papermc.paper.entity.LookAnchor.EYES);
        p.openMerchant(merchant, true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        open.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPurchase(PlayerPurchaseEvent e) {
        TestPlayer tp = merchants.get(e.getMerchant());
        if (tp == null) tp = open.get(e.getPlayer().getUniqueId());
        if (tp == null) return;
        MerchantRecipe r = e.getTrade();
        ItemStack sold = r.getResult();
        // It must still have what it's selling (it may have used it since the screen opened).
        if (!Items.take(tp, sold.getType(), sold.getAmount())) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(Component.text(tp.name + " doesn't have that any more."));
            return;
        }
        for (ItemStack in : r.getIngredients()) if (in != null && !in.getType().isAir()) Items.add(tp, in.getType(), in.getAmount());
        String what = sold.getAmount() + " " + name(sold.getType());
        tp.lastResult = "traded " + what + " to " + e.getPlayer().getName();
        plugin.players().markDirty();
        long now = System.currentTimeMillis();
        Long last = thanked.get(e.getPlayer().getUniqueId());
        if (last == null || now - last > 120_000) {
            thanked.put(e.getPlayer().getUniqueId(), now);
            if (tp.ai) plugin.brain().heard(tp, e.getPlayer().getName(), "(you just traded " + what + " to " + e.getPlayer().getName() + ")");
            else plugin.players().say(tp, "pleasure doing business, " + e.getPlayer().getName());
        }
    }

    /** Its trades, from what it carries: sell its surplus, buy what it wants, a swap or two. */
    private List<MerchantRecipe> recipes(TestPlayer tp) {
        List<MerchantRecipe> out = new ArrayList<>();
        int emeraldWorth = Items.worth(Material.EMERALD);
        // Sell: bundles worth about an emerald each, of things it has more of than it needs.
        tp.items.entrySet().stream().filter(en -> Items.worth(en.getKey()) > 0 && en.getKey() != Material.EMERALD)
                .sorted((a, b) -> b.getValue() * Items.worth(b.getKey()) - a.getValue() * Items.worth(a.getKey()))
                .limit(6).forEach(en -> {
                    Material mat = en.getKey();
                    int spare = en.getValue() - keep(tp, mat);
                    int bundle = Math.max(1, Math.min(mat.getMaxStackSize(), Math.round((float) emeraldWorth / Items.worth(mat))));
                    int price = Math.max(1, Math.round((float) bundle * Items.worth(mat) / emeraldWorth));
                    if (spare < bundle) return;
                    MerchantRecipe r = new MerchantRecipe(new ItemStack(mat, bundle), spare / bundle);
                    r.addIngredient(new ItemStack(Material.EMERALD, price));
                    r.setExperienceReward(false);
                    out.add(r);
                });
        // Buy: what it wants, paid in emeralds it has earned (or, with none, in its best surplus).
        int emeralds = Items.count(tp, Material.EMERALD);
        for (Material want : Items.wants(tp)) {
            int bundle = Math.max(1, Math.min(want.getMaxStackSize(), Math.round((float) emeraldWorth / Math.max(1, Items.worth(want)))));
            if (emeralds > 0) {
                MerchantRecipe r = new MerchantRecipe(new ItemStack(Material.EMERALD, 1), emeralds);
                r.addIngredient(new ItemStack(want, bundle));
                r.setExperienceReward(false);
                out.add(r);
            } else if (!out.isEmpty()) {
                // A straight swap: its first surplus bundle for the thing it wants.
                ItemStack give = out.getFirst().getResult();
                int spare = Items.count(tp, give.getType()) - keep(tp, give.getType());
                if (spare < give.getAmount()) continue;
                MerchantRecipe r = new MerchantRecipe(give.clone(), spare / give.getAmount());
                r.addIngredient(new ItemStack(want, bundle));
                r.setExperienceReward(false);
                out.add(r);
            }
            if (out.size() >= 9) break;
        }
        return out;
    }

    /** What it keeps for itself: its tools, building wood a builder needs, seed for a farmer... */
    private static int keep(TestPlayer tp, Material m) {
        String n = m.name();
        if (n.endsWith("_PICKAXE") || n.endsWith("_AXE") || n.endsWith("_SHOVEL") || n.endsWith("_HOE") || n.endsWith("_SWORD")) return Integer.MAX_VALUE / 2;
        if ("builder".equals(tp.job) && (Items.isLog(m) || m == Material.COBBLESTONE)) return 32;
        if (m == Material.WHEAT_SEEDS) return 8;
        if (m == Material.TORCH) return 8;
        return 0;
    }

    private static String name(Material m) {
        return m.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
