package net.moddedrealms.smp.town;

import java.util.Map;
import org.bukkit.Material;

/** The goods that move between businesses, and the kinds of business that make and sell them. */
final class Econ {
    private Econ() {
    }

    enum Good {
        WHEAT("Wheat", Material.WHEAT, 5, false),
        WOOL("Wool", Material.WHITE_WOOL, 8, false),
        PLANKS("Planks", Material.OAK_PLANKS, 6, false),
        BREAD("Bread", Material.BREAD, 15, true),
        ALE("Ale", Material.HONEY_BOTTLE, 20, true),
        CLOTH("Cloth", Material.WHITE_CARPET, 25, false),
        BEDS("Beds", Material.RED_BED, 120, false),
        MEALS("Meals", Material.RABBIT_STEW, 60, true),
        GOODS("Household goods", Material.CHEST, 40, true),
        STAYS("Hotel stays", Material.WHITE_BED, 250, true),
        MOONSHINE("Moonshine", Material.POTION, 120, true);

        final String label;
        final Material icon;
        /** Reference price per unit (what the town considers fair). */
        final double ref;
        /** Bought by townspeople (as opposed to only by other businesses). */
        final boolean consumer;

        Good(String label, Material icon, double ref, boolean consumer) {
            this.label = label;
            this.icon = icon;
            this.ref = ref;
            this.consumer = consumer;
        }
    }

    enum Kind {
        FARM("Farm", Map.of(), Good.WHEAT, 40, 15000, true, Material.COMPOSTER),
        WOOL_MILL("Wool mill", Map.of(), Good.WOOL, 24, 15000, true, Material.LOOM),
        LUMBER_MILL("Lumber mill", Map.of(), Good.PLANKS, 24, 15000, true, Material.STONECUTTER),
        BAKERY("Bakery", Map.of(Good.WHEAT, 1.0), Good.BREAD, 10, 20000, true, Material.SMOKER),
        TAVERN("Tavern", Map.of(Good.WHEAT, 1.0), Good.ALE, 10, 20000, true, Material.BARREL),
        TAILOR("Tailor", Map.of(Good.WOOL, 2.0), Good.CLOTH, 6, 25000, true, Material.LOOM),
        FURNITURE("Furniture workshop", Map.of(Good.PLANKS, 2.0, Good.WOOL, 1.0), Good.BEDS, 4, 30000, true, Material.CRAFTING_TABLE),
        RESTAURANT("Restaurant", Map.of(Good.BREAD, 1.0, Good.WHEAT, 1.0), Good.MEALS, 6, 30000, true, Material.SMOKER),
        STORE("General store", Map.of(Good.CLOTH, 1.0, Good.PLANKS, 1.0), Good.GOODS, 8, 25000, true, Material.BARREL),
        HOTEL("Hotel", Map.of(Good.MEALS, 1.0, Good.CLOTH, 0.5, Good.BEDS, 0.2), Good.STAYS, 6, 50000, true, Material.WHITE_BED),
        SPEAKEASY("Speakeasy", Map.of(Good.WHEAT, 2.0), Good.MOONSHINE, 5, 20000, false, Material.BREWING_STAND);

        final String label;
        final Map<Good, Double> inputs;
        final Good output;
        /** Units made per cycle at level 1. */
        final int capacity;
        final double startCost;
        final boolean legal;
        /** The block that counts as a workstation for player shifts. */
        final Material station;

        Kind(String label, Map<Good, Double> inputs, Good output, int capacity, double startCost, boolean legal, Material station) {
            this.label = label;
            this.inputs = inputs;
            this.output = output;
            this.capacity = capacity;
            this.startCost = startCost;
            this.legal = legal;
            this.station = station;
        }
    }
}
