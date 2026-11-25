package net.moddedrealms.smp.season;

import com.colonysmp.api.ColonyApi;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;

/**
 * What ColonySMP's /colonyadmin asks of this plugin: the season's events, the season reset and this plugin's NPCs
 * for cleanup. Only loaded when ColonySMP (2.6 or newer) is running.
 */
final class ColonyHook implements ColonyApi.ServerHook {
    private final SeasonManager s;

    private ColonyHook(SeasonManager s) {
        this.s = s;
    }

    static void register(SeasonManager s) {
        ColonyApi.hook(new ColonyHook(s));
    }

    static void unregister() {
        ColonyApi.hook(null);
    }

    @Override
    public List<String> seasonEvents() {
        return List.of("season", "advent", "countdown");
    }

    @Override
    public String seasonEvent(String name, boolean start) {
        switch (name) {
            case "season" -> s.cfg().set("season.enabled", start);
            case "advent" -> s.cfg().set("advent.enabled", start);
            case "countdown" -> s.cfg().set("countdown.enabled", start);
            default -> {
                return "No season event called " + name + ". Events: " + String.join(", ", seasonEvents());
            }
        }
        s.plugin().getLogger().info("Season event " + name + (start ? " started" : " stopped") + " (until the next reload or restart; season.yml has the lasting setting).");
        return null;
    }

    @Override
    public List<String> seasonEnd(boolean confirm) {
        return List.of("<yellow>The season reset isn't built yet: it comes with The Crowning in January.", "<gray>Nothing was changed.");
    }

    @Override
    public List<String> strayNpcs(boolean remove) {
        SmpPlugin p = s.plugin();
        List<String> out = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                boolean stray = p.npcs().isStray(e) || p.town() != null && p.town().isStray(e);
                if (!stray) continue;
                String name = e.customName() == null ? e.getType().name().toLowerCase(java.util.Locale.ROOT) : PlainTextComponentSerializer.plainText().serialize(e.customName());
                out.add("SMP " + name + "|" + w.getName() + " " + e.getLocation().getBlockX() + " " + e.getLocation().getBlockY() + " " + e.getLocation().getBlockZ());
                if (remove) e.remove();
            }
        }
        return out;
    }
}
