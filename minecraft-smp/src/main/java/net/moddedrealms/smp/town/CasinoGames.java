package net.moddedrealms.smp.town;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * The casino's games, all paid in server money: slot machines (about 96% return, jackpots announced), blackjack
 * against the dealer (3:2 blackjack, dealer stands on 17), shared roulette rounds every 30 seconds (European wheel),
 * and dice duels against players or the NPC gamblers (the house keeps 5% of the pot).
 */
public final class CasinoGames implements Listener {
    private final TownManager t;
    private final Random r = new Random();

    CasinoGames(TownManager t) {
        this.t = t;
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() {
        return t.plugin().getConfig();
    }

    private boolean inCasino(Player p) {
        if (p.hasPermission("mrsmp.admin") || t.casino().inside(p.getLocation())) return true;
        p.sendMessage(SmpPlugin.mm("<gray>You can only gamble inside the gambling den."));
        return false;
    }

    private boolean pay(Player p, double amount) {
        if (t.money.pay(p, amount)) return true;
        p.sendMessage(SmpPlugin.mm("<red>You need " + Money.fmt(amount) + " for that."));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
        return false;
    }

    private static ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        it.setItemMeta(meta);
        return it;
    }

    static final class Table implements InventoryHolder {
        final String game;
        Inventory inv;
        // blackjack
        double bet;
        final List<Integer> player = new ArrayList<>(), dealer = new ArrayList<>();
        boolean over;
        // slots
        boolean spinning;
        Runnable finish;

        Table(String game) {
            this.game = game;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    // ------------------------------------------------------------------ slots

    private record Sym(Material m, String name, int weight, int pays) {}

    private static final Sym[] SYMS = {
            new Sym(Material.APPLE, "Apple", 30, 5), new Sym(Material.GOLDEN_CARROT, "Carrot", 22, 8), new Sym(Material.MELON_SLICE, "Melon", 18, 12),
            new Sym(Material.EMERALD, "Emerald", 12, 25), new Sym(Material.DIAMOND, "Diamond", 7, 60), new Sym(Material.NETHER_STAR, "Star", 3, 250)};

    private Sym roll() {
        int total = 0;
        for (Sym s : SYMS) total += s.weight();
        int x = r.nextInt(total);
        for (Sym s : SYMS) if ((x -= s.weight()) < 0) return s;
        return SYMS[0];
    }

    public void slots(Player p, int price) {
        if (!inCasino(p) || !pay(p, price)) return;
        Table tb = new Table("slots");
        tb.inv = Bukkit.createInventory(tb, 27, SmpPlugin.mm("<gold>Slots · " + Money.fmt(price)));
        ItemStack pane = icon(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) tb.inv.setItem(i, pane);
        Sym[] result = {roll(), roll(), roll()};
        int[] reel = {11, 13, 15};
        tb.spinning = true;
        p.openInventory(tb.inv);
        int[] frame = {0};
        tb.finish = () -> {
            if (!tb.spinning) return;
            tb.spinning = false;
            for (int k = 0; k < 3; k++) tb.inv.setItem(reel[k], icon(result[k].m(), "<white>" + result[k].name()));
            payout(p, price, result);
        };
        Bukkit.getScheduler().runTaskTimer(t.plugin(), task -> {
            if (!tb.spinning) {
                task.cancel();
                return;
            }
            frame[0]++;
            for (int k = 0; k < 3; k++) {
                boolean stopped = frame[0] > 8 + k * 5;
                Sym s = stopped ? result[k] : SYMS[r.nextInt(SYMS.length)];
                tb.inv.setItem(reel[k], icon(s.m(), "<white>" + s.name()));
            }
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.2f + frame[0] * 0.03f);
            if (frame[0] > 8 + 2 * 5) {
                tb.finish.run();
                task.cancel();
            }
        }, 2L, 2L);
    }

