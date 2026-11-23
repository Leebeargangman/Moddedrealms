package com.colonysmp.npc;

import com.colonysmp.model.Citizen;
import com.colonysmp.util.BlockPos;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** The live body of a citizen and what it is doing right now (nothing here is saved: the citizen record is). */
public final class Npc {

    public final Citizen c;
    public final Mob body;
    public final Mover mover;
    /** A war horse the body rides (cavalry), or null. */
    public Mob mount;

    public long thinkOffset;
    public long actionAt;
    /** Game time of the last needs update. */
    public long lastLife = -1;
    /** Working at the workplace right now (hours count towards wages and the building's staffing). */
    public boolean working;

    // work
    public BlockPos target;
    public List<BlockPos> work;
    public int workIdx;
    public String jobKey;
    public final List<BlockPos> veins = new ArrayList<>();
    public long noToolUntil;
    /** Checked for something to carry recently and found nothing. */
    public long noHaulUntil;
    /** Looked for something to tend recently and found nothing (druids). */
    public long searchAt;
    public Entity subject;

    // routine
    public boolean sleeping;
    /** Resting without a bed (homeless at night, an exhausted nap, the sick lying down). */
    public boolean resting;
    /** Lying in a clinic bed. */
    public boolean patient;
    public long napUntil;
    public Location idleSpot;
    public long idleUntil;

    // combat
    public LivingEntity enemy;
    public long lastAttack, lastShot, enemyCheckAt;
    public int waypoint = -1;
    public long waypointUntil;
    public boolean gearChecked;
    /** Speed over the last second (blocks per tick), for cavalry charges. */
    public double speed;
    public double prevX, prevZ;
    public boolean braced;
    public long chargeReadyAt;

    // prison visits
    public Citizen visiting;
    public int visitStage;
    public long visitUntil;

    // shown state
    public ItemStack shownHand;
    public String shownName;
    public int shownLevel;

    public Npc(Citizen c, Mob body) {
        this.c = c;
        this.body = body;
        this.mover = new Mover(body);
        this.thinkOffset = Math.floorMod(c.id.hashCode(), 10);
        Location l = body.getLocation();
        prevX = l.getX();
        prevZ = l.getZ();
    }

    public Location loc() {
        return body.getLocation();
    }

    public boolean valid() {
        return body.isValid() && !body.isDead();
    }

    /** What the body is doing, shown in menus. */
    public void doing(String what) {
        if (!what.equals(c.activity)) {
            c.activity = what;
            c.activitySince = System.currentTimeMillis();
        }
    }

    public void resetWork() {
        subject = null;
        target = null;
        work = null;
        workIdx = 0;
        jobKey = null;
        veins.clear();
        working = false;
    }
}
