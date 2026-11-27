package com.colonysmp.npc;

import com.colonysmp.ColonySMP;
import com.colonysmp.Settings;
import com.colonysmp.building.RoomScanner;
import com.colonysmp.core.Skill;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Feature;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Status;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Fx;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

import java.util.ArrayList;
import java.util.List;

/**
 * Schools: a teacher stands at the lectern and gives a lesson every 30 seconds to the children and students in
 * the room. A book (one per school per day, from the school's stock or storage) and bookshelves make lessons
 * better. Education speeds up all work, makes citizens happier and opens skilled jobs.
 */
public final class Schooling {

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    private final ColonySMP plugin;
    private final NpcManager m;

    Schooling(ColonySMP plugin, NpcManager m) {
        this.plugin = plugin;
        this.m = m;
    }

    private static int capacity(Building school) {
        return 4 + 4 * school.level;
    }

    // ───────────── teachers ─────────────

    void teach(Npc n, Colony col, Building school, long now) {
        World w = n.body.getWorld();
        long today = plugin.sim().day(col);
        if (school.bookDay != today && school.stock.available("book") == 0 && n.c.carried.get("book") == 0 && col.storage.available("book") > 0) {
            if (m.fetch(n, col, List.of("book"), 2, "a book for the lessons") == NpcManager.Fetch.FETCHING) return;
        }
        Location spot = lecternSpot(w, school);
        if (spot == null) spot = interiorSpot(w, school, n);
        if (spot == null) {
            m.brain().idle(n, col, now, "Can't get into " + school.label());
            return;
        }
        if (!n.mover.near(spot, 1.2)) {
            n.mover.moveTo(spot, plugin.settings().walkSpeed, 0.9);
            n.doing("Walking to " + school.label());
            return;
        }
        n.mover.stop();
        n.working = true;
        if (school.min != null && school.max != null) {
            CitizenBrain.face(n, new Location(w, (school.min.x() + school.max.x()) / 2.0 + 0.5, spot.getY(), (school.min.z() + school.max.z()) / 2.0 + 0.5));
        }
        int students = present(col, school).size();
        n.doing(students == 0 ? "Waiting for students at " + school.label() : "Teaching " + students + " student" + (students == 1 ? "" : "s") + " at " + school.label());
    }

    // ───────────── students ─────────────

    /** Goes to a school with a teacher and sits the lessons. False if there's no such school with room. */
    boolean study(Npc n, Colony col, long now) {
        Building school = pick(n, col);
        if (school == null) return false;
        World w = n.body.getWorld();
        Location spot = interiorSpot(w, school, n);
        if (spot == null) return false;
        if (!school.inside(n.loc().getX(), n.loc().getY(), n.loc().getZ()) || !n.mover.near(spot, 1.0)) {
            n.mover.moveTo(spot, plugin.settings().walkSpeed, 0.8);
            n.doing("Going to " + school.label());
            return true;
        }
        n.mover.stop();
        n.working = n.c.job == Job.STUDENT;
        Npc teacher = teacherIn(col, school);
        if (teacher != null) CitizenBrain.face(n, teacher.loc());
        n.doing(teacher != null ? "Studying at " + school.label() + " (education " + Math.round(n.c.education) + ")" : "Waiting for the teacher at " + school.label());
        return true;
    }

    /** The school this citizen goes to (sticky), with a teacher and room. */
    private Building pick(Npc n, Colony col) {
        List<Building> schools = new ArrayList<>();
        for (Building b : col.valid(BuildingType.SCHOOL)) if (!col.workers(b).isEmpty()) schools.add(b);
        if (schools.isEmpty()) return null;
        if (n.jobKey != null && n.jobKey.startsWith("study:")) {
            try {
                Building b = col.building(Integer.parseInt(n.jobKey.substring(6)));
                if (b != null && schools.contains(b)) return b;
            } catch (NumberFormatException ignored) {
                // pick again
            }
        }
        Building best = null;
        int bestCount = Integer.MAX_VALUE;
        for (Building b : schools) {
            int count = 0;
            for (Npc o : m.of(col)) if (o != n && ("study:" + b.id).equals(o.jobKey)) count++;
            if (count < capacity(b) && count < bestCount) {
                best = b;
                bestCount = count;
            }
        }
        if (best != null) n.jobKey = "study:" + best.id;
        return best;
    }

