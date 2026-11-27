package com.colonysmp.model;

/** Something a player may or may not do in or for a colony. */
public enum Perm {
    BUILD("Build and break blocks"),
    INTERACT("Use doors, gates, buttons and levers"),
    CONTAINERS("Open chests and other containers"),
    STORAGE_DEPOSIT("Put goods into colony storage"),
    STORAGE_WITHDRAW("Take goods out of colony storage"),
    MANAGE_BUILDINGS("Register, upgrade and remove buildings; order construction"),
    MANAGE_JOBS("Assign jobs and workplaces"),
    MANAGE_CITIZENS("Recruit, house, release and exile citizens"),
    POLICIES("Change settlement policies and stock targets"),
    TREASURY("Spend from and withdraw the treasury"),
    TRADE("Trade with the market and other colonies"),
    MILITARY("Recruit soldiers and command squads"),
    DIPLOMACY("Propose and accept treaties, declare war and make peace"),
    MEMBERS("Invite and remove members"),
    ROLES("Promote and demote members, edit permissions"),
    TERRITORY("Claim and release land"),
    SETTINGS("Rename the colony and change its settings");

    public final String description;

    Perm(String description) {
        this.description = description;
    }
}
