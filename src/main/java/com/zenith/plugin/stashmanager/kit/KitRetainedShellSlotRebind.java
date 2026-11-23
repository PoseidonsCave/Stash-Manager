package com.zenith.plugin.stashmanager.kit;

import com.zenith.cache.data.inventory.Container;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.zenith.Globals.CACHE;

/** Follow a uniquely identified reserved empty shell across inventory slot shuffles. */
public final class KitRetainedShellSlotRebind {
    private KitRetainedShellSlotRebind() { }

    /** An ambiguous or unavailable observation leaves the old witness unchanged and fail closed. */
    public static KitFulfillmentJournalStore.RetainedShell rebindIfUnique(
            KitFulfillmentJournalStore.RetainedShell expected, int ownedShellSlot) {
        if (expected == null || ownedShellSlot < 9 || ownedShellSlot > 44) return expected;
        var playerCache = CACHE.getPlayerCache();
        if (playerCache == null || playerCache.getInventoryCache() == null) return expected;
        Container player = playerCache.getInventoryCache().getPlayerInventory();
        if (player == null) return expected;
        Map<Integer, ItemStack> live = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) live.put(raw, player.getItemStack(raw));
        return rebindIfUnique(expected, ownedShellSlot, live);
    }

    /** Pure inventory witness check, shared with the live rebinding path. */
    public static KitFulfillmentJournalStore.RetainedShell rebindIfUnique(
            KitFulfillmentJournalStore.RetainedShell expected, int ownedShellSlot,
            Map<Integer, ItemStack> live) {
        if (expected == null || live == null) return expected;
        int slot = KitRetainedShell.uniqueSlot(expected, live, ownedShellSlot);
        return slot < 9 || slot == expected.rawSlot() ? expected
                : new KitFulfillmentJournalStore.RetainedShell(slot, expected.itemId(),
                        expected.componentsHash());
    }

    /** Keep the journaled slot when an identical empty borrowed shell is also held,
     * but only if the complete carried-box multiset accounts for every duplicate. */
    public static int accountedSlot(KitFulfillmentJournalStore.RetainedShell expected,
            int ownedShellSlot, Map<String, Integer> packedContents,
            List<KitFulfillmentJournalStore.BorrowedBox> pending,
            Map<Integer, ItemStack> live) {
        int unique = KitRetainedShell.uniqueSlot(expected, live, ownedShellSlot);
        if (pending == null || pending.isEmpty()) return unique;
        if (expected == null || packedContents == null || live == null)
            return -1;
        int retained = unique >= 9 ? unique : expected.rawSlot();
        if (retained == ownedShellSlot) return -1;
        var witnessed = KitRetainedShell.evidence(retained, live.get(retained));
        if (witnessed == null || !expected.itemId().equals(witnessed.itemId())
                || !expected.componentsHash().equals(witnessed.componentsHash())) return -1;
        Map<Integer, KitShulkerLedger.Identity> boxes = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = live.get(raw);
            if (stack == null || stack.getAmount() <= 0) continue;
            String itemId = ItemIdentifier.getItemId(stack);
            if (itemId == null || !ItemIdentifier.baseItemId(itemId)
                    .endsWith("shulker_box")) continue;
            if (stack.getAmount() != 1) return -1;
            boxes.put(raw, new KitShulkerLedger.Identity(
                    itemId, ItemIdentifier.readShulkerContents(stack)));
        }
        var owned = boxes.get(ownedShellSlot);
        if (owned == null || !packedContents.equals(owned.contents())
                || boxes.remove(retained) == null) return -1;
        if (pending.stream().anyMatch(source -> source == null
                || source.itemId() == null || source.contents() == null)) return -1;
        var sources = pending.stream().map(source -> new KitShulkerLedger.Identity(
                source.itemId(), source.contents())).toList();
        return KitShulkerLedger.pendingReturnSlots(boxes, ownedShellSlot, owned, sources)
                .size() == pending.size() ? retained : -1;
    }
}
