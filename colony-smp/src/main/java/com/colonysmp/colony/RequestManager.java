package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.core.Good;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Wishes;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Request;
import com.colonysmp.npc.Npc;
import com.colonysmp.util.Fx;
import com.colonysmp.util.ItemGoods;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Requests: now and then a citizen asks the colony's players for something the stores can't give them (a pie,
 * a few torches, feed for the animals). Whoever brings it is paid from the citizen's own purse, and the citizen
 * is happier and works with a will for a couple of days. Requests nobody answers lapse after a few days.
 */
public final class RequestManager implements Listener {

    private final ColonySMP plugin;

    public RequestManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    public Request of(Colony c, Citizen ct) {
        for (Request r : c.requests) if (r.citizen().equals(ct.id)) return r;
        return null;
    }

    // ───────────── the day ─────────────

    public void daily(Colony c, long day) {
        Settings s = plugin.settings();
        // the ones who gave up waiting, and the ones who are gone
        for (Request r : new ArrayList<>(c.requests)) {
            Citizen ct = c.citizens.get(r.citizen());
            if (ct == null || !ct.status.free()) {
                c.requests.remove(r);
            } else if (r.untilDay() < day) {
                c.requests.remove(r);
                ct.needs.mood = Math.max(0, ct.needs.mood - 3);
                ct.remember(day, "Asked for " + Goods.get(r.good()).name().toLowerCase() + " and nobody brought any");
            }
        }
        if (!s.requestsEnabled || c.core == null || !c.anyMemberOnline()) return;
        if (c.requests.size() >= s.requestsMax) return;
        if (ThreadLocalRandom.current().nextDouble() >= s.requestChance) return;
        Request r = create(c, day);
        if (r != null) announce(c, r);
    }

