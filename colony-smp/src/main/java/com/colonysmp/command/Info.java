package com.colonysmp.command;

import com.colonysmp.ColonySMP;
import com.colonysmp.building.BuildingManager;
import com.colonysmp.core.Category;
import com.colonysmp.core.Good;
import com.colonysmp.core.Goods;
import com.colonysmp.core.MilitaryClass;
import com.colonysmp.core.Morale;
import com.colonysmp.core.Recipe;
import com.colonysmp.core.Recipes;
import com.colonysmp.core.Skill;
import com.colonysmp.core.TxnType;
import com.colonysmp.model.Bond;
import com.colonysmp.model.BuildOrder;
import com.colonysmp.model.Building;
import com.colonysmp.model.BuildingType;
import com.colonysmp.model.Citizen;
import com.colonysmp.model.Colony;
import com.colonysmp.model.Job;
import com.colonysmp.model.LifeStage;
import com.colonysmp.model.Relation;
import com.colonysmp.model.Squad;
import com.colonysmp.model.Status;
import com.colonysmp.model.War;
import com.colonysmp.npc.Construction;
import com.colonysmp.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** The colony's facts as lines of text, shared by the commands and the menus. */
public final class Info {

    private final ColonySMP plugin;

    public Info(ColonySMP plugin) {
        this.plugin = plugin;
    }

    private String money(long v) {
        return Text.value(v);
    }

    public static String name(UUID u) {
        OfflinePlayer p = Bukkit.getOfflinePlayer(u);
        return p.getName() == null ? u.toString().substring(0, 8) : p.getName();
    }

    // ───────────── the colony ─────────────

    public List<String> colony(Colony c) {
        List<String> out = new ArrayList<>();
        var s = plugin.settings();
        long day = plugin.sim().day(c);
        int pop = c.population(), children = 0, soldiers = 0, prisoners = 0;
        double mood = 0;
        for (Citizen ct : c.citizens.values()) {
            if (!ct.status.free()) {
                prisoners++;
                continue;
            }
            mood += ct.needs.mood;
            if (plugin.sim().citizens().stage(ct) == LifeStage.CHILD) children++;
            if (ct.job.military()) soldiers++;
        }
        mood = pop == 0 ? 0 : mood / pop;
        out.add("<gray>Owner <white>" + name(c.owner) + "</white> · settlement level <white>" + c.level() + "</white> · day <white>" + day + "</white>"
                + (c.foundedDay > 0 ? " (founded day " + c.foundedDay + ")" : ""));
        if (c.core == null) out.add("<yellow>Not founded yet: place the Town Hall Core (/colony core) where the colony should stand.");
        out.add("<gray>Land <white>" + c.territory.size() + "/" + s.chunkLimit(c.level()) + "</white> chunks · members <white>" + c.members.size() + "</white>");
        out.add("<gray>Population <white>" + pop + "</white> (" + children + " children, " + soldiers + " guards and soldiers" + (prisoners > 0 ? ", " + prisoners + " prisoners" : "")
                + ") · beds free <white>" + Math.max(0, plugin.sim().population().freeBeds(c)) + "</white>" + (plugin.sim().housing().homeless(c) > 0 ? " <red>(" + plugin.sim().housing().homeless(c) + " homeless)" : ""));
        double food = plugin.sim().finances().foodDays(c);
        out.add("<gray>Food <white>" + (food < 2 ? "<red>" : "") + Text.num(food) + " days</white> · treasury <gold>" + money(c.treasury.balance()) + "</gold> · daily costs <white>"
                + money(plugin.sim().finances().dailyCosts(c)) + "</white>");
        out.add("<gray>Stability " + Text.meter(c.stability) + " <gray>· mood " + Text.meter(mood));
        if (c.stability < 50) out.add("<gray>  Biggest problem: <yellow>" + plugin.sim().order().worstFactor(c));
        out.add("<gray>Storage <white>" + c.storage.usedSlots() + "/" + c.storage.capacity() + "</white> slots, granary <white>" + c.storage.usedGranarySlots() + "/"
                + c.storage.granaryCapacity() + "</white>, armoury <white>" + c.equipment.size() + "/" + c.equipment.capacity() + "</white>");
        out.add("<gray>Reputation <white>" + c.reputation + "/100</white> · " + (c.active ? "<green>active (loaded)" : "<gray>simulated in the background"));
        if (c.protest) out.add("<red>Citizens are protesting: work is slower until stability recovers.");
        War w = plugin.wars().warOf(c);
        if (w != null) {
            Colony other = plugin.colonies().get(w.attackers.contains(c.id) ? w.defender : w.attacker);
            out.add("<red>At war (" + w.goal.display.toLowerCase() + ", " + w.phase.name().toLowerCase() + ") with " + (other == null ? "?" : Text.esc(other.name))
                    + " · score " + w.attackerScore + " - " + w.defenderScore);
        }
        if (c.shielded()) out.add("<aqua>Peace shield: " + Text.duration(c.shieldUntil - System.currentTimeMillis()));
        var raid = plugin.raids().raid(c);
        if (raid != null) out.add("<red>Under attack: " + raid.kind.raid + ", " + raid.alive() + " " + raid.kind.members + " left.");
        else if (c.raidDay >= 0 && c.raidDay == day) out.add("<red>Raiders are expected at nightfall (/colony raid).");
        if (c.researching != null) out.add("<light_purple>Researching " + c.researching.display + ": " + Math.round(plugin.sim().academy().progress(c) * 100) + "%");
        List<String> problems = plugin.sim().reports().problems(c, food);
        if (!problems.isEmpty()) out.add("<yellow>⚠ " + String.join("; ", problems.subList(0, Math.min(4, problems.size()))));
        return out;
    }

