package com.colonysmp;

import com.colonysmp.core.Needs;
import com.colonysmp.model.Job;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/** Typed view of config.yml. Rebuilt on /colony admin reload. */
public final class Settings {

    // general
    public final int autosaveMinutes, backups;

    // colonies and land
    public final int maxColoniesPerPlayer, startRadius, chunkGap, baseChunks, chunksPerLevel, maxChunks;
    public final long claimCost, claimCostStep;
    public final List<String> worlds;
    public final boolean blockPvp, protectExplosions, protectFire, protectFarmland, enterMessages;
    public final double shieldNewHours, shieldDefeatHours;
    /** Days (Minecraft days, counted only while a member is online) in which a new colony's people can't die. */
    public final double foundingGraceDays;
    /** Days between two free bands of settlers for a colony nobody lives in any more ({@code /colony resettle}). */
    public final int resettleCooldownDays;
    /** Places colonies can't be founded or claim land: config list plus zones other plugins add (the API). */
    public final List<Zone> noFoundZones = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** A circle (in blocks) of a world where no colony may hold land. */
    public record Zone(String key, String world, int x, int z, int radius, String label) {
        public boolean covers(String w, int cx, int cz) {
            if (!world.equalsIgnoreCase(w)) return false;
            // nearest point of the chunk to the centre
            int nx = Math.max(cx << 4, Math.min(x, (cx << 4) + 15)), nz = Math.max(cz << 4, Math.min(z, (cz << 4) + 15));
            long dx = nx - x, dz = nz - z;
            return dx * dx + dz * dz <= (long) radius * radius;
        }
    }

    /** The zone that keeps colonies off this chunk, or null. */
    public Zone noFoundZone(String world, int cx, int cz) {
        for (Zone z : noFoundZones) if (z.covers(world, cx, cz)) return z;
        return null;
    }

    // citizens
    public final List<Job> startingJobs;
    public final int maxPopulation, maxBodiesPerColony, maxBodiesTotal, workMargin, stuckTeleportSeconds, stuckForceSeconds;
    public final double citizenHealth, walkSpeed, runSpeed;
    /** Farmers, foresters, miners and fishers do nothing without their tool (it must be stored, handed over or made). */
    public final boolean requireTools;
    /** How far (blocks) from their pen animal keepers go to lead in wild animals (0: never), and whether from unclaimed land too. */
    public final int herdGatherRange;
    public final boolean herdGatherWilds;
    public final double farmInterval, chopInterval, mineInterval, buildInterval, fishInterval, stationInterval;

    // life
    public final double adultAge, elderAge, maxAge, birthChance, birthReserveDays, birthMinMood, partnerAffinity;

    // needs
    public final Needs.Params needs;
    public final Set<Material> neverEat;
    public final long eveningEnd;

    // education
    public final double perLesson, childLearning, bookLearning;
    public final int educationMin, educationMax;

    // economy
    public final long startingTreasury, recruitCost;
    public final double wageMultiplier, upkeepMultiplier, marketSpread, marketRecovery, mealPrice, shopMarkup;
    public final Map<String, Double> prices;

    // storage
    public final int hallSlotsPerLevel, slotsPerContainer, equipmentBase, armourySlotsPerContainer;
    public final double granaryFactor, warehouseLevelBonus;

    // simulation
    public final int stepSeconds, stepBudgetMs;
    public final double maxCatchUpHours;
    public final boolean backgroundProduction;
    public final int invalidGraceDays;

    // health and order
    public final double illnessChance, injuryThreshold, medicineHeal, theftChance, protestBelow, riotBelow;
    public final int protestDays, emigrateDays;
    public final double emigrateBelow, emigrateChance;

    // military
    public final int freeSoldiers;
    public final double trainingPerHour, upkeepPerClass;
    public final long recruitSoldierCost;

    // prisoners
    public final double downedChance, travelerDownedChance;
    public final int downedSeconds, resistanceMin, resistanceMax, persuasionMin, persuasionMax, starveDays;
    public final long ransomBase;

    // travellers
    public final boolean travelersEnabled;
    public final double travelerChance;
    public final int travelerWaitMinutes, reputationStart, attackPenalty, capturePenalty, recruitBonus;
    public final long lodgingFee;

