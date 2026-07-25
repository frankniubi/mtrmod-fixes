package org.mtr.mod.client.asset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.mod.packet.PacketRouteAssetManifest;
import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetManifest;
import org.mtr.mod.route.RouteAssetNegotiation;
import org.mtr.mod.route.RouteAssetProtocol;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientRouteAssetSessionTest {

	private static final String FINGERPRINT = "f".repeat(64);
	private static final String OTHER_FINGERPRINT = "e".repeat(64);
	private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

	@TempDir
	Path temporaryDirectory;

	@Test
	public void stateMachineCoversEveryStateAndRejectsStaleCompletion() {
		final ClientRouteAssetSession session = new ClientRouteAssetSession();
		Assertions.assertEquals(ClientRouteAssetSession.State.DISCONNECTED, session.getState());

		final long disabledGeneration = session.join(false, true, 10);
		Assertions.assertEquals(ClientRouteAssetSession.State.DISABLED, session.getState());
		Assertions.assertTrue(session.isCurrent(disabledGeneration));

		final long fallbackGeneration = session.join(true, false, 20);
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, session.getState());

		final long generation = session.join(true, true, 30);
		Assertions.assertEquals(ClientRouteAssetSession.State.NEGOTIATING, session.getState());
		Assertions.assertTrue(session.beginSync(generation, "a".repeat(64)));
		Assertions.assertFalse(session.beginSync(generation, "a".repeat(64)), "a document hash is authorized once per connection");
		Assertions.assertEquals(ClientRouteAssetSession.State.SYNCING, session.getState());
		Assertions.assertFalse(session.complete(fallbackGeneration, true), "stale generations cannot publish state");
		Assertions.assertEquals(ClientRouteAssetSession.State.SYNCING, session.getState());
		Assertions.assertTrue(session.complete(generation, true));
		Assertions.assertEquals(ClientRouteAssetSession.State.READY, session.getState());

		session.disconnect();
		Assertions.assertEquals(ClientRouteAssetSession.State.DISCONNECTED, session.getState());
		Assertions.assertFalse(session.isCurrent(generation));
	}

	@Test
	public void negotiationTimeoutFailsOpenForOldServers() {
		final ClientRouteAssetSession session = new ClientRouteAssetSession();
		final long generation = session.join(true, true, 1_000);
		Assertions.assertFalse(session.tick(1_000 + RouteAssetProtocol.NEGOTIATION_TIMEOUT_MILLIS * 2));
		Assertions.assertEquals(ClientRouteAssetSession.State.NEGOTIATING, session.getState(), "joining alone must not start the response deadline");
		Assertions.assertTrue(session.markHelloSent(generation, 5_000));
		Assertions.assertFalse(session.tick(5_000 + RouteAssetProtocol.NEGOTIATION_TIMEOUT_MILLIS - 1));
		Assertions.assertEquals(ClientRouteAssetSession.State.NEGOTIATING, session.getState());
		Assertions.assertTrue(session.tick(5_000 + RouteAssetProtocol.NEGOTIATION_TIMEOUT_MILLIS));
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, session.getState());
	}

	@Test
	public void warmUnchangedUsesPersistedAddressAssociationAndPerformsNoNetworkWork() throws Exception {
		final String address = "play.example.com:25565";
		final String serverId = "5c6f7bd4-3af8-4f48-b16a-32eb4bf0cdbf";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String pngHash = cache.admitPng(RouteAssetHash.sha256(PNG), PNG);
		final RouteAssetManifest manifest = manifest(pngHash);
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);

		final AtomicLong clock = new AtomicLong(100);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final List<ClientRouteAssetManager.DocumentRequest> requests = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, clock, 2, FINGERPRINT, hellos, requests);
		manager.onJoin(address);

		Assertions.assertEquals(1, hellos.size());
		Assertions.assertEquals(manifest.getRevision(), hellos.get(0).getCachedRevision(), "the warm revision must be known before the response arrives");
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT));
		Assertions.assertEquals(ClientRouteAssetSession.State.READY, manager.getState());
		Assertions.assertTrue(requests.isEmpty(), "UNCHANGED must not authorize HEAD or GET work");
	}

	@Test
	public void unchangedMissingObjectAndIncompatibleVariantsStayLocalWithoutRequests() throws Exception {
		final String address = "192.168.1.20:25565";
		final String serverId = "b80b0434-bb51-4620-a204-d40886b9f5ad";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String hash = cache.admitPng(RouteAssetHash.sha256(PNG), PNG);
		final RouteAssetManifest manifest = manifest(hash);
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);
		Files.delete(cache.pathForPng(hash));

		final AtomicLong clock = new AtomicLong();
		final List<ClientRouteAssetManager.DocumentRequest> requests = new ArrayList<>();
		final ClientRouteAssetManager missing = manager(cache, clock, 2, FINGERPRINT, new ArrayList<>(), requests);
		missing.onJoin(address);
		missing.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT));
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, missing.getState());
		Assertions.assertTrue(requests.isEmpty());
		cache.admitPng(hash, PNG);
		Files.write(cache.pathForPng(hash), new byte[]{1, 2, 3});
		final ClientRouteAssetManager corrupt = manager(cache, clock, 2, FINGERPRINT, new ArrayList<>(), requests);
		corrupt.onJoin(address);
		corrupt.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT));
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, corrupt.getState());
		Assertions.assertTrue(requests.isEmpty());

		final List<org.mtr.mod.route.RouteAssetHello> highResolutionHellos = new ArrayList<>();
		final ClientRouteAssetManager highResolution = manager(cache, clock, 4, FINGERPRINT, highResolutionHellos, requests);
		highResolution.onJoin(address);
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, highResolution.getState());
		Assertions.assertTrue(highResolutionHellos.isEmpty(), "resolution above three performs no server work");

		final ClientRouteAssetManager mismatch = manager(cache, clock, 2, FINGERPRINT, new ArrayList<>(), requests);
		mismatch.onJoin(address);
		mismatch.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, serverId, manifest.getRevision(), "c".repeat(64), OTHER_FINGERPRINT));
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, mismatch.getState());
		Assertions.assertTrue(requests.isEmpty(), "resource mismatch cannot authorize a document request");
	}

	@Test
	public void onlyNewlyIntroducedDocumentHashAuthorizesWork() throws Exception {
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final AtomicLong clock = new AtomicLong();
		final List<ClientRouteAssetManager.DocumentRequest> requests = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, clock, 2, FINGERPRINT, new ArrayList<>(), requests);
		manager.onJoin("192.168.1.20");
		final PacketRouteAssetManifest.ManifestPayload payload = payload(RouteAssetNegotiation.Mode.SNAPSHOT, "da693c63-7235-4684-bdf4-1361c1dd17e0", "b".repeat(64), "c".repeat(64), FINGERPRINT);
		manager.handleManifest(payload);
		manager.handleManifest(payload);
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, "da693c63-7235-4684-bdf4-1361c1dd17e0", "d".repeat(64), "e".repeat(64), FINGERPRINT));

		Assertions.assertEquals(1, requests.size());
		Assertions.assertEquals("c".repeat(64), requests.get(0).getPayload().getDocumentHash());
		final ClientRouteAssetManager.DocumentRequest request = requests.get(0);
		final ClientRouteAssetUrlPolicy policy = request.getUrlPolicy();
		final ClientRouteAssetUrlPolicy.ResolvedTarget target = request.getDocumentTarget();
		Assertions.assertSame(policy, request.getUrlPolicy());
		Assertions.assertSame(target, request.getDocumentTarget());
		Assertions.assertEquals(target.getUri(), request.getDocumentUri());
		Assertions.assertEquals(ClientRouteAssetSession.State.SYNCING, manager.getState());
	}

	@Test
	public void variantChangeCancelsTheGenerationAndCorrelatesTheNewReply() throws Exception {
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final AtomicLong clock = new AtomicLong();
		final AtomicLong cancellations = new AtomicLong();
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final List<ClientRouteAssetManager.DocumentRequest> requests = new ArrayList<>();
		final ClientRouteAssetManager manager = new ClientRouteAssetManager(
				cache,
				clock::get,
				() -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", FINGERPRINT, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES),
				hellos::add,
				requests::add,
				cancellations::incrementAndGet
		);
		manager.onJoin("play.example.com");
		final long oldGeneration = manager.getGeneration();
		manager.onVariantChanged();
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, "5ca401c6-550d-450b-bc70-397194d0e8f4", "b".repeat(64), "c".repeat(64), FINGERPRINT));

		Assertions.assertFalse(manager.isCurrent(oldGeneration));
		Assertions.assertEquals(ClientRouteAssetSession.State.NEGOTIATING, manager.getState());
		Assertions.assertEquals(2, hellos.size());
		Assertions.assertEquals(1, cancellations.get());
		Assertions.assertTrue(requests.isEmpty());
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, "5ca401c6-550d-450b-bc70-397194d0e8f4", "b".repeat(64), "c".repeat(64), FINGERPRINT, hellos.get(1).getRequestNonce()));
		Assertions.assertEquals(ClientRouteAssetSession.State.SYNCING, manager.getState());
		Assertions.assertEquals(1, requests.size());
	}

	@Test
	public void disconnectInvalidatesAndCancelsAuthorizedWork() throws Exception {
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final AtomicLong cancellations = new AtomicLong();
		final ClientRouteAssetManager manager = new ClientRouteAssetManager(
				cache,
				() -> 0,
				() -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", FINGERPRINT, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES),
				hello -> { },
				request -> { },
				cancellations::incrementAndGet
		);
		manager.onJoin("play.example.com");
		final long generation = manager.getGeneration();
		manager.onDisconnect();

		Assertions.assertFalse(manager.isCurrent(generation));
		Assertions.assertEquals(1, cancellations.get());
		Assertions.assertEquals(ClientRouteAssetSession.State.DISCONNECTED, manager.getState());
	}

	@Test
	public void connectionTransitionsAdvanceTheDiskCacheEpoch() throws Exception {
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final ClientRouteAssetManager manager = manager(cache, new AtomicLong(), 2, FINGERPRINT, new ArrayList<>(), new ArrayList<>());
		final long initialEpoch = cache.getSessionEpoch();
		manager.onJoin("play.example.com");
		final long joinEpoch = cache.getSessionEpoch();
		manager.onVariantChanged();
		final long variantEpoch = cache.getSessionEpoch();
		manager.onDisconnect();
		final long disconnectEpoch = cache.getSessionEpoch();

		Assertions.assertNotEquals(initialEpoch, joinEpoch);
		Assertions.assertNotEquals(joinEpoch, variantEpoch);
		Assertions.assertNotEquals(variantEpoch, disconnectEpoch);
	}

	@Test
	public void documentPathMustBeCanonicalForTheIntroducedHash() throws Exception {
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final List<ClientRouteAssetManager.DocumentRequest> requests = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, new AtomicLong(), 2, FINGERPRINT, new ArrayList<>(), requests);
		manager.onJoin("play.example.com");
		final String documentHash = "c".repeat(64);
		manager.handleManifest(new PacketRouteAssetManifest.ManifestPayload(
				RouteAssetNegotiation.Mode.SNAPSHOT,
				"3a2dd9a7-a1ab-47ea-b891-d9c29c012f12",
				8888,
				"",
				"b".repeat(64),
				documentHash,
				128,
				"v1/ff/" + documentHash + ".json",
				RouteAssetProtocol.RENDERER_VERSION,
				FINGERPRINT,
				"",
				1
		));

		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, manager.getState());
		Assertions.assertTrue(requests.isEmpty());
	}

	@Test
	public void bundledFingerprintMatchesTheServerImplementation() throws Exception {
		final Method method = org.mtr.mod.route.RouteAssetServerManager.class.getDeclaredMethod("defaultResourceFingerprint");
		method.setAccessible(true);
		Assertions.assertEquals(method.invoke(null), RouteAssetProtocol.BUNDLED_RESOURCE_FINGERPRINT);
	}

	@Test
	public void staleReplyFromJoinAIsIgnoredAfterJoinB() throws Exception {
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final List<ClientRouteAssetManager.DocumentRequest> requests = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, new AtomicLong(), 2, FINGERPRINT, hellos, requests);
		manager.onJoin("a.example.com");
		final long nonceA = hellos.get(0).getRequestNonce();
		manager.onJoin("b.example.com");
		final long nonceB = hellos.get(1).getRequestNonce();
		Assertions.assertTrue(nonceA > 0);
		Assertions.assertTrue(nonceB > 0);
		Assertions.assertNotEquals(nonceA, nonceB);

		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, "3df11629-e4f5-4f1c-be16-41ae8ca4f0a8", "b".repeat(64), "c".repeat(64), FINGERPRINT, nonceA));
		Assertions.assertEquals(ClientRouteAssetSession.State.NEGOTIATING, manager.getState());
		Assertions.assertTrue(requests.isEmpty());
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, "3df11629-e4f5-4f1c-be16-41ae8ca4f0a8", "b".repeat(64), "c".repeat(64), FINGERPRINT, nonceB));
		Assertions.assertEquals(ClientRouteAssetSession.State.SYNCING, manager.getState());
		Assertions.assertEquals(1, requests.size());
	}

	@Test
	public void joinDefersRevisionLookupAndDropsStaleHelloCompletion() throws Exception {
		final String address = "b.example.com";
		final String serverId = "6938a8bf-25bc-49e5-806d-5c7973c9e931";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final QueueExecutor metadataExecutor = new QueueExecutor();
		final QueueExecutor clientExecutor = new QueueExecutor();
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = new ClientRouteAssetManager(
				cache,
				() -> 0,
				() -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", FINGERPRINT, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES),
				hellos::add,
				request -> { },
				() -> { },
				Runnable::run,
				metadataExecutor,
				clientExecutor
		);

		manager.onJoin("a.example.com");
		final long generationA = manager.getGeneration();
		Assertions.assertTrue(hellos.isEmpty());
		Assertions.assertEquals(1, metadataExecutor.size());
		Assertions.assertEquals(0, clientExecutor.size());
		metadataExecutor.runNext();
		Assertions.assertTrue(hellos.isEmpty(), "metadata completion must dispatch through the client executor");
		Assertions.assertEquals(1, clientExecutor.size());

		manager.onJoin(address);
		final long generationB = manager.getGeneration();
		final String pngHash = cache.admitPng(RouteAssetHash.sha256(PNG), PNG);
		final RouteAssetManifest manifest = manifest(pngHash);
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);
		clientExecutor.runNext();
		Assertions.assertTrue(hellos.isEmpty(), "the first join cannot send after the second join advances the generation");

		metadataExecutor.runNext();
		Assertions.assertTrue(hellos.isEmpty());
		Assertions.assertEquals(1, clientExecutor.size());
		clientExecutor.runNext();
		Assertions.assertEquals(1, hellos.size());
		Assertions.assertNotEquals(generationA, generationB);
		Assertions.assertEquals(generationB, hellos.get(0).getRequestNonce());
		Assertions.assertEquals(manifest.getRevision(), hellos.get(0).getCachedRevision(), "the revision lookup must run after onJoin returns");
	}

	@Test
	public void queuedMetadataDoesNotConsumeTheOldServerTimeout() throws Exception {
		final AtomicLong clock = new AtomicLong(1_000);
		final QueueExecutor metadataExecutor = new QueueExecutor();
		final QueueExecutor clientExecutor = new QueueExecutor();
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = new ClientRouteAssetManager(
				new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION),
				clock::get,
				() -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", FINGERPRINT, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES),
				hellos::add,
				request -> { },
				() -> { },
				Runnable::run,
				metadataExecutor,
				clientExecutor
		);

		manager.onJoin("blocked.example.com");
		clock.addAndGet(RouteAssetProtocol.NEGOTIATION_TIMEOUT_MILLIS + 1);
		manager.tick(clock.get());
		Assertions.assertEquals(ClientRouteAssetSession.State.NEGOTIATING, manager.getState());
		metadataExecutor.runNext();
		manager.tick(clock.get());
		Assertions.assertEquals(ClientRouteAssetSession.State.NEGOTIATING, manager.getState());
		Assertions.assertTrue(hellos.isEmpty());

		clientExecutor.runNext();
		Assertions.assertEquals(1, hellos.size());
		manager.tick(clock.get() + RouteAssetProtocol.NEGOTIATION_TIMEOUT_MILLIS - 1);
		Assertions.assertEquals(ClientRouteAssetSession.State.NEGOTIATING, manager.getState());
		manager.tick(clock.get() + RouteAssetProtocol.NEGOTIATION_TIMEOUT_MILLIS);
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, manager.getState());
	}

	@Test
	public void unchangedValidatesOnlyTheActiveResolutionAndLanguage() throws Exception {
		final String address = "variants.example.com";
		final String serverId = "9c97b4e2-fe52-49b2-92bd-e2ddda8122a5";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String activeHash = cache.admitPng(RouteAssetHash.sha256(PNG), PNG);
		final RouteAssetManifest manifest = RouteAssetManifest.builder()
				.put(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|1|2|NORMAL|a=4:9,f=0,t=0,v=1"), activeHash, "active")
				.put(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|1|3|CJK|a=4:9,f=0,t=0,v=1"), "d".repeat(64), "inactive")
				.build();
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, new AtomicLong(), 2, FINGERPRINT, hellos, new ArrayList<>());
		manager.onJoin(address);
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT, hellos.get(0).getRequestNonce()));

		Assertions.assertEquals(ClientRouteAssetSession.State.READY, manager.getState());
	}

	@Test
	public void clientLanguageSettingsUseServerCanonicalNames() {
		Assertions.assertEquals("NORMAL", new ClientRouteAssetManager.Settings(true, 2, "NORMAL", FINGERPRINT, 1).getLanguage());
		Assertions.assertEquals("CJK", new ClientRouteAssetManager.Settings(true, 2, "CJK_ONLY", FINGERPRINT, 1).getLanguage());
		Assertions.assertEquals("LATIN", new ClientRouteAssetManager.Settings(true, 2, "NON_CJK_ONLY", FINGERPRINT, 1).getLanguage());
	}

	@Test
	public void unchangedWithoutAnyActiveVariantFallsBackLocally() throws Exception {
		final String address = "no-cjk.example.com";
		final String serverId = "821606c4-1762-4097-a21f-33f3a15be949";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String hash = cache.admitPng(RouteAssetHash.sha256(PNG), PNG);
		final RouteAssetManifest manifest = manifest(hash);
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final List<ClientRouteAssetManager.DocumentRequest> requests = new ArrayList<>();
		final ClientRouteAssetManager manager = new ClientRouteAssetManager(
				cache,
				() -> 0,
				() -> new ClientRouteAssetManager.Settings(true, 2, "CJK_ONLY", FINGERPRINT, 1),
				hellos::add,
				requests::add
		);
		manager.onJoin(address);
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT, hellos.get(0).getRequestNonce()));

		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, manager.getState());
		Assertions.assertTrue(requests.isEmpty());
	}

	@Test
	public void cacheValidationAndPinAwarePruningRunOnlyOnTheMaintenanceExecutor() throws Exception {
		final String address = "maintenance.example.com";
		final String serverId = "bd8a8e2c-092c-45af-a9a2-e44a766da3d9";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final byte[] activePng = png(0xFF123456);
		final byte[] orphanPng = png(0xFF654321);
		final String activeHash = cache.admitPng(RouteAssetHash.sha256(activePng), activePng);
		final String orphanHash = cache.admitPng(RouteAssetHash.sha256(orphanPng), orphanPng);
		Files.setLastModifiedTime(cache.pathForPng(orphanHash), FileTime.fromMillis(1));
		final RouteAssetManifest manifest = RouteAssetManifest.builder()
				.put(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|1|2|NORMAL|a=4:9,f=0,t=0,v=1"), activeHash, "active")
				.put(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|1|3|CJK|a=4:9,f=0,t=0,v=1"), orphanHash, "inactive")
				.build();
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);
		final QueueExecutor executor = new QueueExecutor();
		final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = new ClientRouteAssetManager(
				cache,
				clock::get,
				() -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", FINGERPRINT, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, activePng.length),
				hellos::add,
				request -> { },
				() -> { },
				executor
		);
		manager.onJoin(address);
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT, hellos.get(0).getRequestNonce()));

		Assertions.assertEquals(ClientRouteAssetSession.State.SYNCING, manager.getState());
		Assertions.assertEquals(1, executor.size());
		manager.tick(clock.get());
		Assertions.assertEquals(ClientRouteAssetSession.State.SYNCING, manager.getState(), "queued UNCHANGED validation starts its timeout at the response, not at epoch zero");
		Assertions.assertTrue(Files.isRegularFile(cache.pathForPng(orphanHash)), "the packet thread must not scan or prune the CAS");
		executor.runNext();
		Assertions.assertEquals(ClientRouteAssetSession.State.READY, manager.getState());
		Assertions.assertTrue(Files.isRegularFile(cache.pathForPng(activeHash)));
		Assertions.assertFalse(Files.exists(cache.pathForPng(orphanHash)));
	}

	@Test
	public void explicitRepairClearsWarmRevisionAndInvalidatesTheSession() throws Exception {
		final String address = "repair-current.example.com";
		final String serverId = "0ccb46e5-37e0-46b8-b22d-83b42f190dda";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String hash = cache.admitPng(RouteAssetHash.sha256(PNG), PNG);
		final RouteAssetManifest manifest = manifest(hash);
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);
		final AtomicLong cancellations = new AtomicLong();
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = new ClientRouteAssetManager(
				cache,
				() -> 0,
				() -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", FINGERPRINT, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES),
				hellos::add,
				request -> { },
				cancellations::incrementAndGet
		);
		manager.onJoin(address);
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT, hellos.get(0).getRequestNonce()));
		final long readyGeneration = manager.getGeneration();
		final long readyCacheEpoch = cache.getSessionEpoch();

		Assertions.assertTrue(manager.repairCurrentServerCache().get());
		Assertions.assertNotEquals(readyCacheEpoch, cache.getSessionEpoch());
		Assertions.assertFalse(manager.isCurrent(readyGeneration));
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, manager.getState());
		Assertions.assertEquals(1, cancellations.get());
		Assertions.assertTrue(cache.findRevision(address).isEmpty());
		Assertions.assertTrue(cache.loadManifest(serverId).isEmpty());
	}

	@Test
	public void initializationFailuresReturnALocalFallbackManager() throws Exception {
		final Path unusable = temporaryDirectory.resolve("not-a-directory");
		Files.writeString(unusable, "fixture");
		final QueueExecutor metadataExecutor = new QueueExecutor();
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager cacheFailure = ClientRouteAssetManager.createResilient(
				() -> new ClientRouteAssetDiskCache(unusable.resolve("cache"), RouteAssetProtocol.RENDERER_VERSION),
				() -> FINGERPRINT,
				() -> 0,
				fingerprint -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", fingerprint, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES),
				hellos::add,
				request -> { },
				() -> { },
				metadataExecutor
		);
		final ClientRouteAssetManager fingerprintFailure = ClientRouteAssetManager.createResilient(
				() -> new ClientRouteAssetDiskCache(temporaryDirectory.resolve("cache"), RouteAssetProtocol.RENDERER_VERSION),
				() -> { throw new IOException("missing resources"); },
				() -> 0,
				fingerprint -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", fingerprint, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES),
				hellos::add,
				request -> { },
				() -> { },
				Runnable::run
		);

		Assertions.assertDoesNotThrow(() -> cacheFailure.onJoin("play.example.com"));
		Assertions.assertDoesNotThrow(() -> fingerprintFailure.onJoin("play.example.com"));
		Assertions.assertEquals(ClientRouteAssetSession.State.NEGOTIATING, cacheFailure.getState());
		Assertions.assertEquals(1, metadataExecutor.size());
		metadataExecutor.runNext();
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, cacheFailure.getState());
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, fingerprintFailure.getState());
		Assertions.assertTrue(hellos.isEmpty());
	}

	@Test
	public void defaultMaintenanceThreadIsNamedDaemonAndLowPriority() throws Exception {
		final ExecutorService executor = ClientRouteAssetManager.createMaintenanceExecutor();
		try {
			final Future<Thread> future = executor.submit(Thread::currentThread);
			final Thread thread = future.get();
			Assertions.assertTrue(thread.getName().startsWith("mtr-route-assets-client-cache-"));
			Assertions.assertTrue(thread.isDaemon());
			Assertions.assertEquals(Thread.MIN_PRIORITY, thread.getPriority());
		} finally {
			executor.shutdownNow();
		}
	}

	private static ClientRouteAssetManager manager(ClientRouteAssetDiskCache cache, AtomicLong clock, int resolution, String fingerprint, List<org.mtr.mod.route.RouteAssetHello> hellos, List<ClientRouteAssetManager.DocumentRequest> requests) {
		return new ClientRouteAssetManager(
				cache,
				clock::get,
				() -> new ClientRouteAssetManager.Settings(true, resolution, "NORMAL", fingerprint, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES),
				hellos::add,
				requests::add
		);
	}

	private static RouteAssetManifest manifest(String hash) {
		return RouteAssetManifest.builder().put(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|1|2|NORMAL|a=4:9,f=0,t=0,v=1"), hash, "fixture").build();
	}

	private static PacketRouteAssetManifest.ManifestPayload payload(RouteAssetNegotiation.Mode mode, String serverId, String revision, String documentHash, String fingerprint) {
		return payload(mode, serverId, revision, documentHash, fingerprint, 1);
	}

	private static PacketRouteAssetManifest.ManifestPayload payload(RouteAssetNegotiation.Mode mode, String serverId, String revision, String documentHash, String fingerprint, long requestNonce) {
		return new PacketRouteAssetManifest.ManifestPayload(
				mode,
				serverId,
				8888,
				"",
				revision,
				documentHash,
				documentHash.isEmpty() ? 0 : 128,
				documentHash.isEmpty() ? "" : "v1/" + documentHash.substring(0, 2) + "/" + documentHash + ".json",
				RouteAssetProtocol.RENDERER_VERSION,
				fingerprint,
				"",
				requestNonce
		);
	}

	private static byte[] png(int argb) throws Exception {
		final BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, argb);
		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		Assertions.assertTrue(ImageIO.write(image, "png", output));
		return output.toByteArray();
	}

	private static final class QueueExecutor implements Executor {
		private final Queue<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void execute(Runnable command) {
			tasks.add(command);
		}

		private int size() {
			return tasks.size();
		}

		private void runNext() {
			tasks.remove().run();
		}
	}
}
