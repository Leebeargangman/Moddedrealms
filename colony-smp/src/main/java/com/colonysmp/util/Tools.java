package com.colonysmp.util;

import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Tool and weapon classification, quality, durability and damage. */
public final class Tools {

    public enum Kind { HOE, AXE, PICKAXE, SHOVEL, SWORD, BOW, CROSSBOW, TRIDENT, MACE, HELMET, CHESTPLATE, LEGGINGS, BOOTS, SHIELD, FISHING_ROD, SHEARS, OTHER }

    private Tools() {}

    public static Kind kind(ItemStack it) {
        return it == null ? Kind.OTHER : kind(it.getType());
    }

    public static Kind kind(Material m) {
        String n = m.name();
        if (n.endsWith("_HOE")) return Kind.HOE;
        if (n.endsWith("_AXE")) return Kind.AXE;
        if (n.endsWith("_PICKAXE")) return Kind.PICKAXE;
        if (n.endsWith("_SHOVEL")) return Kind.SHOVEL;
        if (n.endsWith("_SWORD")) return Kind.SWORD;
        if (n.equals("BOW")) return Kind.BOW;
        if (n.equals("CROSSBOW")) return Kind.CROSSBOW;
        if (n.equals("TRIDENT")) return Kind.TRIDENT;
        if (n.equals("MACE")) return Kind.MACE;
        if (n.equals("SHIELD")) return Kind.SHIELD;
        if (n.equals("FISHING_ROD")) return Kind.FISHING_ROD;
        if (n.equals("SHEARS")) return Kind.SHEARS;
        if (n.endsWith("_HELMET") || n.equals("TURTLE_HELMET")) return Kind.HELMET;
        if (n.endsWith("_CHESTPLATE")) return Kind.CHESTPLATE;
        if (n.endsWith("_LEGGINGS")) return Kind.LEGGINGS;
        if (n.endsWith("_BOOTS")) return Kind.BOOTS;
        return Kind.OTHER;
    }

    public static boolean isArmor(Kind k) {
        return k == Kind.HELMET || k == Kind.CHESTPLATE || k == Kind.LEGGINGS || k == Kind.BOOTS;
    }

    public static boolean isMelee(Kind k) {
        return k == Kind.SWORD || k == Kind.AXE || k == Kind.TRIDENT || k == Kind.MACE;
    }

    public static int tier(Material m) {
        String n = m.name();
        if (n.startsWith("NETHERITE_")) return 6;
        if (n.startsWith("DIAMOND_")) return 5;
        if (n.startsWith("IRON_")) return 4;
        if (n.startsWith("CHAINMAIL_") || n.equals("TURTLE_HELMET")) return 3;
        if (n.startsWith("STONE_")) return 3;
        if (n.startsWith("GOLDEN_")) return 2;
        if (n.startsWith("WOODEN_") || n.startsWith("LEATHER_")) return 1;
        if (n.equals("TRIDENT") || n.equals("MACE")) return 5;
        if (n.equals("BOW") || n.equals("CROSSBOW") || n.equals("SHIELD") || n.equals("FISHING_ROD") || n.equals("SHEARS")) return 3;
        return 0;
    }

    /** Quality used to pick the best item for a job: tier first, then enchantments, then wear. */
    public static double score(ItemStack it) {
        if (it == null || it.getType().isAir()) return -1;
        double s = tier(it.getType()) * 100;
        for (Map.Entry<Enchantment, Integer> e : it.getEnchantments().entrySet()) s += e.getValue() * 6;
        int max = it.getType().getMaxDurability();
        if (max > 0 && it.getItemMeta() instanceof Damageable d) s -= 10.0 * d.getDamage() / max;
        Kind k = kind(it);
        if (isMelee(k)) s = meleeDamage(it) * (k == Kind.SWORD ? 1.6 : 0.95) * 10 + s * 0.01;
        return s;
    }

    /** Base melee damage when an NPC hits with this (2 for bare hands). */
    public static double meleeDamage(ItemStack it) {
        if (it == null || it.getType().isAir()) return 2;
        Material m = it.getType();
        int t = tier(m);
        double base;
        switch (kind(m)) {
            case SWORD -> base = switch (t) { case 6 -> 8; case 5 -> 7; case 4 -> 6; case 3 -> 5; default -> 4; };
            case AXE -> base = switch (t) { case 6 -> 10; case 5, 4, 3 -> 9; default -> 7; };
            case TRIDENT -> base = 9;
            case MACE -> base = 7;
            case PICKAXE -> base = 1 + t;
            case SHOVEL -> base = 1.5 + t * 0.8;
            case HOE -> base = 1;
            default -> base = 1;
        }
        int sharp = it.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (sharp > 0) base += 0.5 * sharp + 0.5;
        return base;
    }

