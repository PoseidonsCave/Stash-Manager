package com.zenith.plugin.stashmanager.organizer;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.mc.item.ItemRegistry;
import it.unimi.dsi.fastutil.ints.Int2ObjectArrayMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftPacket;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Objects;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CLIENT_LOG;
import static com.zenith.Globals.CONFIG;

/** Places one ordinary item from the cursor with an exact client prediction. */
final class PlaceOneFromCursor implements InventoryAction {
    private final int containerId;
    private final int slotId;

    PlaceOneFromCursor(int containerId, int slotId) {
        this.containerId = containerId;
        this.slotId = slotId;
    }

    @Override
    public int containerId() {
        return containerId;
    }

    @Override
    public MinecraftPacket packet() {
        Container container = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
        ItemStack cursor = CACHE.getPlayerCache().getInventoryCache().getMouseStack();
        if (container == null || isEmpty(cursor) || slotId < 0 || slotId >= container.getSize()) {
            return null;
        }

        ItemStack target = container.getItemStack(slotId);
        if (!isEmpty(target) && (target.getId() != cursor.getId()
                || !Objects.equals(target.getDataComponents(), cursor.getDataComponents()))) {
            return null;
        }
        int targetAmount = isEmpty(target) ? 0 : target.getAmount();
        int maxStack = Math.max(1, ItemRegistry.REGISTRY.get(cursor.getId()).stackSize());
        if (targetAmount >= maxStack) return null;

        ItemStack placed = new ItemStack(
                cursor.getId(), targetAmount + 1, cursor.getDataComponents());
        ItemStack remaining;
        if (cursor.getAmount() == 1) {
            remaining = Container.EMPTY_STACK;
        } else {
            remaining = new ItemStack(cursor.getId(), cursor.getAmount() - 1,
                    cursor.getDataComponents());
        }
        return createPacket(placed, remaining);
    }

    /** The protocol switched ItemStack predictions to HashedStack after 1.21.8. */
    private MinecraftPacket createPacket(ItemStack placed, ItemStack remaining) {
        try {
            Class<?> packetType = Class.forName(
                    "org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket");
            Constructor<?> packetConstructor = findClickPacketConstructor(packetType);
            if (packetConstructor == null) return null;

            Class<?> predictionType = packetConstructor.getParameterTypes()[5];
            Object predictedCursor = encodePrediction(remaining, predictionType);
            Int2ObjectMap<Object> changed = new Int2ObjectArrayMap<>();
            changed.put(slotId, encodePrediction(placed, predictionType));
            return (MinecraftPacket) packetConstructor.newInstance(
                    containerId,
                    predictedStateId(),
                    slotId,
                    ContainerActionType.CLICK_ITEM,
                    ClickItemAction.RIGHT_CLICK,
                    predictedCursor,
                    changed);
        } catch (ReflectiveOperationException | RuntimeException e) {
            CLIENT_LOG.warn("Could not build exact right-click inventory packet", e);
            return null;
        }
    }

    static Constructor<?> findClickPacketConstructor(Class<?> packetType) {
        if (packetType == null) return null;
        for (Constructor<?> candidate : packetType.getConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length == 7
                    && parameters[3] == ContainerActionType.class
                    && parameters[4].isAssignableFrom(ClickItemAction.class)) {
                return candidate;
            }
        }
        return null;
    }

    private static Object encodePrediction(ItemStack stack, Class<?> predictionType)
            throws ReflectiveOperationException {
        if (predictionType.isInstance(stack)) return stack;
        Class<?> hasherType = Class.forName("com.zenith.mc.item.hashing.ItemStackHasher");
        Method hash = hasherType.getMethod("hash", ItemStack.class);
        return hash.invoke(null, stack);
    }

    private static int predictedStateId() {
        int stateId = CACHE.getPlayerCache().getActionId().get();
        try {
            Field syncField = CONFIG.debug.getClass()
                    .getField("inventoryRequestServerSyncOnAction");
            if (syncField.getBoolean(CONFIG.debug)) stateId++;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Newer Zenith versions no longer expose this compatibility flag.
        }
        return stateId;
    }

    private static boolean isEmpty(ItemStack stack) {
        return stack == null || stack == Container.EMPTY_STACK || stack.getAmount() <= 0;
    }
}
