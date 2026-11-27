package com.colonysmp.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** MiniMessage helpers. Every player-facing string goes through here. */
public final class Text {

    public static final MiniMessage MM = MiniMessage.miniMessage();
    public static final String PREFIX = "<dark_aqua>[<aqua>Colony</aqua>]</dark_aqua> <gray>";

    private Text() {}

    public static Component mm(String s) {
        return MM.deserialize(s);
    }

    /** For item names and lore: no default italics. */
    public static Component item(String s) {
        return MM.deserialize(s).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> lore(List<String> lines) {
        List<Component> out = new ArrayList<>();
        for (String l : lines) out.add(item(l));
        return out;
    }

    public static List<Component> lore(String... lines) {
        return lore(List.of(lines));
    }

    public static void send(CommandSender to, String s) {
        if (to != null) to.sendMessage(mm(PREFIX + s));
    }

    public static void raw(CommandSender to, String s) {
        if (to != null) to.sendMessage(mm(s));
    }

    public static void bar(Player p, String s) {
        p.sendActionBar(mm(s));
    }

    public static void title(Player p, String title, String sub, int fadeIn, int stay, int fadeOut) {
        p.showTitle(Title.title(mm(title), mm(sub), Title.Times.times(
                Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
    }

    /** Escapes user-provided text (colony names, player names) for MiniMessage. */
    /** An item's name as plain text. */
    public static String plain(org.bukkit.inventory.ItemStack it) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.effectiveName());
    }

    public static String esc(String s) {
        return s == null ? "" : MM.escapeTags(s);
    }

    public static String duration(long ms) {
        if (ms <= 0) return "0s";
        long s = ms / 1000;
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }

    /** In-game hours as "3.5 h" or "2 days". */
    public static String gameHours(double hours) {
        if (hours >= 48) return String.format(Locale.ROOT, "%.1f days", hours / 24);
        return String.format(Locale.ROOT, "%.1f h", hours);
    }

    public static String bar(double fraction, int width, String on, String off) {
        int n = (int) Math.round(Math.max(0, Math.min(1, fraction)) * width);
        return on + "|".repeat(n) + off + "|".repeat(width - n);
    }

    /** A coloured 0-100 value with a little bar. */
    public static String meter(double v) {
        String c = v >= 60 ? "<green>" : v >= 30 ? "<yellow>" : "<red>";
        return c + Math.round(v) + " " + bar(v / 100, 10, c, "<dark_gray>");
    }

    public static String color(double v) {
        if (v >= 70) return "<green>";
        if (v >= 50) return "<yellow>";
        if (v >= 30) return "<gold>";
        return "<red>";
    }

    /**
     * A worth in valuables (there is no money: worth is measured against the iron nugget, see
     * {@link com.colonysmp.core.Treasury}).
     */
    public static String value(long amount) {
        return String.format(Locale.ROOT, "%,d worth of valuables", amount);
    }

    /** Valuables listed as items, largest first ("2 diamonds, 1 iron ingot"). */
    public static String items(java.util.Map<String, Long> goods) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        java.util.List<String> order = new java.util.ArrayList<>(goods.keySet());
        order.sort(java.util.Comparator.comparingLong((String g) -> com.colonysmp.core.Treasury.worth(g)).reversed());
        for (String g : order) {
            long n = goods.get(g);
            if (n > 0) parts.add(String.format(Locale.ROOT, "%,d %s", n, com.colonysmp.core.Goods.get(g).name().toLowerCase(Locale.ROOT)));
        }
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }

    public static String signed(long v) {
        return (v > 0 ? "<green>+" : v < 0 ? "<red>" : "<gray>") + String.format(Locale.ROOT, "%,d", v);
    }

    public static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.05) return String.valueOf(Math.round(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /** "a" or "an" before a word (which may start with MiniMessage tags). */
    public static String a(String word) {
        String bare = word.replaceAll("<[^>]*>", "");
        return (!bare.isEmpty() && "AEIOUaeiou".indexOf(bare.charAt(0)) >= 0 ? "an " : "a ") + word;
    }

    public static String nice(String enumName) {
        return com.colonysmp.core.Goods.nice(enumName);
    }
}
