package com.colonysmp.colony;

import com.colonysmp.ColonySMP;
import com.colonysmp.api.ColonyApi;
import com.colonysmp.core.Goods;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.util.Fx;
import com.colonysmp.util.ItemGoods;
import com.colonysmp.util.Messages;
import com.colonysmp.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A colony's first steps, shown on a boss bar (the task bar): open the colony menu, give a settler a job, lay out
 * the first farm, grow to five settlers and stock the stores. Each step pays out at once (onboarding.rewards in
 * config.yml); the text is in messages.yml under quests. Members can hide the bar with /colony tasks off.
 */
public final class QuestManager implements Listener {

    public static final int MENU = 1, JOB = 2, FARM = 3, GROW = 4, STORES = 5;
    public static final int COUNT = 5, DONE = COUNT + 1;
    /** Saved with each colony, so colonies from before this list can be moved onto it. */
    public static final int VERSION = 2;

    private final ColonySMP plugin;
    private final Map<String, BossBar> bars = new HashMap<>();
    private final Map<String, Set<UUID>> viewers = new HashMap<>();
    /** When each colony last moved on a step (for the idle nudges). */
    private final Map<String, Long> lastStep = new HashMap<>();
    private final Map<String, String> barNames = new HashMap<>();

    public QuestManager(ColonySMP plugin) {
        this.plugin = plugin;
    }

    /** A colony saved with the old four steps (found, house, farm, food) gets the matching new step. */
    public static int migrate(int oldStage) {
        if (oldStage <= 1) return MENU;
        if (oldStage >= 5) return DONE;
        return JOB;
    }

    private static String key(int stage) {
        return switch (stage) {
            case MENU -> "menu";
            case JOB -> "job";
            case FARM -> "farm";
            case GROW -> "grow";
            case STORES -> "stores";
            default -> "done";
        };
    }

    public String title(int stage) {
        return Messages.get("quests." + key(stage) + ".title");
    }

    public String task(int stage) {
        return Messages.get("quests." + key(stage) + ".task", "settlers", settlerGoal(), "food", foodGoal());
    }

    /** What a settler says to nudge a player who's stuck on this step. */
    public String hint(int stage) {
        return Messages.get("quests." + key(stage) + ".hint", "settlers", settlerGoal(), "food", foodGoal());
    }

    private int settlerGoal() {
        return Math.max(2, plugin.getConfig().getInt("onboarding.goals.settlers", 5));
    }

    private int foodGoal() {
        return Math.max(1, plugin.getConfig().getInt("onboarding.goals.food", 64));
    }

    /**
     * A brand-new colony still on its first three steps (menu, job, farm): its settlers work through the night so a
     * new player always sees them busy, and requests, travellers and daily reports wait until it's past them.
     */
    public boolean settling(Colony c) {
        return c != null && c.core != null && c.questStage < GROW && plugin.settings().feature("onboarding")
                && plugin.getConfig().getBoolean("onboarding.camp.quiet-first-steps", true);
    }

    /** A new colony in its first half hour (onboarding.camp.busy-minutes) still on its steps: its settlers don't stop for the night. */
    public boolean busy(Colony c) {
        if (settling(c)) return true;
        long mins = plugin.getConfig().getLong("onboarding.camp.busy-minutes", 30);
        return c != null && c.core != null && c.questStage < DONE && plugin.settings().feature("onboarding")
                && System.currentTimeMillis() - c.createdAt < mins * 60_000L;
    }

    public long lastStep(Colony c) {
        return lastStep.getOrDefault(c.id, 0L);
    }

    // ───────────── progress ─────────────

    private double progress(Colony c, StringBuilder label) {
        switch (c.questStage) {
            case MENU, JOB -> {
                label.append(task(c.questStage));
                return 0;
            }
            case FARM -> {
                label.append(task(FARM));
                return c.valid(com.colonysmp.model.BuildingType.FARM).isEmpty() ? 0 : 1;
            }
            case GROW -> {
                int n = c.population(), goal = settlerGoal();
                label.append(Messages.get("quests.grow.progress", "count", Math.min(n, goal), "settlers", goal));
                return Math.min(1.0, n / (double) goal);
            }
            case STORES -> {
                long food = food(c);
                label.append(Messages.get("quests.stores.progress", "count", Math.min(food, foodGoal()), "food", foodGoal()));
                return Math.min(1.0, food / (double) foodGoal());
            }
            default -> {
                return 1;
            }
        }
    }

    private static long food(Colony c) {
        long n = 0;
        for (Map.Entry<String, Long> e : c.storage.contents().entrySet()) if (Goods.get(e.getKey()).nutrition() >= 1) n += e.getValue();
        return n;
    }

