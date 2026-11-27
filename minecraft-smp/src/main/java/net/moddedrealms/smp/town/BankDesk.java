package net.moddedrealms.smp.town;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * The bank counter and your purse. Cash is metal (see {@link Mint}); your account is safe from robbers, pays your
 * rent and loans, takes your auction and stall takings, and buys your shares. Deposits and withdrawals happen at the
 * Old Town bank, at today's worth of each metal.
 */
final class BankDesk implements TabExecutor, Listener {
    private final TownManager t;
    private final Set<UUID> told = new HashSet<>();

    BankDesk(TownManager t) {
        this.t = t;
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    private boolean atBank(Player p) {
        if (cfg().getBoolean("currency.bank-anywhere", false) || p.hasPermission("mrsmp.admin")) return true;
        Bank b = t.bank();
        return b == null || !b.built() || b.inside(p.getLocation());
    }

    private static boolean say(CommandSender s, String m) {
        s.sendMessage(SmpPlugin.mm(m));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (!(sender instanceof Player p)) return say(sender, "Players only.");
        Money m = t.money;
        if (cmd.getName().equalsIgnoreCase("purse")) return purse(p);
        if (!m.physical()) return say(p, "<gray>Your balance: <white>" + Money.fmt2(m.bankBalance(p)));
        String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "", "balance", "info" -> {
                say(p, "<gold><bold>Bank account</bold></gold> <white>" + Money.fmt2(m.bankBalance(p)) + "</white> <gray>· in your purse <white>" + Money.fmt2(t.mint().cash(p)));
                say(p, "<gray>Today's worth of coin:");
                for (String l : t.mint().board()) say(p, " " + l);
                say(p, "<white>/bank deposit <amount|all></white> <gray>· <white>/bank withdraw <amount></white> <gray>(at the Old Town bank) · <white>/bank send <player> <amount></white>");
            }
            case "rates" -> {
                for (String l : t.mint().board()) say(p, " " + l);
            }
            case "deposit", "dep" -> {
                if (!atBank(p)) return say(p, "<red>Deposits are made at the bank in the Old Town. <gray>(The money in your pockets can be robbed: bank it.)");
                if (t.data(p).jailed()) return say(p, "<red>Not from a cell.");
                double cash = t.mint().cash(p);
                double amt;
                if (a.length < 2 || a[1].equalsIgnoreCase("all")) amt = cash;
                else {
                    try {
                        amt = Double.parseDouble(a[1].replace("$", "").replace(",", ""));
                    } catch (NumberFormatException e) {
                        return say(p, "<red>/bank deposit <amount|all>");
                    }
                }
                amt = Math.floor(amt * 100) / 100.0;
                if (amt < 0.01) return say(p, "<gray>You have no coin on you.");
                if (amt > cash + 1e-6) return say(p, "<red>You only carry " + Money.fmt2(cash) + ".");
                if (!t.mint().pay(p, amt)) return say(p, "<red>The teller couldn't count that out.");
                m.bankGive(p, amt);
                say(p, "<green>Deposited " + Money.fmt2(amt) + ". <gray>Balance: <white>" + Money.fmt2(m.bankBalance(p)));
            }
            case "withdraw", "with" -> {
                if (!atBank(p)) return say(p, "<red>Withdrawals are made at the bank in the Old Town.");
                if (t.data(p).jailed()) return say(p, "<red>Not from a cell.");
                if (a.length < 2) return say(p, "<red>/bank withdraw <amount>");
                double amt;
                try {
                    amt = Math.floor(Double.parseDouble(a[1].replace("$", "").replace(",", "")) * 100) / 100.0;
                } catch (NumberFormatException e) {
                    return say(p, "<red>/bank withdraw <amount>");
                }
                if (amt < 0.01) return true;
                if (amt > m.bankBalance(p) + 1e-6) return say(p, "<red>Your account holds " + Money.fmt2(m.bankBalance(p)) + ".");
                if (!m.bankPay(p, amt)) return say(p, "<red>The withdrawal failed.");
                t.mint().give(p, amt);
                say(p, "<green>Withdrew " + Money.fmt2(amt) + " in coin. <gray>Balance: <white>" + Money.fmt2(m.bankBalance(p)));
            }
            case "send", "transfer", "pay" -> {
                if (a.length < 3) return say(p, "<red>/bank send <player> <amount>");
                OfflinePlayer o = Bukkit.getOfflinePlayer(a[1]);
                if (o.getName() == null || !o.hasPlayedBefore() && !o.isOnline()) return say(p, "<red>No account for " + SmpPlugin.MM.escapeTags(a[1]) + ".");
                if (o.getUniqueId().equals(p.getUniqueId())) return true;
                double amt;
                try {
                    amt = Math.floor(Double.parseDouble(a[2].replace("$", "").replace(",", "")) * 100) / 100.0;
                } catch (NumberFormatException e) {
                    return say(p, "<red>/bank send <player> <amount>");
                }
                if (amt < 1) return say(p, "<red>Transfers start at $1.");
                if (!m.bankPay(p, amt)) return say(p, "<red>Your account doesn't hold " + Money.fmt2(amt) + ".");
                m.bankGive(o, amt);
                say(p, "<green>Sent " + Money.fmt2(amt) + " to " + o.getName() + "'s account.");
                if (o.getPlayer() != null) say(o.getPlayer(), "<gold>[Bank] <white>" + p.getName() + "</white> <gray>sent <green>" + Money.fmt2(amt) + "</green> to your account.");
            }
            default -> say(p, "<gray>/bank <white>[deposit|withdraw|send|rates]");
        }
        return true;
    }

    private boolean purse(Player p) {
        Money m = t.money;
        if (!m.physical()) return say(p, "<gray>Your balance: <white>" + Money.fmt2(m.bankBalance(p)));
        var purse = t.mint().purse(p);
        say(p, "<gold>Your purse: <white>" + Money.fmt2(t.mint().cash(p)) + "</white> <gray>in coin" + (purse.isEmpty() ? "." : ": " + Mint.describe(purse) + "."));
        say(p, "<gray>Bank account: <white>" + Money.fmt2(m.bankBalance(p)) + " <dark_gray>(/bank)");
        return true;
    }

    /** The first time after money turned physical: where everyone's dollars went. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!t.money.physical()) return;
        if (told.contains(p.getUniqueId())) return;
        told.add(p.getUniqueId());
        if (t.data(p).seenCoinNotice) return;
        t.data(p).seenCoinNotice = true;
        t.markDirty();
        Bukkit.getScheduler().runTaskLater(t.plugin(), () -> {
            if (!p.isOnline()) return;
            p.sendMessage(SmpPlugin.mm("<gold><bold>Money is metal now.</bold></gold> <gray>Coins are <white>iron, gold, copper, emeralds, diamonds and netherite</white>; "
                    + "what each is worth rises and falls with how much is being spent. Your savings (" + Money.fmt2(t.money.bankBalance(p))
                    + ") are in your <white>bank account</white>: withdraw coin at the Old Town bank with <white>/bank withdraw <amount></white>. <white>/purse</white> shows what you carry."));
        }, 120L);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (c.getName().equalsIgnoreCase("bank") && a.length == 1) {
            for (String x : List.of("deposit", "withdraw", "send", "rates")) if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        }
        return out;
    }
}
