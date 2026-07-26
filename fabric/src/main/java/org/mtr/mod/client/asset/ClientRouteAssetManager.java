package org.mtr.mod.client.asset;

import org.mtr.mapping.holder.MinecraftClient;
import org.mtr.mapping.holder.NativeImage;
import org.mtr.mapping.holder.Screen;
import org.mtr.mapping.holder.ClientWorld;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.MinecraftClientHelper;
import org.mtr.mod.Init;
import org.mtr.mod.InitClient;
import org.mtr.mod.client.DynamicTextureCache;
import org.mtr.mod.config.Config;
import org.mtr.mod.config.LanguageDisplay;
import org.mtr.mod.packet.PacketRouteAssetHello;
import org.mtr.mod.packet.PacketRouteAssetChunk;
import org.mtr.mod.packet.PacketRouteAssetChunkRequest;
import org.mtr.mod.packet.PacketRouteAssetManifest;
import org.mtr.mod.packet.PacketRouteAssetObservedKeys;
import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetHello;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetManifest;
import org.mtr.mod.route.RouteAssetNegotiation;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.RouteAssetType;
import org.mtr.mod.route.RouteAssetVariantPolicy;
import org.mtr.mod.screen.RouteAssetLoadingScreen;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
	private final PacketRouteAssetChunk.ClientTransferReceiver packetFallbackReceiver;
	private final AtomicLong nextPacketTransferId = new AtomicLong();
	private final ThreadLocal<Long> decodeTaskGeneration = new ThreadLocal<>();
	private ClientRouteAssetDownloader downloader;
	private Consumer<PacketRouteAssetChunkRequest.RequestPayload> packetFallbackRequestSender;
	private Consumer<List<RouteAssetKey>> observedKeySender;
	private Supplier<String> observedDimensionSupplier = () -> "";
	private ClientRouteAssetGpuCache<DecodedNativeImage, RouteAssetTextureHandle> gpuCache;
	private LoadingScreenController loadingScreenController = LoadingScreenController.NONE;

	private String multiplayerAddress = "";
	private String currentServerId = "";
	private String pendingRefreshRevision = "";
	private String deferredRefreshRevision = "";
	private Set<String> pinnedHashes = Collections.emptySet();
	private final Set<String> demandedContentHashes = new LinkedHashSet<>();
	private final Set<String> admittedDecodeHashes = new LinkedHashSet<>();
	private final LinkedHashSet<RouteAssetKey> queuedObservedKeys = new LinkedHashSet<>();
	private final LinkedHashSet<RouteAssetKey> awaitingObservedKeys = new LinkedHashSet<>();
	private final Map<String, Long> gpuFailureUntilMillis = new ConcurrentHashMap<>();
	private RouteAssetManifest activeManifest;
	private PrewarmIndex prewarmIndex = PrewarmIndex.EMPTY;
	private Settings settings;
	private long nextMaintenanceMillis;
	private long nextNearbyPrewarmMillis;
	private long diskCacheEpoch;
	private Progress progress = Progress.EMPTY;
	private long syncStartedMillis;
	private long missingAssetsSinceMillis;
	private boolean missingAssetsPlanned;
	private boolean observedRateWindowStarted;
	private long observedRateWindowStartMillis;
	private int observedKeysSentInWindow;
	private Object loadingScreenToken;

	private static final long MAINTENANCE_INTERVAL_MILLIS = 60_000;
	private static final long DEFAULT_SYNC_TIMEOUT_MILLIS = 30_000;
	private static final int MAX_DEMANDED_CONTENT_HASHES = 512;
	private static final int MAX_OUTSTANDING_DECODES = 64;
	private static final int MAX_NEARBY_PREWARM_ASSETS = 8;
	private static final int MAX_PREWARM_INDEX_PLATFORMS = 16_384;
	private static final long NEARBY_PREWARM_INTERVAL_MILLIS = 1_000;
	private static final long GPU_FAILURE_COOLDOWN_MILLIS = 5_000;
	private static final long OBSERVED_KEY_RATE_WINDOW_MILLIS = 60_000;
	private static final AtomicInteger MAINTENANCE_THREAD_COUNTER = new AtomicInteger();
	private static final AtomicInteger DOWNLOAD_THREAD_COUNTER = new AtomicInteger();
	private static final AtomicInteger DECODE_THREAD_COUNTER = new AtomicInteger();

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
		packetFallbackReceiver = new PacketRouteAssetChunk.ClientTransferReceiver(clock);
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
		packetFallbackReceiver = new PacketRouteAssetChunk.ClientTransferReceiver(clock);
		available = false;
	}

	public static ClientRouteAssetManager getInstance() {
		return InstanceHolder.INSTANCE;
	}

	public synchronized void onJoin(String multiplayerAddress) {
		final boolean newPacketConnection = session.getState() == ClientRouteAssetSession.State.DISCONNECTED;
		closeLoadingScreen();
		resetRouteTextureState(newPacketConnection);
		if (session.getState() != ClientRouteAssetSession.State.DISCONNECTED) {
			session.disconnect();
			cancelDocumentWork();
		}
		this.multiplayerAddress = multiplayerAddress == null ? "" : multiplayerAddress.trim();
		currentServerId = "";
		pinnedHashes = Collections.emptySet();
		nextMaintenanceMillis = 0;
		resetSyncUi();
		if (!available) {
			settings = null;
			final long generation = session.join(true, false, clock.getAsLong());
			startPacketFallbackGeneration(generation, newPacketConnection);
			return;
		}
		diskCacheEpoch = diskCache.advanceSessionEpoch();
		try {
			settings = Objects.requireNonNull(settingsSupplier.get(), "settingsSupplier returned null");
		} catch (RuntimeException exception) {
			settings = null;
			final long generation = session.join(true, false, clock.getAsLong());
			startPacketFallbackGeneration(generation, newPacketConnection);
			return;
		}
		final boolean eligible = !this.multiplayerAddress.isEmpty() && settings.getResolution() <= 3;
		final long generation = session.join(settings.isEnabled(), eligible, clock.getAsLong());
		startPacketFallbackGeneration(generation, newPacketConnection);
		if (!session.isCurrent(generation) || session.getState() != ClientRouteAssetSession.State.NEGOTIATING) return;
		scheduleHello(generation, this.multiplayerAddress, settings);
	}

	private void scheduleHello(long generation, String address, Settings settingsSnapshot) {
		try {
			metadataExecutor.execute(() -> {
				if (!isNegotiating(generation)) return;
				String cachedRevision = "";
				try {
					diskCache.initialize();
					final String cachedServerId = diskCache.findServerId(address).orElse("");
					if (!cachedServerId.isEmpty()) {
						final RouteAssetManifest cachedManifest = diskCache.loadManifest(cachedServerId).orElse(null);
						if (cachedManifest != null && diskCache.hasEveryObject(cachedManifest, settingsSnapshot.getResolution(), settingsSnapshot.getLanguage())) cachedRevision = cachedManifest.getRevision();
					}
				} catch (IOException | RuntimeException exception) {
					dispatchClient(generation, () -> restorePreviousOrFallback(generation));
					return;
				}
				if (!isNegotiating(generation)) return;
				final String validatedCachedRevision = cachedRevision;
				dispatchClient(generation, () -> sendHello(generation, settingsSnapshot, validatedCachedRevision));
			});
		} catch (RuntimeException exception) {
			restorePreviousOrFallback(generation);
		}
	}

	private void dispatchClient(long generation, Runnable action) {
		try {
			clientExecutor.execute(action);
		} catch (RuntimeException exception) {
			restorePreviousOrFallback(generation);
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
					packetFallbackRequestSender != null,
					settingsSnapshot.getMaximumRevisionDownloadBytes(),
					generation
			));
			session.markHelloSent(generation, clock.getAsLong());
		} catch (RuntimeException exception) {
			restorePreviousOrFallback(generation);
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
			restorePreviousOrFallback(generation);
			return;
		}

		if (payload.getMode() == RouteAssetNegotiation.Mode.DISABLED) {
			if (activeManifest != null) restorePreviousOrFallback(generation); else session.disable(generation);
			return;
		}
		if (payload.getMode() == RouteAssetNegotiation.Mode.FALLBACK) {
			restorePreviousOrFallback(generation);
			return;
		}

		try {
			currentServerId = UUID.fromString(payload.getServerId()).toString();
			if (payload.getMode() == RouteAssetNegotiation.Mode.UNCHANGED) {
				if (!session.beginCacheValidation(generation)) return;
				syncStartedMillis = clock.getAsLong();
				scheduleUnchangedValidation(generation, diskCacheEpoch, payload, settings, multiplayerAddress);
				return;
			}
			if (payload.getMode() != RouteAssetNegotiation.Mode.DIFF && payload.getMode() != RouteAssetNegotiation.Mode.SNAPSHOT) {
				restorePreviousOrFallback(generation);
				return;
			}
			requireCanonicalDocumentPath(payload);
			if (!session.beginSync(generation, payload.getDocumentHash())) return;
			syncStartedMillis = clock.getAsLong();
			final DocumentRequest request = new DocumentRequest(generation, payload, multiplayerAddress);
			if (downloader == null) {
				scheduleAssociation(generation, multiplayerAddress, currentServerId);
				documentRequestHandler.accept(request);
			} else {
				startDownload(request, settings);
			}
		} catch (RuntimeException exception) {
			restorePreviousOrFallback(generation);
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
				if (finishCacheValidation(generation, valid, payload.getServerId(), pins, manifest) && valid) {
					try {
						diskCache.prune(settingsSnapshot.getMaximumCacheBytes(), pins, expectedCacheEpoch);
					} catch (IOException | RuntimeException ignored) {
					}
				}
			});
		} catch (RejectedExecutionException exception) {
			restorePreviousOrFallback(generation);
		}
	}

	private synchronized boolean finishCacheValidation(long generation, boolean valid, String serverId, Set<String> pins, RouteAssetManifest manifest) {
		if (!session.isCurrent(generation) || session.getState() != ClientRouteAssetSession.State.SYNCING) return false;
		if (valid) {
			currentServerId = serverId;
			pinnedHashes = pins;
			setActiveManifest(Objects.requireNonNull(manifest, "validated manifest"));
			pendingRefreshRevision = "";
			nextMaintenanceMillis = clock.getAsLong() + MAINTENANCE_INTERVAL_MILLIS;
		} else if (activeManifest == null) {
			pinnedHashes = Collections.emptySet();
			setActiveManifest(null);
		}
		pendingRefreshRevision = "";
		final boolean completed = session.complete(generation, valid || activeManifest != null);
		startDeferredRefreshIfNeeded();
		return completed;
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
		packetFallbackReceiver.expireIncomplete();
		if (session.tick(nowMillis) && activeManifest != null) session.completeAuthorized(session.getGeneration());
		if (session.getState() == ClientRouteAssetSession.State.SYNCING) {
			final long timeoutMillis = settings == null ? DEFAULT_SYNC_TIMEOUT_MILLIS : settings.getStartupTimeoutMillis();
			if (nowMillis >= saturatingAdd(syncStartedMillis, timeoutMillis)) {
				restorePreviousOrFallback(session.getGeneration());
				closeLoadingScreen();
			} else if (missingAssetsPlanned && loadingScreenToken == null && nowMillis >= saturatingAdd(missingAssetsSinceMillis, RouteAssetProtocol.LOADING_SCREEN_DELAY_MILLIS) && loadingScreenController.canOpen()) {
				loadingScreenToken = loadingScreenController.open(this::getProgress);
			}
		} else if (session.getState() != ClientRouteAssetSession.State.SYNCING) {
			closeLoadingScreen();
		}
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
		flushObservedKeys(nowMillis);
	}

	public synchronized void onVariantChanged() {
		if (session.getState() == ClientRouteAssetSession.State.DISCONNECTED) return;
		onJoin(multiplayerAddress);
	}

	public synchronized void onResourcesReloaded() {
		if (session.getState() == ClientRouteAssetSession.State.DISCONNECTED) return;
		onJoin(multiplayerAddress);
	}

	public synchronized void handleRefresh(String revision, long connectionNonce) {
		final String targetRevision = RouteAssetHash.requireValid(revision);
		final ClientRouteAssetSession.State state = session.getState();
		if (!available || settings == null || !settings.isEnabled() || connectionNonce != session.getGeneration()) return;
		if (targetRevision.equals(pendingRefreshRevision) || targetRevision.equals(deferredRefreshRevision) || activeManifest != null && targetRevision.equals(activeManifest.getRevision())) return;
		if (state == ClientRouteAssetSession.State.NEGOTIATING || state == ClientRouteAssetSession.State.SYNCING) {
			deferredRefreshRevision = targetRevision;
			return;
		}
		if (state != ClientRouteAssetSession.State.READY && state != ClientRouteAssetSession.State.LOCAL_FALLBACK && state != ClientRouteAssetSession.State.DISABLED) return;
		beginIncrementalRefresh(targetRevision);
	}

	private void beginIncrementalRefresh(String targetRevision) {
		cancelDocumentWork();
		closeLoadingScreen();
		resetSyncUi();
		pendingRefreshRevision = targetRevision;
		final boolean eligible = !multiplayerAddress.isEmpty() && settings.getResolution() <= 3;
		final long generation = session.join(true, eligible, clock.getAsLong());
		startPacketFallbackGeneration(generation, false);
		if (session.getState() == ClientRouteAssetSession.State.NEGOTIATING) scheduleHello(generation, multiplayerAddress, settings);
	}

	public synchronized void beginRenderFrame(int maximumUploads, long budgetNanos) {
		if (maximumUploads < 0 || budgetNanos < 0) throw new IllegalArgumentException("Route asset upload budgets cannot be negative");
		if (gpuCache == null) return;
		prewarmNearbyRouteAssets(clock.getAsLong());
		final Set<String> demandedHashes = new HashSet<>(demandedContentHashes);
		demandedContentHashes.clear();
		gpuCache.replacePinnedContentHashes(demandedHashes);
		gpuCache.drainUploads(maximumUploads, budgetNanos);
		admittedDecodeHashes.clear();
	}

	public synchronized CompletableFuture<Boolean> repairCurrentServerCache() {
		if (!available || session.getState() == ClientRouteAssetSession.State.DISCONNECTED || currentServerId.isEmpty()) return CompletableFuture.completedFuture(false);
		closeLoadingScreen();
		final String serverId = currentServerId;
		diskCacheEpoch = diskCache.advanceSessionEpoch();
		session.disconnect();
		cancelDocumentWork();
		resetRouteTextureState(false);
		final long generation = session.join(settings != null && settings.isEnabled(), false, clock.getAsLong());
		startPacketFallbackGeneration(generation, false);
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
		closeLoadingScreen();
		resetRouteTextureState(true);
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
		resetSyncUi();
		packetFallbackReceiver.disconnect();
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

	public synchronized Progress getProgress() {
		return progress;
	}

	public synchronized String getCurrentServerId() {
		return currentServerId;
	}

	public synchronized RouteTextureVariant getRouteTextureVariant() {
		final ClientRouteAssetSession.State state = session.getState();
		if (settings == null || !settings.isEnabled() || settings.getResolution() > 3 || state != ClientRouteAssetSession.State.NEGOTIATING && state != ClientRouteAssetSession.State.SYNCING && state != ClientRouteAssetSession.State.READY) return null;
		return new RouteTextureVariant(settings.getResolution(), settings.getLanguage());
	}

	public synchronized RouteTextureLookup lookupRouteTexture(RouteAssetKey key) {
		Objects.requireNonNull(key, "key");
		final ClientRouteAssetSession.State state = session.getState();
		if ((state == ClientRouteAssetSession.State.NEGOTIATING || state == ClientRouteAssetSession.State.SYNCING) && activeManifest == null) return RouteTextureLookup.PENDING;
		if (state != ClientRouteAssetSession.State.READY && state != ClientRouteAssetSession.State.NEGOTIATING && state != ClientRouteAssetSession.State.SYNCING || settings == null || activeManifest == null || !RouteAssetVariantPolicy.isActive(key, settings.getResolution(), settings.getLanguage())) return RouteTextureLookup.LOCAL;
		final RouteAssetManifest.Entry entry = activeManifest.getEntries().get(key);
		if (entry == null) {
			if (state == ClientRouteAssetSession.State.READY && key.getType() != RouteAssetType.DESTINATION_SIGN_ATLAS) queueObservedKey(key);
			return RouteTextureLookup.LOCAL;
		}
		final String contentHash = entry.getHash();
		final Long retryAfter = gpuFailureUntilMillis.get(contentHash);
		if (retryAfter != null) {
			if (clock.getAsLong() < retryAfter) return RouteTextureLookup.LOCAL;
			gpuFailureUntilMillis.remove(contentHash, retryAfter);
		}
		if (demandedContentHashes.size() < MAX_DEMANDED_CONTENT_HASHES || demandedContentHashes.contains(contentHash)) demandedContentHashes.add(contentHash);
		if (gpuCache == null) return RouteTextureLookup.PENDING;
		final RouteAssetTextureHandle residentHandle = gpuCache.lookupContentHash(contentHash);
		if (residentHandle != null) return RouteTextureLookup.ready(residentHandle.resource);
		if ((!admittedDecodeHashes.contains(contentHash) && admittedDecodeHashes.size() >= MAX_OUTSTANDING_DECODES) || gpuCache.getInFlightCount() >= MAX_OUTSTANDING_DECODES) return RouteTextureLookup.PENDING;
		admittedDecodeHashes.add(contentHash);
		final RouteAssetTextureHandle handle = gpuCache.request(key.toString(), contentHash);
		return handle == null ? RouteTextureLookup.PENDING : RouteTextureLookup.ready(handle.resource);
	}

	void installDownloader(ClientRouteAssetDownloader downloader) {
		this.downloader = Objects.requireNonNull(downloader, "downloader");
	}

	void installPacketFallbackRequestSender(Consumer<PacketRouteAssetChunkRequest.RequestPayload> sender) {
		packetFallbackRequestSender = Objects.requireNonNull(sender, "sender");
	}

	synchronized void installObservedKeySender(Consumer<List<RouteAssetKey>> sender, Supplier<String> dimensionSupplier) {
		observedKeySender = Objects.requireNonNull(sender, "sender");
		observedDimensionSupplier = Objects.requireNonNull(dimensionSupplier, "dimensionSupplier");
	}

	public void handlePacketFallbackChunk(PacketRouteAssetChunk.ChunkPayload chunkPayload) {
		packetFallbackReceiver.accept(chunkPayload);
	}

	void installGpuCache(ClientRouteAssetGpuCache<DecodedNativeImage, RouteAssetTextureHandle> gpuCache) {
		this.gpuCache = Objects.requireNonNull(gpuCache, "gpuCache");
	}

	void installLoadingScreenController(LoadingScreenController loadingScreenController) {
		this.loadingScreenController = Objects.requireNonNull(loadingScreenController, "loadingScreenController");
	}

	private void cancelDocumentWork() {
		if (downloader != null) downloader.cancelAll();
		try {
			documentWorkCanceller.run();
		} catch (RuntimeException ignored) {
		}
	}

	private void startPacketFallbackGeneration(long generation, boolean newConnection) {
		if (newConnection) packetFallbackReceiver.beginConnection(generation);
		else packetFallbackReceiver.advanceGeneration(generation);
	}

	private CompletableFuture<byte[]> requestPacketFallback(ClientRouteAssetDownloader.DownloadRequest request) {
		final PacketRouteAssetChunkRequest.RequestPayload payload;
		final CompletableFuture<byte[]> completion;
		final Consumer<PacketRouteAssetChunkRequest.RequestPayload> sender;
		synchronized (this) {
			sender = packetFallbackRequestSender;
			if (sender == null || !session.isCurrent(request.getGeneration())) return failedPacketFallback("Route asset packet fallback is unavailable");
			long transferId = nextPacketTransferId.incrementAndGet();
			if (transferId == 0) transferId = nextPacketTransferId.incrementAndGet();
			payload = new PacketRouteAssetChunkRequest.RequestPayload(request.getGeneration(), transferId, request.getPacketObjectType(), request.getExpectedHash(), request.getExpectedLength());
			completion = packetFallbackReceiver.request(payload);
		}
		try {
			sender.accept(payload);
		} catch (RuntimeException exception) {
			packetFallbackReceiver.cancel(payload.getTransferId(), exception);
		}
		return completion;
	}

	private static CompletableFuture<byte[]> failedPacketFallback(String message) {
		final CompletableFuture<byte[]> completion = new CompletableFuture<>();
		completion.completeExceptionally(new IOException(message));
		return completion;
	}

	private void startDownload(DocumentRequest request, Settings settingsSnapshot) {
		final long generation = request.getGeneration();
		downloader.synchronize(
				request,
				settingsSnapshot.getResolution(),
				settingsSnapshot.getLanguage(),
				settingsSnapshot.getMaximumRevisionDownloadBytes(),
				settingsSnapshot.getMaximumCacheBytes(),
				diskCacheEpoch,
				() -> isCurrent(generation),
				new ClientRouteAssetDownloader.ProgressListener() {
					@Override
					public void planned(int totalObjects) {
						dispatchClient(generation, () -> updateDownloadPlan(generation, totalObjects));
					}

					@Override
					public void completed(int completedObjects, int totalObjects) {
						dispatchClient(generation, () -> updateDownloadProgress(generation, completedObjects, totalObjects));
					}
				}
		).whenComplete((result, throwable) -> dispatchClient(generation, () -> finishDownload(generation, result, throwable)));
	}

	private synchronized void updateDownloadPlan(long generation, int totalObjects) {
		if (!session.isCurrent(generation) || session.getState() != ClientRouteAssetSession.State.SYNCING) return;
		progress = new Progress(0, totalObjects);
		if (totalObjects > 0) {
			missingAssetsPlanned = true;
			missingAssetsSinceMillis = clock.getAsLong();
		}
	}

	synchronized void updateDownloadProgress(long generation, int completedObjects, int totalObjects) {
		if (!session.isCurrent(generation)) return;
		progress = new Progress(Math.max(progress.getCompletedObjects(), completedObjects), totalObjects);
	}

	private synchronized void finishDownload(long generation, ClientRouteAssetDownloader.SyncResult result, Throwable throwable) {
		if (!session.isCurrent(generation)) return;
		if (throwable == null && result != null) {
			pinnedHashes = result.getActiveHashes();
			setActiveManifest(result.getManifest());
			pendingRefreshRevision = "";
			nextMaintenanceMillis = clock.getAsLong() + MAINTENANCE_INTERVAL_MILLIS;
			if (session.getState() == ClientRouteAssetSession.State.SYNCING || session.getState() == ClientRouteAssetSession.State.LOCAL_FALLBACK) session.completeAuthorized(generation);
		} else if (session.getState() == ClientRouteAssetSession.State.SYNCING && activeManifest == null) {
			pinnedHashes = Collections.emptySet();
			setActiveManifest(null);
			session.complete(generation, false);
		} else if (session.getState() == ClientRouteAssetSession.State.SYNCING) {
			pendingRefreshRevision = "";
			session.complete(generation, true);
		}
		closeLoadingScreen();
		startDeferredRefreshIfNeeded();
	}

	private void closeLoadingScreen() {
		if (loadingScreenToken != null) {
			if (loadingScreenController.isCurrent(loadingScreenToken)) loadingScreenController.close(loadingScreenToken);
			loadingScreenToken = null;
		}
	}

	private void resetSyncUi() {
		progress = Progress.EMPTY;
		syncStartedMillis = 0;
		missingAssetsSinceMillis = 0;
		missingAssetsPlanned = false;
		loadingScreenToken = null;
	}

	private static long saturatingAdd(long value, long increment) {
		return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
	}

	private synchronized boolean isReady(long generation) {
		return session.isCurrent(generation) && session.getState() == ClientRouteAssetSession.State.READY;
	}

	private void resetRouteTextureState(boolean resetObservedRateWindow) {
		pendingRefreshRevision = "";
		deferredRefreshRevision = "";
		setActiveManifest(null);
		demandedContentHashes.clear();
		admittedDecodeHashes.clear();
		gpuFailureUntilMillis.clear();
		resetObservedKeys(resetObservedRateWindow);
		if (gpuCache != null) gpuCache.reset();
	}

	private synchronized void restorePreviousOrFallback(long generation) {
		pendingRefreshRevision = "";
		if (activeManifest == null) {
			session.fallback(generation);
		} else {
			final ClientRouteAssetSession.State state = session.getState();
			if (state == ClientRouteAssetSession.State.NEGOTIATING) session.ready(generation);
			else if (state == ClientRouteAssetSession.State.SYNCING) session.complete(generation, true);
			else if (state == ClientRouteAssetSession.State.LOCAL_FALLBACK) session.completeAuthorized(generation);
		}
		startDeferredRefreshIfNeeded();
	}

	private void startDeferredRefreshIfNeeded() {
		if (deferredRefreshRevision.isEmpty()) return;
		final String revision = deferredRefreshRevision;
		deferredRefreshRevision = "";
		if (activeManifest == null || !revision.equals(activeManifest.getRevision())) beginIncrementalRefresh(revision);
	}

	private void setActiveManifest(RouteAssetManifest manifest) {
		activeManifest = manifest;
		if (manifest != null && !awaitingObservedKeys.isEmpty()) {
			awaitingObservedKeys.removeIf(manifest.getEntries()::containsKey);
			queuedObservedKeys.removeIf(manifest.getEntries()::containsKey);
		}
		prewarmIndex = manifest == null || settings == null ? PrewarmIndex.EMPTY : buildPrewarmIndex(manifest, settings.getResolution(), settings.getLanguage());
		nextNearbyPrewarmMillis = 0;
	}

	private void queueObservedKey(RouteAssetKey key) {
		if (key.getType() == RouteAssetType.DESTINATION_SIGN_ATLAS || observedKeySender == null || settings == null || settings.getResolution() > 3 || awaitingObservedKeys.size() >= RouteAssetProtocol.MAX_QUEUED_OBSERVED_KEYS) return;
		if (!RouteAssetVariantPolicy.isActive(key, settings.getResolution(), settings.getLanguage())) return;
		final String currentDimension;
		try {
			currentDimension = observedDimensionSupplier.get();
		} catch (RuntimeException exception) {
			return;
		}
		if (currentDimension == null || !key.getDimension().equals(currentDimension)) return;
		if (awaitingObservedKeys.add(key)) queuedObservedKeys.add(key);
	}

	private void flushObservedKeys(long nowMillis) {
		if (session.getState() != ClientRouteAssetSession.State.READY || observedKeySender == null || queuedObservedKeys.isEmpty()) return;
		if (!observedRateWindowStarted || nowMillis < observedRateWindowStartMillis || nowMillis - observedRateWindowStartMillis >= OBSERVED_KEY_RATE_WINDOW_MILLIS) {
			observedRateWindowStarted = true;
			observedRateWindowStartMillis = nowMillis;
			observedKeysSentInWindow = 0;
		}
		final int remaining = RouteAssetProtocol.MAX_OBSERVED_KEYS_PER_PLAYER_PER_MINUTE - observedKeysSentInWindow;
		if (remaining <= 0) return;
		final List<RouteAssetKey> batch = new ArrayList<>(Math.min(remaining, queuedObservedKeys.size()));
		final java.util.Iterator<RouteAssetKey> iterator = queuedObservedKeys.iterator();
		while (iterator.hasNext() && batch.size() < remaining) {
			batch.add(iterator.next());
			iterator.remove();
		}
		try {
			observedKeySender.accept(Collections.unmodifiableList(batch));
			observedKeysSentInWindow += batch.size();
		} catch (RuntimeException exception) {
			queuedObservedKeys.addAll(batch);
		}
	}

	private void resetObservedKeys(boolean resetRateWindow) {
		queuedObservedKeys.clear();
		awaitingObservedKeys.clear();
		if (resetRateWindow) {
			observedRateWindowStarted = false;
			observedRateWindowStartMillis = 0;
			observedKeysSentInWindow = 0;
		}
	}

	private void prewarmNearbyRouteAssets(long nowMillis) {
		if (session.getState() != ClientRouteAssetSession.State.READY || activeManifest == null || prewarmIndex.isEmpty() || nowMillis < nextNearbyPrewarmMillis) return;
		nextNearbyPrewarmMillis = saturatingAdd(nowMillis, NEARBY_PREWARM_INTERVAL_MILLIS);
		try {
			final MinecraftClient minecraftClient = MinecraftClient.getInstance();
			final ClientWorld clientWorld = minecraftClient.getWorldMapped();
			if (clientWorld == null) return;
			final String dimension = Init.getWorldId(new World(clientWorld.data));
			final long[] nearestPlatformId = {0};
			final boolean[] foundPlatform = {false};
			final long radiusBlocks = Math.max(16L, (long) MinecraftClientHelper.getRenderDistance() * 16);
			InitClient.findClosePlatform(minecraftClient.getGameRendererMapped().getCamera().getBlockPos(), (int) Math.min(Integer.MAX_VALUE, radiusBlocks), platform -> {
				nearestPlatformId[0] = platform.getId();
				foundPlatform[0] = true;
			});
			if (!foundPlatform[0]) return;
			int requested = 0;
			for (final RouteAssetKey key : prewarmIndex.find(dimension, nearestPlatformId[0])) {
				if (requested++ >= MAX_NEARBY_PREWARM_ASSETS) break;
				lookupRouteTexture(key);
			}
		} catch (RuntimeException ignored) {
		}
	}

	static PrewarmIndex buildPrewarmIndex(RouteAssetManifest manifest, int resolution, String language) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(language, "language");
		final Map<String, Map<Long, List<RouteAssetKey>>> mutable = new HashMap<>();
		int indexedPlatforms = 0;
		for (final RouteAssetKey key : manifest.getEntries().keySet()) {
			if (!RouteAssetVariantPolicy.isActive(key, resolution, language) || key.getType() == RouteAssetType.ROUTE_SQUARE) continue;
			if (key.getType() != RouteAssetType.ROUTE_MAP && key.getType() != RouteAssetType.DIRECTION_ARROW && key.getType() != RouteAssetType.ROUTE_COLOR_STRIP) continue;
			Map<Long, List<RouteAssetKey>> dimension = mutable.get(key.getDimension());
			List<RouteAssetKey> keys = dimension == null ? null : dimension.get(key.getPrimaryId());
			if (keys == null) {
				if (indexedPlatforms >= MAX_PREWARM_INDEX_PLATFORMS) continue;
				if (dimension == null) {
					dimension = new HashMap<>();
					mutable.put(key.getDimension(), dimension);
				}
				keys = new ArrayList<>(MAX_NEARBY_PREWARM_ASSETS);
				dimension.put(key.getPrimaryId(), keys);
				indexedPlatforms++;
			}
			if (keys.size() < MAX_NEARBY_PREWARM_ASSETS) keys.add(key);
		}
		return PrewarmIndex.copyOf(mutable);
	}

	ClientRouteAssetGpuCache.DecodedResource<DecodedNativeImage> decodeRouteTexture(String contentHash) throws Exception {
		final long expectedGeneration = getDecodeTaskGeneration();
		NativeImage nativeImage = null;
		try {
			requireCurrentGeneration(expectedGeneration);
			RouteAssetHash.requireValid(contentHash);
			final Path pngPath = diskCache.pathForPng(contentHash);
			final long storedSize = Files.size(pngPath);
			if (storedSize <= 0 || storedSize > RouteAssetProtocol.MAX_PNG_BYTES) throw new IOException("Route asset PNG exceeds its byte limit");
			final byte[] bytes;
			try (final InputStream inputStream = Files.newInputStream(pngPath)) {
				bytes = inputStream.readNBytes(RouteAssetProtocol.MAX_PNG_BYTES + 1);
			}
			requireCurrentGeneration(expectedGeneration);
			if (bytes.length == 0 || bytes.length > RouteAssetProtocol.MAX_PNG_BYTES) throw new IOException("Route asset PNG exceeds its byte limit");
			if (!RouteAssetHash.sha256(bytes).equals(contentHash)) throw new IOException("Route asset PNG hash changed after cache validation");

			final ByteBuffer byteBuffer = ByteBuffer.allocateDirect(bytes.length);
			byteBuffer.put(bytes);
			byteBuffer.rewind();
			nativeImage = Objects.requireNonNull(NativeImage.read(byteBuffer), "decoded route asset image");
			requireCurrentGeneration(expectedGeneration);
			final int width = nativeImage.getWidth();
			final int height = nativeImage.getHeight();
			final long pixels = (long) width * height;
			if (width <= 0 || height <= 0 || width > RouteAssetProtocol.MAX_PNG_AXIS || height > RouteAssetProtocol.MAX_PNG_AXIS || pixels > RouteAssetProtocol.MAX_PNG_PIXELS) throw new IOException("Route asset PNG dimensions exceed their limits");
			final DecodedNativeImage decodedNativeImage = new DecodedNativeImage(nativeImage);
			nativeImage = null;
			clearGpuFailure(contentHash, expectedGeneration);
			return new ClientRouteAssetGpuCache.DecodedResource<>(decodedNativeImage, pixels * 4);
		} catch (Exception exception) {
			if (nativeImage != null) nativeImage.close();
			markGpuFailure(contentHash, expectedGeneration);
			throw exception;
		}
	}

	private RouteAssetTextureHandle registerRouteTexture(String contentHash, ClientRouteAssetGpuCache.DecodedResource<DecodedNativeImage> decodedResource) {
		final DynamicTextureCache owner = DynamicTextureCache.instance;
		final DecodedNativeImage decodedNativeImage = decodedResource.transferOwnership();
		try {
			final DynamicTextureCache.DynamicResource resource = owner.registerRouteAssetTexture(decodedNativeImage.transferImage());
			clearGpuFailure(contentHash, session.getGeneration());
			return new RouteAssetTextureHandle(resource, () -> owner.destroyRouteAssetTexture(resource));
		} catch (RuntimeException exception) {
			markGpuFailure(contentHash, session.getGeneration());
			throw exception;
		} finally {
			decodedNativeImage.close();
		}
	}

	private synchronized void markGpuFailure(String contentHash, long expectedGeneration) {
		if (session.isCurrent(expectedGeneration)) gpuFailureUntilMillis.put(contentHash, saturatingAdd(clock.getAsLong(), GPU_FAILURE_COOLDOWN_MILLIS));
	}

	private synchronized void clearGpuFailure(String contentHash, long expectedGeneration) {
		if (session.isCurrent(expectedGeneration)) gpuFailureUntilMillis.remove(contentHash);
	}

	private long getDecodeTaskGeneration() {
		final Long generation = decodeTaskGeneration.get();
		if (generation != null) return generation;
		synchronized (this) {
			return session.getGeneration();
		}
	}

	private synchronized void requireCurrentGeneration(long expectedGeneration) throws IOException {
		if (!session.isCurrent(expectedGeneration)) throw new IOException("Stale route asset decode generation");
	}

	Executor correlateDecodeGeneration(Executor executor) {
		return runnable -> {
			final long scheduledGeneration;
			synchronized (this) {
				scheduledGeneration = session.getGeneration();
			}
			executor.execute(() -> {
				decodeTaskGeneration.set(scheduledGeneration);
				try {
					if (isCurrent(scheduledGeneration)) runnable.run();
				} finally {
					decodeTaskGeneration.remove();
				}
			});
		};
	}

	private static Set<String> hashes(RouteAssetManifest manifest, int resolution, String language) {
		final Set<String> hashes = new HashSet<>();
		manifest.getEntries().forEach((key, entry) -> {
			if (RouteAssetVariantPolicy.isActive(key, resolution, language)) hashes.add(entry.getHash());
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
		final ClientRouteAssetManager manager = createResilient(
				() -> {
					final Path cacheRoot = MinecraftClient.getInstance().getRunDirectoryMapped().toPath().resolve(Path.of("cache", "mtr", "route-textures"));
					return new ClientRouteAssetDiskCache(cacheRoot, RouteAssetProtocol.RENDERER_VERSION);
				},
				ClientRouteAssetResourceFingerprint::get,
				System::currentTimeMillis,
				ignoredFingerprint -> new Settings(
						Config.getClient().getServerRouteTexturesEnabled(),
						Config.getClient().getDynamicTextureResolution(),
						routeAssetLanguage(Config.getClient().getLanguageDisplay()),
						ClientRouteAssetResourceFingerprint.get(),
						RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES,
						(long) Config.getClient().getRouteTextureCacheMiB() * 1024 * 1024,
						(long) Config.getClient().getRouteTextureStartupTimeoutSeconds() * 1000
				),
				hello -> InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketRouteAssetHello(hello)),
				request -> { },
				() -> { },
				backgroundExecutor,
				backgroundExecutor,
				clientExecutor
		);
		if (manager.available) {
			final int downloadConcurrency = Config.getClient().getRouteTextureDownloadConcurrency();
			manager.installPacketFallbackRequestSender(payload -> InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketRouteAssetChunkRequest(payload)));
			manager.installObservedKeySender(keys -> InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketRouteAssetObservedKeys(keys)), ClientRouteAssetManager::currentDimension);
			manager.installDownloader(ClientRouteAssetDownloader.withPacketFallback(manager.diskCache, createDownloadExecutor(downloadConcurrency), manager::requestPacketFallback, downloadConcurrency));
			manager.installGpuCache(new ClientRouteAssetGpuCache<>(manager.correlateDecodeGeneration(createDecodeExecutor()), manager::decodeRouteTexture, manager::registerRouteTexture, System::nanoTime, RouteAssetProtocol.DEFAULT_GPU_CACHE_BYTES));
			manager.installLoadingScreenController(new MinecraftLoadingScreenController());
		}
		return manager;
	}

	private static String currentDimension() {
		try {
			final ClientWorld clientWorld = MinecraftClient.getInstance().getWorldMapped();
			return clientWorld == null ? "" : Init.getWorldId(new World(clientWorld.data));
		} catch (RuntimeException exception) {
			return "";
		}
	}

	static String routeAssetLanguage(LanguageDisplay languageDisplay) {
		switch (Objects.requireNonNull(languageDisplay, "languageDisplay")) {
			case CJK_ONLY: return "CJK";
			case NON_CJK_ONLY: return "LATIN";
			case NORMAL:
			default: return "NORMAL";
		}
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

	static ExecutorService createDownloadExecutor(int concurrency) {
		if (concurrency < 1 || concurrency > 8) throw new IllegalArgumentException("Invalid route asset download concurrency");
		return Executors.newFixedThreadPool(concurrency, runnable -> {
			final Thread thread = new Thread(runnable, "mtr-route-assets-client-download-" + DOWNLOAD_THREAD_COUNTER.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		});
	}

	static ExecutorService createDecodeExecutor() {
		return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_OUTSTANDING_DECODES), runnable -> {
			final Thread thread = new Thread(runnable, "mtr-route-assets-client-decode-" + DECODE_THREAD_COUNTER.incrementAndGet());
			thread.setDaemon(true);
			thread.setPriority(Thread.MIN_PRIORITY);
			return thread;
		}, new ThreadPoolExecutor.AbortPolicy());
	}

	private static final class MinecraftLoadingScreenController implements LoadingScreenController {
		@Override
		public boolean canOpen() {
			return MinecraftClient.getInstance().getCurrentScreenMapped() == null;
		}

		@Override
		public Object open(Supplier<Progress> progressSupplier) {
			final Screen screen = new Screen(new RouteAssetLoadingScreen(progressSupplier));
			MinecraftClient.getInstance().openScreen(screen);
			return screen;
		}

		@Override
		public boolean isCurrent(Object screenToken) {
			final Screen current = MinecraftClient.getInstance().getCurrentScreenMapped();
			return screenToken instanceof Screen && current != null && current.data == ((Screen) screenToken).data;
		}

		@Override
		public void close(Object screenToken) {
			if (isCurrent(screenToken)) MinecraftClient.getInstance().openScreen(null);
		}
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
		private final long startupTimeoutMillis;

		public Settings(boolean enabled, int resolution, String language, String resourceFingerprint, long maximumRevisionDownloadBytes) {
			this(enabled, resolution, language, resourceFingerprint, maximumRevisionDownloadBytes, Long.MAX_VALUE, DEFAULT_SYNC_TIMEOUT_MILLIS);
		}

		public Settings(boolean enabled, int resolution, String language, String resourceFingerprint, long maximumRevisionDownloadBytes, long maximumCacheBytes) {
			this(enabled, resolution, language, resourceFingerprint, maximumRevisionDownloadBytes, maximumCacheBytes, DEFAULT_SYNC_TIMEOUT_MILLIS);
		}

		public Settings(boolean enabled, int resolution, String language, String resourceFingerprint, long maximumRevisionDownloadBytes, long maximumCacheBytes, long startupTimeoutMillis) {
			if (resolution < 0 || resolution > 8 || maximumRevisionDownloadBytes < 0 || maximumRevisionDownloadBytes > RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES || maximumCacheBytes < 0) {
				throw new IllegalArgumentException("Invalid client route asset settings");
			}
			if (startupTimeoutMillis < 1_000 || startupTimeoutMillis > DEFAULT_SYNC_TIMEOUT_MILLIS) throw new IllegalArgumentException("Invalid route asset startup timeout");
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
			this.startupTimeoutMillis = startupTimeoutMillis;
		}

		public boolean isEnabled() { return enabled; }
		public int getResolution() { return resolution; }
		public String getLanguage() { return language; }
		public String getResourceFingerprint() { return resourceFingerprint; }
		public long getMaximumRevisionDownloadBytes() { return maximumRevisionDownloadBytes; }
		public long getMaximumCacheBytes() { return maximumCacheBytes; }
		public long getStartupTimeoutMillis() { return startupTimeoutMillis; }
	}

	public static final class Progress {
		private static final Progress EMPTY = new Progress(0, 0);
		private final int completedObjects;
		private final int totalObjects;

		private Progress(int completedObjects, int totalObjects) {
			if (completedObjects < 0 || totalObjects < 0 || completedObjects > totalObjects) throw new IllegalArgumentException("Invalid route asset progress");
			this.completedObjects = completedObjects;
			this.totalObjects = totalObjects;
		}

		public static Progress empty() { return EMPTY; }

		public int getCompletedObjects() { return completedObjects; }
		public int getTotalObjects() { return totalObjects; }
		public float getFraction() { return totalObjects == 0 ? 0 : (float) completedObjects / totalObjects; }
	}

	static final class PrewarmIndex {
		private static final PrewarmIndex EMPTY = new PrewarmIndex(Collections.emptyMap());
		private final Map<String, Map<Long, List<RouteAssetKey>>> assetsByDimensionAndPlatform;

		private PrewarmIndex(Map<String, Map<Long, List<RouteAssetKey>>> assetsByDimensionAndPlatform) {
			this.assetsByDimensionAndPlatform = assetsByDimensionAndPlatform;
		}

		private static PrewarmIndex copyOf(Map<String, Map<Long, List<RouteAssetKey>>> source) {
			if (source.isEmpty()) return EMPTY;
			final Map<String, Map<Long, List<RouteAssetKey>>> dimensions = new HashMap<>();
			source.forEach((dimension, platforms) -> {
				final Map<Long, List<RouteAssetKey>> platformCopy = new HashMap<>();
				platforms.forEach((platformId, keys) -> platformCopy.put(platformId, Collections.unmodifiableList(new ArrayList<>(keys))));
				dimensions.put(dimension, Collections.unmodifiableMap(platformCopy));
			});
			return new PrewarmIndex(Collections.unmodifiableMap(dimensions));
		}

		List<RouteAssetKey> find(String dimension, long platformId) {
			final Map<Long, List<RouteAssetKey>> platforms = assetsByDimensionAndPlatform.get(dimension);
			return platforms == null ? Collections.emptyList() : platforms.getOrDefault(platformId, Collections.emptyList());
		}

		private boolean isEmpty() {
			return assetsByDimensionAndPlatform.isEmpty();
		}
	}

	public enum RouteTextureState { READY, PENDING, LOCAL }

	public static final class RouteTextureLookup {
		private static final RouteTextureLookup PENDING = new RouteTextureLookup(RouteTextureState.PENDING, null);
		private static final RouteTextureLookup LOCAL = new RouteTextureLookup(RouteTextureState.LOCAL, null);
		private final RouteTextureState state;
		private final DynamicTextureCache.DynamicResource resource;

		private RouteTextureLookup(RouteTextureState state, DynamicTextureCache.DynamicResource resource) {
			this.state = state;
			this.resource = resource;
		}

		private static RouteTextureLookup ready(DynamicTextureCache.DynamicResource resource) {
			return new RouteTextureLookup(RouteTextureState.READY, Objects.requireNonNull(resource, "resource"));
		}

		public RouteTextureState getState() { return state; }
		public DynamicTextureCache.DynamicResource getResource() { return resource; }
	}

	public static final class RouteTextureVariant {
		private final int resolution;
		private final String language;

		private RouteTextureVariant(int resolution, String language) {
			this.resolution = resolution;
			this.language = language;
		}

		public int getResolution() { return resolution; }
		public String getLanguage() { return language; }
	}

	static final class RouteAssetTextureHandle implements AutoCloseable {
		private final DynamicTextureCache.DynamicResource resource;
		private final Runnable closeAction;
		private boolean closed;

		RouteAssetTextureHandle(DynamicTextureCache.DynamicResource resource, Runnable closeAction) {
			this.resource = Objects.requireNonNull(resource, "resource");
			this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
		}

		@Override
		public synchronized void close() {
			if (closed) return;
			closed = true;
			closeAction.run();
		}
	}

	static final class DecodedNativeImage implements AutoCloseable {
		private NativeImage image;

		private DecodedNativeImage(NativeImage image) {
			this.image = Objects.requireNonNull(image, "image");
		}

		private synchronized NativeImage transferImage() {
			if (image == null) throw new IllegalStateException("decoded image ownership is no longer available");
			final NativeImage transferredImage = image;
			image = null;
			return transferredImage;
		}

		@Override
		public synchronized void close() {
			if (image != null) {
				image.close();
				image = null;
			}
		}
	}

	public interface LoadingScreenController {
		LoadingScreenController NONE = new LoadingScreenController() {
			@Override public boolean canOpen() { return false; }
			@Override public Object open(Supplier<Progress> progressSupplier) { throw new IllegalStateException("Route asset loading screen is unavailable"); }
			@Override public boolean isCurrent(Object screenToken) { return false; }
			@Override public void close(Object screenToken) { }
		};

		boolean canOpen();
		Object open(Supplier<Progress> progressSupplier);
		boolean isCurrent(Object screenToken);
		void close(Object screenToken);
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
		public String getMultiplayerAddress() { return multiplayerAddress; }
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
