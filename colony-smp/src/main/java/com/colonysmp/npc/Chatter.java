package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Goods;
import com.colonysmp.core.Mood;
import com.colonysmp.core.Remarks;
import com.colonysmp.model.Building;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Request;
import com.colonysmp.sim.CitizenSim;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Citizens speak to the members of their colony who walk past: about what's stopping their work, what they
 * need, the day's news, or just the time of day. One remark every so often per player, never a flood; a member
 * can turn it off for themselves with /colony chatter.
 */
public final class Chatter {

    private final ColonySMP plugin;
    private final NpcManager m;
    private final Map<UUID, Long> nextAt = new HashMap<>();
    private final Map<UUID, UUID> lastSpeaker = new HashMap<>();

    Chatter(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    /** Every second. */
    public void tick() {
        int every = plugin.settings().chatterSeconds;
        if (every <= 0) return;
        long now = System.currentTimeMillis();
        nextAt.keySet().removeIf(u -> Bukkit.getPlayer(u) == null);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long at = nextAt.get(p.getUniqueId());
            if (at == null) {
                // nobody greets you the instant you log in
                nextAt.put(p.getUniqueId(), now + 15_000);
                continue;
            }
            if (now < at) continue;
            Colony col = plugin.colonies().of(p);
            if (col == null || col.chatterMuted.contains(p.getUniqueId()) || col.world() != p.getWorld()) {
                nextAt.put(p.getUniqueId(), now + 10_000);
                continue;
            }
            List<Npc> near = new ArrayList<>();
            UUID last = lastSpeaker.get(p.getUniqueId());
            for (Entity e : p.getNearbyEntities(6, 3, 6)) {
                Npc n = m.npc(e);
                if (n == null || !col.id.equals(n.c.colonyId) || !n.c.status.free() || n.sleeping || n.c.downed() || n.enemy != null) continue;
                if (n.c.id.equals(last)) continue;
                near.add(n);
            }
            if (near.isEmpty()) {
                // look again soon: they may be walking up to someone
                nextAt.put(p.getUniqueId(), now + 3_000);
                lastSpeaker.remove(p.getUniqueId());
                continue;
            }
            ThreadLocalRandom r = ThreadLocalRandom.current();
            Npc n = near.get(r.nextInt(near.size()));
            String line = Remarks.say(mind(col, n, p), r);
            Text.raw(p, "<dark_aqua>" + Text.esc(n.c.name) + "<dark_gray>:</dark_gray> <gray>" + Text.esc(line));
            if (!n.mover.moving()) CitizenBrain.face(n, p.getLocation());
            lastSpeaker.put(p.getUniqueId(), n.c.id);
            nextAt.put(p.getUniqueId(), now + every * 1000L + r.nextInt(every * 500 + 1));
        }
    }

    private Remarks.Mind mind(Colony col, Npc n, Player listener) {
        Citizen c = n.c;
        var needs = plugin.settings().needs;
        CitizenSim.Context ctx = m.context(col);
        Remarks.Mind x = new Remarks.Mind();
        LifeStage stage = plugin.sim().citizens().stage(c);
        x.child = stage == LifeStage.CHILD;
        // what a body's own activity says about being stuck ("Waiting for...", "No...", "Needs...")
        String act = c.waiting != null ? c.waiting : c.activity;
        if (act != null && c.job.employed() && (c.waiting != null || act.startsWith("Waiting for") || act.startsWith("No ") || act.startsWith("Needs ") || act.contains("failed its inspection"))) {
            x.blocked = act;
        }
        x.starving = c.needs.satiety < 20;
        x.hungry = c.needs.hungry(needs);
        x.exhausted = c.needs.exhausted(needs);
        x.homeless = c.home == 0;
        x.ill = c.ill();
        x.injured = c.injured;
        x.unemployed = c.job == Job.UNEMPLOYED;
        x.unpaidDays = c.unpaidDays;
        Request rq = plugin.requests().of(col, c);
        if (rq != null) x.request = rq.amount() + " " + Goods.get(rq.good()).name().toLowerCase();
        x.underAttack = plugin.raids().raid(col) != null || plugin.wars().fighting(col);
        x.raidTonight = col.raidDay >= 0 && col.raidDay == ctx.day;
        x.festival = col.boon("festival", ctx.day);
        x.christmas = plugin.christmas().season();
        x.christmasTree = plugin.christmas().decorated(col);
        x.motivated = c.motivatedUntil >= ctx.day;
        x.entertained = ctx.entertained;
        x.mood = c.needs.mood;
        if (c.needs.mood < 35) {
            Mood.Result res = plugin.sim().citizens().mood(col, c, ctx);
            x.worst = res.worst();
        }
        x.job = c.job.employed() ? c.title() : null;
        Building b = col.building(c.workplace);
        x.workplace = b == null ? null : b.label();
        x.listener = listener.getName();
        long t = listener.getWorld().getTime();
        x.timeOfDay = t < 4000 ? 0 : t < 11000 ? 1 : t < 13500 ? 2 : 3;
        return x;
    }
}
