package com.colonysmp.model;

import org.bukkit.Material;
import org.bukkit.Tag;

import java.util.function.Predicate;

/** Blocks a building must contain to work: beds, doors, workstations, seats... */
public enum Feature {
    BED("bed", m -> Tag.BEDS.isTagged(m)),
    DOOR("wooden door", m -> Tag.WOODEN_DOORS.isTagged(m)),
    IRON_DOOR("iron door", m -> m == Material.IRON_DOOR),
    CONTAINER("chest or barrel", m -> m == Material.CHEST || m == Material.BARREL || m == Material.TRAPPED_CHEST),
    BARREL("barrel", m -> m == Material.BARREL),
    FURNACE("furnace", m -> m == Material.FURNACE || m == Material.BLAST_FURNACE),
    SMOKER("smoker", m -> m == Material.SMOKER),
    COOKER("smoker, furnace or campfire", m -> m == Material.SMOKER || m == Material.FURNACE || m == Material.CAMPFIRE || m == Material.SOUL_CAMPFIRE),
    ANVIL("anvil", m -> Tag.ANVIL.isTagged(m)),
    GRINDSTONE("grindstone", m -> m == Material.GRINDSTONE),
    CRAFTING_TABLE("crafting table", m -> m == Material.CRAFTING_TABLE),
    STONECUTTER("stonecutter", m -> m == Material.STONECUTTER),
    FLETCHING_TABLE("fletching table", m -> m == Material.FLETCHING_TABLE),
    SMITHING_TABLE("smithing table", m -> m == Material.SMITHING_TABLE),
    CARTOGRAPHY_TABLE("cartography table", m -> m == Material.CARTOGRAPHY_TABLE),
    LECTERN("lectern", m -> m == Material.LECTERN),
    BREWING_STAND("brewing stand", m -> m == Material.BREWING_STAND),
    BOOKSHELF("bookshelf", m -> m == Material.BOOKSHELF || m == Material.CHISELED_BOOKSHELF),
    SEAT("seat (stairs)", m -> Tag.STAIRS.isTagged(m)),
    TARGET("target block", m -> m == Material.TARGET),
    HAY("hay bale", m -> m == Material.HAY_BLOCK),
    COMPOSTER("composter", m -> m == Material.COMPOSTER),
    LOOM("loom", m -> m == Material.LOOM),
    BELL("bell", m -> m == Material.BELL),
    ENCHANTING_TABLE("enchanting table", m -> m == Material.ENCHANTING_TABLE),
    MUSIC("note block or jukebox", m -> m == Material.NOTE_BLOCK || m == Material.JUKEBOX),
    BEEHIVE("beehive or bee nest", m -> m == Material.BEEHIVE || m == Material.BEE_NEST),
    STANDING_STONE("mossy stone (mossy cobblestone or mossy stone bricks)", m -> m == Material.MOSSY_COBBLESTONE || m == Material.MOSSY_STONE_BRICKS),
    ARMOR_HOLDER("chest, barrel or smithing table", m -> m == Material.CHEST || m == Material.BARREL || m == Material.SMITHING_TABLE);

    public final String display;
    private final Predicate<Material> test;

    Feature(String display, Predicate<Material> test) {
        this.display = display;
        this.test = test;
    }

    public boolean matches(Material m) {
        return test.test(m);
    }

    /** Blocks NPCs stand at to work (the first required feature that is one of these is the workstation). */
    public boolean station() {
        return switch (this) {
            case FURNACE, SMOKER, COOKER, ANVIL, GRINDSTONE, CRAFTING_TABLE, STONECUTTER, FLETCHING_TABLE, SMITHING_TABLE,
                 CARTOGRAPHY_TABLE, LECTERN, BREWING_STAND, BARREL, CONTAINER, TARGET, HAY, BELL, LOOM, COMPOSTER,
                 ENCHANTING_TABLE, MUSIC, BEEHIVE, STANDING_STONE -> true;
            default -> false;
        };
    }
}
