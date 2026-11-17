package net.moddedrealms.testplayers;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Mannequin;

/** One simulated test player: what it should be doing, its AI settings, and the mannequin that is its body. */
final class TestPlayer {

    enum Mode { IDLE, WANDER, GOTO, FOLLOW, WALK, BUILD, WORK }

    final String id;
    final String name;

    // What it's doing
    Mode mode = Mode.IDLE;
    Location target;        // GOTO destination, or the current WANDER waypoint
    String follow;          // FOLLOW: player name
    double wanderRadius = 24;
    Location home;          // WANDER centre, and where it comes back after dying
    float walkYaw;          // WALK: heading in degrees (Minecraft yaw: 0 = south, 90 = west)
    boolean sprint;

    // AI
    String model;           // Ollama model, or null for none
    String staff;           // relay the model through the website to this staff member's computer (their connector)
    boolean ai;             // the model chooses what to do
    String goal;            // set on the Bots page; repeated in every prompt
    String personality;     // who it is: shapes what it chooses and how it talks
    long lastBotHeardAt;    // last time another test player's chat reached its AI (limits bot-to-bot chatter)
    long lastBuiltAt;
    String buildStatus = "building";
    String[] pendingBuild;  // {structure, material}: build this once it reaches the build zone it's walking to

    // Town life
    String town;            // the town it lives in, if any
    String job;             // builder, farmer, guard, scout, mayor, entertainer, trader, roadworker
    long jobUntil;          // busy with the current errand until then
    Location scoutOut;      // a scout's destination while it's out exploring
    String scoutReport;     // what a scout says when it gets back
    int harvested;
    final Map<Material, Integer> items = new EnumMap<>(Material.class); // what it carries: nothing appears from nowhere
    String trip;            // an explorer's trip: "out", "nether", "back"
    Location tripFrom;      // where the trip started (the town, or the portal it came through)
    Location portal;        // the portal it's using on a nether trip
    long tripUntil;
    Float tripYaw;          // which way its next trip out goes (its AI said), or null for anywhere
    String crewOf;          // a guard escorting this explorer
    long greetedAt;         // last time it waved at a real player
    String thought = "";
    boolean thinking;
    long nextThinkAt;
    final Deque<String[]> inbox = new ArrayDeque<>(); // {from, text}: chat aimed at this test player
    long lastSaidAt;
    long lastApiAt;         // last request to a paid AI API (the budget spaces them out)
    String aiNote;          // why it isn't thinking right now (budget, setup), for status lines
    boolean answering;      // the decision being made is a reply to chat

    // Body
    boolean wanted;         // should be in the world (false = stopped)
    Mannequin body;
    boolean spawning;       // waiting for its chunk to load
    Location lastKnown;
    long spawnedAt;
    long respawnAt;         // after dying, when to come back
    int deaths;
    double blocksMoved;
    String lastResult = "";

    // Movement bookkeeping
    Location progressMark;
    int stuckTicks;
    long detourUntil;       // walking around an obstacle until then, on detourYaw
    float detourYaw;
    int detourSide;         // +1 or -1: which way the last detour turned
    double bestDist;        // GOTO: closest it has been to the target...
    int ticksSinceBetter;   // ...and how long ago
    java.util.List<Path.Step> path; // the route it's following (see Path), if any
    int pathIndex;
    long pathKey = Long.MIN_VALUE;  // which target block the route is to
    long pathRetryAt;               // no route found: don't search again before then
    long chunkKey = Long.MIN_VALUE;
    String chunkWorld;
    int chunkRadius;

    TestPlayer(String name) {
        this.name = name;
        this.id = name.toLowerCase(Locale.ROOT);
    }

    /** The mannequin, if it's in the world and alive. */
    Mannequin entity() {
        return body != null && body.isValid() ? body : null;
    }

    boolean spawned() {
        return entity() != null;
    }

    String doing() {
        // A citizen shows its errand ("on patrol", "harvesting wheat") rather than how it's moving.
        if (town != null && mode != Mode.BUILD && mode != Mode.WORK && buildStatus != null && !buildStatus.equals("building")) return buildStatus;
        return switch (mode) {
            case IDLE -> "idle";
            case WANDER -> "wandering (" + Math.round(wanderRadius) + " blocks)";
            case GOTO -> target == null ? "going somewhere" : "going to " + target.getBlockX() + " " + target.getBlockY() + " " + target.getBlockZ();
            case FOLLOW -> "following " + follow;
            case WALK -> "walking " + compass(walkYaw);
            case BUILD, WORK -> buildStatus;
        };
    }

    static String compass(float yaw) {
        String[] names = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        int i = Math.floorMod(Math.round(yaw / 45f), 8);
        return names[i];
    }
}
