package com.zenith.plugin.stashmanager.travel.delivery;

import com.zenith.plugin.stashmanager.index.ContainerIndex;
import com.zenith.plugin.stashmanager.retriever.StashRetriever;
import com.zenith.plugin.stashmanager.travel.TravelMission;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

// Drives StashRetriever to gather mission items during the GATHERING phase.
public final class GatherOperation {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashManager/GatherOp");

    public enum State { IDLE, RUNNING, DONE, FAILED }

    private State state = State.IDLE;
    private final StashRetriever retriever;
    private String failReason = "";

    public GatherOperation() {
        this.retriever = new StashRetriever();
    }

    // Returns false if retriever couldn't start (no matching containers or inventory full).
    public boolean start(TravelMission mission, ContainerIndex index) {
        if (state != State.IDLE) return false;

        if (mission.itemIds == null || mission.itemIds.length == 0) {
            state = State.DONE;   // nothing to gather
            return true;
        }

        Map<String, Integer> items = new LinkedHashMap<>();
        for (int i = 0; i < mission.itemIds.length; i++) {
            items.put(mission.itemIds[i], mission.quantities[i]);
        }

        var candidates = new ArrayList<>(index.getAll());
        boolean started = retriever.startKit("delivery#" + mission.id, items, candidates);
        if (!started) {
            failReason = "StashRetriever failed to start (no matching containers or inventory full)";
            state = State.FAILED;
            LOGGER.warn("GatherOperation failed: {}", failReason);
            return false;
        }

        state = State.RUNNING;
        LOGGER.info("GatherOperation started — gathering {} item type(s)", items.size());
        return true;
    }

    /** Forward inbound container packets to the underlying retriever. */
    public void onContainerData(Session session, ClientboundContainerSetContentPacket packet) {
        retriever.onContainerData(session, packet);
    }

    /** Called every game tick. Drives the underlying retriever. */
    public void tick() {
        if (state != State.RUNNING) return;
        retriever.tick();
        if (retriever.getState() == StashRetriever.State.DONE) {
            int rem = retriever.getRemainingTotal();
            if (rem > 0) {
                LOGGER.warn("Gathering incomplete — {} items still needed after exhausting candidates", rem);
            }
            state = State.DONE;
            LOGGER.info("GatherOperation complete");
        }
    }

    public void stop() {
        if (state == State.RUNNING) retriever.stop();
        state = State.IDLE;
    }

    public boolean isActive()       { return state == State.RUNNING; }
    public State   getState()       { return state; }
    public boolean isDone()         { return state == State.DONE; }
    public boolean isFailed()       { return state == State.FAILED; }
    public String  getFailReason()  { return failReason; }
    public int     getRemainingTotal() { return retriever.getRemainingTotal(); }
}
