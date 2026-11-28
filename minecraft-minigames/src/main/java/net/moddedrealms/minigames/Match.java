package net.moddedrealms.minigames;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

/**
 * One match of a game in one arena: players wait in the arena, bots fill the empty spots when there aren't enough
 * real players, a countdown, the game, the result, and everyone back to the hub.
 */
abstract class Match {

    enum State { WAITING, COUNTDOWN, PLAYING, ENDING }

    final MinigamesPlugin plugin;
    final GameType type;
    final int slot;
    final Location origin;
    final List<Participant> players = new ArrayList<>();
    State state = State.WAITING;
    long t;               // ticks since the match was made
    long firstHumanAt = -1;
    long nextBotAt;
    int botTarget;        // fill up to this many with bots
    int countdown;
    long startedAt;
    long endAt;
    int startHumans;
    private final List<Object[]> chatQueue = new ArrayList<>(); // {tick, participant, text}

    Match(MinigamesPlugin plugin, GameType type, int slot) {
        this.plugin = plugin;
        this.type = type;
        this.slot = slot;
        this.origin = type.origin(plugin, slot);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int max = max(), min = min();
        // Not every lobby fills up: somewhere from a few short of full to full.
        this.botTarget = Math.max(min, r.nextInt(Math.max(min, max - 3), max + 1));
    }

    // ------------------------------------------------------------ what each game provides

    abstract void prepareArena();

    abstract Location waitingSpot(int index);

    abstract void onStart();

    abstract void tickGame();

    /** Right after the result: reveal roles, fireworks... */
    void onFinish(List<Participant> winners) {
    }

    /** Before everyone goes back: corpses, items, arena repairs. */
    abstract void onCleanup();

    abstract List<Component> sidebar(Participant viewer);

    /** A participant is out of the game for good (fell, killed, left). Games decide what else happens. */
    abstract void onOut(Participant p, String why);

    /** A bot joined the lobby: games give it a brain. */
    void onBotJoined(Participant p) {
    }

    /** Who hears what this participant says (dead players in Murder Mystery only reach the dead). */
    List<Player> audience(Participant speaker) {
        return humansOnline();
    }

    boolean nameTagsVisible() {
        return true;
    }

    // ------------------------------------------------------------ sizes and counts

    int min() {
        return type.min(plugin);
    }

    int max() {
        return type.max(plugin);
    }

    ConfigurationSection cfg() {
        return type.cfg(plugin);
    }

    int humans() {
        int n = 0;
        for (Participant p : players) if (!p.isBot() && !p.gone) n++;
        return n;
    }

    int bots() {
        int n = 0;
        for (Participant p : players) if (p.isBot() && !p.gone) n++;
        return n;
    }

    int present() {
        int n = 0;
        for (Participant p : players) if (!p.gone) n++;
        return n;
    }

    List<Participant> alive() {
        List<Participant> a = new ArrayList<>();
        for (Participant p : players) if (p.alive && !p.gone) a.add(p);
        return a;
    }

    List<Player> humansOnline() {
        List<Player> l = new ArrayList<>();
        for (Participant p : players) {
            if (p.isBot() || p.gone) continue;
            Player pl = p.player();
            if (pl != null) l.add(pl);
        }
        return l;
    }

    Participant of(Player p) {
        for (Participant pa : players) if (!pa.isBot() && pa.id.equals(p.getUniqueId()) && !pa.gone) return pa;
        return null;
    }

    Participant ofEntity(org.bukkit.entity.Entity e) {
        if (e == null) return null;
        for (Participant pa : players) {
            if (pa.gone) continue;
            if (pa.isBot() ? pa.bot.body() == e : pa.id.equals(e.getUniqueId())) return pa;
        }
        return null;
    }

    boolean open() {
        return (state == State.WAITING || state == State.COUNTDOWN) && (present() < max() || bots() > 0);
    }

    // ------------------------------------------------------------ joining and leaving

