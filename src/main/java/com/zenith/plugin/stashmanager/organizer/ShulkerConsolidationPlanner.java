package com.zenith.plugin.stashmanager.organizer;

import com.zenith.plugin.stashmanager.index.ContainerEntry;
import com.zenith.plugin.stashmanager.orchestration.ShulkerClassification;
import com.zenith.plugin.stashmanager.orchestration.StorageClassPolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Select one verified, single-item donor whose stacks fit beside another partial box. */
final class ShulkerConsolidationPlanner {
    private ShulkerConsolidationPlanner() {}

    record Candidate(ContainerEntry source, int slot, String color, String itemId,
                     String storageClass, String fingerprint, Map<String, Integer> contents,
                     int occupiedSlots, ContainerEntry recipient, int recipientSlots) {
        String attemptKey() {
            return key(source, fingerprint);
        }
    }

    private record Box(ContainerEntry container, ContainerEntry.ShulkerDetail detail,
                       String itemId, String storageClass, String fingerprint, int slots) {}

    static Optional<Candidate> next(Collection<ContainerEntry> containers,
                                    Predicate<ContainerEntry> owned,
                                    long scanStartedAtEpochMilli,
                                    Set<String> attempted) {
        if (containers == null || owned == null || scanStartedAtEpochMilli <= 0) return Optional.empty();
        Map<String, List<Box>> byExactItem = new HashMap<>();
        for (ContainerEntry container : containers) {
            if (container == null || container.timestamp() < scanStartedAtEpochMilli
                    || !owned.test(container)) continue;
            for (ContainerEntry.ShulkerDetail detail : container.shulkerDetails()) {
                if (!detail.isPhysicalInstance() || detail.items().size() != 1) continue;
                Map.Entry<String, Integer> item = detail.items().entrySet().iterator().next();
                if (item.getValue() == null || item.getValue() <= 0
                        || !detail.stackSlots().containsKey(item.getKey())) continue;
                String exact = StorageClassPolicy.exact(item.getKey());
                if (exact == null || "chest".equals(exact) || "shulker_shell".equals(exact)) continue;
                int slots = detail.stackSlots().get(item.getKey());
                if (slots <= 0 || slots >= 27) continue;
                ShulkerClassification classification = ShulkerClassification.classify(detail.items());
                if (classification.kind() != ShulkerClassification.Kind.BULK) continue;
                byExactItem.computeIfAbsent(exact, ignored -> new ArrayList<>())
                        .add(new Box(container, detail, exact, classification.storageKey(),
                                classification.fingerprint(), slots));
            }
        }

        Comparator<Box> donorOrder = Comparator.comparingInt(Box::slots)
                .thenComparing(box -> box.itemId)
                .thenComparingInt(box -> box.container.x())
                .thenComparingInt(box -> box.container.y())
                .thenComparingInt(box -> box.container.z())
                .thenComparingInt(box -> box.detail.slot());
        for (Box donor : byExactItem.values().stream().flatMap(List::stream)
                .sorted(donorOrder).toList()) {
            if (attempted != null && attempted.contains(key(donor.container, donor.fingerprint))) continue;
            Box recipient = byExactItem.get(donor.itemId).stream()
                    .filter(box -> box != donor
                            && (attempted == null
                                || !attempted.contains(key(box.container, box.fingerprint)))
                            && box.slots + donor.slots <= 27
                            && (!sameInventory(box.container, donor.container)
                                || box.detail.slot() != donor.detail.slot()))
                    .max(Comparator.comparingInt(Box::slots)
                            .thenComparingInt(box -> box.container.x())
                            .thenComparingInt(box -> box.container.y())
                            .thenComparingInt(box -> box.container.z()))
                    .orElse(null);
            if (recipient == null) continue;
            return Optional.of(new Candidate(donor.container, donor.detail.slot(),
                    donor.detail.color(), donor.itemId, donor.storageClass, donor.fingerprint,
                    Map.copyOf(donor.detail.items()), donor.slots,
                    recipient.container, recipient.slots));
        }
        return Optional.empty();
    }

    static String key(ContainerEntry container, String fingerprint) {
        return container.posKey() + ":" + Objects.toString(fingerprint, "");
    }

    private static boolean sameInventory(ContainerEntry a, ContainerEntry b) {
        return a.isDouble() && b.isDouble() && a.inventoryIdentityKnown() && b.inventoryIdentityKnown()
                ? a.inventoryKey() == b.inventoryKey() : a.posKey() == b.posKey();
    }
}
