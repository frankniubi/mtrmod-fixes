package org.mtr.mod.client.asset;

import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mod.InitClient;
import org.mtr.mod.config.Config;
import org.mtr.mod.packet.PacketRouteAssetHello;
import org.mtr.mod.packet.PacketRouteAssetManifest;
import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetHello;
import org.mtr.mod.route.RouteAssetManifest;
import org.mtr.mod.route.RouteAssetNegotiation;
import org.mtr.mod.route.RouteAssetProtocol;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class ClientRouteAssetManager {

	private final ClientRouteAssetDiskCache diskCache;
	private final LongSupplier clock;
	private final Supplier<Settings> settingsSupplier;
	private final Consumer<RouteAssetHello> helloSender;
	private final Consumer<DocumentRequest> documentRequestHandler;
	private final Runnable documentWorkCanceller;
	private final Executor maintenanceExecutor;
	private final Executor metadataExecutor;
	private final Executor clientExecutor;
	private final boolean available;
	private final ClientRouteAssetSession session = new ClientRouteAssetSession();
	private final AtomicBoolean maintenanceQueued = new AtomicBoolean();

	private String multiplayerAddress = "";
	private String currentServerId = "";
	private Set<String> pinnedHashes = Collections.emptySet();
	private Settings settings;
	private long nextMaintenanceMillis;
	private long diskCacheEpoch;

	private static final long MAINTENANCE_INTERVAL_MILLIS = 60_000;
	private static final AtomicInteger MAINTENANCE_THREAD_COUNTER = new AtomicInteger();

	public ClientRouteAssetManager(ClientRouteAssetDiskCache diskCache, LongSupplier clock, Supplier<Settings> settingsSupplier, Consumer<RouteAssetHello> helloSender, Consumer<DocumentRequest> documentRequestHandler) {
		this(diskCache, clock, settingsSupplier, helloSender, documentRequestHandler, () -> { });
	}

	public ClientRouteAssetManager(ClientRouteAssetDiskCache diskCache, LongSupplier clock, Supplier<Settings> settingsSupplier, Consumer<RouteAssetHello> helloSender, Consumer<DocumentRequest> documentRequestHandler, Runnable documentWorkCanceller) {
		this(diskCache, clock, settingsSupplier, helloSender, documentRequestHandler, documentWorkCanceller, Runnable::run);
	}

	public ClientRouteAssetManager(ClientRouteAssetDiskCache diskCache, LongSupplier clock, Supplier<Settings> settingsSupplier, Consumer<RouteAssetHello> helloSender, Consumer<DocumentRequest> documentRequestHandler, Runnable documentWorkCanceller, Executor maintenanceExecutor) {
		this(diskCache, clock, settingsSupplier, helloSender, documentRequestHandler, documentWorkCanceller, maintenanceExecutor, Runnable::run, Runnable::run);
	}

	public ClientRouteAssetManager(ClientRouteAssetDiskCache diskCache, LongSupplier clock, Supplier<Settings> settingsSupplier, Consumer<RouteAssetHello> helloSender, Consumer<DocumentRequest> documentRequestHandler, Runnable documentWorkCanceller, Executor maintenanceExecutor, Executor metadataExecutor, Executor clientExecutor) {
		this.diskCache = Objects.requireNonNull(diskCache, "diskCache");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier");
		this.helloSender = Objects.requireNonNull(helloSender, "helloSender");
		this.documentRequestHandler = Objects.requireNonNull(documentRequestHandler, "documentRequestHandler");
		this.documentWorkCanceller = Objects.requireNonNull(documentWorkCanceller, "documentWorkCanceller");
		this.maintenanceExecutor = Objects.requireNonNull(maintenanceExecutor, "maintenanceExecutor");
		this.metadataExecutor = Objects.requireNonNull(metadataExecutor, "metadataExecutor");
		this.clientExecutor = Objects.requireNonNull(clientExecutor, "clientExecutor");
		available = true;
	}

	private ClientRouteAssetManager(LongSupplier clock, Runnable documentWorkCanceller, Executor maintenanceExecutor) {
		diskCache = null;
		this.clock = Objects.requireNonNull(clock, "clock");
		settingsSupplier = () -> null;
		helloSender = hello -> { };
		documentRequestHandler = request -> { };
		this.documentWorkCanceller = Objects.requireNonNull(documentWorkCanceller, "documentWorkCanceller");
		this.maintenanceExecutor = Objects.requireNonNull(maintenanceExecutor, "maintenanceExecutor");
		metadataExecutor = maintenanceExecutor;
		clientExecutor = Runnable::run;
		available = false;
	}

	public static ClientRouteAssetManager getInstance() {
		return InstanceHolder.INSTANCE;
	}

	public synchronized void onJoin(String multiplayerAddress) {
		if (session.getState() != ClientRouteAssetSession.State.DISCONNECTED) {
			session.disconnect();
			cancelDocumentWork();
		}
		this.multiplayerAddress = multiplayerAddress == null ? "" : multiplayerAddress.trim();
		currentServerId = "";
		pinnedHashes = Collections.emptySet();
		nextMaintenanceMillis = 0;
		if (!available) {
			settings = null;
			session.join(true, false, clock.getAsLong());
			return;
		}
		diskCacheEpoch = diskCache.advanceSessionEpoch();
		try {
			settings = Objects.requireNonNull(settingsSupplier.get(), "settingsSupplier returned null");
		} catch (RuntimeException exception) {
			settings = null;
			session.join(true, false, clock.getAsLong());
			return;
		}
		final boolean eligible = !this.multiplayerAddress.isEmpty() && settings.getResolution() <= 3;
		final long generation = session.join(settings.isEnabled(), eligible, clock.getAsLong());
		if (!session.isCurrent(generation) || session.getState() != ClientRouteAssetSession.State.NEGOTIATING) return;
		scheduleHello(generation, this.multiplayerAddress, settings);
	}

	private void scheduleHello(long generation, String address, Settings settingsSnapshot) {
		try {
			metadataExecutor.execute(() -> {
				if (!isNegotiating(generation)) return;
				final String cachedRevision;
				try {
					diskCache.initialize();
					cachedRevision = diskCache.findRevision(address).orElse("");
				} catch (IOException | RuntimeException exception) {
					dispatchClient(generation, () -> session.fallback(generation));
					return;
				}
				if (!isNegotiating(generation)) return;
				dispatchClient(generation, () -> sendHello(generation, settingsSnapshot, cachedRevision));
			});
		} catch (RuntimeException exception) {
			session.fallback(generation);
		}
	}

	private void dispatchClient(long generation, Runnable action) {
		try {
			clientExecutor.execute(action);
		} catch (RuntimeException exception) {
			session.fallback(generation);
		}
	}

	private synchronized void sendHello(long generation, Settings settingsSnapshot, String cachedRevision) {
		if (!isNegotiating(generation) || settings != settingsSnapshot) return;
		try {
			helloSender.accept(new RouteAssetHello(
					RouteAssetProtocol.PROTOCOL_VERSION,
					RouteAssetProtocol.RENDERER_VERSION,
					settingsSnapshot.getResolution(),
					settingsSnapshot.getLanguage(),
					settingsSnapshot.getResourceFingerprint(),
					cachedRevision,
					true,
					false,
					settingsSnapshot.getMaximumRevisionDownloadBytes(),
					generation
			));
			session.markHelloSent(generation, clock.getAsLong());
		} catch (RuntimeException exception) {
			session.fallback(generation);
		}
	}

	private boolean isNegotiating(long generation) {
		return session.isCurrent(generation) && session.getState() == ClientRouteAssetSession.State.NEGOTIATING;
	}

	public synchronized void handleManifest(PacketRouteAssetManifest.ManifestPayload payload) {
		Objects.requireNonNull(payload, "payload");
		final long generation = session.getGeneration();
		if (!available || !session.isCurrent(generation) || session.getState() != ClientRouteAssetSession.State.NEGOTIATING || payload.getRequestNonce() != generation) return;
		if (settings == null || payload.getRendererVersion() != RouteAssetProtocol.RENDERER_VERSION || !settings.getResourceFingerprint().equals(payload.getResourceFingerprint())) {
			session.fallback(generation);
			return;
		}

		if (payload.getMode() == RouteAssetNegotiation.Mode.DISABLED) {
			session.disable(generation);
			return;
		}
		if (payload.getMode() == RouteAssetNegotiation.Mode.FALLBACK) {
			session.fallback(generation);
			return;
		}

		try {
			currentServerId = UUID.fromString(payload.getServerId()).toString();
			if (payload.getMode() == RouteAssetNegotiation.Mode.UNCHANGED) {
				if (!session.beginCacheValidation(generation)) return;
				scheduleUnchangedValidation(generation, diskCacheEpoch, payload, settings, multiplayerAddress);
				return;
			}
			if (payload.getMode() != RouteAssetNegotiation.Mode.DIFF && payload.getMode() != RouteAssetNegotiation.Mode.SNAPSHOT) {
				session.fallback(generation);
				return;
			}
			requireCanonicalDocumentPath(payload);
			if (!session.beginSync(generation, payload.getDocumentHash())) return;
			scheduleAssociation(generation, multiplayerAddress, currentServerId);
			documentRequestHandler.accept(new DocumentRequest(generation, payload, multiplayerAddress));
		} catch (RuntimeException exception) {
			session.fallback(generation);
		}
	}

	private void scheduleUnchangedValidation(long generation, long expectedCacheEpoch, PacketRouteAssetManifest.ManifestPayload payload, Settings settingsSnapshot, String address) {
		try {
			maintenanceExecutor.execute(() -> {
				if (!session.isCurrent(generation)) return;
				RouteAssetManifest manifest = null;
				boolean valid = false;
				try {
					diskCache.associate(address, payload.getServerId());
					manifest = diskCache.loadManifest(payload.getServerId()).orElse(null);
					valid = manifest != null && manifest.getRevision().equals(payload.getAuthoritativeRevision()) && diskCache.hasEveryObject(manifest, settingsSnapshot.getResolution(), settingsSnapshot.getLanguage());
				} catch (IOException | RuntimeException ignored) {
				}
				final Set<String> pins = manifest == null ? Collections.emptySet() : hashes(manifest, settingsSnapshot.getResolution(), settingsSnapshot.getLanguage());
				if (finishCacheValidation(generation, valid, payload.getServerId(), pins) && valid) {
					try {
						diskCache.prune(settingsSnapshot.getMaximumCacheBytes(), pins, expectedCacheEpoch);
					} catch (IOException | RuntimeException ignored) {
					}
				}
			});
		} catch (RejectedExecutionException exception) {
			session.complete(generation, false);
		}
	}

	private synchronized boolean finishCacheValidation(long generation, boolean valid, String serverId, Set<String> pins) {
		if (!session.isCurrent(generation) || session.getState() != ClientRouteAssetSession.State.SYNCING) return false;
		if (valid) {
			currentServerId = serverId;
			pinnedHashes = pins;
			nextMaintenanceMillis = clock.getAsLong() + MAINTENANCE_INTERVAL_MILLIS;
		} else {
			pinnedHashes = Collections.emptySet();
		}
		return session.complete(generation, valid);
	}

	private void scheduleAssociation(long generation, String address, String serverId) {
		try {
			maintenanceExecutor.execute(() -> {
				if (!session.isCurrent(generation)) return;
				try {
					diskCache.associate(address, serverId);
				} catch (IOException | RuntimeException ignored) {
				}
			});
		} catch (RejectedExecutionException ignored) {
		}
	}

	public synchronized void tick(long nowMillis) {
		session.tick(nowMillis);
		if (available && settings != null && session.getState() == ClientRouteAssetSession.State.READY && nowMillis >= nextMaintenanceMillis && maintenanceQueued.compareAndSet(false, true)) {
			final long generation = session.getGeneration();
			final long maximumBytes = settings.getMaximumCacheBytes();
			final Set<String> pins = pinnedHashes;
			final long expectedCacheEpoch = diskCacheEpoch;
			nextMaintenanceMillis = nowMillis + MAINTENANCE_INTERVAL_MILLIS;
			try {
				maintenanceExecutor.execute(() -> {
					try {
						if (isReady(generation)) diskCache.prune(maximumBytes, pins, expectedCacheEpoch);
					} catch (IOException | RuntimeException ignored) {
					} finally {
						maintenanceQueued.set(false);
					}
				});
			} catch (RejectedExecutionException exception) {
				maintenanceQueued.set(false);
			}
		}
	}

	public synchronized void onVariantChanged() {
		if (session.getState() == ClientRouteAssetSession.State.DISCONNECTED) return;
		onJoin(multiplayerAddress);
	}

	public void beginRenderFrame(int maximumUploads, long budgetNanos) {
		if (maximumUploads < 0 || budgetNanos < 0) throw new IllegalArgumentException("Route asset upload budgets cannot be negative");
	}

	public synchronized CompletableFuture<Boolean> repairCurrentServerCache() {
		if (!available || session.getState() == ClientRouteAssetSession.State.DISCONNECTED || currentServerId.isEmpty()) return CompletableFuture.completedFuture(false);
		final String serverId = currentServerId;
		diskCacheEpoch = diskCache.advanceSessionEpoch();
		session.disconnect();
		cancelDocumentWork();
		session.join(settings != null && settings.isEnabled(), false, clock.getAsLong());
		currentServerId = "";
		pinnedHashes = Collections.emptySet();
		nextMaintenanceMillis = 0;
		final CompletableFuture<Boolean> completion = new CompletableFuture<>();
		try {
			maintenanceExecutor.execute(() -> {
				try {
					diskCache.repair(serverId);
					completion.complete(true);
				} catch (IOException | RuntimeException exception) {
					completion.complete(false);
				}
			});
		} catch (RejectedExecutionException exception) {
			completion.complete(false);
		}
		return completion;
	}

	public synchronized void onDisconnect() {
		if (available) diskCacheEpoch = diskCache.advanceSessionEpoch();
		if (session.getState() != ClientRouteAssetSession.State.DISCONNECTED) {
			session.disconnect();
			cancelDocumentWork();
		}
		multiplayerAddress = "";
		currentServerId = "";
		pinnedHashes = Collections.emptySet();
		settings = null;
		nextMaintenanceMillis = 0;
	}

	public boolean isCurrent(long generation) {
		return session.isCurrent(generation);
	}

	public ClientRouteAssetSession.State getState() {
		return session.getState();
	}

	public long getGeneration() {
		return session.getGeneration();
	}

	private void cancelDocumentWork() {
		try {
			documentWorkCanceller.run();
		} catch (RuntimeException ignored) {
		}
	}

	private synchronized boolean isReady(long generation) {
		return session.isCurrent(generation) && session.getState() == ClientRouteAssetSession.State.READY;
	}

	private static Set<String> hashes(RouteAssetManifest manifest, int resolution, String language) {
		final Set<String> hashes = new HashSet<>();
		manifest.getEntries().forEach((key, entry) -> {
			if (key.getVariant().getResolution() == resolution && key.getVariant().getLanguage().equals(language)) hashes.add(entry.getHash());
		});
		return Collections.unmodifiableSet(hashes);
	}

	private static void requireCanonicalDocumentPath(PacketRouteAssetManifest.ManifestPayload payload) {
		final String hash = payload.getDocumentHash();
		final String expectedPath = "v" + payload.getRendererVersion() + '/' + hash.substring(0, 2) + '/' + hash + ".json";
		if (!expectedPath.equals(payload.getDocumentPath())) throw new IllegalArgumentException("Route asset document path does not match its hash");
	}

	private static ClientRouteAssetManager createDefault() {
		final ExecutorService backgroundExecutor = createMaintenanceExecutor();
		final Executor clientExecutor = MinecraftClient.getInstance()::execute;
		return createResilient(
				() -> {
					final Path cacheRoot = MinecraftClient.getInstance().getRunDirectoryMapped().toPath().resolve(Path.of("cache", "mtr", "route-textures"));
					return new ClientRouteAssetDiskCache(cacheRoot, RouteAssetProtocol.RENDERER_VERSION);
				},
				() -> RouteAssetProtocol.BUNDLED_RESOURCE_FINGERPRINT,
				System::currentTimeMillis,
				fingerprint -> new Settings(
						Config.getClient().getServerRouteTexturesEnabled(),
						Config.getClient().getDynamicTextureResolution(),
						Config.getClient().getLanguageDisplay().name(),
						fingerprint,
						RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES,
						(long) Config.getClient().getRouteTextureCacheMiB() * 1024 * 1024
				),
				hello -> InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketRouteAssetHello(hello)),
				request -> { },
				() -> { },
				backgroundExecutor,
				backgroundExecutor,
				clientExecutor
		);
	}

	static ClientRouteAssetManager createResilient(IoSupplier<ClientRouteAssetDiskCache> cacheLoader, IoSupplier<String> fingerprintLoader, LongSupplier clock, Function<String, Settings> settingsFactory, Consumer<RouteAssetHello> helloSender, Consumer<DocumentRequest> documentRequestHandler, Runnable documentWorkCanceller, Executor maintenanceExecutor) {
		return createResilient(cacheLoader, fingerprintLoader, clock, settingsFactory, helloSender, documentRequestHandler, documentWorkCanceller, maintenanceExecutor, maintenanceExecutor, Runnable::run);
	}

	static ClientRouteAssetManager createResilient(IoSupplier<ClientRouteAssetDiskCache> cacheLoader, IoSupplier<String> fingerprintLoader, LongSupplier clock, Function<String, Settings> settingsFactory, Consumer<RouteAssetHello> helloSender, Consumer<DocumentRequest> documentRequestHandler, Runnable documentWorkCanceller, Executor maintenanceExecutor, Executor metadataExecutor, Executor clientExecutor) {
		try {
			final ClientRouteAssetDiskCache cache = Objects.requireNonNull(cacheLoader.get(), "cacheLoader returned null");
			final String fingerprint = RouteAssetHash.requireValid(fingerprintLoader.get());
			return new ClientRouteAssetManager(
					cache,
					clock,
					() -> settingsFactory.apply(fingerprint),
					helloSender,
					documentRequestHandler,
					documentWorkCanceller,
					maintenanceExecutor,
					metadataExecutor,
					clientExecutor
			);
		} catch (IOException | RuntimeException exception) {
			return new ClientRouteAssetManager(clock, documentWorkCanceller, maintenanceExecutor);
		}
	}

	static ExecutorService createMaintenanceExecutor() {
		return Executors.newSingleThreadExecutor(runnable -> {
			final Thread thread = new Thread(runnable, "mtr-route-assets-client-cache-" + MAINTENANCE_THREAD_COUNTER.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
	}

	private static final class InstanceHolder {
		private static final ClientRouteAssetManager INSTANCE = createDefault();
	}

	@FunctionalInterface
	interface IoSupplier<T> {
		T get() throws IOException;
	}

	public static final class Settings {
		private final boolean enabled;
		private final int resolution;
		private final String language;
		private final String resourceFingerprint;
		private final long maximumRevisionDownloadBytes;
		private final long maximumCacheBytes;

		public Settings(boolean enabled, int resolution, String language, String resourceFingerprint, long maximumRevisionDownloadBytes) {
			this(enabled, resolution, language, resourceFingerprint, maximumRevisionDownloadBytes, Long.MAX_VALUE);
		}

		public Settings(boolean enabled, int resolution, String language, String resourceFingerprint, long maximumRevisionDownloadBytes, long maximumCacheBytes) {
			if (resolution < 0 || resolution > 8 || maximumRevisionDownloadBytes < 0 || maximumRevisionDownloadBytes > RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES || maximumCacheBytes < 0) {
				throw new IllegalArgumentException("Invalid client route asset settings");
			}
			this.enabled = enabled;
			this.resolution = resolution;
			final String configuredLanguage = Objects.requireNonNull(language, "language").trim().toUpperCase(java.util.Locale.ROOT);
			switch (configuredLanguage) {
				case "NORMAL": this.language = "NORMAL"; break;
				case "CJK":
				case "CJK_ONLY": this.language = "CJK"; break;
				case "LATIN":
				case "NON_CJK_ONLY": this.language = "LATIN"; break;
				default: throw new IllegalArgumentException("Invalid route asset language");
			}
			this.resourceFingerprint = RouteAssetHash.requireValid(resourceFingerprint);
			this.maximumRevisionDownloadBytes = maximumRevisionDownloadBytes;
			this.maximumCacheBytes = maximumCacheBytes;
		}

		public boolean isEnabled() { return enabled; }
		public int getResolution() { return resolution; }
		public String getLanguage() { return language; }
		public String getResourceFingerprint() { return resourceFingerprint; }
		public long getMaximumRevisionDownloadBytes() { return maximumRevisionDownloadBytes; }
		public long getMaximumCacheBytes() { return maximumCacheBytes; }
	}

	public static final class DocumentRequest {
		private final long generation;
		private final PacketRouteAssetManifest.ManifestPayload payload;
		private final String multiplayerAddress;
		private ClientRouteAssetUrlPolicy urlPolicy;
		private ClientRouteAssetUrlPolicy.ResolvedTarget documentTarget;

		private DocumentRequest(long generation, PacketRouteAssetManifest.ManifestPayload payload, String multiplayerAddress) {
			this.generation = generation;
			this.payload = payload;
			this.multiplayerAddress = multiplayerAddress;
		}

		public long getGeneration() { return generation; }
		public PacketRouteAssetManifest.ManifestPayload getPayload() { return payload; }
		public synchronized ClientRouteAssetUrlPolicy getUrlPolicy() {
			resolveDocumentTarget();
			return urlPolicy;
		}
		public synchronized ClientRouteAssetUrlPolicy.ResolvedTarget getDocumentTarget() { return resolveDocumentTarget(); }
		public URI getDocumentUri() { return getDocumentTarget().getUri(); }

		private ClientRouteAssetUrlPolicy.ResolvedTarget resolveDocumentTarget() {
			if (documentTarget == null) {
				urlPolicy = new ClientRouteAssetUrlPolicy(multiplayerAddress, payload.getOriginPort(), payload.getPublicBaseUrl());
				documentTarget = urlPolicy.resolve(payload.getDocumentPath());
			}
			return documentTarget;
		}
	}
}
