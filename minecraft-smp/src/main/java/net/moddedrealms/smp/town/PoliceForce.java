package net.moddedrealms.smp.town;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.format.TextDecoration;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Vector;

/**
 * Players in the Town Watch. Respected players sign up with Chief Warden Holt; they get a uniform, radio calls about
 * wanted players, can cuff low-level suspects (sneak + right-click), arrest anyone they beat down, and are paid per
 * arrest and for time on duty. Ranks come from arrests. Breaking the law gets them dismissed.
 */
public final class PoliceForce {
    public static final String[] RANKS = {"Cadet", "Officer", "Sergeant", "Captain", "Chief"};
    private static final int[] RANK_AT = {0, 5, 15, 30, 50};

    private final TownManager t;

    PoliceForce(TownManager t) {
        this.t = t;
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    public int rankIndex(TownData d) {
        int r = 0;
        for (int i = 0; i < RANK_AT.length; i++) if (d.copArrests >= RANK_AT[i]) r = i;
        return r;
    }

    public String rank(TownData d) {
        return RANKS[rankIndex(d)];
    }

    private double mult(TownData d) {
        return 1 + 0.25 * rankIndex(d);
    }

    public boolean onDuty(Player p) {
        TownData d = t.data(p);
        return d.cop && d.onDuty;
    }

    // ------------------------------------------------------------------ joining

    private boolean atStation(Player p) {
        Entity warden = t.plugin().npcs().entity("warden");
        if (warden != null && warden.getWorld() == p.getWorld() && warden.getLocation().distance(p.getLocation()) <= 10) return true;
        Location rel = t.jail().releasePoint();
        return rel != null && rel.getWorld() == p.getWorld() && rel.distance(p.getLocation()) <= 20;
    }

    public void join(Player p) {
        TownData d = t.data(p);
        if (d.cop) {
            info(p);
            return;
        }
        int need = cfg().getInt("town.reputation.respected", 100);
        if (!atStation(p)) {
            p.sendMessage(SmpPlugin.mm("<gray>Sign up at the guardhouse, with Chief Warden Holt. <dark_gray>(south-east of the fountain)"));
        } else if (d.rep < need) {
            p.sendMessage(SmpPlugin.mm("<red>The Watch only takes respected townsfolk (reputation " + need + "+, you have " + d.rep + "). <gray>Quests and good deeds raise it."));
        } else if (d.stars > 0 || d.jailed() || d.caseOpen) {
            p.sendMessage(SmpPlugin.mm("<red>Not while you're wanted."));
        } else {
            d.cop = true;
            d.onDuty = true;
            d.dutySince = System.currentTimeMillis();
            t.markDirty();
            uniform(p);
            p.sendMessage(SmpPlugin.mm("<blue><bold>Welcome to the Town Watch, Cadet " + p.getName() + "!</bold>"));
            p.sendMessage(SmpPlugin.mm("<gray>• On duty you hear the radio when someone becomes wanted. <white>/police radar</white> points you to them."));
            p.sendMessage(SmpPlugin.mm("<gray>• <white>Sneak + right-click</white> a wanted player (empty hand) to cuff them, if they're at ★★ or below, or worn down."));
            p.sendMessage(SmpPlugin.mm("<gray>• The blow that would kill a wanted player arrests them instead. You get their bounty and your pay."));
            p.sendMessage(SmpPlugin.mm("<gray>• Pay rises with rank: " + String.join(" → ", RANKS) + ". <white>/police duty</white> to go off duty, <white>/police quit</white> to leave."));
            p.sendMessage(SmpPlugin.mm("<red>• Break the law and you're out."));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            t.news(p.getName() + " joined the Town Watch!");
        }
    }

    private void uniform(Player p) {
        String col = cfg().getString("town.officer-uniform-color", "#1F2A44");
        List<ItemStack> kit = new ArrayList<>();
        for (Material m : new Material[]{Material.LEATHER_CHESTPLATE, Material.LEATHER_LEGGINGS, Material.LEATHER_BOOTS}) {
            ItemStack it = new ItemStack(m);
            LeatherArmorMeta meta = (LeatherArmorMeta) it.getItemMeta();
            meta.setColor(Color.fromRGB(Integer.parseInt(col.substring(1), 16)));
            meta.displayName(SmpPlugin.mm("<blue>Town Watch Uniform").decoration(TextDecoration.ITALIC, false));
            it.setItemMeta(meta);
            kit.add(it);
        }
        for (ItemStack left : p.getInventory().addItem(kit.toArray(new ItemStack[0])).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    public void quit(Player p) {
        TownData d = t.data(p);
        if (!d.cop) {
            p.sendMessage(SmpPlugin.mm("<gray>You're not in the Watch."));
            return;
        }
        d.cop = false;
        d.onDuty = false;
        t.markDirty();
        p.sendMessage(SmpPlugin.mm("<gray>You hand in your badge. Thanks for your service, " + rank(d) + "."));
    }

    public void duty(Player p) {
        TownData d = t.data(p);
        if (!d.cop) {
            join(p);
            return;
        }
        d.onDuty = !d.onDuty;
        d.dutySince = System.currentTimeMillis();
        t.markDirty();
        p.sendMessage(SmpPlugin.mm(d.onDuty ? "<blue>[Watch] <green>You're on duty." : "<blue>[Watch] <gray>You're off duty. No radio, no pay."));
    }

    public void info(Player p) {
        TownData d = t.data(p);
        if (!d.cop) {
            join(p);
            return;
        }
        int r = rankIndex(d);
        String next = r + 1 < RANKS.length ? " <gray>(" + (RANK_AT[r + 1] - d.copArrests) + " more arrests to " + RANKS[r + 1] + ")" : "";
        p.sendMessage(SmpPlugin.mm("<blue>[Watch] <white>" + RANKS[r] + " " + p.getName() + "<gray>, " + d.copArrests + " arrests" + next
                + ". " + (d.onDuty ? "<green>On duty" : "<gray>Off duty") + "<gray>. /police radar | duty | quit"));
    }

    public void radar(Player p) {
        if (!onDuty(p)) {
            p.sendMessage(SmpPlugin.mm("<gray>Only guards on duty have the radio."));
            return;
        }
        List<Player> wanted = t.wantedOnline();
        wanted.remove(p);
        if (wanted.isEmpty()) {
            p.sendMessage(SmpPlugin.mm("<blue>[Radio] <gray>All quiet. Nobody is wanted."));
            return;
        }
        for (Player w : wanted) {
            TownData d = t.data(w);
            String where = w.getWorld() != p.getWorld() ? "in another world" : (int) w.getLocation().distance(p.getLocation()) + "m " + direction(p.getLocation(), w.getLocation());
            p.sendMessage(SmpPlugin.mm("<blue>[Radio] " + TownManager.stars(d.stars) + " <white>" + w.getName() + " <gray>" + d.reason + ", " + where
                    + " <gold>" + Money.fmt(t.bounties().of(d))));
        }
    }

    static String direction(Location from, Location to) {
        Vector v = to.toVector().subtract(from.toVector());
        double a = Math.toDegrees(Math.atan2(v.getX(), -v.getZ()));
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return dirs[(int) Math.floorMod(Math.round(a / 45), 8)];
    }

    // ------------------------------------------------------------------ on the job

    /** Sneak + right-click (or /police cuff) on a wanted player. */
    public boolean cuff(Player cop, Player target) {
        if (!onDuty(cop)) return false;
        TownData d = t.data(target);
        if (d.stars <= 0 || d.jailed()) {
            cop.sendMessage(SmpPlugin.mm("<gray>" + target.getName() + " isn't wanted."));
            return true;
        }
        if (cop.getWorld() != target.getWorld() || cop.getLocation().distance(target.getLocation()) > 3.5) {
            cop.sendMessage(SmpPlugin.mm("<gray>Get closer to cuff them."));
            return true;
        }
        var max = target.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        boolean worn = target.getHealth() <= 0.4 * (max == null ? 20 : max.getValue());
        if (d.stars > 2 && !worn) {
            cop.sendMessage(SmpPlugin.mm("<red>" + target.getName() + " is too dangerous to cuff. Wear them down first (under 40% health)."));
            return true;
        }
        cop.sendMessage(SmpPlugin.mm("<blue>[Watch] <green>You cuffed " + target.getName() + "."));
        t.arrest(target, null, false, cop);
        return true;
    }

    /** Radio call to everyone on duty. */
    void alert(Player wanted, TownData d, Crime c) {
        Location l = wanted.getLocation();
        for (Player cop : Bukkit.getOnlinePlayers()) {
            if (cop == wanted || !onDuty(cop)) continue;
            String dist = cop.getWorld() == l.getWorld() ? (int) cop.getLocation().distance(l) + "m " + direction(cop.getLocation(), l) : "far away";
            cop.sendMessage(SmpPlugin.mm("<blue>[Radio] <white>" + wanted.getName() + " <gray>is wanted " + TownManager.stars(d.stars) + " <gray>for "
                    + c.label + ", " + dist + ". <gold>" + Money.fmt(t.bounties().of(d))));
        }
    }

    /** Pay and promotion for officers who arrested or helped (hit them in the last 30s). */
    void credit(Player arrested, TownData d, int stars, Player arrester) {
        long now = System.currentTimeMillis();
        java.util.Set<Player> helped = new java.util.LinkedHashSet<>();
        if (arrester != null && t.data(arrester).cop) helped.add(arrester);
        for (var e : d.damagedBy.entrySet()) {
            Player cop = Bukkit.getPlayer(e.getKey());
            if (cop != null && cop != arrested && now - e.getValue() <= 30_000 && t.data(cop).cop) helped.add(cop);
        }
        for (Player cop : helped) {
            TownData cd = t.data(cop);
            int before = rankIndex(cd);
            double pay = Math.floor(stars * cfg().getDouble("town.police.pay-per-star", 75) * mult(cd));
            cd.copArrests++;
            t.money.give(cop, pay);
            t.addRep(cop, cfg().getInt("town.police.reputation-per-arrest", 10));
            cop.sendMessage(SmpPlugin.mm("<blue>[Watch] <green>Arrest of " + arrested.getName() + " logged. Pay: <yellow>+" + Money.fmt(pay)));
            if (rankIndex(cd) > before) {
                cop.sendMessage(SmpPlugin.mm("<blue><bold>Promoted to " + rank(cd) + "!</bold> <gray>Your pay goes up."));
                cop.playSound(cop.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
                t.news(cop.getName() + " was promoted to " + rank(cd) + " of the Town Watch.");
            }
            t.markDirty();
        }
    }

    void fire(Player p, TownData d) {
        if (!d.cop) return;
        d.cop = false;
        d.onDuty = false;
        d.copArrests = d.copArrests / 2;
        t.markDirty();
        p.sendMessage(SmpPlugin.mm("<red>You've been dismissed from the Town Watch for breaking the law."));
        t.news(p.getName() + " was thrown out of the Town Watch!");
    }

    /** Every second: salary for time on duty inside the police zone. */
    void second(Player p, TownData d) {
        if (!d.cop || !d.onDuty) return;
        long now = System.currentTimeMillis();
        long every = cfg().getLong("town.police.salary-minutes", 10) * 60_000L;
        if (d.dutySince == 0) d.dutySince = now;
        if (!t.inPoliceZone(p.getLocation())) {
            d.dutySince += 1000; // the clock only runs inside the town
            return;
        }
        if (now - d.dutySince >= every) {
            d.dutySince = now;
            double pay = Math.floor(cfg().getDouble("town.police.salary", 50) * mult(d));
            t.money.give(p, pay);
            p.sendMessage(SmpPlugin.mm("<blue>[Watch] <gray>Patrol pay: <yellow>+" + Money.fmt(pay)));
        }
    }
}
