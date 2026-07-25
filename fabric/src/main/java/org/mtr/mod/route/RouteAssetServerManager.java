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
import org.mtr.mod.Init;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
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
	private final TreeMap<RouteAssetKey, RouteAssetDependencyCatalog.Entry> observedEntries = new TreeMap<>();
	private final Map<RouteAssetKey, String> dependencyFingerprints = new HashMap<>();
	private final Map<UUID, RouteAssetHello> playerCapabilities = new HashMap<>();
	private final Map<UUID, RateWindow> observedRateWindows = new HashMap<>();
	private volatile int originPort;
	private volatile boolean closed;
	private boolean coordinatorScheduled;
	private long desiredGeneration;
	private GenerationRequest pending;
	private String fullRefreshCause = "refresh";

	private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
	private static final SerializedDataBase EMPTY_REQUEST = new SerializedDataBase() {
		@Override public void updateData(ReaderBase readerBase) { }
		@Override public void serializeData(WriterBase writerBase) { }
	};

	public RouteAssetServerManager(Path outputRoot, int generationThreads, int retainedRevisions) throws IOException {
		this(new RouteAssetRepository(outputRoot, RouteAssetProtocol.RENDERER_VERSION, retainedRevisions), new RouteAssetDataMirror(), new RouteAssetDependencyCatalog(), defaultRenderer(), generationThreads, defaultResourceFingerprint(), new RouteAssetMetrics());
	}

	public RouteAssetServerManager(RouteAssetRepository repository, RouteAssetDataMirror mirror, RouteAssetDependencyCatalog catalog, RenderFunction renderer, int generationThreads, String resourceFingerprint, RouteAssetMetrics metrics) {
		this.repository = Objects.requireNonNull(repository, "repository");
		this.mirror = Objects.requireNonNull(mirror, "mirror");
		this.catalog = Objects.requireNonNull(catalog, "catalog");
		this.renderer = Objects.requireNonNull(renderer, "renderer");
		this.generationThreads = Math.max(1, Math.min(4, generationThreads));
		this.resourceFingerprint = RouteAssetHash.requireValid(resourceFingerprint);
		this.metrics = Objects.requireNonNull(metrics, "metrics");
		coordinator = Executors.newSingleThreadExecutor(threadFactory("mtr-route-assets-coordinator-"));
		workers = Executors.newFixedThreadPool(this.generationThreads, threadFactory("mtr-route-assets-worker-"));
		generatedLanguages.add("NORMAL");
	}

	public void start(MinecraftServer server) {
		requestRefresh(server, "startup");
	}

	public void tick(MinecraftServer server) {
	}

	public void requestRefresh(MinecraftServer server, String cause) {
		final Set<String> worlds = new HashSet<>();
		MinecraftServerHelper.iterateWorlds(server, serverWorld -> worlds.add(Init.getWorldId(new World(serverWorld.data))));
		final long generation = beginFullRefresh(worlds, cause);
		MinecraftServerHelper.iterateWorlds(server, serverWorld -> {
			final World world = new World(serverWorld.data);
			final String worldId = Init.getWorldId(world);
			Init.sendMessageC2S(OperationProcessor.LIST_DATA, server, world, EMPTY_REQUEST, response -> acceptFullData(worldId, generation, Utilities.getJsonObjectFromData(response)), ListDataResponse.class);
		});
	}

	public long beginFullRefresh(Set<String> worldIds, String cause) {
		fullRefreshCause = boundedCause(cause);
		return mirror.beginGeneration(worldIds);
	}

	public void acceptFullData(String worldId, long generation, JsonObject json) {
		if (mirror.acceptListJson(generation, worldId, json)) {
			mirror.snapshot(generation).ifPresent(snapshot -> submitSnapshot(snapshot, fullRefreshCause));
		}
	}

	public void acceptUpdate(String worldId, JsonObject json) {
		if (mirror.applyUpdateJson(worldId, json)) submitSnapshot(mirror.currentSnapshot(), "update:" + worldId);
	}

	public void acceptDelete(String worldId, JsonObject json) {
		if (mirror.applyDeleteJson(worldId, json)) submitSnapshot(mirror.currentSnapshot(), "delete:" + worldId);
	}

	public void handleHello(ServerPlayerEntity player, RouteAssetHello hello) {
		final UUID uuid = player.getUuid();
		synchronized (stateLock) {
			playerCapabilities.put(uuid, hello);
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
				if (!rateWindow.tryAcquire(now) || observedEntries.size() >= RouteAssetProtocol.MAX_QUEUED_OBSERVED_KEYS) break;
				final java.util.Optional<RouteAssetDependencyCatalog.Entry> entry = catalog.resolveObserved(key, snapshot, resourceFingerprint);
				if (entry.isPresent() && observedEntries.put(key, entry.get()) == null) changed = true;
			}
		}
		if (changed) submitSnapshot(snapshot, "observed:" + uuid);
	}

	public void onPlayerDisconnect(UUID uuid) {
		synchronized (stateLock) {
			playerCapabilities.remove(uuid);
			observedRateWindows.remove(uuid);
		}
	}

	public long submitSnapshot(RouteAssetDataMirror.Snapshot snapshot, String cause) {
		synchronized (stateLock) {
			if (closed) return -1;
			final long generation = ++desiredGeneration;
			pending = new GenerationRequest(generation, snapshot, Collections.singletonList(boundedCause(cause)));
			metrics.queued();
			if (!coordinatorScheduled) {
				coordinatorScheduled = true;
				coordinator.execute(this::drain);
			}
			stateLock.notifyAll();
			return generation;
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
		final RouteAssetVariant variant = RouteAssetVariant.parse(Math.min(3, hello.getResolution()), hello.getLanguage(), "align=LEFT");
		final RouteAssetNegotiation negotiation = repository.negotiate(hello.getCachedRevision(), variant);
		return new RouteAssetNegotiation(negotiation.getMode(), negotiation.getRendererVersion(), negotiation.getAuthoritativeRevision(), negotiation.getDocumentHash(), negotiation.getPublicBaseUrl(), originPort);
	}

	public void setOriginPort(int originPort) {
		if (originPort < 0 || originPort > 65535) throw new IllegalArgumentException("Invalid route asset origin port");
		this.originPort = originPort;
	}

	public RouteAssetRepository getRepository() { return repository; }
	public RouteAssetMetrics getMetrics() { return metrics; }
	public int getGenerationThreads() { return generationThreads; }
	public String getResourceFingerprint() { return resourceFingerprint; }

	@Override
	public void close() {
		synchronized (stateLock) {
			if (closed) return;
			closed = true;
			desiredGeneration++;
			pending = null;
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
			synchronized (stateLock) {
				entries.putAll(observedEntries);
			}

			final RouteAssetRepository.RouteAssetHead head = repository.loadHead();
			final RouteAssetManifest previous = head.getRevision().isEmpty() ? RouteAssetManifest.builder().build() : repository.loadManifest(head.getRevision());
			final TreeMap<RouteAssetKey, RouteAssetManifest.Entry> nextEntries = new TreeMap<>();
			final TreeMap<RouteAssetKey, String> nextDependencies = new TreeMap<>();
			final List<Future<RenderedEntry>> futures = new ArrayList<>();
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
					futures.add(workers.submit(() -> render(entry)));
				}
			}
			long bytes = 0;
			for (final Future<RenderedEntry> future : futures) {
				final RenderedEntry rendered = future.get();
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
			}
			final int[] counts = count(diff);
			final RouteAssetMetrics.Summary summary = new RouteAssetMetrics.Summary(next.getRevision(), counts[0], counts[1], counts[2], counts[3], System.currentTimeMillis() - started, bytes, reused, originPort);
			metrics.summary(summary);
			Init.LOGGER.info("route_texture side=server event=revision revision={} add={} modify={} delete={} move={} duration_ms={} queue={} cancelled={} failed={} bytes={} reused={} cas_bytes={} origin_port={}", next.getRevision(), counts[0], counts[1], counts[2], counts[3], summary.getDurationMillis(), pendingCount(), metrics.getCancelledGenerations(), metrics.getFailedGenerations(), bytes, reused, 0, originPort);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			metrics.cancelled();
		} catch (ExecutionException | IOException | RuntimeException exception) {
			metrics.failed();
			Init.LOGGER.warn("route_texture side=server event=generation_failed generation={} cause={}", request.generation, exception.toString());
		}
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

	private static RenderFunction defaultRenderer() throws IOException {
		final RouteAssetSourceImages sources = new RouteAssetSourceImages(RouteAssetServerManager::readAsset);
		final RouteAssetTextRasterizer text = RouteAssetTextRasterizer.fromFonts(readAsset("font/noto-sans-semibold.ttf"), readAsset("font/noto-serif-cjk-tc-semibold.ttf"));
		final RouteAssetRenderer renderer = new RouteAssetRenderer();
		return (key, snapshot) -> renderer.render(key, snapshot, text, sources);
	}

	private static String defaultResourceFingerprint() throws IOException {
		final RouteAssetSourceImages sources = new RouteAssetSourceImages(RouteAssetServerManager::readAsset);
		for (final String path : List.of("textures/block/sign/arrow.png", "textures/block/sign/railway_interchange.png", "textures/block/sign/airplane.png")) sources.get(path);
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		output.write(sources.getFingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8));
		output.write(readAsset("font/noto-sans-semibold.ttf"));
		output.write(readAsset("font/noto-serif-cjk-tc-semibold.ttf"));
		return RouteAssetHash.sha256(output.toByteArray());
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

	private static final class GenerationRequest {
		private final long generation;
		private final RouteAssetDataMirror.Snapshot snapshot;
		private final List<String> causes;

		private GenerationRequest(long generation, RouteAssetDataMirror.Snapshot snapshot, List<String> causes) {
			this.generation = generation;
			this.snapshot = snapshot;
			this.causes = causes;
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
}
