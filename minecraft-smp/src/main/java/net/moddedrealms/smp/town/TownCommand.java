package net.moddedrealms.smp.town;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.moddedrealms.smp.SmpPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** /mrtown (admin), and the player commands /rep /bail /surrender /police /wanted /tip /bounty /bounties /bribe /service. */
public final class TownCommand implements TabExecutor {
    private final TownManager t;

    public TownCommand(TownManager t) {
        this.t = t;
    }

    private static boolean say(CommandSender s, String mm) {
        s.sendMessage(SmpPlugin.mm(mm));
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "rep" -> {
                if (args.length > 0 && sender.hasPermission("mrsmp.admin")) {
                    Player p = Bukkit.getPlayerExact(args[0]);
                    if (p == null) return say(sender, "<red>Not online.");
                    return record(sender, p);
                }
                if (sender instanceof Player p) return record(sender, p);
                return true;
            }
            case "bail" -> {
                if (!(sender instanceof Player p)) return true;
                TownData d = t.data(p);
                if (!d.jailed()) return say(p, "<gray>You're not in jail.");
                double cost = t.jailOf(d).bail(d);
                if (!t.money.pay(p, cost)) return say(p, "<red>Bail is " + Money.fmt(cost) + " and you can't afford it. <gray>Sit tight: " + TownManager.fmtTime(d.jailLeft) + " to go.");
                t.release(p, "Bail paid (" + Money.fmt(cost) + ").");
                return true;
            }
            case "surrender" -> {
                if (!(sender instanceof Player p)) return true;
                if (t.data(p).stars <= 0) return say(p, "<gray>You're not wanted.");
                if (!t.surrender(p)) say(p, "<gray>There's no guard close enough to give yourself up to.");
                return true;
            }
            case "police" -> {
                if (!(sender instanceof Player p)) return true;
                String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                switch (sub) {
                    case "join" -> t.police().join(p);
                    case "quit", "resign" -> t.police().quit(p);
                    case "duty" -> t.police().duty(p);
                    case "radar" -> t.police().radar(p);
                    case "cuff" -> {
                        Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                        if (target == null) return say(p, "<yellow>/police cuff <player>  <gray>(or sneak + right-click them)");
                        if (!t.police().cuff(p, target)) say(p, "<gray>Only guards on duty can cuff people.");
                    }
                    default -> t.police().info(p);
                }
                return true;
            }
            case "tip" -> {
                if (sender instanceof Player p) t.bounties().tip(p, args.length > 0 ? args[0] : null);
                return true;
            }
            case "bounty" -> {
                if (sender instanceof Player p) t.bounties().add(p, args.length > 0 ? args[0] : null, args.length > 1 ? args[1] : null);
                return true;
            }
            case "bounties" -> {
                t.bounties().list(sender);
                return true;
            }
            case "bribe" -> {
                if (sender instanceof Player p) t.officers().bribe(p);
                return true;
            }
            case "service" -> {
                if (sender instanceof Player p) t.service().start(p);
                return true;
            }
            case "casino" -> {
                if (!(sender instanceof Player p)) return true;
                String g = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                switch (g) {
                    case "blackjack" -> t.games().blackjack(p);
                    case "roulette" -> t.games().roulette(p);
                    case "dice" -> t.games().dice(p, new String[0]);
                    default -> say(p, "<gold>The Golden Rat: <gray>slots on the west wall, blackjack and roulette with the dealers, dice with the gamblers or <white>/dice <player> <stake></white>.");
                }
                return true;
            }
            case "dice" -> {
                if (sender instanceof Player p) t.games().dice(p, args);
                return true;
            }
            case "heist" -> {
                if (sender instanceof Player p) t.heists().command(p, args);
                return true;
            }
            case "ticket" -> {
                if (sender instanceof Player p) t.railway().ticketOffice(p);
                return true;
            }
            case "herald" -> {
                if (sender instanceof Player p) t.herald().read(p);
                return true;
            }
            case "trial" -> {
                if (sender instanceof Player p) t.court().command(p, args);
                return true;
            }
            case "loan" -> {
                if (sender instanceof Player p) t.loans().command(p, args);
                return true;
            }
            case "census" -> {
                t.residents().census(sender);
                return true;
            }
            case "wanted" -> {
                List<Player> w = t.wantedOnline();
                if (w.isEmpty()) return say(sender, "<green>Nobody is wanted right now.");
                say(sender, "<red><bold>Wanted:");
                for (Player p : w) {
                    TownData d = t.data(p);
                    say(sender, " " + TownManager.stars(d.stars) + " <white>" + p.getName() + " <gray>" + d.reason);
                }
                return true;
            }
            default -> {
                return admin(sender, args);
            }
        }
    }

    private boolean record(CommandSender to, Player p) {
        TownData d = t.data(p);
        TownManager.Tier tier = t.tier(d.rep);
        say(to, "<gold><bold>" + p.getName() + "'s standing</bold>");
        say(to, " <gray>Reputation: " + tier.label + " <gray>(" + d.rep + ")" + (d.cop ? "  <blue>[Town Watch " + t.police().rank(d) + ", " + d.copArrests + " arrests]" : ""));
        if (!t.tierPerk(tier).isEmpty()) say(to, " <dark_gray>" + t.tierPerk(tier));
        say(to, " <gray>Wanted: " + (d.stars > 0 ? TownManager.stars(d.stars) + " <gray>" + d.reason + " <gold>bounty " + Money.fmt(t.bounties().of(d)) : "<green>no"));
        if (d.caseOpen) say(to, " <dark_red>The Inquisition has an open case on you <gray>(" + d.caseReason + ")");
        if (d.inService()) say(to, " <gray>Community service: <white>" + d.serviceLeft + "</white> pieces of litter left");
        Gangs.Gang gang = t.gangs().of(p);
        say(to, " <gray>Gang: " + (gang == null ? "<white>none" : gang.label() + " <white>" + gang.name));
        say(to, " <gray>Street: " + t.npcGangs().standings(p));
        if (d.jailed()) say(to, " <gray>Jail: <white>" + TownManager.fmtTime(d.jailLeft) + "</white> left");
        say(to, " <gray>Arrests: <white>" + d.arrests);
        return true;
    }

    private boolean admin(CommandSender s, String[] a) {
        if (!s.hasPermission("mrsmp.admin")) return say(s, "<red>You need mrsmp.admin for that.");
        String sub = a.length == 0 ? "help" : a[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "status" -> {
                    Map<String, Integer> roles = new TreeMap<>();
                    for (Walker w : t.walkers()) roles.merge(w.role.name().toLowerCase(), 1, Integer::sum);
                    int jailed = 0;
                    for (Player p : Bukkit.getOnlinePlayers()) if (t.data(p).jailed()) jailed++;
                    say(s, "<gold>Town " + (t.enabled ? "<green>on" : "<red>off") + "<gold>: centre " + (int) t.cx + " " + (int) t.cz + " in " + t.worldName
                            + ", Watch within " + (int) t.policeRadius + ", townsfolk within " + (int) t.townRadius + ".");
                    say(s, "<gray>NPCs walking: <white>" + (roles.isEmpty() ? "none" : roles) + "<gray>. Wanted online: <white>" + t.wantedOnline().size()
                            + "<gray>. In jail: <white>" + jailed + "<gray>. Jail " + (t.jail().ready() ? "<green>ready (" + t.jail().cells().size() + " cells)" : "<red>not set up")
                            + "<gray>. Economy: " + (t.money.ready() ? "<green>ok" : "<red>missing (Vault)"));
                }
                case "toggle" -> {
                    t.enabled = !t.enabled;
                    if (!t.enabled) t.despawnAllWalkers();
                    say(s, "<gold>Town " + (t.enabled ? "<green>enabled" : "<red>disabled until restart/reload") + "<gold>.");
                }
                case "clear" -> {
                    t.despawnAllWalkers();
                    say(s, "<green>All walking NPCs removed (they come back as players move around).");
                }
                case "spawn" -> {
                    if (!(s instanceof Player p) || a.length < 2) return say(s, "<yellow>/mrtown spawn <officer|merchant|gossip|traveller|thief|fence>");
                    Walker.Role r = Walker.Role.valueOf(a[1].toUpperCase(Locale.ROOT));
                    Walker w = switch (r) {
                        case OFFICER -> t.officers().spawn(p.getLocation());
                        case AGENT -> t.bureau().spawn(p.getLocation());
                        case BANDIT -> t.bandits().spawn(p.getLocation(), a.length > 2 ? Integer.parseInt(a[2]) : 2);
                        case GANGSTER -> t.npcGangs().spawn(p.getLocation(), a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "vipers");
                        case PATRON, COURIER -> throw new IllegalArgumentException("Those only appear in the gambling den or during a heist.");
                        default -> t.townsfolk().spawn(p.getLocation(), r);
                    };
                    say(s, "<green>Spawned " + w.name + "<green>.");
                }
                case "wanted" -> {
                    if (a.length < 3) return say(s, "<yellow>/mrtown wanted <player> <stars 0-5> [crime]");
                    Player p = Bukkit.getPlayerExact(a[1]);
                    if (p == null) return say(s, "<red>Not online.");
                    int n = Math.max(0, Math.min(5, Integer.parseInt(a[2])));
                    TownData d = t.data(p);
                    if (n == 0) {
                        t.clearWanted(d);
                        t.officers().forget(p);
                    } else {
                        Crime c = a.length > 3 ? Crime.valueOf(a[3].toUpperCase(Locale.ROOT).replace('-', '_')) : Crime.ASSAULT;
                        d.stars = n;
                        d.reason = c.label;
                        d.hostile = n >= t.hostileStars;
                        d.lastSeenByPolice = System.currentTimeMillis();
                        d.lastDecay = d.lastSeenByPolice;
                        t.officers().radio(p, null);
                        t.bureau().maybeOpen(p, d, c);
                    }
                    t.markDirty();
                    say(s, "<green>" + p.getName() + " is now wanted " + TownManager.stars(n) + "<green>.");
                }
                case "pardon" -> {
                    Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                    if (p == null) return say(s, "<yellow>/mrtown pardon <online player>");
                    t.clearWanted(t.data(p));
                    t.officers().forget(p);
                    if (t.data(p).jailed()) t.release(p, "You've been pardoned.");
                    say(s, "<green>Pardoned " + p.getName() + ".");
                }
                case "release" -> {
                    Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                    if (p == null || !t.data(p).jailed()) return say(s, "<yellow>/mrtown release <jailed online player>");
                    t.release(p, "You've been released early.");
                    say(s, "<green>Released " + p.getName() + ".");
                }
                case "arrest" -> {
                    Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                    if (p == null) return say(s, "<yellow>/mrtown arrest <online player>");
                    if (t.data(p).stars <= 0) t.data(p).stars = 1;
                    t.arrest(p, null, false);
                    say(s, "<green>Arrested " + p.getName() + ".");
                }
                case "rep" -> {
                    if (a.length < 2) return say(s, "<yellow>/mrtown rep <player> [set|add <n>]");
                    var off = Bukkit.getOfflinePlayer(a[1]);
                    TownData d = t.data(off.getUniqueId());
                    if (a.length >= 4) {
                        int n = Integer.parseInt(a[3]);
                        Player on = off.getPlayer();
                        if (a[2].equalsIgnoreCase("set")) n -= d.rep;
                        if (on != null) t.addRep(on, n);
                        else d.rep = Math.max(-1000, Math.min(1000, d.rep + n));
                        t.markDirty();
                    }
                    say(s, "<gold>" + a[1] + ": reputation " + d.rep + " (" + t.tier(d.rep).label + "<gold>), arrests " + d.arrests + (d.cop ? ", Watch " + t.police().rank(d) : ""));
                }
                case "jail" -> jail(s, a);
                case "bank" -> bank(s, a);
                case "casino" -> casino(s, a);
                case "medieval" -> {
                    String which = a.length > 1 ? a[1].toLowerCase() : "all";
                    say(s, "<green>" + t.medieval(which) + " " + t.repair(which));
                }
                case "repair" -> {
                    String which = a.length > 1 ? a[1].toLowerCase() : "all";
                    say(s, "<green>" + t.repair(which));
                }
                case "ai" -> {
                    String what = a.length > 1 ? a[1].toLowerCase() : "status";
                    if (what.equals("key")) {
                        // the key comes from a book and quill in the hand: typed in a command it would land in the logs
                        if (!(s instanceof Player p)) return say(s, "<gray>Do this in game: paste the key into a book and quill, hold it, then /mrtown ai key.");
                        var hand = p.getInventory().getItemInMainHand();
                        if (hand.getType() != org.bukkit.Material.WRITABLE_BOOK || !(hand.getItemMeta() instanceof org.bukkit.inventory.meta.BookMeta bm) || !bm.hasPages()) {
                            return say(p, "<yellow>Paste the OpenAI key into a book and quill (first page), hold it, then run /mrtown ai key. <gray>The book is wiped afterwards.");
                        }
                        String key = String.join("", bm.getPages());
                        boolean ok = t.ai().setKey(key);
                        p.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(org.bukkit.Material.WRITABLE_BOOK));
                        if (!ok) return say(p, "<red>That doesn't look like an OpenAI key (they start with sk-). The book was wiped anyway.");
                        say(p, "<green>Key saved to openai.key and the book wiped. <gray>Picking a model... check with /mrtown ai in a few seconds.");
                        return true;
                    }
                    if (what.equals("reload")) {
                        t.ai().reload();
                        say(s, "<green>Read openai.key and the AI settings again. <gray>(The model is picked in a moment.)");
                    } else if (what.equals("herald")) {
                        t.herald().print(t.recentNews(7 * 86_400_000L), s instanceof Player p ? p : null);
                        say(s, "<gray>Printing an edition of the Herald from the latest news...");
                        return true;
                    }
                    say(s, "<gold>AI: " + t.ai().status());
                    say(s, "<dark_gray>To set the key: paste it into a book and quill, hold it, /mrtown ai key. Or put it in plugins/ModdedrealmsSMP/openai.key and /mrtown ai reload. Never paste it in chat or the console.");
                }
                case "residents" -> {
                    if (a.length > 1 && a[1].equalsIgnoreCase("day")) {
                        t.residents().forceDay();
                        say(s, "<green>A new day for the residents (births, weddings, growing up, jobs).");
                    }
                    t.residents().census(s);
                }
                case "econ" -> {
                    t.businesses().cycle();
                    say(s, "<green>Ran an economy cycle.");
                }
                case "sabotage" -> {
                    Businesses.Biz b = a.length > 1 ? t.businesses().get(a[1]) : null;
                    if (b == null) return say(s, "<yellow>/mrtown sabotage <business id>  <gray>(ids are in businesses.yml)");
                    t.businesses().sabotage(b, null);
                    say(s, "<green>Sabotaged " + b.name + ".");
                }
                case "loandue" -> {
                    Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                    if (p == null) return say(s, "<yellow>/mrtown loandue <player>  <gray>(makes their next loan payment due now)");
                    t.data(p).loanNextDue = System.currentTimeMillis() - 1;
                    say(s, "<green>Next payment is due now (checked within a minute).");
                }
                case "railway" -> {
                    String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "status";
                    if (what.equals("undo")) return say(s, "<green>" + t.railway().undo());
                    if ((what.equals("check") || what.equals("build")) && a.length >= 5) {
                        World w = a.length >= 6 ? Bukkit.getWorld(a[5]) : s instanceof Player p ? p.getWorld() : t.world();
                        if (w == null) throw new IOException("World not found.");
                        int x = Integer.parseInt(a[2]), sz = Integer.parseInt(a[3]), nz = Integer.parseInt(a[4]);
                        return say(s, what.equals("check") ? "<gold>" + t.railway().check(w, x, sz, nz) : "<green>" + t.railway().build(w, x, sz, nz) + " <gray>" + t.medieval("railway") + " " + t.repair("railway"));
                    }
                    say(s, "<gold>" + t.railway().status() + " <gray>/mrtown railway check|build <x> <southZ> <northZ> · undo");
                }
                case "meadowbrook" -> {
                    String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
                    if (what.equals("undo")) return say(s, "<green>" + t.meadowbrook().undo());
                    if ((what.equals("check") || what.equals("build")) && a.length >= 5) {
                        World w = a.length >= 6 ? Bukkit.getWorld(a[5]) : s instanceof Player p ? p.getWorld() : t.world();
                        if (w == null) throw new IOException("World not found.");
                        int x = Integer.parseInt(a[2]), y = Integer.parseInt(a[3]), z = Integer.parseInt(a[4]);
                        return say(s, what.equals("check") ? "<gold>" + t.meadowbrook().check(w, x, y, z) : "<green>" + t.meadowbrook().build(w, x, y, z) + " <gray>" + t.medieval("meadowbrook") + " " + t.repair("meadowbrook"));
                    }
                    say(s, "<yellow>/mrtown meadowbrook check|build <x> <floorY> <z> [world] · undo  <gray>(built: " + t.meadowbrook().built() + ")");
                }
                case "raid" -> {
                    if (!t.casino().ready()) return say(s, "<red>There's no gambling den.");
                    var in = t.casino().playersInside();
                    if (in.isEmpty()) return say(s, "<gray>Nobody is in the gambling den.");
                    t.casino().raid(in);
                    say(s, "<green>Raid started.");
                }
                case "npcgang" -> say(s, "<gold>" + t.npcGangs().reset(a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : ""));
                case "heists" -> say(s, "<gold>" + t.heists().adminReset());
                case "case" -> {
                    Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                    if (p == null) return say(s, "<yellow>/mrtown case <player> [close|ambush]");
                    TownData d = t.data(p);
                    String what = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "info";
                    if (what.equals("close")) t.bureau().close(d, p, "<gray>The Inquisition has dropped your case.");
                    else if (what.equals("ambush")) {
                        if (!d.caseOpen) {
                            d.stars = Math.max(d.stars, 3);
                            if (d.reason.isEmpty()) d.reason = Crime.ASSAULT.label;
                            t.bureau().maybeOpen(p, d, Crime.ASSAULT);
                        }
                        t.bureau().ambush(p, Bureau.Kind.AMBUSH);
                    }
                    say(s, "<gold>" + p.getName() + ": Inquisition case " + (d.caseOpen ? "<red>open (" + d.caseReason + ")" : "<green>closed"));
                }
                case "service" -> {
                    Player p = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                    if (p == null) return say(s, "<yellow>/mrtown service <jailed player>  — move them to community service");
                    t.data(p).serviceOfferUntil = System.currentTimeMillis() + 5000;
                    t.service().start(p);
                    say(s, "<green>Done.");
                }
                case "reload" -> {
                    t.plugin().reloadEverything();
                    say(s, "<green>Reloaded.");
                }
                default -> {
                    say(s, "<gold>/mrtown status | toggle | clear | spawn <role> | wanted <player> <stars> [crime] | pardon <player> | release <player> | arrest <player> | rep <player> [set|add n] | reload");
                    say(s, "<gold>/mrtown jail check <x y z> | build [x y z] | undo | addcell | clearcells | setrelease | info");
                    say(s, "<gold>/mrtown bank check <x y z> | build [x y z] | undo | setvault | info   /mrtown case <player> [close|ambush]   /mrtown service <player>");
                    say(s, "<gold>/mrtown casino check <x floorY z> | build <x floorY z> | undo | info   /mrtown raid   /mrtown npcgang <vipers|jackals>   /mrtown heists (clear cooldowns)");
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            say(s, "<red>" + e.getMessage());
        }
        return true;
    }

    private void jail(CommandSender s, String[] a) throws IOException {
        String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        Player p = s instanceof Player pl ? pl : null;
        switch (what) {
            case "check", "build" -> {
                World w;
                int x, y, z;
                if (a.length >= 5) {
                    x = Integer.parseInt(a[2]);
                    y = Integer.parseInt(a[3]);
                    z = Integer.parseInt(a[4]);
                    w = a.length >= 6 ? Bukkit.getWorld(a[5]) : p != null ? p.getWorld() : t.world();
                } else if (p != null) {
                    w = p.getWorld();
                    x = p.getLocation().getBlockX();
                    y = p.getLocation().getBlockY() - 1;
                    z = p.getLocation().getBlockZ();
                } else {
                    say(s, "<yellow>/mrtown jail " + what + " <x> <floorY> <z> [world]");
                    return;
                }
                if (w == null) throw new IOException("World not found.");
                say(s, what.equals("check") ? "<gold>" + t.jail().check(w, x, y, z) : "<green>" + t.jail().build(w, x, y, z) + " <gray>" + t.medieval("jail") + " " + t.repair("jail"));
            }
            case "undo" -> say(s, "<green>" + t.jail().undo());
            case "addcell" -> {
                if (p == null) return;
                say(s, "<green>" + t.jail().addCell(p.getLocation()) + " <gray>Stand where prisoners should appear, facing out.");
            }
            case "clearcells" -> say(s, "<green>" + t.jail().clearCells());
            case "setrelease" -> {
                if (p == null) return;
                say(s, "<green>" + t.jail().setRelease(p.getLocation()));
            }
            default -> say(s, "<gold>Jail: " + (t.jail().ready() ? "<green>ready" : "<red>not set up") + "<gold>, " + t.jail().cells().size() + " cells, release point "
                    + (t.jail().releasePoint() == null ? "<red>missing" : "<green>set") + "<gold>, generated building: " + (t.jail().built() ? "yes" : "no") + ".");
        }
    }

    private void casino(CommandSender s, String[] a) throws IOException {
        String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        switch (what) {
            case "check", "build" -> {
                if (a.length < 5) {
                    say(s, "<yellow>/mrtown casino " + what + " <x> <hall floor Y> <z> [world]  <gray>(the hall is dug out under the surface)");
                    return;
                }
                World w = a.length >= 6 ? Bukkit.getWorld(a[5]) : s instanceof Player p ? p.getWorld() : t.world();
                if (w == null) throw new IOException("World not found.");
                int x = Integer.parseInt(a[2]), y = Integer.parseInt(a[3]), z = Integer.parseInt(a[4]);
                say(s, what.equals("check") ? "<gold>" + t.casino().check(w, x, y, z) : "<green>" + t.casino().build(w, x, y, z) + " <gray>" + t.medieval("casino") + " " + t.repair("casino"));
            }
            case "undo" -> say(s, "<green>" + t.casino().undo());
            default -> {
                var c = t.casino().centre();
                say(s, "<gold>Gambling Den: " + (c == null ? "not built" : "hall at " + c.getBlockX() + " " + c.getBlockY() + " " + c.getBlockZ() + ", "
                        + t.casino().playersInside().size() + " players inside" + (t.casino().raiding() ? ", <red>raid in progress" : "")));
            }
        }
    }

    private void bank(CommandSender s, String[] a) throws IOException {
        String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "info";
        Player p = s instanceof Player pl ? pl : null;
        switch (what) {
            case "check", "build" -> {
                World w;
                int x, y, z;
                if (a.length >= 5) {
                    x = Integer.parseInt(a[2]);
                    y = Integer.parseInt(a[3]);
                    z = Integer.parseInt(a[4]);
                    w = a.length >= 6 ? Bukkit.getWorld(a[5]) : p != null ? p.getWorld() : t.world();
                } else if (p != null) {
                    w = p.getWorld();
                    x = p.getLocation().getBlockX();
                    y = p.getLocation().getBlockY() - 1;
                    z = p.getLocation().getBlockZ();
                } else {
                    say(s, "<yellow>/mrtown bank " + what + " <x> <floorY> <z> [world]");
                    return;
                }
                if (w == null) throw new IOException("World not found.");
                say(s, what.equals("check") ? "<gold>" + t.bank().check(w, x, y, z) : "<green>" + t.bank().build(w, x, y, z) + " <gray>" + t.medieval("bank") + " " + t.repair("bank"));
            }
            case "undo" -> say(s, "<green>" + t.bank().undo());
            case "setvault" -> {
                if (p != null) say(s, "<green>" + t.bank().setVault(p));
            }
            default -> say(s, "<gold>" + t.bank().info());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if ((cmd.equals("tip") || cmd.equals("bounty") || cmd.equals("dice")) && args.length == 1) return null;
        if (cmd.equals("heist") && args.length == 1) {
            for (String x : List.of("invite", "join", "start", "status", "leave", "cancel")) if (x.startsWith(args[0].toLowerCase())) out.add(x);
            return out;
        }
        if (cmd.equals("police") && args.length == 1) {
            for (String x : List.of("join", "duty", "radar", "cuff", "quit")) if (x.startsWith(args[0].toLowerCase())) out.add(x);
            return out;
        }
        if (!command.getName().equalsIgnoreCase("mrtown") || !sender.hasPermission("mrsmp.admin")) {
            if (args.length == 1 && command.getName().equalsIgnoreCase("rep") && sender.hasPermission("mrsmp.admin")) return null;
            return out;
        }
        if (args.length == 1) {
            for (String x : List.of("status", "toggle", "clear", "spawn", "wanted", "pardon", "release", "arrest", "rep", "jail", "bank", "casino", "meadowbrook", "railway", "raid",
                    "npcgang", "heists", "case", "service", "reload")) {
                if (x.startsWith(args[0].toLowerCase())) out.add(x);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("jail")) {
            for (String x : List.of("info", "check", "build", "undo", "addcell", "clearcells", "setrelease")) if (x.startsWith(args[1].toLowerCase())) out.add(x);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("bank")) {
            for (String x : List.of("info", "check", "build", "undo", "setvault")) if (x.startsWith(args[1].toLowerCase())) out.add(x);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            for (Walker.Role r : Walker.Role.values()) out.add(r.name().toLowerCase());
        } else if (args.length == 2) {
            return null;
        } else if (args.length == 4 && args[0].equalsIgnoreCase("wanted")) {
            for (Crime c : Crime.values()) out.add(c.key());
        }
        return out;
    }
}