    private static boolean woodcutter(Colony c) {
        for (Citizen ct : c.citizens.values()) if (ct.job == Job.FORESTER && ct.workplace != 0) return true;
        return false;
    }

    /** Steps finished by what the colony has (the others are finished by what a member does, see {@link #event}). */
    private boolean complete(Colony c) {
        if (c.core == null) return false;
        return switch (c.questStage) {
            case JOB -> woodcutter(c);
            case FARM -> !c.valid(com.colonysmp.model.BuildingType.FARM).isEmpty();
            case GROW -> c.population() >= settlerGoal();
            case STORES -> food(c) >= foodGoal();
            default -> false;
        };
    }

    /** Advances steps whose goals are met and refreshes the bar. Cheap enough to call often. */
    public void check(Colony c) {
        int guard = 0;
        while (c.questStage < DONE && complete(c) && guard++ < COUNT) finish(c, null);
        refresh(c);
    }

    /**
     * Something a member did that finishes a step: "menu" (opened the colony menu) or "job" (gave a settler a job).
     * A later step done first also finishes the ones before it.
     */
    public void event(Colony c, Player by, String what) {
        if (c == null || c.core == null || c.questStage >= DONE) return;
        int upTo = switch (what) {
            case "menu" -> MENU;
            case "job" -> JOB;
            default -> 0;
        };
        int guard = 0;
        while (c.questStage <= upTo && guard++ < COUNT) finish(c, by);
        check(c);
    }

    private void finish(Colony c, Player by) {
        int done = c.questStage;
        c.questStage++;
        lastStep.put(c.id, System.currentTimeMillis());
        for (Player p : c.onlineMembers()) {
            Text.title(p, Messages.get("quests.complete-title"), "<white>" + title(done), 5, 50, 15);
            Fx.sound(p, "minecraft:entity.player.levelup", 0.8f, 1.2f);
            Text.send(p, Messages.compose("quests.complete", "step", done, "count", COUNT, "title", title(done)));
            if (c.questStage < DONE) {
                Text.send(p, Messages.compose("quests.next", "title", title(c.questStage), "task", task(c.questStage)));
            }
        }
        reward(c, done, by);
        if (c.questStage == DONE) finale(c);
        plugin.requestSave();
    }

    /** Who gets a step's personal rewards: whoever did it, else the owner, else any member online. */
    private Player receiver(Colony c, Player by) {
        if (by != null && by.isOnline() && c.isMember(by.getUniqueId())) return by;
        Player owner = Bukkit.getPlayer(c.owner);
        if (owner != null) return owner;
        List<Player> on = c.onlineMembers();
        return on.isEmpty() ? null : on.get(0);
    }

    /**
     * Pays out a step: lines in onboarding.rewards.step-N. "treasury:25" and "good:oak_log:16" go to the colony,
     * "item:camp_banner" (or any ColonySMP item) to the player, everything else (money, key, xp, cosmetic...) to
     * ModdedrealmsSMP's rewards. Each owner is paid for each step once, however many colonies they found.
     */
    private void reward(Colony c, int step, Player by) {
        List<String> lines = plugin.getConfig().getStringList("onboarding.rewards.step-" + step);
        if (lines.isEmpty()) return;
        if (!plugin.funnel().firstTime(c.owner, "reward:step-" + step)) return;
        Player to = receiver(c, by);
        String said = pay(c, to, lines, "First steps: " + title(step));
        if (!said.isEmpty() && to != null) Text.send(to, Messages.compose("quests.reward", "rewards", Text.esc(said)));
    }

