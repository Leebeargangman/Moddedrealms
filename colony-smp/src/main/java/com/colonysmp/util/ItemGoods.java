package com.colonysmp.util;

import com.colonysmp.core.Good;
import com.colonysmp.core.Goods;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts between items and colony goods. Plain stackable items are goods; tools, armour and anything named are equipment. */
public final class ItemGoods {

    private ItemGoods() {}

    /** Registers every item on this server as a good, then the colony goods and price overrides. */
    @SuppressWarnings("deprecation")
    public static int registerAll(Map<String, Double> prices) {
        Goods.clear();
        int n = 0;
        for (Material m : Material.values()) {
            if (m.isLegacy() || m.isAir() || !m.isItem()) continue;
            Goods.register(Goods.classify(m.name(), m.getMaxStackSize(), m.getMaxDurability()));
            n++;
        }
        Goods.registerColonyGoods();
        prices.forEach(Goods::setValue);
        return n;
    }

    /** The good an item is, or null if it's equipment (durability, enchantments, a name...) or a plugin item. */
    public static String goodOf(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        if (it.hasItemMeta()) {
            ItemMeta meta = it.getItemMeta();
            String custom = meta.getPersistentDataContainer().get(Keys.GOOD, PersistentDataType.STRING);
            if (custom != null) return Goods.known(custom) ? custom : null;
            if (meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants() || !meta.getPersistentDataContainer().isEmpty()) return null;
            if (meta instanceof org.bukkit.inventory.meta.PotionMeta || meta instanceof org.bukkit.inventory.meta.BookMeta
                    || meta instanceof org.bukkit.inventory.meta.BlockStateMeta bs && bs.hasBlockState()) return null;
        }
        if (it.getType().getMaxDurability() > 0) return null;
        String key = Goods.key(it.getType().name());
        return Goods.known(key) ? key : null;
    }

    public static boolean equipment(ItemStack it) {
        return it != null && !it.getType().isAir() && goodOf(it) == null && Items.id(it) == null;
    }

    /** An item stack of a good (at most one stack). */
    public static ItemStack item(String key, int amount) {
        Good g = Goods.get(key);
        Material m = Material.matchMaterial(g.material());
        if (m == null) m = Material.STONE;
        ItemStack it = new ItemStack(m, Math.max(1, Math.min(amount, m.getMaxStackSize())));
        if (g.custom()) {
            ItemMeta meta = it.getItemMeta();
            meta.displayName(Text.item("<white>" + g.name()));
            meta.lore(Text.lore("<dark_aqua>Colony good", "<gray>Stored and used by colonies."));
            meta.getPersistentDataContainer().set(Keys.GOOD, PersistentDataType.STRING, key);
            meta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (m.getMaxStackSize() < g.stack()) meta.setMaxStackSize(Math.min(99, g.stack()));
            it.setItemMeta(meta);
            it.setAmount(Math.max(1, Math.min(amount, g.stack())));
        }
        return it;
    }

    /** Whole stacks of a good, for handing a quantity out. */
    public static List<ItemStack> stacks(String key, long amount) {
        List<ItemStack> out = new ArrayList<>();
        int per = Math.max(1, Math.min(Goods.stack(key), item(key, 1).getMaxStackSize()));
        while (amount > 0 && out.size() < 64) {
            int n = (int) Math.min(per, amount);
            out.add(item(key, n));
            amount -= n;
        }
        return out;
    }

    public static Material material(String key) {
        Material m = Material.matchMaterial(Goods.get(key).material());
        return m == null ? Material.BARRIER : m;
    }

    /** Menu icon material for a good. */
    public static Material icon(String key) {
        Material m = material(key);
        return m.isItem() ? m : Material.PAPER;
    }
}