    // war and diplomacy
    public final int warmupMinutes, siegeMinutes, raidMinutes, campMinDistance, campMaxDistance, supplyCheckSeconds, cooldownMinutes,
            requireOnlineDefenders, restoreDelaySeconds, proposalMinutes, truceDays, shipmentSecondsPerChunk;
    public final double supplyCutStability, spoilsFraction, raidLootFraction;
    public final boolean restoreBlocks, respawnAtCamp, allowOfflineRaids;
    public final long declareCost;

    // mobilisation
    public final int mobilizeMinutes, mobilizeCooldownMinutes;
    public final double mobilizeCost;

    // blueprints and mines
    public final int blueprintMaxSize, blueprintMaxBlocks, blueprintMax;
    public final int mineTargetY, mineTunnelLength, mineBranchLength, maxMines;

    // raids from the wilds
    public final boolean raidsEnabled, raidLoot;
    public final int raidMinPopulation, raidGraceDays, raidMinDays, raidMinSize, raidMaxSize, raidSpawnDistance, raidMaxMinutes;
    public final double raidChance, raidUndeadShare;
    public final long raidBounty, raidTributePerRaider;

    // requests, happenings and milestones
    public final boolean requestsEnabled, eventsEnabled, milestonesEnabled;
    public final int requestsMax, requestDays, requestMotivationDays, eventMinDays, caravanMinutes;
    public final double requestChance, eventChance, milestoneRewardFactor;

    // talk and Christmas
    public final int chatterSeconds, santaDays;
    /** "auto" (by the calendar), "always" or "never". */
    public final String christmasMode;
    public final java.time.MonthDay christmasFrom, christmasTo;

    public final List<ItemStack> questReward;
    /** Provisions the first settlers bring. */
    public final List<ItemStack> startingGoods;

    /** The living world (config: civilisations) and the transaction journal (config: transactions). */
    public static final class Civs {
        public final boolean enabled, generate, generatedChunksOnly, protect, wars, contracts, fullSync;
        public final int maxSettlements, maxPopulation, maxSoldiers, maxPerCiv, maxActiveNpcs, residents, activationDistance;
        public final int stepSeconds, generationSeconds, spacing, minFromSpawn, minFromColony, minFromPlayers, maxContracts;
        public final double production, growth, events, density, offlineRate, tradeRange, caravanSpeed, hourlyGainNote;
        public final int offlineDaysMax, tradeGapMs, tradesPerMinuteNote, rejectsPerMinuteNote, journalKeepDays;
        public final String news;
        public final java.util.List<String> worlds;
        /** "world,x,z,radius" places nothing is ever generated near. */
        public final java.util.List<int[]> avoid = new java.util.ArrayList<>();
        public final java.util.List<String> avoidWorlds = new java.util.ArrayList<>();
        public final Map<String, Double> worth = new java.util.HashMap<>();

