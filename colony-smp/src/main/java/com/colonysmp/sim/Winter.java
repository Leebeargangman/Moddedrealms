package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.core.Recipes;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Feature;
import com.colonysmp.util.Messages;
import com.colonysmp.util.Text;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Winter needs (features.winter-needs): settlers need warmth as well as food. A house with a fire inside (a
 * campfire, furnace or smoker) keeps the people who live there warm, and the Town Hall's hearth warms a few more.
 * Every fire burns firewood from storage once a day. The cold makes settlers unhappy, then slower; misery can make
 * one leave (at most winter.max-leave-per-day a day). New colonies are spared while they settle in, and the cold
 * never destroys a colony.
 */
public final class Winter {

    /** How warm the colony was at its last day change, for the menu. */
    public record Summary(int warm, int cold, int freezing, int heatedHouses, int unheatedHouses, long firewood, double days, boolean hallLit) {}

    /** One kind of firewood: a good (or "#group") and how many of it make one firewood. */
    private record Fuel(String good, int per) {}

    private final ColonySMP plugin;
    private final Map<String, Summary> summaries = new HashMap<>();
    private final Map<String, Integer> leftToday = new HashMap<>();
    private Set<Integer> months = Set.of();
    private List<Fuel> fuel = List.of();
    private int houseFirewood, hallFirewood, hallWarms, freezingAfter, maxLeave, warnDays;
    private double moodCold, moodFreezing, moodWarm, workCold, workFreezing;
    private ZoneId zone = ZoneId.of("Europe/London");

    Winter(ColonySMP plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();
        Set<Integer> m = new HashSet<>();
        for (Integer i : c.getIntegerList("winter.months")) if (i >= 1 && i <= 12) m.add(i);
        months = m;
        List<Fuel> f = new ArrayList<>();
        for (String s : c.getStringList("winter.fuel")) {
            String[] parts = s.toLowerCase(Locale.ROOT).split("\\*");
            int per = 1;
            if (parts.length > 1) {
                try {
                    per = Math.max(1, Integer.parseInt(parts[1].trim()));
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("winter.fuel: \"" + s + "\" should look like \"#planks*4\"");
                }
            }
            f.add(new Fuel(parts[0].trim(), per));
        }
        fuel = f;
        houseFirewood = Math.max(0, c.getInt("winter.house-firewood"));
        hallFirewood = Math.max(0, c.getInt("winter.hall-firewood"));
        hallWarms = Math.max(0, c.getInt("winter.hall-warms"));
        freezingAfter = Math.max(1, c.getInt("winter.freezing-after-days"));
        maxLeave = Math.max(0, c.getInt("winter.max-leave-per-day"));
        warnDays = Math.max(0, c.getInt("winter.warn-days"));
        moodCold = c.getDouble("winter.mood-cold");
        moodFreezing = c.getDouble("winter.mood-freezing");
        moodWarm = c.getDouble("winter.mood-warm");
        workCold = Math.max(0.05, Math.min(1, c.getDouble("winter.work-cold")));
        workFreezing = Math.max(0.05, Math.min(1, c.getDouble("winter.work-freezing")));
        try {
            zone = ZoneId.of(c.getString("onboarding.timezone", "Europe/London"));
        } catch (Exception e) {
            zone = ZoneId.of("Europe/London");
        }
    }

    /** Winter needs are on and it's a winter month. */
    public boolean season() {
        if (!plugin.settings().feature("winter-needs")) return false;
        return months.isEmpty() || months.contains(ZonedDateTime.now(zone).getMonthValue());
    }

    /** This colony feels the cold today (not while it's new and settling in). */
    public boolean active(Colony c) {
        return season() && !spared(c);
    }

    private boolean spared(Colony c) {
        return c.inGrace() || plugin.quests().busy(c);
    }

    // ───────────── the day ─────────────

