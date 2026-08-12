package com.zenith.plugin.stashmanager.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.command.api.CommandCategory;
import com.zenith.plugin.stashmanager.StashManagerPlugin;
import com.zenith.plugin.stashmanager.database.DatabaseManager;
import com.zenith.plugin.stashmanager.travel.TravelManager;
import com.zenith.plugin.stashmanager.travel.tunnel.TunnelManager;
import com.zenith.plugin.stashmanager.travel.tunnel.core.Tunnel;
import com.zenith.plugin.stashmanager.travel.tunnel.storage.TunnelRepository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static com.mojang.brigadier.arguments.LongArgumentType.longArg;
import static com.zenith.Globals.CACHE;

// /stashtunnel — inspect and manage the bedrock-floor tunnel knowledge base.
// Sub-commands: list [page] | status | scan | verify | delete <id> | clear | count
public class StashTunnelCommand extends Command {

    private static final int PAGE_SIZE = 10;

    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("stashtunnel")
            .category(CommandCategory.MODULE)
            .description("Inspect and manage the bedrock-floor tunnel knowledge base")
            .usageLines(
                "list [page]",
                "status",
                "scan",
                "verify",
                "delete <id>",
                "clear",
                "count"
            )
            .aliases("tunnel")
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("stashtunnel")
            // ── list ──────────────────────────────────────────────────────────
            .then(literal("list")
                .executes(c -> { return handleList(c.getSource(), 1); })
                .then(argument("page", longArg(1))
                    .executes(c -> { return handleList(c.getSource(),
                            (int) LongArgumentType.getLong(c, "page")); })))

            // ── status ────────────────────────────────────────────────────────
            .then(literal("status")
                .executes(c -> { return handleStatus(c.getSource()); }))

            // ── scan ──────────────────────────────────────────────────────────
            .then(literal("scan")
                .executes(c -> { return handleScan(c.getSource()); }))

            // ── verify ────────────────────────────────────────────────────────
            .then(literal("verify")
                .executes(c -> { return handleVerify(c.getSource()); }))

            // ── delete <id> ───────────────────────────────────────────────────
            .then(literal("delete")
                .then(argument("id", longArg(1))
                    .executes(c -> { return handleDelete(c.getSource(),
                            LongArgumentType.getLong(c, "id")); })))

            // ── clear ─────────────────────────────────────────────────────────
            .then(literal("clear")
                .executes(c -> { return handleClear(c.getSource()); }))

            // ── count ─────────────────────────────────────────────────────────
            .then(literal("count")
                .executes(c -> { return handleCount(c.getSource()); }));
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private int handleList(CommandContext ctx, int page) {
        TunnelRepository repo = getRepo();
        if (repo == null) {
            ctx.getEmbed().title("Tunnels").description("Database not initialized.").errorColor();
            return OK;
        }
        int offset = (page - 1) * PAGE_SIZE;
        List<Tunnel> tunnels = fetchPage(repo, offset, PAGE_SIZE);
        int total = repo.count();
        int pages = (int) Math.ceil(total / (double) PAGE_SIZE);

        if (tunnels.isEmpty()) {
            ctx.getEmbed().title("Tunnels (page " + page + ")").description("No tunnels found.").primaryColor();
            return OK;
        }
        StringBuilder sb = new StringBuilder();
        for (Tunnel t : tunnels) {
            sb.append("**#").append(t.id).append("** ")
              .append(t.startX).append(",").append(t.startZ)
              .append(" → ").append(t.endX).append(",").append(t.endZ)
              .append(" @y=").append(t.floorY)
              .append("  ").append(t.status).append("  conf=")
              .append(String.format("%.2f", t.confidence))
              .append("  used=").append(t.timesUsed)
              .append("\n");
        }
        sb.append("Page ").append(page).append(" / ").append(Math.max(1, pages))
          .append("  (").append(total).append(" total)");
        ctx.getEmbed().title("Tunnels").description(sb.toString()).primaryColor();
        return OK;
    }

    private int handleStatus(CommandContext ctx) {
        TunnelManager tm = TravelManager.get().tunnelManager();
        if (tm == null) {
            ctx.getEmbed().title("Tunnel Status").description("TunnelManager not initialized (DB needed).").primaryColor();
            return OK;
        }
        StringBuilder sb = new StringBuilder();
        if (tm.isIdle()) {
            sb.append("State: **IDLE**");
        } else if (tm.isReady()) {
            Tunnel t = tm.getActiveTunnel();
            sb.append("State: **DONE** — route ready\n");
            if (t != null) sb.append("Active: ").append(t);
        } else if (tm.isBuilding()) {
            sb.append("State: **BUILDING**\n");
            sb.append("Phase: ").append(tm.getBuildPhase()).append("\n");
            sb.append("Progress: ").append(String.format("%.1f%%", tm.getBuildProgress() * 100));
        } else if (tm.isBuildFailed()) {
            sb.append("State: **FAILED** — ").append(tm.getFailReason());
        } else {
            sb.append("State: scanning/routing…");
        }
        ctx.getEmbed().title("Tunnel Status").description(sb.toString()).primaryColor();
        return OK;
    }

