package com.zenith.plugin.stashmanager.util;

import com.zenith.feature.pathfinder.Baritone;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.BOT;

public final class BaritoneCompat {

    private static final Method PLACE_BLOCK = find("placeBlock", int.class, int.class, int.class, ItemData.class);
    private static final Method BREAK_BLOCK = find("breakBlock", int.class, int.class, int.class, boolean.class);
    private static final Method CLEAR_AREA = find("clearArea", BlockPos.class, BlockPos.class);
    private static final Method STOP_DESTROY_BLOCK = findDeclared(
            BOT.getInteractions().getClass(), "stopDestroyBlock");
    private static final Field DESTROY_DELAY = findField(
            BOT.getInteractions().getClass(), "destroyDelay");
    private static final Field WAS_LEFT_CLICKING = findField(BOT.getClass(), "wasLeftClicking");

    private BaritoneCompat() {}

    public static PathingRequestFuture placeBlock(int x, int y, int z, ItemData item) {
        return invoke(PLACE_BLOCK, x, y, z, item);
    }

    public static PathingRequestFuture breakBlock(int x, int y, int z, boolean maintainY) {
        return invoke(BREAK_BLOCK, x, y, z, maintainY);
    }

    public static PathingRequestFuture clearArea(BlockPos from, BlockPos to) {
        return invoke(CLEAR_AREA, from, to);
    }

    /** Clears Zenith's retained destroy target/delay before reusing a shulker worksite. */
    public static boolean resetBlockBreakingState() {
        if (STOP_DESTROY_BLOCK == null || DESTROY_DELAY == null || WAS_LEFT_CLICKING == null) {
            return false;
        }
        try {
            Object interactions = BOT.getInteractions();
            STOP_DESTROY_BLOCK.invoke(interactions);
            DESTROY_DELAY.setInt(interactions, 0);
            WAS_LEFT_CLICKING.setBoolean(BOT, false);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static Method find(String name, Class<?>... parameterTypes) {
        try {
            return Baritone.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Method findDeclared(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            Method method = owner.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static PathingRequestFuture invoke(Method method, Object... arguments) {
        if (method == null) return PathingRequestFuture.rejected;
        try {
            Object result = method.invoke(BARITONE, arguments);
            return result instanceof PathingRequestFuture future ? future : PathingRequestFuture.rejected;
        } catch (ReflectiveOperationException ignored) {
            return PathingRequestFuture.rejected;
        }
    }
}