    private void payout(Player p, int price, Sym[] s) {
        double win = 0;
        if (s[0] == s[1] && s[1] == s[2]) win = price * s[0].pays();
        else if (s[0] == s[1] || s[1] == s[2] || s[0] == s[2]) win = price;
        if (win > 0) t.money.give(p, win);
        if (win > price) {
            p.sendMessage(SmpPlugin.mm("<gold>★ Three " + s[0].name() + "s! <yellow>+" + Money.fmt(win)));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
            if (s[0].pays() >= 60) {
                for (Player o : Bukkit.getOnlinePlayers()) o.sendMessage(SmpPlugin.mm("<gold><bold>JACKPOT!</bold> <white>" + p.getName() + "</white> hit " + Money.fmt(win) + " on the slots at the Golden Rat!"));
                t.news(p.getName() + " hit a " + Money.fmt(win) + " jackpot at the underground gambling den!");
            }
        } else if (win == price) {
            p.sendActionBar(SmpPlugin.mm("<yellow>A pair: your " + Money.fmt(price) + " back."));
        } else {
            p.sendActionBar(SmpPlugin.mm("<gray>No luck. -" + Money.fmt(price)));
        }
    }

    // ------------------------------------------------------------------ blackjack

    private static final int[] BETS = {50, 100, 250, 500, 1000, 2500};

    public void blackjack(Player p) {
        if (!inCasino(p)) return;
        Table tb = new Table("bj-bet");
        tb.inv = Bukkit.createInventory(tb, 27, SmpPlugin.mm("<dark_green>Blackjack · place your bet"));
        for (int i = 0; i < BETS.length; i++) {
            tb.inv.setItem(10 + i, icon(Material.GOLD_NUGGET, "<gold>Bet " + Money.fmt(BETS[i]), "<gray>Blackjack pays 3:2, dealer stands on 17."));
        }
        tb.inv.setItem(22, icon(Material.GOLD_INGOT, "<gold>Balance: " + Money.fmt(t.money.balance(p))));
        p.openInventory(tb.inv);
    }

    private static int value(List<Integer> hand) {
        int v = 0, aces = 0;
        for (int c : hand) {
            int rank = c % 13 + 1;
            if (rank == 1) {
                aces++;
                v += 11;
            } else v += Math.min(10, rank);
        }
        while (v > 21 && aces-- > 0) v -= 10;
        return v;
    }

    private ItemStack card(int c) {
        int rank = c % 13 + 1;
        String suit = new String[]{"♠", "♥", "♦", "♣"}[c / 13 % 4];
        String name = rank == 1 ? "A" : rank == 11 ? "J" : rank == 12 ? "Q" : rank == 13 ? "K" : String.valueOf(rank);
        boolean red = suit.equals("♥") || suit.equals("♦");
        return icon(red ? Material.RED_STAINED_GLASS_PANE : Material.WHITE_STAINED_GLASS_PANE, (red ? "<red>" : "<white>") + name + suit);
    }

    private int draw() {
        return r.nextInt(52);
    }

    private void startHand(Player p, double bet) {
        if (!pay(p, bet)) return;
        Table tb = new Table("bj");
        tb.bet = bet;
        tb.inv = Bukkit.createInventory(tb, 54, SmpPlugin.mm("<dark_green>Blackjack · " + Money.fmt(bet)));
        tb.player.add(draw());
        tb.dealer.add(draw());
        tb.player.add(draw());
        tb.dealer.add(draw());
        p.openInventory(tb.inv);
        if (value(tb.player) == 21) {
            finishHand(p, tb);
            return;
        }
        render(tb, false);
    }

