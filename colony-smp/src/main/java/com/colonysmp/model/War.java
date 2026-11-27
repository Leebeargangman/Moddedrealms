package com.colonysmp.model;

import com.colonysmp.util.BlockPos;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A war between two sides (each a leading colony and any allies who joined). A raid is a short incursion to
 * loot and hurt; a siege aims to destroy the Town Hall or starve the defenders of supplies. Both have a
 * warm-up, explicit rules, a score, and end in victory, a negotiated peace or surrender.
 */
public final class War {

    public enum Goal {
        RAID("Raid"), SIEGE("Siege");

        public final String display;

        Goal(String display) {
            this.display = display;
        }
    }

    public enum Phase {WARMUP, ACTIVE, ENDED}

    public final String id;
    public final String attacker, defender;
    public final Set<String> attackers = new LinkedHashSet<>(), defenders = new LinkedHashSet<>();
    public Goal goal = Goal.SIEGE;
    public Phase phase = Phase.WARMUP;
    public long declaredAt, phaseEnds;
    public String campWorld;
    public BlockPos camp;
    public String world;
    public long lastSupply;
    public boolean supplyCut, coreDestroyed;
    public int attackerScore, defenderScore;
    public long looted;
    /** Original blocks changed during the fighting, put back afterwards. */
    public final Map<String, String> restore = new LinkedHashMap<>();
    public long restoreAt;
    public transient long warned;
    public transient long lastLoot;

    public War(String id, String attacker, String defender) {
        this.id = id;
        this.attacker = attacker;
        this.defender = defender;
        attackers.add(attacker);
        defenders.add(defender);
    }

    public boolean involves(String colony) {
        return attackers.contains(colony) || defenders.contains(colony);
    }

    public boolean onAttackingSide(String colony) {
        return attackers.contains(colony);
    }

    public boolean enemies(String x, String y) {
        return attackers.contains(x) && defenders.contains(y) || defenders.contains(x) && attackers.contains(y);
    }

    public void save(ConfigurationSection s) {
        s.set("attacker", attacker);
        s.set("defender", defender);
        s.set("attackers", new ArrayList<>(attackers));
        s.set("defenders", new ArrayList<>(defenders));
        s.set("goal", goal.name());
        s.set("phase", phase.name());
        s.set("declared", declaredAt);
        s.set("phase-ends", phaseEnds);
        s.set("camp-world", campWorld);
        s.set("camp", camp == null ? null : camp.toString());
        s.set("world", world);
        s.set("supply-cut", supplyCut);
        s.set("core-destroyed", coreDestroyed);
        s.set("attacker-score", attackerScore);
        s.set("defender-score", defenderScore);
        s.set("looted", looted);
        s.set("restore-at", restoreAt);
        s.set("saved-at", System.currentTimeMillis());
        List<String> r = new ArrayList<>();
        for (Map.Entry<String, String> e : restore.entrySet()) r.add(e.getKey() + "|" + e.getValue());
        s.set("restore", r);
    }

    public static War load(String id, ConfigurationSection s) {
        String atk = s.getString("attacker"), def = s.getString("defender");
        if (atk == null || def == null) return null;
        War w = new War(id, atk, def);
        w.attackers.addAll(s.getStringList("attackers"));
        w.defenders.addAll(s.getStringList("defenders"));
        w.goal = Policies.parse(Goal.class, s.getString("goal"), Goal.SIEGE);
        w.phase = Policies.parse(Phase.class, s.getString("phase"), Phase.ENDED);
        w.declaredAt = s.getLong("declared");
        w.phaseEnds = s.getLong("phase-ends");
        w.campWorld = s.getString("camp-world");
        w.camp = BlockPos.parse(s.getString("camp"));
        w.world = s.getString("world");
        w.supplyCut = s.getBoolean("supply-cut");
        w.coreDestroyed = s.getBoolean("core-destroyed");
        w.attackerScore = s.getInt("attacker-score");
        w.defenderScore = s.getInt("defender-score");
        w.looted = s.getLong("looted");
        w.restoreAt = s.getLong("restore-at");
        for (String r : s.getStringList("restore")) {
            int i = r.indexOf('|');
            if (i > 0) w.restore.put(r.substring(0, i), r.substring(i + 1));
        }
        // the clocks stop while the server is down, so nothing runs out unseen
        long down = Math.max(0, System.currentTimeMillis() - s.getLong("saved-at", System.currentTimeMillis()));
        if (w.phase != Phase.ENDED) w.phaseEnds += down;
        else w.restoreAt += down;
        return w;
    }
}
