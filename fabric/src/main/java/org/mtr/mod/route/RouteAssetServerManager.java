package org.mtr.mod.route;

import org.mtr.core.operation.ListDataResponse;
import org.mtr.core.serializer.ReaderBase;
import org.mtr.core.serializer.SerializedDataBase;
import org.mtr.core.serializer.WriterBase;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.mapping.holder.MinecraftServer;
import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mapping.mapper.PersistenceStateExtension;
import org.mtr.mod.Init;
import org.mtr.mod.data.PersistentStateData;
import org.mtr.mod.packet.PacketRouteAssetChunk;
import org.mtr.mod.packet.PacketRouteAssetChunkRequest;
import org.mtr.mod.packet.PacketRouteAssetRefresh;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class RouteAssetServerManager implements AutoCloseable {

	private final RouteAssetRepository repository;
	private final RouteAssetDataMirror mirror;
	private final RouteAssetDependencyCatalog catalog;
	private final RenderFunction renderer;
	private final int generationThreads;
	private final String resourceFingerprint;
	private final RouteAssetMetrics metrics;
	private final ExecutorService coordinator;
	private final ExecutorService workers;
	private final Object stateLock = new Object();
	private final Set<String> generatedLanguages = new HashSet<>();
	private final Set<RouteAssetKey> observedKeys = new TreeSet<>();
	private final Map<RouteAssetKey, String> dependencyFingerprints = new HashMap<>();
	private final Map<UUID, RouteAssetHello> playerCapabilities = new HashMap<>();
	private final Map<UUID, ServerPlayerEntity> connectedPlayers = new HashMap<>();
	private final Set<String> publishedLanguages = new HashSet<>();
	private final RefreshNotificationSender refreshNotificationSender;
	private final Map<UUID, RateWindow> observedRateWindows = new HashMap<>();
	private final Map<UUID, PacketFallbackAuthorization> packetFallbackAuthorizations = new HashMap<>();
	private final Map<UUID, PacketFallbackConnection> packetFallbackConnections = new HashMap<>();
	private final Map<Long, FullRefreshContext> fullRefreshContexts = new HashMap<>();
	private final ArrayDeque<String> configuredAssetDiagnostics = new ArrayDeque<>();
	private volatile int originPort;
	private volatile MinecraftServer activeServer;
	private volatile boolean closed;
	private boolean coordinatorScheduled;
	private long desiredGeneration;
	private GenerationRequest pending;
	private List<ConfiguredSignAssetIndex.Entry> configuredSignEntries = Collections.emptyList();
	private long nextPeriodicRefreshMillis;

	private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
	private static final int MAX_TRACKED_PACKET_FALLBACK_TRANSFERS = 1024;
	private static final int MAX_CONFIGURED_ASSET_DIAGNOSTICS = 64;
	private static final Set<String> FIXED_ASSET_LANGUAGES = Set.of("NORMAL", "CJK", "LATIN");
	private static final long PERIODIC_REFRESH_MILLIS = 60_000;
	private static final SerializedDataBase EMPTY_REQUEST = new SerializedDataBase() {
		@Override public void updateData(ReaderBase readerBase) { }
		@Override public void serializeData(WriterBase writerBase) { }
	};

	public RouteAssetServerManager(Path outputRoot, int generationThreads, int retainedRevisions) throws IOException {
		this(new RouteAssetRepository(outputRoot, RouteAssetProtocol.RENDERER_VERSION, retainedRevisions), new RouteAssetDataMirror(), new RouteAssetDependencyCatalog(), defaultRenderer(), generationThreads, defaultResourceFingerprint(), new RouteAssetMetrics());
	}

	public RouteAssetServerManager(RouteAssetRepository repository, RouteAssetDataMirror mirror, RouteAssetDependencyCatalog catalog, RenderFunction renderer, int generationThreads, String resourceFingerprint, RouteAssetMetrics metrics) {
		this(repository, mirror, catalog, renderer, generationThreads, resourceFingerprint, metrics, null);
	}

	public RouteAssetServerManager(RouteAssetRepository repository, RouteAssetDataMirror mirror, RouteAssetDependencyCatalog catalog, RenderFunction renderer, int generationThreads, String resourceFingerprint, RouteAssetMetrics metrics, RefreshNotificationSender refreshNotificationSender) {
		this.repository = Objects.requireNonNull(repository, "repository");
		this.mirror = Objects.requireNonNull(mirror, "mirror");
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.renderer = Objects.requireNonNull(renderer, "renderer");
		this.generationThreads = Math.max(1, Math.min(4, generationThreads));
		this.resourceFingerprint = RouteAssetHash.requireValid(resourceFingerprint);
		this.metrics = Objects.requireNonNull(metrics, "metrics");
		this.refreshNotificationSender = refreshNotificationSender == null ? this::sendRefreshNotification : refreshNotificationSender;
		coordinator = Executors.newSingleThreadExecutor(threadFactory("mtr-route-assets-coordinator-"));
		workers = Executors.newFixedThreadPool(this.generationThreads, threadFactory("mtr-route-assets-worker-"));
		generatedLanguages.add("NORMAL");
		try {
			dependencyFingerprints.putAll(repository.loadDependencyFingerprints());
			final RouteAssetRepository.RouteAssetHead head = repository.loadHead();
			if (!head.getRevision().isEmpty()) repository.loadManifest(head.getRevision()).getEntries().keySet().forEach(key -> {
				if (FIXED_ASSET_LANGUAGES.contains(key.getVariant().getLanguage())) publishedLanguages.add(key.getVariant().getLanguage());
			});
			generatedLanguages.addAll(publishedLanguages);
		} catch (IOException exception) {
			Init.LOGGER.warn("Unable to load persisted route texture dependency fingerprints", exception);
		}
	}

	public void start(MinecraftServer server) {
		activeServer = Objects.requireNonNull(server, "server");
		nextPeriodicRefreshMillis = saturatingAdd(System.currentTimeMillis(), PERIODIC_REFRESH_MILLIS);
		requestRefresh(server, "startup");
	}

	public void tick() {
		final MinecraftServer server = activeServer;
		if (server == null) return;
		final long now = System.currentTimeMillis();
		if (now >= nextPeriodicRefreshMillis) {
			nextPeriodicRefreshMillis = saturatingAdd(now, PERIODIC_REFRESH_MILLIS);
			requestRefresh(server, "periodic-core-poll");
		}
	}

	public void requestRefresh(MinecraftServer server, String cause) {
		final Set<String> worlds = new HashSet<>();
		MinecraftServerHelper.iterateWorlds(server, serverWorld -> worlds.add(Init.getWorldId(new World(serverWorld.data))));
		final long generation = beginFullRefresh(worlds, cause, captureConfiguredSigns(server));
		MinecraftServerHelper.iterateWorlds(server, serverWorld -> {
			final World world = new World(serverWorld.data);
			final String worldId = Init.getWorldId(world);
			Init.sendMessageC2S(OperationProcessor.LIST_DATA, server, world, EMPTY_REQUEST, response -> acceptFullData(worldId, generation, Utilities.getJsonObjectFromData(response)), ListDataResponse.class);
		});
	}

	public long beginFullRefresh(Set<String> worldIds, String cause) {
		final List<ConfiguredSignAssetIndex.Entry> configured;
		synchronized (stateLock) {
			configured = configuredSignEntries;
		}
		return beginFullRefresh(worldIds, cause, configured);
	}

	public void acceptFullData(String worldId, long generation, JsonObject json) {
		if (mirror.acceptListJson(generation, worldId, json)) {
			mirror.snapshot(generation).ifPresent(snapshot -> {
				final FullRefreshContext context;
				synchronized (stateLock) {
					context = fullRefreshContexts.remove(generation);
				}
				submitSnapshot(snapshot, context == null ? Collections.emptyList() : context.configuredSignEntries, context == null ? "refresh" : context.cause);
			});
		}
	}

	public void acceptUpdate(String worldId, JsonObject json) {
		if (mirror.applyUpdateJson(worldId, json)) submitSnapshot(mirror.currentSnapshot(), "update:" + worldId);
	}

	public void acceptDelete(String worldId, JsonObject json) {
		if (mirror.applyDeleteJson(worldId, json)) submitSnapshot(mirror.currentSnapshot(), "delete:" + worldId);
	}

	public void handleHello(ServerPlayerEntity player, RouteAssetHello hello) {
		handleHello(player.getUuid(), hello, player);
	}

	private void handleHello(UUID uuid, RouteAssetHello hello) {
		handleHello(uuid, hello, null);
	}

	private void handleHello(UUID uuid, RouteAssetHello hello, ServerPlayerEntity player) {
		synchronized (stateLock) {
			playerCapabilities.put(uuid, hello);
			if (compatible(hello) && player != null) connectedPlayers.put(uuid, player);
			else if (!compatible(hello)) connectedPlayers.remove(uuid);
			packetFallbackAuthorizations.remove(uuid);
			packetFallbackConnections.computeIfAbsent(uuid, ignored -> new PacketFallbackConnection()).advanceGeneration(hello.getRequestNonce());
			if (compatible(hello) && generatedLanguages.add(hello.getLanguage())) {
				submitSnapshot(mirror.currentSnapshot(), "language:" + hello.getLanguage());
			}
		}
	}

	public void handleObservedKeys(ServerPlayerEntity player, List<RouteAssetKey> keys) {
		final UUID uuid = player.getUuid();
		final RouteAssetDataMirror.Snapshot snapshot = mirror.currentSnapshot();
		boolean changed = false;
		synchronized (stateLock) {
			final long now = System.currentTimeMillis();
			final RateWindow rateWindow = observedRateWindows.computeIfAbsent(uuid, ignored -> new RateWindow(now));
			for (final RouteAssetKey key : keys) {
				if (!rateWindow.tryAcquire(now) || observedKeys.size() >= RouteAssetProtocol.MAX_QUEUED_OBSERVED_KEYS) break;
				final java.util.Optional<RouteAssetDependencyCatalog.Entry> entry = catalog.resolveObserved(key, snapshot, resourceFingerprint);
				if (entry.isPresent() && observedKeys.add(key)) changed = true;
			}
		}
		if (changed) submitSnapshot(snapshot, "observed:" + uuid);
	}

	public void onPlayerDisconnect(UUID uuid) {
		synchronized (stateLock) {
			playerCapabilities.remove(uuid);
			connectedPlayers.remove(uuid);
			observedRateWindows.remove(uuid);
			packetFallbackAuthorizations.remove(uuid);
			packetFallbackConnections.remove(uuid);
		}
	}

	public long submitSnapshot(RouteAssetDataMirror.Snapshot snapshot, String cause) {
		final List<ConfiguredSignAssetIndex.Entry> configured;
		synchronized (stateLock) {
			configured = configuredSignEntries;
		}
		return submitSnapshot(snapshot, configured, cause);
	}

	public long submitSnapshot(RouteAssetDataMirror.Snapshot snapshot, List<ConfiguredSignAssetIndex.Entry> configuredSigns, String cause) {
		final List<ConfiguredSignAssetIndex.Entry> checkedConfiguredSigns = immutableConfiguredSigns(configuredSigns);
		synchronized (stateLock) {
			if (closed) return -1;
			final long generation = ++desiredGeneration;
			configuredSignEntries = checkedConfiguredSigns;
			pending = new GenerationRequest(generation, snapshot, checkedConfiguredSigns, Collections.singletonList(boundedCause(cause)));
			metrics.queued();
			if (!coordinatorScheduled) {
				coordinatorScheduled = true;
				coordinator.execute(this::drain);
			}
			stateLock.notifyAll();
			return generation;
		}
	}

	public RouteAssetDataMirror.Snapshot getCurrentSnapshot() {
		return mirror.currentSnapshot();
	}

	public void configuredSignsChanged(MinecraftServer server, String cause) {
		final List<ConfiguredSignAssetIndex.Entry> configured = captureConfiguredSigns(Objects.requireNonNull(server, "server"));
		final RouteAssetDataMirror.Snapshot snapshot = mirror.currentSnapshot();
		if (snapshot.getDimensions().isEmpty()) {
			synchronized (stateLock) {
				configuredSignEntries = configured;
			}
			requestRefresh(server, cause);
		} else {
			submitSnapshot(snapshot, configured, cause);
		}
	}

	public boolean awaitIdle(long timeoutMillis) throws InterruptedException {
		final long deadline = System.currentTimeMillis() + timeoutMillis;
		synchronized (stateLock) {
			while (coordinatorScheduled || pending != null) {
				final long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0) return false;
				stateLock.wait(remaining);
			}
			return true;
		}
	}

	public RouteAssetNegotiation negotiate(RouteAssetHello hello) throws IOException {
		if (!compatible(hello)) return new RouteAssetNegotiation(RouteAssetNegotiation.Mode.FALLBACK, RouteAssetProtocol.RENDERER_VERSION, "", "", "", originPort);
		synchronized (stateLock) {
			if (!publishedLanguages.contains(hello.getLanguage())) return new RouteAssetNegotiation(RouteAssetNegotiation.Mode.FALLBACK, RouteAssetProtocol.RENDERER_VERSION, "", "", "", originPort);
		}
		final RouteAssetVariant variant = RouteAssetVariant.parse(Math.min(3, hello.getResolution()), hello.getLanguage(), "align=LEFT");
		final RouteAssetNegotiation negotiation = repository.negotiate(hello.getCachedRevision(), variant);
		return new RouteAssetNegotiation(negotiation.getMode(), negotiation.getRendererVersion(), negotiation.getAuthoritativeRevision(), negotiation.getDocumentHash(), negotiation.getPublicBaseUrl(), originPort);
	}

	public RouteAssetNegotiation negotiate(UUID playerId, RouteAssetHello hello) throws IOException {
		Objects.requireNonNull(playerId, "playerId");
		Objects.requireNonNull(hello, "hello");
		handleHello(playerId, hello);
		return authorizePacketFallback(playerId, hello, negotiate(hello));
	}

	private RouteAssetNegotiation authorizePacketFallback(UUID playerId, RouteAssetHello hello, RouteAssetNegotiation negotiation) throws IOException {
		if (!hello.isHttpSupported() || !hello.isPacketFallbackSupported() || negotiation.getMode() == RouteAssetNegotiation.Mode.FALLBACK || negotiation.getMode() == RouteAssetNegotiation.Mode.DISABLED) return negotiation;
		final RouteAssetManifest manifest = repository.loadManifest(negotiation.getAuthoritativeRevision());
		final Set<String> activeHashes = new HashSet<>();
		for (final Map.Entry<RouteAssetKey, RouteAssetManifest.Entry> entry : manifest.getEntries().entrySet()) {
			if (RouteAssetVariantPolicy.isActive(entry.getKey(), hello.getResolution(), hello.getLanguage())) activeHashes.add(entry.getValue().getHash());
		}
		final PacketFallbackAuthorization authorization = new PacketFallbackAuthorization(hello.getRequestNonce(), negotiation.getDocumentHash(), activeHashes);
		synchronized (stateLock) {
			if (hello.equals(playerCapabilities.get(playerId))) packetFallbackAuthorizations.put(playerId, authorization);
		}
		return negotiation;
	}

	public RouteAssetNegotiation negotiate(ServerPlayerEntity player, RouteAssetHello hello) throws IOException {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(hello, "hello");
		handleHello(player, hello);
		return authorizePacketFallback(player.getUuid(), hello, negotiate(hello));
	}

	public List<PacketRouteAssetChunk.ChunkPayload> handlePacketFallbackRequest(UUID playerId, PacketRouteAssetChunkRequest.RequestPayload request) {
		Objects.requireNonNull(playerId, "playerId");
		Objects.requireNonNull(request, "request");
		final PacketFallbackAuthorization authorization;
		synchronized (stateLock) {
			authorization = packetFallbackAuthorizations.get(playerId);
			if (authorization == null || !authorization.allows(request)) return Collections.emptyList();
		}
		try {
			final RouteAssetCas.MediaType mediaType = request.getObjectType() == PacketRouteAssetChunkRequest.ObjectType.PNG ? RouteAssetCas.MediaType.PNG : RouteAssetCas.MediaType.JSON;
			final RouteAssetCas cas = repository.getCas();
			final String hash = request.getExpectedHash();
			final Path candidate = cas.resolvePublicObject("v" + RouteAssetProtocol.RENDERER_VERSION, hash.substring(0, 2), hash, mediaType.getExtension());
			if (!Files.isRegularFile(candidate)) return Collections.emptyList();
			final long size = Files.size(candidate);
			if (size <= 0 || size > RouteAssetProtocol.MAX_PACKET_FALLBACK_OBJECT_BYTES || request.getExpectedLength() >= 0 && request.getExpectedLength() != size) return Collections.emptyList();
			final Path verified = cas.find(hash, mediaType).orElse(null);
			if (verified == null) return Collections.emptyList();
			final byte[] bytes = Files.readAllBytes(verified);
			if (bytes.length != size || !RouteAssetHash.sha256(bytes).equals(hash)) return Collections.emptyList();
			synchronized (stateLock) {
				if (authorization != packetFallbackAuthorizations.get(playerId) || !authorization.allows(request)) return Collections.emptyList();
				final PacketFallbackConnection connection = packetFallbackConnections.get(playerId);
				if (connection == null || !connection.authorize(request, bytes.length, System.currentTimeMillis())) return Collections.emptyList();
			}
			return PacketRouteAssetChunk.split(request, bytes);
		} catch (IOException | RuntimeException exception) {
			return Collections.emptyList();
		}
	}

	public void setOriginPort(int originPort) {
		if (originPort < 0 || originPort > 65535) throw new IllegalArgumentException("Invalid route asset origin port");
		this.originPort = originPort;
	}

	public RouteAssetRepository getRepository() { return repository; }
	public RouteAssetMetrics getMetrics() { return metrics; }
	public int getGenerationThreads() { return generationThreads; }
	public String getResourceFingerprint() { return resourceFingerprint; }
	public List<String> getConfiguredAssetDiagnostics() {
		synchronized (stateLock) {
			return List.copyOf(configuredAssetDiagnostics);
		}
	}

	@Override
	public void close() {
		synchronized (stateLock) {
			if (closed) return;
			closed = true;
			activeServer = null;
			desiredGeneration++;
			pending = null;
			playerCapabilities.clear();
			connectedPlayers.clear();
			fullRefreshContexts.clear();
			stateLock.notifyAll();
		}
		coordinator.shutdownNow();
		workers.shutdownNow();
		try {
			coordinator.awaitTermination(5, TimeUnit.SECONDS);
			workers.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	private void notifyPublishedRevision(String revision) {
		final List<RefreshTarget> targets = new ArrayList<>();
		synchronized (stateLock) {
			playerCapabilities.forEach((playerId, hello) -> {
				if (compatible(hello)) targets.add(new RefreshTarget(playerId, hello.getRequestNonce()));
			});
		}
		for (final RefreshTarget target : targets) refreshNotificationSender.send(target.playerId, new PacketRouteAssetRefresh.RefreshPayload(revision, target.connectionNonce));
	}

	private void sendRefreshNotification(UUID playerId, PacketRouteAssetRefresh.RefreshPayload payload) {
		final MinecraftServer server = activeServer;
		if (server == null) return;
		server.execute(() -> {
			final ServerPlayerEntity player;
			synchronized (stateLock) {
				final RouteAssetHello hello = playerCapabilities.get(playerId);
				player = connectedPlayers.get(playerId);
				if (closed || player == null || hello == null || hello.getRequestNonce() != payload.getConnectionNonce()) return;
			}
			Init.REGISTRY.sendPacketToClient(player, new PacketRouteAssetRefresh(payload));
		});
	}

	private void drain() {
		while (true) {
			final GenerationRequest request;
			synchronized (stateLock) {
				request = pending;
				pending = null;
				if (request == null || closed) {
					coordinatorScheduled = false;
					stateLock.notifyAll();
					return;
				}
			}
			generate(request);
		}
	}

	private void generate(GenerationRequest request) {
		final long started = System.currentTimeMillis();
		try {
			final TreeMap<RouteAssetKey, RouteAssetDependencyCatalog.Entry> entries = new TreeMap<>();
			final Set<String> languages;
			synchronized (stateLock) {
				languages = new HashSet<>(generatedLanguages);
			}
			for (final String language : languages) entries.putAll(catalog.enumerateFixed(request.snapshot, resourceFingerprint, language));
			final Set<String> configuredRouteSignIdentities = new HashSet<>();
			final Set<RouteAssetKey> configuredAssetKeys = new HashSet<>();
			for (final ConfiguredSignAssetIndex.Entry configured : request.configuredSignEntries) {
				if (configured.isRouteSign()) {
					configuredRouteSignIdentities.add(configured.canonicalAssetIdentity());
					for (final String language : FIXED_ASSET_LANGUAGES) {
						for (int resolution = 0; resolution <= 3; resolution++) {
							catalog.resolveConfiguredRouteSign(configured, resolution, language, request.snapshot, resourceFingerprint).ifPresent(entry -> {
								entries.put(entry.getKey(), entry);
								configuredAssetKeys.add(entry.getKey());
							});
						}
					}
				} else {
					final DestinationSignConfiguredEntry destination = configured.getDestinationSign();
					for (int resolution = 0; resolution <= 3; resolution++) {
						final RouteAssetKey key = RouteAssetCanonicalKeyFactory.destinationSign(configured.getDimension(), destination.getSourceStationId(),
								destination.getDestinationStationIds(), destination.getCustomHeader(), resolution,
								destination.getStyle(), destination.getWidthBlocks(), destination.getHeightBlocks(), destination.isShowEta(), destination.getRoutesPerBlockHeight());
						catalog.resolveDestinationSign(key, request.snapshot, resourceFingerprint).ifPresent(entry -> {
							entries.put(entry.getKey(), entry);
							configuredAssetKeys.add(entry.getKey());
						});
					}
				}
			}
			final Set<RouteAssetKey> observed;
			synchronized (stateLock) { observed = new TreeSet<>(observedKeys); }
			for (final RouteAssetKey key : observed) {
				if (!isInactiveExplicitRouteSign(key, configuredRouteSignIdentities)) {
					catalog.resolveObserved(key, request.snapshot, resourceFingerprint).ifPresent(entry -> entries.put(key, entry));
				}
			}

			final RouteAssetRepository.RouteAssetHead head = repository.loadHead();
			final RouteAssetManifest previous = head.getRevision().isEmpty() ? RouteAssetManifest.builder().build() : repository.loadManifest(head.getRevision());
			final TreeMap<RouteAssetKey, RouteAssetManifest.Entry> nextEntries = new TreeMap<>();
			final TreeMap<RouteAssetKey, String> nextDependencies = new TreeMap<>();
			final List<RenderJob> renderJobs = new ArrayList<>();
			long reused = 0;
			for (final RouteAssetDependencyCatalog.Entry entry : entries.values()) {
				final RouteAssetManifest.Entry oldEntry = previous.getEntries().get(entry.getKey());
				final String knownDependency = dependencyFingerprints.getOrDefault(entry.getKey(), oldEntry == null ? "" : oldEntry.getDependencyFingerprint());
				nextDependencies.put(entry.getKey(), entry.getDependencyFingerprint());
				if (oldEntry != null && knownDependency.equals(entry.getDependencyFingerprint()) && repository.getCas().find(oldEntry.getHash(), RouteAssetCas.MediaType.PNG).isPresent()) {
					nextEntries.put(entry.getKey(), oldEntry);
					reused++;
					metrics.reused();
				} else {
					final Future<RenderedEntry> future = workers.submit(() -> {
						if (isStale(request.generation)) throw new CancellationException("Stale route asset generation");
						return render(entry);
					});
					renderJobs.add(new RenderJob(entry, future, configuredAssetKeys.contains(entry.getKey())));
				}
			}
			long bytes = 0;
			for (final RenderJob job : renderJobs) {
				if (isStale(request.generation)) {
					renderJobs.forEach(pendingJob -> pendingJob.future.cancel(true));
					metrics.cancelled();
					return;
				}
				final RenderedEntry rendered;
				try {
					rendered = job.future.get();
				} catch (ExecutionException exception) {
					if (exception.getCause() instanceof CancellationException) {
						renderJobs.forEach(pendingJob -> pendingJob.future.cancel(true));
						metrics.cancelled();
						return;
					}
					if (job.configuredAsset) {
						final RouteAssetManifest.Entry oldEntry = previous.getEntries().get(job.entry.getKey());
						if (oldEntry != null && repository.getCas().find(oldEntry.getHash(), RouteAssetCas.MediaType.PNG).isPresent()) {
							nextEntries.put(job.entry.getKey(), oldEntry);
							nextDependencies.put(job.entry.getKey(), oldEntry.getDependencyFingerprint());
							reused++;
						} else {
							nextDependencies.remove(job.entry.getKey());
						}
						recordConfiguredAssetFailure(job.entry.getKey(), exception.getCause());
						continue;
					}
					throw exception;
				}
				bytes += rendered.bytes;
				final RouteAssetManifest.Entry oldEntry = previous.getEntries().get(rendered.key);
				if (oldEntry != null && oldEntry.getHash().equals(rendered.hash)) {
					nextEntries.put(rendered.key, oldEntry);
				} else {
					nextEntries.put(rendered.key, new RouteAssetManifest.Entry(rendered.hash, rendered.dependencyFingerprint));
				}
			}
			if (isStale(request.generation)) {
				metrics.cancelled();
				return;
			}
			final RouteAssetManifest.Builder builder = RouteAssetManifest.builder();
			nextEntries.forEach(builder::put);
			final RouteAssetManifest next = builder.build();
			if (!head.getRevision().isEmpty() && next.getRevision().equals(previous.getRevision())) {
				synchronized (stateLock) {
					dependencyFingerprints.clear();
					dependencyFingerprints.putAll(nextDependencies);
				}
				persistDependencyFingerprints(nextDependencies);
				return;
			}
			final RouteAssetManifestDiff diff = RouteAssetManifestDiff.between(previous.getRevision(), previous, next);
			repository.publish(next, request.causes);
			if (isStale(request.generation)) {
				// A newer request can arrive after HEAD switches; it will immediately supersede this complete transaction.
			}
			synchronized (stateLock) {
				dependencyFingerprints.clear();
				dependencyFingerprints.putAll(nextDependencies);
				publishedLanguages.clear();
				publishedLanguages.addAll(languages);
			}
			persistDependencyFingerprints(nextDependencies);
			notifyPublishedRevision(next.getRevision());
			final int[] counts = count(diff);
			final RouteAssetMetrics.Summary summary = new RouteAssetMetrics.Summary(next.getRevision(), counts[0], counts[1], counts[2], counts[3], System.currentTimeMillis() - started, bytes, reused, originPort);
			metrics.summary(summary);
			Init.LOGGER.info("route_texture side=server event=revision revision={} add={} modify={} delete={} move={} duration_ms={} queue={} cancelled={} failed={} bytes={} reused={} cas_bytes={} origin_port={}", next.getRevision(), counts[0], counts[1], counts[2], counts[3], summary.getDurationMillis(), pendingCount(), metrics.getCancelledGenerations(), metrics.getFailedGenerations(), bytes, reused, 0, originPort);
		} catch (CancellationException exception) {
			metrics.cancelled();
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			metrics.cancelled();
		} catch (ExecutionException | IOException | RuntimeException exception) {
			metrics.failed();
			Init.LOGGER.warn("route_texture side=server event=generation_failed generation={} cause={}", request.generation, exception.toString());
		}
	}

	private void persistDependencyFingerprints(Map<RouteAssetKey, String> fingerprints) {
		try {
			repository.saveDependencyFingerprints(fingerprints);
		} catch (IOException | RuntimeException exception) {
			Init.LOGGER.warn("Unable to persist route texture dependency fingerprints", exception);
		}
	}

	private void recordConfiguredAssetFailure(RouteAssetKey key, Throwable throwable) {
		final String message = key + ": " + (throwable == null ? "unknown failure" : throwable.toString());
		synchronized (stateLock) {
			while (configuredAssetDiagnostics.size() >= MAX_CONFIGURED_ASSET_DIAGNOSTICS) configuredAssetDiagnostics.removeFirst();
			configuredAssetDiagnostics.addLast(message);
		}
		Init.LOGGER.warn("route_texture side=server event=configured_asset_failed key={} cause={}", key, throwable == null ? "unknown" : throwable.toString());
	}

	private RenderedEntry render(RouteAssetDependencyCatalog.Entry entry) throws IOException {
		final RouteAssetImage image;
		try {
			image = renderer.render(entry.getKey(), entry.getSnapshot());
		} catch (Exception exception) {
			throw new IOException("Unable to render route asset " + entry.getKey(), exception);
		}
		final byte[] png = image.toPng();
		final String hash = repository.getCas().putPng(png);
		metrics.rendered(png.length);
		return new RenderedEntry(entry.getKey(), hash, entry.getDependencyFingerprint(), png.length);
	}

	private boolean compatible(RouteAssetHello hello) {
		return hello.getProtocolVersion() == RouteAssetProtocol.PROTOCOL_VERSION && hello.getRendererVersion() == RouteAssetProtocol.RENDERER_VERSION && hello.getResolution() <= 3 && hello.getResourceFingerprint().equals(resourceFingerprint);
	}

	private boolean isStale(long generation) {
		synchronized (stateLock) {
			return closed || generation != desiredGeneration;
		}
	}

	private int pendingCount() {
		synchronized (stateLock) {
			return pending == null ? 0 : 1;
		}
	}

	private static int[] count(RouteAssetManifestDiff diff) {
		final int[] result = new int[4];
		for (final RouteAssetManifestDiff.Change change : diff.getChanges()) {
			switch (change.getOperation()) {
				case ADD: result[0]++; break;
				case MODIFY: result[1]++; break;
				case DELETE: result[2]++; break;
				case MOVE: result[3]++; break;
			}
		}
		return result;
	}

	private static ThreadFactory threadFactory(String prefix) {
		return runnable -> {
			final Thread thread = new Thread(runnable, prefix + THREAD_COUNTER.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		};
	}

	private static String boundedCause(String cause) {
		final String value = Objects.requireNonNull(cause, "cause").trim();
		if (value.isEmpty() || value.length() > RouteAssetProtocol.MAX_KEY_UTF8_BYTES) throw new IllegalArgumentException("Invalid route asset refresh cause");
		return value;
	}

	private long beginFullRefresh(Set<String> worldIds, String cause, List<ConfiguredSignAssetIndex.Entry> configuredSigns) {
		final long generation = mirror.beginGeneration(worldIds);
		final FullRefreshContext context = new FullRefreshContext(boundedCause(cause), immutableConfiguredSigns(configuredSigns));
		synchronized (stateLock) {
			configuredSignEntries = context.configuredSignEntries;
			fullRefreshContexts.entrySet().removeIf(entry -> entry.getKey() < generation - 8);
			fullRefreshContexts.put(generation, context);
		}
		return generation;
	}

	private List<ConfiguredSignAssetIndex.Entry> captureConfiguredSigns(MinecraftServer server) {
		final List<ConfiguredSignAssetIndex.Entry> configured = new ArrayList<>();
		MinecraftServerHelper.iterateWorlds(server, serverWorld -> {
			final PersistentStateData persistentState = (PersistentStateData) PersistenceStateExtension.register(serverWorld, PersistentStateData::new, Init.MOD_ID);
			final String dimension = Init.getWorldId(new World(serverWorld.data));
			configured.addAll(persistentState.getConfiguredSignAssetIndex().snapshot(dimension));
		});
		return immutableConfiguredSigns(configured);
	}

	private static List<ConfiguredSignAssetIndex.Entry> immutableConfiguredSigns(List<ConfiguredSignAssetIndex.Entry> configuredSigns) {
		final List<ConfiguredSignAssetIndex.Entry> result = new ArrayList<>(Objects.requireNonNull(configuredSigns, "configuredSigns"));
		result.forEach(entry -> Objects.requireNonNull(entry, "configuredSign"));
		Collections.sort(result);
		return Collections.unmodifiableList(result);
	}

	private static boolean isInactiveExplicitRouteSign(RouteAssetKey key, Set<String> configuredIdentities) {
		if (key.getType() != RouteAssetType.ROUTE_MAP || !RouteMapPurpose.ROUTE_SIGN.name().equals(key.getVariant().getParameters().get("p"))) return false;
		final RouteAssetCanonicalKeyFactory.RouteMapParameters parameters = RouteAssetCanonicalKeyFactory.decodeRouteMap(key);
		if (parameters == null) return true;
		if (!parameters.styleMode.isExplicit()) return false;
		final String platformIdentity = parameters.platformIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(":"));
		final String configuredIdentity = key.getDimension() + "|ROUTE_SIGN|" + platformIdentity + '|'
				+ parameters.customPlatformHeader.length() + ':' + parameters.customPlatformHeader + '|' + parameters.styleMode.name();
		return !configuredIdentities.contains(configuredIdentity);
	}

	private static RenderFunction defaultRenderer() throws IOException {
		final RouteAssetSourceImages sources = new RouteAssetSourceImages(RouteAssetServerManager::readAsset);
		final RouteAssetTextRasterizer text = RouteAssetTextRasterizer.fromFonts(readAsset("font/noto-sans-semibold.ttf"), readAsset("font/noto-serif-cjk-tc-semibold.ttf"));
		final RouteAssetRenderer renderer = new RouteAssetRenderer();
		return (key, snapshot) -> renderer.render(key, snapshot, text, sources);
	}

	private static String defaultResourceFingerprint() throws IOException {
		return RouteAssetResourceFingerprint.compute(RouteAssetServerManager::readAsset);
	}

	private static byte[] readAsset(String path) throws IOException {
		try (final InputStream input = RouteAssetServerManager.class.getClassLoader().getResourceAsStream("assets/mtr/" + path)) {
			if (input == null) throw new IOException("Missing route asset resource: " + path);
			final ByteArrayOutputStream output = new ByteArrayOutputStream();
			final byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
			return output.toByteArray();
		}
	}

	@FunctionalInterface
	public interface RenderFunction {
		RouteAssetImage render(RouteAssetKey key, RouteAssetRenderSnapshot snapshot) throws Exception;
	}

	@FunctionalInterface
	public interface RefreshNotificationSender {
		void send(UUID playerId, PacketRouteAssetRefresh.RefreshPayload payload);
	}

	private static final class RefreshTarget {
		private final UUID playerId;
		private final long connectionNonce;

		private RefreshTarget(UUID playerId, long connectionNonce) {
			this.playerId = playerId;
			this.connectionNonce = connectionNonce;
		}
	}

	private static final class GenerationRequest {
		private final long generation;
		private final RouteAssetDataMirror.Snapshot snapshot;
		private final List<ConfiguredSignAssetIndex.Entry> configuredSignEntries;
		private final List<String> causes;

		private GenerationRequest(long generation, RouteAssetDataMirror.Snapshot snapshot, List<ConfiguredSignAssetIndex.Entry> configuredSignEntries, List<String> causes) {
			this.generation = generation;
			this.snapshot = snapshot;
			this.configuredSignEntries = configuredSignEntries;
			this.causes = causes;
		}
	}

	private static final class FullRefreshContext {
		private final String cause;
		private final List<ConfiguredSignAssetIndex.Entry> configuredSignEntries;

		private FullRefreshContext(String cause, List<ConfiguredSignAssetIndex.Entry> configuredSignEntries) {
			this.cause = cause;
			this.configuredSignEntries = configuredSignEntries;
		}
	}

	private static final class RenderedEntry {
		private final RouteAssetKey key;
		private final String hash;
		private final String dependencyFingerprint;
		private final long bytes;

		private RenderedEntry(RouteAssetKey key, String hash, String dependencyFingerprint, long bytes) {
			this.key = key;
			this.hash = hash;
			this.dependencyFingerprint = dependencyFingerprint;
			this.bytes = bytes;
		}
	}

	private static final class RenderJob {
		private final RouteAssetDependencyCatalog.Entry entry;
		private final Future<RenderedEntry> future;
		private final boolean configuredAsset;

		private RenderJob(RouteAssetDependencyCatalog.Entry entry, Future<RenderedEntry> future, boolean configuredAsset) {
			this.entry = entry;
			this.future = future;
			this.configuredAsset = configuredAsset;
		}
	}

	private static final class RateWindow {
		private long startedMillis;
		private int count;

		private RateWindow(long startedMillis) {
			this.startedMillis = startedMillis;
		}

		private boolean tryAcquire(long now) {
			if (now - startedMillis >= 60_000) {
				startedMillis = now;
				count = 0;
			}
			return count++ < RouteAssetProtocol.MAX_OBSERVED_KEYS_PER_PLAYER_PER_MINUTE;
		}
	}

	private static final class PacketFallbackAuthorization {
		private final long generation;
		private final String documentHash;
		private final Set<String> activePngHashes;

		private PacketFallbackAuthorization(long generation, String documentHash, Set<String> activePngHashes) {
			this.generation = generation;
			this.documentHash = documentHash;
			this.activePngHashes = Collections.unmodifiableSet(new HashSet<>(activePngHashes));
		}

		private boolean allows(PacketRouteAssetChunkRequest.RequestPayload request) {
			if (request.getGeneration() != generation) return false;
			return request.getObjectType() == PacketRouteAssetChunkRequest.ObjectType.JSON ? !documentHash.isEmpty() && documentHash.equals(request.getExpectedHash()) : activePngHashes.contains(request.getExpectedHash());
		}
	}

	private static final class PacketFallbackConnection {
		private final Map<Long, PacketFallbackTransfer> transfers = new HashMap<>();
		private long generation;
		private long sentBytes;

		private void advanceGeneration(long generation) {
			this.generation = generation;
			transfers.clear();
		}

		private boolean authorize(PacketRouteAssetChunkRequest.RequestPayload request, int length, long now) {
			transfers.entrySet().removeIf(entry -> elapsedAtLeast(now, entry.getValue().createdMillis, RouteAssetProtocol.PACKET_FALLBACK_EXPIRY_MILLIS));
			if (request.getGeneration() != generation || length <= 0 || length > RouteAssetProtocol.MAX_PACKET_FALLBACK_OBJECT_BYTES) return false;
			final PacketFallbackTransfer existing = transfers.get(request.getTransferId());
			if (existing != null) return existing.matches(request, length);
			if (transfers.size() >= MAX_TRACKED_PACKET_FALLBACK_TRANSFERS || length > RouteAssetProtocol.MAX_PACKET_FALLBACK_CONNECTION_BYTES - sentBytes) return false;
			sentBytes += length;
			transfers.put(request.getTransferId(), new PacketFallbackTransfer(request, length, now));
			return true;
		}
	}

	private static final class PacketFallbackTransfer {
		private final PacketRouteAssetChunkRequest.ObjectType objectType;
		private final String hash;
		private final int length;
		private final long expectedLength;
		private final long createdMillis;

		private PacketFallbackTransfer(PacketRouteAssetChunkRequest.RequestPayload request, int length, long createdMillis) {
			objectType = request.getObjectType();
			hash = request.getExpectedHash();
			this.length = length;
			expectedLength = request.getExpectedLength();
			this.createdMillis = createdMillis;
		}

		private boolean matches(PacketRouteAssetChunkRequest.RequestPayload request, int length) {
			return objectType == request.getObjectType() && hash.equals(request.getExpectedHash()) && this.length == length && expectedLength == request.getExpectedLength();
		}
	}

	private static boolean elapsedAtLeast(long now, long started, long duration) {
		return now >= started && now - started >= duration;
	}

	private static long saturatingAdd(long value, long increment) {
		return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
	}
}
