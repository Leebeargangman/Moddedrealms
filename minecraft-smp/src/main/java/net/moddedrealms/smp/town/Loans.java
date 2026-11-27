package net.moddedrealms.smp.town;

import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Bank loans from Meadowbrook Savings & Loans (or the Old Town bank). The limit grows with your reputation and the
 * businesses you own. A loan is repaid in daily instalments taken from your balance automatically. A missed
 * instalment adds a 5% penalty; two missed in a row is a default: you're wanted for it, and when you're caught you
 * serve the maximum sentence and the bank seizes what it can from your balance and your businesses.
 */
public final class Loans {
    private final TownManager t;
    private int tick;

    Loans(TownManager t) {
        this.t = t;
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    public double limit(Player p) {
        TownData d = t.data(p);
        double lim = cfg().getDouble("town.loans.base-limit", 10000);
        for (Businesses.Biz b : t.businesses().ownedBy(p.getUniqueId())) lim += b.kind.startCost * b.level;
        lim += Math.max(0, d.rep) * cfg().getDouble("town.loans.per-reputation", 100);
        if (d.loanDefaults > 0) lim /= (1 + d.loanDefaults);
        return Math.min(cfg().getDouble("town.loans.max", 500000), Math.floor(lim / 1000) * 1000);
    }

    private boolean atBank(Player p) {
        if (p.hasPermission("mrsmp.admin")) return true;
        for (String id : List.of("mb_banker", "banker")) {
            Entity e = t.plugin().npcs().entity(id);
            if (e != null && e.getWorld() == p.getWorld() && e.getLocation().distance(p.getLocation()) < 10) return true;
        }
        return false;
    }

    public void command(Player p, String[] a) {
        TownData d = t.data(p);
        String sub = a.length == 0 ? "" : a[0].toLowerCase();
        double rate = cfg().getDouble("town.loans.interest", 0.15);
        int days = cfg().getInt("town.loans.days", 7);
        switch (sub) {
            case "take" -> {
                if (!atBank(p)) {
                    say(p, "<gray>Loans are arranged in person: Moneylender Oakes in Meadowbrook, or the moneylender at the Old Town counting house.");
                    return;
                }
                if (d.loanOwed > 0) {
                    say(p, "<red>Pay off your current loan first (" + Money.fmt(d.loanOwed) + " owed).");
                    return;
                }
                if (d.stars > 0 || d.jailed()) {
                    say(p, "<red>\"We don't lend to people the watch is looking for.\"");
                    return;
                }
                double amt;
                try {
                    amt = Math.floor(Double.parseDouble(a.length > 1 ? a[1] : "0"));
                } catch (NumberFormatException e) {
                    amt = 0;
                }
                double lim = limit(p);
                if (amt < 1000 || amt > lim) {
                    say(p, "<red>You can borrow between $1,000 and " + Money.fmt(lim) + ".");
                    return;
                }
                d.loanOwed = Math.ceil(amt * (1 + rate));
                d.loanInstalment = Math.ceil(d.loanOwed / days);
                d.loanNextDue = System.currentTimeMillis() + 86_400_000L;
                d.loanMissed = 0;
                d.loanDefaulted = false;
                t.money.give(p, amt);
                t.markDirty();
                say(p, "<green>The counting house lends you " + Money.fmt(amt) + ". <gray>You owe " + Money.fmt(d.loanOwed) + ", repaid " + Money.fmt(d.loanInstalment)
                        + " a day from your balance for " + days + " days. <red>Miss two payments in a row and it's a crime.");
                t.news(p.getName() + " took out a loan at the counting house.");
            }
            case "repay" -> {
                if (d.loanOwed <= 0) {
                    say(p, "<gray>You don't owe the counting house anything.");
                    return;
                }
                double amt = a.length > 1 && !a[1].equalsIgnoreCase("all") ? num(a[1]) : d.loanOwed;
                amt = Math.min(amt, d.loanOwed);
                if (amt <= 0 || !t.money.pay(p, amt)) {
                    say(p, "<red>You can't pay " + Money.fmt(amt) + ".");
                    return;
                }
                d.loanOwed -= amt;
                if (d.loanOwed < 1) settle(p, d);
                else say(p, "<green>Paid " + Money.fmt(amt) + ". " + Money.fmt(d.loanOwed) + " to go.");
                t.markDirty();
            }
            default -> {
                if (d.loanOwed > 0) {
                    long due = Math.max(0, d.loanNextDue - System.currentTimeMillis());
                    say(p, "<gold>Your loan: <white>" + Money.fmt(d.loanOwed) + "</white> owed, next payment " + Money.fmt(Math.min(d.loanInstalment, d.loanOwed)) + " in "
                            + due / 3_600_000 + "h " + due / 60_000 % 60 + "m" + (d.loanMissed > 0 ? " <red>(" + d.loanMissed + " missed!)" : "")
                            + (d.loanDefaulted ? " <dark_red>IN DEFAULT" : "") + ". <gray>/loan repay <amount|all>");
                    return;
                }
                double lim = limit(p);
                say(p, "<gold>Counting House: <gray>you can borrow up to <white>" + Money.fmt(lim) + "</white> at " + Math.round(rate * 100) + "% interest, repaid over " + days + " days.");
                var line = Component.text().append(SmpPlugin.mm("<gray>Borrow: "));
                for (double amt : new double[]{5000, 20000, 50000, 100000, 250000}) {
                    if (amt > lim) break;
                    line.append(SmpPlugin.mm("<green>[" + Money.fmt(amt) + "] ").clickEvent(ClickEvent.runCommand("/loan take " + (long) amt)));
                }
                p.sendMessage(line.build());
            }
        }
    }

    private static double num(String s) {
        try {
            return Math.floor(Double.parseDouble(s));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void settle(Player p, TownData d) {
        d.loanOwed = 0;
        d.loanMissed = 0;
        d.loanDefaulted = false;
        t.addRep(p, 5);
        say(p, "<green>Loan paid off in full. The counting house thanks you. <gray>(+5 reputation, a bigger limit next time)");
    }

    private static void say(Player p, String m) {
        p.sendMessage(SmpPlugin.mm(m));
    }

    /** Every second; instalments are checked once a minute. */
    void second() {
        if (++tick % 60 != 0) return;
        long now = System.currentTimeMillis();
        for (UUID id : t.knownPlayers()) {
            TownData d = t.dataIfKnown(id);
            if (d == null || d.loanOwed <= 0 || now < d.loanNextDue) continue;
            OfflinePlayer o = Bukkit.getOfflinePlayer(id);
            Player p = o.getPlayer();
            double inst = Math.min(d.loanInstalment, d.loanOwed);
            d.loanNextDue += 86_400_000L;
            if (t.money.bankPay(o, inst)) {
                d.loanOwed -= inst;
                d.loanMissed = 0;
                if (p != null) p.sendMessage(SmpPlugin.mm("<gray>Loan payment: <white>-" + Money.fmt(inst) + "</white>, " + Money.fmt(Math.max(0, d.loanOwed)) + " left."));
                if (d.loanOwed < 1 && p != null) settle(p, d);
                else if (d.loanOwed < 1) d.loanOwed = 0;
            } else {
                d.loanMissed++;
                d.loanOwed = Math.ceil(d.loanOwed * 1.05);
                if (p != null) p.sendMessage(SmpPlugin.mm("<red>You missed a loan payment of " + Money.fmt(inst) + ". +5% penalty. "
                        + (d.loanMissed >= 2 ? "<dark_red>That's two: you're in default." : "Miss another and it's a crime.")));
                if (d.loanMissed >= 2 && !d.loanDefaulted) {
                    d.loanDefaulted = true;
                    d.loanDefaults++;
                    if (p != null) t.wanted(p, Crime.LOAN_DEFAULT, null);
                    t.news((o.getName() == null ? "Someone" : o.getName()) + " defaulted on a counting house loan. The watch is looking for them.");
                }
            }
            t.markDirty();
        }
    }

    /** On join: a defaulter who was offline when it happened is now wanted. */
    void joined(Player p) {
        TownData d = t.data(p);
        if (d.loanDefaulted && d.stars == 0 && !d.jailed()) t.wanted(p, Crime.LOAN_DEFAULT, null);
    }

    /** On arrest: the bank takes what it can; returns true if the loan was in default (maximum sentence). */
    boolean seize(Player p, TownData d) {
        if (!d.loanDefaulted) return false;
        double got = t.money.take(p, d.loanOwed);
        for (Businesses.Biz b : t.businesses().ownedBy(p.getUniqueId())) {
            if (got >= d.loanOwed) break;
            double take = Math.min(Math.max(0, b.cash), d.loanOwed - got);
            b.cash -= take;
            got += take;
        }
        d.loanOwed = Math.max(0, d.loanOwed - got);
        d.loanDefaulted = false;
        d.loanMissed = 0;
        d.loanNextDue = System.currentTimeMillis() + 86_400_000L;
        p.sendMessage(SmpPlugin.mm("<red>The counting house seized " + Money.fmt(got) + " towards your debt." + (d.loanOwed > 0 ? " You still owe " + Money.fmt(d.loanOwed) + ": keep paying." : " The loan is closed.")));
        t.markDirty();
        return true;
    }
}
