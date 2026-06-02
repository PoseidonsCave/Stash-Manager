package com.zenith.plugin.stashmanager.travel.delivery;

import com.zenith.Proxy;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.zenith.Globals.CACHE;

// Records home position and manages the /kill → respawn return flow.
// Flow: sendKill() → isDead() → sendRespawn() → isAlive() → back at bed/anchor.
public final class HomeTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashManager/HomeTracker");

    private int[] homePos = null;
    private String homeDimension = null;

    /** Record the given coordinates as home. Call once at DELIVERY_INIT. */
    public void recordHome(int[] pos, String dimName) {
        this.homePos = pos.clone();
        this.homeDimension = dimName;
        LOGGER.info("Home recorded: [{},{},{}] in {}", pos[0], pos[1], pos[2], dimName);
    }

    public int[]  getHomePos()       { return homePos; }
    public String getHomeDimension() { return homeDimension; }
    public boolean hasHome()         { return homePos != null; }

    /** Sends /kill. */
    public void sendKill() {
        var client = Proxy.getInstance().getClient();
        if (client != null) {
            client.sendAsync(new ServerboundChatCommandPacket("kill"));
            LOGGER.info("Sent /kill command for return-home");
        }
    }

    /** Sends the RESPAWN packet. Call once the death screen is up (isDead()). */
    public void sendRespawn() {
        var client = Proxy.getInstance().getClient();
        if (client != null) {
            client.sendAsync(new ServerboundClientCommandPacket(ClientCommand.RESPAWN));
            LOGGER.info("Sent RESPAWN packet");
        }
    }

    /** True if the player's health has reached zero (death screen). */
    public boolean isDead() {
        try {
            return CACHE.getPlayerCache().getThePlayer().getHealth() <= 0f;
        } catch (Exception e) {
            return false;
        }
    }

    /** True if the player has a positive health value (alive / already respawned). */
    public boolean isAlive() {
        return !isDead();
    }

    /** True if within tolerance blocks of recorded home. */
    public boolean isAtHome(int[] playerPos, int tolerance) {
        if (homePos == null) return false;
        int dx = playerPos[0] - homePos[0];
        int dz = playerPos[2] - homePos[2];
        return (dx * dx + dz * dz) <= tolerance * tolerance;
    }
}
