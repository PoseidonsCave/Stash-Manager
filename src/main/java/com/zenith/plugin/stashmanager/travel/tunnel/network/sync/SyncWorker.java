package com.zenith.plugin.stashmanager.travel.tunnel.network.sync;

import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.travel.tunnel.core.Tunnel;
import com.zenith.plugin.stashmanager.travel.tunnel.network.TunnelNetworkClient;
import com.zenith.plugin.stashmanager.travel.tunnel.network.TunnelNetworkConfig;
import com.zenith.plugin.stashmanager.travel.tunnel.network.dto.CapabilitiesDto;
import com.zenith.plugin.stashmanager.travel.tunnel.network.dto.TunnelDto;
import com.zenith.plugin.stashmanager.travel.tunnel.network.dto.TunnelSubmissionDto;
import com.zenith.plugin.stashmanager.travel.tunnel.storage.TunnelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

// Periodic background sync between local tunnel storage and a remote backend.
public final class SyncWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger("StashManager/TunnelSync");

    private final TunnelNetworkConfig config;
    private final TunnelNetworkClient client;
    private final ConflictResolver conflictResolver = new ConflictResolver();

    private DatabaseManager database;
    private CompletableFuture<SyncResult> activeSync;
    private Instant nextSyncAt = Instant.EPOCH;
    private int failureCount = 0;
    private CapabilitiesDto cachedCapabilities;
    private Instant capabilitiesFetchedAt = Instant.EPOCH;

    public SyncWorker(StashManagerConfig config) {
        this(new TunnelNetworkConfig(config));
    }

    public SyncWorker(TunnelNetworkConfig config) {
        this.config = config;
        this.client = new TunnelNetworkClient(config);
    }

    public void setDatabase(DatabaseManager database) {
        this.database = database;
    }

    public void tick() {
        if (!config.isConfigured() || database == null || !database.isInitialized()) return;

        if (activeSync != null) {
            if (activeSync.isDone()) {
                finishSync();
            }
            return;
        }

        if (Instant.now().isBefore(nextSyncAt)) return;
        activeSync = CompletableFuture.supplyAsync(this::syncOnce);
    }

    public void stop() {
        if (activeSync != null) {
            activeSync.cancel(true);
            activeSync = null;
        }
        nextSyncAt = Instant.EPOCH;
    }

    private void finishSync() {
        try {
            SyncResult result = activeSync.join();
            if (result.success) {
                failureCount = 0;
                nextSyncAt = Instant.now().plus(Duration.ofMinutes(config.syncIntervalMinutes));
                LOGGER.debug("Tunnel sync completed: uploaded={}, downloaded={}", result.uploaded, result.downloaded);
            } else {
                failureCount++;
                nextSyncAt = Instant.now().plus(backoff());
                LOGGER.debug("Tunnel sync failed: {}", result.message);
            }
        } catch (Exception e) {
            failureCount++;
            nextSyncAt = Instant.now().plus(backoff());
            LOGGER.debug("Tunnel sync failed: {}", e.getMessage());
        } finally {
            activeSync = null;
        }
    }

    private SyncResult syncOnce() {
        try {
            TunnelRepository repository = new TunnelRepository(database);
            refreshCapabilities();

            int uploaded = uploadDiscoveries(repository);
            int downloaded = downloadRoutes(repository);
            return SyncResult.success(uploaded, downloaded);
        } catch (Exception e) {
            return SyncResult.failure(e.getMessage());
        }
    }

    private void refreshCapabilities() {
        if (cachedCapabilities != null && Duration.between(capabilitiesFetchedAt, Instant.now()).toMinutes() < config.syncIntervalMinutes) {
            return;
        }
        client.fetchCapabilities().ifPresent(capabilities -> {
            cachedCapabilities = capabilities;
            capabilitiesFetchedAt = Instant.now();
        });
    }

    private int uploadDiscoveries(TunnelRepository repository) {
        if (!config.uploadDiscoveries) return 0;
        if (cachedCapabilities != null && !cachedCapabilities.uploadTunnels) return 0;
        List<Tunnel> local = repository.findAll();
        List<TunnelDto> pending = new ArrayList<>();
        for (Tunnel tunnel : local) {
            if (!tunnel.sharedToNetwork || tunnel.networkId == null || tunnel.networkId.isBlank()) {
                pending.add(TunnelDto.fromTunnel(tunnel));
            }
        }
        if (pending.isEmpty()) return 0;

        int batchSize = cachedCapabilities != null ? Math.max(1, cachedCapabilities.maxBatchSize) : 100;
        int uploaded = 0;
        for (int i = 0; i < pending.size(); i += batchSize) {
            List<TunnelDto> batch = pending.subList(i, Math.min(pending.size(), i + batchSize));
            if (!client.uploadTunnels(TunnelSubmissionDto.of(batch))) {
                continue;
            }
            uploaded += batch.size();
            for (TunnelDto dto : batch) {
                repository.findById(dto.id).ifPresent(tunnel -> {
                    tunnel.sharedToNetwork = true;
                    if (dto.networkId != null && !dto.networkId.isBlank()) {
                        tunnel.networkId = dto.networkId;
                    }
                    repository.save(tunnel);
                });
            }
        }
        return uploaded;
    }

    private int downloadRoutes(TunnelRepository repository) {
        if (!config.downloadRoutes) return 0;
        if (cachedCapabilities != null && !cachedCapabilities.downloadTunnels) return 0;
        List<TunnelDto> remote = client.downloadTunnels();
        if (remote.isEmpty()) return 0;

        Map<String, Tunnel> localByKey = new HashMap<>();
        for (Tunnel tunnel : repository.findAll()) {
            localByKey.put(conflictResolver.key(tunnel), tunnel);
        }

        int downloaded = 0;
        for (TunnelDto dto : remote) {
            Tunnel remoteTunnel = dto.toTunnel();
            Tunnel localTunnel = localByKey.get(conflictResolver.key(remoteTunnel));
            Tunnel merged = conflictResolver.merge(localTunnel, remoteTunnel);
            repository.save(merged);
            downloaded++;
        }
        return downloaded;
    }

    private Duration backoff() {
        int cappedFailures = Math.min(failureCount, 5);
        long minutes = Math.min(15L, 1L << cappedFailures);
        return Duration.ofMinutes(minutes);
    }

    private record SyncResult(boolean success, int uploaded, int downloaded, String message) {
        static SyncResult success(int uploaded, int downloaded) {
            return new SyncResult(true, uploaded, downloaded, null);
        }

        static SyncResult failure(String message) {
            return new SyncResult(false, 0, 0, message);
        }
    }
}