    // ───────────── citizens ─────────────

    public List<String> citizen(Colony col, Citizen c) {
        List<String> out = new ArrayList<>();
        var p = plugin.settings().needs;
        LifeStage stage = plugin.sim().citizens().stage(c);
        out.add(c.status.color + c.title() + " <gray>· " + stage.display + ", " + (int) c.ageDays + " days old · " + c.trait.display + " <dark_gray>(" + c.trait.description + ")");
        Building work = col.building(c.workplace), home = col.building(c.home);
        out.add("<gray>Works at <white>" + (work != null ? work.label() : c.job.needsWorkplace() ? "<red>nowhere" : "-") + "</white> · lives at <white>"
                + (home != null ? home.label() : c.status == Status.PRISONER ? "a cell" : "<red>no home") + "</white>");
        out.add("<gray>Doing: <white>" + Text.esc(c.activity) + (c.waiting != null ? " <yellow>(" + Text.esc(c.waiting) + ")" : ""));
        out.add("<gray>Health " + Text.meter(c.needs.healthFraction() * 100) + " <gray>Hunger " + Text.meter(c.needs.satiety) + " <gray>Energy " + Text.meter(c.needs.energy));
        out.add("<gray>Social " + Text.meter(c.needs.social) + " <gray>Mood " + Text.meter(c.needs.mood) + " <gray>Housing " + Text.meter(housing(col, c)));
        out.add("<gray>Mood: <white>" + Text.esc(plugin.sim().citizens().why(col, c)));
        if (c.ill() || c.injured) out.add("<light_purple>" + (c.ill() ? c.illness.display + (c.treated ? " (treated)" : " (untreated)") : "") + (c.ill() && c.injured ? ", " : "")
                + (c.injured ? "injured" : ""));
        out.add("<gray>Education <white>" + Math.round(c.education) + "</white> (" + c.educationTier() + ") · efficiency <white>"
                + Math.round(plugin.sim().citizens().efficiency(col, c) * 100) + "%</white>" + (c.wallet > 0 || c.unpaidDays > 0 ? " · savings <gold>" + money(c.wallet) + "</gold>" : "")
                + (c.unpaidDays > 0 ? " <red>(unpaid " + c.unpaidDays + " days)" : ""));
        List<String> skills = new ArrayList<>();
        for (Map.Entry<Skill, Double> e : c.skills.all().entrySet()) {
            int lvl = Skill.level(e.getValue());
            if (lvl > 0) skills.add(e.getKey().display + " " + lvl);
        }
        if (!skills.isEmpty()) out.add("<gray>Skills: <white>" + String.join(", ", skills));
        List<String> family = new ArrayList<>();
        for (Map.Entry<UUID, Bond> e : c.bonds.entrySet()) {
            Bond.Kind k = e.getValue().kind;
            if (k == Bond.Kind.ACQUAINTANCE) continue;
            Citizen o = col.citizens.get(e.getKey());
            if (o != null) family.add(k.display.toLowerCase() + " " + o.name);
        }
        if (!family.isEmpty()) out.add("<gray>Family and friends: <white>" + String.join(", ", family.subList(0, Math.min(6, family.size()))));
        if (c.job.military()) {
            out.add("<gray>Training <white>" + Math.round(c.training * 100) + "%</white> · morale <white>" + Math.round(c.morale) + " (" + Morale.state(c.morale).display + ")"
                    + (c.squad != 0 && col.squad(c.squad) != null ? "</white> · squad <white>" + Text.esc(col.squad(c.squad).name) : ""));
            String missing = plugin.military().missingArms(c);
            if (missing != null) out.add("<yellow>Lacks: " + missing);
        }
        List<String> gear = new ArrayList<>();
        for (ItemStack it : c.gear()) gear.add(Text.nice(it.getType().name()).toLowerCase());
        if (c.ammo > 0) gear.add(c.ammo + " ammunition");
        if (c.horse) gear.add("a war horse");
        if (!gear.isEmpty()) out.add("<gray>Carries: <white>" + String.join(", ", gear));
        if (!c.carried.isEmpty()) {
            List<String> load = new ArrayList<>();
            c.carried.contents().forEach((k, v) -> load.add(v + " " + Goods.get(k).name()));
            out.add("<gray>Load: <white>" + String.join(", ", load));
        }
        if (c.status == Status.PRISONER) {
            out.add(c.origin == null ? "<yellow>Serving a sentence until day " + c.sentenceUntil
                    : "<yellow>Prisoner from " + origin(c) + " · resistance " + Math.round(c.resistance) + "% · ransom " + money(plugin.prison().ransom(c)));
        }
        int i = 0;
        for (String h : c.history) {
            if (i++ >= 4) break;
            out.add("<dark_gray>" + Text.esc(h));
        }
        return out;
    }

