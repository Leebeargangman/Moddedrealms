package net.moddedrealms.smp.town;

import java.util.List;
import java.util.UUID;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Bounties. The town puts a price on every wanted player (per star) and on every bandit; players can add their own
 * money on top. Whoever brings the target in claims it: a citizen's arrest (the blow that would kill a wanted player
 * arrests them instead) pays the whole bounty, and a /tip that leads to an arrest within 10 minutes pays a share.
 */
public final class Bounties {
    private final TownManager t;

    Bounties(TownManager t) {
        this.t = t;
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    public double perStar() {
        return cfg().getDouble("town.bounty.per-star", 250);
    }

    public double of(TownData d) {
        return Math.max(0, d.stars) * perStar() + d.bountyPlayers;
    }

    private double tipShare() {
        return cfg().getDouble("town.bounty.tip-share", 0.5);
    }

    /** Called when someone's wanted level went up. */
    void posted(Player p, TownData d, int before) {
        if (d.stars < cfg().getInt("town.bounty.announce-from-stars", 2) || d.stars <= before) return;
        double b = of(d);
        p.sendMessage(SmpPlugin.mm("<gold>⚑ There's a <white>" + Money.fmt(b) + "</white> bounty on your head."));
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o == p) continue;
            o.sendMessage(SmpPlugin.mm("<gold>⚑ Bounty: <white>" + p.getName() + "</white> is wanted " + TownManager.stars(d.stars) + " <gray>for "
                    + d.reason + ". <gold>" + Money.fmt(b) + "</gold> <gray>for a citizen's arrest, or <yellow>/tip " + p.getName() + "</yellow> when you see them."));
        }
    }

    /** Pays out when a wanted player is arrested. stars = their level before it was cleared. */
    void payout(Player p, TownData d, int stars, Player arrester) {
        double total = stars * perStar() + d.bountyPlayers;
        d.bountyPlayers = 0;
        long now = System.currentTimeMillis();
        UUID tipper = d.tippedBy;
        d.tippedBy = null;
        if (arrester != null && arrester != p) {
            t.money.give(arrester, total);
            t.addRep(arrester, cfg().getInt("town.reputation.citizens-arrest", 10));
            arrester.sendMessage(SmpPlugin.mm("<gold>⚑ You brought in " + p.getName() + "! Bounty: <yellow>+" + Money.fmt(total)));
            arrester.playSound(arrester.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
            if (!t.data(arrester).cop) t.news(arrester.getName() + " made a citizen's arrest on " + p.getName() + "!");
            return;
        }
        if (tipper != null && now - d.tippedAt < 10 * 60_000L && !tipper.equals(p.getUniqueId())) {
            double share = Math.floor(total * tipShare());
            OfflinePlayer tp = Bukkit.getOfflinePlayer(tipper);
            t.money.give(tp, share);
            Player on = tp.getPlayer();
            if (on != null) on.sendMessage(SmpPlugin.mm("<gold>⚑ Your tip paid off: the watch caught " + p.getName() + ". <yellow>+" + Money.fmt(share)));
        }
    }

    // ------------------------------------------------------------------ /tip

    public void tip(Player reporter, String targetName) {
        long now = System.currentTimeMillis();
        TownData rd = t.data(reporter);
        if (now - rd.lastTip < 30_000) {
            reporter.sendMessage(SmpPlugin.mm("<gray>Give the watch a moment to act on your last tip."));
            return;
        }
        if (targetName == null) {
            tipBandit(reporter);
            return;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || target == reporter) {
            reporter.sendMessage(SmpPlugin.mm("<red>/tip <player>  — or /tip on its own to report a bandit you can see."));
            return;
        }
        TownData d = t.data(target);
        if (d.stars <= 0 && !d.caseOpen) {
            reporter.sendMessage(SmpPlugin.mm("<gray>Nobody is looking for " + target.getName() + " right now."));
            return;
        }
        if (target.getWorld() != reporter.getWorld() || target.getLocation().distance(reporter.getLocation()) > 64 || !reporter.hasLineOfSight(target)) {
            reporter.sendMessage(SmpPlugin.mm("<red>You have to be able to see " + target.getName() + " (within 64 blocks) to tip off the police."));
            return;
        }
        rd.lastTip = now;
        d.tippedBy = reporter.getUniqueId();
        d.tippedAt = now;
        d.lastSeenByPolice = now;
        String share = Money.fmt(Math.floor(of(d) * tipShare()));
        if (d.stars > 0 && t.inPoliceZone(target.getLocation())) {
            t.officers().dispatch(target);
            reporter.sendMessage(SmpPlugin.mm("<blue>[Watch] <gray>Thanks, the watch is on its way. If they catch " + target.getName()
                    + " in the next 10 minutes you get <gold>" + share + "</gold>."));
        } else if (d.caseOpen && t.bureau().inReach(target)) {
            t.bureau().ambush(target, Bureau.Kind.TIP);
            reporter.sendMessage(SmpPlugin.mm("<dark_gray>[Bureau] <gray>Noted. Agents are moving in. If they catch " + target.getName()
                    + " in the next 10 minutes you get <gold>" + share + "</gold>."));
        } else {
            reporter.sendMessage(SmpPlugin.mm("<gray>" + target.getName() + " is outside the watch's " + (int) t.policeRadius
                    + " blocks. Bring them in yourself (a citizen's arrest) to claim the whole <gold>" + Money.fmt(of(d)) + "</gold>."));
        }
    }

    private void tipBandit(Player reporter) {
        Walker best = null;
        double bd = 64;
        for (Walker b : t.walkers(Walker.Role.BANDIT)) {
            if (!b.valid() || b.world() != reporter.getWorld()) continue;
            double d = b.dist(reporter.getLocation());
            if (d < bd && reporter.hasLineOfSight(b.body)) {
                bd = d;
                best = b;
            }
        }
        if (best == null) {
            reporter.sendMessage(SmpPlugin.mm("<red>/tip <player> to report a wanted player, or stand where you can see a bandit and /tip."));
            return;
        }
        t.data(reporter).lastTip = System.currentTimeMillis();
        best.tipper = reporter.getUniqueId();
        best.tippedAt = System.currentTimeMillis();
        String share = Money.fmt(Math.floor(best.bounty * tipShare()));
        Location at = best.loc();
        if (t.inPoliceZone(at)) {
            t.officers().hunt(best);
            reporter.sendMessage(SmpPlugin.mm("<blue>[Watch] <gray>Officers are on their way to " + best.name + "<gray>. If they bring them in you get <gold>" + share + "</gold>."));
        } else if (best.stars >= cfg().getInt("town.bureau.from-stars", 3) && t.bureau().inReach(at)) {
            t.bureau().hunt(best);
            reporter.sendMessage(SmpPlugin.mm("<dark_gray>[Bureau] <gray>Agents are moving in on " + best.name + "<gray>. If they bring them in you get <gold>" + share + "</gold>."));
        } else {
            reporter.sendMessage(SmpPlugin.mm("<gray>That's too far out for the watch, and too small for the Bureau. Take " + best.name
                    + " <gray>down yourself for the full <gold>" + Money.fmt(best.bounty) + "</gold>."));
        }
    }

    // ------------------------------------------------------------------ /bounty and /bounties

    public void add(Player payer, String targetName, String amountText) {
        Player target = targetName == null ? null : Bukkit.getPlayerExact(targetName);
        if (target == null || amountText == null) {
            payer.sendMessage(SmpPlugin.mm("<yellow>/bounty <wanted player> <amount>  <gray>adds your money to their bounty."));
            return;
        }
        double amount;
        try {
            amount = Math.floor(Double.parseDouble(amountText));
        } catch (NumberFormatException e) {
            payer.sendMessage(SmpPlugin.mm("<red>Amount must be a number."));
            return;
        }
        double min = cfg().getDouble("town.bounty.min-player-bounty", 100);
        TownData d = t.data(target);
        if (target == payer) {
            payer.sendMessage(SmpPlugin.mm("<red>You can't put a bounty on yourself."));
        } else if (d.stars <= 0) {
            payer.sendMessage(SmpPlugin.mm("<red>You can only add to the bounty of someone who is wanted. <gray>(/wanted)"));
        } else if (amount < min) {
            payer.sendMessage(SmpPlugin.mm("<red>The smallest bounty is " + Money.fmt(min) + "."));
        } else if (!t.money.pay(payer, amount)) {
            payer.sendMessage(SmpPlugin.mm("<red>You can't afford that."));
        } else {
            d.bountyPlayers += amount;
            t.markDirty();
            for (Player o : Bukkit.getOnlinePlayers()) {
                o.sendMessage(SmpPlugin.mm("<gold>⚑ " + payer.getName() + " added <white>" + Money.fmt(amount) + "</white> to the bounty on "
                        + target.getName() + ". Total: <white>" + Money.fmt(of(d))));
            }
        }
    }

    public void list(CommandSender s) {
        List<Player> wanted = t.wantedOnline();
        List<Walker> bandits = t.walkers(Walker.Role.BANDIT);
        if (wanted.isEmpty() && bandits.isEmpty()) {
            s.sendMessage(SmpPlugin.mm("<green>No bounties right now. The town is quiet."));
            return;
        }
        s.sendMessage(SmpPlugin.mm("<gold><bold>Bounties</bold> <gray>(citizen's arrest pays it all, /tip pays " + Math.round(tipShare() * 100) + "%)"));
        for (Player p : wanted) {
            TownData d = t.data(p);
            s.sendMessage(SmpPlugin.mm(" " + TownManager.stars(d.stars) + " <white>" + p.getName() + " <gold>" + Money.fmt(of(d)) + " <gray>" + d.reason
                    + (d.caseOpen ? " <dark_gray>[Bureau case]" : "")));
        }
        for (Walker b : bandits) {
            Location l = b.loc();
            s.sendMessage(SmpPlugin.mm(" " + TownManager.stars(b.stars) + " " + b.name + " <gold>" + Money.fmt(b.bounty) + " <gray>last seen near "
                    + (l.getBlockX() / 50 * 50) + ", " + (l.getBlockZ() / 50 * 50)));
        }
    }
}
