package com.zenith.plugin.stashmanager.travel.tunnel.network.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Upload payload for a batch of tunnel discoveries. */
public class TunnelSubmissionDto {
    public String client = "stash-manager";
    public String uploadedAt = Instant.now().toString();
    public List<TunnelDto> tunnels = new ArrayList<>();

    public static TunnelSubmissionDto of(List<TunnelDto> tunnels) {
        TunnelSubmissionDto dto = new TunnelSubmissionDto();
        dto.tunnels.addAll(tunnels);
        return dto;
    }
}