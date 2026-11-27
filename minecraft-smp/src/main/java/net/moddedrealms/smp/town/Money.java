package net.moddedrealms.smp.town;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * The server economy. The Vault balance (Essentials provides it, looked up by reflection so Vault stays optional) is
 * the bank account. With physical money on (see {@link Mint}), cash in hand is metal coins: balance/pay/take/give
 * work on an online player's coins and fall back to the bank for offline players; the bank* methods always use the
 * account (rent, loans, stocks, sales made while you're away).
 */
public final class Money {
    private Object eco;
    private Method balance, withdraw, deposit;
    private Mint mint;

    void attach(Mint m) {
        this.mint = m;
    }

    /** True when money in hand is physical (metal coins) and the Vault balance is the bank account. */
    public boolean physical() {
        return mint != null && mint.enabled();
    }

    private Player online(OfflinePlayer p) {
        return physical() && p != null ? p.getPlayer() : null;
    }

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

    // ------------------------------------------------------------------ the bank account (Vault)

    public double bankBalance(OfflinePlayer p) {
        if (!ready()) return 0;
        try {
            return (double) balance.invoke(eco, p);
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Takes up to the amount from the bank account (never below zero). Returns what was actually taken. */
    public double bankTake(OfflinePlayer p, double amount) {
        double have = bankBalance(p);
        double t = Math.floor(Math.min(have, amount) * 100) / 100.0;
        if (t <= 0) return 0;
        try {
            Object r = withdraw.invoke(eco, p, t);
            return success(r) ? t : 0;
        } catch (ReflectiveOperationException e) {
            return 0;
        }
    }

    /** Takes exactly this amount from the bank account, or nothing if it can't. */
    public boolean bankPay(OfflinePlayer p, double amount) {
        if (amount <= 0) return true;
        if (bankBalance(p) + 1e-6 < amount) return false;
        return bankTake(p, amount) + 0.005 >= Math.floor(amount * 100) / 100.0;
    }

    public void bankGive(OfflinePlayer p, double amount) {
        if (amount <= 0 || !ready()) return;
        try {
            deposit.invoke(eco, p, physical() ? Math.floor(amount * 100) / 100.0 : Math.floor(amount));
        } catch (ReflectiveOperationException ignored) {
        }
    }

    // ------------------------------------------------------------------ money in hand
    // With physical money on, an online player pays and is paid in coin; an offline one through the bank.

    /** What they can spend in person: the coin they carry (or, offline, the bank account). */
    public double balance(OfflinePlayer p) {
        Player o = online(p);
        return o != null ? mint.cash(o) : bankBalance(p);
    }

    /** Takes up to the amount (fines, robberies). Returns what was actually taken. */
    public double take(OfflinePlayer p, double amount) {
        Player o = online(p);
        return o != null ? mint.take(o, amount) : bankTake(p, amount);
    }

    /** Takes exactly this amount in coin (or from the bank, offline), or nothing if they can't. */
    public boolean pay(OfflinePlayer p, double amount) {
        Player o = online(p);
        if (o != null) return mint.pay(o, amount);
        if (bankBalance(p) < amount) return false;
        return bankTake(p, amount) >= Math.floor(amount);
    }

    /** Pays them: coins into their pockets when online, the bank account otherwise. */
    public void give(OfflinePlayer p, double amount) {
        Player o = online(p);
        if (o != null) mint.give(o, amount);
        else bankGive(p, amount);
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

    /** With cents when there are any: $12.50, $3, $1,204.10. */
    public static String fmt2(double v) {
        double r = Math.round(v * 100) / 100.0;
        if (Math.abs(r - Math.rint(r)) < 0.005) return "$" + String.format("%,d", Math.round(r));
        return "$" + String.format(java.util.Locale.ROOT, "%,.2f", r);
    }
}