    void join(Player p) {
        if (present() >= max()) {
            // A real player takes a bot's spot.
            for (int i = players.size() - 1; i >= 0; i--) {
                Participant b = players.get(i);
                if (b.isBot() && !b.gone) {
                    removeBot(b, true);
                    break;
                }
            }
        }
        Participant pa = new Participant(p);
        players.add(pa);
        if (firstHumanAt < 0) firstHumanAt = t;
        nextBotAt = Math.max(nextBotAt, t + 40);
        readyPlayer(p);
        p.teleport(waitingSpot(players.size() - 1));
        p.getInventory().setItem(8, Items.leave());
        p.showTitle(Title.title(Component.text(type.title, type.color, TextDecoration.BOLD), Component.text(type.blurb, NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(600))));
        broadcast(pa.display(NamedTextColor.YELLOW).append(Component.text(" joined (" + present() + "/" + max() + ")", NamedTextColor.YELLOW)));
        if (state == State.WAITING && humans() == 1) {
            p.sendMessage(Component.text("» ", NamedTextColor.GOLD).append(Component.text(
                    plugin.getConfig().getBoolean("bots.enabled", true)
                            ? "Waiting for players. If not enough real players come, bots fill the empty spots."
                            : "Waiting for players.", NamedTextColor.GRAY)));
        }
        plugin.manager().track(p, this);
    }

    void addBot() {
        List<String> taken = new ArrayList<>();
        for (Match m : plugin.manager().matches()) for (Participant x : m.players) taken.add(x.name);
        double[] sk = range("bots.skill", 0.35, 0.85);
        Bot bot = new Bot(Bot.randomName(taken), ThreadLocalRandom.current().nextDouble(sk[0], sk[1] + 1e-9));
        Participant pa = new Participant(bot);
        players.add(pa);
        Location at = waitingSpot(players.size() - 1);
        bot.spawn(plugin, at, nameTagsVisible());
        broadcast(pa.display(NamedTextColor.YELLOW).append(Component.text(" joined (" + present() + "/" + max() + ")", NamedTextColor.YELLOW)));
        onBotJoined(pa);
        if (chance(0.25)) botSay(pa, pick("hi", "hey", "yo", "gl", "hii", "glhf", "hello", "sup"), 30 + rnd(80));
    }

    void removeBot(Participant b, boolean message) {
        b.gone = true;
        b.alive = false;
        b.bot.remove();
        players.remove(b);
        if (message) broadcast(b.display(NamedTextColor.GRAY).append(Component.text(" left (" + present() + "/" + max() + ")", NamedTextColor.GRAY)));
    }

    /** A real player leaves (quit, /hub, the leave item). */
    void leave(Participant p, boolean quit) {
        if (state == State.WAITING || state == State.COUNTDOWN) {
            players.remove(p);
            p.gone = true;
            broadcast(p.display(NamedTextColor.GRAY).append(Component.text(" left (" + present() + "/" + max() + ")", NamedTextColor.GRAY)));
        } else {
            boolean wasAlive = p.alive;
            p.gone = true;
            if (wasAlive && state == State.PLAYING) {
                p.alive = false;
                p.outAt = t;
                onOut(p, "left");
            }
            if (state == State.PLAYING) plugin.stats().game(type, p.id, p.name, false, p.kills, startHumans >= 2);
        }
    }

    // ------------------------------------------------------------ the match running

    final void tick() {
        t++;
        flushChat();
        switch (state) {
            case WAITING -> tickWaiting();
            case COUNTDOWN -> tickCountdown();
            case PLAYING -> {
                if (humans() == 0) {
                    // No real players left to play with: no point carrying on.
                    cleanup();
                    return;
                }
                tickGame();
            }
            case ENDING -> {
                if (t >= endAt) {
                    cleanup();
                    return;
                }
            }
        }
        for (Participant p : players) if (p.isBot() && !p.gone) p.bot.tickBody();
        if (t % 10 == 0) updateBoards();
    }

    private void tickWaiting() {
        if (humans() == 0) {
            cleanup();
            return;
        }
        boolean bots = plugin.getConfig().getBoolean("bots.enabled", true);
        long fillAt = firstHumanAt + (long) (plugin.getConfig().getDouble("bots.fill-after-seconds", 12) * 20);
        if (bots && t >= fillAt && present() < botTarget && t >= nextBotAt) {
            addBot();
            double[] g = range("bots.join-gap-seconds", 0.6, 2.2);
            nextBotAt = t + (long) (ThreadLocalRandom.current().nextDouble(g[0], g[1] + 1e-9) * 20);
        }
        boolean full = present() >= max() || (bots && present() >= botTarget);
        if (present() >= min() && (full || humans() >= min() && t - firstHumanAt > 20 * 45)) {
            state = State.COUNTDOWN;
            countdown = Math.max(3, cfg().getInt("countdown-seconds", 10));
            broadcast(Component.text("» ", NamedTextColor.GOLD).append(Component.text("Starting in " + countdown + " seconds!", NamedTextColor.YELLOW)));
        }
    }

