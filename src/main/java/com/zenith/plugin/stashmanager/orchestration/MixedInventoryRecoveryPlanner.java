package com.zenith.plugin.stashmanager.orchestration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Plans one recovery task per physical mixed shulker already held by the bot. */
public final class MixedInventoryRecoveryPlanner {
    private MixedInventoryRecoveryPlanner() {}

    public record Cargo(String itemId, String fingerprint, Map<String, Integer> contents) {
        public Cargo {
            itemId = Objects.toString(itemId, "");
            fingerprint = Objects.toString(fingerprint, "");
            contents = contents == null ? Map.of() : Map.copyOf(contents);
        }
    }

    /** Subtracts queued work as a multiset so identical physical boxes stay independent. */
    public static List<Cargo> uncovered(
            Collection<Cargo> inventoryCargo,
            Collection<Cargo> scheduledCargo) {
        if (inventoryCargo == null || inventoryCargo.isEmpty()) return List.of();

        Map<Key, Integer> covered = new LinkedHashMap<>();
        if (scheduledCargo != null) {
            for (Cargo cargo : scheduledCargo) {
                if (cargo == null) continue;
                covered.merge(Key.from(cargo), 1, Integer::sum);
            }
        }

        List<Cargo> result = new ArrayList<>();
        for (Cargo cargo : inventoryCargo) {
            if (cargo == null) continue;
            Key key = Key.from(cargo);
            int remaining = covered.getOrDefault(key, 0);
            if (remaining > 0) {
                if (remaining == 1) covered.remove(key);
                else covered.put(key, remaining - 1);
            } else {
                result.add(cargo);
            }
        }
        return List.copyOf(result);
    }

    private record Key(String itemId, String fingerprint) {
        private static Key from(Cargo cargo) {
            return new Key(cargo.itemId(), cargo.fingerprint());
        }
    }
}