    /** Where the teacher stands: beside a lectern, inside the room. */
    private static Location lecternSpot(World w, Building school) {
        for (BlockPos lec : school.stations) {
            if (!lec.loaded(w)) continue;
            for (BlockFace f : SIDES) {
                BlockPos p = lec.relative(f);
                if (!school.contains(p)) continue;
                if (RoomScanner.standable(w, p.x(), p.y(), p.z())) return p.center(w).subtract(0, 0.5, 0);
            }
        }
        return null;
    }

    /** A spot in the room for this person (the same one every time). */
    private static Location interiorSpot(World w, Building school, Npc n) {
        if (school.min == null || school.max == null) return null;
        List<Location> spots = new ArrayList<>();
        for (int x = school.min.x(); x <= school.max.x(); x++) {
            for (int z = school.min.z(); z <= school.max.z(); z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                for (int y = school.min.y(); y <= Math.min(school.max.y(), school.min.y() + 1); y++) {
                    if (RoomScanner.standable(w, x, y, z)) {
                        spots.add(new Location(w, x + 0.5, y, z + 0.5));
                        break;
                    }
                }
            }
        }
        if (spots.isEmpty()) return null;
        return spots.get(Math.floorMod(n.c.id.hashCode(), spots.size()));
    }

    private Npc teacherIn(Colony col, Building school) {
        for (Npc o : m.of(col)) {
            if (o.c.status == Status.CITIZEN && o.c.job == Job.TEACHER && o.c.workplace == school.id && !o.sleeping
                    && school.inside(o.loc().getX(), o.loc().getY(), o.loc().getZ())) return o;
        }
        return null;
    }

    private List<Npc> present(Colony col, Building school) {
        List<Npc> out = new ArrayList<>();
        for (Npc o : m.of(col)) {
            Citizen c = o.c;
            boolean pupil = c.status == Status.CITIZEN && (c.job == Job.STUDENT || plugin.sim().citizens().stage(c) == LifeStage.CHILD);
            if (!pupil || o.sleeping || !school.inside(o.loc().getX(), o.loc().getY(), o.loc().getZ())) continue;
            out.add(o);
            if (out.size() >= capacity(school)) break;
        }
        return out;
    }

    // ───────────── lessons ─────────────

    /** Every 30 seconds: a lesson in every school with its teacher and pupils present. */
    void lessons(Colony col) {
        World w = col.world();
        if (w == null) return;
        Settings s = plugin.settings();
        long today = plugin.sim().day(col);
        for (Building school : col.valid(BuildingType.SCHOOL)) {
            Npc teacher = teacherIn(col, school);
            if (teacher == null) continue;
            List<Npc> pupils = present(col, school);
            if (pupils.isEmpty()) continue;
            if (school.bookDay != today && (school.stock.take("book", 1) == 1 || teacher.c.carried.take("book", 1) == 1)) school.bookDay = today;
            double book = school.bookDay == today ? s.bookLearning : 1;
            double shelves = 1 + Math.min(0.5, school.feature(Feature.BOOKSHELF) * 0.05);
            double skill = Math.max(0.5, Math.min(1.5, teacher.c.education / 60)) * (1 + 0.05 * teacher.c.level(Skill.TEACHING));
            for (Npc p : pupils) {
                boolean child = plugin.sim().citizens().stage(p.c) == LifeStage.CHILD;
                double gain = s.perLesson * (child ? s.childLearning : 1) * book * shelves * skill * p.c.trait.learning() * (0.8 + p.c.needs.mood / 250)
                        * (1 + col.bonus(com.colonysmp.core.Research.Effect.LEARNING));
                p.c.education = Math.min(100, p.c.education + gain);
                w.spawnParticle(Particle.ENCHANT, p.loc().add(0, 1.8, 0), 8, 0.3, 0.3, 0.3, 0.5);
            }
            teacher.c.skills.gain(Skill.TEACHING, 2 * pupils.size() * teacher.c.trait.learning());
            Fx.sound(teacher.loc(), "minecraft:item.book.page_turn", 0.8f, 1f);
            w.spawnParticle(Particle.NOTE, teacher.loc().add(0, 2.2, 0), 2, 0.3, 0.2, 0.3, 1);
        }
    }
}
