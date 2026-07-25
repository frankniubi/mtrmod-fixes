package org.mtr.mod.client.asset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.mapping.holder.Identifier;
import org.mtr.mapping.holder.NativeImageBackedTexture;
import org.mtr.mod.client.DynamicTextureCache;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
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
	public void renderLookupDistinguishesNegotiationMappedAndLocalFallbackStates() throws Exception {
		final String address = "render-state.example.com:25565";
		final String serverId = "b57b948a-49d5-4b1b-8cf0-c2c923e681ab";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String pngHash = cache.admitPng(RouteAssetHash.sha256(PNG), PNG);
		final RouteAssetManifest manifest = manifest(pngHash);
		final RouteAssetKey mappedKey = manifest.getEntries().firstKey();
		final RouteAssetKey absentKey = mappedKey.withPrimaryId(mappedKey.getPrimaryId() + 1);
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);

		final QueueExecutor maintenanceExecutor = new QueueExecutor();
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = new ClientRouteAssetManager(
				cache,
				() -> 100,
				() -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", FINGERPRINT, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES),
				hellos::add,
				request -> { },
				() -> { },
				maintenanceExecutor
		);

		manager.onJoin(address);
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.PENDING, manager.lookupRouteTexture(mappedKey).getState());
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT, hellos.get(0).getRequestNonce()));
		Assertions.assertEquals(ClientRouteAssetSession.State.SYNCING, manager.getState());
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.PENDING, manager.lookupRouteTexture(mappedKey).getState());

		maintenanceExecutor.runNext();
		Assertions.assertEquals(ClientRouteAssetSession.State.READY, manager.getState());
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.PENDING, manager.lookupRouteTexture(mappedKey).getState(), "a mapped hash remains a placeholder until the GPU cache makes it resident");
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.LOCAL, manager.lookupRouteTexture(absentKey).getState());

		manager.onDisconnect();
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.LOCAL, manager.lookupRouteTexture(mappedKey).getState());
		final ClientRouteAssetManager highResolution = manager(cache, new AtomicLong(), 4, FINGERPRINT, new ArrayList<>(), new ArrayList<>());
		highResolution.onJoin(address);
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, highResolution.getState());
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.LOCAL, highResolution.lookupRouteTexture(mappedKey).getState());
	}

	@Test
	public void staleDecodeOutcomeCannotChangeTheNewGenerationCooldown() throws Exception {
		final String address = "decode-generation.example.com";
		final String serverId = "24121ced-d974-450c-8262-4867895eaf27";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String pngHash = cache.admitPng(RouteAssetHash.sha256(PNG), PNG);
		final RouteAssetManifest manifest = manifest(pngHash);
		final RouteAssetKey mappedKey = manifest.getEntries().firstKey();
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);
		final AtomicLong clock = new AtomicLong(1_000);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, clock, 2, FINGERPRINT, hellos, new ArrayList<>());

		manager.onJoin(address);
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT, hellos.get(0).getRequestNonce()));
		final long staleGeneration = manager.getGeneration();
		manager.onVariantChanged();
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT, hellos.get(1).getRequestNonce()));
		final long currentGeneration = manager.getGeneration();
		Assertions.assertNotEquals(staleGeneration, currentGeneration);

		final Method markFailure = ClientRouteAssetManager.class.getDeclaredMethod("markGpuFailure", String.class, long.class);
		final Method clearFailure = ClientRouteAssetManager.class.getDeclaredMethod("clearGpuFailure", String.class, long.class);
		markFailure.setAccessible(true);
		clearFailure.setAccessible(true);
		markFailure.invoke(manager, pngHash, staleGeneration);
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.PENDING, manager.lookupRouteTexture(mappedKey).getState(), "a late old-generation failure cannot force local rasterization");

		markFailure.invoke(manager, pngHash, currentGeneration);
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.LOCAL, manager.lookupRouteTexture(mappedKey).getState());
		clearFailure.invoke(manager, pngHash, staleGeneration);
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.LOCAL, manager.lookupRouteTexture(mappedKey).getState(), "a late old-generation success cannot clear the current cooldown");
		clearFailure.invoke(manager, pngHash, currentGeneration);
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.PENDING, manager.lookupRouteTexture(mappedKey).getState());
	}

	@Test
	public void managerCapsOutstandingDecodeAdmissionAndResetSkipsQueuedIo() throws Exception {
		final String address = "decode-admission.example.com";
		final String serverId = "e6b29d24-d9d8-46f9-a340-dbf23fd18f60";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final RouteAssetManifest.Builder manifestBuilder = RouteAssetManifest.builder();
		final List<RouteAssetKey> keys = new ArrayList<>();
		for (int index = 0; index < 65; index++) {
			final byte[] bytes = png(0xFF000000 | index);
			final String hash = cache.admitPng(RouteAssetHash.sha256(bytes), bytes);
			final RouteAssetKey key = RouteAssetKey.parse("minecraft/overworld|ROUTE_COLOR_STRIP|" + (index + 1) + "|2|NORMAL|style=DEFAULT");
			manifestBuilder.put(key, hash, "fixture-" + index);
			keys.add(key);
		}
		final RouteAssetManifest manifest = manifestBuilder.build();
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);
		final QueueExecutor decodeExecutor = new QueueExecutor();
		final AtomicLong decodeCount = new AtomicLong();
		final ClientRouteAssetGpuCache<ClientRouteAssetManager.DecodedNativeImage, ClientRouteAssetManager.RouteAssetTextureHandle> gpuCache = new ClientRouteAssetGpuCache<>(
				decodeExecutor,
				hash -> {
					decodeCount.incrementAndGet();
					throw new IOException("decoder should not run in this test");
				},
				(hash, resource) -> { throw new IOException("registrar should not run in this test"); },
				() -> 0,
				RouteAssetProtocol.DEFAULT_GPU_CACHE_BYTES
		);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, new AtomicLong(), 2, FINGERPRINT, hellos, new ArrayList<>());
		manager.installGpuCache(gpuCache);

		manager.onJoin(address);
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT, hellos.get(0).getRequestNonce()));
		for (final RouteAssetKey key : keys) Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.PENDING, manager.lookupRouteTexture(key).getState());
		Assertions.assertEquals(64, gpuCache.getInFlightCount());
		Assertions.assertEquals(64, decodeExecutor.size(), "the sixty-fifth unique hash must receive placeholder backpressure without queueing decode work");

		manager.onDisconnect();
		decodeExecutor.runAll();
		Assertions.assertEquals(0, decodeCount.get(), "reset queued tasks must fail the GPU generation precheck before decoder IO");
		Assertions.assertEquals(0, gpuCache.getInFlightCount());
	}

	@Test
	public void productionDiskDecoderTransitionsMappedLookupFromPendingToReady() throws Exception {
		final String address = "decode-ready.example.com";
		final String serverId = "09620fd4-28dc-4055-a649-2293999730df";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String pngHash = cache.admitPng(RouteAssetHash.sha256(PNG), PNG);
		final RouteAssetManifest manifest = manifest(pngHash);
		final RouteAssetKey mappedKey = manifest.getEntries().firstKey();
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, new AtomicLong(), 2, FINGERPRINT, hellos, new ArrayList<>());
		final QueueExecutor decodeExecutor = new QueueExecutor();
		final AtomicLong handleCloses = new AtomicLong();
		final DynamicTextureCache.DynamicResource resource = fakeDynamicResource();
		final ClientRouteAssetGpuCache<ClientRouteAssetManager.DecodedNativeImage, ClientRouteAssetManager.RouteAssetTextureHandle> gpuCache = new ClientRouteAssetGpuCache<>(
				manager.correlateDecodeGeneration(decodeExecutor),
				manager::decodeRouteTexture,
				(hash, decodedResource) -> {
					final ClientRouteAssetManager.DecodedNativeImage ownedImage = decodedResource.transferOwnership();
					return new ClientRouteAssetManager.RouteAssetTextureHandle(resource, () -> {
						ownedImage.close();
						handleCloses.incrementAndGet();
					});
				},
				() -> 0,
				RouteAssetProtocol.DEFAULT_GPU_CACHE_BYTES
		);
		manager.installGpuCache(gpuCache);

		manager.onJoin(address);
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT, hellos.get(0).getRequestNonce()));
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.PENDING, manager.lookupRouteTexture(mappedKey).getState());
		decodeExecutor.runNext();
		Assertions.assertEquals(1, gpuCache.getDecodedQueueSize());
		manager.beginRenderFrame(8, 2_000_000);
		final ClientRouteAssetManager.RouteTextureLookup ready = manager.lookupRouteTexture(mappedKey);
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.READY, ready.getState());
		Assertions.assertSame(resource, ready.getResource());

		manager.onDisconnect();
		Assertions.assertEquals(1, handleCloses.get(), "the resident handle must receive decoded image ownership exactly once");
	}

	@Test
	public void changedCachedBytesEnterLocalCooldownWithoutPerFrameDecodeRetry() throws Exception {
		final String address = "decode-cooldown.example.com";
		final String serverId = "1620a672-5c0f-4242-a7ba-a8521a94d909";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String pngHash = cache.admitPng(RouteAssetHash.sha256(PNG), PNG);
		final RouteAssetManifest manifest = manifest(pngHash);
		final RouteAssetKey mappedKey = manifest.getEntries().firstKey();
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);
		final AtomicLong clock = new AtomicLong(1_000);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, clock, 2, FINGERPRINT, hellos, new ArrayList<>());
		final QueueExecutor decodeExecutor = new QueueExecutor();
		final ClientRouteAssetGpuCache<ClientRouteAssetManager.DecodedNativeImage, ClientRouteAssetManager.RouteAssetTextureHandle> gpuCache = new ClientRouteAssetGpuCache<>(
				manager.correlateDecodeGeneration(decodeExecutor),
				manager::decodeRouteTexture,
				(hash, resource) -> { throw new AssertionError("corrupt content cannot reach registration"); },
				() -> 0,
				RouteAssetProtocol.DEFAULT_GPU_CACHE_BYTES
		);
		manager.installGpuCache(gpuCache);

		manager.onJoin(address);
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT, hellos.get(0).getRequestNonce()));
		Files.write(cache.pathForPng(pngHash), new byte[]{1, 2, 3});
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.PENDING, manager.lookupRouteTexture(mappedKey).getState());
		decodeExecutor.runNext();
		Assertions.assertEquals(0, gpuCache.getInFlightCount());
		for (int index = 0; index < 10; index++) Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.LOCAL, manager.lookupRouteTexture(mappedKey).getState());
		Assertions.assertEquals(0, decodeExecutor.size(), "unchanged corrupt hash must not cause a per-frame retry or HTTP repair");
		clock.addAndGet(4_999);
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.LOCAL, manager.lookupRouteTexture(mappedKey).getState());
		clock.incrementAndGet();
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.PENDING, manager.lookupRouteTexture(mappedKey).getState());
		Assertions.assertEquals(1, decodeExecutor.size());
		manager.onDisconnect();
		decodeExecutor.runAll();
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

	@Test
	public void defaultDecodePoolIsBoundedNamedDaemonAndLowPriority() throws Exception {
		final ExecutorService executor = ClientRouteAssetManager.createDecodeExecutor();
		final CountDownLatch started = new CountDownLatch(2);
		final CountDownLatch release = new CountDownLatch(1);
		try {
			final Future<Thread> first = executor.submit(() -> {
				started.countDown();
				release.await();
				return Thread.currentThread();
			});
			final Future<Thread> second = executor.submit(() -> {
				started.countDown();
				release.await();
				return Thread.currentThread();
			});
			Assertions.assertTrue(started.await(5, TimeUnit.SECONDS));
			for (int index = 0; index < 64; index++) executor.execute(() -> { });
			Assertions.assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> { }), "decode work must apply backpressure instead of growing an unbounded queue");
			release.countDown();
			for (final Thread thread : List.of(first.get(), second.get())) {
				Assertions.assertTrue(thread.getName().startsWith("mtr-route-assets-client-decode-"));
				Assertions.assertTrue(thread.isDaemon());
				Assertions.assertEquals(Thread.MIN_PRIORITY, thread.getPriority());
			}
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void refreshPreservesReadyManifestAndRejectsDuplicates() throws Exception {
		final String address = "refresh-ready.example.com";
		final String serverId = "c2f97e82-d97e-4cbb-931e-4911279ab2af";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String pngHash = cache.admitPng(RouteAssetHash.sha256(PNG), PNG);
		final RouteAssetManifest manifest = manifest(pngHash);
		final RouteAssetKey key = manifest.getEntries().firstKey();
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, new AtomicLong(1_000), 2, FINGERPRINT, hellos, new ArrayList<>());

		manager.onJoin(address);
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT, manager.getGeneration()));
		final long readyNonce = manager.getGeneration();
		manager.handleRefresh("a".repeat(64), readyNonce - 1);
		manager.handleRefresh(manifest.getRevision(), readyNonce);
		Assertions.assertEquals(1, hellos.size());

		manager.handleRefresh("b".repeat(64), readyNonce);
		Assertions.assertEquals(ClientRouteAssetSession.State.NEGOTIATING, manager.getState());
		Assertions.assertEquals(manifest.getRevision(), hellos.get(1).getCachedRevision());
		manager.handleRefresh("b".repeat(64), manager.getGeneration());
		Assertions.assertEquals(2, hellos.size());
		manager.handleRefresh("c".repeat(64), manager.getGeneration());
		manager.handleManifest(PacketRouteAssetManifest.ManifestPayload.fallback("temporary", FINGERPRINT, manager.getGeneration()));
		Assertions.assertEquals(ClientRouteAssetSession.State.NEGOTIATING, manager.getState(), "a newer revision observed during negotiation must immediately start the next re-hello");
		Assertions.assertEquals(3, hellos.size());
		manager.handleRefresh("c".repeat(64), manager.getGeneration());
		Assertions.assertEquals(3, hellos.size());
		manager.handleManifest(PacketRouteAssetManifest.ManifestPayload.fallback("temporary", FINGERPRINT, manager.getGeneration()));
		Assertions.assertEquals(ClientRouteAssetSession.State.READY, manager.getState());
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.PENDING, manager.lookupRouteTexture(key).getState());
	}

	@Test
	public void emptyHeadAndIncrementalTimeoutRecoverWithoutDroppingOldManifest() throws Exception {
		final ClientRouteAssetDiskCache emptyCache = new ClientRouteAssetDiskCache(temporaryDirectory.resolve("empty"), RouteAssetProtocol.RENDERER_VERSION);
		final List<org.mtr.mod.route.RouteAssetHello> emptyHellos = new ArrayList<>();
		final ClientRouteAssetManager empty = manager(emptyCache, new AtomicLong(), 2, FINGERPRINT, emptyHellos, new ArrayList<>());
		empty.onJoin("empty.example.com");
		empty.handleManifest(PacketRouteAssetManifest.ManifestPayload.fallback("no-revision", FINGERPRINT, empty.getGeneration()));
		Assertions.assertEquals(ClientRouteAssetSession.State.LOCAL_FALLBACK, empty.getState());
		empty.handleRefresh("c".repeat(64), empty.getGeneration());
		Assertions.assertEquals(ClientRouteAssetSession.State.NEGOTIATING, empty.getState());
		Assertions.assertEquals(2, emptyHellos.size());

		final String address = "timeout.example.com";
		final String serverId = "cff21004-3252-4474-b739-23e31fe299e6";
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory.resolve("timeout"), RouteAssetProtocol.RENDERER_VERSION);
		final String hash = cache.admitPng(RouteAssetHash.sha256(PNG), PNG);
		final RouteAssetManifest manifest = manifest(hash);
		cache.storeManifest(serverId, manifest);
		cache.associate(address, serverId);
		final AtomicLong clock = new AtomicLong(1_000);
		final List<ClientRouteAssetManager.DocumentRequest> requests = new ArrayList<>();
		final ClientRouteAssetManager manager = manager(cache, clock, 2, FINGERPRINT, new ArrayList<>(), requests);
		manager.onJoin(address);
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.UNCHANGED, serverId, manifest.getRevision(), "", FINGERPRINT, manager.getGeneration()));
		manager.handleRefresh("d".repeat(64), manager.getGeneration());
		manager.handleManifest(payload(RouteAssetNegotiation.Mode.SNAPSHOT, serverId, "d".repeat(64), "e".repeat(64), FINGERPRINT, manager.getGeneration()));
		Assertions.assertEquals(ClientRouteAssetSession.State.SYNCING, manager.getState());
		clock.addAndGet(30_000);
		manager.tick(clock.get());
		Assertions.assertEquals(ClientRouteAssetSession.State.READY, manager.getState());
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.PENDING, manager.lookupRouteTexture(manifest.getEntries().firstKey()).getState());
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

	private static DynamicTextureCache.DynamicResource fakeDynamicResource() throws Exception {
		final Constructor<DynamicTextureCache.DynamicResource> constructor = DynamicTextureCache.DynamicResource.class.getDeclaredConstructor(Identifier.class, NativeImageBackedTexture.class);
		constructor.setAccessible(true);
		return constructor.newInstance(new Identifier("mtr", "route_asset_test"), null);
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

		private void runAll() {
			while (!tasks.isEmpty()) runNext();
		}
	}
}
