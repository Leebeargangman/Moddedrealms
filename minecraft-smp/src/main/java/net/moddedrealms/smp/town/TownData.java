package net.moddedrealms.smp.town;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

/** One player's standing with the town: reputation, wanted level, bounty, jail time, police career, Bureau case. */
public final class TownData {
    public final UUID id;
    public String name = "";
    public int rep;
    public int stars;
    public String reason = "";
    /** Seconds still to serve (only counts down while online). */
    public int jailLeft;
    public int cell = -1;
    /** Which town's jail they're in ("spawn" or another town's id). */
    public String jailTown = "spawn";
    public int offences;
    /** Newcomer warnings used up: a newcomer's first minor offences are only a warning. */
    public int graceWarnings;
    public int arrests;
    public boolean warnedContraband;
    /** Money other players added to this player's bounty (the town adds its own per wanted star). */
    public double bountyPlayers;
    // player police
    public boolean cop;
    public boolean onDuty;
    public int copArrests;
    // Bureau case: open until this world full-time (ticks)
    public boolean caseOpen;
    public long caseUntil;
    public String caseReason = "";
    // community service: pieces of litter left, and the jail time it replaced (served if they walk off)
    public int serviceLeft;
    public int serviceFallback;
    // not saved
    public long lastSeenByPolice;
    public long lastDecay;
    public long orderedAt;
    public boolean hostile;
    public long lastSearch;
    public long lastRobbed;
    public long lastPickpocketTry;
    public long lastRobTry;
    public long lastTip;
    /** Runtime only: when they last fought a monster, when the Watch last let them off, when they were last told off for a stray hit. */
    public long lastMobFight, lastGraceAt, lastAccidentNote;
    public UUID tippedBy;
    public long tippedAt;
    public long nextAmbush;
    public boolean stakeoutDone;
    public boolean outOfReachNotified;
    public Location trap;
    public final Map<Long, Integer> visits = new HashMap<>();
    public final Map<Long, Location> visitSpot = new HashMap<>();
    public long lastTrack;
    public long dutySince;
    public long serviceOfferUntil;
    public int lastArrestStars;
    /** Who this player hit, and when (so the other side may hit back in self-defence). */
    public final Map<UUID, Long> attacked = new HashMap<>();
    /** Who hit this player recently (player police who helped share the credit when a wanted player is caught). */
    public final Map<UUID, Long> damagedBy = new HashMap<>();
    public final Map<String, Long> recentCrimes = new HashMap<>();
    public final Map<UUID, Long> pickedNpcs = new HashMap<>();
    /** Standing with each NPC gang (50+ = ally). Saved. */
    public final Map<String, Integer> npcGangRep = new HashMap<>();
    public long breakoutUntil;
    // bank loan (saved)
    public double loanOwed, loanInstalment;
    public long loanNextDue;
    public int loanMissed, loanDefaults;
    public boolean loanDefaulted;
    // railway tickets (saved)
    public int rides;
    public long dayPassUntil;
    public boolean firstTicketBought;
    /** Told that money turned into metal coins. */
    public boolean seenCoinNotice;
    public long lastGangHome;
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
        d.jailTown = s.getString("jail-town", "spawn");
        d.offences = s.getInt("offences");
        d.graceWarnings = s.getInt("grace-warnings");
        d.arrests = s.getInt("arrests");
        d.cop = s.getBoolean("police", s.getBoolean("deputy"));
        d.onDuty = s.getBoolean("on-duty");
        d.copArrests = s.getInt("police-arrests");
        d.warnedContraband = s.getBoolean("warned-contraband");
        d.bountyPlayers = s.getDouble("bounty-players");
        d.caseOpen = s.getBoolean("case.open");
        d.caseUntil = s.getLong("case.until");
        d.caseReason = s.getString("case.reason", "");
        d.serviceLeft = s.getInt("service.left");
        d.serviceFallback = s.getInt("service.fallback");
        d.loanOwed = s.getDouble("loan.owed");
        d.loanInstalment = s.getDouble("loan.instalment");
        d.loanNextDue = s.getLong("loan.next-due");
        d.loanMissed = s.getInt("loan.missed");
        d.loanDefaults = s.getInt("loan.defaults");
        d.loanDefaulted = s.getBoolean("loan.defaulted");
        d.rides = s.getInt("rail.rides");
        d.dayPassUntil = s.getLong("rail.day-pass-until");
        d.firstTicketBought = s.getBoolean("rail.first-ticket");
        d.seenCoinNotice = s.getBoolean("coin-notice");
        var ng = s.getConfigurationSection("npc-gangs");
        if (ng != null) for (String k : ng.getKeys(false)) d.npcGangRep.put(k, ng.getInt(k));
        return d;
    }

    boolean worthSaving() {
        return loanOwed > 0 || loanDefaults > 0 || rides > 0 || firstTicketBought || seenCoinNotice || !npcGangRep.isEmpty() || rep != 0 || stars != 0 || jailLeft > 0 || offences > 0 || graceWarnings > 0 || cop || warnedContraband || bountyPlayers > 0 || caseOpen || serviceLeft > 0;
    }

    void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("rep", rep);
        s.set("stars", stars);
        s.set("reason", reason.isEmpty() ? null : reason);
        s.set("jail-left", jailLeft > 0 ? jailLeft : null);
        s.set("cell", jailLeft > 0 ? cell : null);
        s.set("jail-town", jailLeft > 0 && !"spawn".equals(jailTown) ? jailTown : null);
        s.set("offences", offences);
        s.set("grace-warnings", graceWarnings > 0 ? graceWarnings : null);
        s.set("arrests", arrests);
        s.set("police", cop ? true : null);
        s.set("on-duty", cop && onDuty ? true : null);
        s.set("police-arrests", copArrests > 0 ? copArrests : null);
        s.set("warned-contraband", warnedContraband ? true : null);
        s.set("bounty-players", bountyPlayers > 0 ? bountyPlayers : null);
        if (caseOpen) {
            s.set("case.open", true);
            s.set("case.until", caseUntil);
            s.set("case.reason", caseReason);
        }
        for (var e : npcGangRep.entrySet()) s.set("npc-gangs." + e.getKey(), e.getValue());
        if (rides > 0) s.set("rail.rides", rides);
        if (loanOwed > 0 || loanDefaults > 0) {
            s.set("loan.owed", loanOwed);
            s.set("loan.instalment", loanInstalment);
            s.set("loan.next-due", loanNextDue);
            s.set("loan.missed", loanMissed);
            s.set("loan.defaults", loanDefaults);
            s.set("loan.defaulted", loanDefaulted);
        }
        if (dayPassUntil > System.currentTimeMillis()) s.set("rail.day-pass-until", dayPassUntil);
        if (firstTicketBought) s.set("rail.first-ticket", true);
        if (seenCoinNotice) s.set("coin-notice", true);
        if (serviceLeft > 0) {
            s.set("service.left", serviceLeft);
            s.set("service.fallback", serviceFallback);
        }
    }

    public boolean jailed() {
        return jailLeft > 0;
    }

    public boolean inService() {
        return serviceLeft > 0;
    }
}