    private String origin(Citizen c) {
        Colony o = c.origin == null ? null : plugin.colonies().get(c.origin);
        return o != null ? Text.esc(o.name) : c.origin == null ? "here" : c.origin;
    }

    /** 0-100 for the housing need (no home 0, shared crowded room low, a good house high). */
    public double housing(Colony col, Citizen c) {
        Building h = col.building(c.home);
        if (h == null) return 0;
        if (h.type == BuildingType.BARRACKS) return 50;
        return Math.min(100, 30 + 20 * h.level + Math.min(20, h.floor));
    }

    // ───────────── buildings ─────────────

    public List<String> building(Colony col, Building b) {
        List<String> out = new ArrayList<>();
        out.add("<gray>" + b.type.category.display + " · level <white>" + b.level + "/" + b.type.maxLevel + "</white>" + (b.size().isEmpty() ? "" : " · " + b.size())
                + " · condition " + Text.meter(b.condition));
        out.add(b.valid ? "<green>Passes inspection" : "<red>Failed inspection: " + Text.esc(b.problem == null ? "?" : b.problem) + " <gray>(" + b.invalidDays + " day(s))");
        if (b.anchor != null) out.add("<gray>At <white>" + b.anchor.x() + ", " + b.anchor.y() + ", " + b.anchor.z() + "</white>");
        Map<Job, Integer> jobs = b.type.jobs(b.level);
        for (Map.Entry<Job, Integer> e : jobs.entrySet()) {
            List<String> names = new ArrayList<>();
            for (Citizen w : col.workers(b)) if (w.job == e.getKey()) names.add(w.name);
            out.add("<gray>" + e.getKey().display + "s <white>" + names.size() + "/" + b.slots(e.getKey()) + "</white>" + (names.isEmpty() ? "" : ": " + Text.esc(String.join(", ", names))));
        }
        if (!b.beds.isEmpty()) out.add("<gray>Beds <white>" + b.beds.size());
        if (!b.counts.isEmpty()) {
            List<String> l = new ArrayList<>();
            b.counts.forEach((k, v) -> l.add(v + " " + k.replace('_', ' ')));
            out.add("<gray>" + (b.type == BuildingType.FARM ? "Growing" : "Animals") + ": <white>" + String.join(", ", l));
        }
        if (b.type == BuildingType.FARM) out.add("<gray>Crop: <white>" + (b.crop == null ? "follow demand" : b.crop));
        switch (b.type) {
            case UNIVERSITY -> out.add(col.researching == null ? "<gray>Research: <white>nothing chosen</white> (/colony research)"
                    : "<gray>Research: <white>" + col.researching.display + " (" + Math.round(plugin.sim().academy().progress(col) * 100) + "%)");
            case ENCHANTERS_TOWER -> out.add("<gray>Enchantments up to level <white>" + plugin.sim().trades().enchantLevel(col, b) + "</white> · next one <white>"
                    + Math.min(99, Math.round(b.progress / com.colonysmp.sim.Trades.ENCHANT_HOURS * 100)) + "%</white> · 2 lapis a level");
            case APIARY -> out.add("<gray>Hives <white>" + b.feature(com.colonysmp.model.Feature.BEEHIVE) + "</white> · honey needs glass bottles, comb doesn't");
            case DRUID_GROVE -> out.add("<gray>Fields grow <white>+" + Math.round(plugin.sim().trades().growthBonus(col, plugin.sim().day(col)) * 100) + "%</white> faster");
            case LIBRARY -> out.add("<gray>Adults read here in the evening and educate themselves (up to 70)");
            default -> {
            }
        }
        Recipe r = Recipes.get(b.recipe);
        if (r != null) out.add("<gray>Making: <white>" + r.name + " (" + Math.min(99, Math.round(b.progress / r.hours * 100)) + "%)");
        if (b.waiting != null) out.add("<yellow>" + Text.esc(b.waiting));
        if (!b.stock.isEmpty()) out.add("<gray>Stock: <white>" + goods(b.stock.contents(), 6));
        if (b.type.upkeep(b.level) > 0) out.add("<gray>Upkeep <white>" + money(b.type.upkeep(b.level)) + "</white>/day");
        if (b.level < b.type.maxLevel) {
            String why = plugin.buildings().canUpgrade(col, b);
            out.add("<gray>Upgrade to " + (b.level + 1) + ": <white>" + BuildingManager.costText(b.type, b.level + 1)
                    + (why == null ? "" : " <red>(" + Text.esc(why) + ")"));
            var next = b.type.level(b.level + 1);
            if (!next.features().isEmpty() || next.floor() > 0) {
                List<String> need = new ArrayList<>();
                next.features().forEach((f, n) -> need.add(n + " " + f.display));
                if (next.floor() > 0) need.add(next.floor() + " floor tiles");
                out.add("<gray>  Needs: " + String.join(", ", need));
            }
        }
        return out;
    }

