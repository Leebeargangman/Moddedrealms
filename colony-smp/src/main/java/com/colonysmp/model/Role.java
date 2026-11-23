package com.colonysmp.model;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * A player's standing in a colony. The first four are members; the last three are what everyone else is,
 * decided by diplomacy. Colonies can change what MANAGER, RESIDENT, ALLY and VISITOR may do.
 */
public enum Role {
    OWNER("Owner", true),
    CO_OWNER("Co-owner", true),
    MANAGER("Manager", true),
    RESIDENT("Resident", true),
    ALLY("Ally", false),
    VISITOR("Visitor", false),
    ENEMY("Enemy", false);

    public final String display;
    public final boolean member;

    Role(String display, boolean member) {
        this.display = display;
        this.member = member;
    }

    /** Built-in permissions (before a colony's own changes). */
    public Set<Perm> defaults() {
        return switch (this) {
            case OWNER -> EnumSet.allOf(Perm.class);
            case CO_OWNER -> EnumSet.complementOf(EnumSet.of(Perm.ROLES));
            case MANAGER -> EnumSet.of(Perm.BUILD, Perm.INTERACT, Perm.CONTAINERS, Perm.STORAGE_DEPOSIT, Perm.STORAGE_WITHDRAW,
                    Perm.MANAGE_BUILDINGS, Perm.MANAGE_JOBS, Perm.MANAGE_CITIZENS, Perm.TRADE, Perm.MILITARY);
            case RESIDENT -> EnumSet.of(Perm.BUILD, Perm.INTERACT, Perm.CONTAINERS, Perm.STORAGE_DEPOSIT);
            case ALLY -> EnumSet.of(Perm.INTERACT);
            case VISITOR, ENEMY -> EnumSet.noneOf(Perm.class);
        };
    }

    /** Roles whose permissions a colony may edit. */
    public boolean editable() {
        return this == MANAGER || this == RESIDENT || this == ALLY || this == VISITOR;
    }

    /** Higher outranks lower (owner 4 ... visitor 0). */
    public int rank() {
        return switch (this) {
            case OWNER -> 4;
            case CO_OWNER -> 3;
            case MANAGER -> 2;
            case RESIDENT -> 1;
            default -> 0;
        };
    }

    public static Role parse(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (u) {
            case "FOUNDER", "CHAIRMAN" -> u = "OWNER";
            case "OFFICER", "COMMISSAR", "ADMIN", "COOWNER" -> u = "CO_OWNER";
            case "MEMBER", "COMRADE", "CITIZEN" -> u = "RESIDENT";
            default -> {
            }
        }
        try {
            return valueOf(u);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
