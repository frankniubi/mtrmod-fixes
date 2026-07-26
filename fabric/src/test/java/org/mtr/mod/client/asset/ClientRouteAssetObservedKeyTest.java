package org.mtr.mod.client.asset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.mod.packet.PacketRouteAssetManifest;
import org.mtr.mod.config.LanguageDisplay;
import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetManifest;
import org.mtr.mod.route.RouteAssetNegotiation;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.RouteAssetCanonicalKeyFactory;
import org.mtr.mod.route.DestinationSignStyle;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientRouteAssetObservedKeyTest {

	private static final String FINGERPRINT = "f".repeat(64);
	private static final String SERVER_ID = "bff58817-7660-4bc3-a662-615cd6369a4d";
	private static final String ADDRESS = "observed.example.com";
	private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

	@TempDir
	Path temporaryDirectory;

	@Test
	public void readyMissesAreDeduplicatedBatchedAndRateLimited() throws Exception {
		final AtomicLong clock = new AtomicLong(1_000);
		final List<List<RouteAssetKey>> packets = new ArrayList<>();
		final ReadyManager ready = readyManager(clock, packets);
		final RouteAssetKey template = ready.manifest.getEntries().firstKey();

		for (int index = 0; index < 70; index++) {
			final RouteAssetKey missing = template.withPrimaryId(1_000 + index);
			Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.LOCAL, ready.manager.lookupRouteTexture(missing).getState());
			Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.LOCAL, ready.manager.lookupRouteTexture(missing).getState());
		}
		final RouteAssetKey otherDimension = RouteAssetKey.parse(template.toString().replace("minecraft/overworld", "minecraft/the_nether")).withPrimaryId(9_999);
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.LOCAL, ready.manager.lookupRouteTexture(otherDimension).getState());

		ready.manager.tick(clock.get());
		Assertions.assertEquals(1, packets.size());
		Assertions.assertEquals(RouteAssetProtocol.MAX_OBSERVED_KEYS_PER_PLAYER_PER_MINUTE, packets.get(0).size());
		Assertions.assertEquals(packets.get(0).size(), packets.get(0).stream().distinct().count());
		Assertions.assertFalse(packets.get(0).contains(otherDimension));

		clock.addAndGet(59_999);
		ready.manager.tick(clock.get());
		Assertions.assertEquals(1, packets.size(), "the remaining keys must wait for the next rate window");

		clock.incrementAndGet();
		ready.manager.tick(clock.get());
		Assertions.assertEquals(2, packets.size());
		Assertions.assertEquals(6, packets.get(1).size());
	}

	@Test
	public void publishedAndResetKeysCanBeObservedAgain() throws Exception {
		final AtomicLong clock = new AtomicLong(5_000);
		final List<List<RouteAssetKey>> packets = new ArrayList<>();
		final ReadyManager ready = readyManager(clock, packets);
		final RouteAssetKey observed = ready.manifest.getEntries().firstKey().withPrimaryId(2_000);

		ready.manager.lookupRouteTexture(observed);
		ready.manager.tick(clock.get());
		Assertions.assertEquals(List.of(observed), packets.get(0));

		final RouteAssetManifest.Builder withObservedBuilder = RouteAssetManifest.builder();
		ready.manifest.getEntries().forEach(withObservedBuilder::put);
		final RouteAssetManifest withObserved = withObservedBuilder.put(observed, ready.pngHash, "observed").build();
		activateCachedManifest(ready, withObserved);
		activateCachedManifest(ready, ready.manifest);
		clock.addAndGet(60_000);
		ready.manager.lookupRouteTexture(observed);
		ready.manager.tick(clock.get());
		Assertions.assertEquals(List.of(observed), packets.get(1), "a key present in a successful manifest must leave the awaiting set");

		ready.manager.onDisconnect();
		activateInitialManifest(ready.manager, ready.cache, ready.manifest);
		ready.manager.lookupRouteTexture(observed);
		ready.manager.tick(clock.get());
		Assertions.assertEquals(List.of(observed), packets.get(2), "disconnect must clear observed-key deduplication and rate state");
	}

	@Test
	public void missingDestinationAtlasUsesLocalFallbackWithoutObservedKeyTraffic() throws Exception {
		final AtomicLong clock = new AtomicLong(5_000);
		final List<List<RouteAssetKey>> packets = new ArrayList<>();
		final ReadyManager ready = readyManager(clock, packets);
		final RouteAssetKey destination = RouteAssetCanonicalKeyFactory.destinationSign("minecraft/overworld", 10, 20, 2, DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		Assertions.assertEquals(ClientRouteAssetManager.RouteTextureState.LOCAL, ready.manager.lookupRouteTexture(destination).getState());
		ready.manager.tick(clock.get());
		Assertions.assertTrue(packets.isEmpty());
	}

	@Test
	public void resourceReloadStartsANewNegotiationGeneration() throws Exception {
		final AtomicInteger cancellations = new AtomicInteger();
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = new ClientRouteAssetManager(
				new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION),
				() -> 0,
				() -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", FINGERPRINT, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES),
				hellos::add,
				request -> { },
				cancellations::incrementAndGet
		);

		manager.onJoin(ADDRESS);
		final long firstGeneration = manager.getGeneration();
		manager.onResourcesReloaded();

		Assertions.assertNotEquals(firstGeneration, manager.getGeneration());
		Assertions.assertEquals(2, hellos.size());
		Assertions.assertEquals(1, cancellations.get());
		Assertions.assertEquals(ClientRouteAssetSession.State.NEGOTIATING, manager.getState());
	}

	@Test
	public void clientLanguageOptionsMapToProtocolVariants() {
		Assertions.assertEquals("NORMAL", ClientRouteAssetManager.routeAssetLanguage(LanguageDisplay.NORMAL));
		Assertions.assertEquals("CJK", ClientRouteAssetManager.routeAssetLanguage(LanguageDisplay.CJK_ONLY));
		Assertions.assertEquals("LATIN", ClientRouteAssetManager.routeAssetLanguage(LanguageDisplay.NON_CJK_ONLY));
	}

	@Test
	public void resourceRenegotiationPreservesTheConnectionRateWindow() throws Exception {
		final AtomicLong clock = new AtomicLong(10_000);
		final List<List<RouteAssetKey>> packets = new ArrayList<>();
		final ReadyManager ready = readyManager(clock, packets);
		final RouteAssetKey template = ready.manifest.getEntries().firstKey();
		for (int index = 0; index < RouteAssetProtocol.MAX_OBSERVED_KEYS_PER_PLAYER_PER_MINUTE; index++) ready.manager.lookupRouteTexture(template.withPrimaryId(3_000 + index));
		ready.manager.tick(clock.get());
		Assertions.assertEquals(1, packets.size());

		ready.manager.onResourcesReloaded();
		ready.manager.handleManifest(payload(ready.manifest, ready.manager.getGeneration()));
		Assertions.assertEquals(ClientRouteAssetSession.State.READY, ready.manager.getState());
		ready.manager.lookupRouteTexture(template.withPrimaryId(4_000));
		ready.manager.tick(clock.get());
		Assertions.assertEquals(1, packets.size(), "a resource/variant re-hello must not reset the connection-scoped 64/minute window");

		clock.addAndGet(60_000);
		ready.manager.tick(clock.get());
		Assertions.assertEquals(2, packets.size());
		Assertions.assertEquals(1, packets.get(1).size());
	}

	private ReadyManager readyManager(AtomicLong clock, List<List<RouteAssetKey>> packets) throws Exception {
		final ClientRouteAssetDiskCache cache = new ClientRouteAssetDiskCache(temporaryDirectory, RouteAssetProtocol.RENDERER_VERSION);
		final String pngHash = cache.admitPng(RouteAssetHash.sha256(PNG), PNG);
		final RouteAssetManifest manifest = RouteAssetManifest.builder()
				.put(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|1|2|NORMAL|a=4:9,f=0,t=0,v=1"), pngHash, "fixture")
				.build();
		cache.storeManifest(SERVER_ID, manifest);
		cache.associate(ADDRESS, SERVER_ID);
		final List<org.mtr.mod.route.RouteAssetHello> hellos = new ArrayList<>();
		final ClientRouteAssetManager manager = new ClientRouteAssetManager(
				cache,
				clock::get,
				() -> new ClientRouteAssetManager.Settings(true, 2, "NORMAL", FINGERPRINT, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES),
				hellos::add,
				request -> { }
		);
		manager.installObservedKeySender(keys -> packets.add(List.copyOf(keys)), () -> "minecraft/overworld");
		activateInitialManifest(manager, cache, manifest);
		return new ReadyManager(manager, cache, manifest, pngHash, hellos);
	}

	private static void activateInitialManifest(ClientRouteAssetManager manager, ClientRouteAssetDiskCache cache, RouteAssetManifest manifest) throws Exception {
		cache.storeManifest(SERVER_ID, manifest);
		cache.associate(ADDRESS, SERVER_ID);
		manager.onJoin(ADDRESS);
		manager.handleManifest(payload(manifest, manager.getGeneration()));
		Assertions.assertEquals(ClientRouteAssetSession.State.READY, manager.getState());
	}

	private static void activateCachedManifest(ReadyManager ready, RouteAssetManifest manifest) throws Exception {
		ready.cache.storeManifest(SERVER_ID, manifest);
		ready.manager.handleRefresh(manifest.getRevision(), ready.manager.getGeneration());
		ready.manager.handleManifest(payload(manifest, ready.manager.getGeneration()));
		Assertions.assertEquals(ClientRouteAssetSession.State.READY, ready.manager.getState());
	}

	private static PacketRouteAssetManifest.ManifestPayload payload(RouteAssetManifest manifest, long requestNonce) {
		return new PacketRouteAssetManifest.ManifestPayload(
				RouteAssetNegotiation.Mode.UNCHANGED,
				SERVER_ID,
				8888,
				"",
				manifest.getRevision(),
				"",
				0,
				"",
				RouteAssetProtocol.RENDERER_VERSION,
				FINGERPRINT,
				"",
				requestNonce
		);
	}

	private static final class ReadyManager {
		private final ClientRouteAssetManager manager;
		private final ClientRouteAssetDiskCache cache;
		private final RouteAssetManifest manifest;
		private final String pngHash;
		@SuppressWarnings("unused") private final List<org.mtr.mod.route.RouteAssetHello> hellos;

		private ReadyManager(ClientRouteAssetManager manager, ClientRouteAssetDiskCache cache, RouteAssetManifest manifest, String pngHash, List<org.mtr.mod.route.RouteAssetHello> hellos) {
			this.manager = manager;
			this.cache = cache;
			this.manifest = manifest;
			this.pngHash = pngHash;
			this.hellos = hellos;
		}
	}
}