    private void render(Table tb, boolean reveal) {
        Inventory inv = tb.inv;
        inv.clear();
        ItemStack felt = icon(Material.GREEN_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) inv.setItem(i, felt);
        inv.setItem(0, icon(Material.SKELETON_SKULL, "<gray>Dealer" + (reveal ? ": <white>" + value(tb.dealer) : "")));
        for (int i = 0; i < tb.dealer.size() && i < 7; i++) inv.setItem(2 + i, !reveal && i == 1 ? icon(Material.BLUE_STAINED_GLASS_PANE, "<gray>?") : card(tb.dealer.get(i)));
        inv.setItem(27, icon(Material.PLAYER_HEAD, "<white>You: " + value(tb.player)));
        for (int i = 0; i < tb.player.size() && i < 7; i++) inv.setItem(29 + i, card(tb.player.get(i)));
        if (!tb.over) {
            inv.setItem(46, icon(Material.LIME_CONCRETE, "<green><bold>HIT", "<gray>Take another card"));
            inv.setItem(49, icon(Material.RED_CONCRETE, "<red><bold>STAND", "<gray>Dealer plays"));
            if (tb.player.size() == 2) inv.setItem(52, icon(Material.GOLD_BLOCK, "<gold><bold>DOUBLE", "<gray>Double the bet, one more card"));
        } else {
            inv.setItem(49, icon(Material.GOLD_NUGGET, "<gold><bold>PLAY AGAIN", "<gray>Same bet: " + Money.fmt(tb.bet)));
        }
    }

    private void finishHand(Player p, Table tb) {
        tb.over = true;
        int pv = value(tb.player);
        boolean playerBJ = pv == 21 && tb.player.size() == 2;
        if (pv <= 21 && !playerBJ) while (value(tb.dealer) < 17) tb.dealer.add(draw());
        int dv = value(tb.dealer);
        boolean dealerBJ = dv == 21 && tb.dealer.size() == 2;
        double back;
        String msg;
        if (pv > 21) {
            back = 0;
            msg = "<red>Bust! You lose " + Money.fmt(tb.bet) + ".";
        } else if (playerBJ && !dealerBJ) {
            back = tb.bet * 2.5;
            msg = "<gold>Blackjack! You win " + Money.fmt(tb.bet * 1.5) + ".";
        } else if (dv > 21 || pv > dv) {
            back = tb.bet * 2;
            msg = "<green>You win " + Money.fmt(tb.bet) + "!";
        } else if (pv == dv && !(dealerBJ && !playerBJ)) {
            back = tb.bet;
            msg = "<yellow>Push. Your bet comes back.";
        } else {
            back = 0;
            msg = "<red>Dealer wins with " + dv + ". You lose " + Money.fmt(tb.bet) + ".";
        }
        if (back > 0) t.money.give(p, back);
        p.sendMessage(SmpPlugin.mm("<dark_green>[Blackjack] " + msg));
        p.playSound(p.getLocation(), back > tb.bet ? Sound.ENTITY_PLAYER_LEVELUP : back == tb.bet ? Sound.BLOCK_NOTE_BLOCK_PLING : Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 1f);
        render(tb, true);
    }

    // ------------------------------------------------------------------ roulette

    private static final int[] RED = {1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36};

    private record Bet(UUID player, String on, double amount) {}

    private final List<Bet> round = new ArrayList<>();
    private final Map<UUID, Integer> rouletteStake = new HashMap<>();
    private long spinAt;
    private String lastResults = "";

    private static boolean red(int n) {
        for (int x : RED) if (x == n) return true;
        return false;
    }

    public void roulette(Player p) {
        if (!inCasino(p)) return;
        Table tb = new Table("roulette");
        tb.inv = Bukkit.createInventory(tb, 27, SmpPlugin.mm("<dark_red>Roulette · place your bets"));
        drawRoulette(p, tb);
        p.openInventory(tb.inv);
    }

