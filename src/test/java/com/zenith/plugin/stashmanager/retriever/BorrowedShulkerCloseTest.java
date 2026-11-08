package com.zenith.plugin.stashmanager.retriever;

import org.junit.jupiter.api.Test;

import static com.zenith.plugin.stashmanager.retriever.StashRetriever.BorrowedCloseAction.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BorrowedShulkerCloseTest {
    @Test
    void breakWaitsForTheBorrowedGuiToActuallyClose() {
        assertEquals(WAIT, StashRetriever.borrowedCloseAction(3, 3, 1, true));
        assertEquals(RETRY, StashRetriever.borrowedCloseAction(3, 3, 10, false));
        assertEquals(WAIT, StashRetriever.borrowedCloseAction(3, 3, 11, false));
        assertEquals(CLOSED, StashRetriever.borrowedCloseAction(3, 0, 121, false));
    }

    @Test
    void refusesWrongContainerAndBoundsCloseRetries() {
        assertEquals(WRONG_CONTAINER,
                StashRetriever.borrowedCloseAction(3, 4, 1, false));
        assertEquals(TIMEOUT,
                StashRetriever.borrowedCloseAction(3, 3, 121, false));
    }
}
