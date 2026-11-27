package com.colonysmp.core;

/**
 * The damage rules between soldiers (and between soldiers and anyone else). Pure arithmetic: the plugin fills
 * in a {@link Hit} from the world (angles, speeds, formations) and applies the {@link Result}.
 *
 * <p>The counters: shields stop frontal blows and arrows but not flanking; axes and maces wear shields down;
 * cavalry charges hit hard at speed, but prepared spears and pikes break them; crossbows and maces go
 * through armour; pikes are clumsy up close; berserkers hit hard and fall fast.
 */
public final class CombatMath {

    /** Blocks per tick a horse must be moving for a charge (about 5 blocks a second). */
    public static final double CHARGE_SPEED = 0.25;
    public static final double SHIELD_ARC = 120, SHIELD_WALL_ARC = 170;

    public static final class Hit {
        /** Attacking class, or null for players, monsters and unclassed citizens. */
        public MilitaryClass attacker;
        public MilitaryClass defender;
        public double baseDamage;
        public boolean ranged;
        /** Angle in degrees between where the defender faces and where the blow comes from (0 = head on). */
        public double angle;
        /** Attacker's speed in blocks per tick (for charges). */
        public double attackerSpeed;
        public boolean attackerMounted, defenderMounted;
        /** Defender holds a shield and isn't downed or broken. */
        public boolean defenderShield;
        public boolean shieldWall, spearWall;
        /** Armour points the defender wears (vanilla scale, 0-20). */
        public double defenderArmor;
        /** 1 on open ground; lower in water, mud, forest and on slopes (slows cavalry). */
        public double terrain = 1;
        public double attackerMorale = 70, defenderMorale = 70;
        /** Distance between them, for pikes being clumsy up close. */
        public double distance = 2;
        /** Height of the attacker over the defender (ranged high ground). */
        public double heightAdvantage;
        /** Whether to apply armour here (true for NPC defenders; vanilla already does it for players). */
        public boolean applyArmor = true;
    }

    public record Result(double damage, boolean blocked, boolean charge, boolean countered, double counterDamage,
                         double shieldWear, double knockback, boolean flanked, String note) {}

    private CombatMath() {}

    public static Result resolve(Hit h) {
        MilitaryClass a = h.attacker, d = h.defender;
        double dmg = Math.max(0, h.baseDamage);
        StringBuilder note = new StringBuilder();
        if (a != null) dmg *= h.ranged ? 1 : a.melee;
        // morale: shaken attackers hit softer, shaken defenders guard worse
        dmg *= 0.7 + 0.3 * clamp01(h.attackerMorale / 100);
        dmg *= 1 + 0.2 * (1 - clamp01(h.defenderMorale / 100));

        boolean charge = false, countered = false;
        double counter = 0, knockback = 0.4;
        boolean front = h.angle <= 60;
        // cavalry charges: speed and open ground make them, prepared spears break them
        if (!h.ranged && a != null && h.attackerMounted && a.charge > 1 && h.attackerSpeed >= CHARGE_SPEED) {
            double braced = d != null && d.antiCavalry > 1 && front ? (h.spearWall ? 1 : 0.6) : 0;
            if (braced > 0) {
                countered = true;
                dmg *= 0.3 + 0.4 * (1 - braced);
                counter = 6 * d.antiCavalry * braced;
                knockback = 0.1;
                note.append("charge broken by ").append(d.display.toLowerCase()).append("s; ");
            } else {
                charge = true;
                double t = Math.max(0.2, Math.min(1, h.terrain));
                dmg *= 1 + (a.charge - 1) * t;
                knockback = 1.2 * t;
                note.append("charge; ");
            }
        }
        // spears and pikes against horsemen
        if (a != null && h.defenderMounted && a.antiCavalry > 1 && !h.ranged) {
            dmg *= a.antiCavalry;
            note.append("anti-cavalry; ");
        }
        // pikes are clumsy up close
        if (a != null && a.closePenalty < 1 && !h.ranged && h.distance < 1.6) dmg *= a.closePenalty;
        // high ground for archers
        if (h.ranged && h.heightAdvantage > 2) dmg *= 1 + Math.min(0.25, h.heightAdvantage * 0.03);

        // flanking
        boolean flanked = h.angle > 100;
        if (h.angle > 150) dmg *= 1.5;
        else if (flanked) dmg *= 1.25;
        if (flanked) note.append("flanked; ");

        // shields
        boolean blocked = false;
        double wear = 0;
        double arc = h.shieldWall ? SHIELD_WALL_ARC : SHIELD_ARC;
        if (h.defenderShield && h.angle <= arc / 2 && !charge) {
            double block = h.ranged ? 0.9 : 0.7;
            if (d == MilitaryClass.SHIELDMAN) block += 0.1;
            if (h.shieldWall) block += 0.1;
            double breakPower = a == null ? 0 : a.shieldBreak;
            block = Math.min(0.97, block) * (1 - breakPower);
            wear = dmg * 0.5 * (1 + breakPower * 2);
            dmg *= 1 - block;
            blocked = block >= 0.5;
            note.append(blocked ? "blocked; " : "shield split; ");
        }

        // armour
        if (h.applyArmor) {
            double armor = Math.max(0, h.defenderArmor + (d == null ? 0 : d.armor));
            double pierce = a == null ? 0 : a.armorPierce;
            dmg *= 1 - Math.min(0.6, armor * 0.035 * (1 - pierce));
        }
        if (d != null) dmg *= d.vulnerable;
        return new Result(Math.max(0, dmg), blocked, charge, countered, counter, wear, knockback, flanked, note.toString().trim());
    }

    /** Angle (0-180) between a facing yaw and the direction an attack comes from. */
    public static double angle(float defenderYaw, double dx, double dz) {
        // direction from the defender to the attacker
        double toAttacker = Math.toDegrees(Math.atan2(-dx, dz));
        double diff = Math.abs(normalize(toAttacker - defenderYaw));
        return diff;
    }

    static double normalize(double deg) {
        double d = deg % 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    /**
     * How urgent an enemy is to a defender choosing whom to fight: lower is more urgent. Nearer comes first,
     * but one that is attacking the colony's own people, a raid's leader, or one nearly finished counts as
     * nearer than it is.
     *
     * @param distSq         squared distance to the enemy
     * @param attackingOurs  it is going for a citizen or a member right now
     * @param leader         it leads the others (a chief, a necromancer)
     * @param healthFraction its health, 0-1
     */
    public static double urgency(double distSq, boolean attackingOurs, boolean leader, double healthFraction) {
        double s = Math.max(0, distSq);
        if (attackingOurs) s *= 0.45;
        if (leader) s *= 0.7;
        s *= 0.75 + 0.25 * Math.max(0, Math.min(1, healthFraction));
        return s;
    }
}
