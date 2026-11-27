package net.moddedrealms.smp.town;

import net.moddedrealms.smp.SafeYaml;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Physical money. Cash is metal: copper, gold, iron, emerald, diamond and netherite (nuggets, ingots and blocks of
 * iron and gold). Each metal has a worth in dollars that is its configured price when the economy is balanced, and
 * it floats: every unit players spend pushes that metal's worth down (there's more of it about), every unit paid out
 * pushes it back up, and it drifts home over a day or two. So someone who mines a mountain of gold and spends it all
 * finds gold buying less and less. Payments take real items and give change; payouts hand over real items, made
 * mostly in whichever metal is plentiful, and whatever doesn't fit (or is smaller than the smallest coin) goes to the
 * player's bank account. The bank account itself is the Vault balance.
 */
final class Mint {
    enum Metal {
        COPPER, GOLD, IRON, EMERALD, DIAMOND, NETHERITE
    }

    /** One kind of coin: an item, which metal it is, and how many ingots of it it holds. */
    record Coin(Material item, Metal metal, double units, boolean payout) {
    }

    private static final List<Coin> COINS = List.of(
            new Coin(Material.NETHERITE_INGOT, Metal.NETHERITE, 1, false),
            new Coin(Material.DIAMOND, Metal.DIAMOND, 1, false),
            new Coin(Material.EMERALD, Metal.EMERALD, 1, false),
            new Coin(Material.IRON_BLOCK, Metal.IRON, 9, true),
            new Coin(Material.GOLD_BLOCK, Metal.GOLD, 9, true),
            new Coin(Material.IRON_INGOT, Metal.IRON, 1, true),
            new Coin(Material.GOLD_INGOT, Metal.GOLD, 1, true),
            new Coin(Material.IRON_NUGGET, Metal.IRON, 1.0 / 9, true),
            new Coin(Material.COPPER_INGOT, Metal.COPPER, 1, true),
            new Coin(Material.GOLD_NUGGET, Metal.GOLD, 1.0 / 9, true));

    private final TownManager t;
    private final File file;
    private final Map<Metal, Double> net = new EnumMap<>(Metal.class);
    private final Map<UUID, Long> hinted = new HashMap<>();
    private long lastDecay = System.currentTimeMillis();
    private boolean dirty;

