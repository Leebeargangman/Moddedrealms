package com.colonysmp.core;

/**
 * A kind of goods the colony can store, produce and trade.
 *
 * @param key       lower-case id: the vanilla material name ("wheat") or a colony good ("flour")
 * @param name      shown to players
 * @param stack     items per storage slot
 * @param value     base market price of one item
 * @param nutrition hunger points one item restores (0 for things nobody eats)
 * @param quality   how good a meal it makes: 0 raw, 1 simple, 2 cooked or baked, 3 a proper meal
 * @param material  the item it is held as in the world (for colony goods: the base item it is tagged on)
 * @param custom    a colony good that only exists as a tagged item (flour, medicine...)
 */
public record Good(String key, String name, Category category, int stack, double value, double nutrition,
                   int quality, String material, boolean custom) {

    public boolean edible() {
        return nutrition > 0;
    }

    public boolean granary() {
        return category.granary;
    }
}
