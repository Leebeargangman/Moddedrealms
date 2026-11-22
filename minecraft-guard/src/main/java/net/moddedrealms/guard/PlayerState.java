package net.moddedrealms.guard;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.moddedrealms.guard.check.Check;
import org.bukkit.Location;

/** Everything the checks remember about one online player. */
public final class PlayerState {
    public final UUID id;
    public final String name;
    public final boolean bedrock;

    // ---- main thread (movement / combat) ----
    /** Server tick until which nothing is checked. */
    public long graceUntil;
    /** Set by knockback, explosions, bounces, dismounts...: no fly/speed checks until the player lands. */
    public boolean awaitingGround;
    public long awaitingSince;
    public Location lastSafe;
    public double lastGroundY;
    public int airTicks;
    public long lastMoveTick;
    /** (tick, y) samples while in the air, for the hover check. */
    public final ArrayDeque<double[]> airY = new ArrayDeque<>();
    public long lastFlyFlagTick;

    public double windowDist;
    public int windowMoves;
    public int speedStreak;
    public int speedSuspectStreak;

    public Location prevLocation;

    // xray
    public final ArrayDeque<Long> hiddenOres = new ArrayDeque<>();

    // ---- netty threads (packets): guarded by this object's monitor ----
    public long lastTickEndNanos;
    public double timerBalanceMs;
    public final Set<Integer> attackedThisTick = new HashSet<>();
    public long firstAttackNanos;
    public final ArrayDeque<Long> swings = new ArrayDeque<>();
    public int cpsStreak;
    public long lastCpsSecond;
    /** True once we know the client sends a tick-end packet every tick (1.21.2+). */
    public volatile boolean modernClient;

    // ---- violations (main thread) ----
    public final Map<Check, ArrayDeque<Long>> certain = new EnumMap<>(Check.class);
    public final Map<Check, Long> lastAlert = new EnumMap<>(Check.class);
    public final Map<Check, Integer> totalFlags = new EnumMap<>(Check.class);
    public long lastAutoReplay;
    public long exemptUntilMillis;
    /** /mrguard debug: log every movement decision to console. */
    public volatile boolean debug;

    public PlayerState(UUID id, String name) {
        this.id = id;
        this.name = name;
        // Floodgate gives Bedrock players UUIDs starting with 00000000-0000-0000-.
        this.bedrock = id.getMostSignificantBits() == 0L;
    }
}