    private void drawRoulette(Player p, Table tb) {
        int stake = BETS[rouletteStake.getOrDefault(p.getUniqueId(), 1)];
        Inventory inv = tb.inv;
        inv.clear();
        inv.setItem(10, icon(Material.RED_WOOL, "<red><bold>RED", "<gray>Pays 1:1"));
        inv.setItem(11, icon(Material.BLACK_WOOL, "<dark_gray><bold>BLACK", "<gray>Pays 1:1"));
        inv.setItem(12, icon(Material.QUARTZ, "<white><bold>ODD", "<gray>Pays 1:1"));
        inv.setItem(13, icon(Material.COAL, "<gray><bold>EVEN", "<gray>Pays 1:1"));
        inv.setItem(14, icon(Material.IRON_NUGGET, "<white><bold>1-18", "<gray>Pays 1:1"));
        inv.setItem(15, icon(Material.GOLD_NUGGET, "<gold><bold>19-36", "<gray>Pays 1:1"));
        inv.setItem(16, icon(Material.LIME_WOOL, "<green><bold>ZERO", "<gray>Pays 35:1"));
        inv.setItem(22, icon(Material.SUNFLOWER, "<gold>Stake: " + Money.fmt(stake), "<gray>Click to change"));
        long left = spinAt == 0 ? -1 : Math.max(0, (spinAt - System.currentTimeMillis()) / 1000);
        inv.setItem(4, icon(Material.CLOCK, left < 0 ? "<gray>No bets yet: the wheel spins 30s after the first one" : "<yellow>Spinning in " + left + "s",
                "<gray>Last: " + (lastResults.isEmpty() ? "-" : lastResults)));
    }

    private void placeRoulette(Player p, String on) {
        double stake = BETS[rouletteStake.getOrDefault(p.getUniqueId(), 1)];
        if (!pay(p, stake)) return;
        round.add(new Bet(p.getUniqueId(), on, stake));
        if (spinAt == 0) spinAt = System.currentTimeMillis() + cfg().getLong("town.casino.roulette-seconds", 30) * 1000L;
        p.sendMessage(SmpPlugin.mm("<dark_red>[Roulette] <gray>" + Money.fmt(stake) + " on <white>" + on + "</white>. Spinning in "
                + Math.max(0, (spinAt - System.currentTimeMillis()) / 1000) + "s."));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 0.7f, 1.4f);
    }

    private boolean wins(String on, int n) {
        return switch (on) {
            case "red" -> n != 0 && red(n);
            case "black" -> n != 0 && !red(n);
            case "odd" -> n != 0 && n % 2 == 1;
            case "even" -> n != 0 && n % 2 == 0;
            case "1-18" -> n >= 1 && n <= 18;
            case "19-36" -> n >= 19;
            case "zero" -> n == 0;
            default -> false;
        };
    }

    /** Every second: spin when it's time, keep the board above the table up to date. */
    void second() {
        long now = System.currentTimeMillis();
        if (spinAt != 0 && now >= spinAt) {
            int n = r.nextInt(37);
            String col = n == 0 ? "<green>" : red(n) ? "<red>" : "<dark_gray>";
            lastResults = (col + n + " " + lastResults).trim();
            if (lastResults.length() > 120) lastResults = lastResults.substring(0, lastResults.lastIndexOf('<'));
            Map<UUID, Double> won = new HashMap<>();
            for (Bet b : round) {
                if (!wins(b.on(), n)) continue;
                double back = b.on().equals("zero") ? b.amount() * 36 : b.amount() * 2;
                won.merge(b.player(), back, Double::sum);
            }
            for (Bet b : round) won.putIfAbsent(b.player(), 0.0);
            for (var e : won.entrySet()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (e.getValue() > 0) t.money.give(Bukkit.getOfflinePlayer(e.getKey()), e.getValue());
                if (p != null) {
                    p.sendMessage(SmpPlugin.mm("<dark_red>[Roulette] <white>The ball lands on " + col + "<bold>" + n + "</bold><white>. "
                            + (e.getValue() > 0 ? "<green>You win " + Money.fmt(e.getValue()) + "!" : "<gray>Better luck next spin.")));
                    p.playSound(p.getLocation(), e.getValue() > 0 ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.2f);
                }
            }
            round.clear();
            spinAt = 0;
        }
        UUID board = t.casino().rouletteBoard();
        if (board != null) {
            Entity e = Bukkit.getEntity(board);
            if (e instanceof TextDisplay td) {
                String when = spinAt == 0 ? "<gray>Talk to Marcel to bet" : "<yellow>Spinning in " + Math.max(0, (spinAt - now) / 1000) + "s · " + round.size() + " bets";
                td.text(SmpPlugin.mm("<red><bold>ROULETTE</bold>\n" + when + (lastResults.isEmpty() ? "" : "\n<gray>Last: " + lastResults.split(" (?=<)")[0].trim())));
            }
        }
    }

