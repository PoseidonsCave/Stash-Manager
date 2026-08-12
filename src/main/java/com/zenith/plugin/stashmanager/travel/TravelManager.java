package com.zenith.plugin.stashmanager.travel;

import com.zenith.plugin.stashmanager.StashManagerPlugin;
import com.zenith.plugin.stashmanager.travel.bridge.TravelBaritoneBridge;
import com.zenith.plugin.stashmanager.travel.tunnel.TunnelManager;
import com.zenith.plugin.stashmanager.travel.tunnel.storage.TunnelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Owns the shared tunnel manager lifecycle.
public final class TravelManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashManager/Travel");

    private static final TravelManager INSTANCE = new TravelManager();
    public static TravelManager get() { return INSTANCE; }

    private final TravelBaritoneBridge bridge = TravelBaritoneBridge.get();
    private TunnelManager tunnelManager = null;

    private TravelManager() {}

    public synchronized void tick() {
        TunnelManager manager = tunnelManager;
        if (manager != null) manager.tick();
    }

    public TunnelManager tunnelManager() { return getTunnelManager(); }

    private TunnelManager getTunnelManager() {
        if (tunnelManager == null) {
            try {
                var db = StashManagerPlugin.getDatabase();
                if (db != null && db.isInitialized()) {
                    tunnelManager = new TunnelManager(bridge, new TunnelRepository(db));
                }
            } catch (Exception e) {
                LOGGER.error("Failed to initialize TunnelManager", e);
            }
        }
        return tunnelManager;
    }
}
