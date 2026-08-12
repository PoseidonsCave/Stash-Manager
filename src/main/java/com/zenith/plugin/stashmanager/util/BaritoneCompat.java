package com.zenith.plugin.stashmanager.util;

import com.zenith.feature.pathfinder.Baritone;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;

import java.lang.reflect.Method;

import static com.zenith.Globals.BARITONE;

public final class BaritoneCompat {

    private static final Method PLACE_BLOCK = find("placeBlock", int.class, int.class, int.class, ItemData.class);
    private static final Method BREAK_BLOCK = find("breakBlock", int.class, int.class, int.class, boolean.class);
    private static final Method CLEAR_AREA = find("clearArea", BlockPos.class, BlockPos.class);

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

    private static Method find(String name, Class<?>... parameterTypes) {
        try {
            return Baritone.class.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
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