package com.colonysmp.sim;

import com.colonysmp.ColonySMP;
import com.colonysmp.model.Bond;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.util.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who sleeps where. Everyone keeps their bed while it is still valid; families are kept under one roof when
 * there's a house with room for them; soldiers prefer the barracks; the rest fill the free beds of the best
 * houses first.
 */
public final class Housing {

    private final ColonySMP plugin;

    Housing(ColonySMP plugin) {
        this.plugin = plugin;
    }

    /** Re-checks every bed in the colony. Cheap enough to run whenever buildings or people change. */
    public void assign(Colony c) {
        Map<Integer, Building> homes = new LinkedHashMap<>();
        for (Building b : c.buildings.values()) {
            if (b.valid && (b.type == BuildingType.HOUSE || b.type == BuildingType.BARRACKS) && !b.beds.isEmpty()) homes.put(b.id, b);
        }
        // beds in use (one person per bed)
        Map<BlockPos, UUID> used = new HashMap<>();
        List<Citizen> homeless = new ArrayList<>();
        for (Citizen ct : c.citizens.values()) {
            if (!ct.status.free()) {
                // prisoners sleep in their cell; captives wherever they're held
                ct.home = 0;
                ct.bed = null;
                Building cell = ct.status == com.colonysmp.model.Status.PRISONER ? c.building(ct.cell) : null;
                if (cell != null) {
                    for (BlockPos p : cell.beds) {
                        if (used.containsKey(p)) continue;
                        ct.bed = p;
                        used.put(p, ct.id);
                        break;
                    }
                }
                continue;
            }
            Building h = homes.get(ct.home);
            boolean keep = h != null && ct.bed != null && h.beds.contains(ct.bed) && !used.containsKey(ct.bed)
                    && (h.type != BuildingType.BARRACKS || ct.job.military());
            if (keep) used.put(ct.bed, ct.id);
            else {
                ct.home = 0;
                ct.bed = null;
                homeless.add(ct);
            }
        }
        if (homeless.isEmpty()) return;
        // soldiers and guards first take barracks beds
        for (Citizen ct : new ArrayList<>(homeless)) {
            if (!ct.job.military()) continue;
            for (Building b : homes.values()) {
                if (b.type != BuildingType.BARRACKS) continue;
                BlockPos bed = freeBed(b, used);
                if (bed == null) continue;
                give(ct, b, bed, used);
                homeless.remove(ct);
                break;
            }
        }
        // families: join a partner or parent who already has a house with a free bed
        boolean moved = true;
        while (moved) {
            moved = false;
            for (Citizen ct : new ArrayList<>(homeless)) {
                Building family = familyHome(c, ct, homes);
                if (family == null) continue;
                BlockPos bed = freeBed(family, used);
                if (bed == null) continue;
                give(ct, family, bed, used);
                homeless.remove(ct);
                moved = true;
            }
        }
        // a family with nobody housed yet moves into the house with most free beds
        List<Building> houses = new ArrayList<>();
        for (Building b : homes.values()) if (b.type == BuildingType.HOUSE) houses.add(b);
        houses.sort((a, b) -> b.level != a.level ? b.level - a.level : free(b, used) - free(a, used));
        Set<UUID> placed = new HashSet<>();
        for (Citizen ct : new ArrayList<>(homeless)) {
            if (placed.contains(ct.id)) continue;
            List<Citizen> family = household(c, ct, homeless);
            Building best = null;
            for (Building h : houses) {
                int f = free(h, used);
                if (f >= family.size() && (best == null || f < free(best, used))) best = h;
            }
            if (best == null) {
                // nobody has room for them all: just the one person, anywhere
                for (Building h : houses) {
                    if (free(h, used) > 0) {
                        best = h;
                        break;
                    }
                }
                if (best == null) continue;
                family = List.of(ct);
            }
            for (Citizen m : family) {
                BlockPos bed = freeBed(best, used);
                if (bed == null) break;
                give(m, best, bed, used);
                placed.add(m.id);
                homeless.remove(m);
            }
        }
        // soldiers with no barracks bed sleep in a house if there's one free
        for (Citizen ct : homeless) {
            if (placed.contains(ct.id) || !ct.job.military()) continue;
            for (Building h : houses) {
                BlockPos bed = freeBed(h, used);
                if (bed != null) {
                    give(ct, h, bed, used);
                    break;
                }
            }
        }
    }

    private static void give(Citizen ct, Building b, BlockPos bed, Map<BlockPos, UUID> used) {
        ct.home = b.id;
        ct.bed = bed;
        used.put(bed, ct.id);
    }

    private static BlockPos freeBed(Building b, Map<BlockPos, UUID> used) {
        for (BlockPos p : b.beds) if (!used.containsKey(p)) return p;
        return null;
    }

    private static int free(Building b, Map<BlockPos, UUID> used) {
        int n = 0;
        for (BlockPos p : b.beds) if (!used.containsKey(p)) n++;
        return n;
    }

    /** A house where their partner, a parent or a child already lives. */
    private static Building familyHome(Colony c, Citizen ct, Map<Integer, Building> homes) {
        Building best = null;
        for (Map.Entry<UUID, Bond> e : ct.bonds.entrySet()) {
            Bond.Kind k = e.getValue().kind;
            if (k != Bond.Kind.PARTNER && k != Bond.Kind.PARENT && k != Bond.Kind.CHILD) continue;
            Citizen o = c.citizens.get(e.getKey());
            if (o == null || o.home == 0) continue;
            Building h = homes.get(o.home);
            if (h == null || h.type != BuildingType.HOUSE) continue;
            if (k == Bond.Kind.PARTNER) return h;
            if (best == null) best = h;
        }
        return best;
    }

    /** The homeless person and their homeless partner and children. */
    private static List<Citizen> household(Colony c, Citizen ct, List<Citizen> homeless) {
        List<Citizen> out = new ArrayList<>();
        out.add(ct);
        for (Map.Entry<UUID, Bond> e : ct.bonds.entrySet()) {
            Bond.Kind k = e.getValue().kind;
            if (k != Bond.Kind.PARTNER && k != Bond.Kind.CHILD) continue;
            Citizen o = c.citizens.get(e.getKey());
            if (o != null && homeless.contains(o) && !o.job.military()) out.add(o);
        }
        return out;
    }

    /** People with no bed (for the Town Hall and warnings). */
    public int homeless(Colony c) {
        int n = 0;
        for (Citizen ct : c.citizens.values()) if (ct.status.free() && ct.home == 0) n++;
        return n;
    }
}
