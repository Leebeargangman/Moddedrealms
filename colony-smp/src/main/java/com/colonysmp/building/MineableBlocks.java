package com.colonysmp.building;

import org.bukkit.Material;
import org.bukkit.Tag;

/** Which blocks miners may dig and which count as ore. */
public final class MineableBlocks {

    private MineableBlocks() {}

    public static boolean isOre(Material m) {
        String n = m.name();
        return n.endsWith("_ORE") || m == Material.ANCIENT_DEBRIS || m == Material.RAW_IRON_BLOCK || m == Material.RAW_COPPER_BLOCK
                || m == Material.RAW_GOLD_BLOCK || m == Material.AMETHYST_CLUSTER;
    }

    /** Natural ground a mine entrance can be registered on. */
    public static boolean natural(Material m) {
        return mineable(m) || m == Material.GRASS_BLOCK || m == Material.PODZOL || m == Material.MYCELIUM || m == Material.SNOW_BLOCK;
    }

    /** Blocks a miner digs through (never player containers, bedrock, obsidian or building blocks like planks). */
    public static boolean mineable(Material m) {
        if (isOre(m)) return true;
        if (Tag.BASE_STONE_OVERWORLD.isTagged(m) || Tag.BASE_STONE_NETHER.isTagged(m)) return true;
        if (Tag.DIRT.isTagged(m) || Tag.SAND.isTagged(m)) return true;
        return switch (m.name()) {
            case "COBBLESTONE", "MOSSY_COBBLESTONE", "COBBLED_DEEPSLATE", "GRAVEL", "CLAY", "CALCITE", "DRIPSTONE_BLOCK",
                 "POINTED_DRIPSTONE", "SANDSTONE", "RED_SANDSTONE", "TERRACOTTA", "SMOOTH_BASALT", "MAGMA_BLOCK",
                 "AMETHYST_BLOCK", "BUDDING_AMETHYST", "SNOW_BLOCK", "ICE", "PACKED_ICE", "SOUL_SAND", "SOUL_SOIL", "GLOW_LICHEN",
                 "MOSS_BLOCK", "MOSS_CARPET", "COBWEB", "DIRT_PATH", "GRASS_BLOCK", "MYCELIUM", "PODZOL", "MUD", "PACKED_MUD",
                 "SCULK", "SCULK_VEIN", "WHITE_TERRACOTTA", "ORANGE_TERRACOTTA", "YELLOW_TERRACOTTA", "BROWN_TERRACOTTA",
                 "RED_TERRACOTTA", "LIGHT_GRAY_TERRACOTTA" -> true;
            default -> false;
        };
    }

    /** Falls when the block under it is removed. */
    public static boolean gravity(Material m) {
        return m.hasGravity();
    }
}