    public String goods(Map<String, Long> g, int max) {
        List<String> l = new ArrayList<>();
        for (Map.Entry<String, Long> e : g.entrySet()) {
            if (l.size() >= max) {
                l.add("…");
                break;
            }
            l.add(e.getValue() + " " + Goods.get(e.getKey()).name());
        }
        return String.join(", ", l);
    }

    // ───────────── resources and production ─────────────

    public List<String> resources(Colony c, Category only) {
        Map<Category, List<String>> by = new EnumMap<>(Category.class);
        Map<String, Long> all = new TreeMap<>();
        for (Map.Entry<String, Long> e : c.storage.contents().entrySet()) all.merge(e.getKey(), e.getValue(), Long::sum);
        for (Building b : c.buildings.values()) for (Map.Entry<String, Long> e : b.stock.contents().entrySet()) all.merge(e.getKey(), e.getValue(), Long::sum);
        for (Map.Entry<String, Long> e : all.entrySet()) {
            Good g = Goods.get(e.getKey());
            if (only != null && g.category() != only) continue;
            long reserved = c.storage.reserved(e.getKey());
            int[] t = c.target(e.getKey());
            by.computeIfAbsent(g.category(), k -> new ArrayList<>()).add("<white>" + e.getValue() + "</white> " + g.name()
                    + (reserved > 0 ? " <dark_gray>(" + reserved + " reserved)" : "") + (t != null ? " <dark_gray>[" + t[0] + "-" + t[1] + "]" : ""));
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<Category, List<String>> e : by.entrySet()) out.add("<gold>" + e.getKey().display + ":</gold> " + String.join("<gray>, ", e.getValue()));
        if (out.isEmpty()) out.add("<gray>Nothing in storage" + (only != null ? " in " + only.display.toLowerCase() : "") + ".");
        return out;
    }

