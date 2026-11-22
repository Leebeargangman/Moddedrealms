package net.moddedrealms.smp;

import java.io.File;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.moddedrealms.smp.crate.CrateManager;
import net.moddedrealms.smp.npc.Dialogues;
import net.moddedrealms.smp.npc.NpcCommand;
import net.moddedrealms.smp.npc.NpcManager;
import net.moddedrealms.smp.quest.QuestCommand;
import net.moddedrealms.smp.quest.QuestManager;
import net.moddedrealms.smp.spawn.PlazaBuilder;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        for (String f : new String[]{"quests.yml", "dialogues.yml", "crates.yml"}) {
            if (!new File(getDataFolder(), f).exists()) saveResource(f, false);
        }
        rewards = new Rewards(this);
        npcs = new NpcManager(this);
        dialogues = new Dialogues(this);
        quests = new QuestManager(this);
        crates = new CrateManager(this);
        plaza = new PlazaBuilder(this);

        var pm = getServer().getPluginManager();
        pm.registerEvents(npcs, this);
        pm.registerEvents(quests, this);
        pm.registerEvents(crates, this);
        pm.registerEvents(new Welcome(this), this);

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

        npcs.start();
        quests.start();
        if (getConfig().getBoolean("console-bridge", true)) new ConsoleBridge(this).start();
    }

    @Override
    public void onDisable() {
        if (crates != null) crates.finishAll();
        if (quests != null) quests.saveAll();
        if (npcs != null) npcs.despawnAll();
    }

    public void reloadEverything() {
        reloadConfig();
        rewards = new Rewards(this);
        dialogues = new Dialogues(this);
        quests.reload();
        crates.reload();
        npcs.reload();
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
}
