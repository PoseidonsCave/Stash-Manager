package com.zenith.plugin.stashmanager.debug;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentType;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KitInteractionTraceTest {
    @TempDir Path temporary;

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void serverShulkerUpdateReportsBoundedNumericContents() {
        List<Optional<ItemStack>> contents = new ArrayList<>();
        for (int i = 0; i < 27; i++) contents.add(Optional.empty());
        contents.set(0, Optional.of(new ItemStack(303, 31)));
        for (int i = 1; i <= 20; i++) contents.set(i, Optional.of(new ItemStack(303, 64)));
        DataComponents components = new DataComponents(new HashMap<>());
        components.put((DataComponentType) DataComponentTypes.CONTAINER, contents);
        KitInteractionTrace.Stack observed = KitInteractionTrace.Stack.of(
                new ItemStack(561, 1, components));
        assertEquals(303, observed.nestedItemId());
        assertEquals(1311, observed.nestedUnits());
        assertEquals(21, observed.nestedStacks());
    }

    @Test
    void serverWindowProvesChestToPlayerDeltaWithoutCreditingAnOldStack() throws Exception {
        Path file = temporary.resolve("clicks.jsonl");
        KitInteractionTrace trace = new KitInteractionTrace(file);
        ItemStack[] before = new ItemStack[63]; // 27 box slots + 36 player slots
        before[0] = new ItemStack(7, 31);
        before[62] = new ItemStack(7, 33); // pre-existing player stack
        trace.onOpen(4);
        trace.onSetContent(4, 8, before, null);
        trace.onClickProposal(4, 8, 0, "SHIFT_CLICK:0",
                Map.of(0, new KitInteractionTrace.Stack(0, 0)), null);
        assertEquals(KitInteractionTrace.Verdict.WAITING_FOR_FULL_WINDOW,
                trace.lastVerdict());

        trace.onSetSlot(4, 9, 0, null);
        trace.onSetSlot(4, 9, 54, new ItemStack(7, 31));
        assertEquals(KitInteractionTrace.Verdict.WAITING_FOR_FULL_WINDOW,
                trace.lastVerdict());

        ItemStack[] after = before.clone();
        after[0] = null;
        after[54] = new ItemStack(7, 31);
        trace.onSetContent(4, 9, after, null);
        assertEquals(KitInteractionTrace.Verdict.BALANCED_WINDOW_DELTA,
                trace.lastVerdict());
        assertTrue(trace.status().awaitingPlayerMirror());
        trace.onClose(4, false);
        ItemStack[] playerView = new ItemStack[46];
        playerView[36] = new ItemStack(7, 31);
        playerView[44] = new ItemStack(7, 33);
        trace.onSetContent(0, 10, playerView, null);
        assertEquals(KitInteractionTrace.Verdict.DUAL_VIEW_MATCH,
                trace.lastVerdict());
        assertFalse(trace.status().awaitingPlayerMirror());
        String evidence = Files.readString(file);
        assertTrue(evidence.contains("client_click_proposal"));
        assertTrue(evidence.contains("partial_not_creditable"));
        assertTrue(evidence.contains("BALANCED_WINDOW_DELTA"));
        assertTrue(evidence.contains("DUAL_VIEW_MATCH"));
        assertFalse(evidence.contains("DataComponents"));
        assertEquals(PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(file));
    }

    @Test
    void overlappingClicksAndMissingFullWindowCannotCreditTransfer() {
        KitInteractionTrace trace = new KitInteractionTrace(temporary.resolve("ambiguous.jsonl"));
        ItemStack[] before = new ItemStack[63];
        before[0] = new ItemStack(7, 64);
        trace.onOpen(5);
        trace.onSetContent(5, 10, before, null);
        trace.onClickProposal(5, 10, 0, "SHIFT_CLICK:0", Map.of(), null);
        trace.onSetSlot(5, 11, 0, null);
        assertEquals(KitInteractionTrace.Verdict.WAITING_FOR_FULL_WINDOW,
                trace.lastVerdict());
        trace.onClickProposal(5, 11, 1, "CLICK:0", Map.of(), null);
        ItemStack[] after = before.clone();
        after[0] = null;
        after[54] = new ItemStack(7, 64);
        trace.onSetContent(5, 12, after, null);
        assertEquals(KitInteractionTrace.Verdict.AMBIGUOUS_CLICKS,
                trace.lastVerdict());
    }
}
