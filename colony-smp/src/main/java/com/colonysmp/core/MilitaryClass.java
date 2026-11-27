package com.colonysmp.core;

import java.util.List;
import java.util.Locale;

/**
 * Every kind of soldier. The numbers here drive both the soldiers' AI (reach, range, speed) and the damage
 * rules in {@link CombatMath}: nothing is universally strongest, each class has a job and a weakness.
 */
public enum MilitaryClass {

    // ── infantry ──
    MILITIA("Militia", Role.INFANTRY, "Cheap, lightly trained settlement defenders.",
            stats().melee(0.8).attack(20).reach(2.2).morale(-10).upkeep(1).train(null)),
    SWORDSMAN("Swordsman", Role.INFANTRY, "Balanced melee damage, speed and defence.",
            stats().melee(1.0).attack(16).reach(2.4).armor(2).upkeep(3)),
    SHIELDMAN("Shieldman", Role.INFANTRY, "Blocks frontal attacks and arrows; forms shield walls; bashes. Weak to flanking and axes.",
            stats().melee(0.85).attack(18).reach(2.2).speed(0.9).armor(2).shield().upkeep(3).arms(Arms.MELEE, Arms.SHIELD)),
    SPEARMAN("Spearman", Role.INFANTRY, "Reach to keep enemies away; bonus against charging cavalry.",
            stats().melee(0.95).attack(18).reach(3.2).speed(0.95).antiCavalry(1.8).upkeep(3)),
    PIKEMAN("Pikeman", Role.INFANTRY, "Long pike walls that break cavalry charges. Weak up close and from the flanks.",
            stats().melee(0.9).attack(22).reach(3.8).speed(0.85).antiCavalry(2.5).closePenalty(0.6).upkeep(3)),
    AXEMAN("Axeman", Role.INFANTRY, "Heavy blows that split shields and break formations.",
            stats().melee(1.2).attack(20).reach(2.4).shieldBreak(0.6).upkeep(3)),
    BERSERKER("Berserker", Role.INFANTRY, "Huge damage in assaults, little defence. Fearless.",
            stats().melee(1.4).attack(14).reach(2.3).speed(1.1).armor(-2).vulnerable(1.2).morale(15).upkeep(4)),
    MACEMAN("Maceman", Role.INFANTRY, "Slow, crushing blows that go through armour.",
            stats().melee(1.15).attack(26).reach(2.3).armorPierce(0.5).shieldBreak(0.3).upkeep(3)),
    DUELIST("Duelist", Role.INFANTRY, "Fast and evasive one-on-one; struggles against groups.",
            stats().melee(1.0).attack(10).reach(2.4).speed(1.1).evasion(0.25).upkeep(4)),
    BODYGUARD("Bodyguard", Role.INFANTRY, "Stays with a leader or citizen and protects them.",
            stats().melee(1.0).attack(16).reach(2.4).armor(2).upkeep(4)),

    // ── ranged ──
    ARCHER("Archer", Role.RANGED, "General ranged attacks, even from behind friendly infantry.",
            stats().melee(0.6).range(22).ranged(4).reload(30).upkeep(3).train("ARCHERY_RANGE").arms(Arms.BOW, Arms.ARROWS)),
    LONGBOWMAN("Longbowman", Role.RANGED, "Out-ranges other archers and punishes exposed formations.",
            stats().melee(0.6).range(32).ranged(5).reload(40).upkeep(4).train("ARCHERY_RANGE").arms(Arms.BOW, Arms.ARROWS)),
    CROSSBOWMAN("Crossbowman", Role.RANGED, "Powerful bolts that pierce armour; slow to reload.",
            stats().melee(0.6).range(24).ranged(7).reload(60).armorPierce(0.4).upkeep(4).train("ARCHERY_RANGE").arms(Arms.CROSSBOW, Arms.ARROWS)),
    JAVELINEER("Javelinman", Role.RANGED, "Short-range thrown javelins for skirmishing.",
            stats().melee(0.8).range(12).ranged(6).reload(35).upkeep(3).train("ARCHERY_RANGE").arms(Arms.MELEE, Arms.JAVELINS)),
    SLINGER("Slinger", Role.RANGED, "Cheap harassment: cobblestone for ammunition.",
            stats().melee(0.6).range(18).ranged(3).reload(28).upkeep(2).train("ARCHERY_RANGE").arms(Arms.SLING_STONES)),

