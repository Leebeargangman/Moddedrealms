package net.moddedrealms.smp.quest;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** The read-only /quests menu. */
final class QuestGui implements InventoryHolder {
    private Inventory inv;

    @Override
    public Inventory getInventory() {
        return inv;
    }

    static void open(SmpPlugin plugin, QuestManager qm, Player p) {
        QuestGui holder = new QuestGui();
        Inventory inv = Bukkit.createInventory(holder, 45, SmpPlugin.mm("<dark_gray>Quests"));
        holder.inv = inv;
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 0; i < 45; i++) inv.setItem(i, pane);

        PlayerQuests data = qm.peek(p);
        LocalDateTime now = LocalDateTime.now();
        Duration toDay = Duration.between(now, LocalDate.now().plusDays(1).atStartOfDay());
        Duration toWeek = Duration.between(now, LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay());

        inv.setItem(4, item(Material.BLAZE_POWDER, "<gold><bold>Daily streak: " + data.streak + " day" + (data.streak == 1 ? "" : "s"),
                List.of("<gray>Finish all your daily quests", "<gray>every day to grow your streak.", "",
                        "<yellow>Bonus at 3, 7 and 14 days.")));
        inv.setItem(9, item(Material.SUNFLOWER, "<yellow><bold>Daily quests", List.of("<gray>New ones in <white>" + fmt(toDay))));
        inv.setItem(27, item(Material.CLOCK, "<light_purple><bold>Weekly quests", List.of("<gray>New ones in <white>" + fmt(toWeek))));

        int[] dailySlots = {11, 13, 15};
        int[] weeklySlots = {29, 31, 33};
        int d = 0, w = 0;
        for (QuestManager.Active a : qm.active(p)) {
            ItemStack it = questItem(plugin, a);
            switch (a.def().kind) {
                case DAILY -> { if (d < dailySlots.length) inv.setItem(dailySlots[d++], it); }
                case WEEKLY -> { if (w < weeklySlots.length) inv.setItem(weeklySlots[w++], it); }
                case STORY -> inv.setItem(40, it);
            }
        }
        if (qm.storyStep(p) == null) inv.setItem(40, item(Material.NETHER_STAR, "<gold><bold>Story complete", List.of("<gray>You are a Legend of Moddedrealms.")));
        p.openInventory(inv);
    }

    private static ItemStack questItem(SmpPlugin plugin, QuestManager.Active a) {
        QuestDef q = a.def();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + q.description);
        lore.add("");
        if (a.done()) lore.add("<green>✔ Complete");
        else lore.add("<yellow>" + QuestManager.bar(a.progress(), q.amount) + " <white>" + a.progress() + "/" + q.amount);
        lore.add("");
        lore.add("<gray>Reward: <white>" + plugin.rewards().describe(q.rewards));
        String title;
        if (q.kind == QuestDef.Kind.STORY) {
            var giver = q.giver == null ? null : plugin.npcs().get(q.giver);
            title = "<gold><bold>✎ " + q.name;
            if (giver != null) lore.add("<gray>Quest giver: " + giver.name);
        } else {
            title = (a.done() ? "<green>" : q.kind == QuestDef.Kind.WEEKLY ? "<light_purple>" : "<yellow>") + "<bold>" + q.name;
        }
        ItemStack it = item(a.done() ? Material.LIME_DYE : q.icon, title, lore);
        if (a.done()) {
            ItemMeta m = it.getItemMeta();
            m.setEnchantmentGlintOverride(true);
            it.setItemMeta(m);
        }
        return it;
    }

    private static ItemStack item(Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(SmpPlugin.mm(name).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String s : lore) l.add(SmpPlugin.mm(s).decoration(TextDecoration.ITALIC, false));
        meta.lore(l);
        meta.addItemFlags(ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    private static String fmt(Duration d) {
        long h = d.toHours(), m = d.toMinutesPart();
        return h >= 24 ? (h / 24) + "d " + (h % 24) + "h" : h + "h " + m + "m";
    }
}