    private int handleScan(CommandContext ctx) {
        TunnelManager tm = TravelManager.get().tunnelManager();
        if (tm == null) {
            ctx.getEmbed().title("Tunnel Scan").description("TunnelManager not initialized (DB needed).").errorColor();
            return OK;
        }
        if (!tm.isIdle()) {
            ctx.getEmbed().title("Tunnel Scan").description("TunnelManager is busy — cancel current operation first.").errorColor();
            return OK;
        }
        var pc = CACHE.getPlayerCache();
        int px = (int) pc.getX();
        int pz = (int) pc.getZ();
        // Request a route to player position (no-op route but forces SCANNING state which runs the passive scan)
        tm.requestRoute(px, pz);
        ctx.getEmbed().title("Tunnel Scan").description("Passive chunk-cache scan started at " + px + "," + pz + ".").successColor();
        return OK;
    }

    private int handleVerify(CommandContext ctx) {
        TunnelManager tm = TravelManager.get().tunnelManager();
        if (tm == null) {
            ctx.getEmbed().title("Tunnel Verify").description("TunnelManager not initialized.").errorColor();
            return OK;
        }
        Tunnel active = tm.getActiveTunnel();
        if (active == null) {
            ctx.getEmbed().title("Tunnel Verify").description("No active tunnel to verify.").primaryColor();
            return OK;
        }
        tm.verifyActiveTunnel();
        ctx.getEmbed().title("Tunnel Verify")
            .description("Verified tunnel #" + active.id + " — status: " + active.status + ", conf=" + String.format("%.2f", active.confidence))
            .successColor();
        return OK;
    }

    private int handleDelete(CommandContext ctx, long id) {
        DatabaseManager db = StashManagerPlugin.getDatabase();
        if (db == null || !db.isInitialized()) {
            ctx.getEmbed().title("Tunnel Delete").description("Database not initialized.").errorColor();
            return OK;
        }
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement("DELETE FROM tunnels WHERE id = ?")) {
            ps.setLong(1, id);
            int rows = ps.executeUpdate();
            if (rows > 0) {
                ctx.getEmbed().title("Tunnel Delete").description("Deleted tunnel #" + id + ".").successColor();
            } else {
                ctx.getEmbed().title("Tunnel Delete").description("No tunnel found with id=" + id + ".").errorColor();
            }
        } catch (Exception e) {
            ctx.getEmbed().title("Tunnel Delete").description("Error: " + e.getMessage()).errorColor();
        }
        return OK;
    }

    private int handleClear(CommandContext ctx) {
        DatabaseManager db = StashManagerPlugin.getDatabase();
        if (db == null || !db.isInitialized()) {
            ctx.getEmbed().title("Tunnel Clear").description("Database not initialized.").errorColor();
            return OK;
        }
        try (Connection conn = db.getConnection();
             Statement stmt = conn.createStatement()) {
            int rows = stmt.executeUpdate("DELETE FROM tunnels");
            ctx.getEmbed().title("Tunnel Clear").description("Deleted " + rows + " tunnel record(s).").successColor();
        } catch (Exception e) {
            ctx.getEmbed().title("Tunnel Clear").description("Error: " + e.getMessage()).errorColor();
        }
        return OK;
    }

    private int handleCount(CommandContext ctx) {
        TunnelRepository repo = getRepo();
        if (repo == null) {
            ctx.getEmbed().title("Tunnel Count").description("Database not initialized.").errorColor();
            return OK;
        }
        ctx.getEmbed().title("Tunnel Count").description("Known tunnels: **" + repo.count() + "**").primaryColor();
        return OK;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TunnelRepository getRepo() {
        DatabaseManager db = StashManagerPlugin.getDatabase();
        if (db == null || !db.isInitialized()) return null;
        return new TunnelRepository(db);
    }

    /** Fetch a page of tunnels ordered by most-recently-used. */
    private List<Tunnel> fetchPage(TunnelRepository repo, int offset, int limit) {
        DatabaseManager db = StashManagerPlugin.getDatabase();
        if (db == null || !db.isInitialized()) return List.of();
        List<Tunnel> results = new ArrayList<>();
        String sql = "SELECT id FROM tunnels ORDER BY last_used_at DESC NULLS LAST, id DESC LIMIT ? OFFSET ?";
        try (Connection conn = db.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    repo.findById(rs.getLong("id")).ifPresent(results::add);
                }
            }
        } catch (Exception ignored) {
        }
        return results;
    }
}
