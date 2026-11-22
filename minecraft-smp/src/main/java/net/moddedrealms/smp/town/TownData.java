package net.moddedrealms.smp.town;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;

/** One player's standing with the town: reputation, wanted level and jail time. */
public final class TownData {
    public final UUID id;
    public String name = "";
    public int rep;
    public int stars;
    public String reason = "";
    /** Seconds still to serve (only counts down while online). */
    public int jailLeft;
    public int cell = -1;
    public int offences;
    public int arrests;
    public boolean deputy;
    public boolean warnedContraband;
    // not saved
    public long lastSeenByPolice;
    public long lastDecay;
    public long orderedAt;
    public boolean hostile;
    public long lastSearch;
    public long lastRobbed;
    public long lastPickpocketTry;
    public long lastRobTry;
    /** Who this player hit, and when (so the other side may hit back in self-defence). */
    public final Map<UUID, Long> attacked = new HashMap<>();
    /** Who hit this player recently (deputies share the reward when a wanted player is caught). */
    public final Map<UUID, Long> damagedBy = new HashMap<>();
    public final Map<String, Long> recentCrimes = new HashMap<>();
    public final Map<UUID, Long> pickedNpcs = new HashMap<>();
    public int pickpocketsThisHour;
    public long pickpocketHour;

    TownData(UUID id) {
        this.id = id;
    }

    static TownData load(UUID id, ConfigurationSection s) {
        TownData d = new TownData(id);
        if (s == null) return d;
        d.name = s.getString("name", "");
        d.rep = s.getInt("rep");
        d.stars = s.getInt("stars");
        d.reason = s.getString("reason", "");
        d.jailLeft = s.getInt("jail-left");
        d.cell = s.getInt("cell", -1);
        d.offences = s.getInt("offences");
        d.arrests = s.getInt("arrests");
        d.deputy = s.getBoolean("deputy");
        d.warnedContraband = s.getBoolean("warned-contraband");
        return d;
    }

    boolean worthSaving() {
        return rep != 0 || stars != 0 || jailLeft > 0 || offences > 0 || deputy || warnedContraband;
    }

    void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("rep", rep);
        s.set("stars", stars);
        s.set("reason", reason.isEmpty() ? null : reason);
        s.set("jail-left", jailLeft > 0 ? jailLeft : null);
        s.set("cell", jailLeft > 0 ? cell : null);
        s.set("offences", offences);
        s.set("arrests", arrests);
        s.set("deputy", deputy ? true : null);
        s.set("warned-contraband", warnedContraband ? true : null);
    }

    public boolean jailed() {
        return jailLeft > 0;
    }
}
