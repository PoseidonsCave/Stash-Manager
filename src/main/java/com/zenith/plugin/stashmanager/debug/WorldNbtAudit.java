package com.zenith.plugin.stashmanager.debug;

import com.zenith.cache.data.chunk.Chunk;
import com.zenith.feature.player.World;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.zenith.Globals.CACHE;

/** A passive snapshot of only the world, entity, and inventory data already cached by Zenith. */
public final class WorldNbtAudit {
    private WorldNbtAudit() {}

    public record Report(int loadedChunks, int checkedChunks, int placedShulkers,
                         int droppedItems, int inventoryShulkers, String text) {
        public String summary() {
            return "Loaded " + loadedChunks + "/" + checkedChunks + " checked chunks; "
                    + placedShulkers + " placed shulker(s), " + droppedItems
                    + " relevant dropped item(s), " + inventoryShulkers
                    + " shulker(s) in bot inventory. This is a cache snapshot, not a server-wide "
                    + "search or proof of chest contents.";
        }
    }

    /** Radius is in chunks, centered on the bot; this never paths, opens, clicks, or indexes. */
    public static Report capture(int radiusChunks) {
        if (radiusChunks < 1 || radiusChunks > 12) {
            throw new IllegalArgumentException("radiusChunks must be 1..12");
        }
        var player = CACHE.getPlayerCache();
        int centerChunkX = ((int) Math.floor(player.getX())) >> 4;
        int centerChunkZ = ((int) Math.floor(player.getZ())) >> 4;
        int loaded = 0;
        int placed = 0;
        int dropped = 0;
        int inventory = 0;
        List<String> findings = new ArrayList<>();
        int diameter = radiusChunks * 2 + 1;
        int checked = diameter * diameter;

        for (int cx = centerChunkX - radiusChunks; cx <= centerChunkX + radiusChunks; cx++) {
            for (int cz = centerChunkZ - radiusChunks; cz <= centerChunkZ + radiusChunks; cz++) {
                Chunk chunk = CACHE.getChunkCache().get(cx, cz);
                if (chunk == null) continue;
                loaded++;
                synchronized (chunk.getBlockEntities()) {
                    for (var be : chunk.getBlockEntities()) {
                        if (be.getType() != BlockEntityType.SHULKER_BOX) continue;
                        int x = cx * 16 + be.getX();
                        int y = be.getY();
                        int z = cz * 16 + be.getZ();
                        String blockName = World.getBlock(x, y, z).name();
                        findings.add("placed " + blockName + " @ " + x + " " + y + " " + z
                                + " block_entity_nbt=" + (be.getNbt() == null ? "absent" : "present")
                                + " contents=not_verified");
                        placed++;
                    }
                }
            }
        }

        // Only the server's currently tracked item entities are visible. A missing drop may
        // already have been collected, unloaded, or despawned; it is not loss evidence.
        for (var entity : List.copyOf(CACHE.getEntityCache().getEntities().values())) {
            if (entity == null || entity.isRemoved() || entity.getEntityType() != EntityType.ITEM) continue;
            var metadata = entity.getMetadata().get(8);
            if (metadata == null || !(metadata.getValue() instanceof ItemStack stack)
                    || stack.getAmount() <= 0) continue;
            String itemId = ItemIdentifier.getItemId(stack);
            if (!isRelevantItem(itemId)) continue;
            findings.add("dropped " + describe(stack) + " @ "
                    + (int) Math.floor(entity.getX()) + " "
                    + (int) Math.floor(entity.getY()) + " "
                    + (int) Math.floor(entity.getZ()));
            dropped++;
        }

        var playerInventory = player.getInventoryCache().getPlayerInventory();
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerInventory.getItemStack(slot);
            if (stack == null || stack.getAmount() <= 0) continue;
            String itemId = ItemIdentifier.getItemId(stack);
            if (!itemId.contains("shulker_box")) continue;
            findings.add("inventory raw=" + slot + " " + describe(stack));
            inventory++;
        }

        StringBuilder text = new StringBuilder(4096);
        text.append("Passive Zenith cache audit\n")
                .append("Bot: ").append(player.getX()).append(' ')
                .append(player.getY()).append(' ').append(player.getZ()).append('\n')
                .append("Chunk radius: ").append(radiusChunks).append("; loaded: ")
                .append(loaded).append('/').append(checked).append("\n")
                .append("Scope: placed shulker blocks, tracked dropped shulkers/carrots/apples, ")
                .append("and bot inventory shulkers. Unopened container inventories are NOT known.\n")
                .append("No movement, GUI open, click, index write, or checkpoint change was made.\n");
        if (findings.isEmpty()) text.append("No matching objects in the cached scope.\n");
        else findings.forEach(line -> text.append(line).append('\n'));
        return new Report(loaded, checked, placed, dropped, inventory, text.toString());
    }

    static boolean isRelevantItem(String itemId) {
        return itemId != null && (itemId.contains("shulker_box")
                || itemId.equals("golden_carrot")
                || itemId.equals("enchanted_golden_apple"));
    }

    private static String describe(ItemStack stack) {
        String itemId = ItemIdentifier.getItemId(stack);
        if (!itemId.contains("shulker_box")) return itemId + " x" + stack.getAmount();
        Map<String, Integer> contents = ItemIdentifier.readShulkerContents(stack);
        return itemId + " x" + stack.getAmount() + " contents=" + contents;
    }
}
