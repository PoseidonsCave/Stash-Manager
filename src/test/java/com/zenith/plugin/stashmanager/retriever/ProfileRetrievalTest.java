package com.zenith.plugin.stashmanager.retriever;

import com.zenith.plugin.stashmanager.profile.ItemProfile;
import com.zenith.plugin.stashmanager.profile.ItemProfiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProfileRetrievalTest {
    @AfterEach void reset() { ItemProfiles.install(List.of()); }

    @Test void kitProfileSelectsOnlyMatchingEquipment() {
        ItemProfiles.install(List.of(new ItemProfile("pvp_sword", "diamond_sword",
                ItemProfile.Mode.EXACT, Map.of("sharpness", 5, "unbreaking", 3), false)));
        String requested = "profile:pvp_sword";
        String match = "diamond_sword[sharpness=5,unbreaking=3]";
        String wrong = "diamond_sword[sharpness=4,unbreaking=3]";
        assertEquals(requested, StashRetriever.requestKeyForItem(Map.of(requested, 1), match));
        assertNull(StashRetriever.requestKeyForItem(Map.of(requested, 1), wrong));
        assertEquals(2, StashRetriever.amountMatchingRequest(Map.of(match, 2, wrong, 3), requested));
    }
}
