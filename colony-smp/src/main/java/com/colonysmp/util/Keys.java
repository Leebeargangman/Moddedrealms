package com.colonysmp.util;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;

/** PersistentDataContainer keys used to tag ColonySMP items and entities. */
public final class Keys {
    /** string: wand | core | book | rope | baton | war_banner | marker (also on placed Building Marker banners) */
    public static NamespacedKey ITEM;
    /** string: colony id (Town Hall Core item) */
    public static NamespacedKey COLONY;
    /** string: citizen id (NPC bodies and their mounts) */
    public static NamespacedKey CITIZEN;
    /** string: traveller id (traveller bodies) */
    public static NamespacedKey TRAVELER;
    /** long: epoch ms a downed vanilla mob bleeds out */
    public static NamespacedKey DOWNED;
    /** string: a colony good held as an item (flour, medicine...) */
    public static NamespacedKey GOOD;
    /** string: random id so menu icons never stack */
    public static NamespacedKey UNIQUE;
    /** string: the colony a horse was stabled for */
    public static NamespacedKey MOUNT;
    /** double: base damage of a shot fired by a citizen (arrows, bolts, javelins, sling stones) */
    public static NamespacedKey SHOT;
    /** string: colony id (raiders from the wilds attacking that colony) */
    public static NamespacedKey RAIDER;
    /** string: colony id (a visiting merchant and their llamas) */
    public static NamespacedKey MERCHANT;
    /** string: "colonyId:buildingId" of the pen an animal was last counted in (its keeper brings it back if it strays) */
    public static NamespacedKey PEN;
    /** string: citizen id of the keeper leading this animal to a pen (its lead never drops as an item) */
    public static NamespacedKey LED;
    /** string: "settlementId:role" on a villager of an NPC settlement (merchant, resident) */
    public static NamespacedKey CIV_NPC;

    public static NamespacedKey RECIPE_WAND, RECIPE_ROPE, RECIPE_BANNER, RECIPE_BOOK, RECIPE_BATON, RECIPE_MARKER, RECIPE_CHRISTMAS;

    private Keys() {}

    public static void init(Plugin plugin) {
        ITEM = new NamespacedKey(plugin, "item");
        COLONY = new NamespacedKey(plugin, "colony");
        CITIZEN = new NamespacedKey(plugin, "citizen");
        TRAVELER = new NamespacedKey(plugin, "traveler");
        DOWNED = new NamespacedKey(plugin, "downed");
        GOOD = new NamespacedKey(plugin, "good");
        UNIQUE = new NamespacedKey(plugin, "unique");
        MOUNT = new NamespacedKey(plugin, "mount");
        SHOT = new NamespacedKey(plugin, "shot");
        RAIDER = new NamespacedKey(plugin, "raider");
        MERCHANT = new NamespacedKey(plugin, "merchant");
        PEN = new NamespacedKey(plugin, "pen");
        LED = new NamespacedKey(plugin, "led");
        CIV_NPC = new NamespacedKey(plugin, "civ_npc");
        RECIPE_WAND = new NamespacedKey(plugin, "colony_wand");
        RECIPE_ROPE = new NamespacedKey(plugin, "rope");
        RECIPE_BANNER = new NamespacedKey(plugin, "war_banner");
        RECIPE_BOOK = new NamespacedKey(plugin, "blueprint_book");
        RECIPE_BATON = new NamespacedKey(plugin, "command_baton");
        RECIPE_MARKER = new NamespacedKey(plugin, "building_marker");
        RECIPE_CHRISTMAS = new NamespacedKey(plugin, "christmas_sapling");
    }
}
