package net.moddedrealms.minigames;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/** Each player's own sidebar (and the name-tag team Murder Mystery uses to hide names). */
final class Board {

    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    Scoreboard of(Player p) {
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null || p.getScoreboard() != sb) {
            sb = Bukkit.getScoreboardManager().getNewScoreboard();
            boards.put(p.getUniqueId(), sb);
            p.setScoreboard(sb);
        }
        return sb;
    }

    void show(Player p, Component title, List<Component> lines) {
        Scoreboard sb = of(p);
        Objective o = sb.getObjective("mg");
        if (o == null) {
            o = sb.registerNewObjective("mg", Criteria.DUMMY, title);
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.numberFormat(NumberFormat.blank());
        } else if (!title.equals(o.displayName())) {
            o.displayName(title);
        }
        int n = Math.min(15, lines.size());
        for (int i = 0; i < 15; i++) {
            String entry = "l" + i;
            if (i < n) {
                Score s = o.getScore(entry);
                if (s.getScore() != n - i || !s.isScoreSet()) s.setScore(n - i);
                Component c = lines.get(i);
                if (!c.equals(s.customName())) s.customName(c);
            } else if (o.getScore(entry).isScoreSet()) {
                sb.resetScores(entry);
            }
        }
    }

    /** Hide (or show again) every name tag this player sees (Murder Mystery hides them). */
    void hideNames(Player p, boolean hide, List<String> names) {
        Scoreboard sb = of(p);
        Team t = sb.getTeam("hide");
        if (!hide) {
            if (t != null) t.unregister();
            return;
        }
        if (t == null) {
            t = sb.registerNewTeam("hide");
            t.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            t.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        }
        for (String n : names) if (!t.hasEntry(n)) t.addEntry(n);
    }

    void forget(Player p) {
        boards.remove(p.getUniqueId());
    }
}
