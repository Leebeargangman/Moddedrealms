package net.moddedrealms.smp.town;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

/** The server economy through Vault (Essentials provides it), looked up by reflection so Vault stays optional. */
public final class Money {
    private Object eco;
    private Method balance, withdraw, deposit;

    public boolean ready() {
        if (eco != null) return true;
        try {
            Class<?> c = Class.forName("net.milkbowl.vault.economy.Economy");
            var reg = Bukkit.getServicesManager().getRegistration(c);
            if (reg == null) return false;
            eco = reg.getProvider();
            balance = c.getMethod("getBalance", OfflinePlayer.class);
            withdraw = c.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            deposit = c.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            eco = null;
            return false;
        }
    }

    public double balance(OfflinePlayer p) {
        if (!ready()) return 0;
        try {
            return (double) balance.invoke(eco, p);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Takes up to the amount (never below zero). Returns what was actually taken. */
    public double take(OfflinePlayer p, double amount) {
        double have = balance(p);
        double t = Math.floor(Math.min(have, amount));
        if (t <= 0) return 0;
        try {
            Object r = withdraw.invoke(eco, p, t);
            return success(r) ? t : 0;
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Takes exactly this amount, or nothing if they can't afford it. */
    public boolean pay(OfflinePlayer p, double amount) {
        if (balance(p) < amount) return false;
        return take(p, amount) >= amount;
    }

    public void give(OfflinePlayer p, double amount) {
        if (amount <= 0 || !ready()) return;
        try {
            deposit.invoke(eco, p, Math.floor(amount));
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static boolean success(Object response) {
        try {
            return (boolean) response.getClass().getMethod("transactionSuccess").invoke(response);
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    public static String fmt(double v) {
        return "$" + String.format("%,d", (long) Math.floor(v));
    }
}
