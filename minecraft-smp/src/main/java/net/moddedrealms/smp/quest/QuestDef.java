package net.moddedrealms.smp.quest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;

/** A quest from quests.yml. */
public final class QuestDef {
    public enum Kind { DAILY, WEEKLY, STORY }

    public enum Type { BREAK, PLACE, HARVEST, KILL, KILL_BOSS, FISH, BREED, TAME, ENCHANT, EAT, CRAFT, SMELT, TRADE, COLONY_MEET, WALK, PLAYTIME, COMMAND, TALK }

    public String id;
    public Kind kind;
    public String name;
    public Material icon;
    public String description;
    public Type type;
    public final List<String> targets = new ArrayList<>();
    private final List<Pattern> wild = new ArrayList<>();
    public int amount;
    public List<String> rewards = List.of();
    public String giver;
    public List<String> talk = List.of();

    static QuestDef load(String id, Kind kind, ConfigurationSection s) {
        QuestDef q = new QuestDef();
        q.id = id;
        q.kind = kind;
        q.name = s.getString("name", id);
        Material m = Material.matchMaterial(s.getString("icon", "PAPER"));
        q.icon = m == null ? Material.PAPER : m;
        q.description = s.getString("description", "");
        ConfigurationSection o = s.getConfigurationSection("objective");
        if (o == null) return null;
        try {
            q.type = Type.valueOf(o.getString("type", "BREAK").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (String t : o.getString("target", "ANY").split(",")) {
            String u = t.trim().toUpperCase(Locale.ROOT);
            if (u.isEmpty()) continue;
            if (u.contains("*")) q.wild.add(Pattern.compile(u.replace("*", ".*")));
            else q.targets.add(u);
        }
        q.amount = Math.max(1, o.getInt("amount", 1));
        q.rewards = s.getStringList("rewards");
        q.giver = s.getString("giver");
        q.talk = s.getStringList("talk");
        return q;
    }

    /** Does this event value (material / entity type / command / npc id) count? */
    public boolean matches(String value, Entity entity) {
        if (targets.contains("ANY")) return true;
        String v = value == null ? "" : value.toUpperCase(Locale.ROOT);
        if (targets.contains(v)) return true;
        if (entity != null) {
            if (targets.contains("HOSTILE") && entity instanceof Enemy) return true;
            if (targets.contains("ANIMAL") && entity instanceof Animals) return true;
        }
        for (Pattern p : wild) if (p.matcher(v).matches()) return true;
        return false;
    }
}