    // ── cavalry ──
    LIGHT_CAVALRY("Light Cavalry", Role.CAVALRY, "Scouting, harassment and pursuit.",
            stats().melee(0.9).attack(16).reach(2.6).speed(1.6).charge(1.4).upkeep(6).train("STABLES").arms(Arms.MELEE, Arms.HORSE)),
    HEAVY_CAVALRY("Heavy Cavalry", Role.CAVALRY, "Armoured mounted assaults with crushing charges.",
            stats().melee(1.1).attack(18).reach(2.6).speed(1.3).charge(2.0).armor(4).upkeep(9).train("STABLES").arms(Arms.MELEE, Arms.HORSE)),
    LANCER("Lancer", Role.CAVALRY, "Devastating lance charges; must wheel away to charge again.",
            stats().melee(1.0).attack(20).reach(3.0).speed(1.45).charge(2.6).upkeep(8).train("STABLES").arms(Arms.MELEE, Arms.HORSE)),
    HORSE_ARCHER("Horse Archer", Role.CAVALRY, "Mobile ranged attacks from the saddle.",
            stats().melee(0.6).speed(1.5).range(20).ranged(3.5).reload(34).upkeep(8).train("STABLES").arms(Arms.BOW, Arms.ARROWS, Arms.HORSE)),
    CAVALRY_RAIDER("Cavalry Raider", Role.CAVALRY, "Hits vulnerable targets and supply lines; loots fast.",
            stats().melee(1.0).attack(16).reach(2.6).speed(1.6).charge(1.3).upkeep(6).train("STABLES").arms(Arms.MELEE, Arms.HORSE)),
    MOUNTED_SWORDSMAN("Mounted Swordsman", Role.CAVALRY, "General-purpose mounted melee.",
            stats().melee(1.05).attack(16).reach(2.6).speed(1.4).charge(1.3).armor(2).upkeep(7).train("STABLES").arms(Arms.MELEE, Arms.HORSE)),

    // ── specialists ──
    MEDIC("Medic", Role.SUPPORT, "Treats wounded soldiers in the field.",
            stats().melee(0.5).heal(2).upkeep(3).train("CLINIC").arms(Arms.MEDICINE)),
    STANDARD_BEARER("Standard Bearer", Role.SUPPORT, "Steadies morale and formation cohesion nearby.",
            stats().melee(0.7).aura(1.0).upkeep(3).arms(Arms.BANNER)),
    COMMANDER("Commander", Role.SUPPORT, "Issues orders and coordinates troops. Their fall shakes the army.",
            stats().melee(1.0).attack(16).reach(2.4).armor(2).aura(0.5).upkeep(6)),
    SCOUT("Scout", Role.SUPPORT, "Spots and reports enemy movements far ahead.",
            stats().melee(0.7).speed(1.25).sight(48).upkeep(2).train(null)),
    SAPPER("Sapper", Role.SUPPORT, "Breaches fortifications with siege charges during sieges.",
            stats().melee(0.7).upkeep(4).train("ENGINEERING_WORKSHOP").arms(Arms.SIEGE_CHARGES)),
    SIEGE_ENGINEER("Siege Engineer", Role.SUPPORT, "Operates siege charges and repairs works.",
            stats().melee(0.6).upkeep(5).train("ENGINEERING_WORKSHOP").arms(Arms.SIEGE_CHARGES));

    public enum Role { INFANTRY, RANGED, CAVALRY, SUPPORT }

