package com.zenith.plugin.stashmanager.kit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reconcile an interrupted apple retrieval from a durable baseline and a complete live inventory. */
public final class KitBorrowedBatchRecovery {
    private KitBorrowedBatchRecovery() { }

    public record Slot(String itemId, int amount, Map<String, Integer> boxContents) {
        public Slot {
            boxContents = boxContents == null ? Map.of() : Map.copyOf(boxContents);
        }
        public boolean isBox() {
            return itemId != null && itemId.contains("shulker_box") && amount == 1;
        }
    }

    public record Plan(String blocker, Map<Integer, Integer> cargoSlots,
                       List<KitFulfillmentJournalStore.BorrowedBox> sources) {
        public Plan {
            cargoSlots = cargoSlots == null ? Map.of() : Map.copyOf(cargoSlots);
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
        public boolean accepted() { return blocker == null; }
    }

    public static Plan inspect(KitFulfillmentJournalStore.Snapshot checkpoint,
                               KitProvenanceJournal.BlockedRetrieval evidence,
                               Map<Integer, Slot> live) {
        if (checkpoint == null || evidence == null || live == null
                || !"BLOCKED".equals(checkpoint.state())
                || !"kit_cargo_retrieval_incomplete".equals(checkpoint.blocker())
                || !"enchanted_golden_apple".equals(checkpoint.currentItem())
                || checkpoint.currentQuantity() < 2
                || checkpoint.kitWorksite() != null
                || checkpoint.borrowedSourceWorksite() != null
                || checkpoint.auditedCargoSlots().size() > 0
                || checkpoint.protectedLoose() != null
                || checkpoint.heldBoxRebase() != null
                || checkpoint.sourceBoxesToReturn().isEmpty()) {
            return reject("checkpoint is not at the interrupted borrowed apple boundary");
        }
        Set<Integer> occupied = new HashSet<>();
        Map<Integer, Integer> cargo = new HashMap<>();
        int before = 0, after = 0, added = 0;
        for (int raw = 9; raw <= 44; raw++) {
            Slot stack = live.get(raw);
            if (stack == null || stack.amount() <= 0) continue;
            occupied.add(raw);
            if (!"enchanted_golden_apple".equals(stack.itemId())) continue;
            after = Math.addExact(after, stack.amount());
            Integer old = evidence.beforeItemSlots().get(raw);
            if (old != null) {
                if (old != stack.amount()) return reject("pre-retrieval apple slot changed: " + raw);
            } else {
                if (evidence.beforeItemSlots().containsKey(raw))
                    return reject("invalid baseline apple slot: " + raw);
                if (stack.amount() > 64) return reject("new apple stack is oversized");
                cargo.put(raw, stack.amount());
                added = Math.addExact(added, stack.amount());
            }
        }
        for (var entry : evidence.beforeItemSlots().entrySet()) {
            if (entry.getKey() < 9 || entry.getKey() > 44 || entry.getValue() < 1
                    || entry.getValue() > 64
                    || live.get(entry.getKey()) == null)
                return reject("pre-retrieval apple slot is missing or invalid");
            before = Math.addExact(before, entry.getValue());
        }
        if (!occupied.equals(evidence.blockedOccupiedSlots()))
            return reject("live occupied slots differ from the blocked inventory witness");
        if (after != before + added || cargo.size() < 2 || added < 1
                || added >= checkpoint.currentQuantity())
            return reject("new apple cargo is missing, ambiguous, or exceeds the request");

        Slot shell = live.get(checkpoint.ownedShellSlot());
        if (shell == null || !shell.isBox()
                || !shell.boxContents().equals(checkpoint.packedContents()))
            return reject("owned kit shell does not match the checkpoint");

        Map<KitShulkerLedger.Identity, Integer> journaled = new HashMap<>();
        for (var source : checkpoint.sourceBoxesToReturn()) {
            if (source == null || source.itemId() == null || source.contents() == null
                    || !Arrays.equals(source.sourcePosition(),
                            checkpoint.sourceBoxesToReturn().getFirst().sourcePosition()))
                return reject("journaled source origins are inconsistent");
            journaled.merge(new KitShulkerLedger.Identity(
                    source.itemId(), source.contents()), 1, Integer::sum);
        }
        int extraRaw = -1;
        Slot extra = null;
        for (int raw = 9; raw <= 44; raw++) {
            Slot stack = live.get(raw);
            if (stack == null || !stack.isBox() || raw == checkpoint.ownedShellSlot()) continue;
            var id = new KitShulkerLedger.Identity(stack.itemId(), stack.boxContents());
            int remaining = journaled.getOrDefault(id, 0);
            if (remaining > 0) journaled.put(id, remaining - 1);
            else if (extra == null) { extra = stack; extraRaw = raw; }
            else return reject("more than one unjournaled shulker is carried");
        }
        if (journaled.values().stream().anyMatch(count -> count != 0)
                || extra == null || evidence.beforeOccupiedSlots().contains(extraRaw)
                || !extra.boxContents().keySet().equals(Set.of("enchanted_golden_apple"))
                || extra.boxContents().get("enchanted_golden_apple") < 1
                || extra.boxContents().get("enchanted_golden_apple") > 1728) {
            return reject("carried source boxes do not match the three journaled boxes plus one apple box");
        }
        int[] origin = checkpoint.sourceBoxesToReturn().getFirst().sourcePosition();
        if (origin == null || !position(origin).equals(evidence.sourcePosition()))
            return reject("last retrieval target differs from the journaled source origin");
        List<KitFulfillmentJournalStore.BorrowedBox> sources =
                new ArrayList<>(checkpoint.sourceBoxesToReturn());
        sources.add(new KitFulfillmentJournalStore.BorrowedBox(
                origin, extra.itemId(), extra.boxContents()));
        return new Plan(null, cargo, sources);
    }

    private static String position(int[] p) {
        return p[0] + ", " + p[1] + ", " + p[2];
    }

    private static Plan reject(String blocker) { return new Plan(blocker, Map.of(), List.of()); }
}
