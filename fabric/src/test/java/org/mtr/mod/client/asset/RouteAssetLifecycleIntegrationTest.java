package org.mtr.mod.client.asset;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.mod.packet.PacketRouteAssetManifest;
import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetManifest;
import org.mtr.mod.route.RouteAssetManifestCodec;
import org.mtr.mod.route.RouteAssetManifestDiff;
import org.mtr.mod.route.RouteAssetNegotiation;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.RouteAssetType;
import org.mtr.mod.route.RouteAssetVariant;
import org.mtr.mod.screen.RouteAssetLoadingScreen;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class RouteAssetLifecycleIntegrationTest {

	private static final String FINGERPRINT = "f".repeat(64);
	private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
	private static final byte[] PNG_2 = createPng();

	@TempDir
	Path temporaryDirectory;

	private HttpServer server;
	private ExecutorService executor;
	private ExecutorService serverExecutor;

	@AfterEach
	public void close() {
		if (server != null) server.stop(0);
		if (executor != null) executor.shutdownNow();
		if (serverExecutor != null) serverExecutor.shutdownNow();
	}

	@Test
	public void snapshotDownloadsOnlyNewActiveVariantThenPublishesManifest() throws Exception {
		final String activeHash = RouteAssetHash.sha256(PNG);
		final String inactiveHash = RouteAssetHash.sha256(PNG_2);
		final RouteAssetManifest manifest = RouteAssetManifest.builder()
				.put(key(10, 2, "NORMAL"), activeHash, "active")
				.put(key(11, 1, "NORMAL"), inactiveHash, "inactive-resolution")
				.put(key(12, 2, "CJK"), inactiveHash, "inactive-language")
				.build();
		final byte[] document = RouteAssetManifestCodec.encode(manifest);
		final List<String> requestedPaths = new java.util.concurrent.CopyOnWriteArrayList<>();
		startServer(requestedPaths, Map.of(
				documentPath(document), document,
				pngPath(activeHash), PNG,
				pngPath(inactiveHash), PNG_2
		));
		final ClientRouteAssetDiskCache cache = cache();
		final AtomicLong clock = new AtomicLong(1_000);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, clock, hellos);

		manager.onJoin("127.0.0.1");
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, UUID.randomUUID().toString(), manifest.getRevision(), document, hellos.get(0).getRequestNonce()));
		awaitState(manager, ClientRouteAssetSession.State.READY);

		Assertions.assertEquals(List.of(routePath(documentPath(document)), routePath(pngPath(activeHash))), requestedPaths);
		Assertions.assertTrue(cache.findPng(activeHash).isPresent());
		Assertions.assertTrue(cache.findPng(inactiveHash).isEmpty());
		Assertions.assertEquals(manifest, cache.loadManifest(manager.getCurrentServerId()).orElseThrow());
		Assertions.assertEquals(1, manager.getProgress().getCompletedObjects());
		Assertions.assertEquals(1, manager.getProgress().getTotalObjects());
	}

	@Test
	public void diffNeverReauthorizesMissingUnchangedHashAndPreservesOldManifest() throws Exception {
		final String unchangedHash = RouteAssetHash.sha256(PNG);
		final String introducedHash = RouteAssetHash.sha256(PNG_2);
		final RouteAssetManifest before = RouteAssetManifest.builder().put(key(20, 2, "NORMAL"), unchangedHash, "unchanged").build();
		final RouteAssetManifest after = RouteAssetManifest.builder()
				.put(key(20, 2, "NORMAL"), unchangedHash, "unchanged")
				.put(key(21, 2, "NORMAL"), introducedHash, "new")
				.build();
		final byte[] document = RouteAssetManifestCodec.encode(RouteAssetManifestDiff.between(before.getRevision(), before, after));
		final String serverId = UUID.randomUUID().toString();
		final ClientRouteAssetDiskCache cache = cache();
		cache.storeManifest(serverId, before);
		cache.associate("127.0.0.1", serverId);
		final List<String> requestedPaths = new java.util.concurrent.CopyOnWriteArrayList<>();
		startServer(requestedPaths, Map.of(documentPath(document), document, pngPath(introducedHash), PNG_2, pngPath(unchangedHash), PNG));
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, new AtomicLong(), hellos);

		manager.onJoin("127.0.0.1");
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.DIFF, serverId, after.getRevision(), document, hellos.get(0).getRequestNonce()));
		awaitState(manager, ClientRouteAssetSession.State.LOCAL_FALLBACK);

		Assertions.assertFalse(requestedPaths.contains(routePath(pngPath(unchangedHash))), "an unchanged missing object is never reauthorized");
		Assertions.assertEquals(before, cache.loadManifest(serverId).orElseThrow(), "a failed revision cannot replace the prior manifest");
	}

	@Test
	public void moveAndDeleteReuseExistingCasWithoutPngRequests() throws Exception {
		final String hash = RouteAssetHash.sha256(PNG);
		final RouteAssetKey oldKey = key(30, 2, "NORMAL");
		final RouteAssetKey deletedKey = key(31, 2, "NORMAL");
		final RouteAssetKey movedKey = key(32, 2, "NORMAL");
		final RouteAssetManifest before = RouteAssetManifest.builder().put(oldKey, hash, "same").put(deletedKey, hash, "delete").build();
		final RouteAssetManifest after = RouteAssetManifest.builder().put(movedKey, hash, "same").build();
		final byte[] document = RouteAssetManifestCodec.encode(RouteAssetManifestDiff.between(before.getRevision(), before, after));
		final String serverId = UUID.randomUUID().toString();
		final ClientRouteAssetDiskCache cache = cache();
		cache.admitPng(hash, PNG);
		cache.storeManifest(serverId, before);
		cache.associate("127.0.0.1", serverId);
		final List<String> requestedPaths = new java.util.concurrent.CopyOnWriteArrayList<>();
		startServer(requestedPaths, Map.of(documentPath(document), document));
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, new AtomicLong(), hellos);

		manager.onJoin("127.0.0.1");
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.DIFF, serverId, after.getRevision(), document, hellos.get(0).getRequestNonce()));
		awaitState(manager, ClientRouteAssetSession.State.READY);

		Assertions.assertEquals(List.of(routePath(documentPath(document))), requestedPaths);
		Assertions.assertEquals(after, cache.loadManifest(serverId).orElseThrow());
	}

	@Test
	public void verifiedSnapshotCanRepairEveryMissingActiveHash() throws Exception {
		final String hash = RouteAssetHash.sha256(PNG);
		final RouteAssetManifest manifest = RouteAssetManifest.builder().put(key(35, 2, "NORMAL"), hash, "repair").build();
		final String serverId = UUID.randomUUID().toString();
		final ClientRouteAssetDiskCache cache = cache();
		cache.storeManifest(serverId, manifest);
		cache.associate("127.0.0.1", serverId);
		final byte[] document = RouteAssetManifestCodec.encode(manifest);
		final List<String> requestedPaths = new java.util.concurrent.CopyOnWriteArrayList<>();
		startServer(requestedPaths, Map.of(documentPath(document), document, pngPath(hash), PNG));
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, new AtomicLong(), hellos);

		manager.onJoin("127.0.0.1");
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, serverId, manifest.getRevision(), document, hellos.get(0).getRequestNonce()));
		awaitState(manager, ClientRouteAssetSession.State.READY);

		Assertions.assertTrue(requestedPaths.contains(routePath(pngPath(hash))), "a verified full snapshot authorizes repair of its active objects");
		Assertions.assertTrue(cache.findPng(hash).isPresent());
	}

	@Test
	public void nearFullCachePreservesPinnedObjectsAndRejectsNewAdmission() throws Exception {
		final String oldHash = RouteAssetHash.sha256(PNG);
		final String newHash = RouteAssetHash.sha256(PNG_2);
		final RouteAssetManifest before = RouteAssetManifest.builder().put(key(37, 2, "NORMAL"), oldHash, "pinned").build();
		final RouteAssetManifest after = RouteAssetManifest.builder().put(key(37, 2, "NORMAL"), oldHash, "pinned").put(key(38, 2, "NORMAL"), newHash, "new").build();
		final String serverId = UUID.randomUUID().toString();
		final ClientRouteAssetDiskCache cache = cache();
		cache.admitPng(oldHash, PNG);
		cache.storeManifest(serverId, before);
		cache.associate("127.0.0.1", serverId);
		final byte[] document = RouteAssetManifestCodec.encode(after);
		startServer(new java.util.concurrent.CopyOnWriteArrayList<>(), Map.of(documentPath(document), document, pngPath(newHash), PNG_2));
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final long cacheLimit = PNG.length + PNG_2.length - 1L;
		final ClientRouteAssetManager manager = manager(cache, new AtomicLong(), hellos, () -> { }, cacheLimit);

		manager.onJoin("127.0.0.1");
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, serverId, after.getRevision(), document, hellos.get(0).getRequestNonce()));
		awaitState(manager, ClientRouteAssetSession.State.LOCAL_FALLBACK);

		Assertions.assertTrue(cache.findPng(oldHash).isPresent(), "the active old revision remains pinned during capacity pruning");
		Assertions.assertTrue(cache.findPng(newHash).isEmpty());
		Assertions.assertEquals(before, cache.loadManifest(serverId).orElseThrow());
	}

	@Test
	public void disconnectDuringCommitCannotPublishAStaleManifest() throws Exception {
		final String oldHash = RouteAssetHash.sha256(PNG);
		final String newHash = RouteAssetHash.sha256(PNG_2);
		final RouteAssetManifest before = RouteAssetManifest.builder().put(key(36, 2, "NORMAL"), oldHash, "old").build();
		final RouteAssetManifest after = RouteAssetManifest.builder().put(key(36, 2, "NORMAL"), newHash, "new").build();
		final String serverId = UUID.randomUUID().toString();
		final ClientRouteAssetDiskCache cache = cache();
		cache.admitPng(oldHash, PNG);
		cache.storeManifest(serverId, before);
		cache.associate("127.0.0.1", serverId);
		final byte[] document = RouteAssetManifestCodec.encode(after);
		startServer(new java.util.concurrent.CopyOnWriteArrayList<>(), Map.of(documentPath(document), document, pngPath(newHash), PNG_2));
		final CountDownLatch commitEntered = new CountDownLatch(1);
		final CountDownLatch releaseCommit = new CountDownLatch(1);
		final AtomicLong clock = new AtomicLong();
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, clock, hellos, () -> {
			commitEntered.countDown();
			try {
				releaseCommit.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		});

		manager.onJoin("127.0.0.1");
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, serverId, after.getRevision(), document, hellos.get(0).getRequestNonce()));
		Assertions.assertTrue(commitEntered.await(10, TimeUnit.SECONDS));
		final long disconnectStarted = System.nanoTime();
		manager.onDisconnect();
		Assertions.assertTrue(System.nanoTime() - disconnectStarted < TimeUnit.SECONDS.toNanos(2), "disconnect cannot wait for staged manifest I/O or the final-move latch");
		releaseCommit.countDown();
		Thread.sleep(100);

		Assertions.assertEquals(ClientRouteAssetSession.State.DISCONNECTED, manager.getState());
		Assertions.assertEquals(before, cache.loadManifest(serverId).orElseThrow());
	}

	@Test
	public void screenWaits150MillisFailsOpenAt30SecondsAndNeverClosesNativeScreen() throws Exception {
		final String hash = RouteAssetHash.sha256(PNG);
		final RouteAssetManifest manifest = RouteAssetManifest.builder().put(key(40, 2, "NORMAL"), hash, "slow").build();
		final byte[] document = RouteAssetManifestCodec.encode(manifest);
		final CountDownLatch releasePng = new CountDownLatch(1);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(routePath(documentPath(document)), exchange -> respond(exchange, document));
		server.createContext(routePath(pngPath(hash)), exchange -> {
			try {
				releasePng.await(10, TimeUnit.SECONDS);
				respond(exchange, PNG);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				exchange.close();
			}
		});
		server.start();
		final AtomicLong clock = new AtomicLong(5_000);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache(), clock, hellos);
		final FakeScreenController screens = new FakeScreenController();
		manager.installLoadingScreenController(screens);

		final String serverId = UUID.randomUUID().toString();
		manager.onJoin("127.0.0.1");
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, serverId, manifest.getRevision(), document, hellos.get(0).getRequestNonce()));
		awaitProgress(manager, 1);
		manager.tick(clock.addAndGet(RouteAssetProtocol.LOADING_SCREEN_DELAY_MILLIS - 1));
		Assertions.assertEquals(0, screens.opens);
		screens.nativeScreen = true;
		manager.tick(clock.incrementAndGet());
		Assertions.assertEquals(0, screens.opens, "native screens take precedence at the delay boundary");
		screens.nativeScreen = false;
		manager.tick(clock.incrementAndGet());
		Assertions.assertEquals(1, screens.opens);

		screens.ownedScreenCurrent = false;
		screens.nativeScreen = true;
		manager.tick(5_000 + 30_000);
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, manager.getState());
		Assertions.assertEquals(0, screens.closes, "the manager may close only its own current screen");
		releasePng.countDown();
		final long cacheDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (managerCacheManifest(manager, serverId).isEmpty() && System.nanoTime() < cacheDeadline) Thread.sleep(10);
		Assertions.assertTrue(managerCacheManifest(manager, serverId).isPresent(), "already-authorized work may finish in the background after fail-open");
		awaitState(manager, ClientRouteAssetSession.State.READY);
	}

	@Test
	public void stalledDocumentFailsOpenAt30SecondsBeforeAnyAssetPlan() throws Exception {
		final String hash = RouteAssetHash.sha256(PNG);
		final RouteAssetManifest manifest = RouteAssetManifest.builder().put(key(41, 2, "NORMAL"), hash, "cached").build();
		final byte[] document = RouteAssetManifestCodec.encode(manifest);
		final String serverId = UUID.randomUUID().toString();
		final ClientRouteAssetDiskCache cache = cache();
		cache.admitPng(hash, PNG);
		final CountDownLatch releaseDocument = new CountDownLatch(1);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(routePath(documentPath(document)), exchange -> {
			try {
				releaseDocument.await(10, TimeUnit.SECONDS);
				respond(exchange, document);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				exchange.close();
			}
		});
		server.start();
		final AtomicLong clock = new AtomicLong(10_000);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, clock, hellos);
		final FakeScreenController screens = new FakeScreenController();
		manager.installLoadingScreenController(screens);

		manager.onJoin("127.0.0.1");
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, serverId, manifest.getRevision(), document, hellos.get(0).getRequestNonce()));
		manager.tick(40_000);
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, manager.getState());
		Assertions.assertEquals(0, screens.opens, "the loading screen remains gated on a missing-object plan");
		releaseDocument.countDown();
		awaitState(manager, ClientRouteAssetSession.State.READY);
	}

	@Test
	public void activePngDownloadsUseConfiguredParallelismWithoutExceedingIt() throws Exception {
		final List<byte[]> pngs = List.of(createPng(0xFFFF0000), createPng(0xFF00FF00), createPng(0xFF0000FF), createPng(0xFFFFFF00), createPng(0xFFFF00FF));
		final RouteAssetManifest.Builder builder = RouteAssetManifest.builder();
		final Map<String, byte[]> bodies = new java.util.HashMap<>();
		for (int index = 0; index < pngs.size(); index++) {
			final String hash = RouteAssetHash.sha256(pngs.get(index));
			builder.put(key(50 + index, 2, "NORMAL"), hash, "parallel-" + index);
			bodies.put(routePath(pngPath(hash)), pngs.get(index));
		}
		final RouteAssetManifest manifest = builder.build();
		final byte[] document = RouteAssetManifestCodec.encode(manifest);
		bodies.put(routePath(documentPath(document)), document);
		final AtomicLong activePngRequests = new AtomicLong();
		final AtomicLong maximumActivePngRequests = new AtomicLong();
		final CountDownLatch concurrentRequestsSeen = new CountDownLatch(1);
		final CountDownLatch releasePngs = new CountDownLatch(1);
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		serverExecutor = Executors.newCachedThreadPool();
		server.setExecutor(serverExecutor);
		server.createContext(RouteAssetProtocol.HTTP_PATH, exchange -> {
			final byte[] body = bodies.get(exchange.getRequestURI().getPath());
			if (body == null) {
				exchange.sendResponseHeaders(404, -1);
				exchange.close();
				return;
			}
			if (exchange.getRequestURI().getPath().endsWith(".png")) {
				final long active = activePngRequests.incrementAndGet();
				maximumActivePngRequests.accumulateAndGet(active, Math::max);
				if (active >= 2) concurrentRequestsSeen.countDown();
				try {
					releasePngs.await(10, TimeUnit.SECONDS);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				} finally {
					activePngRequests.decrementAndGet();
				}
			}
			respond(exchange, body);
		});
		server.start();
		final ClientRouteAssetDiskCache cache = cache();
		final AtomicLong clock = new AtomicLong();
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		executor = Executors.newFixedThreadPool(4);
		final ClientRouteAssetManager manager = new ClientRouteAssetManager(cache, clock::get, () -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", FINGERPRINT, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, 32L * 1024 * 1024), hellos::add, request -> { }, () -> { }, Runnable::run, Runnable::run, Runnable::run);
		manager.installDownloader(new ClientRouteAssetDownloader(cache, executor, 2));

		manager.onJoin("127.0.0.1");
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, UUID.randomUUID().toString(), manifest.getRevision(), document, hellos.get(0).getRequestNonce()));
		Assertions.assertTrue(concurrentRequestsSeen.await(10, TimeUnit.SECONDS), "configured concurrency above one must produce overlapping PNG requests");
		Assertions.assertEquals(2, maximumActivePngRequests.get(), "active requests must honor the configured cap even when the executor is larger");
		releasePngs.countDown();
		awaitState(manager, ClientRouteAssetSession.State.READY);
	}

	@Test
	public void configuredSingleDownloadWorkerCompletesWithoutCoordinatorDeadlock() throws Exception {
		final byte[] first = createPng(0xFF102030);
		final byte[] second = createPng(0xFF405060);
		final String firstHash = RouteAssetHash.sha256(first);
		final String secondHash = RouteAssetHash.sha256(second);
		final RouteAssetManifest manifest = RouteAssetManifest.builder().put(key(60, 2, "NORMAL"), firstHash, "one").put(key(61, 2, "NORMAL"), secondHash, "two").build();
		final byte[] document = RouteAssetManifestCodec.encode(manifest);
		startServer(new java.util.concurrent.CopyOnWriteArrayList<>(), Map.of(documentPath(document), document, pngPath(firstHash), first, pngPath(secondHash), second));
		final ClientRouteAssetDiskCache cache = cache();
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		executor = Executors.newFixedThreadPool(4);
		final ClientRouteAssetManager manager = new ClientRouteAssetManager(cache, () -> 0, () -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", FINGERPRINT, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, 32L * 1024 * 1024), hellos::add, request -> { }, () -> { }, Runnable::run, Runnable::run, Runnable::run);
		manager.installDownloader(new ClientRouteAssetDownloader(cache, executor, 1));

		manager.onJoin("127.0.0.1");
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, UUID.randomUUID().toString(), manifest.getRevision(), document, hellos.get(0).getRequestNonce()));
		awaitState(manager, ClientRouteAssetSession.State.READY);
	}

	@Test
	public void outOfOrderParallelCallbacksCannotRegressProgress() {
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache(), new AtomicLong(), hellos);
		manager.onJoin("127.0.0.1");
		final long generation = manager.getGeneration();

		manager.updateDownloadProgress(generation, 2, 2);
		manager.updateDownloadProgress(generation, 1, 2);

		Assertions.assertEquals(2, manager.getProgress().getCompletedObjects());
		Assertions.assertEquals(2, manager.getProgress().getTotalObjects());
	}

	@Test
	public void loadingScreenIsNonPausingAndEscapeCannotDismissIt() {
		final RouteAssetLoadingScreen screen = new RouteAssetLoadingScreen(() -> ClientRouteAssetManager.Progress.empty());
		Assertions.assertFalse(screen.isPauseScreen2());
		Assertions.assertFalse(screen.shouldCloseOnEsc2());
	}

	private ClientRouteAssetManager manager(ClientRouteAssetDiskCache cache, AtomicLong clock, List<org.mtr.mod.route.RouteAssetHello> hellos) {
		return manager(cache, clock, hellos, () -> { });
	}

	private ClientRouteAssetManager manager(ClientRouteAssetDiskCache cache, AtomicLong clock, List<org.mtr.mod.route.RouteAssetHello> hellos, Runnable beforeManifestCommit) {
		return manager(cache, clock, hellos, beforeManifestCommit, 32L * 1024 * 1024);
	}

	private ClientRouteAssetManager manager(ClientRouteAssetDiskCache cache, AtomicLong clock, List<org.mtr.mod.route.RouteAssetHello> hellos, Runnable beforeManifestCommit, long cacheMaximumBytes) {
		executor = Executors.newFixedThreadPool(2);
		final ClientRouteAssetManager manager = new ClientRouteAssetManager(
				cache,
				clock::get,
				() -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", FINGERPRINT, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, cacheMaximumBytes),
				hellos::add,
				request -> { },
				() -> { },
				Runnable::run,
				Runnable::run,
				Runnable::run
		);
		manager.installDownloader(new ClientRouteAssetDownloader(cache, executor, beforeManifestCommit));
		return manager;
	}

	private ClientRouteAssetDiskCache cache() {
		return new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
	}

	private void startServer(List<String> requestedPaths, Map<String, byte[]> bodies) throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		bodies.forEach((path, body) -> server.createContext(routePath(path), exchange -> {
			requestedPaths.add(exchange.getRequestURI().getPath());
			respond(exchange, body);
		}));
		server.start();
	}

	private PacketRouteAssetManifest.ManifestPayload payload(RouteAssetNegotiation.Mode mode, String serverId, String revision, byte[] document, long nonce) {
		final String hash = RouteAssetHash.sha256(document);
		return new PacketRouteAssetManifest.ManifestPayload(mode, serverId, server.getAddress().getPort(), "", revision, hash, document.length, documentPath(document), RouteAssetProtocol.RENDERER_VERSION, FINGERPRINT, "", nonce);
	}

	private static RouteAssetKey key(long id, int resolution, String language) {
		return new RouteAssetKey("minecraft:overworld", RouteAssetType.ROUTE_MAP, id, new RouteAssetVariant(resolution, language, Map.of("style", "normal")));
	}

	private static String documentPath(byte[] document) {
		final String hash = RouteAssetHash.sha256(document);
		return "v" + RouteAssetProtocol.RENDERER_VERSION + '/' + hash.substring(0, 2) + '/' + hash + ".json";
	}

	private static String pngPath(String hash) {
		return "v" + RouteAssetProtocol.RENDERER_VERSION + '/' + hash.substring(0, 2) + '/' + hash + ".png";
	}

	private static String routePath(String relative) {
		return RouteAssetProtocol.HTTP_PATH + relative;
	}

	private static void respond(com.sun.net.httpserver.HttpExchange exchange, byte[] body) throws java.io.IOException {
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
		exchange.close();
	}

	private static byte[] createPng() {
		return createPng(0xFFFF0000);
	}

	private static byte[] createPng(int color) {
		try {
			final java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(2, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			image.setRGB(0, 0, color);
			image.setRGB(1, 0, color ^ 0x00FFFFFF);
			final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(image, "png", output);
			return output.toByteArray();
		} catch (java.io.IOException exception) {
			throw new ExceptionInInitializerError(exception);
		}
	}

	private static void awaitState(ClientRouteAssetManager manager, ClientRouteAssetSession.State state) throws Exception {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (manager.getState() != state && System.nanoTime() < deadline) Thread.sleep(10);
		Assertions.assertEquals(state, manager.getState());
	}

	private static void awaitProgress(ClientRouteAssetManager manager, int total) throws Exception {
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (manager.getProgress().getTotalObjects() != total && System.nanoTime() < deadline) Thread.sleep(10);
		Assertions.assertEquals(total, manager.getProgress().getTotalObjects());
	}

	private java.util.Optional<RouteAssetManifest> managerCacheManifest(ClientRouteAssetManager manager, String serverId) {
		return cache().loadManifest(serverId);
	}

	private static final class FakeScreenController implements ClientRouteAssetManager.LoadingScreenController {
		private int opens;
		private int closes;
		private boolean nativeScreen;
		private boolean ownedScreenCurrent = true;

		@Override
		public boolean canOpen() { return !nativeScreen; }

		@Override
		public Object open(java.util.function.Supplier<ClientRouteAssetManager.Progress> progressSupplier) {
			opens++;
			ownedScreenCurrent = true;
			return new Object();
		}

		@Override
		public boolean isCurrent(Object screenToken) { return ownedScreenCurrent && !nativeScreen; }

		@Override
		public void close(Object screenToken) { closes++; }
	}
}