    /** Equipment a class needs from the armoury. */
    public enum Arms {
        MELEE("a melee weapon"), SHIELD("a shield"), BOW("a bow"), CROSSBOW("a crossbow"), ARROWS("arrows"),
        JAVELINS("javelins"), SLING_STONES("cobblestone (sling stones)"), HORSE("a war horse"), MEDICINE("medicine"),
        SIEGE_CHARGES("siege charges"), BANNER("a banner");

        public final String display;

        Arms(String display) {
            this.display = display;
        }
    }

    public final String display;
    public final Role role;
    public final String description;
    public final double melee, reach, speed, range, ranged, antiCavalry, closePenalty, shieldBreak, armorPierce, charge,
            vulnerable, evasion, heal, aura, sight;
    public final int attackTicks, reloadTicks, armor, morale, upkeep;
    public final boolean shield;
    /** Building type where the class trains (null: none needed). */
    public final String training;
    public final List<Arms> arms;

    MilitaryClass(String display, Role role, String description, S s) {
        this.display = display;
        this.role = role;
        this.description = description;
        this.melee = s.melee;
        this.attackTicks = s.attack;
        this.reach = s.reach;
        this.speed = s.speed;
        this.range = s.range;
        this.ranged = s.ranged;
        this.reloadTicks = s.reload;
        this.antiCavalry = s.antiCavalry;
        this.closePenalty = s.closePenalty;
        this.shieldBreak = s.shieldBreak;
        this.armorPierce = s.armorPierce;
        this.charge = s.charge;
        this.vulnerable = s.vulnerable;
        this.evasion = s.evasion;
        this.heal = s.heal;
        this.aura = s.aura;
        this.sight = s.sight;
        this.armor = s.armor;
        this.morale = s.morale;
        this.upkeep = s.upkeep;
        this.shield = s.shield;
        this.training = s.training;
        this.arms = s.arms;
    }

    public boolean mounted() {
        return arms.contains(Arms.HORSE);
    }

    public boolean shooter() {
        return range > 0;
    }

    public boolean melee() {
        return attackTicks > 0;
    }

    public static MilitaryClass parse(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (u.equals("JAVELINMAN")) return JAVELINEER;
        try {
            return valueOf(u);
        } catch (IllegalArgumentException e) {
            for (MilitaryClass c : values()) if (c.display.equalsIgnoreCase(s.trim())) return c;
            return null;
        }
    }

    // ───────────── stat builder ─────────────

    private static S stats() {
        return new S();
    }

    private static final class S {
        double melee = 1, reach = 2.4, speed = 1, range, ranged, antiCavalry = 1, closePenalty = 1, shieldBreak, armorPierce, charge = 1,
                vulnerable = 1, evasion, heal, aura, sight = 24;
        int attack = 16, reload, armor, morale, upkeep = 3;
        boolean shield;
        String training = "TRAINING_GROUND";
        List<Arms> arms = List.of(Arms.MELEE);

        S melee(double v) { melee = v; return this; }
        S attack(int v) { attack = v; return this; }
        S reach(double v) { reach = v; return this; }
        S speed(double v) { speed = v; return this; }
        S range(double v) { range = v; return this; }
        S ranged(double v) { ranged = v; return this; }
        S reload(int v) { reload = v; return this; }
        S antiCavalry(double v) { antiCavalry = v; return this; }
        S closePenalty(double v) { closePenalty = v; return this; }
        S shieldBreak(double v) { shieldBreak = v; return this; }
        S armorPierce(double v) { armorPierce = v; return this; }
        S charge(double v) { charge = v; return this; }
        S vulnerable(double v) { vulnerable = v; return this; }
        S evasion(double v) { evasion = v; return this; }
        S heal(double v) { heal = v; return this; }
        S aura(double v) { aura = v; return this; }
        S sight(double v) { sight = v; return this; }
        S armor(int v) { armor = v; return this; }
        S morale(int v) { morale = v; return this; }
        S upkeep(int v) { upkeep = v; return this; }
        S shield() { shield = true; return this; }
        S train(String b) { training = b; return this; }
        S arms(Arms... a) { arms = List.of(a); return this; }
    }
}