    /**
     * Wears an item by one use, respecting Unbreaking. Returns true when the item broke
     * (the caller must then drop its reference to it).
     */
    public static boolean wear(ItemStack it, int amount) {
        if (it == null || it.getType().isAir()) return false;
        int max = it.getType().getMaxDurability();
        if (max <= 0) return false;
        ItemMeta meta = it.getItemMeta();
        if (!(meta instanceof Damageable d) || meta.isUnbreakable()) return false;
        int unbreaking = it.getEnchantmentLevel(Enchantment.UNBREAKING);
        int dealt = 0;
        for (int i = 0; i < amount; i++) {
            if (unbreaking > 0 && ThreadLocalRandom.current().nextInt(unbreaking + 1) != 0) continue;
            dealt++;
        }
        if (dealt == 0) return false;
        d.setDamage(d.getDamage() + dealt);
        it.setItemMeta(meta);
        return d.getDamage() >= max;
    }

    public static double wornFraction(ItemStack it) {
        if (it == null) return 0;
        int max = it.getType().getMaxDurability();
        if (max <= 0 || !(it.getItemMeta() instanceof Damageable d)) return 0;
        return (double) d.getDamage() / max;
    }

    /** Vanilla armour points of a piece of armour (0 for anything else). */
    public static double armorPoints(Material m) {
        String n = m.name();
        int slot = n.endsWith("_HELMET") ? 0 : n.endsWith("_CHESTPLATE") ? 1 : n.endsWith("_LEGGINGS") ? 2 : n.endsWith("_BOOTS") ? 3 : -1;
        if (slot < 0) return 0;
        int[] p;
        if (n.startsWith("LEATHER_")) p = new int[]{1, 3, 2, 1};
        else if (n.startsWith("CHAINMAIL_")) p = new int[]{2, 5, 4, 1};
        else if (n.startsWith("IRON_")) p = new int[]{2, 6, 5, 2};
        else if (n.startsWith("GOLDEN_")) p = new int[]{2, 5, 3, 1};
        else if (n.startsWith("DIAMOND_") || n.startsWith("NETHERITE_")) p = new int[]{3, 8, 6, 3};
        else if (n.equals("TURTLE_HELMET")) p = new int[]{2, 0, 0, 0};
        else return 0;
        return p[slot];
    }

    /** How fast work goes with no tool at all (0 when {@code citizens.require-tools} is on: no tool, no work). */
    private static double bareHands = 0.3;

    public static void setBareHands(double speed) {
        bareHands = Math.max(0, Math.min(1, speed));
    }

    public static double bareHands() {
        return bareHands;
    }

    /**
     * Speed multiplier for work with this tool. The better the tool the faster the work: wood 0.75, stone 1.0,
     * gold 1.3 (quick but it wears out fast), iron 1.3, diamond 1.6, netherite 1.9, and 0.12 more for every
     * level of Efficiency. Bare hands are far slower, or don't work at all (see {@link #setBareHands}).
     */
    public static double speed(ItemStack it) {
        if (it == null || it.getType().isAir()) return bareHands;
        int t = tier(it.getType());
        double s = switch (t) { case 6 -> 1.9; case 5 -> 1.6; case 4 -> 1.3; case 3 -> 1.0; case 2 -> 1.3; default -> 0.75; };
        int eff = it.getEnchantmentLevel(Enchantment.EFFICIENCY);
        return s + eff * 0.12;
    }

    /**
     * The share of extra produce a tool brings in (a better hoe wastes less of the crop, a better axe splits
     * cleaner): nothing for wood, 5% for stone and gold, 12% iron, 22% diamond, 30% netherite, and 10% more for
     * every level of Fortune.
     */
    public static double yield(ItemStack it) {
        if (it == null || it.getType().isAir()) return 0;
        double y = switch (tier(it.getType())) { case 6 -> 0.30; case 5 -> 0.22; case 4 -> 0.12; case 3, 2 -> 0.05; default -> 0; };
        return y + it.getEnchantmentLevel(Enchantment.FORTUNE) * 0.10;
    }
}
