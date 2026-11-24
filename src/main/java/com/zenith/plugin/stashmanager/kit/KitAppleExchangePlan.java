package com.zenith.plugin.stashmanager.kit;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Plans an apple exchange from physical stacks, never from the aggregate item index.
 * Component equality is deliberately stricter than the kit item ID: it preserves name
 * capitalization and every other component Minecraft uses when deciding to stack.
 */
public final class KitAppleExchangePlan {
    private KitAppleExchangePlan() { }

    public enum Choice { EXISTING_NAMED, OTHER_NAMED, UNNAMED, PARTIAL, NONE }

    public record Plan(Choice choice, ItemStack exemplar, int retained, int toRetrieve,
                       int toRemove, int shortfall, int slotsUsed) {
        public boolean complete() { return shortfall == 0 && exemplar != null; }
        public boolean needsExchange() { return toRemove > 0; }
    }

    private static final class Group {
        private final ItemStack exemplar;
        private int inBox;
        private int available;

        private Group(ItemStack exemplar) { this.exemplar = exemplar; }
    }

    /**
     * Pick a single component-identical group for the apple slots. A different named
     * group is never described as stack-compatible even if its item ID is identical.
     * Source stacks must be observed live; an index-only estimate is not sufficient.
     */
    public static Plan choose(List<ItemStack> inBox, List<ItemStack> sources,
                              int required, int appleSlots) {
        if (required <= 0 || appleSlots <= 0) {
            return new Plan(Choice.NONE, null, 0, 0, count(inBox),
                    Math.max(0, required), 0);
        }
        List<Group> groups = new ArrayList<>();
        collect(groups, inBox, true);
        collect(groups, sources, false);
        int totalInBox = count(inBox);
        int maximum = Math.min(required, appleSlots * 64);
        Group selected = null;
        Choice choice = Choice.NONE;
        int bestFill = 0;
        int selectedRank = Integer.MAX_VALUE;
        for (Group group : groups) {
            int fill = Math.min(maximum, group.inBox + group.available);
            if (fill <= 0) continue;
            boolean named = named(group.exemplar);
            Choice kind = group.inBox > 0 && named ? Choice.EXISTING_NAMED
                    : named ? Choice.OTHER_NAMED : Choice.UNNAMED;
            if (fill == required) {
                if (selected == null || bestFill < required
                        || rank(kind) < selectedRank
                        || (rank(kind) == selectedRank && group.inBox > selected.inBox)) {
                    selected = group;
                    choice = kind;
                    bestFill = fill;
                    selectedRank = rank(kind);
                }
            } else if (bestFill < required && (fill > bestFill
                    || (fill == bestFill && (rank(kind) < selectedRank
                        || (rank(kind) == selectedRank && selected != null
                            && group.inBox > selected.inBox))))) {
                selected = group;
                choice = Choice.PARTIAL;
                bestFill = fill;
                selectedRank = rank(kind);
            }
        }
        if (selected == null) {
            return new Plan(Choice.NONE, null, 0, 0, totalInBox, required, 0);
        }
        int retained = Math.min(bestFill, selected.inBox);
        return new Plan(choice, selected.exemplar, retained,
                bestFill - retained, totalInBox - retained,
                required - bestFill, (bestFill + 63) / 64);
    }

    public static boolean samePhysicalStack(ItemStack first, ItemStack second) {
        return apple(first) && apple(second) && first.getId() == second.getId()
                && Objects.equals(first.getDataComponents(), second.getDataComponents());
    }

    /** Count actual Minecraft stack slots, not just aggregate apple item IDs. */
    public static boolean fitsPhysicalSlots(List<ItemStack> apples, int required, int slotLimit) {
        if (apples == null || apples.isEmpty() || required <= 0 || slotLimit <= 0) return false;
        List<Group> groups = new ArrayList<>();
        int total = 0;
        for (ItemStack stack : apples) {
            if (!apple(stack) || stack.getAmount() > 64) return false;
            total += stack.getAmount();
            Group group = groups.stream()
                    .filter(candidate -> samePhysicalStack(candidate.exemplar, stack))
                    .findFirst().orElse(null);
            if (group == null) {
                group = new Group(stack);
                groups.add(group);
            }
            group.available += stack.getAmount();
        }
        return total == required && groups.stream()
                .mapToInt(group -> (group.available + 63) / 64).sum() <= slotLimit;
    }

    public static boolean named(ItemStack stack) {
        if (!apple(stack)) return false;
        DataComponents components = stack.getDataComponents();
        return components != null && (components.get(DataComponentTypes.CUSTOM_NAME) != null
                || components.get(DataComponentTypes.ITEM_NAME) != null);
    }

    private static void collect(List<Group> groups, List<ItemStack> stacks, boolean box) {
        if (stacks == null) return;
        for (ItemStack stack : stacks) {
            if (!apple(stack)) continue;
            Group group = groups.stream()
                    .filter(candidate -> samePhysicalStack(candidate.exemplar, stack))
                    .findFirst().orElse(null);
            if (group == null) {
                group = new Group(stack);
                groups.add(group);
            }
            if (box) group.inBox += stack.getAmount();
            else group.available += stack.getAmount();
        }
    }

    private static int count(List<ItemStack> stacks) {
        if (stacks == null) return 0;
        return stacks.stream().filter(KitAppleExchangePlan::apple)
                .mapToInt(ItemStack::getAmount).sum();
    }

    private static boolean apple(ItemStack stack) {
        return stack != null && stack.getAmount() > 0
                && "enchanted_golden_apple".equals(ItemIdentifier.getItemId(stack));
    }

    private static int rank(Choice choice) {
        return switch (choice) {
            case EXISTING_NAMED -> 0;
            case OTHER_NAMED -> 1;
            case UNNAMED -> 2;
            default -> 3;
        };
    }
}