    /**
     * Pays reward lines: "treasury:25" and "good:oak_log:16" to the colony, "item:camp_banner" (or any ColonySMP
     * item) to the player, the rest (money, key, xp, cosmetic...) through ModdedrealmsSMP. Returns what was paid in
     * a few words ("" if nothing).
     */
    public String pay(Colony c, Player to, List<String> lines, String memo) {
        long day = plugin.sim().day(c);
        List<String> forServer = new ArrayList<>();
        List<String> said = new ArrayList<>();
        for (String line : lines) {
            String[] f = line.split(":");
            String kind = f[0].toLowerCase(Locale.ROOT);
            try {
                switch (kind) {
                    case "treasury" -> {
                        long n = Long.parseLong(f[1]);
                        c.treasury.credit(n, TxnType.REWARDS, memo, System.currentTimeMillis(), day);
                        said.add(Text.value(n) + " " + Messages.get("quests.reward-treasury"));
                    }
                    case "good" -> {
                        String good = Goods.key(f[1]);
                        long n = f.length > 2 ? Long.parseLong(f[2]) : 1;
                        if (Goods.known(good)) {
                            c.storage.force(good, n);
                            said.add(n + " " + Goods.get(good).name().toLowerCase(Locale.ROOT));
                        }
                    }
                    case "item" -> {
                        ItemStack it = f[1].equalsIgnoreCase("camp_banner") ? plugin.items().campBanner(c.name) : plugin.items().byId(f[1].toLowerCase(Locale.ROOT));
                        if (it != null && to != null) {
                            com.colonysmp.command.ColonyCommand.give(to, it);
                            said.add(Text.plain(it));
                        }
                    }
                    default -> forServer.add(line);
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Reward '" + line + "' (" + memo + ") failed: " + ex.getMessage());
            }
        }
        ColonyApi.ServerHook hook = ColonyApi.hook();
        if (!forServer.isEmpty() && to != null && hook != null) {
            hook.reward(to, forServer);
            String d = hook.describe(forServer);
            if (d != null && !d.isBlank()) said.add(d);
        }
        return String.join(", ", said);
    }

    private void finale(Colony c) {
        for (ItemStack it : plugin.settings().questReward) {
            String good = ItemGoods.goodOf(it);
            if (good != null) c.storage.force(good, it.getAmount());
            else c.equipment.force(it);
        }
        for (Player p : c.onlineMembers()) {
            Text.title(p, Messages.get("quests.done-title"), Messages.get("quests.done-subtitle"), 10, 70, 20);
            Fx.sound(p, "minecraft:ui.toast.challenge_complete", 1f, 1f);
            Text.send(p, Messages.get("quests.done"));
        }
    }

    // ───────────── the bar ─────────────

    public void refresh(Colony c) {
        BossBar bar = bars.get(c.id);
        Set<UUID> shown = viewers.computeIfAbsent(c.id, k -> new HashSet<>());
        if (c.questStage >= DONE) {
            if (bar != null) {
                for (UUID u : shown) {
                    Player p = Bukkit.getPlayer(u);
                    if (p != null) p.hideBossBar(bar);
                }
                shown.clear();
                bars.remove(c.id);
            }
            return;
        }
        StringBuilder label = new StringBuilder();
        double prog;
        String name;
        if (c.core == null) {
            prog = 0;
            name = Messages.get("quests.place-core");
        } else {
            prog = progress(c, label);
            name = Messages.compose("quests.bar", "step", c.questStage, "count", COUNT, "title", title(c.questStage), "task", label.toString());
        }
        if (bar == null) {
            bar = BossBar.bossBar(Text.mm(name), (float) prog, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);
            bars.put(c.id, bar);
        } else if (!name.equals(barNames.get(c.id)) || Math.abs(bar.progress() - prog) > 0.001) {
            bar.name(Text.mm(name));
            bar.progress((float) Math.max(0, Math.min(1, prog)));
        }
        barNames.put(c.id, name);
        Set<UUID> want = new HashSet<>();
        for (Player p : c.onlineMembers()) if (!c.questHidden.contains(p.getUniqueId())) want.add(p.getUniqueId());
        for (UUID u : new HashSet<>(shown)) {
            if (want.contains(u)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(bar);
            shown.remove(u);
        }
        for (UUID u : want) {
            if (shown.add(u)) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) p.showBossBar(bar);
            }
        }
    }

    public void setHidden(Colony c, Player p, boolean hidden) {
        if (hidden) c.questHidden.add(p.getUniqueId());
        else c.questHidden.remove(p.getUniqueId());
        refresh(c);
    }

    public void tick() {
        for (Colony c : plugin.colonies().all()) if (c.questStage < DONE) check(c);
    }

    public void colonyRemoved(Colony c) {
        lastStep.remove(c.id);
        barNames.remove(c.id);
        BossBar bar = bars.remove(c.id);
        Set<UUID> shown = viewers.remove(c.id);
        if (bar == null || shown == null) return;
        for (UUID u : shown) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.hideBossBar(bar);
        }
    }

    public void shutdown() {
        for (String id : List.copyOf(bars.keySet())) {
            Colony c = plugin.colonies().get(id);
            if (c != null) colonyRemoved(c);
        }
        bars.clear();
        viewers.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Colony c = plugin.colonies().of(e.getPlayer());
        if (c != null) Bukkit.getScheduler().runTaskLater(plugin, () -> refresh(c), 20);
        if (c != null && c.core != null && c.citizens.isEmpty()) {
            org.bukkit.entity.Player p = e.getPlayer();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                Text.send(p, "<yellow>Nobody lives in " + Text.esc(c.name) + " any more.</yellow> <gray>Call a new band of settlers (free, with provisions): "
                        + "<click:run_command:'/colony resettle'><hover:show_text:'<gray>Calls new settlers to your Town Hall'><white><u>/colony resettle</u></white></hover></click>");
            }, 100);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        for (Set<UUID> s : viewers.values()) s.remove(e.getPlayer().getUniqueId());
    }
}
