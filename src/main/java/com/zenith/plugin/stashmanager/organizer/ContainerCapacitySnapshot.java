package com.zenith.plugin.stashmanager.organizer;

import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;

import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.function.IntFunction;

/** Physical slots and components, without collapsing distinct stacks into item totals. */
record ContainerCapacitySnapshot(int slots, int emptySlots, List<Stack> occupied) {
    record Stack(int id, int amount, int limit, DataComponents components) {}

    static ContainerCapacitySnapshot read(int slots, IntFunction<ItemStack> items) {
        List<Stack> occupied = new ArrayList<>();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = items.apply(slot);
            if (stack == null || stack.getId() == 0 || stack.getAmount() <= 0) continue;
            occupied.add(new Stack(stack.getId(), stack.getAmount(), stackLimit(stack),
                    components(stack).clone()));
        }
        return new ContainerCapacitySnapshot(slots, slots - occupied.size(), List.copyOf(occupied));
    }

    static Optional<ContainerCapacitySnapshot> fromShulker(ItemStack shulker) {
        if (shulker == null || shulker.getAmount() <= 0) return Optional.empty();
        Object value = components(shulker).get(DataComponentTypes.CONTAINER);
        if (value == null) return Optional.of(read(27, slot -> null));
        if (!(value instanceof List<?> items) || items.size() > 27) return Optional.empty();
        List<ItemStack> stacks = new ArrayList<>();
        for (Object entry : items) {
            Object item = entry instanceof Optional<?> optional ? optional.orElse(null) : entry;
            if (item != null && !(item instanceof ItemStack)) return Optional.empty();
            stacks.add((ItemStack) item);
        }
        return Optional.of(read(27, slot -> slot < stacks.size() ? stacks.get(slot) : null));
    }

    static int stackLimit(ItemStack stack) {
        Integer override = components(stack).get(DataComponentTypes.MAX_STACK_SIZE);
        if (override != null) return Math.max(1, override);
        var data = ItemRegistry.REGISTRY.get(stack.getId());
        return data == null ? 1 : Math.max(1, data.stackSize());
    }

    int matchingHeadroom(ItemStack cargo) {
        if (cargo == null) return 0;
        return occupied.stream()
                .filter(stack -> stack.id() == cargo.getId()
                        && stack.components().equals(components(cargo)))
                .mapToInt(stack -> Math.max(0, stack.limit() - stack.amount()))
                .sum();
    }

    boolean accepts(ItemStack cargo) {
        return cargo != null && cargo.getAmount() > 0
                && (emptySlots > 0 || matchingHeadroom(cargo) > 0);
    }

    boolean atMaximumCapacity() {
        return emptySlots == 0 && occupied.stream().allMatch(stack -> stack.amount() >= stack.limit());
    }

    private static DataComponents components(ItemStack stack) {
        return stack.getDataComponents() == null ? new DataComponents(Map.of()) : stack.getDataComponents();
    }
}