    Mint(TownManager t) {
        this.t = t;
        this.file = new File(t.plugin().getDataFolder(), "mint.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (Metal m : Metal.values()) net.put(m, y.getDouble("net." + m.name()));
        lastDecay = y.getLong("last-decay", lastDecay);
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    boolean enabled() {
        return cfg().getBoolean("currency.physical", true);
    }

    void save() {
        if (!dirty) return;
        dirty = false;
        YamlConfiguration y = new YamlConfiguration();
        for (var e : net.entrySet()) y.set("net." + e.getKey().name(), Math.round(e.getValue() * 1000) / 1000.0);
        y.set("last-decay", lastDecay);
        try {
            SafeYaml.save(y, file);
        } catch (IOException ignored) {
        }
    }

    // ------------------------------------------------------------------ worth

    /** The balanced worth of one ingot of the metal. */
    double base(Metal m) {
        double d = switch (m) {
            case COPPER -> 2;
            case GOLD -> 10;
            case IRON -> 30;
            case EMERALD -> 60;
            case DIAMOND -> 250;
            case NETHERITE -> 2500;
        };
        return Math.max(0.01, cfg().getDouble("currency.worth." + m.name().toLowerCase(Locale.ROOT), d));
    }

    /** How many ingots of it in circulation count as "a lot" (worth falls to about half). */
    private double normal(Metal m) {
        double d = switch (m) {
            case COPPER -> 4000;
            case GOLD, IRON -> 3000;
            case EMERALD -> 1500;
            case DIAMOND -> 400;
            case NETHERITE -> 40;
        };
        return Math.max(1, cfg().getDouble("currency.normal." + m.name().toLowerCase(Locale.ROOT), d));
    }

    /** What one ingot of the metal is worth now. */
    double worth(Metal m) {
        decay();
        double n = normal(m);
        double x = Math.max(-0.8 * n, net.getOrDefault(m, 0.0));
        double f = Math.pow(n / (n + x), cfg().getDouble("currency.elasticity", 1.0));
        f = Math.max(cfg().getDouble("currency.floor", 0.3), Math.min(cfg().getDouble("currency.ceiling", 2.5), f));
        return base(m) * f;
    }

    double worth(Coin c) {
        return worth(c.metal()) * c.units();
    }

    /** Worth now as a share of the balanced worth (1.0 = balanced). */
    double ratio(Metal m) {
        return worth(m) / base(m);
    }

    /** Circulation drifts back to balance: half-life currency.recover-hours. */
    private void decay() {
        long now = System.currentTimeMillis();
        double hours = (now - lastDecay) / 3_600_000.0;
        if (hours < 0.05) return;
        lastDecay = now;
        double half = Math.max(1, cfg().getDouble("currency.recover-hours", 24));
        double k = Math.pow(0.5, hours / half);
        for (Metal m : Metal.values()) net.put(m, net.getOrDefault(m, 0.0) * k);
        dirty = true;
    }

    private void flow(Metal m, double units) {
        net.merge(m, units, Double::sum);
        dirty = true;
    }

    static Coin coin(ItemStack it) {
        if (it == null || it.getType().isAir()) return null;
        for (Coin c : COINS) {
            if (c.item() == it.getType()) return Market.plain(it) ? c : null;
        }
        return null;
    }

    static boolean isCurrency(Material m) {
        for (Coin c : COINS) if (c.item() == m) return true;
        return false;
    }

    // ------------------------------------------------------------------ wallets

    /** The cash a player carries (main inventory and off hand), in dollars. */
    double cash(Player p) {
        double v = 0;
        PlayerInventory inv = p.getInventory();
        for (ItemStack it : inv.getStorageContents()) {
            Coin c = coin(it);
            if (c != null) v += worth(c) * it.getAmount();
        }
        Coin off = coin(inv.getItemInOffHand());
        if (off != null) v += worth(off) * inv.getItemInOffHand().getAmount();
        return v;
    }

    /** What the player carries, as "3 iron ingots, 12 gold nuggets". */
    Map<Coin, Integer> purse(Player p) {
        Map<Coin, Integer> out = new LinkedHashMap<>();
        for (ItemStack it : p.getInventory().getStorageContents()) {
            Coin c = coin(it);
            if (c != null) out.merge(c, it.getAmount(), Integer::sum);
        }
        Coin off = coin(p.getInventory().getItemInOffHand());
        if (off != null) out.merge(off, p.getInventory().getItemInOffHand().getAmount(), Integer::sum);
        return out;
    }

    /**
     * Pays exactly this amount from the player's cash: the coins are taken (largest first, without overpaying when
     * smaller coins do), change is given, and any part of a cent goes to their bank account. False if they don't
     * carry enough.
     */
    boolean pay(Player p, double amount) {
        if (amount <= 0) return true;
        double have = cash(p);
        if (have + 1e-6 < amount) {
            hint(p, amount, have);
            return false;
        }
        Map<Coin, Integer> purse = purse(p);
        List<Coin> order = new ArrayList<>(purse.keySet());
        order.sort((a, b) -> Double.compare(worth(b), worth(a)));
        Map<Coin, Integer> take = new LinkedHashMap<>();
        double left = amount;
        for (Coin c : order) {
            double w = worth(c);
            int n = (int) Math.min(purse.get(c), Math.floor((left + 1e-9) / w));
            if (n > 0) {
                take.merge(c, n, Integer::sum);
                left -= n * w;
            }
        }
        if (left > 1e-6) {
            // the smallest coin that covers what's left (they'll get change)
            Coin best = null;
            for (Coin c : order) {
                int spare = purse.get(c) - take.getOrDefault(c, 0);
                if (spare > 0 && worth(c) + 1e-9 >= left && (best == null || worth(c) < worth(best))) best = c;
            }
            if (best == null) {
                // only small coins left, and not enough of them together: take whatever's left until it's covered
                for (int i = order.size() - 1; i >= 0 && left > 1e-6; i--) {
                    Coin c = order.get(i);
                    int spare = purse.get(c) - take.getOrDefault(c, 0);
                    int n = (int) Math.min(spare, Math.ceil(left / worth(c)));
                    if (n > 0) {
                        take.merge(c, n, Integer::sum);
                        left -= n * worth(c);
                    }
                }
            } else {
                take.merge(best, 1, Integer::sum);
                left -= worth(best);
            }
        }
        if (left > 1e-6) {
            hint(p, amount, have);
            return false;
        }
        double paid = 0;
        for (var e : take.entrySet()) {
            remove(p.getInventory(), e.getKey().item(), e.getValue());
            paid += worth(e.getKey()) * e.getValue();
        }
        for (var e : take.entrySet()) flow(e.getKey().metal(), e.getKey().units() * e.getValue());
        double change = paid - amount;
        String took = describe(take);
        if (change >= 0.01) {
            Map<Coin, Integer> back = payout(p, change);
            p.sendActionBar(SmpPlugin.mm("<gray>Paid " + Money.fmt2(amount) + ": " + took + (back.isEmpty() ? "" : " · change " + describe(back))));
        } else {
            p.sendActionBar(SmpPlugin.mm("<gray>Paid " + Money.fmt2(amount) + ": " + took));
        }
        return true;
    }

    /** Takes up to this much cash (fines, robberies). Returns what was taken. */
    double take(Player p, double amount) {
        double have = cash(p);
        double n = Math.min(have, amount);
        if (n < 0.01) return 0;
        return pay(p, n) ? n : 0;
    }

    /** Hands over this much as coins. Whatever won't fit, and the odd part of a cent, goes to the bank account. */
    void give(Player p, double amount) {
        if (amount < 0.005) return;
        payout(p, amount);
    }

    private Map<Coin, Integer> payout(Player p, double amount) {
        Map<Coin, Integer> give = new LinkedHashMap<>();
        double left = amount;
        // plentiful metals first, so paying out is what brings a glut back into balance; big coins before small
        List<Coin> pay = new ArrayList<>();
        for (Coin c : COINS) if (c.payout() && cfg().getBoolean("currency.payout." + c.item().name().toLowerCase(Locale.ROOT), true)) pay.add(c);
        pay.sort((a, b) -> {
            double wa = worth(a), wb = worth(b);
            if (Math.abs(wa - wb) > 0.5) return Double.compare(wb, wa);
            return Double.compare(ratio(a.metal()), ratio(b.metal()));
        });
        for (Coin c : pay) {
            double w = worth(c);
            int n = (int) Math.floor((left + 1e-9) / w);
            if (n <= 0) continue;
            give.put(c, n);
            left -= n * w;
        }
        double toBank = Math.max(0, left);
        Map<Coin, Integer> given = new LinkedHashMap<>();
        for (var e : give.entrySet()) {
            Coin c = e.getKey();
            int remaining = e.getValue(), handed = 0;
            while (remaining > 0) {
                int st = Math.min(remaining, c.item().getMaxStackSize());
                int didnt = 0;
                for (ItemStack it : p.getInventory().addItem(new ItemStack(c.item(), st)).values()) didnt += it.getAmount();
                handed += st - didnt;
                remaining -= st;
                if (didnt > 0) {
                    toBank += (didnt + remaining) * worth(c);
                    remaining = 0;
                }
            }
            if (handed > 0) {
                given.put(c, handed);
                flow(c.metal(), -c.units() * handed);
            }
        }
        give = given;
        give.values().removeIf(v -> v <= 0);
        if (toBank >= 0.01) {
            t.money.bankGive(p, Math.round(toBank * 100) / 100.0);
            if (toBank >= 1) p.sendMessage(SmpPlugin.mm("<gray>" + Money.fmt2(toBank) + " didn't fit in your pockets and went to your bank account."));
        }
        return give;
    }

    private static void remove(PlayerInventory inv, Material m, int n) {
        ItemStack[] c = inv.getStorageContents();
        for (int i = 0; i < c.length && n > 0; i++) {
            ItemStack it = c[i];
            if (it == null || it.getType() != m || !Market.plain(it)) continue;
            int take = Math.min(n, it.getAmount());
            it.setAmount(it.getAmount() - take);
            c[i] = it.getAmount() <= 0 ? null : it;
            n -= take;
        }
        inv.setStorageContents(c);
        if (n > 0) {
            ItemStack off = inv.getItemInOffHand();
            if (off.getType() == m && Market.plain(off)) {
                int take = Math.min(n, off.getAmount());
                off.setAmount(off.getAmount() - take);
                inv.setItemInOffHand(off.getAmount() <= 0 ? null : off);
            }
        }
    }

    static String describe(Map<Coin, Integer> coins) {
        List<String> parts = new ArrayList<>();
        for (var e : coins.entrySet()) parts.add(e.getValue() + " " + Market.nice(e.getKey().item()).toLowerCase(Locale.ROOT) + (e.getValue() == 1 ? "" : "s"));
        return String.join(", ", parts);
    }

    private void hint(Player p, double amount, double have) {
        Long at = hinted.get(p.getUniqueId());
        long now = System.currentTimeMillis();
        if (at != null && now - at < 20_000) return;
        hinted.put(p.getUniqueId(), now);
        double bank = t.money.bankBalance(p);
        p.sendMessage(SmpPlugin.mm("<gray>You carry " + Money.fmt2(have) + " in coin and need " + Money.fmt2(amount) + "."
                + (bank >= amount - have ? " <white>Withdraw some at the bank:</white> <click:suggest_command:'/bank withdraw '><yellow><u>/bank withdraw <amount></u></yellow></click> <gray>(you have "
                + Money.fmt2(bank) + " there)." : "")));
    }

    /** The rate board: one line per metal. */
    List<String> board() {
        List<String> out = new ArrayList<>();
        for (Metal m : Metal.values()) {
            double r = ratio(m);
            String arrow = r > 1.02 ? "<green>▲ " + Math.round((r - 1) * 100) + "%" : r < 0.98 ? "<red>▼ " + Math.round((1 - r) * 100) + "%" : "<gray>■ balanced";
            out.add("<white>" + cap(m.name()) + (m == Metal.IRON || m == Metal.GOLD ? " ingot" : "") + " <gold>" + Money.fmt2(worth(m)) + " " + arrow
                    + (m == Metal.IRON || m == Metal.GOLD ? " <dark_gray>(nugget " + Money.fmt2(worth(m) / 9) + ", block " + Money.fmt2(worth(m) * 9) + ")" : ""));
        }
        return out;
    }

    private static String cap(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    /** For admins: the circulation numbers. */
    String status() {
        StringBuilder b = new StringBuilder("Circulation (ingots, + = glut): ");
        for (Metal m : Metal.values()) b.append(m.name().toLowerCase(Locale.ROOT)).append(' ').append(Math.round(net.getOrDefault(m, 0.0))).append("  ");
        return b.toString();
    }

    void adjust(Metal m, double units) {
        flow(m, units);
    }
}
