package net.moddedrealms.smp;

import java.io.File;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.moddedrealms.smp.crate.CrateManager;
import net.moddedrealms.smp.grave.GraveManager;
import net.moddedrealms.smp.npc.Dialogues;
import net.moddedrealms.smp.npc.NpcCommand;
import net.moddedrealms.smp.npc.NpcManager;
import net.moddedrealms.smp.quest.QuestCommand;
import net.moddedrealms.smp.quest.QuestManager;
import net.moddedrealms.smp.spawn.PlazaBuilder;
import net.moddedrealms.smp.town.TownCommand;
import net.moddedrealms.smp.town.TownManager;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class SmpPlugin extends JavaPlugin {
    public static final MiniMessage MM = MiniMessage.miniMessage();

    private Rewards rewards;
    private NpcManager npcs;
    private Dialogues dialogues;
    private QuestManager quests;
    private PlazaBuilder plaza;
    private CrateManager crates;
    private TownManager town;
    private net.moddedrealms.smp.season.SeasonManager season;
    private GraveManager graves;
    private net.moddedrealms.smp.onboard.Onboarding onboarding;
    private PackSender packSender;
    private Feedback feedback;
    private Insights insights;
    private Guide guide;
    private net.moddedrealms.smp.play.DailyStreak streaks;
    private net.moddedrealms.smp.play.Referrals referrals;
    private Reports reports;
    private net.moddedrealms.smp.play.Duels duels;
    private net.moddedrealms.smp.play.SupplyDrops drops;
    private net.moddedrealms.smp.play.AuctionHouse auctions;
    private net.moddedrealms.smp.play.Leaderboards boards;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Pack.load(getConfig());
        for (String f : new String[]{"quests.yml", "dialogues.yml", "crates.yml"}) {
            if (!new File(getDataFolder(), f).exists()) saveResource(f, false);
        }
        rewards = new Rewards(this);
        npcs = new NpcManager(this);
        dialogues = new Dialogues(this);
        quests = new QuestManager(this);
        crates = new CrateManager(this);
        plaza = new PlazaBuilder(this);
        town = new TownManager(this);
        season = new net.moddedrealms.smp.season.SeasonManager(this);
        graves = new GraveManager(this);
        onboarding = new net.moddedrealms.smp.onboard.Onboarding(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(npcs, this);
        pm.registerEvents(onboarding, this);
        pm.registerEvents(quests, this);
        pm.registerEvents(crates, this);
        pm.registerEvents(new Welcome(this), this);
        pm.registerEvents(graves, this);
        getCommand("graves").setExecutor(graves);
        getCommand("graves").setTabCompleter(graves);
        packSender = new PackSender(this);
        pm.registerEvents(packSender, this);
        feedback = new Feedback(this);
        pm.registerEvents(feedback, this);
        insights = new Insights(this);
        pm.registerEvents(insights, this);
        guide = new Guide(this);
        pm.registerEvents(guide, this);
        getCommand("start").setExecutor(guide);
        getCommand("poll").setExecutor(feedback);
        getCommand("suggest").setExecutor(feedback);
        reports = new Reports(this);
        pm.registerEvents(reports, this);
        getCommand("report").setExecutor(reports);
        streaks = new net.moddedrealms.smp.play.DailyStreak(this);
        pm.registerEvents(streaks, this);
        getCommand("daily").setExecutor(streaks);
        referrals = new net.moddedrealms.smp.play.Referrals(this);
        pm.registerEvents(referrals, this);
        getCommand("invite").setExecutor(referrals);
        getServer().getScheduler().runTaskTimer(this, referrals::tick, 1200L, 1200L);
        duels = new net.moddedrealms.smp.play.Duels(this);
        pm.registerEvents(duels, this);
        getCommand("duel").setExecutor(duels);
        getCommand("duel").setTabCompleter(duels);
        drops = new net.moddedrealms.smp.play.SupplyDrops(this);
        pm.registerEvents(drops, this);
        getCommand("drop").setExecutor(drops);
        auctions = new net.moddedrealms.smp.play.AuctionHouse(this);
        pm.registerEvents(auctions, this);
        getCommand("ah").setExecutor(auctions);
        getCommand("ah").setTabCompleter(auctions);
        boards = new net.moddedrealms.smp.play.Leaderboards(this);
        pm.registerEvents(boards, this);
        getCommand("leaderboard").setExecutor(boards);
        getCommand("leaderboard").setTabCompleter(boards);

        getCommand("advent").setExecutor(season.advent());
        getCommand("advent").setTabCompleter(season.advent());
        getCommand("titles").setExecutor(season.cosmetics());
        getCommand("titles").setTabCompleter(season.cosmetics());

        var smp = new SmpCommand(this);
        getCommand("mrsmp").setExecutor(smp);
        getCommand("mrsmp").setTabCompleter(smp);
        var q = new QuestCommand(this);
        getCommand("quests").setExecutor(q);
        getCommand("crates").setExecutor((sender, cmd, label, args) -> {
            if (sender instanceof org.bukkit.entity.Player p) crates.openMain(p);
            return true;
        });
        var n = new NpcCommand(this);
        getCommand("mrnpc").setExecutor(n);
        getCommand("mrnpc").setTabCompleter(n);

        var tc = new TownCommand(town);
        for (String c : new String[]{"mrtown", "rep", "bail", "surrender", "police", "wanted", "tip", "bounty", "bounties", "bribe", "service",
                "casino", "dice", "heist", "herald", "loan", "ticket", "census", "trial", "market", "stall"}) {
            getCommand(c).setExecutor(tc);
            getCommand(c).setTabCompleter(tc);
        }

        for (String c : new String[]{"business", "jobs", "job"}) {
            getCommand(c).setExecutor(town.businesses());
            getCommand(c).setTabCompleter(town.businesses());
        }
        for (String c : new String[]{"bank", "purse"}) {
            getCommand(c).setExecutor(town.bankDesk());
            getCommand(c).setTabCompleter(town.bankDesk());
        }
        getCommand("stocks").setExecutor(town.stocks());
        getCommand("stocks").setTabCompleter(town.stocks());
        getCommand("plot").setExecutor(town.plots());
        getCommand("plot").setTabCompleter(town.plots());
        for (String c : new String[]{"gang", "gc"}) {
            getCommand(c).setExecutor(town.gangs());
            getCommand(c).setTabCompleter(town.gangs());
        }

        npcs.start();
        quests.start();
        town.start();
        graves.start();
        feedback.start();
        insights.start();
        drops.start();
        auctions.start();
        boards.start();
        if (getConfig().getBoolean("console-bridge", true)) new ConsoleBridge(this).start();
    }

    @Override
    public void onDisable() {
        if (onboarding != null) onboarding.shutdown();
        if (graves != null) graves.stop();
        if (duels != null) duels.shutdown();
        if (drops != null) drops.shutdown();
        if (boards != null) boards.shutdown();
        if (feedback != null) feedback.save();
        if (insights != null) insights.save();
        if (referrals != null) referrals.save();
        if (season != null) season.shutdown();
        if (town != null) town.stop();
        if (crates != null) crates.finishAll();
        if (quests != null) quests.saveAll();
        if (npcs != null) npcs.despawnAll();
    }

    public void reloadEverything() {
        reloadConfig();
        Pack.load(getConfig());
        if (packSender != null) packSender.load(getConfig());
        rewards = new Rewards(this);
        dialogues = new Dialogues(this);
        quests.reload();
        crates.reload();
        npcs.reload();
        town.loadSettings();
        graves.loadSettings();
    }

    public YamlConfiguration yaml(String name) {
        return YamlConfiguration.loadConfiguration(new File(getDataFolder(), name));
    }

    public static Component mm(String s) {
        return MM.deserialize(s == null ? "" : s);
    }

    public Rewards rewards() { return rewards; }
    public NpcManager npcs() { return npcs; }
    public Dialogues dialogues() { return dialogues; }
    public QuestManager quests() { return quests; }
    public PlazaBuilder plaza() { return plaza; }
    public CrateManager crates() { return crates; }
    public TownManager town() { return town; }
    /** Season 1: advent calendar, countdown, cosmetics (season.yml). */
    public net.moddedrealms.smp.season.SeasonManager season() { return season; }
    public GraveManager graves() { return graves; }
    /** Elder Rowan's START HERE flow for new players. */
    public net.moddedrealms.smp.onboard.Onboarding onboarding() { return onboarding; }
    public Feedback feedback() { return feedback; }
    public Reports reports() { return reports; }
    public Guide guide() { return guide; }
    public net.moddedrealms.smp.play.DailyStreak streaks() { return streaks; }
    public net.moddedrealms.smp.play.Referrals referrals() { return referrals; }
    public Insights insights() { return insights; }
    public net.moddedrealms.smp.play.Duels duels() { return duels; }
    public net.moddedrealms.smp.play.SupplyDrops drops() { return drops; }
    public net.moddedrealms.smp.play.AuctionHouse auctions() { return auctions; }
    public net.moddedrealms.smp.play.Leaderboards boards() { return boards; }
}