    // ------------------------------------------------------------------ dice

    private record Challenge(UUID from, UUID to, double stake, long at) {}

    private final Map<UUID, Challenge> challenges = new HashMap<>();

    private int[] roll2() {
        return new int[]{1 + r.nextInt(6), 1 + r.nextInt(6)};
    }

    /** /dice <player> <stake> or /dice accept; NPC gamblers open a stake menu. */
    public void dice(Player p, String[] args) {
        if (!inCasino(p)) return;
        if (args.length == 0) {
            Table tb = new Table("dice-npc");
            tb.inv = Bukkit.createInventory(tb, 27, SmpPlugin.mm("<yellow>Dice · beat the house's roll"));
            for (int i = 0; i < BETS.length; i++) tb.inv.setItem(10 + i, icon(Material.BONE, "<gold>Roll for " + Money.fmt(BETS[i]), "<gray>Higher total wins, ties re-roll.", "<gray>The house keeps 5% of a win."));
            tb.inv.setItem(22, icon(Material.PAPER, "<gray>Or challenge a player:", "<white>/dice <player> <stake>"));
            p.openInventory(tb.inv);
            return;
        }
        if (args[0].equalsIgnoreCase("accept")) {
            Challenge c = challenges.remove(p.getUniqueId());
            if (c == null || System.currentTimeMillis() - c.at() > 60_000) {
                p.sendMessage(SmpPlugin.mm("<gray>Nobody has challenged you (or it expired)."));
                return;
            }
            Player from = Bukkit.getPlayer(c.from());
            if (from == null || !t.casino().inside(from.getLocation()) && !from.hasPermission("mrsmp.admin")) {
                p.sendMessage(SmpPlugin.mm("<gray>They're gone."));
                return;
            }
            if (!t.money.pay(from, c.stake())) {
                p.sendMessage(SmpPlugin.mm("<gray>" + from.getName() + " can't cover the stake any more."));
                return;
            }
            if (!pay(p, c.stake())) {
                t.money.give(from, c.stake());
                return;
            }
            duel(from, p, c.stake());
            return;
        }
        Player to = Bukkit.getPlayerExact(args[0]);
        if (to == null || to == p || args.length < 2) {
            p.sendMessage(SmpPlugin.mm("<yellow>/dice <player> <stake>  <gray>· /dice accept · or talk to a dice gambler"));
            return;
        }
        double stake;
        try {
            stake = Math.floor(Double.parseDouble(args[1]));
        } catch (NumberFormatException e) {
            p.sendMessage(SmpPlugin.mm("<red>Stake must be a number."));
            return;
        }
        if (stake < 10 || t.money.balance(p) < stake) {
            p.sendMessage(SmpPlugin.mm("<red>Stake at least $10, and you need to have it."));
            return;
        }
        challenges.put(to.getUniqueId(), new Challenge(p.getUniqueId(), to.getUniqueId(), stake, System.currentTimeMillis()));
        p.sendMessage(SmpPlugin.mm("<yellow>You challenge " + to.getName() + " to dice for " + Money.fmt(stake) + "."));
        to.sendMessage(Component.text().append(SmpPlugin.mm("<yellow>" + p.getName() + " challenges you to dice for " + Money.fmt(stake) + ". "))
                .append(SmpPlugin.mm("<green><bold>[Accept]").clickEvent(ClickEvent.runCommand("/dice accept"))).append(SmpPlugin.mm(" <gray>(60s)")).build());
    }