    public List<String> production(Colony c) {
        List<String> out = new ArrayList<>();
        out.add("<gray>Produced today: <white>" + c.producedToday + "</white> goods · couriers' hours today <white>" + Text.num(plugin.sim().logistics().courierHoursToday(c)));
        for (Building b : c.buildings.values()) {
            if (!b.type.workplace() || b.type == BuildingType.TOWN_HALL) continue;
            int workers = c.workers(b).size();
            Recipe r = Recipes.get(b.recipe);
            String state = !b.valid ? "<red>failed inspection" : workers == 0 ? "<red>no workers" : r != null ? "<green>" + r.name + " " + Math.min(99, Math.round(b.progress / r.hours * 100)) + "%"
                    : b.waiting != null ? "<yellow>" + Text.esc(b.waiting) : "<gray>working";
            out.add("<white>" + Text.esc(b.label()) + "</white> <gray>(" + workers + " workers): " + state);
        }
        if (out.size() == 1) out.add("<gray>No workplaces yet. Build and register them with the Blueprint Book.");
        return out;
    }

    // ───────────── economy ─────────────

    public List<String> economy(Colony c) {
        List<String> out = new ArrayList<>();
        long day = plugin.sim().day(c);
        out.add("<gray>Treasury <gold>" + money(c.treasury.balance()) + "</gold> · economy <white>" + c.policies.economy.display + "</white> · wages <white>"
                + c.policies.wages.display + "</white> · tax <white>" + Math.round(c.policies.taxRate * 100) + "%");
        out.add("<gray>Daily wages and upkeep <white>" + money(plugin.sim().finances().dailyCosts(c)) + "</white> (upkeep " + money(plugin.sim().finances().upkeep(c))
                + ") · stock worth <white>" + money(plugin.sim().finances().stockValue(c)));
        for (int i = 0; i < 2; i++) {
            Map<TxnType, Long> m = i == 0 ? c.treasury.today(day) : c.treasury.yesterday(day);
            if (m.isEmpty()) continue;
            List<String> l = new ArrayList<>();
            m.forEach((t, v) -> l.add(t.display + " " + (v >= 0 ? "<green>+" : "<red>") + v + "<gray>"));
            out.add("<gray>" + (i == 0 ? "Today" : "Yesterday") + ": " + String.join(", ", l));
        }
        var tp = plugin.economy().tradingPost(c);
        out.add(tp == null ? "<gray>No staffed Trading Post: you can't trade on the market yet." : "<gray>Trading Post: <white>" + tp.label() + "</white>, merchant skill <white>"
                + plugin.economy().merchantSkill(c));
        out.add("<gray>Auto-sell surplus <white>" + (c.policies.autoSell ? "on" : "off") + "</white> · auto-buy shortfalls <white>" + (c.policies.autoBuy ? "on" : "off"));
        return out;
    }

