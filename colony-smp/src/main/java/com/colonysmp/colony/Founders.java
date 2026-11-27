package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.util.SafeFiles;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Who has had a founding grant this season (founders.yml). The grant is real metal in the new colony's treasury,
 * so without this a player could found, withdraw it, disband and found again, over and over. Each player gets it
 * once; a season reset clears the list.
 */
public final class Founders {

    private final ColonySMP plugin;
    private final File file;
    private final Set<UUID> granted = new LinkedHashSet<>();

    public Founders(ColonySMP plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "founders.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String s : y.getStringList("granted")) {
            try {
                granted.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // a hand-edited line that isn't a UUID
            }
        }
    }

    /** True if this player hasn't had a founding grant yet this season. */
    public boolean mayGrant(UUID player) {
        return player != null && !granted.contains(player);
    }

    /** Notes that the player has had their grant (written at once: it guards real items). */
    public void granted(UUID player) {
        if (player == null || !granted.add(player)) return;
        save();
    }

    /** A new season: everyone may have a grant again. */
    public void clear() {
        granted.clear();
        save();
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(java.util.List.of("Players who have had a colony founding grant this season. Cleared by /colonyadmin season end."));
        y.set("granted", granted.stream().map(UUID::toString).toList());
        try {
            SafeFiles.save(y, file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Couldn't save founders.yml", e);
        }
    }
}
