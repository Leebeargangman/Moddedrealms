package com.colonysmp.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecipesTest {

    private Ledger stock;

    @BeforeEach
    void setUp() {
        TestGoods.setUp();
        stock = new Ledger("r");
    }

    @Test
    void breadNeedsFlourAndFuel() {
        Recipe bread = Recipes.get("bread");
        assertNotNull(bread);
        assertNull(Recipes.resolve(bread, stock, k -> true), "nothing in stock");
        assertNotNull(Recipes.missing(bread, stock, k -> true));
        stock.add(Goods.FLOUR, 6);
        assertEquals("fuel (coal, charcoal or wood)", Recipes.missing(bread, stock, k -> true));
        stock.add("coal", 1);
        Map<String, Long> goods = Recipes.resolve(bread, stock, k -> true);
        assertEquals(Map.of(Goods.FLOUR, 6L, "coal", 1L), goods);
    }

    @Test
    void fuelPrefersCoalAndFallsBackToWood() {
        stock.add("raw_iron", 8);
        stock.add("oak_planks", 64);
        Map<String, Long> goods = Recipes.resolve(Recipes.get("smelt_iron"), stock, k -> true);
        assertNotNull(goods);
        assertEquals(6, goods.get("oak_planks"), "8 fuel units = 6 planks at 1.5 each");
        stock.add("coal", 1);
        goods = Recipes.resolve(Recipes.get("smelt_iron"), stock, k -> true);
        assertEquals(1, goods.get("coal"));
        assertNull(goods.get("oak_planks"));
    }

    @Test
    void groupsUseWhateverMembersThereAre() {
        stock.add("cooked_beef", 1);
        stock.add("cooked_cod", 1);
        stock.add("carrot", 2);
        stock.add("bread", 1);
        Map<String, Long> goods = Recipes.resolve(Recipes.get("hearty_meal"), stock, k -> true);
        assertNotNull(goods);
        assertEquals(1, goods.get("cooked_beef"));
        assertEquals(1, goods.get("cooked_cod"));
        assertEquals(2, goods.get("carrot"));
    }

    @Test
    void reservedInputsCantBeUsedByASecondBatch() {
        stock.add(Goods.FLOUR, 6);
        stock.add("coal", 2);
        Map<String, Long> first = Recipes.resolve(Recipes.get("bread"), stock, k -> true);
        assertNotNull(stock.reserve("bakery", first, 0));
        assertNull(Recipes.resolve(Recipes.get("bread"), stock, k -> true), "the flour is already promised");
    }

    @Test
    void heldBackGoodsAreNotUsed() {
        stock.add("wheat", 64);
        stock.add("coal", 4);
        assertNull(Recipes.resolve(Recipes.get("bread_wheat"), stock, k -> !k.equals("wheat")));
    }

    @Test
    void everyRecipeHasAnOutputAndPositiveWork() {
        for (Recipe r : Recipes.all()) {
            assertFalse(r.outputs.isEmpty(), r.id);
            assertTrue(r.hours > 0, r.id);
            assertFalse(r.inputs.isEmpty(), r.id);
        }
    }
}
