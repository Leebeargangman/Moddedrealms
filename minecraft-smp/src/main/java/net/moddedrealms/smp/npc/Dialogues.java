package net.moddedrealms.smp.npc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** NPC conversations: lines appear one by one with a "voice" blip, then clickable choices. */
public final class Dialogues {
    public record Choice(String text, String action) {}

    private final SmpPlugin plugin;
    private final YamlConfiguration data;
    /** The jar's dialogues.yml, so NPCs added in an update can talk before the server's copy has their lines. */
    private final YamlConfiguration defaults;
    private final Map<UUID, BukkitTask> running = new HashMap<>();
    private final Map<UUID, List<Choice>> offered = new HashMap<>();
    private final Map<UUID, Long> offeredAt = new HashMap<>();

    public Dialogues(SmpPlugin plugin) {
        this.plugin = plugin;
        this.data = plugin.yaml("dialogues.yml");
        var in = plugin.getResource("dialogues.yml");
        this.defaults = in == null ? new YamlConfiguration()
                : YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
    }

    public void interact(Player p, NpcDef npc) {
        if (plugin.onboarding() != null && plugin.onboarding().intercept(p, npc)) return;
        converse(p, npc);
    }

    /** The NPC's own conversation (story, quests, dialogue lines), without the new-player detour. */
    public void converse(Player p, NpcDef npc) {
        String action = npc.action;
        if (action != null && !action.startsWith("dialogue")) {
            plugin.quests().talked(p, npc.id);
            run(p, action);
            return;
        }
        String dlg = action != null && action.startsWith("dialogue:") ? action.substring(9) : npc.id;
        List<String> story = plugin.quests().talk(p, npc.id);
        if (story != null) {
            play(p, npc, story, List.of(new Choice("<green>[My story]", "story"), new Choice("<yellow>[Daily quests]", "quests")));
            return;
        }
        ConfigurationSection s = data.getConfigurationSection(dlg);
        if (s == null) s = defaults.getConfigurationSection(dlg);
        List<String> lines = s == null ? List.of("...") : s.getStringList("lines");
        List<Choice> choices = new ArrayList<>();
        if (s != null) {
            for (Map<?, ?> m : s.getMapList("choices")) {
                choices.add(new Choice(String.valueOf(m.get("text")), String.valueOf(m.get("action"))));
            }
        }
        play(p, npc, lines, choices);
    }

    public void play(Player p, NpcDef npc, List<String> lines, List<Choice> choices) {
        BukkitTask old = running.remove(p.getUniqueId());
        if (old != null) old.cancel();
        Component name = SmpPlugin.mm(npc.name);
        int[] i = {0};
        running.put(p.getUniqueId(), Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline()) {
                stop(p);
                return;
            }
            if (i[0] < lines.size()) {
                p.sendMessage(Component.text().append(name).append(Component.text(" » ", NamedTextColor.DARK_GRAY))
                        .append(SmpPlugin.mm("<white>" + lines.get(i[0]))).build());
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BANJO, 0.5f, 1.4f + (i[0] % 3) * 0.1f);
                i[0]++;
                return;
            }
            if (!choices.isEmpty()) {
                offered.put(p.getUniqueId(), choices);
                offeredAt.put(p.getUniqueId(), System.currentTimeMillis());
                var b = Component.text().append(Component.text("   ", NamedTextColor.GRAY));
                for (int c = 0; c < choices.size(); c++) {
                    b.append(SmpPlugin.mm(choices.get(c).text()).clickEvent(ClickEvent.runCommand("/mrsmp choose " + c))).append(Component.text("  "));
                }
                p.sendMessage(b.build());
            }
            stop(p);
        }, 0L, 28L));
    }

    private void stop(Player p) {
        BukkitTask t = running.remove(p.getUniqueId());
        if (t != null) t.cancel();
    }

    /** /mrsmp choose <n>: only choices this player was just offered. */
    public void choose(Player p, int n) {
        List<Choice> c = offered.get(p.getUniqueId());
        if (c == null || n < 0 || n >= c.size() || System.currentTimeMillis() - offeredAt.getOrDefault(p.getUniqueId(), 0L) > 120_000) return;
        offered.remove(p.getUniqueId());
        run(p, c.get(n).action());
    }

    public void run(Player p, String action) {
        if (action == null || action.equals("close")) return;
        if (action.equals("quests")) plugin.quests().openGui(p);
        else if (action.equals("story")) plugin.quests().showStory(p);
        else if (action.equals("crates")) plugin.crates().openMain(p);
        else if (action.startsWith("command:")) p.performCommand(action.substring(8));
        else if (action.startsWith("console:")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), action.substring(8).replace("{player}", p.getName()));
    }
}
