package com.zenith.plugin.stashmanager.util;

import com.zenith.mc.block.Direction;
import com.zenith.mc.block.properties.ChestType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DoubleChestIdentityTest {
    @Test
    void derivesTheOnlyLegalPartnerDirectionFromFacingAndHalf() {
        assertEquals(Direction.EAST,
                DoubleChestIdentity.partnerDirection(ChestType.LEFT, Direction.NORTH));
        assertEquals(Direction.WEST,
                DoubleChestIdentity.partnerDirection(ChestType.RIGHT, Direction.NORTH));
        assertEquals(Direction.SOUTH,
                DoubleChestIdentity.partnerDirection(ChestType.LEFT, Direction.EAST));
        assertEquals(Direction.NORTH,
                DoubleChestIdentity.partnerDirection(ChestType.RIGHT, Direction.EAST));
        assertEquals(Direction.WEST,
                DoubleChestIdentity.partnerDirection(ChestType.LEFT, Direction.SOUTH));
        assertEquals(Direction.EAST,
                DoubleChestIdentity.partnerDirection(ChestType.RIGHT, Direction.SOUTH));
        assertEquals(Direction.NORTH,
                DoubleChestIdentity.partnerDirection(ChestType.LEFT, Direction.WEST));
        assertEquals(Direction.SOUTH,
                DoubleChestIdentity.partnerDirection(ChestType.RIGHT, Direction.WEST));
    }

    @Test
    void rejectsSingleAndVerticalChestDirections() {
        assertNull(DoubleChestIdentity.partnerDirection(ChestType.SINGLE, Direction.NORTH));
        assertNull(DoubleChestIdentity.partnerDirection(ChestType.LEFT, Direction.UP));
    }
}
