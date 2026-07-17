package io.github.greytaiwolf.fakeaiplayer.craft;

import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecipeRegistryCatalogWoodPartsTest {
    @Test
    void catalogWoodStylesHaveDeterministicDoorAndStairRecipes() {
        assertWoodParts(Items.BIRCH_PLANKS, Items.BIRCH_DOOR, Items.BIRCH_STAIRS);
        assertWoodParts(Items.DARK_OAK_PLANKS, Items.DARK_OAK_DOOR, Items.DARK_OAK_STAIRS);
    }

    private static void assertWoodParts(Item planks, Item door, Item stairs) {
        RecipeRegistry.Recipe doorRecipe = RecipeRegistry.find(door).orElseThrow();
        assertEquals(3, doorRecipe.outputCount());
        assertEquals(List.of(planks), doorRecipe.ingredients().getFirst().anyOf());
        assertEquals(6, doorRecipe.ingredients().getFirst().count());

        RecipeRegistry.Recipe stairRecipe = RecipeRegistry.find(stairs).orElseThrow();
        assertEquals(4, stairRecipe.outputCount());
        assertEquals(List.of(planks), stairRecipe.ingredients().getFirst().anyOf());
        assertEquals(6, stairRecipe.ingredients().getFirst().count());
    }
}
