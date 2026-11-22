package net.moddedrealms.smp.npc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

/** /mrnpc: create and edit NPCs in game. Changes are saved to npcs.yml at once. */
public final class NpcCommand implements TabExecutor {
    private static final List<String> SUBS = List.of("list", "create", "move", "skin", "name", "title", "action", "equip", "wander", "look", "remove", "tp");
    private final SmpPlugin plugin;

    public NpcCommand(SmpPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            say(sender, "NPCs (" + plugin.npcs().all().size() + "):", NamedTextColor.GOLD);
            for (NpcDef d : plugin.npcs().all()) {
                sender.sendMessage(Component.text(" " + d.id + " ", NamedTextColor.YELLOW).append(SmpPlugin.mm(d.name))
                        .append(Component.text(String.format("  %s %.0f %.0f %.0f  %s", d.world, d.x, d.y, d.z, d.action == null ? "dialogue" : d.action), NamedTextColor.GRAY)));
            }
            return true;
        }
        String sub = args[0].toLowerCase();
        if (args.length < 2) return say(sender, "/mrnpc " + sub + " <id> ...", NamedTextColor.YELLOW);
        String id = args[1].toLowerCase();
        String rest = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null;
        NpcDef d = plugin.npcs().get(id);
        if (!sub.equals("create") && d == null) return say(sender, "No NPC called " + id + ". /mrnpc list", NamedTextColor.RED);
        Player p = sender instanceof Player pl ? pl : null;
        switch (sub) {
            case "create" -> {
                if (p == null) return say(sender, "Stand where it should go.", NamedTextColor.RED);
                if (d != null) return say(sender, id + " already exists.", NamedTextColor.RED);
                d = new NpcDef();
                d.id = id;
                d.name = rest != null ? rest : "<yellow>" + id;
                d.setHome(p.getLocation());
                plugin.npcs().put(d);
                say(sender, "Created " + id + ". Its dialogue is '" + id + "' in dialogues.yml (or set /mrnpc action).", NamedTextColor.GREEN);
            }
            case "move" -> {
                if (p == null) return true;
                d.setHome(p.getLocation());
                done(sender, d);
            }
            case "skin" -> {
                d.skin = rest == null || rest.equalsIgnoreCase("none") ? null : rest.split(" ")[0];
                done(sender, d);
            }
            case "name" -> {
                d.name = rest == null ? id : rest;
                done(sender, d);
            }
            case "title" -> {
                d.title = rest == null || rest.equalsIgnoreCase("none") ? null : rest;
                done(sender, d);
            }
            case "action" -> {
                d.action = rest == null || rest.equalsIgnoreCase("dialogue") ? null : rest;
                done(sender, d);
            }
            case "look" -> {
                d.look = !d.look;
                done(sender, d);
            }
            case "wander" -> {
                try {
                    d.wander = rest == null ? 0 : Math.max(0, Math.min(16, Double.parseDouble(rest)));
                } catch (NumberFormatException e) {
                    return say(sender, "/mrnpc wander <id> <radius>", NamedTextColor.RED);
                }
                done(sender, d);
            }
            case "equip" -> {
                if (p == null) return true;
                d.equipment.clear();
                d.leatherColor = null;
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                    ItemStack it = p.getInventory().getItem(slot);
                    if (it != null && !it.getType().isAir()) {
                        d.equipment.put(slot, it.getType());
                        if (it.getItemMeta() instanceof LeatherArmorMeta lm && d.leatherColor == null) {
                            Color c = lm.getColor();
                            d.leatherColor = c;
                        }
                    }
                }
                done(sender, d);
            }
            case "remove" -> {
                plugin.npcs().remove(id);
                say(sender, "Removed " + id + ".", NamedTextColor.GREEN);
            }
            case "tp" -> {
                if (p == null) return true;
                Entity e = plugin.npcs().entity(id);
                Location l = e != null ? e.getLocation() : d.home();
                if (l != null) p.teleport(l);
            }
            default -> say(sender, "/mrnpc <" + String.join("|", SUBS) + ">", NamedTextColor.YELLOW);
        }
        return true;
    }

    private void done(CommandSender s, NpcDef d) {
        plugin.npcs().save();
        plugin.npcs().refresh(d.id);
        say(s, "Updated " + d.id + ".", NamedTextColor.GREEN);
    }

    private static boolean say(CommandSender s, String m, NamedTextColor c) {
        s.sendMessage(Component.text(m, c));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("create")) {
            for (NpcDef d : plugin.npcs().all()) if (d.id.startsWith(args[1].toLowerCase())) out.add(d.id);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("action")) {
            out.addAll(List.of("dialogue", "quests", "command:", "console:"));
        }
        return out;
    }
}