    /** Once a day: light the fires that have firewood, then see who's warm. */
    void daily(Colony col, long day) {
        leftToday.remove(col.id);
        if (!active(col)) {
            for (Citizen c : col.citizens.values()) c.coldDays = 0;
            summaries.remove(col.id);
            return;
        }
        Building hall = col.townHall();
        boolean hallLit = hall != null && burn(col, hallFirewood);
        // the fullest houses get their fire first
        List<Building> houses = new ArrayList<>();
        int unheated = 0;
        for (Building b : col.valid(BuildingType.HOUSE)) {
            if (heated(b)) houses.add(b);
            else unheated++;
        }
        Map<Integer, Integer> occupants = new HashMap<>();
        for (Citizen c : col.citizens.values()) if (c.home != 0) occupants.merge(c.home, 1, Integer::sum);
        houses.sort((a, b) -> Integer.compare(occupants.getOrDefault(b.id, 0), occupants.getOrDefault(a.id, 0)));
        Set<Integer> lit = new HashSet<>();
        for (Building b : houses) if (burn(col, houseFirewood)) lit.add(b.id);
        int room = hallLit ? hallWarms : 0;
        int warm = 0, cold = 0, freezing = 0;
        for (Citizen c : col.citizens.values()) {
            if (!c.status.free()) continue;
            if (c.home != 0 && lit.contains(c.home)) {
                c.coldDays = 0;
            } else if (room > 0) {
                room--;
                c.coldDays = 0;
            } else {
                c.coldDays++;
            }
            if (c.coldDays == 0) warm++;
            else if (c.coldDays >= freezingAfter) freezing++;
            else cold++;
        }
        long left = firewood(col);
        int perDay = (hall != null ? hallFirewood : 0) + houses.size() * houseFirewood;
        double days = perDay <= 0 ? 99 : (double) left / perDay;
        summaries.put(col.id, new Summary(warm, cold, freezing, lit.size(), unheated + houses.size() - lit.size(), left, days, hallLit));
        if (cold + freezing > 0) {
            col.log(ColonyEvent.Type.POPULATION, day, Text.plainOf(Messages.get("winter.log-cold", "n", cold + freezing)));
        }
        tell(col, cold + freezing, days, perDay);
    }

    private void tell(Colony col, int cold, double days, int perDay) {
        for (Player p : col.onlineMembers()) {
            if (cold > 0) Text.send(p, Messages.get("winter.cold", "n", cold, "colony", col.name));
            else if (perDay > 0 && days < warnDays) Text.send(p, Messages.get("winter.low", "days", Math.max(0, (int) Math.floor(days))));
        }
    }

    /** A house with a fire inside: a campfire, furnace or smoker. */
    public static boolean heated(Building b) {
        return b.features.getOrDefault(Feature.COOKER, 0) > 0 || b.features.getOrDefault(Feature.FURNACE, 0) > 0
                || b.features.getOrDefault(Feature.SMOKER, 0) > 0;
    }

    /** Takes this much firewood from storage, in the configured order. False (and nothing taken) if there isn't enough. */
    private boolean burn(Colony col, int amount) {
        if (amount <= 0) return true;
        if (firewood(col) < amount) return false;
        int need = amount;
        for (Fuel f : fuel) {
            for (String good : members(f.good())) {
                while (need > 0 && col.storage.available(good) >= f.per()) {
                    long got = col.storage.take(good, f.per());
                    if (got < f.per()) {
                        if (got > 0) col.storage.force(good, got);
                        break;
                    }
                    need--;
                }
                if (need <= 0) return true;
            }
        }
        return need <= 0;
    }

    /** Firewood in storage, counted in fires' worth. */
    public long firewood(Colony col) {
        long n = 0;
        for (Fuel f : fuel) for (String good : members(f.good())) n += col.storage.available(good) / f.per();
        return n;
    }

    private static List<String> members(String good) {
        return Recipes.isGroup(good) ? Recipes.members(good) : List.of(good);
    }

    // ───────────── effects ─────────────

    /** 0 warm, 1 cold, 2 freezing; -1 when winter doesn't apply to this colony now. */
    public int level(Colony col, Citizen c) {
        if (!active(col) || !c.status.free()) return -1;
        return c.coldDays <= 0 ? 0 : c.coldDays >= freezingAfter ? 2 : 1;
    }

    /** The mood line for the cold (label and value), or null. */
    public Map.Entry<String, Double> mood(Colony col, Citizen c) {
        return switch (level(col, c)) {
            case 0 -> Map.entry("Warm by the fire", moodWarm);
            case 1 -> Map.entry("Cold", moodCold);
            case 2 -> Map.entry("Freezing", moodFreezing);
            default -> null;
        };
    }

    /** Work speed in the cold. */
    public double work(Colony col, Citizen c) {
        return switch (level(col, c)) {
            case 1 -> workCold;
            case 2 -> workFreezing;
            default -> 1;
        };
    }

    /** Misery can make settlers leave, but only so many a day in winter. */
    public boolean mayLeave(Colony col) {
        if (!season()) return true;
        int n = leftToday.getOrDefault(col.id, 0);
        if (n >= maxLeave) return false;
        leftToday.put(col.id, n + 1);
        return true;
    }

    public Summary summary(Colony col) {
        return summaries.get(col.id);
    }
}
