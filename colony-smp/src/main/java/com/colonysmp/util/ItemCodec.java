package com.colonysmp.util;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/** Item <-> Base64 using Paper's versioned byte format (upgraded by the server's data fixers on load). */
public final class ItemCodec {

    private ItemCodec() {}

    public static String encode(ItemStack it) {
        if (it == null || it.getType().isAir() || it.getAmount() <= 0) return null;
        return Base64.getEncoder().encodeToString(it.serializeAsBytes());
    }

    public static ItemStack decode(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
        } catch (Exception e) {
            return null;
        }
    }

    public static byte[] bytes(ItemStack it) {
        return it.serializeAsBytes();
    }

    public static ItemStack fromBytes(byte[] b) {
        try {
            return ItemStack.deserializeBytes(b);
        } catch (Exception e) {
            return null;
        }
    }
}
