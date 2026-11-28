package com.colonysmp.command;

import com.colonysmp.ColonySMP;
import com.colonysmp.building.Blueprint;
import com.colonysmp.colony.QuestManager;
import com.colonysmp.core.Category;
import com.colonysmp.core.Goods;
import com.colonysmp.core.MilitaryClass;
import com.colonysmp.core.Recipe;
import com.colonysmp.core.Recipes;
import com.colonysmp.core.Research;
import com.colonysmp.gui.ResearchMenu;
import com.colonysmp.model.Request;
import com.colonysmp.gui.BuildingsMenu;
import com.colonysmp.gui.CitizensMenu;
import com.colonysmp.gui.ConfirmMenu;
import com.colonysmp.gui.DiplomacyMenu;
import com.colonysmp.gui.EconomyMenu;
import com.colonysmp.gui.MilitaryMenu;
import com.colonysmp.gui.PoliciesMenu;
import com.colonysmp.gui.ShopMenu;
import com.colonysmp.gui.TownHallMenu;
import com.colonysmp.model.BuildOrder;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.ColonyEvent;
import com.colonysmp.model.Job;
import com.colonysmp.model.Perm;
import com.colonysmp.model.Policies;
import com.colonysmp.model.Proposal;
import com.colonysmp.model.Relation;
import com.colonysmp.model.Role;
import com.colonysmp.model.Squad;
import com.colonysmp.model.Status;
import com.colonysmp.model.War;
import com.colonysmp.util.BlockPos;
import com.colonysmp.util.Items;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** /colony: every player command, and the admin tools under /colony admin. */
public final class ColonyCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = List.of("help", "start", "tasks", "create", "info", "list", "map", "claim", "unclaim", "invite", "join", "ask", "letin", "turnaway", "challenge", "leave", "kick", "members",
            "promote", "demote", "rename", "settings", "menu", "core", "book", "marker", "wand", "rope", "baton", "banner", "disband", "quests",
            "citizens", "citizen", "jobs", "assign", "home", "needs", "settler", "traveller", "exile", "release",
            "buildings", "building", "validate", "upgrade", "construction",
            "resources", "storage", "economy", "trade", "production", "deposit", "withdraw", "shop", "policies",
            "military", "recruit", "army", "orders", "squad", "mobilize",
            "diplomacy", "declarewar", "peace", "surrender", "prison", "events", "stats",
            "research", "raid", "requests", "refugees", "milestones", "today", "chatter", "christmas", "civ", "audit", "resettle", "admin");

    private final ColonySMP plugin;
    private final Info info;
    private final AdminCommand admin;

    /** The /colony admin tools, which /colonyadmin also runs. */
    AdminCommand admin() {
        return admin;
    }

    public ColonyCommand(ColonySMP plugin) {
        this.plugin = plugin;
        this.info = new Info(plugin);
        this.admin = new AdminCommand(plugin, info);
    }

    public Info info() {
        return info;
    }

    /** Gives an item, dropping what doesn't fit. */
    public static void give(Player p, ItemStack it) {
        for (ItemStack left : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), left);
    }

    public void sendInfo(CommandSender to, Colony c) {
        Text.raw(to, "<aqua><bold>" + Text.esc(c.name) + "</bold></aqua>");
        for (String l : info.colony(c)) Text.raw(to, " " + l);
    }

    // ───────────── dispatch ─────────────

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        try {
            if (sub.equals("admin")) {
                admin.run(sender, rest);
                return true;
            }
            if (sub.equals("civ") || sub.equals("world")) {
                plugin.civCommand().run(sender, rest);
                return true;
            }
            if (sub.equals("audit")) {
                plugin.civCommand().audit(sender, rest);
                return true;
            }
            if (sub.equals("help")) {
                if (sender instanceof Player hp && rest.length == 0 && plugin.settings().feature("onboarding")) new com.colonysmp.gui.HelpMenu(plugin, hp).open();
                else help(sender, rest);
                return true;
            }
            if (sub.equals("list")) {
                list(sender);
                return true;
            }
            if (!(sender instanceof Player p)) {
                Text.send(sender, "Players only (try /colony list or /colony admin).");
                return true;
            }
            if (!p.hasPermission("colonysmp.play")) {
                Text.send(p, "<red>You don't have permission.");
                return true;
            }
            run(p, sub, rest);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "/colony " + String.join(" ", args) + " failed", ex);
            Text.send(sender, "<red>Something went wrong: " + Text.esc(String.valueOf(ex.getMessage())));
        }
        return true;
    }

    private void run(Player p, String sub, String[] a) {
        Colony c = plugin.colonies().of(p);
        switch (sub) {
            case "create" -> create(p, a);
            case "info" -> {
                if (a.length == 0 && c != null && c.core != null && plugin.settings().feature("onboarding")) {
                    new TownHallMenu(plugin, p, c).open();
                    return;
                }
                Colony t = a.length > 0 ? plugin.colonies().byName(String.join(" ", a)) : c != null ? c : plugin.colonies().at(p.getLocation());
                if (t == null) {
                    if (plugin.settings().feature("starter-camp")) com.colonysmp.util.Messages.send(p, "camp.how");
                    else Text.send(p, "You don't belong to a colony. Start one with <white>/colony create <name></white>.");
                    return;
                }
                sendInfo(p, t);
            }
            case "map" -> {
                for (String l : plugin.territory().map(p)) Text.raw(p, l);
            }
            case "claim", "unclaim" -> {
                if (need(p, c)) return;
                String err = sub.equals("claim") ? plugin.territory().claim(p, c, p.getLocation().getChunk()) : plugin.territory().unclaim(p, c, p.getLocation().getChunk());
                if (err != null) Text.send(p, "<red>" + err);
            }
            case "invite" -> invite(p, c, a);
            case "join" -> join(p, a);
            case "challenge", "challenges" -> {
                for (String line : plugin.challenges().describe(c)) Text.raw(p, line);
            }
            case "ask" -> {
                Colony t = a.length == 0 ? null : plugin.colonies().byName(String.join(" ", a));
                String err = plugin.friends().ask(p, t);
                if (err != null) Text.send(p, "<red>" + Text.esc(err));
            }
            case "letin", "turnaway" -> {
                if (need(p, c)) return;
                if (sub.equals("letin")) {
                    Role as = a.length > 1 ? Role.parse(a[1]) : Role.RESIDENT;
                    if (as == null || !as.member || as == Role.OWNER) {
                        Text.send(p, "<red>Let them in as resident, manager or co_owner.");
                        return;
                    }
                    String why = mayGrant(p, c, as);
                    if (why != null) Text.send(p, "<red>" + why);
                    else plugin.friends().letIn(p, c, a.length == 0 ? null : a[0], as);
                }
                else plugin.friends().turnAway(p, c, a.length == 0 ? null : a[0]);
            }
            case "leave" -> leave(p, c);
            case "kick" -> kick(p, c, a);
            case "members" -> members(p, c);
            case "promote", "demote" -> promote(p, c, a, sub.equals("promote"));
            case "rename" -> rename(p, c, a);
            case "settings" -> settings(p, c, a);
            case "menu" -> {
                if (!need(p, c)) new TownHallMenu(plugin, p, c).open();
            }
            case "core" -> {
                if (need(p, c)) return;
                if (c.core != null) {
                    Text.send(p, "<red>" + Text.esc(c.name) + " already has its Town Hall.");
                    return;
                }
                if (!plugin.access().check(p, c, Perm.SETTINGS)) return;
                give(p, plugin.items().core(c.id, c.name));
                Text.send(p, "Here is the <aqua>Town Hall Core</aqua> of " + Text.esc(c.name) + ". Place it where the colony should stand.");
            }
            case "book" -> {
                // /colony book <type>: turns the book to a building type (and hands one over if needed)
                if (a.length > 0) {
                    BuildingType t = BuildingType.parse(String.join("_", a));
                    if (t == null) {
                        Text.send(p, "<red>No building type called " + Text.esc(String.join(" ", a)) + ". Try /colony help buildings.");
                        return;
                    }
                    plugin.blueprints().select(p, t);
                    if (!p.getInventory().contains(plugin.items().book().getType())) items(p, c, sub);
                    Text.send(p, "<yellow>Plan:</yellow> <white>" + t.display + "</white>. Shift + right-click inside it to register it.");
                } else items(p, c, sub);
            }
            case "marker", "wand", "rope", "baton", "banner" -> items(p, c, sub);
            case "disband" -> disband(p, c);
            case "quests" -> quests(p, c, a);
            case "tasks" -> quests(p, c, a.length == 0 ? a : new String[]{a[0].equalsIgnoreCase("off") || a[0].equalsIgnoreCase("hide") ? "hide" : "show"});
            case "start" -> {
                String err = plugin.camps().start(p, e -> {
                    if (e != null && !e.equals("offline")) Text.send(p, "<red>" + e);
                });
                if (err != null) Text.send(p, "<red>" + err);
            }
            case "citizens" -> {
                if (!need(p, c)) new CitizensMenu(plugin, p, c, null).open();
            }
            case "citizen" -> citizen(p, c, a);
            case "jobs" -> jobs(p, c);
            case "assign" -> assign(p, c, a);
            case "home" -> home(p, c, a);
            case "needs" -> needs(p, c, a);
            case "settler" -> {
                if (need(p, c) || !plugin.access().check(p, c, Perm.MANAGE_CITIZENS)) return;
                String err = plugin.sim().population().recruit(p, c);
                if (err != null) Text.send(p, "<red>" + err);
                else Text.send(p, "<green>A settler arrives from the region.");
            }
            case "traveller", "traveler" -> {
                if (need(p, c)) return;
                String err = a.length > 0 && a[0].equalsIgnoreCase("dismiss") ? plugin.travelers().dismiss(c, p) : plugin.travelers().recruit(c, p);
                if (err != null) Text.send(p, "<red>" + err);
            }
            case "exile" -> exile(p, c, a);
            case "release" -> release(p, c, a);
            case "buildings" -> {
                if (!need(p, c)) new BuildingsMenu(plugin, p, c).open();
            }
            case "building" -> building(p, c, a);
            case "validate" -> validate(p, c, a);
            case "upgrade" -> upgrade(p, c, a);
            case "construction" -> construction(p, c, a);
            case "resources" -> {
                if (need(p, c)) return;
                Category only = null;
                if (a.length > 0) {
                    for (Category cat : Category.values()) if (cat.name().equalsIgnoreCase(a[0]) || cat.display.equalsIgnoreCase(a[0])) only = cat;
                }
                Text.raw(p, "<aqua><bold>Resources of " + Text.esc(c.name));
                for (String l : info.resources(c, only)) Text.raw(p, " " + l);
            }
            case "storage" -> storage(p, c, a);
            case "economy" -> {
                if (need(p, c)) return;
                if (a.length == 0) {
                    new EconomyMenu(plugin, p, c).open();
                    return;
                }
                for (String l : info.economy(c)) Text.raw(p, " " + l);
            }
            case "trade" -> trade(p, c, a);
            case "production" -> {
                if (need(p, c)) return;
                Text.raw(p, "<aqua><bold>Production in " + Text.esc(c.name));
                for (String l : info.production(c)) Text.raw(p, " " + l);
            }
            case "deposit", "withdraw" -> money(p, c, a, sub.equals("deposit"));
            case "shop" -> {
                Colony t = a.length > 0 ? plugin.colonies().byName(String.join(" ", a)) : plugin.colonies().at(p.getLocation());
                if (t == null) t = c;
                if (t == null) {
                    Text.send(p, "Stand in a colony (or name one) to shop at its market.");
                    return;
                }
                new ShopMenu(plugin, p, t).open();
            }
            case "policies" -> policies(p, c, a);
            case "military" -> {
                if (need(p, c)) return;
                if (a.length == 0) new MilitaryMenu(plugin, p, c).open();
                else for (String l : info.military(c)) Text.raw(p, " " + l);
            }
            case "recruit" -> recruit(p, c, a);
            case "resettle" -> {
                if (need(p, c)) return;
                if (!plugin.access().can(p, c, Perm.MANAGE_CITIZENS)) {
                    Text.send(p, "<red>You may not call settlers for " + Text.esc(c.name) + ".");
                    return;
                }
                String err = plugin.sim().population().resettle(p, c);
                if (err != null) Text.send(p, "<red>" + err);
            }
            case "army" -> army(p, c);
            case "orders" -> orders(p, c, a);
            case "squad" -> squad(p, c, a);
            case "mobilize", "mobilise" -> {
                if (need(p, c)) return;
                String err = plugin.military().mobilize(p, c);
                if (err != null) Text.send(p, "<red>" + err);
            }
            case "diplomacy" -> diplomacy(p, c, a);
            case "declarewar" -> declare(p, c, a);
            case "peace" -> peace(p, c, a);
            case "surrender" -> {
                if (need(p, c)) return;
                String err = plugin.wars().surrender(p, c);
                if (err != null) Text.send(p, "<red>" + err);
            }
            case "prison" -> prison(p, c, a);
            case "events" -> events(p, c, a);
            case "stats" -> stats(p, c);
            case "research" -> research(p, c, a);
            case "raid", "raids" -> raid(p, c, a);
            case "requests", "request" -> requests(p, c, a);
            case "refugees" -> {
                if (need(p, c)) return;
                String err = plugin.happenings().refugees(p, c, a.length == 0 || !a[0].equalsIgnoreCase("refuse"));
                if (err != null) Text.send(p, "<red>" + err);
            }
            case "milestones" -> {
                if (need(p, c)) return;
                plugin.milestones().check(c);
                for (String l : plugin.milestones().list(c)) Text.raw(p, l);
            }
            case "chatter" -> {
                if (need(p, c)) return;
                boolean off = a.length > 0 ? a[0].equalsIgnoreCase("off") : !c.chatterMuted.contains(p.getUniqueId());
                if (off) c.chatterMuted.add(p.getUniqueId());
                else c.chatterMuted.remove(p.getUniqueId());
                plugin.requestSave();
                Text.send(p, off ? "The citizens will keep their thoughts to themselves. <gray>(/colony chatter on to hear them again)"
                        : "The citizens will speak to you as you pass. <gray>(/colony chatter off to stop them)");
            }
            case "christmas", "xmas" -> {
                if (need(p, c)) return;
                Text.raw(p, "<green><bold>Christmas</bold> <gray>- " + Text.esc(c.name));
                for (String l : plugin.christmas().describe(c)) Text.raw(p, " " + l);
                String err = plugin.christmas().freeSapling(p, c);
                if (err != null && plugin.christmas().season()) Text.send(p, "<red>" + err);
            }
            case "today" -> {
                if (need(p, c)) return;
                Text.raw(p, "<aqua><bold>Today in " + Text.esc(c.name));
                for (String l : plugin.happenings().describe(c)) Text.raw(p, " " + l);
                if (plugin.christmas().season()) {
                    Text.raw(p, plugin.christmas().decorated(c) ? " <green>🎄 Christmas: the tree stands, and everyone is merrier for it."
                            : " <green>🎄 It's Christmas, but the colony has no tree: <click:run_command:'/colony christmas'>[Christmas]</click>");
                }
                for (String l : plugin.raids().describe(c).subList(0, 1)) Text.raw(p, " " + l);
                int open = c.requests.size();
                if (open > 0) Text.raw(p, " <yellow>" + open + " citizen" + (open == 1 ? " is" : "s are") + " asking for something: <click:run_command:'/colony requests'>[Requests]</click>");
                if (c.researching != null) {
                    Text.raw(p, " <light_purple>Researching " + c.researching.display + ": " + Math.round(plugin.sim().academy().progress(c) * 100) + "%");
                }
            }
            default -> Text.send(p, "Unknown subcommand. Try <white>/colony help</white>.");
        }
    }

    // ───────────── helpers ─────────────

    /** True (and a message) if the player has no colony. */
    private boolean need(Player p, Colony c) {
        if (c != null) return false;
        Text.send(p, "You don't belong to a colony. Start one with <white>/colony create <name></white> or join one with <white>/colony join <name></white>.");
        return true;
    }

    private static String join(String[] a, int from) {
        return from >= a.length ? "" : String.join(" ", Arrays.copyOfRange(a, from, a.length));
    }

    private Citizen findCitizen(Colony c, String name) {
        if (name == null || name.isEmpty()) return null;
        Citizen partial = null;
        for (Citizen ct : c.citizens.values()) {
            if (ct.name.equalsIgnoreCase(name)) return ct;
            if (partial == null && ct.name.toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))) partial = ct;
        }
        return partial;
    }

    /** A citizen named by the words from a[from] on (names have spaces), and how many words the name took. */
    private record Named(Citizen citizen, int words) {
    }

    private Named leading(Colony c, String[] a, int from) {
        for (int n = a.length - from; n >= 1; n--) {
            String name = String.join(" ", Arrays.copyOfRange(a, from, from + n));
            for (Citizen ct : c.citizens.values()) if (ct.name.equalsIgnoreCase(name)) return new Named(ct, n);
        }
        return new Named(from < a.length ? findCitizen(c, a[from]) : null, 1);
    }

    private Building findBuilding(Colony c, String s) {
        try {
            return c.building(Integer.parseInt(s.replace("#", "")));
        } catch (NumberFormatException e) {
            for (Building b : c.buildings.values()) if (b.label().equalsIgnoreCase(s) || b.name != null && b.name.equalsIgnoreCase(s)) return b;
            return null;
        }
    }

    private static long number(String s, long def) {
        try {
            return Long.parseLong(s.replace(",", ""));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String good(String s) {
        String k = Goods.key(s.replace(' ', '_'));
        return Goods.known(k) ? k : null;
    }

    // ───────────── membership ─────────────

    private void create(Player p, String[] a) {
        if (a.length == 0) {
            Text.send(p, "Usage: <white>/colony create <name>");
            return;
        }
        if (plugin.colonies().coloniesOf(p.getUniqueId()).size() >= plugin.settings().maxColoniesPerPlayer && !plugin.isBypassing(p)) {
            Text.send(p, "<red>You already belong to " + plugin.settings().maxColoniesPerPlayer + " colony(ies). Leave one first.");
            return;
        }
        if (!plugin.settings().worldAllowed(p.getWorld().getName())) {
            Text.send(p, "<red>Colonies can't be founded in this world.");
            return;
        }
        String name = String.join(" ", a);
        String err = plugin.colonies().checkName(name, null);
        if (err != null) {
            Text.send(p, "<red>" + err);
            return;
        }
        boolean grant = plugin.founders().mayGrant(p.getUniqueId());
        Colony c = plugin.colonies().create(p, name);
        give(p, plugin.items().core(c.id, c.name));
        give(p, plugin.items().book());
        give(p, plugin.items().marker());
        Text.send(p, "<green>The colony of <white>" + Text.esc(c.name) + "</white> is created.</green> Place the <aqua>Town Hall Core</aqua> (in your inventory) where it should stand: "
                + "the land around it becomes yours and your first settlers arrive. Place the <aqua>Building Marker</aqua> inside a building you've built to register it "
                + "(it works out what the building is); the <white>Blueprint Book</white> orders new ones.");
        if (!grant && plugin.settings().startingTreasury > 0) {
            com.colonysmp.util.Messages.send(p, "founding.no-grant");
        }
        plugin.quests().refresh(c);
    }

    private void invite(Player p, Colony c, String[] a) {
        if (need(p, c) || !plugin.access().check(p, c, Perm.MEMBERS)) return;
        if (a.length == 0) {
            Text.send(p, "Usage: <white>/colony invite <player> [resident|manager|co_owner]");
            return;
        }
        Role role = Role.RESIDENT;
        if (a.length > 1) {
            role = Role.parse(a[1]);
            if (role == null || !role.member) {
                Text.send(p, "<red>Members can be Resident, Manager or Co-owner.");
                return;
            }
            if (role == Role.OWNER) {
                Text.send(p, "<red>You can't invite someone as owner. <gray>Invite them, then hand the colony over with <white>/colony promote " + Text.esc(a[0]) + " owner</white>.");
                return;
            }
            String why = mayGrant(p, c, role);
            if (why != null) {
                Text.send(p, "<red>" + why);
                return;
            }
        }
        OfflinePlayer known = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (known != null && c.isMember(known.getUniqueId())) {
            if (a.length > 1 && c.role(known.getUniqueId()) != role) {
                // "invite them as manager" when they're already in: what's meant is a promotion
                promote(p, c, new String[] {a[0], a[1]}, true);
            } else {
                Text.send(p, Text.esc(known.getName()) + " is already a member <gray>(" + c.role(known.getUniqueId()).display.toLowerCase(Locale.ROOT)
                        + "). To change what they may do: <white>/colony promote " + Text.esc(known.getName()) + " manager");
            }
            return;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) {
            Text.send(p, "<red>That player isn't online.");
            return;
        }
        c.invites.add(t.getUniqueId());
        if (role == Role.RESIDENT) c.inviteRoles.remove(t.getUniqueId());
        else c.inviteRoles.put(t.getUniqueId(), role);
        String as = role == Role.RESIDENT ? "" : " as " + Text.a(role.display.toLowerCase(Locale.ROOT));
        Text.send(p, "Invited <white>" + Text.esc(t.getName()) + "</white>" + as + ".");
        Text.send(t, Text.esc(p.getName()) + " invited you to <aqua>" + Text.esc(c.name) + "</aqua>" + as + ". <click:run_command:'/colony join " + Text.esc(c.name)
                + "'><green>[Join]</green></click>");
    }

    /** Why this player may not hand out this role here (null = they may). Below their own rank, and they need Roles. */
    String mayGrant(Player p, Colony c, Role role) {
        if (role == Role.RESIDENT || plugin.isBypassing(p)) return null;
        Role mine = c.role(p.getUniqueId());
        if (mine == null || !c.can(mine, Perm.ROLES)) return "Only members who may promote (the owner, by default) can hand out roles. Invite them as a resident instead.";
        if (role.rank() >= mine.rank()) return "You can only hand out roles below your own (" + mine.display.toLowerCase(Locale.ROOT) + ").";
        return null;
    }

    private void join(Player p, String[] a) {
        Colony c = a.length == 0 ? null : plugin.colonies().byName(String.join(" ", a));
        if (c == null) {
            Text.send(p, "Usage: <white>/colony join <colony></white> (you need an invitation).");
            return;
        }
        if (!c.invites.remove(p.getUniqueId()) && !plugin.isBypassing(p)) {
            Text.send(p, "<red>You haven't been invited to " + Text.esc(c.name) + ".");
            return;
        }
        if (plugin.colonies().coloniesOf(p.getUniqueId()).size() >= plugin.settings().maxColoniesPerPlayer && !plugin.isBypassing(p)) {
            Text.send(p, "<red>You already belong to as many colonies as you may.");
            return;
        }
        Role as = c.inviteRoles.remove(p.getUniqueId());
        if (as == null || !as.member || as == Role.OWNER) as = Role.RESIDENT;
        c.members.put(p.getUniqueId(), as);
        c.log(ColonyEvent.Type.COLONY, plugin.sim().day(c), p.getName() + " joined the colony" + (as == Role.RESIDENT ? "" : " as " + as.display) + ".");
        if (as != Role.RESIDENT) Text.send(p, "You joined as " + Text.a("<white>" + as.display.toLowerCase(Locale.ROOT)) + "</white>.");
        for (Player m : c.onlineMembers()) Text.send(m, "<green>" + Text.esc(p.getName()) + " joined " + Text.esc(c.name) + ".");
        plugin.quests().refresh(c);
        plugin.requestSave();
        if (plugin.friends().on()) plugin.friends().welcome(p, c, plugin.getServer().getOfflinePlayer(c.owner).getName());
    }

    private void leave(Player p, Colony c) {
        if (need(p, c)) return;
        if (c.owner.equals(p.getUniqueId())) {
            Text.send(p, "<red>The owner can't leave. Hand the colony over (/colony promote <player> owner) or disband it (/colony disband).");
            return;
        }
        c.members.remove(p.getUniqueId());
        c.log(ColonyEvent.Type.COLONY, plugin.sim().day(c), p.getName() + " left the colony.");
        Text.send(p, "You left " + Text.esc(c.name) + ".");
        plugin.quests().refresh(c);
        plugin.requestSave();
    }

    private void kick(Player p, Colony c, String[] a) {
        if (need(p, c) || !plugin.access().check(p, c, Perm.MEMBERS)) return;
        OfflinePlayer t = a.length == 0 ? null : Bukkit.getOfflinePlayerIfCached(a[0]);
        if (t == null || !c.isMember(t.getUniqueId())) {
            Text.send(p, "<red>No member called that.");
            return;
        }
        if (!plugin.access().outranks(p, c, t.getUniqueId())) {
            Text.send(p, "<red>You can only remove members below your own rank.");
            return;
        }
        c.members.remove(t.getUniqueId());
        c.log(ColonyEvent.Type.COLONY, plugin.sim().day(c), p.getName() + " removed " + t.getName() + " from the colony.");
        Text.send(p, "Removed " + Text.esc(t.getName()) + ".");
        plugin.requestSave();
    }

    private void members(Player p, Colony c) {
        if (need(p, c)) return;
        Text.raw(p, "<aqua><bold>Members of " + Text.esc(c.name));
        for (Map.Entry<UUID, Role> e : c.members.entrySet()) {
            boolean online = Bukkit.getPlayer(e.getKey()) != null;
            Text.raw(p, " " + (online ? "<green>●" : "<dark_gray>●") + " <white>" + Text.esc(Info.name(e.getKey())) + " <gray>" + e.getValue().display);
        }
    }

    private void promote(Player p, Colony c, String[] a, boolean up) {
        if (need(p, c)) return;
        if (a.length == 0) {
            Text.send(p, "Usage: <white>/colony " + (up ? "promote" : "demote") + " <player> [role]");
            return;
        }
        OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(a[0]);
        if (t == null || !c.isMember(t.getUniqueId())) {
            Text.send(p, "<red>No member called that.");
            return;
        }
        Role cur = c.role(t.getUniqueId());
        Role to = a.length > 1 ? Role.parse(a[1]) : null;
        if (to == null) {
            Role[] order = {Role.RESIDENT, Role.MANAGER, Role.CO_OWNER};
            int i = Math.max(0, Arrays.asList(order).indexOf(cur));
            to = up ? order[Math.min(order.length - 1, i + 1)] : order[Math.max(0, i - 1)];
        }
        if (!to.member) {
            Text.send(p, "<red>Members can be Owner, Co-owner, Manager or Resident.");
            return;
        }
        if (to == Role.OWNER) {
            if (!plugin.access().owner(p, c)) {
                Text.send(p, "<red>Only the owner can hand over the colony.");
                return;
            }
            c.members.put(c.owner, Role.CO_OWNER);
            c.owner = t.getUniqueId();
            c.members.put(t.getUniqueId(), Role.OWNER);
        } else {
            if (!plugin.access().outranks(p, c, t.getUniqueId()) || c.role(p.getUniqueId()) != null && to.rank() >= c.role(p.getUniqueId()).rank() && !plugin.isBypassing(p)) {
                Text.send(p, "<red>You can only change the role of members below you, to a role below yours.");
                return;
            }
            c.members.put(t.getUniqueId(), to);
        }
        c.log(ColonyEvent.Type.COLONY, plugin.sim().day(c), p.getName() + " made " + t.getName() + " " + to.display + ".");
        Text.send(p, Text.esc(t.getName()) + " is now <white>" + to.display + "</white>.");
        plugin.requestSave();
    }

    private void rename(Player p, Colony c, String[] a) {
        if (need(p, c) || !plugin.access().check(p, c, Perm.SETTINGS)) return;
        String name = String.join(" ", a);
        String err = plugin.colonies().checkName(name, c);
        if (err != null) {
            Text.send(p, "<red>" + err);
            return;
        }
        c.log(ColonyEvent.Type.COLONY, plugin.sim().day(c), p.getName() + " renamed the colony from " + c.name + " to " + name.trim() + ".");
        c.name = name.trim();
        Text.send(p, "The colony is now called <white>" + Text.esc(c.name) + "</white>.");
        plugin.requestSave();
    }

    /** /colony settings perm <role> <permission> <on|off> -- what each role may do here. */
    private void settings(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length >= 4 && a[0].equalsIgnoreCase("perm")) {
            if (!plugin.access().check(p, c, Perm.ROLES)) return;
            Role r = Role.parse(a[1]);
            Perm perm;
            try {
                perm = Perm.valueOf(a[2].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                perm = null;
            }
            if (r == null || !r.editable() || perm == null) {
                Text.send(p, "<red>Editable roles: manager, resident, ally, visitor. Permissions: " + Arrays.toString(Perm.values()).toLowerCase(Locale.ROOT));
                return;
            }
            var set = java.util.EnumSet.noneOf(Perm.class);
            set.addAll(c.perms(r));
            if (a[3].equalsIgnoreCase("on") || a[3].equalsIgnoreCase("true")) set.add(perm);
            else set.remove(perm);
            c.perms.put(r, set);
            Text.send(p, r.display + "s " + (set.contains(perm) ? "<green>may</green>" : "<red>may not</red>") + " " + perm.description.toLowerCase() + ".");
            plugin.requestSave();
            return;
        }
        Text.raw(p, "<aqua><bold>Permissions in " + Text.esc(c.name) + "</bold> <gray>(/colony settings perm <role> <permission> on|off)");
        for (Role r : Role.values()) {
            List<String> l = new ArrayList<>();
            for (Perm perm : c.perms(r)) l.add(perm.name().toLowerCase(Locale.ROOT));
            Text.raw(p, " <white>" + r.display + "</white><gray>: " + (l.isEmpty() ? "nothing" : String.join(", ", l)));
        }
    }

    private void items(Player p, Colony c, String which) {
        switch (which) {
            case "book" -> give(p, plugin.items().book());
            case "marker" -> give(p, plugin.items().marker());
            case "wand" -> give(p, plugin.items().wand());
            case "rope" -> {
                if (!plugin.isBypassing(p)) {
                    Text.send(p, "Rope is crafted from 3 string in a column (makes 2; see /colony help prisoners).");
                    return;
                }
                give(p, plugin.items().rope());
            }
            case "baton" -> {
                if (need(p, c) || !plugin.access().check(p, c, Perm.MILITARY)) return;
                give(p, plugin.items().baton());
            }
            default -> {
                if (!plugin.isBypassing(p)) {
                    Text.send(p, "The War Banner is crafted: red banner, iron sword, gold ingot.");
                    return;
                }
                give(p, plugin.items().warBanner());
            }
        }
    }

    private void disband(Player p, Colony c) {
        if (need(p, c)) return;
        if (!plugin.access().owner(p, c)) {
            Text.send(p, "<red>Only the owner can disband the colony.");
            return;
        }
        new ConfirmMenu(p, "<red>Disband " + Text.esc(c.name) + "?", List.of("<gray>Its citizens leave, its land is freed", "<gray>and its storage is lost. This can't be undone."),
                () -> plugin.colonies().delete(c, "disbanded by " + p.getName()), null).open();
    }

    private void quests(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length > 0 && (a[0].equalsIgnoreCase("hide") || a[0].equalsIgnoreCase("show"))) {
            plugin.quests().setHidden(c, p, a[0].equalsIgnoreCase("hide"));
            Text.send(p, "Quest bar " + (a[0].equalsIgnoreCase("hide") ? "hidden" : "shown") + ".");
            return;
        }
        QuestManager q = plugin.quests();
        Text.raw(p, "<gold><bold>First steps</bold> <gray>- " + Text.esc(c.name));
        for (int i = 1; i <= QuestManager.COUNT; i++) {
            String mark = c.questStage > i ? "<green>✔" : c.questStage == i ? "<yellow>➤" : "<dark_gray>✘";
            Text.raw(p, " " + mark + " <white>" + i + ". " + q.title(i) + " <gray>- " + q.task(i));
        }
    }

    // ───────────── citizens ─────────────

    private void citizen(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        Citizen ct = findCitizen(c, join(a, 0));
        if (ct == null) {
            Text.send(p, "<red>No citizen called that. (/colony citizens lists them)");
            return;
        }
        Text.raw(p, "<aqua><bold>" + Text.esc(ct.name) + "</bold></aqua>");
        for (String l : info.citizen(c, ct)) Text.raw(p, " " + l);
    }

    private void jobs(Player p, Colony c) {
        if (need(p, c)) return;
        Map<Job, Integer> count = new java.util.EnumMap<>(Job.class);
        for (Citizen ct : c.citizens.values()) if (ct.status.free()) count.merge(ct.job, 1, Integer::sum);
        Text.raw(p, "<aqua><bold>Jobs in " + Text.esc(c.name) + "</bold> <gray>(/colony assign <citizen> <job> [building])");
        List<String> l = new ArrayList<>();
        count.forEach((j, n) -> l.add("<white>" + n + "</white> " + j.display));
        Text.raw(p, " " + String.join("<gray>, ", l));
        var open = plugin.sim().jobs().openings(c);
        if (open.isEmpty()) Text.raw(p, " <gray>No open places. Build or upgrade workplaces for more jobs.");
        for (var o : open) Text.raw(p, " <green>" + o.free() + "</green> <gray>× " + o.job().display + " at <white>" + Text.esc(o.building().label()) + "</white>"
                + (o.job().education > 0 ? " <dark_gray>(education " + o.job().education + ")" : ""));
        if (!plugin.sim().jobs().administrated(c)) Text.raw(p, " <dark_gray>Tip: an Administrator at the Town Hall places the unemployed automatically.");
    }

    private void assign(Player p, Colony c, String[] a) {
        if (need(p, c) || !plugin.access().check(p, c, Perm.MANAGE_JOBS)) return;
        if (a.length < 2) {
            Text.send(p, "Usage: <white>/colony assign <citizen> <job> [building id]");
            return;
        }
        Named who = leading(c, a, 0);
        Citizen ct = who.citizen();
        int at0 = who.words();
        Job j = at0 < a.length ? Job.parse(a[at0]) : null;
        if (ct == null || j == null) {
            Text.send(p, "<red>" + (ct == null ? "No citizen called that." : "No such job. Jobs: " + Arrays.toString(Job.values()).toLowerCase(Locale.ROOT)));
            return;
        }
        if (j == Job.SOLDIER) {
            Text.send(p, "Use <white>/colony recruit " + ct.name + " <class></white> to make someone a soldier.");
            return;
        }
        Building b = a.length > at0 + 1 ? findBuilding(c, a[at0 + 1]) : null;
        if (a.length > at0 + 1 && b == null) {
            Text.send(p, "<red>No building with that id (see /colony buildings).");
            return;
        }
        String err = plugin.sim().jobs().assign(c, ct, j, b);
        if (err != null) Text.send(p, "<red>" + err);
        else {
            Building at = c.building(ct.workplace);
            Text.send(p, "<white>" + Text.esc(ct.name) + "</white> is now " + Text.a("<white>" + j.display) + "</white>" + (at != null ? " at " + Text.esc(at.label()) : "")
                    + (j.needsWorkplace() && at == null ? " <yellow>(no free place yet: they'll start when there is one)" : "") + ".");
        }
    }

    private void home(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        Named who = leading(c, a, 0);
        Citizen ct = who.citizen();
        if (ct == null) {
            Text.send(p, "Usage: <white>/colony home <citizen> [house id]");
            return;
        }
        if (a.length <= who.words()) {
            Building h = c.building(ct.home);
            Text.send(p, Text.esc(ct.name) + (h == null ? " has no home." : " lives in " + Text.esc(h.label()) + (ct.bed != null ? " (bed at " + ct.bed + ")" : "") + "."));
            return;
        }
        if (!plugin.access().check(p, c, Perm.MANAGE_CITIZENS)) return;
        Building h = findBuilding(c, a[who.words()]);
        if (h == null || h.type != BuildingType.HOUSE && h.type != BuildingType.BARRACKS || !h.valid) {
            Text.send(p, "<red>That isn't a working house.");
            return;
        }
        BlockPos free = null;
        for (BlockPos bed : h.beds) {
            boolean taken = false;
            for (Citizen o : c.citizens.values()) if (o != ct && bed.equals(o.bed)) taken = true;
            if (!taken) {
                free = bed;
                break;
            }
        }
        if (free == null) {
            Text.send(p, "<red>" + Text.esc(h.label()) + " has no free bed.");
            return;
        }
        ct.home = h.id;
        ct.bed = free;
        Text.send(p, Text.esc(ct.name) + " moves into " + Text.esc(h.label()) + ".");
        plugin.requestSave();
    }

    private void needs(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length > 0) {
            citizen(p, c, a);
            return;
        }
        double hunger = 0, energy = 0, health = 0, social = 0, mood = 0, housing = 0;
        int n = 0, employed = 0, adults = 0, ill = 0;
        for (Citizen ct : c.citizens.values()) {
            if (!ct.status.free()) continue;
            n++;
            hunger += ct.needs.satiety;
            energy += ct.needs.energy;
            health += ct.needs.healthFraction() * 100;
            social += ct.needs.social;
            mood += ct.needs.mood;
            housing += info.housing(c, ct);
            if (ct.ill() || ct.injured) ill++;
            if (plugin.sim().citizens().stage(ct) != com.colonysmp.model.LifeStage.CHILD) {
                adults++;
                if (ct.job.employed()) employed++;
            }
        }
        if (n == 0) {
            Text.send(p, "No citizens yet.");
            return;
        }
        Text.raw(p, "<aqua><bold>Needs in " + Text.esc(c.name) + "</bold> <gray>(averages of " + n + " citizens)");
        Text.raw(p, " <gray>Hunger " + Text.meter(hunger / n) + " <gray>Energy " + Text.meter(energy / n) + " <gray>Health " + Text.meter(health / n));
        Text.raw(p, " <gray>Housing " + Text.meter(housing / n) + " <gray>Social " + Text.meter(social / n) + " <gray>Mood " + Text.meter(mood / n));
        Text.raw(p, " <gray>Safety " + Text.meter(plugin.wars().fighting(c) ? 10 : plugin.military().threatened(c) ? 45 : 90) + " <gray>Employment "
                + Text.meter(adults == 0 ? 100 : employed * 100.0 / adults) + " <gray>(" + ill + " ill or hurt)");
    }

    private void exile(Player p, Colony c, String[] a) {
        if (need(p, c) || !plugin.access().check(p, c, Perm.MANAGE_CITIZENS)) return;
        Citizen ct = findCitizen(c, join(a, 0));
        if (ct == null || !ct.status.free()) {
            Text.send(p, "<red>No free citizen called that.");
            return;
        }
        new ConfirmMenu(p, "<red>Exile " + Text.esc(ct.name) + "?", List.of("<gray>They leave the colony for good.", "<gray>Their family and friends won't be pleased."), () -> {
            c.addStability(-2);
            plugin.sim().population().leave(c, ct, "was exiled by " + p.getName());
        }, null).open();
    }

    private void release(Player p, Colony c, String[] a) {
        if (need(p, c) || !plugin.access().check(p, c, Perm.MANAGE_CITIZENS)) return;
        Citizen ct = findCitizen(c, join(a, 0));
        if (ct == null) {
            Text.send(p, "<red>No prisoner called that.");
            return;
        }
        String err = plugin.prison().release(p, c, ct);
        if (err != null) Text.send(p, "<red>" + err);
        else Text.send(p, Text.esc(ct.name) + " was released.");
    }

    // ───────────── buildings ─────────────

    private void building(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length == 0) {
            Text.send(p, "Usage: <white>/colony building <id> | info <id> | register <type> | crop <id> <crop|auto> | herd <id> <size> | recipe <id> <recipe> on|off | name <id> <name> | remove <id>");
            return;
        }
        String op = a[0].toLowerCase(Locale.ROOT);
        if (op.equals("register")) {
            // the Blueprint Book's Shift + Right-Click as a command: the room you stand in, or the block you look at
            BuildingType t = a.length > 1 ? BuildingType.parse(String.join("_", Arrays.copyOfRange(a, 1, a.length))) : plugin.blueprints().type(p);
            if (t == null) {
                Text.send(p, "<red>Usage: /colony building register <type> (stand inside it, or look at it).");
                return;
            }
            plugin.blueprints().select(p, t);
            org.bukkit.block.Block look = p.getTargetBlockExact(6);
            plugin.blueprints().register(p, look != null ? look : p.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN), org.bukkit.block.BlockFace.UP);
            return;
        }
        if (!List.of("info", "crop", "herd", "recipe", "name", "remove").contains(op)) {
            a = new String[]{"info", a[0]};
            op = "info";
        }
        Building b = a.length > 1 ? findBuilding(c, a[1]) : null;
        if (b == null) {
            Text.send(p, "<red>No building with that id (see /colony buildings).");
            return;
        }
        switch (op) {
            case "info" -> {
                Text.raw(p, "<aqua><bold>" + Text.esc(b.label()) + "</bold></aqua>");
                for (String l : info.building(c, b)) Text.raw(p, " " + l);
                List<Recipe> rs = Recipes.of(b.type.station());
                if (!rs.isEmpty()) {
                    List<String> l = new ArrayList<>();
                    for (Recipe r : rs) l.add((b.disabled.contains(r.id) ? "<red>" : "<green>") + r.id);
                    Text.raw(p, " <gray>Recipes: " + String.join("<gray>, ", l));
                }
            }
            case "crop" -> {
                if (!plugin.access().check(p, c, Perm.MANAGE_BUILDINGS)) return;
                if (b.type != BuildingType.FARM || a.length < 3) {
                    Text.send(p, "<red>Usage: /colony building crop <farm id> <wheat|carrots|potatoes|beetroots|auto>");
                    return;
                }
                String crop = a[2].toLowerCase(Locale.ROOT);
                if (!List.of("wheat", "carrots", "potatoes", "beetroots", "auto").contains(crop)) {
                    Text.send(p, "<red>Crops: wheat, carrots, potatoes, beetroots, or auto.");
                    return;
                }
                b.crop = crop.equals("auto") ? null : crop;
                Text.send(p, Text.esc(b.label()) + " will grow " + (b.crop == null ? "whatever is needed" : b.crop) + ".");
            }
            case "herd" -> {
                if (!plugin.access().check(p, c, Perm.MANAGE_BUILDINGS)) return;
                if (b.type != BuildingType.ANIMAL_PEN && b.type != BuildingType.STABLES || a.length < 3) {
                    Text.send(p, "<red>Usage: /colony building herd <pen id> <animals of each kind to keep>");
                    return;
                }
                b.herdTarget = (int) Math.max(2, Math.min(64, number(a[2], 8)));
                Text.send(p, Text.esc(b.label()) + " keeps up to " + b.herdTarget + " of each kind; the rest go to slaughter.");
            }
            case "recipe" -> {
                if (!plugin.access().check(p, c, Perm.MANAGE_BUILDINGS)) return;
                Recipe r = a.length > 2 ? Recipes.get(a[2]) : null;
                if (r == null || !r.station.equals(b.type.station())) {
                    Text.send(p, "<red>That workplace has no such recipe.");
                    return;
                }
                boolean on = a.length < 4 || !a[3].equalsIgnoreCase("off");
                if (on) b.disabled.remove(r.id);
                else b.disabled.add(r.id);
                Text.send(p, r.name + " is " + (on ? "<green>allowed" : "<red>not made") + "</green> at " + Text.esc(b.label()) + ".");
            }
            case "name" -> {
                if (!plugin.access().check(p, c, Perm.MANAGE_BUILDINGS)) return;
                String n = join(a, 2);
                b.name = n.isBlank() ? null : n.substring(0, Math.min(24, n.length()));
                Text.send(p, "Renamed to " + Text.esc(b.label()) + ".");
            }
            default -> {
                if (!plugin.access().check(p, c, Perm.MANAGE_BUILDINGS)) return;
                if (b.type == BuildingType.TOWN_HALL) {
                    Text.send(p, "<red>The Town Hall can't be removed.");
                    return;
                }
                Building fb = b;
                new ConfirmMenu(p, "<red>Unregister " + Text.esc(b.label()) + "?", List.of("<gray>Its workers lose their workplace and its stock goes to storage.",
                        "<gray>The blocks stay where they are."), () -> plugin.buildings().remove(c, fb, "removed by " + p.getName()), null).open();
            }
        }
        plugin.requestSave();
    }

    private void validate(Player p, Colony c, String[] a) {
        if (need(p, c) || !plugin.access().check(p, c, Perm.MANAGE_BUILDINGS)) return;
        List<Building> list = new ArrayList<>();
        if (a.length == 0 || a[0].equalsIgnoreCase("all")) list.addAll(c.buildings.values());
        else {
            Building b = findBuilding(c, a[0]);
            if (b != null) list.add(b);
        }
        if (list.isEmpty()) {
            Text.send(p, "<red>No such building.");
            return;
        }
        int ok = 0;
        for (Building b : list) {
            String problem = plugin.buildings().validate(c, b);
            if (problem == null) {
                ok++;
                b.valid = true;
                b.problem = null;
                b.invalidDays = 0;
            } else {
                b.valid = false;
                b.problem = problem;
                Text.raw(p, " <red>" + Text.esc(b.label()) + ": " + Text.esc(problem));
            }
        }
        plugin.buildings().changed(c);
        Text.send(p, ok + " of " + list.size() + " building(s) pass inspection." + (ok < list.size() ? " <gray>Broken ones stop working until fixed." : ""));
    }

    private void upgrade(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        Building b = a.length == 0 ? null : findBuilding(c, a[0]);
        if (b == null) {
            Text.send(p, "Usage: <white>/colony upgrade <building id></white> (see /colony buildings).");
            return;
        }
        String err = plugin.buildings().upgrade(p, c, b);
        if (err != null) Text.send(p, "<red>" + err);
    }

    private void construction(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length >= 1 && a[0].equalsIgnoreCase("order")) {
            // the Blueprint Book's Shift + Left-Click as a command: build at the ground you look at
            BuildingType t = a.length > 1 ? BuildingType.parse(String.join("_", Arrays.copyOfRange(a, 1, a.length))) : plugin.blueprints().type(p);
            if (t == null) {
                Text.send(p, "<red>Usage: /colony construction order <type> (look at the ground where it should stand).");
                return;
            }
            plugin.blueprints().select(p, t);
            plugin.blueprints().order(p);
            return;
        }
        if (a.length >= 2) {
            if (!plugin.access().check(p, c, Perm.MANAGE_BUILDINGS)) return;
            BuildOrder o = null;
            for (BuildOrder x : c.buildQueue) if (String.valueOf(x.id).equals(a[1].replace("#", ""))) o = x;
            if (o == null) {
                Text.send(p, "<red>No build order #" + Text.esc(a[1]) + ".");
                return;
            }
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "pause" -> o.state = BuildOrder.State.PAUSED;
                case "resume" -> o.state = o.step > 0 ? BuildOrder.State.UNDER_CONSTRUCTION : BuildOrder.State.PLANNED;
                case "cancel" -> c.buildQueue.remove(o);
                case "first", "top" -> {
                    c.buildQueue.remove(o);
                    c.buildQueue.add(0, o);
                }
                default -> {
                    Text.send(p, "<red>Use pause, resume, cancel or first.");
                    return;
                }
            }
            Text.send(p, "Build order #" + o.id + " " + a[0].toLowerCase(Locale.ROOT) + (a[0].toLowerCase(Locale.ROOT).endsWith("e") ? "d" : "ed") + ".");
            plugin.requestSave();
            return;
        }
        Text.raw(p, "<aqua><bold>Construction in " + Text.esc(c.name) + "</bold> <gray>(/colony construction order <type> · pause|resume|cancel|first <id>)");
        for (String l : info.construction(c)) Text.raw(p, " " + l);
        if (c.buildQueue.isEmpty()) Text.raw(p, " <gray>No orders. Order a building with the Blueprint Book (choose a plan, look at the ground, Shift + Left-Click),"
                + " or with /colony construction order <type>.");
    }

    // ───────────── storage and economy ─────────────

    private void storage(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length >= 2 && (a[0].equalsIgnoreCase("target") || a[0].equalsIgnoreCase("clear"))) {
            if (!plugin.access().check(p, c, Perm.POLICIES)) return;
            String g = good(a[1]);
            if (g == null) {
                Text.send(p, "<red>Unknown good: " + Text.esc(a[1]));
                return;
            }
            if (a[0].equalsIgnoreCase("clear")) {
                c.targets.remove(g);
                Text.send(p, "Cleared the stock target for " + Goods.get(g).name() + ".");
            } else {
                int min = (int) Math.max(0, number(a.length > 2 ? a[2] : "0", 0)), max = (int) Math.max(min, number(a.length > 3 ? a[3] : "0", 0));
                c.targets.put(g, new int[]{min, max});
                Text.send(p, Goods.get(g).name() + ": keep at least <white>" + min + "</white>" + (max > 0 ? ", produce up to <white>" + max + "</white>" : "")
                        + ". <gray>(Minimums aren't spent or sold; workshops stop at the maximum; auto-buy tops up to the minimum.)");
            }
            plugin.requestSave();
            return;
        }
        Text.raw(p, "<aqua><bold>Storage of " + Text.esc(c.name) + "</bold> <gray>(" + c.storage.usedSlots() + "/" + c.storage.capacity() + " slots, granary "
                + c.storage.usedGranarySlots() + "/" + c.storage.granaryCapacity() + ") <dark_gray>/colony storage target <good> <min> [max]");
        for (String l : info.resources(c, null)) Text.raw(p, " " + l);
        Text.raw(p, " <gray>Open the Town Hall chest to put goods in or take them out.");
    }

    private void trade(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length >= 2 && (a[0].equalsIgnoreCase("sell") || a[0].equalsIgnoreCase("buy"))) {
            String g = good(a[1]);
            if (g == null) {
                Text.send(p, "<red>Unknown good: " + Text.esc(a[1]));
                return;
            }
            long qty = a.length > 2 ? Math.max(1, number(a[2], 1)) : Goods.stack(g);
            long before = c.treasury.balance(), had = c.storage.get(g);
            boolean sell = a[0].equalsIgnoreCase("sell");
            String err = sell ? plugin.economy().sell(p, c, g, qty, false) : plugin.economy().buy(p, c, g, qty, false);
            if (err != null) Text.send(p, "<red>" + err);
            else {
                long moved = Math.abs(c.storage.get(g) - had);
                Text.send(p, (sell ? "Sold " : "Bought ") + moved + " " + Goods.get(g).name() + ": treasury " + Text.signed(c.treasury.balance() - before) + " <gray>worth."
                        + (moved < qty ? " <yellow>(" + (sell ? "the rest is kept back" : "storage had room for no more") + ")" : ""));
            }
            return;
        }
        if (a.length == 0) {
            new EconomyMenu(plugin, p, c).trade();
            return;
        }
        Text.send(p, "Usage: <white>/colony trade [sell|buy <good> [quantity]]");
    }

    private void money(Player p, Colony c, String[] a, boolean in) {
        if (need(p, c)) return;
        boolean all = in && a.length > 0 && a[0].equalsIgnoreCase("all");
        long amount = a.length == 0 || all ? 0 : number(a[0], 0);
        if (amount <= 0 && !all) {
            Text.send(p, "Usage: <white>/colony " + (in ? "deposit <worth|all>" : "withdraw <worth>") + "</white>. The treasury is valuables: iron, gold and copper"
                    + " (nuggets to blocks), emeralds, diamonds and netherite. A nugget is worth 1, an iron ingot 9, a gold ingot " + com.colonysmp.core.Treasury.worth("gold_ingot")
                    + ", an emerald " + com.colonysmp.core.Treasury.worth("emerald") + ", a diamond " + com.colonysmp.core.Treasury.worth("diamond") + ". You carry " + plugin.economy().money(p) + ".");
            return;
        }
        String err = in ? plugin.economy().deposit(p, c, amount) : plugin.economy().withdraw(p, c, amount);
        if (err != null) Text.send(p, "<red>" + err);
        else Text.send(p, (in ? "Deposited into" : "Withdrew from") + " the treasury. It now holds <gold>" + Text.value(c.treasury.balance()) + "</gold>.");
    }

    private void policies(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length == 0) {
            new PoliciesMenu(plugin, p, c).open();
            return;
        }
        if (a.length < 3 || !a[0].equalsIgnoreCase("set")) {
            Text.send(p, "Usage: <white>/colony policies set <policy> <value></white>, or /colony policies for the menu.");
            return;
        }
        if (!plugin.access().check(p, c, Perm.POLICIES)) return;
        String err = PoliciesMenu.set(plugin, c, a[1], a[2]);
        if (err != null) Text.send(p, "<red>" + err);
        else {
            c.log(ColonyEvent.Type.COLONY, plugin.sim().day(c), p.getName() + " set the " + a[1] + " policy to " + a[2] + ".");
            Text.send(p, "Policy changed.");
            plugin.requestSave();
        }
    }

    // ───────────── military ─────────────

    private void recruit(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length < 2) {
            Text.raw(p, "<aqua><bold>Military classes</bold> <gray>(/colony recruit <citizen> <class>)");
            for (MilitaryClass k : MilitaryClass.values()) {
                String missing = plugin.military().missingFor(c, k);
                Text.raw(p, " " + (missing == null ? "<green>" : "<yellow>") + k.display + " <dark_gray>- " + k.description + (missing == null ? "" : " <red>(needs " + missing + ")"));
            }
            return;
        }
        Named who = leading(c, a, 0);
        Citizen ct = who.citizen();
        MilitaryClass k = who.words() < a.length ? MilitaryClass.parse(join(a, who.words())) : null;
        if (ct == null || k == null) {
            Text.send(p, "<red>" + (ct == null ? "No citizen called that." : "No such class (see /colony recruit)."));
            return;
        }
        String r = plugin.military().recruit(p, c, ct, k);
        if (r == null) Text.send(p, "<green>" + Text.esc(ct.name) + " is now " + Text.a(k.display) + ".");
        else Text.send(p, r.startsWith("!") ? "<yellow>" + r.substring(1) : "<red>" + r);
    }

    private void army(Player p, Colony c) {
        if (need(p, c)) return;
        Text.raw(p, "<aqua><bold>The army of " + Text.esc(c.name));
        for (String l : info.military(c)) Text.raw(p, " " + l);
        for (Citizen ct : plugin.military().army(c)) {
            Squad q = c.squad(ct.squad);
            String missing = plugin.military().missingArms(ct);
            Text.raw(p, " <white>" + Text.esc(ct.name) + "</white> <gray>" + (ct.militaryClass == null ? "?" : ct.militaryClass.display) + " · trained " + Math.round(ct.training * 100)
                    + "% · morale " + Math.round(ct.morale) + (q != null ? " · " + Text.esc(q.name) : "") + (missing != null ? " <red>lacks " + missing : ""));
        }
    }

    private Squad findSquad(Colony c, String s) {
        try {
            Squad q = c.squad(Integer.parseInt(s.replace("#", "")));
            if (q != null) return q;
        } catch (NumberFormatException ignored) {
            // by name
        }
        for (Squad q : c.squads.values()) if (q.name.equalsIgnoreCase(s)) return q;
        return null;
    }

    private void orders(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length < 2) {
            Text.raw(p, "<aqua><bold>Orders</bold> <gray>(/colony orders <squad> <order> [here])");
            for (Squad.Order o : Squad.Order.values()) Text.raw(p, " <white>" + o.name().toLowerCase(Locale.ROOT) + "</white> <dark_gray>- " + o.description);
            return;
        }
        Squad q = findSquad(c, a[0]);
        Squad.Order o = Squad.order(a[1]);
        if (q == null || o == null) {
            Text.send(p, "<red>" + (q == null ? "No such squad (see /colony army)." : "No such order (see /colony orders)."));
            return;
        }
        boolean here = a.length > 2 && a[2].equalsIgnoreCase("here");
        String err = plugin.military().order(p, c, q, o, here ? p.getLocation() : null);
        if (err != null) Text.send(p, "<red>" + err);
        else Text.send(p, "<gold>" + Text.esc(q.name) + "</gold>: " + o.display + (here ? " here" : "") + ".");
    }

    private void squad(Player p, Colony c, String[] a) {
        if (need(p, c) || !plugin.access().check(p, c, Perm.MILITARY)) return;
        if (a.length == 0) {
            Text.send(p, "Usage: <white>/colony squad create <name> | delete <squad> | rename <squad> <name> | add <squad> <citizen> | remove <citizen> | formation <squad> <formation> | patrol <squad> add|clear | select <squad>");
            return;
        }
        String op = a[0].toLowerCase(Locale.ROOT);
        if (op.equals("create")) {
            Squad q = plugin.military().createSquad(c, join(a, 1));
            plugin.military().select(p, q);
            Text.send(p, "Created squad <white>" + Text.esc(q.name) + "</white> (#" + q.id + "). Add soldiers with /colony squad add " + q.id + " <citizen>, or the Command Baton.");
            return;
        }
        if (op.equals("remove")) {
            Citizen ct = findCitizen(c, join(a, 1));
            if (ct == null) {
                Text.send(p, "<red>No citizen called that.");
                return;
            }
            ct.squad = 0;
            Text.send(p, Text.esc(ct.name) + " left their squad.");
            plugin.requestSave();
            return;
        }
        Squad q = a.length > 1 ? findSquad(c, a[1]) : null;
        if (q == null) {
            Text.send(p, "<red>No such squad.");
            return;
        }
        switch (op) {
            case "delete" -> {
                plugin.military().deleteSquad(c, q);
                Text.send(p, "Disbanded " + Text.esc(q.name) + ".");
            }
            case "rename" -> {
                q.name = join(a, 2).isBlank() ? q.name : join(a, 2);
                Text.send(p, "Renamed to " + Text.esc(q.name) + ".");
            }
            case "add" -> {
                Citizen ct = findCitizen(c, join(a, 2));
                if (ct == null || ct.job != Job.SOLDIER) {
                    Text.send(p, "<red>No soldier called that.");
                    return;
                }
                ct.squad = q.id;
                Text.send(p, Text.esc(ct.name) + " joined " + Text.esc(q.name) + ".");
            }
            case "formation" -> {
                Squad.Formation f = a.length > 2 ? Policies.parse(Squad.Formation.class, a[2], null) : null;
                if (f == null) {
                    Text.send(p, "<red>Formations: line, column, wedge, square, loose.");
                    return;
                }
                q.formation = f;
                Text.send(p, Text.esc(q.name) + " forms a " + f.display.toLowerCase(Locale.ROOT) + ".");
            }
            case "patrol" -> {
                if (a.length > 2 && a[2].equalsIgnoreCase("clear")) {
                    q.patrol.clear();
                    Text.send(p, "Patrol route cleared.");
                } else {
                    q.patrol.add(BlockPos.of(p.getLocation()));
                    q.world = p.getWorld().getName();
                    Text.send(p, "Added a patrol point (" + q.patrol.size() + " in the route). Order them to patrol with /colony orders " + q.id + " patrol.");
                }
            }
            case "select" -> {
                plugin.military().select(p, q);
                Text.send(p, "Selected " + Text.esc(q.name) + " for the Command Baton.");
            }
            default -> Text.send(p, "<red>Unknown squad action.");
        }
        plugin.requestSave();
    }

    // ───────────── diplomacy and war ─────────────

    private Colony other(Player p, String name) {
        Colony o = plugin.colonies().byName(name);
        if (o == null) Text.send(p, "<red>No colony called " + Text.esc(name) + ".");
        return o;
    }

    private void diplomacy(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length == 0) {
            new DiplomacyMenu(plugin, p, c).open();
            return;
        }
        String op = a[0].toLowerCase(Locale.ROOT);
        String err = null;
        var dip = plugin.diplomacy();
        switch (op) {
            case "list", "relations" -> {
                Text.raw(p, "<aqua><bold>Relations of " + Text.esc(c.name));
                for (String l : info.diplomacy(c)) Text.raw(p, " " + l);
                return;
            }
            case "offers" -> {
                Text.raw(p, "<aqua><bold>Offers");
                for (Proposal x : dip.incoming(c)) {
                    Colony f = plugin.colonies().get(x.from);
                    Text.raw(p, " <gray>From <white>" + (f == null ? "?" : Text.esc(f.name)) + "</white>: " + Text.esc(dip.describe(x))
                            + " <click:run_command:'/colony diplomacy accept " + x.id + "'><green>[Accept]</green></click> <click:run_command:'/colony diplomacy decline " + x.id + "'><red>[Decline]</red></click>");
                }
                for (Proposal x : dip.outgoing(c)) {
                    Colony t = plugin.colonies().get(x.to);
                    Text.raw(p, " <gray>To <white>" + (t == null ? "?" : Text.esc(t.name)) + "</white>: " + Text.esc(dip.describe(x)) + " <click:run_command:'/colony diplomacy decline " + x.id
                            + "'><red>[Withdraw]</red></click>");
                }
                for (var k : dip.contractsOf(c)) {
                    Colony s = plugin.colonies().get(k.supplier), b = plugin.colonies().get(k.buyer);
                    Text.raw(p, " <gray>Contract: " + (s == null ? "?" : Text.esc(s.name)) + " → " + (b == null ? "?" : Text.esc(b.name)) + ": " + k.qty + " " + Goods.get(k.good).name()
                            + " every " + k.everyDays + " day(s) until day " + k.endDay + (k.lastProblem != null ? " <red>(" + Text.esc(k.lastProblem) + ")" : ""));
                }
                return;
            }
            case "accept", "decline" -> {
                Proposal x = a.length > 1 ? dip.proposal(a[1]) : null;
                if (x == null && a.length > 1) {
                    // by colony name: their latest offer to us
                    Colony from = plugin.colonies().byName(join(a, 1));
                    for (Proposal y : dip.incoming(c)) if (from != null && y.from.equals(from.id) && (x == null || y.expires > x.expires)) x = y;
                }
                if (x == null) {
                    Text.send(p, "<red>That offer is no longer there (see /colony diplomacy offers).");
                    return;
                }
                err = op.equals("accept") ? dip.accept(p, x) : dip.decline(p, x);
                if (err == null && op.equals("decline")) Text.send(p, "Declined.");
            }
            case "ally", "alliance" -> {
                Colony o = a.length > 1 ? other(p, join(a, 1)) : null;
                if (o == null) return;
                err = dip.propose(p, c, o, dip.newProposal(Proposal.Type.ALLIANCE, c, o));
                if (err == null) Text.send(p, "Alliance offered to " + Text.esc(o.name) + ".");
            }
            case "treaty" -> {
                Relation.Treaty t = a.length > 2 ? Relation.Treaty.parse(a[a.length - 1]) : null;
                Colony o = a.length > 2 ? other(p, join(Arrays.copyOf(a, a.length - 1), 1)) : null;
                if (o == null || t == null) {
                    if (t == null) Text.send(p, "Usage: <white>/colony diplomacy treaty <colony> <nonaggression|trade|defence|borders>");
                    return;
                }
                Proposal x = dip.newProposal(Proposal.Type.TREATY, c, o);
                x.treaty = t;
                err = dip.propose(p, c, o, x);
                if (err == null) Text.send(p, t.display + " offered to " + Text.esc(o.name) + ".");
            }
            case "cancel", "break" -> {
                if (a.length < 3) {
                    Text.send(p, "Usage: <white>/colony diplomacy cancel <colony> <alliance|treaty>");
                    return;
                }
                Colony o = other(p, join(Arrays.copyOf(a, a.length - 1), 1));
                if (o == null) return;
                boolean alliance = a[a.length - 1].equalsIgnoreCase("alliance");
                err = dip.cancel(p, c, o, alliance ? null : Relation.Treaty.parse(a[a.length - 1]), alliance);
                if (err == null) Text.send(p, "Done.");
            }
            case "gift" -> {
                if (a.length < 3) {
                    Text.send(p, "Usage: <white>/colony diplomacy gift <colony> <worth> | <good> <quantity>");
                    return;
                }
                Colony o = other(p, a[1]);
                if (o == null) return;
                String g = good(a[2]);
                err = g != null ? dip.gift(p, c, o, g, a.length > 3 ? number(a[3], 1) : 1, 0) : dip.gift(p, c, o, null, 0, number(a[2], 0));
                if (err == null) Text.send(p, "Gift sent to " + Text.esc(o.name) + ".");
            }
            case "trade" -> err = tradeOffer(p, c, a);
            case "contract" -> {
                if (a.length < 7) {
                    Text.send(p, "Usage: <white>/colony diplomacy contract <buyer colony> <good> <quantity> <every N days> <for N days> <price each delivery></white> (you supply them)");
                    return;
                }
                Colony o = other(p, a[1]);
                String g = good(a[2]);
                if (o == null || g == null) {
                    if (g == null) Text.send(p, "<red>Unknown good.");
                    return;
                }
                Proposal x = dip.newProposal(Proposal.Type.CONTRACT, c, o);
                x.good = g;
                x.qty = (int) Math.max(1, number(a[3], 1));
                x.everyDays = (int) Math.max(1, number(a[4], 1));
                x.days = (int) Math.max(1, number(a[5], 7));
                x.money = Math.max(0, number(a[6], 0));
                err = dip.propose(p, c, o, x);
                if (err == null) Text.send(p, "Supply contract offered to " + Text.esc(o.name) + ".");
            }
            case "calltoarms" -> {
                Colony o = a.length > 1 ? other(p, join(a, 1)) : null;
                if (o == null) return;
                err = dip.propose(p, c, o, dip.newProposal(Proposal.Type.JOIN_WAR, c, o));
                if (err == null) Text.send(p, "Called " + Text.esc(o.name) + " to arms.");
            }
            default -> {
                Text.send(p, "Usage: <white>/colony diplomacy [list|offers|accept|decline|ally|treaty|cancel|gift|trade|contract|calltoarms]");
                return;
            }
        }
        if (err != null) Text.send(p, "<red>" + err);
    }

    /** /colony diplomacy trade <colony> give <good> <qty> [want <good> <qty>] [money <n>] */
    private String tradeOffer(Player p, Colony c, String[] a) {
        if (a.length < 4) return "Usage: /colony diplomacy trade <colony> [give <good> <qty>] [want <good> <qty>] [pay <worth>] [ask <worth>]";
        Colony o = plugin.colonies().byName(a[1]);
        if (o == null) return "No colony called " + Text.esc(a[1]) + ".";
        Proposal x = plugin.diplomacy().newProposal(Proposal.Type.TRADE, c, o);
        for (int i = 2; i < a.length; i++) {
            String w = a[i].toLowerCase(Locale.ROOT);
            if ((w.equals("give") || w.equals("want")) && i + 2 < a.length) {
                String g = good(a[i + 1]);
                if (g == null) return "Unknown good: " + Text.esc(a[i + 1]);
                (w.equals("give") ? x.give : x.want).merge(g, Math.max(1, number(a[i + 2], 1)), Long::sum);
                i += 2;
            } else if ((w.equals("pay") || w.equals("ask")) && i + 1 < a.length) {
                x.money += (w.equals("pay") ? 1 : -1) * Math.max(0, number(a[i + 1], 0));
                i++;
            }
        }
        if (x.give.isEmpty() && x.want.isEmpty() && x.money == 0) return "Offer something.";
        String err = plugin.diplomacy().propose(p, c, o, x);
        if (err == null) Text.send(p, "Trade offered to " + Text.esc(o.name) + ".");
        return err;
    }

    private void declare(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length == 0) {
            Text.send(p, "Usage: <white>/colony declarewar <colony> [raid|siege]");
            return;
        }
        War.Goal goal = War.Goal.SIEGE;
        String[] names = a;
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        if (last.equals("raid") || last.equals("siege")) {
            goal = last.equals("raid") ? War.Goal.RAID : War.Goal.SIEGE;
            names = Arrays.copyOf(a, a.length - 1);
        }
        Colony target = other(p, String.join(" ", names));
        if (target == null) return;
        War.Goal g = goal;
        new ConfirmMenu(p, "<dark_red>Declare a " + goal.display.toLowerCase() + " on " + Text.esc(target.name) + "?",
                List.of("<gray>Costs " + Text.value(plugin.settings().declareCost) + " and sours relations badly.",
                        goal == War.Goal.SIEGE ? "<gray>You must raise a War Camp before the warm-up ends." : "<gray>A short raid: loot their storage, win on score."),
                () -> {
                    String err = plugin.wars().declare(p, c, target, g);
                    if (err != null) Text.send(p, "<red>" + err);
                }, null).open();
    }

    private void peace(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length == 0) {
            Text.send(p, "Usage: <white>/colony peace <colony> [truce days]");
            return;
        }
        int days = plugin.settings().truceDays;
        String[] names = a;
        if (a.length > 1 && a[a.length - 1].matches("\\d+")) {
            days = Integer.parseInt(a[a.length - 1]);
            names = Arrays.copyOf(a, a.length - 1);
        }
        Colony o = other(p, String.join(" ", names));
        if (o == null) return;
        Proposal x = plugin.diplomacy().newProposal(Proposal.Type.PEACE, c, o);
        x.truceDays = days;
        String err = plugin.diplomacy().propose(p, c, o, x);
        if (err != null) Text.send(p, "<red>" + err);
        else Text.send(p, "Peace offered to " + Text.esc(o.name) + ".");
    }

    private void prison(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length >= 2 && a[0].equalsIgnoreCase("release")) {
            release(p, c, Arrays.copyOfRange(a, 1, a.length));
            return;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("ransom")) {
            if (!plugin.access().check(p, c, Perm.DIPLOMACY)) return;
            Named who = leading(c, a, 1);
            Citizen ct = who.citizen();
            if (ct == null || ct.status != Status.PRISONER || ct.origin == null || plugin.colonies().get(ct.origin) == null) {
                Text.send(p, "<red>No prisoner from another colony called that.");
                return;
            }
            Colony home = plugin.colonies().get(ct.origin);
            Proposal x = plugin.diplomacy().newProposal(Proposal.Type.RANSOM, c, home);
            x.release.add(ct.id);
            int amountAt = 1 + who.words();
            x.money = -(a.length > amountAt ? Math.max(0, number(a[amountAt], 0)) : plugin.prison().ransom(ct));
            String err = plugin.diplomacy().propose(p, c, home, x);
            if (err != null) Text.send(p, "<red>" + err);
            else Text.send(p, "Ransom of " + Text.value(-x.money) + " asked of " + Text.esc(home.name) + " for " + Text.esc(ct.name) + ".");
            return;
        }
        if (a.length >= 3 && a[0].equalsIgnoreCase("exchange")) {
            if (!plugin.access().check(p, c, Perm.DIPLOMACY)) return;
            Named first = leading(c, a, 1);
            Citizen ours = first.citizen();
            if (ours == null || ours.status != Status.PRISONER || ours.origin == null || plugin.colonies().get(ours.origin) == null) {
                Text.send(p, "<red>No prisoner from another colony called " + Text.esc(a[1]) + ".");
                return;
            }
            Colony home = plugin.colonies().get(ours.origin);
            Citizen theirs = leading(home, a, 1 + first.words()).citizen();
            if (theirs == null || theirs.status != Status.PRISONER || !c.id.equals(theirs.origin)) {
                Text.send(p, "<red>" + Text.esc(home.name) + " holds no prisoner of yours called " + Text.esc(join(a, 1 + first.words())) + ".");
                return;
            }
            Proposal x = plugin.diplomacy().newProposal(Proposal.Type.PRISONER_EXCHANGE, c, home);
            x.release.add(ours.id);
            x.demand.add(theirs.id);
            String err = plugin.diplomacy().propose(p, c, home, x);
            if (err != null) Text.send(p, "<red>" + err);
            else Text.send(p, "Exchange offered to " + Text.esc(home.name) + ".");
            return;
        }
        Text.raw(p, "<aqua><bold>Prisoners of " + Text.esc(c.name) + "</bold> <gray>(/colony prison release|ransom|exchange)");
        List<Citizen> list = plugin.prison().prisoners(c);
        if (list.isEmpty()) Text.raw(p, " <gray>None.");
        for (Citizen ct : list) {
            Text.raw(p, " <white>" + Text.esc(ct.name) + "</white> <gray>" + ct.status.display + (ct.origin == null ? " (sentence until day " + ct.sentenceUntil + ")"
                    : " · resistance " + Math.round(ct.resistance) + "% · ransom " + Text.value(plugin.prison().ransom(ct))));
        }
    }

    private void events(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        ColonyEvent.Type type = null;
        int page = 1;
        for (String s : a) {
            if (s.matches("\\d+")) page = Integer.parseInt(s);
            else type = ColonyEvent.Type.parse(s.toUpperCase(Locale.ROOT));
        }
        List<ColonyEvent> list = new ArrayList<>();
        for (ColonyEvent e : c.events) if (type == null || e.type() == type) list.add(e);
        int per = 10, from = (page - 1) * per;
        Text.raw(p, "<aqua><bold>History of " + Text.esc(c.name) + "</bold> <gray>(page " + page + ", /colony events [type] [page])");
        for (int i = from; i < Math.min(list.size(), from + per); i++) {
            ColonyEvent e = list.get(i);
            Text.raw(p, " <dark_gray>Day " + e.day() + "</dark_gray> " + e.type().color + e.type().display + "<gray>: " + Text.esc(e.message()));
        }
        if (list.isEmpty()) Text.raw(p, " <gray>Nothing yet.");
    }

    // ───────────── research, raids, requests ─────────────

    private void research(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        var academy = plugin.sim().academy();
        if (a.length == 0) {
            new ResearchMenu(plugin, p, c).open();
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                int level = academy.universityLevel(c), staff = academy.researchers(c);
                Text.raw(p, "<light_purple><bold>Research</bold> <gray>- University level " + level + ", " + staff + " researcher" + (staff == 1 ? "" : "s")
                        + (c.researching == null ? "" : " · working on <white>" + c.researching.display + "</white> (" + Math.round(academy.progress(c) * 100) + "%)"));
                for (Research r : Research.values()) {
                    boolean known = c.research.contains(r);
                    String why = known || c.researching == r ? null : academy.blocked(c, r);
                    String state = known ? "<green>✔" : c.researching == r ? "<light_purple>…" : why == null ? "<white>○" : "<dark_gray>✘";
                    Text.raw(p, " " + state + " <white>" + r.display + "</white> <dark_gray>(" + r.branch.display + ")</dark_gray> <gray>" + r.description
                            + (known || c.researching == r ? "" : " <dark_gray>Costs " + academy.costText(r) + "." + (why == null ? "" : " <red>" + Text.esc(why))));
                }
            }
            case "start", "begin" -> {
                Research r = Research.parse(join(a, 1));
                if (r == null) {
                    Text.send(p, "<red>No research called " + Text.esc(join(a, 1)) + ". See /colony research list.");
                    return;
                }
                String err = academy.start(p, c, r);
                if (err != null) Text.send(p, "<red>" + err);
            }
            case "abandon", "cancel" -> {
                String err = academy.abandon(p, c);
                Text.send(p, err != null ? "<red>" + err : "<yellow>The researchers put the project aside.");
            }
            default -> Text.send(p, "Usage: /colony research [list | start <project> | abandon]");
        }
    }

    private void raid(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length > 0 && a[0].equalsIgnoreCase("tribute")) {
            String err = plugin.raids().tribute(p, c);
            if (err != null) Text.send(p, "<red>" + err);
            return;
        }
        Text.raw(p, "<red><bold>Raids</bold> <gray>- " + Text.esc(c.name));
        for (String l : plugin.raids().describe(c)) Text.raw(p, " " + l);
    }

    private void requests(Player p, Colony c, String[] a) {
        if (need(p, c)) return;
        if (a.length >= 2 && (a[0].equalsIgnoreCase("deliver") || a[0].equalsIgnoreCase("give"))) {
            Request r;
            try {
                r = c.request(Integer.parseInt(a[1]));
            } catch (NumberFormatException e) {
                r = null;
            }
            String err = plugin.requests().deliver(p, c, r);
            if (err != null) Text.send(p, "<red>" + err);
            return;
        }
        Text.raw(p, "<yellow><bold>Requests</bold> <gray>- what the people of " + Text.esc(c.name) + " are asking for");
        for (String l : plugin.requests().list(c)) Text.raw(p, l);
    }

    private void stats(Player p, Colony c) {
        if (need(p, c)) return;
        Text.raw(p, "<aqua><bold>Recent days in " + Text.esc(c.name));
        for (Colony.DayStats d : c.stats) {
            Text.raw(p, " <dark_gray>Day " + d.day() + "</dark_gray> <gray>pop <white>" + d.population() + "</white> food <white>" + Text.num(d.foodDays()) + "d</white> mood <white>"
                    + Math.round(d.mood()) + "</white> stability <white>" + Math.round(d.stability()) + "</white> made <white>" + d.produced() + "</white> treasury <white>"
                    + d.balance() + "</white> (" + Text.signed(d.income() - d.expenses()) + "<gray>)");
        }
        if (c.stats.isEmpty()) Text.raw(p, " <gray>The first day hasn't ended yet.");
    }

    // ───────────── help and lists ─────────────

    private void list(CommandSender s) {
        Text.raw(s, "<aqua><bold>Colonies</bold>");
        for (Colony c : plugin.colonies().list()) {
            Text.raw(s, " <white>" + Text.esc(c.name) + "</white> <gray>· " + Text.esc(Info.name(c.owner)) + " · " + c.population() + " citizens · level " + c.level() + " · " + c.territory.size() + " chunks");
        }
        if (plugin.colonies().list().isEmpty()) Text.raw(s, " <gray>None yet. Start one with /colony create <name>.");
    }

    private void help(CommandSender s, String[] a) {
        String topic = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        Text.raw(s, "<aqua><bold>ColonySMP</bold> <gray>- /colony help [colony|citizens|buildings|economy|military|diplomacy|prisoners|events|admin]");
        List<String> l = switch (topic) {
            case "citizens" -> List.of("citizens · citizen <name> · jobs · assign <citizen> <job> [building] · home <citizen> [house] · needs [citizen]",
                    "settler (recruit from the region) · traveller recruit|dismiss · exile <citizen> · resettle (an empty colony, free once a week)",
                    "Right-click a citizen for their menu. Children study; adults work; an Administrator fills empty jobs.");
            case "buildings" -> List.of("buildings · building <id> · building register <type> · building crop|herd|recipe|name|remove ... · validate [id|all] · upgrade <id> · construction",
                    "Build rooms and fields by hand, then place a Building Marker inside (/colony marker): it works out what the building is",
                    "and registers it, and you can break it again afterwards. Or Shift + Right-Click inside with the Blueprint Book,",
                    "or pick a plan in the book, look at the ground and Shift + Left-Click to order your builders to build it",
                    "(or /colony construction order <type>, facing the way you look).",
                    "Right-click with the book to turn its pages, or jump to a plan with /colony book <type>.");
            case "economy" -> List.of("resources [category] · storage [target <good> <min> [max]] · economy · production · policies",
                    "trade [sell|buy <good> <qty>] (needs a staffed Trading Post) · deposit <worth|all> · withdraw <worth> · shop [colony]");
            case "military" -> List.of("military · recruit <citizen> <class> · army · squad create|add|formation|patrol ... · orders <squad> <order> [here]",
                    "mobilize · baton (Command Baton: right-click a block to rally, shift-right-click for the next order, left-click an enemy to focus)");
            case "diplomacy" -> List.of("diplomacy [list|offers|accept|decline|ally|treaty|cancel|gift|trade|contract|calltoarms]",
                    "declarewar <colony> [raid|siege] · peace <colony> [truce days] · surrender");
            case "prisoners" -> List.of("Downed enemies can be bound with Rope (3 string in a column) and led into a Prison.",
                    "prison · prison release <name> · prison ransom <name> [price] · prison exchange <ours> <theirs>");
            case "events", "research", "raids", "requests" -> List.of("today (what's going on) · requests [deliver <n>] · research [list|start <project>|abandon] · raid [tribute] · refugees accept|refuse · milestones",
                    "chatter [on|off] (citizens talking to you) · christmas (the season, the tree, a free sapling)",
                    "Citizens ask you for things: bring them (right-click the citizen holding the goods) and they pay you and work harder.",
                    "Researchers at a University work through the project you choose; each one changes the colony for good.",
                    "Raiders come at nightfall once the colony has grown: scouts warn you that morning. Barbarians can be paid off.",
                    "Festivals, merchants' caravans, refugees, lucky strikes and storms come and go: /colony today shows what's on.");
            case "admin" -> List.of("admin reload|save|bypass|give|delete|tp|inspect|citizen|building|reservations|production|sim|perf|db|stuck|events",
                    "admin simulate <colony> <hours> · setstock <colony> <good> <qty> · treasury <colony> set|add <n> · addcitizen <colony> [job] · endwar · shield · traveller · repair",
                    "admin raid <colony> [barbarians|undead] [size] · event <colony> <kind> · research <colony> <project|all> · request <colony>");
            default -> List.of("create <name> · info [colony] · list · map · claim · unclaim · menu · core · book [type] · wand · baton · disband · quests",
                    "invite <player> · join <colony> · leave · kick <player> · members · promote|demote <player> [role] · rename <name> · settings",
                    "today · requests · research · raid · milestones · events [type] [page] · stats · and the topics above.");
        };
        for (String line : l) Text.raw(s, " <gray>" + line);
    }

    // ───────────── tab completion ─────────────

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT)) && (!s.equals("admin") && !s.equals("audit") || sender.hasPermission("colonysmp.admin"))) out.add(s);
            return out;
        }
        if (args[0].equalsIgnoreCase("civ")) return plugin.civCommand().complete(Arrays.copyOfRange(args, 1, args.length));
        if (args[0].equalsIgnoreCase("audit")) return args.length == 2 ? List.of("player", "transactions", "flags", "resolve") : List.of();
        if (args[0].equalsIgnoreCase("admin")) return admin.complete(sender, Arrays.copyOfRange(args, 1, args.length));
        Colony c = sender instanceof Player p ? plugin.colonies().of(p) : null;
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        String sub = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        switch (sub) {
            case "info", "join", "declarewar", "peace", "shop" -> {
                for (Colony o : plugin.colonies().list()) options.add(o.name);
                if (sub.equals("declarewar") && args.length == 3) options = List.of("raid", "siege");
            }
            case "citizen", "exile", "release", "home", "needs" -> {
                if (c != null && args.length == 2) for (Citizen ct : c.citizens.values()) options.add(ct.name);
            }
            case "assign" -> {
                if (c != null && args.length == 2) for (Citizen ct : c.citizens.values()) options.add(ct.name);
                if (args.length == 3) for (Job j : Job.values()) options.add(j.name().toLowerCase(Locale.ROOT));
            }
            case "recruit" -> {
                if (c != null && args.length == 2) for (Citizen ct : c.citizens.values()) if (ct.status.free()) options.add(ct.name);
                if (args.length == 3) for (MilitaryClass k : MilitaryClass.values()) options.add(k.name().toLowerCase(Locale.ROOT));
            }
            case "orders" -> {
                if (c != null && args.length == 2) for (Squad q : c.squads.values()) options.add(String.valueOf(q.id));
                if (args.length == 3) for (Squad.Order o : Squad.Order.values()) options.add(o.name().toLowerCase(Locale.ROOT));
                if (args.length == 4) options.add("here");
            }
            case "squad" -> {
                if (args.length == 2) options = List.of("create", "delete", "rename", "add", "remove", "formation", "patrol", "select");
            }
            case "trade" -> {
                if (args.length == 2) options = List.of("sell", "buy");
                if (args.length == 3 && c != null) options.addAll(c.storage.contents().keySet());
            }
            case "storage" -> {
                if (args.length == 2) options = List.of("target", "clear");
                if (args.length == 3 && c != null) options.addAll(c.storage.contents().keySet());
            }
            case "building" -> {
                if (args.length == 2) options = List.of("info", "register", "crop", "herd", "recipe", "name", "remove");
                if (args.length == 3 && args[1].equalsIgnoreCase("register")) for (BuildingType t : BuildingType.values()) options.add(t.name().toLowerCase(Locale.ROOT));
                else if (args.length == 3 && c != null) for (Building b : c.buildings.values()) options.add(String.valueOf(b.id));
            }
            case "book" -> {
                if (args.length == 2) for (BuildingType t : BuildingType.values()) options.add(t.name().toLowerCase(Locale.ROOT));
            }
            case "upgrade", "validate" -> {
                if (c != null && args.length == 2) for (Building b : c.buildings.values()) options.add(String.valueOf(b.id));
            }
            case "construction" -> {
                if (args.length == 2) options = List.of("order", "pause", "resume", "cancel", "first");
                if (args.length == 3 && args[1].equalsIgnoreCase("order")) for (BuildingType t : BuildingType.values()) if (Blueprint.of(t) != null) options.add(t.name().toLowerCase(Locale.ROOT));
            }
            case "diplomacy" -> {
                if (args.length == 2) options = List.of("list", "offers", "accept", "decline", "ally", "treaty", "cancel", "gift", "trade", "contract", "calltoarms");
                else for (Colony o : plugin.colonies().list()) options.add(o.name);
            }
            case "prison" -> {
                if (args.length == 2) options = List.of("release", "ransom", "exchange");
                else if (c != null) for (Citizen ct : plugin.prison().prisoners(c)) options.add(ct.name);
            }
            case "policies" -> {
                if (args.length == 2) options = List.of("set");
                if (args.length == 3) options = PoliciesMenu.NAMES;
            }
            case "promote", "demote", "kick", "invite", "letin" -> {
                if (args.length == 2) for (Player o : Bukkit.getOnlinePlayers()) options.add(o.getName());
                if (args.length == 3 && !args[0].equalsIgnoreCase("kick")) {
                    for (Role r : Role.values()) {
                        if (r.member && (r != Role.OWNER || args[0].equalsIgnoreCase("promote"))) options.add(r.name().toLowerCase(Locale.ROOT));
                    }
                }
            }
            case "traveller", "traveler" -> options = List.of("recruit", "dismiss");
            case "research" -> {
                if (args.length == 2) options = List.of("list", "start", "abandon");
                if (args.length == 3 && args[1].equalsIgnoreCase("start")) for (Research r : Research.values()) options.add(r.name().toLowerCase(Locale.ROOT));
            }
            case "raid", "raids" -> options = List.of("tribute");
            case "chatter" -> options = List.of("on", "off");
            case "refugees" -> options = List.of("accept", "refuse");
            case "requests", "request" -> {
                if (args.length == 2) options = List.of("deliver");
                if (args.length == 3 && c != null) for (Request r : c.requests) options.add(String.valueOf(r.id()));
            }
            case "resources" -> {
                for (Category cat : Category.values()) options.add(cat.name().toLowerCase(Locale.ROOT));
            }
            case "help" -> options = List.of("colony", "citizens", "buildings", "economy", "military", "diplomacy", "prisoners", "events", "admin");
            default -> {
            }
        }
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(last)) out.add(o);
        return out;
    }
}