    private void tickCountdown() {
        if (humans() == 0) {
            cleanup();
            return;
        }
        if (present() < min()) {
            state = State.WAITING;
            broadcast(Component.text("Not enough players, waiting...", NamedTextColor.RED));
            return;
        }
        if (t % 20 != 0) return;
        countdown--;
        if (countdown <= 5 && countdown > 0) {
            for (Player p : humansOnline()) {
                p.showTitle(Title.title(Component.text(String.valueOf(countdown), countdown <= 3 ? NamedTextColor.RED : NamedTextColor.YELLOW, TextDecoration.BOLD),
                        Component.empty(), Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
            }
        }
        if (countdown <= 0) start();
    }

    private void start() {
        state = State.PLAYING;
        startedAt = t;
        startHumans = humans();
        for (Participant p : players) p.resetGame();
        for (Player p : humansOnline()) p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        onStart();
    }

    /** The game is decided: show the result, record stats, and go back to the hub a few seconds later. */
    void finish(List<Participant> winners, Component headline) {
        if (state == State.ENDING) return;
        state = State.ENDING;
        endAt = t + 20 * 7;
        Component sub = winners.isEmpty() ? Component.empty() : names(winners);
        for (Player p : humansOnline()) {
            Participant me = of(p);
            boolean won = me != null && winners.contains(me);
            p.showTitle(Title.title(won ? Component.text("VICTORY!", NamedTextColor.GOLD, TextDecoration.BOLD) : headline, won ? headline : sub,
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(4), Duration.ofSeconds(1))));
            p.playSound(p.getLocation(), won ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_WITHER_SPAWN, 0.7f, 1f);
        }
        broadcast(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
        broadcast(Component.text("  ").append(headline));
        if (!winners.isEmpty()) broadcast(Component.text("  Winner" + (winners.size() > 1 ? "s" : "") + ": ", NamedTextColor.GRAY).append(sub));
        onFinish(winners);
        broadcast(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
        for (Participant p : players) {
            if (p.isBot() || p.gone) continue;
            plugin.stats().game(type, p.id, p.name, winners.contains(p), p.kills, startHumans >= 2);
        }
        for (Participant p : players) {
            if (!p.isBot() || p.gone) continue;
            if (chance(plugin.getConfig().getDouble("bots.chat-chance", 0.35) + 0.1)) {
                botSay(p, winners.contains(p) ? pick("gg", "ggs", "gg wp", "yay", "gg!", "gg :)") : pick("gg", "ggs", "gg wp", "gg", "rematch?", "close one"), 15 + rnd(60));
            }
        }
    }

    void cleanup() {
        onCleanup();
        for (Participant p : new ArrayList<>(players)) {
            if (p.isBot()) {
                p.bot.remove();
                continue;
            }
            Player pl = p.player();
            if (pl != null && !p.gone && plugin.manager().matchOf(pl) == this) {
                plugin.manager().untrack(pl);
                plugin.hub().send(pl);
            }
        }
        players.clear();
        plugin.manager().release(this);
    }

    // ------------------------------------------------------------ helpers for games

    void readyPlayer(Player p) {
        p.setGameMode(GameMode.ADVENTURE);
        p.getInventory().clear();
        p.setItemOnCursor(null);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        var hp = p.getAttribute(Attribute.MAX_HEALTH);
        p.setHealth(hp == null ? 20 : hp.getValue());
        p.setFoodLevel(20);
        p.setSaturation(20);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setLevel(0);
        p.setExp(0);
        p.setInvisible(false);
    }

    /** A real player is out but stays to watch. */
    void spectate(Player p, Location at) {
        p.getInventory().clear();
        p.setAllowFlight(true);
        p.setGameMode(GameMode.SPECTATOR);
        if (at != null) p.teleport(at);
        p.sendMessage(Component.text("» ", NamedTextColor.GOLD).append(Component.text("You're spectating. ", NamedTextColor.GRAY))
                .append(Component.text("/mg again", NamedTextColor.YELLOW)).append(Component.text(" plays again, ", NamedTextColor.GRAY))
                .append(Component.text("/hub", NamedTextColor.YELLOW)).append(Component.text(" goes back.", NamedTextColor.GRAY)));
    }

    void broadcast(Component c) {
        for (Player p : humansOnline()) p.sendMessage(c);
    }

    void actionBar(Component c) {
        for (Player p : humansOnline()) p.sendActionBar(c);
    }

    void sound(Location at, Sound s, float vol, float pitch) {
        at.getWorld().playSound(at, s, vol, pitch);
    }

    /** A bot says something in chat a little later (people take a moment to type). */
    void botSay(Participant bot, String text, int delayTicks) {
        botSay(bot, text, delayTicks, false);
    }

    /** duringGame: only worth saying while the game is still on (a callout), dropped once it's over. */
    void botSay(Participant bot, String text, int delayTicks, boolean duringGame) {
        chatQueue.add(new Object[] {t + Math.max(1, delayTicks), bot, text, duringGame});
    }

    private void flushChat() {
        if (chatQueue.isEmpty()) return;
        Iterator<Object[]> it = chatQueue.iterator();
        while (it.hasNext()) {
            Object[] q = it.next();
            if ((long) q[0] > t) continue;
            it.remove();
            Participant b = (Participant) q[1];
            if (b.gone || ((boolean) q[3] && state != State.PLAYING)) continue;
            Component line = Component.text(Bot.tag() + " ", NamedTextColor.DARK_GRAY)
                    .append(Component.text("<" + b.name + "> ", NamedTextColor.WHITE))
                    .append(Component.text((String) q[2], NamedTextColor.WHITE));
            for (Player p : audience(b)) p.sendMessage(line);
            plugin.getLogger().info("[bot chat] <" + b.name + "> " + q[2]);
        }
    }

    void updateBoards() {
        Component title = Component.text(type.title.toUpperCase(), type.color, TextDecoration.BOLD);
        for (Participant p : players) {
            if (p.isBot() || p.gone) continue;
            Player pl = p.player();
            if (pl == null) continue;
            List<Component> lines = new ArrayList<>();
            if (state == State.WAITING || state == State.COUNTDOWN) {
                lines.add(Component.empty());
                lines.add(Component.text("Players: ", NamedTextColor.GRAY).append(Component.text(present() + "/" + max(), NamedTextColor.WHITE)));
                if (bots() > 0) lines.add(Component.text("  of them bots: ", NamedTextColor.DARK_GRAY).append(Component.text(String.valueOf(bots()), NamedTextColor.GRAY)));
                lines.add(Component.empty());
                lines.add(state == State.COUNTDOWN
                        ? Component.text("Starting in ", NamedTextColor.GRAY).append(Component.text(countdown + "s", NamedTextColor.GREEN))
                        : Component.text("Waiting for players...", NamedTextColor.GRAY));
                lines.add(Component.empty());
                lines.add(Component.text("play.moddedrealms.net", NamedTextColor.YELLOW));
            } else {
                lines.addAll(sidebar(p));
            }
            plugin.board().show(pl, title, lines);
        }
    }

    Component names(List<Participant> ps) {
        Component c = Component.empty();
        for (int i = 0; i < ps.size(); i++) {
            if (i > 0) c = c.append(Component.text(", ", NamedTextColor.GRAY));
            c = c.append(ps.get(i).display(NamedTextColor.YELLOW));
        }
        return c;
    }

    double[] range(String path, double a, double b) {
        List<Double> l = plugin.getConfig().getDoubleList(path);
        if (l.size() >= 2) return new double[] {Math.min(l.get(0), l.get(1)), Math.max(l.get(0), l.get(1))};
        return new double[] {a, b};
    }

    static boolean chance(double c) {
        return ThreadLocalRandom.current().nextDouble() < c;
    }

    static int rnd(int n) {
        return ThreadLocalRandom.current().nextInt(Math.max(1, n));
    }

    @SafeVarargs
    static <T> T pick(T... options) {
        return options[ThreadLocalRandom.current().nextInt(options.length)];
    }

    static ItemStack item(Material m) {
        return new ItemStack(m);
    }

    long secondsLeft(long untilTick) {
        return Math.max(0, (untilTick - t + 19) / 20);
    }
}
