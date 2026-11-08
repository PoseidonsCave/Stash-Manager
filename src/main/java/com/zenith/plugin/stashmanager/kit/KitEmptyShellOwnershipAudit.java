package com.zenith.plugin.stashmanager.kit;

import com.zenith.cache.data.inventory.Container;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Explicit operator assignment of two otherwise indistinguishable empty boxes. */
public final class KitEmptyShellOwnershipAudit {
    private static final String PREFIX = "resume_audited_empty_shell_ownership:";

    private KitEmptyShellOwnershipAudit() { }

    public static boolean canAudit(KitFulfillmentJournalStore.Snapshot snapshot) {
        if (snapshot == null || !"BLOCKED".equals(snapshot.state())
                || !"kit_owned_shell_identity_ambiguous".equals(snapshot.blocker())
                || snapshot.resumeState() != null || !snapshot.packedContents().isEmpty()
                || snapshot.kitWorksite() != null || snapshot.borrowedSourceWorksite() != null
                || snapshot.retainedShell() != null || snapshot.currentItem() == null
                || snapshot.currentQuantity() <= 0 || snapshot.auditedCargoSlot() >= 0
                || !snapshot.auditedCargoSlots().isEmpty()
                || snapshot.sourceBoxesToReturn().size() != 2) return false;
        long empty = snapshot.sourceBoxesToReturn().stream()
                .filter(source -> source != null && source.contents().isEmpty()).count();
        return empty == 1;
    }

    public static String marker(int ownedRaw, int borrowedRaw,
                                ItemStack owned, ItemStack borrowed) {
        if (ownedRaw < 9 || ownedRaw > 44 || borrowedRaw < 9 || borrowedRaw > 44
                || ownedRaw == borrowedRaw || owned == null || borrowed == null) return null;
        String ownedHash = KitVariantFingerprint.canonicalVariant(owned);
        String borrowedHash = KitVariantFingerprint.canonicalVariant(borrowed);
        if (ownedHash == null || borrowedHash == null || ownedHash.equals(borrowedHash)) return null;
        return PREFIX + ownedRaw + ":" + borrowedRaw + ":" + ownedHash + ":" + borrowedHash;
    }

    public static boolean isScheduled(String blocker) {
        return blocker != null && blocker.startsWith(PREFIX);
    }

    /** Recheck all three carried boxes and both physical fingerprints after cooldown. */
    public static boolean verify(KitFulfillmentJournalStore.Snapshot snapshot, Container player) {
        if (snapshot == null || player == null || !isScheduled(snapshot.blocker())
                || !"BLOCKED".equals(snapshot.state())
                || !"WAIT_CARGO".equals(snapshot.resumeState())
                || !snapshot.packedContents().isEmpty()
                || snapshot.kitWorksite() != null || snapshot.borrowedSourceWorksite() != null
                || snapshot.retainedShell() != null || snapshot.sourceBoxesToReturn().size() != 2)
            return false;
        String[] parts = snapshot.blocker().substring(PREFIX.length()).split(":", -1);
        if (parts.length != 4) return false;
        final int ownedRaw;
        final int borrowedRaw;
        try {
            ownedRaw = Integer.parseInt(parts[0]);
            borrowedRaw = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return false;
        }
        if (ownedRaw < 9 || ownedRaw > 44 || borrowedRaw < 9 || borrowedRaw > 44
                || ownedRaw == borrowedRaw || snapshot.ownedShellSlot() != ownedRaw)
            return false;
        ItemStack owned = player.getItemStack(ownedRaw);
        ItemStack borrowed = player.getItemStack(borrowedRaw);
        if (owned == null || borrowed == null || owned.getAmount() != 1
                || borrowed.getAmount() != 1
                || ItemIdentifier.hasCustomPresentation(owned)
                || !ItemIdentifier.hasCustomPresentation(borrowed)
                || !parts[2].equals(KitVariantFingerprint.canonicalVariant(owned))
                || !parts[3].equals(KitVariantFingerprint.canonicalVariant(borrowed))) return false;
        KitShulkerLedger.Identity ownedIdentity = identity(owned);
        KitShulkerLedger.Identity borrowedIdentity = identity(borrowed);
        if (ownedIdentity == null || borrowedIdentity == null
                || !ownedIdentity.contents().isEmpty()
                || !ownedIdentity.equals(borrowedIdentity)) return false;
        Map<KitShulkerLedger.Identity, Integer> observed = new HashMap<>();
        for (int raw = 9; raw <= 44; raw++) {
            ItemStack stack = player.getItemStack(raw);
            if (stack == null || stack.getAmount() <= 0) continue;
            if (!ItemIdentifier.baseItemId(ItemIdentifier.getItemId(stack))
                    .contains("shulker_box")) continue;
            KitShulkerLedger.Identity identity = identity(stack);
            if (identity == null) return false;
            observed.merge(identity, 1, Integer::sum);
        }
        var pending = snapshot.sourceBoxesToReturn().stream()
                .map(source -> new KitShulkerLedger.Identity(source.itemId(), source.contents()))
                .toList();
        return KitShulkerLedger.matchesPendingReturns(observed, ownedIdentity, pending);
    }

    private static KitShulkerLedger.Identity identity(ItemStack stack) {
        if (stack == null || stack.getAmount() != 1) return null;
        String itemId = ItemIdentifier.getItemId(stack);
        if (!ItemIdentifier.baseItemId(itemId).contains("shulker_box")) return null;
        return new KitShulkerLedger.Identity(itemId,
                ItemIdentifier.readShulkerContents(stack));
    }
}
