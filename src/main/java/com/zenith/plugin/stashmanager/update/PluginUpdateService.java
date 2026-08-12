package com.zenith.plugin.stashmanager.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.plugin.stashmanager.BuildConstants;
import com.zenith.plugin.stashmanager.StashManagerConfig;
import com.zenith.plugin.stashmanager.StashManagerPlugin;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class PluginUpdateService {
    private static final String REPO_OWNER = "PoseidonsCave";
    private static final String REPO_NAME = "Stash-Management";
    private static final URI LATEST_RELEASE_URI = URI.create(
        "https://api.github.com/repos/" + REPO_OWNER + "/" + REPO_NAME + "/releases/latest"
    );

    private final StashManagerConfig config;
    private final ComponentLogger logger;
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final Path currentArtifactPath;
    private final Path pluginsDirectory;
    private final String runtimeMcVersion;
    private final String updateTarget;

    private volatile StatusSnapshot snapshot;

    public PluginUpdateService(final StashManagerConfig config, final ComponentLogger logger) {
        this.config = config;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
        this.executor = Executors.newSingleThreadExecutor(r -> {
            var thread = new Thread(r, "stash-manager-updater");
            thread.setDaemon(true);
            return thread;
        });
        this.currentArtifactPath = resolveCurrentArtifactPath();
        this.pluginsDirectory = resolvePluginsDirectory(this.currentArtifactPath);
        this.runtimeMcVersion = MinecraftCodec.CODEC.getMinecraftVersion();
        this.updateTarget = resolveUpdateTarget(runtimeMcVersion).orElse(null);
        if (updateTarget == null) {
            logger.warn("Automatic updates are unavailable for ZenithProxy Minecraft version {}.", runtimeMcVersion);
        } else if (!BuildConstants.MC_VERSION.equals(updateTarget)) {
            logger.warn(
                "Installed plugin targets Minecraft {}, but ZenithProxy runs {}. A compatible update is required.",
                BuildConstants.MC_VERSION,
                updateTarget
            );
        }
        this.snapshot = new StatusSnapshot(
            UpdateState.NOT_CHECKED,
            BuildConstants.VERSION,
            null,
            null,
            null,
            null,
            null
        );
    }

    public void scheduleStartupCheck() {
        if (!config.updateCheckOnLoad) return;
        executor.execute(() -> {
            var result = runUpdateCycle(config.updateAutoDownload);
            logAutomaticResult(result);
        });
    }

    public StatusSnapshot getSnapshot() {
        return snapshot;
    }

    public UpdateResult checkForUpdates() {
        return runUpdateCycle(false);
    }

    public UpdateResult checkAndStageUpdate() {
        return runUpdateCycle(true);
    }

    private synchronized UpdateResult runUpdateCycle(final boolean stageIfAvailable) {
        snapshot = snapshot.withState(stageIfAvailable ? UpdateState.STAGING : UpdateState.CHECKING)
            .withLastError(null);

        final Instant checkedAt = Instant.now();
        try {
            if (updateTarget == null) {
                throw new IOException("Unsupported ZenithProxy Minecraft version: " + runtimeMcVersion + ".");
            }

            final boolean targetRepair = !BuildConstants.MC_VERSION.equals(updateTarget);
            final var release = fetchLatestRelease(updateTarget);
            if (targetRepair && compareVersions(BuildConstants.VERSION, release.version()) > 0) {
                throw new IOException(
                    "Latest compatible release " + release.version()
                        + " is older than installed plugin " + BuildConstants.VERSION + "."
                );
            }
            final var baseSnapshot = snapshot
                .withLatestVersion(release.version())
                .withLastCheckedAt(checkedAt)
                .withLastError(null);

            if (!targetRepair && !isNewerVersion(BuildConstants.VERSION, release.version())) {
                snapshot = baseSnapshot.withState(UpdateState.UP_TO_DATE);
                return new UpdateResult(
                    UpdateOutcome.UP_TO_DATE,
                    "Stash Manager is already up to date (" + BuildConstants.VERSION + ").",
                    BuildConstants.VERSION,
                    release.version(),
                    null
                );
            }

            if (!stageIfAvailable) {
                snapshot = baseSnapshot.withState(UpdateState.UPDATE_AVAILABLE);
                final String message = targetRepair
                    ? "Installed plugin targets Minecraft " + BuildConstants.MC_VERSION
                        + ", but ZenithProxy runs " + updateTarget + ". A compatible replacement is available."
                    : "Update available: " + release.version() + " (current " + BuildConstants.VERSION + ").";
                return new UpdateResult(
                    UpdateOutcome.UPDATE_AVAILABLE,
                    message,
                    BuildConstants.VERSION,
                    release.version(),
                    null
                );
            }

            return stageRelease(release, checkedAt, baseSnapshot, targetRepair);
        } catch (Exception e) {
            snapshot = snapshot
                .withState(UpdateState.FAILED)
                .withLastError(e.getMessage())
                .withLastCheckedAt(checkedAt);
            return new UpdateResult(
                UpdateOutcome.FAILED,
                "Update check failed: " + e.getMessage(),
                BuildConstants.VERSION,
                snapshot.latestVersion(),
                null
            );
        }
    }

    private UpdateResult stageRelease(final ReleaseInfo release,
                                      final Instant checkedAt,
                                      final StatusSnapshot baseSnapshot,
                                      final boolean targetRepair) throws IOException, InterruptedException {
        Files.createDirectories(pluginsDirectory);

        final Path targetPath = resolveStageTarget(release);
        if (Files.exists(targetPath)) {
            final Optional<JarMetadata> existingMetadata = readJarMetadata(targetPath);
            if (existingMetadata.isPresent()
                && BuildConstants.PLUGIN_ID.equals(existingMetadata.get().pluginId())
                && release.version().equals(normalizeVersion(existingMetadata.get().version()))
                && existingMetadata.get().mcVersions().contains(updateTarget)) {
                snapshot = baseSnapshot
                    .withState(UpdateState.STAGED)
                    .withStagedVersion(release.version())
                    .withStagedJarName(targetPath.getFileName().toString())
                    .withLastCheckedAt(checkedAt);
                return new UpdateResult(
                    UpdateOutcome.ALREADY_STAGED,
                    "Version " + release.version() + " is already staged for the next restart.",
                    BuildConstants.VERSION,
                    release.version(),
                    targetPath
                );
            }
        }

        snapshot = baseSnapshot.withState(UpdateState.STAGING);
        final Path tempFile = Files.createTempFile(pluginsDirectory, BuildConstants.PLUGIN_ID + "-", ".download");
        try {
            downloadReleaseAsset(release, tempFile);

            final JarMetadata metadata = readJarMetadata(tempFile)
                .orElseThrow(() -> new IOException("Downloaded file is missing zenithproxy.plugin.json"));
            final String downloadedVersion = normalizeVersion(metadata.version());
            if (!BuildConstants.PLUGIN_ID.equals(metadata.pluginId())) {
                throw new IOException("Downloaded jar has unexpected plugin id: " + metadata.pluginId());
            }
            if (!release.version().equals(downloadedVersion)) {
                throw new IOException("Downloaded jar version " + downloadedVersion + " does not match release " + release.version());
            }
            if (!metadata.mcVersions().contains(updateTarget)) {
                throw new IOException("Downloaded jar does not support Minecraft " + updateTarget + ".");
            }
            final int versionComparison = compareVersions(BuildConstants.VERSION, downloadedVersion);
            if (versionComparison > 0 || (!targetRepair && versionComparison == 0)) {
                throw new IOException("Downloaded jar is not newer than the current version.");
            }

            moveIntoPlace(tempFile, targetPath);
            snapshot = baseSnapshot
                .withState(UpdateState.STAGED)
                .withStagedVersion(downloadedVersion)
                .withStagedJarName(targetPath.getFileName().toString())
                .withLastCheckedAt(checkedAt)
                .withLastError(null);
            return new UpdateResult(
                UpdateOutcome.STAGED,
                "Downloaded " + downloadedVersion + " to " + targetPath.getFileName() + ". Restart ZenithProxy to load it.",
                BuildConstants.VERSION,
                downloadedVersion,
                targetPath
            );
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private ReleaseInfo fetchLatestRelease(final String target) throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(LATEST_RELEASE_URI)
            .header("User-Agent", "StashManager/" + BuildConstants.VERSION)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        final HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("GitHub API returned HTTP " + response.statusCode());
        }

        final JsonElement releaseJson = JsonParser.parseString(response.body());
        if (!releaseJson.isJsonObject()) throw new IOException("Invalid response from GitHub.");
        final JsonObject json = releaseJson.getAsJsonObject();
        final String tagName = getRequiredString(json, "tag_name");
        final String latestVersion = normalizeVersion(tagName);
        final JsonArray assets = json.getAsJsonArray("assets");
        if (assets == null || assets.isEmpty()) {
            throw new IOException("Latest release has no downloadable jar assets.");
        }

        final AssetInfo asset = chooseJarAsset(assets, target)
            .orElseThrow(() -> new IOException(
                "Latest release has no jar for Minecraft " + target + "."
            ));
        return new ReleaseInfo(tagName, latestVersion, asset.name(), URI.create(asset.downloadUrl()));
    }

    private Optional<AssetInfo> chooseJarAsset(final JsonArray assets, final String target) {
        final String targetSuffix = "+" + target + ".jar";
        for (JsonElement element : assets) {
            final JsonObject asset = element.getAsJsonObject();
            final AssetInfo assetInfo = new AssetInfo(
                getRequiredString(asset, "name"),
                getRequiredString(asset, "browser_download_url")
            );
            if (assetInfo.name().endsWith(targetSuffix)) return Optional.of(assetInfo);
        }
        return Optional.empty();
    }

    private void downloadReleaseAsset(final ReleaseInfo release, final Path tempFile)
        throws IOException, InterruptedException {
        final HttpRequest request = HttpRequest.newBuilder(release.downloadUri())
            .header("User-Agent", "StashManager/" + BuildConstants.VERSION)
            .header("Accept", "application/octet-stream")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        final HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            throw new IOException("Asset download returned HTTP " + response.statusCode());
        }

        try (InputStream in = response.body()) {
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Optional<JarMetadata> readJarMetadata(final Path jarPath) throws IOException {
        try (ZipFile zipFile = new ZipFile(jarPath.toFile())) {
            final ZipEntry entry = Objects.requireNonNullElse(
                zipFile.getEntry("zenithproxy.plugin.json"),
                zipFile.getEntry("plugin.json")
            );
            if (entry == null) return Optional.empty();
            try (InputStream in = zipFile.getInputStream(entry)) {
                final JsonElement metadataJson = JsonParser.parseString(new String(in.readAllBytes()));
                if (!metadataJson.isJsonObject()) return Optional.empty();
                final JsonObject json = metadataJson.getAsJsonObject();
                final String pluginId = getRequiredString(json, "id");
                final String version = readVersionField(json.get("version"));
                final Set<String> mcVersions = new HashSet<>();
                final JsonArray mcVersionArray = json.getAsJsonArray("mcVersions");
                if (mcVersionArray != null) {
                    for (JsonElement mcVersion : mcVersionArray) {
                        mcVersions.add(mcVersion.getAsString());
                    }
                }
                return Optional.of(new JarMetadata(pluginId, version, Set.copyOf(mcVersions)));
            }
        }
    }

    private void moveIntoPlace(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path resolveStageTarget(final ReleaseInfo release) {
        Path target = pluginsDirectory.resolve(release.assetName()).toAbsolutePath().normalize();
        if (target.equals(currentArtifactPath)) {
            target = pluginsDirectory.resolve(addSuffixBeforeExtension(release.assetName(), "-" + release.version() + "-staged"))
                .toAbsolutePath()
                .normalize();
        }
        return target;
    }

    private Path resolveCurrentArtifactPath() {
        try {
            if (StashManagerPlugin.class.getProtectionDomain() == null
                || StashManagerPlugin.class.getProtectionDomain().getCodeSource() == null) {
                return Path.of(".").toAbsolutePath().normalize();
            }
            return Path.of(StashManagerPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath()
                .normalize();
        } catch (Exception e) {
            logger.warn("Unable to resolve plugin artifact path: {}", e.getMessage());
            return Path.of(".").toAbsolutePath().normalize();
        }
    }

    private Path resolvePluginsDirectory(final Path artifactPath) {
        if (Files.isRegularFile(artifactPath) && artifactPath.toString().endsWith(".jar")) {
            final Path parent = artifactPath.getParent();
            if (parent != null) return parent;
        }
        return Path.of("plugins").toAbsolutePath().normalize();
    }

    private void logAutomaticResult(final UpdateResult result) {
        switch (result.outcome()) {
            case UP_TO_DATE -> logger.info(result.message());
            case UPDATE_AVAILABLE -> logger.warn("{} Enable auto-download or run `stash update` to stage it.", result.message());
            case STAGED, ALREADY_STAGED -> logger.warn("{} Restart ZenithProxy to apply it.", result.message());
            case FAILED -> logger.warn(result.message());
        }
    }

    private static String getRequiredString(final JsonObject json, final String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return json.get(key).getAsString();
    }

    private static String readVersionField(final JsonElement versionElement) {
        if (versionElement == null || versionElement.isJsonNull()) {
            throw new IllegalArgumentException("Missing version field in plugin metadata.");
        }
        if (versionElement.isJsonPrimitive()) {
            return versionElement.getAsString();
        }
        if (versionElement.isJsonObject()) {
            final JsonObject versionObject = versionElement.getAsJsonObject();
            if (versionObject.has("version")) return versionObject.get("version").getAsString();
            if (versionObject.has("major") && versionObject.has("minor") && versionObject.has("patch")) {
                return versionObject.get("major").getAsInt()
                    + "." + versionObject.get("minor").getAsInt()
                    + "." + versionObject.get("patch").getAsInt();
            }
        }
        throw new IllegalArgumentException("Unsupported version format in plugin metadata.");
    }

    private static boolean isNewerVersion(final String currentVersion, final String otherVersion) {
        return compareVersions(normalizeVersion(currentVersion), normalizeVersion(otherVersion)) < 0;
    }

    private static int compareVersions(final String left, final String right) {
        final String[] leftParts = normalizeVersion(left).split("\\.");
        final String[] rightParts = normalizeVersion(right).split("\\.");
        final int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            final int leftValue = i < leftParts.length ? Integer.parseInt(leftParts[i]) : 0;
            final int rightValue = i < rightParts.length ? Integer.parseInt(rightParts[i]) : 0;
            if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
        }
        return 0;
    }

    private static String normalizeVersion(final String rawVersion) {
        String normalized = rawVersion.trim();
        if (normalized.startsWith("refs/tags/")) {
            normalized = normalized.substring("refs/tags/".length());
        }
        if (normalized.length() > 1 && (normalized.charAt(0) == 'v' || normalized.charAt(0) == 'V')
            && Character.isDigit(normalized.charAt(1))) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static Optional<String> resolveUpdateTarget(final String runtimeVersion) {
        return switch (runtimeVersion) {
            case "1.21.4", "1.21.8", "1.21.11", "26.1.2" -> Optional.of(runtimeVersion);
            case "26.2" -> Optional.of("26.2.0");
            default -> Optional.empty();
        };
    }

    private static String addSuffixBeforeExtension(final String fileName, final String suffix) {
        final int dot = fileName.lastIndexOf('.');
        if (dot <= 0) return fileName + suffix;
        return fileName.substring(0, dot) + suffix + fileName.substring(dot);
    }

    public enum UpdateState {
        NOT_CHECKED,
        CHECKING,
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        STAGING,
        STAGED,
        FAILED
    }

    public enum UpdateOutcome {
        UP_TO_DATE,
        UPDATE_AVAILABLE,
        STAGED,
        ALREADY_STAGED,
        FAILED
    }

    public record UpdateResult(
        UpdateOutcome outcome,
        String message,
        String currentVersion,
        String latestVersion,
        Path stagedPath
    ) { }

    public record StatusSnapshot(
        UpdateState state,
        String currentVersion,
        String latestVersion,
        String stagedVersion,
        String stagedJarName,
        String lastError,
        Instant lastCheckedAt
    ) {
        public StatusSnapshot withState(final UpdateState nextState) {
            return new StatusSnapshot(nextState, currentVersion, latestVersion, stagedVersion, stagedJarName, lastError, lastCheckedAt);
        }

        public StatusSnapshot withLatestVersion(final String version) {
            return new StatusSnapshot(state, currentVersion, version, stagedVersion, stagedJarName, lastError, lastCheckedAt);
        }

        public StatusSnapshot withStagedVersion(final String version) {
            return new StatusSnapshot(state, currentVersion, latestVersion, version, stagedJarName, lastError, lastCheckedAt);
        }

        public StatusSnapshot withStagedJarName(final String jarName) {
            return new StatusSnapshot(state, currentVersion, latestVersion, stagedVersion, jarName, lastError, lastCheckedAt);
        }

        public StatusSnapshot withLastError(final String error) {
            return new StatusSnapshot(state, currentVersion, latestVersion, stagedVersion, stagedJarName, error, lastCheckedAt);
        }

        public StatusSnapshot withLastCheckedAt(final Instant checkedAt) {
            return new StatusSnapshot(state, currentVersion, latestVersion, stagedVersion, stagedJarName, lastError, checkedAt);
        }
    }

    private record ReleaseInfo(String tagName, String version, String assetName, URI downloadUri) { }

    private record AssetInfo(String name, String downloadUrl) { }

    private record JarMetadata(String pluginId, String version, Set<String> mcVersions) { }
}