    /** A new request from someone who has none, or null if nobody wants anything the stores lack. */
    public Request create(Colony c, long day) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        List<Citizen> pool = new ArrayList<>();
        for (Citizen ct : c.citizens.values()) if (ct.status.free() && !ct.downed() && of(c, ct) == null) pool.add(ct);
        // a few tries: the first person asked may want for nothing
        for (int i = 0; i < 6 && !pool.isEmpty(); i++) {
            Citizen ct = pool.remove(rnd.nextInt(pool.size()));
            LifeStage stage = plugin.sim().citizens().stage(ct);
            List<Wishes.Wish> wishes = new ArrayList<>(Wishes.pool(ct.job.name(), stage == LifeStage.CHILD, stage == LifeStage.ELDER));
            // at Christmas, half of what people want is for the season
            if (plugin.christmas().season() && rnd.nextBoolean()) wishes = Wishes.festive(stage == LifeStage.CHILD);
            while (!wishes.isEmpty()) {
                Wishes.Wish w = wishes.remove(rnd.nextInt(wishes.size()));
                if (!Goods.known(w.good())) continue;
                int amount = Math.min(rnd.nextInt(w.min(), w.max() + 1), Goods.stack(w.good()) * 4);
                // what the stores hold, they can fetch for themselves
                if (c.storage.available(w.good()) >= amount) continue;
                Good g = Goods.get(w.good());
                long reward = Wishes.reward(g.value(), amount, ct.wallet);
                Request r = new Request(c.nextRequestId++, ct.id, w.good(), amount, payable(reward), w.reason(), day + plugin.settings().requestDays);
                c.requests.add(r);
                plugin.requestSave();
                return r;
            }
        }
        return null;
    }

    /** Rewards are paid in valuables (a nugget is the smallest), so any whole worth can be paid. */
    private long payable(long reward) {
        return reward;
    }

    private String line(Colony c, Request r) {
        Citizen ct = c.citizens.get(r.citizen());
        String who = ct == null ? "Someone" : ct.name + " (" + ct.title() + ")";
        return "<white>" + Text.esc(who) + "</white> <gray>asks for <yellow>" + r.amount() + " " + Goods.get(r.good()).name() + "</yellow>: <italic>\"" + Text.esc(r.reason()) + "\"</italic>";
    }

    private String rewardText(Request r) {
        return r.reward() > 0 ? "<gold>" + Text.value(r.reward()) + "</gold> and their thanks" : "their thanks (they've nothing to spare)";
    }

    private void announce(Colony c, Request r) {
        for (Player p : c.onlineMembers()) {
            Text.send(p, "<yellow>✉</yellow> " + line(c, r) + " <gray>For " + rewardText(r) + ". <click:run_command:'/colony requests'><yellow>[Requests]</yellow></click>");
            Fx.sound(p, "minecraft:entity.villager.ambient", 0.8f, 1.1f);
        }
    }

    // ───────────── answering ─────────────

    /** What's open, for the command. */
    public List<String> list(Colony c) {
        List<String> out = new ArrayList<>();
        long day = plugin.sim().day(c);
        if (c.requests.isEmpty()) {
            out.add("<gray>Nobody is asking for anything just now. Citizens ask the players for things from time to time;");
            out.add("<gray>bring what they ask and they pay you, cheer up, and work harder for a while.");
        }
        for (Request r : c.requests) {
            long left = Math.max(0, r.untilDay() - day);
            out.add(" <dark_gray>#" + r.id() + "</dark_gray> " + line(c, r));
            out.add("    <gray>For " + rewardText(r) + " · waits " + (left == 0 ? "until tonight" : left + " more day" + (left == 1 ? "" : "s"))
                    + " <click:run_command:'/colony requests deliver " + r.id() + "'><green><bold>[Hand it over]</bold></green></click>");
        }
        out.add("<dark_gray>Requests answered so far: " + c.requestsFilled + ". Right-click the citizen holding the goods, or use the link.");
        return out;
    }

    private static int count(Player p, String good) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (it != null && good.equals(ItemGoods.goodOf(it))) n += it.getAmount();
        return n;
    }

    private static void take(Player p, String good, int amount) {
        int left = amount;
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length && left > 0; i++) {
            ItemStack it = inv[i];
            if (it == null || !good.equals(ItemGoods.goodOf(it))) continue;
            int n = Math.min(left, it.getAmount());
            left -= n;
            if (n >= it.getAmount()) inv[i] = null;
            else it.setAmount(it.getAmount() - n);
        }
        p.getInventory().setStorageContents(inv);
    }

    /** A player hands over what was asked for, from their own inventory. Returns an error, or null. */
    public String deliver(Player p, Colony c, Request r) {
        if (r == null || !c.requests.contains(r)) return "That request is no longer open.";
        Citizen ct = c.citizens.get(r.citizen());
        if (ct == null || !ct.status.free()) {
            c.requests.remove(r);
            return "They're no longer here to take it.";
        }
        Good g = Goods.get(r.good());
        int have = count(p, r.good());
        if (have < r.amount()) return Text.esc(ct.name) + " asked for " + r.amount() + " " + g.name() + " (you're carrying " + have + ").";
        take(p, r.good(), r.amount());
        c.requests.remove(r);
        c.requestsFilled++;
        long day = plugin.sim().day(c);
        // food is eaten on the spot; everything else goes to the colony's stores for them to use
        if (g.edible() && plugin.sim().citizens().edible(g.key())) {
            int eat = (int) Math.min(r.amount(), Math.ceil(ct.needs.appetite(plugin.settings().needs) / g.nutrition()));
            for (int i = 0; i < eat; i++) ct.needs.eat(g.nutrition(), g.quality(), plugin.settings().needs);
            if (r.amount() > eat) c.storage.force(g.key(), r.amount() - eat);
        } else {
            c.storage.force(g.key(), r.amount());
        }
        long paid = 0;
        if (r.reward() > 0 && ct.wallet > 0) {
            paid = plugin.economy().pay(p, Math.min(ct.wallet, r.reward()));
            ct.wallet -= Math.min(ct.wallet, paid);
        }
        ct.needs.mood = Math.min(100, ct.needs.mood + 12);
        ct.motivatedUntil = day + plugin.settings().requestMotivationDays;
        ct.remember(day, p.getName() + " brought the " + g.name().toLowerCase() + " they asked for");
        c.addStability(0.5);
        c.reputation = Math.min(100, c.reputation + 1);
        c.log(ColonyEvent.Type.POPULATION, day, p.getName() + " brought " + ct.name + " " + r.amount() + " " + g.name() + ".");
        Text.send(p, "<green>" + Text.esc(ct.name) + " is delighted.</green> <gray>" + (paid > 0 ? "They pressed <gold>" + Text.value(paid) + "</gold> into your hand. " : "")
                + "They'll work with a will for the next " + plugin.settings().requestMotivationDays + " days.");
        Fx.sound(p, "minecraft:entity.villager.celebrate", 1f, 1f);
        Npc n = plugin.npcs().npc(ct);
        if (n != null) n.body.getWorld().spawnParticle(Particle.HEART, n.loc().add(0, 2, 0), 6, 0.3, 0.3, 0.3, 0);
        plugin.milestones().check(c);
        plugin.requestSave();
        return null;
    }

    /**
     * A member right-clicked a citizen. If that citizen is waiting on a request and the player holds what they
     * asked for, it's handed over. Returns true if the click was used up.
     */
    public boolean interact(Player p, Citizen ct, Colony c, ItemStack hand) {
        if (!c.isMember(p.getUniqueId())) return false;
        Request r = of(c, ct);
        if (r == null) return false;
        if (hand == null || !r.good().equals(ItemGoods.goodOf(hand))) {
            // a reminder as the menu opens
            Text.bar(p, "<yellow>" + Text.esc(ct.name) + " asks for " + r.amount() + " " + Goods.get(r.good()).name() + ". <gray>Click them holding it.");
            return false;
        }
        String err = deliver(p, c, r);
        if (err != null) Text.send(p, "<red>" + err);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Colony c = plugin.colonies().of(p);
            if (c == null || c.requests.isEmpty()) return;
            int n = c.requests.size();
            Text.send(p, "<yellow>✉</yellow> <gray>" + n + " citizen" + (n == 1 ? " is" : "s are") + " waiting on something they asked for. <click:run_command:'/colony requests'><yellow>[Requests]</yellow></click>");
        }, 100);
    }
}
