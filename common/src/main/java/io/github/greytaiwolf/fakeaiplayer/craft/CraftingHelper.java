package io.github.greytaiwolf.fakeaiplayer.craft;

import io.github.greytaiwolf.fakeaiplayer.entity.AIPlayerEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class CraftingHelper {
    private CraftingHelper() {
    }

    public record CraftStep(RecipeRegistry.Recipe recipe, int crafts) {
        public int outputCount() {
            return recipe.outputCount() * crafts;
        }
    }

    public record Missing(Item item, int count) {
        public String describe() {
            return BuiltInRegistries.ITEM.getKey(item) + " x" + count;
        }
    }

    public record CraftPlan(Item target, int targetCount, List<CraftStep> steps, List<Missing> missing, boolean needsCraftingTable) {
        public boolean success() {
            return missing.isEmpty();
        }

        public String missingDescription() {
            if (missing.isEmpty()) {
                return "";
            }
            List<String> parts = new ArrayList<>();
            for (Missing item : missing) {
                parts.add(item.describe());
            }
            return String.join(", ", parts);
        }
    }

    public static CraftPlan plan(AIPlayerEntity bot, Item target, int targetCount) {
        Planner planner = new Planner(inventoryCounts(bot), target, Math.max(1, targetCount));
        planner.ensureItem(target, Math.max(1, targetCount), new HashSet<>());
        return new CraftPlan(target, Math.max(1, targetCount), List.copyOf(planner.steps), List.copyOf(planner.missing), planner.needsCraftingTable);
    }

    private static Map<Item, Integer> inventoryCounts(AIPlayerEntity bot) {
        Map<Item, Integer> counts = new HashMap<>();
        for (ItemStack stack : bot.getInventory().items) {
            add(counts, stack);
        }
        for (ItemStack stack : bot.getInventory().offhand) {
            add(counts, stack);
        }
        return counts;
    }

    private static void add(Map<Item, Integer> counts, ItemStack stack) {
        if (!stack.isEmpty()) {
            counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }

    private static final class Planner {
        private final Map<Item, Integer> counts;
        private final Item rootTarget;
        private final int rootTargetCount;
        private final List<CraftStep> steps = new ArrayList<>();
        private final List<Missing> missing = new ArrayList<>();
        private boolean needsCraftingTable;

        private Planner(Map<Item, Integer> counts, Item rootTarget, int rootTargetCount) {
            this.counts = counts;
            this.rootTarget = rootTarget;
            this.rootTargetCount = rootTargetCount;
        }

        private boolean ensureItem(Item item, int desiredCount, Set<Item> stack) {
            int available = counts.getOrDefault(item, 0);
            if (available >= desiredCount) {
                return true;
            }
            if (stack.contains(item)) {
                addMissing(item, desiredCount - available);
                return false;
            }
            RecipeRegistry.Recipe recipe = RecipeRegistry.find(item).orElse(null);
            if (recipe == null) {
                addMissing(item, desiredCount - available);
                return false;
            }

            int missingCount = desiredCount - available;
            int crafts = divideRoundUp(missingCount, recipe.outputCount());
            stack.add(item);
            for (RecipeRegistry.Ingredient ingredient : recipe.ingredients()) {
                int need = ingredient.count() * crafts;
                if (!ensureIngredient(ingredient, need, stack)) {
                    stack.remove(item);
                    return false;
                }
                consume(ingredient, need);
            }
            counts.merge(item, recipe.outputCount() * crafts, Integer::sum);
            steps.add(new CraftStep(recipe, crafts));
            needsCraftingTable = needsCraftingTable || recipe.needsCraftingTable();
            stack.remove(item);
            return true;
        }

        private boolean ensureIngredient(RecipeRegistry.Ingredient ingredient, int count, Set<Item> stack) {
            int total = total(ingredient.anyOf());
            if (total >= count) {
                return true;
            }

            int deficit = count - total;
            for (Item candidate : ingredient.anyOf()) {
                if (RecipeRegistry.find(candidate).isEmpty()) {
                    continue;
                }
                Map<Item, Integer> countsBackup = new HashMap<>(counts);
                int stepCount = steps.size();
                int missingCount = missing.size();
                boolean tableBackup = needsCraftingTable;
                if (ensureItem(candidate, counts.getOrDefault(candidate, 0) + deficit, stack) && total(ingredient.anyOf()) >= count) {
                    return true;
                }
                counts.clear();
                counts.putAll(countsBackup);
                while (steps.size() > stepCount) {
                    steps.remove(steps.size() - 1);
                }
                while (missing.size() > missingCount) {
                    missing.remove(missing.size() - 1);
                }
                needsCraftingTable = tableBackup;
            }

            Item representative = ingredient.anyOf().isEmpty() ? rootTarget : ingredient.anyOf().get(0);
            addMissing(representative, deficit);
            return false;
        }

        private void consume(RecipeRegistry.Ingredient ingredient, int count) {
            int remaining = count;
            for (Item item : ingredient.anyOf()) {
                if (remaining <= 0) {
                    return;
                }
                int available = counts.getOrDefault(item, 0);
                int take = Math.min(available, remaining);
                if (take > 0) {
                    counts.put(item, available - take);
                    remaining -= take;
                }
            }
        }

        private int total(List<Item> items) {
            int total = 0;
            for (Item item : items) {
                total += counts.getOrDefault(item, 0);
            }
            return total;
        }

        private void addMissing(Item item, int count) {
            if (item == rootTarget && counts.getOrDefault(item, 0) >= rootTargetCount) {
                return;
            }
            missing.add(new Missing(item, Math.max(1, count)));
        }

        private static int divideRoundUp(int value, int divisor) {
            return (value + divisor - 1) / divisor;
        }
    }
}