    private void duel(Player a, Player b, double stake) {
        int[] ra, rb;
        do {
            ra = roll2();
            rb = roll2();
        } while (ra[0] + ra[1] == rb[0] + rb[1]);
        Player winner = ra[0] + ra[1] > rb[0] + rb[1] ? a : b;
        double pot = Math.floor(stake * 2 * (1 - cfg().getDouble("town.casino.house-cut", 0.05)));
        t.money.give(winner, pot);
        String line = "<yellow>[Dice] <white>" + a.getName() + " rolls " + ra[0] + "+" + ra[1] + ", " + b.getName() + " rolls " + rb[0] + "+" + rb[1]
                + ". <gold>" + winner.getName() + " takes " + Money.fmt(pot) + "!";
        for (Player o : a.getWorld().getPlayers()) if (o.getLocation().distance(a.getLocation()) < 16) o.sendMessage(SmpPlugin.mm(line));
        if (!b.getWorld().equals(a.getWorld()) || b.getLocation().distance(a.getLocation()) >= 16) b.sendMessage(SmpPlugin.mm(line));
        a.playSound(a.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.7f);
        b.playSound(b.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.7f);
    }

    private void diceVsHouse(Player p, double stake) {
        if (!pay(p, stake)) return;
        int[] me, house;
        do {
            me = roll2();
            house = roll2();
        } while (me[0] + me[1] == house[0] + house[1]);
        boolean win = me[0] + me[1] > house[0] + house[1];
        double pot = Math.floor(stake * 2 * (1 - cfg().getDouble("town.casino.house-cut", 0.05)));
        if (win) t.money.give(p, pot);
        p.sendMessage(SmpPlugin.mm("<yellow>[Dice] <white>You roll " + me[0] + "+" + me[1] + ", the house rolls " + house[0] + "+" + house[1] + ". "
                + (win ? "<green>You take " + Money.fmt(pot) + "!" : "<red>You lose " + Money.fmt(stake) + ".")));
        p.playSound(p.getLocation(), win ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 1.2f);
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Table tb)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        switch (tb.game) {
            case "bj-bet" -> {
                int i = slot - 10;
                if (i >= 0 && i < BETS.length) startHand(p, BETS[i]);
            }
            case "bj" -> {
                if (tb.over) {
                    if (slot == 49) startHand(p, tb.bet);
                    return;
                }
                if (slot == 46) {
                    tb.player.add(draw());
                    if (value(tb.player) >= 21) finishHand(p, tb);
                    else render(tb, false);
                } else if (slot == 49) {
                    finishHand(p, tb);
                } else if (slot == 52 && tb.player.size() == 2) {
                    if (!pay(p, tb.bet)) return;
                    tb.bet *= 2;
                    tb.player.add(draw());
                    finishHand(p, tb);
                }
            }
            case "roulette" -> {
                String on = switch (slot) {
                    case 10 -> "red";
                    case 11 -> "black";
                    case 12 -> "odd";
                    case 13 -> "even";
                    case 14 -> "1-18";
                    case 15 -> "19-36";
                    case 16 -> "zero";
                    default -> null;
                };
                if (on != null) placeRoulette(p, on);
                else if (slot == 22) rouletteStake.put(p.getUniqueId(), (rouletteStake.getOrDefault(p.getUniqueId(), 1) + 1) % BETS.length);
                drawRoulette(p, tb);
            }
            case "dice-npc" -> {
                int i = slot - 10;
                if (i >= 0 && i < BETS.length) diceVsHouse(p, BETS[i]);
            }
            default -> { }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Table tb && tb.game.equals("slots") && tb.spinning && tb.finish != null) tb.finish.run();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Table) e.setCancelled(true);
    }

    /** Settles a blackjack hand left open when the player closes the table: they stand. */
    @EventHandler
    public void onCloseBj(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Table tb && tb.game.equals("bj") && !tb.over && e.getPlayer() instanceof Player p) {
            finishHand(p, tb);
        }
    }
}