    // ───────────── the army ─────────────

    public List<String> military(Colony c) {
        List<String> out = new ArrayList<>();
        var mm = plugin.military();
        Map<MilitaryClass, Integer> classes = new EnumMap<>(MilitaryClass.class);
        int guards = 0;
        for (Citizen ct : c.citizens.values()) {
            if (!ct.status.free()) continue;
            if (ct.job == Job.GUARD) guards++;
            if (ct.job == Job.SOLDIER && ct.militaryClass != null) classes.merge(ct.militaryClass, 1, Integer::sum);
        }
        out.add("<gray>Soldiers <white>" + mm.army(c).size() + "/" + mm.capacity(c) + "</white> · guards <white>" + guards + "</white> · posture <white>"
                + c.policies.posture.display + "</white> · " + (mm.alert(c) ? "<red>on alert" : "<green>at peace"));
        if (!classes.isEmpty()) {
            List<String> l = new ArrayList<>();
            classes.forEach((k, v) -> l.add(v + " " + k.display));
            out.add("<gray>Classes: <white>" + String.join(", ", l));
        }
        for (Squad q : c.squads.values()) {
            out.add("<gray>Squad <white>" + Text.esc(q.name) + "</white> (#" + q.id + "): " + mm.members(c, q).size() + " soldiers, " + q.order.display + ", " + q.formation.display
                    + " formation, morale " + Math.round(q.morale));
        }
        return out;
    }

    // ───────────── diplomacy ─────────────

    public List<String> diplomacy(Colony c) {
        List<String> out = new ArrayList<>();
        long day = plugin.sim().day(c);
        for (Relation r : plugin.diplomacy().relationsOf(c)) {
            Colony o = plugin.colonies().get(r.other(c.id));
            if (o == null) continue;
            Relation.Status s = r.status(day);
            List<String> t = new ArrayList<>();
            for (Relation.Treaty tr : r.treaties) t.add(tr.display);
            out.add(s.color + s.display + " <white>" + Text.esc(o.name) + "</white> <gray>(" + Math.round(r.score) + ")" + (t.isEmpty() ? "" : " · " + String.join(", ", t)));
        }
        if (out.isEmpty()) out.add("<gray>No dealings with other colonies yet.");
        return out;
    }

    // ───────────── construction ─────────────

    public List<String> construction(Colony c) {
        List<String> out = new ArrayList<>();
        BuildOrder cur = Construction.current(c);
        int builders = 0;
        for (Citizen ct : c.citizens.values()) if (ct.job == Job.BUILDER && ct.status.free()) builders++;
        out.add("<gray>Builders <white>" + builders + "</white> · orders <white>" + c.buildQueue.size());
        for (BuildOrder o : c.buildQueue) {
            var bp = com.colonysmp.building.Blueprint.of(c, o);
            int pct = bp == null || bp.steps.isEmpty() ? 0 : o.step * 100 / bp.steps.size();
            out.add("<white>#" + o.id + " " + Text.esc(o.label()) + "</white> <gray>at " + o.origin.x() + ", " + o.origin.y() + ", " + o.origin.z() + " · " + o.state.display + " · " + pct + "%"
                    + (o == cur ? " <green>(being built)" : "") + (o.waitingFor != null ? " <yellow>waiting for " + Text.esc(o.waitingFor) : ""));
        }
        return out;
    }
}