        Civs(FileConfiguration c, Logger log) {
            enabled = c.getBoolean("civilisations.enabled", true);
            maxSettlements = Math.max(0, c.getInt("civilisations.max-settlements", 24));
            maxPopulation = Math.max(10, c.getInt("civilisations.max-citizens-per-settlement", 220));
            maxSoldiers = Math.max(0, c.getInt("civilisations.max-military-units", 60));
            maxPerCiv = Math.max(1, c.getInt("civilisations.max-settlements-per-civilisation", 4));
            maxActiveNpcs = Math.max(0, c.getInt("civilisations.max-active-npcs", 40));
            residents = Math.max(0, c.getInt("civilisations.residents-per-settlement", 3));
            activationDistance = Math.max(16, c.getInt("civilisations.npc-activation-distance", 64));
            stepSeconds = Math.max(1, c.getInt("civilisations.simulation-interval-seconds", 5));
            production = clamp(c.getDouble("civilisations.production-rate", 1), 0, 10);
            growth = clamp(c.getDouble("civilisations.growth-rate", 1), 0, 10);
            events = clamp(c.getDouble("civilisations.event-frequency", 1), 0, 10);
            wars = c.getBoolean("civilisations.wars", true);
            contracts = c.getBoolean("civilisations.contracts", true);
            maxContracts = Math.max(1, c.getInt("civilisations.max-contracts-per-player", 3));
            tradeRange = Math.max(100, c.getDouble("civilisations.trade-range", 2000));
            caravanSpeed = Math.max(50, c.getDouble("civilisations.caravan-blocks-per-day", 500));
            offlineRate = clamp(c.getDouble("civilisations.offline-rate", 0.25), 0, 1);
            offlineDaysMax = Math.max(0, c.getInt("civilisations.offline-days-max", 14));
            news = c.getString("civilisations.news", "nearby").toLowerCase(java.util.Locale.ROOT);
            protect = c.getBoolean("civilisations.protect-settlements", true);
            generate = c.getBoolean("civilisations.generation.enabled", true);
            generatedChunksOnly = c.getBoolean("civilisations.generation.only-in-explored-chunks", true);
            generationSeconds = Math.max(20, c.getInt("civilisations.generation.interval-seconds", 300));
            spacing = Math.max(200, c.getInt("civilisations.generation.spacing", 600));
            density = clamp(c.getDouble("civilisations.generation.density", 0.4), 0, 1);
            minFromSpawn = Math.max(0, c.getInt("civilisations.generation.min-distance-from-spawn", 250));
            minFromColony = Math.max(32, c.getInt("civilisations.generation.min-distance-from-colonies", 160));
            minFromPlayers = Math.max(0, c.getInt("civilisations.generation.min-distance-from-players", 180));
            worlds = c.getStringList("civilisations.generation.worlds");
            for (String a : c.getStringList("civilisations.generation.avoid")) {
                String[] p = a.split(",");
                try {
                    avoid.add(new int[]{Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim())});
                    avoidWorlds.add(p[0].trim());
                } catch (RuntimeException e) {
                    log.warning("civilisations.generation.avoid: '" + a + "' should be world,x,z,radius");
                }
            }
            ConfigurationSection w = c.getConfigurationSection("civilisations.worth");
            if (w != null) for (String k : w.getKeys(false)) worth.put(k.toLowerCase(java.util.Locale.ROOT), w.getDouble(k));
            fullSync = !"normal".equalsIgnoreCase(c.getString("transactions.durability", "full"));
            tradeGapMs = Math.max(0, c.getInt("transactions.min-milliseconds-between-trades", 250));
            tradesPerMinuteNote = Math.max(1, c.getInt("transactions.audit.trades-per-minute", 30));
            rejectsPerMinuteNote = Math.max(1, c.getInt("transactions.audit.refusals-per-minute", 20));
            hourlyGainNote = Math.max(1, c.getDouble("transactions.audit.net-worth-per-hour", 5000));
            journalKeepDays = Math.max(1, c.getInt("transactions.keep-days", 90));
        }
    }

    public final Civs civs;

    public Settings(FileConfiguration c, Logger log) {
        civs = new Civs(c, log);
        autosaveMinutes = Math.max(1, c.getInt("general.autosave-minutes", 5));
        backups = Math.max(0, c.getInt("general.backups-to-keep", 5));

        maxColoniesPerPlayer = Math.max(1, c.getInt("colonies.max-per-player", 1));
        startRadius = clampI(c.getInt("colonies.starting-radius", 1), 0, 4);
        chunkGap = Math.max(0, c.getInt("colonies.chunk-gap", 2));
        baseChunks = Math.max(1, c.getInt("colonies.base-chunks", 12));
        chunksPerLevel = Math.max(0, c.getInt("colonies.chunks-per-level", 10));
        maxChunks = Math.max(baseChunks, c.getInt("colonies.max-chunks", 120));
        claimCost = Math.max(0, c.getLong("colonies.claim-cost", 25));
        claimCostStep = Math.max(0, c.getLong("colonies.claim-cost-step", 5));
        worlds = c.getStringList("colonies.worlds");
        blockPvp = c.getBoolean("protection.block-pvp", true);
        protectExplosions = c.getBoolean("protection.protect-explosions", true);
        protectFire = c.getBoolean("protection.protect-fire", true);
        protectFarmland = c.getBoolean("protection.protect-farmland", true);
        enterMessages = c.getBoolean("protection.enter-messages", true);
        shieldNewHours = c.getDouble("protection.new-colony-shield-hours", 72);
        shieldDefeatHours = c.getDouble("protection.after-defeat-shield-hours", 24);
        foundingGraceDays = Math.max(0, c.getDouble("colonies.founding-grace-days", 4));
        resettleCooldownDays = Math.max(0, c.getInt("colonies.resettle-cooldown-days", 7));
        for (String z : c.getStringList("colonies.no-found-zones")) {
            String[] f = z.split(",");
            try {
                if (f.length < 4) throw new NumberFormatException();
                String label = f.length > 4 ? String.join(",", java.util.Arrays.copyOfRange(f, 4, f.length)).trim() : "a protected area";
                noFoundZones.add(new Zone("config:" + noFoundZones.size(), f[0].trim(), Integer.parseInt(f[1].trim()), Integer.parseInt(f[2].trim()),
                        Math.max(0, Integer.parseInt(f[3].trim())), label));
            } catch (NumberFormatException ex) {
                log.warning("colonies.no-found-zones: expected \"world,x,z,radius[,name]\", got " + z);
            }
        }

        List<Job> jobs = new ArrayList<>();
        for (String s : c.getStringList("citizens.starting-jobs")) {
            Job j = Job.parse(s);
            if (j == null) log.warning("citizens.starting-jobs: unknown job " + s);
            else jobs.add(j);
        }
        if (jobs.isEmpty()) jobs.addAll(List.of(Job.FARMER, Job.BUILDER, Job.UNEMPLOYED));
        startingJobs = List.copyOf(jobs);
        maxPopulation = Math.max(2, c.getInt("citizens.max-population", 150));
        maxBodiesPerColony = Math.max(4, c.getInt("citizens.max-bodies-per-colony", 60));
        maxBodiesTotal = Math.max(maxBodiesPerColony, c.getInt("citizens.max-bodies-total", 300));
        citizenHealth = Math.max(1, c.getDouble("citizens.health", 20));
        walkSpeed = clamp(c.getDouble("citizens.walk-speed", 0.13), 0.02, 0.6);
        runSpeed = clamp(c.getDouble("citizens.run-speed", 0.21), 0.02, 0.8);
        workMargin = Math.max(0, c.getInt("citizens.work-margin", 24));
        requireTools = c.getBoolean("citizens.require-tools", true);
        herdGatherRange = Math.max(0, Math.min(64, c.getInt("citizens.herd-gather-range", 32)));
        herdGatherWilds = c.getBoolean("citizens.herd-gather-wilds", true);
        com.colonysmp.util.Tools.setBareHands(requireTools ? 0 : clamp(c.getDouble("citizens.bare-hands-speed", 0.3), 0.05, 1));
        farmInterval = Math.max(0.1, c.getDouble("citizens.farm-interval", 1.5));
        chopInterval = Math.max(0.1, c.getDouble("citizens.chop-interval", 2.0));
        mineInterval = Math.max(0.1, c.getDouble("citizens.mine-interval", 2.5));
        buildInterval = Math.max(0.1, c.getDouble("citizens.build-interval", 1.2));
        fishInterval = Math.max(1, c.getDouble("citizens.fish-interval", 12));
        stationInterval = Math.max(0.2, c.getDouble("citizens.station-interval", 2.0));
        stuckTeleportSeconds = Math.max(3, c.getInt("citizens.stuck-teleport-seconds", 20));
        stuckForceSeconds = Math.max(stuckTeleportSeconds, c.getInt("citizens.stuck-force-seconds", 60));

        adultAge = Math.max(0, c.getDouble("life.adult-age-days", 5));
        elderAge = Math.max(adultAge + 1, c.getDouble("life.elder-age-days", 60));
        maxAge = Math.max(elderAge + 1, c.getDouble("life.max-age-days", 90));
        birthChance = clamp(c.getDouble("life.birth-chance", 0.25), 0, 1);
        birthReserveDays = Math.max(0, c.getDouble("life.birth-food-reserve-days", 3));
        birthMinMood = clamp(c.getDouble("life.birth-min-mood", 45), 0, 100);
        partnerAffinity = clamp(c.getDouble("life.partner-affinity", 60), 0, 100);

        needs = new Needs.Params(
                c.getDouble("needs.hunger-awake", 3.5), c.getDouble("needs.hunger-asleep", 1.5),
                c.getDouble("needs.eat-below", 55), c.getDouble("needs.eat-until", 92), c.getDouble("needs.satiety-per-point", 6),
                c.getDouble("needs.energy-awake", 5), c.getDouble("needs.energy-bed", 12), c.getDouble("needs.energy-no-bed", 6),
                c.getDouble("needs.tired", 30), c.getDouble("needs.exhausted", 12), c.getDouble("needs.starve-damage", 1.5),
                c.getDouble("needs.heal-fed", 0.4), c.getDouble("needs.heal-bed", 1.5), c.getDouble("needs.social-decay", 2),
                c.getDouble("needs.social-gain", 8), c.getDouble("needs.heavy-work-factor", 1.3));
        Set<Material> never = EnumSet.noneOf(Material.class);
        for (String s : c.getStringList("needs.never-eat")) {
            Material m = Material.matchMaterial(s);
            if (m != null) never.add(m);
        }
        neverEat = never;
        eveningEnd = c.getLong("needs.bedtime", 13500);

        perLesson = Math.max(0, c.getDouble("education.per-lesson", 1.5));
        childLearning = Math.max(0, c.getDouble("education.child-multiplier", 2));
        bookLearning = Math.max(1, c.getDouble("education.book-multiplier", 1.5));
        educationMin = clampI(c.getInt("education.starting-min", 10), 0, 100);
        educationMax = clampI(c.getInt("education.starting-max", 45), educationMin, 100);

        startingTreasury = Math.max(0, c.getLong("economy.starting-treasury", 150));
        recruitCost = Math.max(0, c.getLong("economy.recruit-cost", 30));
        wageMultiplier = Math.max(0, c.getDouble("economy.wage-multiplier", 1));
        upkeepMultiplier = Math.max(0, c.getDouble("economy.upkeep-multiplier", 1));
        marketSpread = clamp(c.getDouble("economy.market-spread", 0.25), 0, 0.9);
        marketRecovery = clamp(c.getDouble("economy.market-recovery-per-hour", 0.02), 0, 1);
        mealPrice = Math.max(0, c.getDouble("economy.meal-price-multiplier", 1));
        shopMarkup = Math.max(1, c.getDouble("economy.shop-markup", 1.2));
        Map<String, Double> pr = new HashMap<>();
        ConfigurationSection ps = c.getConfigurationSection("economy.prices");
        if (ps != null) for (String k : ps.getKeys(false)) pr.put(k.toLowerCase(java.util.Locale.ROOT), ps.getDouble(k));
        prices = pr;

        hallSlotsPerLevel = Math.max(9, c.getInt("storage.town-hall-slots-per-level", 27));
        slotsPerContainer = Math.max(1, c.getInt("storage.slots-per-container", 27));
        granaryFactor = Math.max(1, c.getDouble("storage.granary-factor", 1.5));
        warehouseLevelBonus = Math.max(0, c.getDouble("storage.level-bonus", 0.25));
        equipmentBase = Math.max(9, c.getInt("storage.equipment-base-slots", 54));
        armourySlotsPerContainer = Math.max(1, c.getInt("storage.armoury-slots-per-container", 27));

        stepSeconds = clampI(c.getInt("simulation.step-seconds", 30), 5, 600);
        stepBudgetMs = clampI(c.getInt("simulation.step-budget-ms", 15), 1, 1000);
        maxCatchUpHours = Math.max(1, c.getDouble("simulation.max-catch-up-hours", 48));
        backgroundProduction = c.getBoolean("simulation.background-production", true);
        invalidGraceDays = Math.max(0, c.getInt("simulation.invalid-building-grace-days", 2));

        illnessChance = clamp(c.getDouble("health.illness-chance", 0.01), 0, 1);
        injuryThreshold = clamp(c.getDouble("health.injury-threshold", 0.4), 0, 1);
        medicineHeal = Math.max(0, c.getDouble("health.medicine-heal", 6));
        theftChance = clamp(c.getDouble("order.theft-chance", 0.04), 0, 1);
        protestBelow = clamp(c.getDouble("order.protest-below", 35), 0, 100);
        riotBelow = clamp(c.getDouble("order.riot-below", 15), 0, 100);
        protestDays = Math.max(1, c.getInt("order.protest-days", 3));
        emigrateBelow = clamp(c.getDouble("order.emigrate-below", 15), 0, 100);
        emigrateDays = Math.max(1, c.getInt("order.emigrate-days", 3));
        emigrateChance = clamp(c.getDouble("order.emigrate-chance", 0.5), 0, 1);

        freeSoldiers = Math.max(0, c.getInt("military.soldiers-without-barracks", 4));
        trainingPerHour = Math.max(0, c.getDouble("military.training-per-hour", 0.02));
        upkeepPerClass = Math.max(0, c.getDouble("military.upkeep-multiplier", 1));
        recruitSoldierCost = Math.max(0, c.getLong("military.recruit-cost", 20));

        downedChance = clamp(c.getDouble("prisoners.downed-chance", 0.3), 0, 1);
        travelerDownedChance = clamp(c.getDouble("prisoners.traveler-downed-chance", 1.0), 0, 1);
        downedSeconds = Math.max(5, c.getInt("prisoners.downed-seconds", 60));
        resistanceMin = clampI(c.getInt("prisoners.resistance-min", 45), 1, 100);
        resistanceMax = clampI(c.getInt("prisoners.resistance-max", 100), resistanceMin, 100);
        persuasionMin = Math.max(1, c.getInt("prisoners.persuasion-min", 8));
        persuasionMax = Math.max(persuasionMin, c.getInt("prisoners.persuasion-max", 18));
        starveDays = Math.max(1, c.getInt("prisoners.starve-days", 5));
        ransomBase = Math.max(0, c.getLong("prisoners.ransom-base", 40));

        travelersEnabled = c.getBoolean("travelers.enabled", true);
        travelerChance = clamp(c.getDouble("travelers.chance-per-day", 0.6), 0, 1);
        travelerWaitMinutes = Math.max(1, c.getInt("travelers.wait-minutes", 6));
        reputationStart = clampI(c.getInt("travelers.reputation-start", 50), 0, 100);
        attackPenalty = Math.max(0, c.getInt("travelers.attack-penalty", 5));
        capturePenalty = Math.max(0, c.getInt("travelers.capture-penalty", 15));
        recruitBonus = Math.max(0, c.getInt("travelers.recruit-bonus", 5));
        lodgingFee = Math.max(0, c.getLong("travelers.lodging-fee", 6));

        warmupMinutes = Math.max(0, c.getInt("war.warmup-minutes", 20));
        siegeMinutes = Math.max(1, c.getInt("war.siege-minutes", 45));
        raidMinutes = Math.max(1, c.getInt("war.raid-minutes", 20));
        campMinDistance = Math.max(0, c.getInt("war.camp-min-distance", 8));
        campMaxDistance = Math.max(campMinDistance + 1, c.getInt("war.camp-max-distance", 64));
        supplyCheckSeconds = Math.max(5, c.getInt("war.supply-check-seconds", 30));
        supplyCutStability = Math.max(0, c.getDouble("war.supply-cut-stability", 5));
        spoilsFraction = clamp(c.getDouble("war.spoils-fraction", 0.3), 0, 1);
        raidLootFraction = clamp(c.getDouble("war.raid-loot-fraction", 0.05), 0, 1);
        cooldownMinutes = Math.max(0, c.getInt("war.cooldown-minutes", 60));
        requireOnlineDefenders = Math.max(0, c.getInt("war.require-online-defenders", 1));
        allowOfflineRaids = c.getBoolean("war.allow-offline-raids", false);
        restoreBlocks = c.getBoolean("war.restore-blocks", true);
        restoreDelaySeconds = Math.max(0, c.getInt("war.restore-delay-seconds", 120));
        respawnAtCamp = c.getBoolean("war.respawn-at-camp", true);
        declareCost = Math.max(0, c.getLong("war.declare-cost", 50));
        proposalMinutes = Math.max(1, c.getInt("diplomacy.proposal-minutes", 30));
        truceDays = Math.max(0, c.getInt("diplomacy.truce-days", 3));
        shipmentSecondsPerChunk = Math.max(0, c.getInt("diplomacy.caravan-seconds-per-chunk", 2));

        mobilizeMinutes = Math.max(1, c.getInt("military.mobilize-minutes", 10));
        mobilizeCooldownMinutes = Math.max(0, c.getInt("military.mobilize-cooldown-minutes", 20));
        mobilizeCost = Math.max(0, c.getDouble("military.mobilize-stability-cost", 5));

        blueprintMaxSize = Math.max(4, c.getInt("blueprints.max-size", 48));
        blueprintMaxBlocks = Math.max(64, c.getInt("blueprints.max-blocks", 20000));
        blueprintMax = Math.max(1, c.getInt("blueprints.max-per-colony", 10));
        mineTargetY = c.getInt("mines.target-y", 12);
        mineTunnelLength = Math.max(4, c.getInt("mines.tunnel-length", 32));
        mineBranchLength = Math.max(2, c.getInt("mines.branch-length", 16));
        maxMines = Math.max(1, c.getInt("mines.max-per-colony", 3));

        raidsEnabled = c.getBoolean("raids.enabled", true);
        raidMinPopulation = Math.max(1, c.getInt("raids.min-population", 6));
        raidGraceDays = Math.max(0, c.getInt("raids.grace-days", 5));
        raidMinDays = Math.max(1, c.getInt("raids.min-days-between", 4));
        raidChance = clamp(c.getDouble("raids.chance-per-day", 0.25), 0, 1);
        raidUndeadShare = clamp(c.getDouble("raids.undead-share", 0.35), 0, 1);
        raidMinSize = Math.max(1, c.getInt("raids.min-size", 3));
        raidMaxSize = Math.max(raidMinSize, c.getInt("raids.max-size", 24));
        raidSpawnDistance = clampI(c.getInt("raids.spawn-distance", 14), 2, 48);
        raidMaxMinutes = Math.max(1, c.getInt("raids.max-minutes", 12));
        raidBounty = Math.max(0, c.getLong("raids.bounty-per-raider", 6));
        raidTributePerRaider = Math.max(0, c.getLong("raids.tribute-per-raider", 12));
        raidLoot = c.getBoolean("raids.loot-storage", true);

        requestsEnabled = c.getBoolean("requests.enabled", true);
        requestsMax = Math.max(1, c.getInt("requests.max-open", 3));
        requestChance = clamp(c.getDouble("requests.chance-per-day", 0.7), 0, 1);
        requestDays = Math.max(1, c.getInt("requests.wait-days", 3));
        requestMotivationDays = Math.max(0, c.getInt("requests.motivated-days", 2));

        eventsEnabled = c.getBoolean("events.enabled", true);
        eventMinDays = Math.max(1, c.getInt("events.min-days-between", 2));
        eventChance = clamp(c.getDouble("events.chance-per-day", 0.4), 0, 1);
        caravanMinutes = Math.max(1, c.getInt("events.caravan-minutes", 8));

        milestonesEnabled = c.getBoolean("milestones.enabled", true);
        milestoneRewardFactor = Math.max(0, c.getDouble("milestones.reward-multiplier", 1));

        chatterSeconds = Math.max(0, c.getInt("citizens.chatter-seconds", 45));
        String mode = String.valueOf(c.getString("christmas.mode", "auto")).trim().toLowerCase(java.util.Locale.ROOT);
        christmasMode = mode.equals("always") || mode.equals("never") ? mode : "auto";
        christmasFrom = com.colonysmp.core.Season.parse(c.getString("christmas.from"), java.time.MonthDay.of(12, 1));
        christmasTo = com.colonysmp.core.Season.parse(c.getString("christmas.to"), java.time.MonthDay.of(1, 6));
        santaDays = Math.max(1, c.getInt("christmas.father-christmas-days-between", 6));

        questReward = items(c, "quests.reward", log);
        startingGoods = items(c, "citizens.starting-goods", log);
    }

    /** A list of "MATERIAL:amount" entries. */
    private static List<ItemStack> items(FileConfiguration c, String path, Logger log) {
        List<ItemStack> out = new ArrayList<>();
        for (String s : c.getStringList(path)) {
            String[] p = s.split(":");
            Material m = Material.matchMaterial(p[0].trim());
            if (m == null || !m.isItem()) {
                log.warning(path + ": unknown item " + s);
                continue;
            }
            int n = 1;
            if (p.length > 1) {
                try {
                    n = Integer.parseInt(p[1].trim());
                } catch (NumberFormatException e) {
                    log.warning(path + ": bad amount in " + s);
                }
            }
            out.add(new ItemStack(m, Math.max(1, n)));
        }
        return List.copyOf(out);
    }

    public boolean worldAllowed(String world) {
        if (worlds.isEmpty()) return true;
        for (String w : worlds) if (w.equalsIgnoreCase(world)) return true;
        return false;
    }

    /** Most chunks a colony of this settlement level may hold. */
    public int chunkLimit(int level) {
        return Math.min(maxChunks, baseChunks + chunksPerLevel * (level - 1));
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
