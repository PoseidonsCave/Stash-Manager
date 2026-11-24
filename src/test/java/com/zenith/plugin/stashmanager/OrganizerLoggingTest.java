package com.zenith.plugin.stashmanager;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.spi.FilterReply;
import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.organizer.StashOrganizer;
import com.zenith.terminal.logback.LogSourceFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

class OrganizerLoggingTest {
    @Test
    void zenithAcceptsOrganizerAndPersistenceLoggerNames() throws Exception {
        var filter = new LogSourceFilter();
        for (Class<?> owner : new Class<?>[]{StashOrganizer.class, ContainerIndex.class}) {
            var field = owner.getDeclaredField("LOGGER");
            field.setAccessible(true);
            Logger logger = (Logger) field.get(null);
            LoggingEvent event = new LoggingEvent();
            event.setLoggerName(logger.getName());
            event.setLevel(Level.INFO);
            assertNotEquals(FilterReply.DENY, filter.decide(event), logger.getName());
        }
    }
}
