package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.ClientData;
import org.mtr.core.operation.ListDataResponse;
import org.mtr.core.tool.Utilities;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class RouteAssetServerManagerTest {

	@TempDir
	Path root;

	@Test
	public void unchangedFingerprintsRenderNothingAndSamePixelsDoNotMoveHead() throws Exception {
		final AtomicInteger renderCalls = new AtomicInteger();
		final AtomicBoolean constantPixels = new AtomicBoolean(false);
		try (final RouteAssetServerManager manager = manager(1, (key, snapshot) -> {
			renderCalls.incrementAndGet();
			return image(constantPixels.get() ? 7 : snapshot.getRouteName().hashCode());
		})) {
			manager.submitSnapshot(snapshot("A"), "initial");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			Assertions.assertEquals(36, renderCalls.get());
			Assertions.assertEquals(36, manager.getMetrics().getLastSummary().getAdd());
			Assertions.assertEquals(0, manager.getMetrics().getLastSummary().getModify());
			final RouteAssetRepository.RouteAssetHead firstHead = manager.getRepository().loadHead();
			final String firstRevision = firstHead.getRevision();
			final long firstDiffCount = diffCount(manager);
			final RouteAssetManifest firstManifest = manager.getRepository().loadManifest(firstRevision);
			final Set<Integer> genericResolutions = new HashSet<>();
			final Set<Integer> routeSignResolutions = new HashSet<>();
			for (final RouteAssetKey key : firstManifest.getEntries().keySet()) {
				if (key.getType() != RouteAssetType.ROUTE_MAP) continue;
				final String purpose = key.getVariant().getParameters().get("p");
				if (RouteMapPurpose.GENERIC.name().equals(purpose)) genericResolutions.add(key.getVariant().getResolution());
				if (RouteMapPurpose.ROUTE_SIGN.name().equals(purpose)) routeSignResolutions.add(key.getVariant().getResolution());
			}
			Assertions.assertEquals(Set.of(0, 1, 2, 3), genericResolutions);
			Assertions.assertEquals(Set.of(0, 1, 2, 3), routeSignResolutions);

			manager.submitSnapshot(snapshot("A"), "unchanged");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			Assertions.assertEquals(36, renderCalls.get());
			final RouteAssetRepository.RouteAssetHead unchangedHead = manager.getRepository().loadHead();
			Assertions.assertEquals(firstRevision, unchangedHead.getRevision());
			Assertions.assertEquals(firstHead.getDiffDocumentHash(), unchangedHead.getDiffDocumentHash());
			Assertions.assertEquals(firstDiffCount, diffCount(manager));

			constantPixels.set(true);
			manager.submitSnapshot(snapshot("B"), "first-constant");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			Assertions.assertEquals(0, manager.getMetrics().getLastSummary().getAdd());
			Assertions.assertEquals(16, manager.getMetrics().getLastSummary().getModify());
			final String constantRevision = manager.getRepository().loadHead().getRevision();
			manager.submitSnapshot(snapshot("C"), "same-pixels");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			Assertions.assertEquals(constantRevision, manager.getRepository().loadHead().getRevision());
		}
	}

	@Test
	public void oldClientRendererFallsBackAgainstTheCurrentServer() throws Exception {
		try (final RouteAssetServerManager manager = manager(1, (key, snapshot) -> image(1))) {
			manager.submitSnapshot(snapshot("Compatibility"), "compatibility");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			final RouteAssetHello current = hello(RouteAssetProtocol.RENDERER_VERSION);
			final RouteAssetHello old = hello(RouteAssetProtocol.RENDERER_VERSION - 1);
			final RouteAssetHello oldProtocol = new RouteAssetHello(RouteAssetProtocol.PROTOCOL_VERSION - 1,
					RouteAssetProtocol.RENDERER_VERSION, 2, "NORMAL", "f".repeat(64), "", true, false,
					RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, 2);

			Assertions.assertNotEquals(RouteAssetNegotiation.Mode.FALLBACK, manager.negotiate(current).getMode());
			Assertions.assertEquals(RouteAssetNegotiation.Mode.FALLBACK, manager.negotiate(old).getMode());
			Assertions.assertEquals(RouteAssetNegotiation.Mode.FALLBACK, manager.negotiate(oldProtocol).getMode());
		}
	}

	@Test
	public void latestGenerationWinsAndFailuresPreserveHead() throws Exception {
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicBoolean block = new AtomicBoolean(true);
		final AtomicBoolean fail = new AtomicBoolean(false);
		try (final RouteAssetServerManager manager = manager(2, (key, snapshot) -> {
			if (block.compareAndSet(true, false)) {
				started.countDown();
				if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
			}
			if (fail.get()) throw new IllegalStateException("injected");
			return image(snapshot.getRouteName().hashCode());
		})) {
			manager.submitSnapshot(snapshot("Old"), "old");
			Assertions.assertTrue(started.await(10, TimeUnit.SECONDS));
			manager.submitSnapshot(snapshot("Middle"), "middle");
			manager.submitSnapshot(snapshot("Newest"), "newest");
			release.countDown();
			Assertions.assertTrue(manager.awaitIdle(10_000));
			Assertions.assertTrue(manager.getMetrics().getCancelledGenerations() >= 1);
			final String newestRevision = manager.getRepository().loadHead().getRevision();

			fail.set(true);
			manager.submitSnapshot(snapshot("Broken"), "broken");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			Assertions.assertEquals(newestRevision, manager.getRepository().loadHead().getRevision());
			Assertions.assertTrue(manager.getMetrics().getFailedGenerations() >= 1);
		}
	}

	@Test
	public void threadsAreBoundedNamedDaemonAndLowPriority() throws Exception {
		final AtomicReference<Thread> renderThread = new AtomicReference<>();
		try (final RouteAssetServerManager manager = manager(99, (key, snapshot) -> {
			renderThread.compareAndSet(null, Thread.currentThread());
			return image(1);
		})) {
			Assertions.assertEquals(4, manager.getGenerationThreads());
			manager.submitSnapshot(snapshot("Thread"), "thread-check");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			Assertions.assertNotNull(renderThread.get());
			Assertions.assertTrue(renderThread.get().isDaemon());
			Assertions.assertTrue(renderThread.get().getName().startsWith("mtr-route-assets-worker-"));
			Assertions.assertEquals(Thread.MIN_PRIORITY, renderThread.get().getPriority());
		}
	}

	@Test
	public void allDimensionsMustReachTheBarrierBeforePublication() throws Exception {
		try (final RouteAssetServerManager manager = manager(1, (key, snapshot) -> image(1))) {
			final long generation = manager.beginFullRefresh(Set.of("minecraft/overworld", "minecraft/the_nether"), "barrier");
			final org.mtr.libraries.com.google.gson.JsonObject empty = Utilities.getJsonObjectFromData(new ListDataResponse(new ClientData()).list());
			manager.acceptFullData("minecraft/the_nether", generation, empty);
			Assertions.assertTrue(manager.awaitIdle(200));
			Assertions.assertEquals("", manager.getRepository().loadHead().getRevision());
			manager.acceptFullData("minecraft/overworld", generation, empty);
			Assertions.assertTrue(manager.awaitIdle(10_000));
			Assertions.assertFalse(manager.getRepository().loadHead().getRevision().isEmpty());
			final RouteAssetMetrics.Summary summary = manager.getMetrics().getLastSummary();
			Assertions.assertEquals(0, summary.getAdd());
			Assertions.assertEquals(0, summary.getModify());
			Assertions.assertEquals(0, summary.getDelete());
			Assertions.assertEquals(0, summary.getMove());
		}
	}

	@Test
	public void configuredRouteSignsPublishAllStaticVariantsWithoutClientObservation() throws Exception {
		final AtomicInteger renderCalls = new AtomicInteger();
		try (final RouteAssetServerManager manager = manager(1, (key, snapshot) -> {
			renderCalls.incrementAndGet();
			return image(key.toString().hashCode());
		})) {
			final ConfiguredSignAssetIndex index = new ConfiguredSignAssetIndex();
			index.configureRouteSign(1, 20, RouteSignStyleMode.RAILWAY);
			index.configureRouteSign(2, 20, RouteSignStyleMode.RAILWAY);
			manager.submitSnapshot(snapshot("Configured"), index.snapshot("minecraft/overworld"), "configured-add");
			Assertions.assertTrue(manager.awaitIdle(10_000));

			final RouteAssetManifest configured = manager.getRepository().loadManifest(manager.getRepository().loadHead().getRevision());
			final Set<RouteAssetKey> explicit = configured.getEntries().keySet().stream()
					.filter(RouteAssetServerManagerTest::isExplicitRailwayRouteSign)
					.collect(java.util.stream.Collectors.toSet());
			Assertions.assertEquals(12, explicit.size());
			Assertions.assertEquals(Set.of("NORMAL", "CJK", "LATIN"), explicit.stream().map(key -> key.getVariant().getLanguage()).collect(java.util.stream.Collectors.toSet()));
			Assertions.assertEquals(Set.of(0, 1, 2, 3), explicit.stream().map(key -> key.getVariant().getResolution()).collect(java.util.stream.Collectors.toSet()));
			Assertions.assertEquals(48, renderCalls.get());

			manager.submitSnapshot(snapshot("Configured"), List.of(), "configured-remove");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			final RouteAssetManifest removed = manager.getRepository().loadManifest(manager.getRepository().loadHead().getRevision());
			Assertions.assertTrue(removed.getEntries().keySet().stream().noneMatch(RouteAssetServerManagerTest::isExplicitRailwayRouteSign));
			Assertions.assertEquals(12, manager.getMetrics().getLastSummary().getDelete());
		}
	}

	@Test
	public void configuredRouteSignSetsAndHeadersRemainDistinctAndPruneIndependently() throws Exception {
		try (final RouteAssetServerManager manager = manager(1, (key, snapshot) -> image(key.toString().hashCode()))) {
			final ConfiguredSignAssetIndex all = new ConfiguredSignAssetIndex();
			all.configureRouteSign(1, Set.of(20L, 30L), RouteSignStyleMode.RAILWAY, "A|A");
			all.configureRouteSign(2, Set.of(20L), RouteSignStyleMode.RAILWAY, "B|B");
			all.configureRouteSign(3, Set.of(30L, 20L), RouteSignStyleMode.RAILWAY, "A|A");
			manager.submitSnapshot(multiRouteSignSnapshot("Configured"), all.snapshot("minecraft/overworld"), "multi-configured-add");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			RouteAssetManifest manifest = manager.getRepository().loadManifest(manager.getRepository().loadHead().getRevision());
			Assertions.assertEquals(24, manifest.getEntries().keySet().stream().filter(RouteAssetServerManagerTest::isExplicitRailwayRouteSign).count());

			final ConfiguredSignAssetIndex stillBoth = new ConfiguredSignAssetIndex();
			stillBoth.configureRouteSign(2, Set.of(20L), RouteSignStyleMode.RAILWAY, "B|B");
			stillBoth.configureRouteSign(3, Set.of(20L, 30L), RouteSignStyleMode.RAILWAY, "A|A");
			manager.submitSnapshot(multiRouteSignSnapshot("Configured"), stillBoth.snapshot("minecraft/overworld"), "multi-configured-one-anchor-removed");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			manifest = manager.getRepository().loadManifest(manager.getRepository().loadHead().getRevision());
			Assertions.assertEquals(24, manifest.getEntries().keySet().stream().filter(RouteAssetServerManagerTest::isExplicitRailwayRouteSign).count());

			final ConfiguredSignAssetIndex onlyHeaderB = new ConfiguredSignAssetIndex();
			onlyHeaderB.configureRouteSign(2, Set.of(20L), RouteSignStyleMode.RAILWAY, "B|B");
			manager.submitSnapshot(multiRouteSignSnapshot("Configured"), onlyHeaderB.snapshot("minecraft/overworld"), "multi-configured-identity-removed");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			manifest = manager.getRepository().loadManifest(manager.getRepository().loadHead().getRevision());
			Assertions.assertEquals(12, manifest.getEntries().keySet().stream().filter(RouteAssetServerManagerTest::isExplicitRailwayRouteSign).count());
			Assertions.assertEquals(12, manager.getMetrics().getLastSummary().getDelete());
		}
	}

	@Test
	public void duplicateConfiguredDestinationSignsPublishFourCanonicalAtlases() throws Exception {
		final AtomicInteger renderCalls = new AtomicInteger();
		try (final RouteAssetServerManager manager = manager(1, (key, snapshot) -> {
			renderCalls.incrementAndGet();
			return image(key.toString().hashCode());
		})) {
			final ConfiguredSignAssetIndex index = destinationIndex();
			index.configureDestinationSign(2, destinationConfig());
			manager.submitSnapshot(destinationSnapshot("Destination"), index.snapshot("minecraft/overworld"), "destination-add");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			final RouteAssetManifest manifest = manager.getRepository().loadManifest(manager.getRepository().loadHead().getRevision());
			final List<RouteAssetKey> atlases = manifest.getEntries().keySet().stream().filter(key -> key.getType() == RouteAssetType.DESTINATION_SIGN_ATLAS).toList();
			Assertions.assertEquals(4, atlases.size());
			Assertions.assertEquals(Set.of(0, 1, 2, 3), atlases.stream().map(key -> key.getVariant().getResolution()).collect(java.util.stream.Collectors.toSet()));
			Assertions.assertTrue(atlases.stream().allMatch(key -> key.getVariant().getLanguage().equals("MULTI")));
			Assertions.assertEquals(40, renderCalls.get());
		}
	}

	@Test
	public void configuredDestinationDensityIsPublishedInEveryServerKey() throws Exception {
		try (final RouteAssetServerManager manager = manager(1, (key, snapshot) -> image(key.toString().hashCode()))) {
			final ConfiguredSignAssetIndex index = new ConfiguredSignAssetIndex();
			index.configureDestinationSign(1, new DestinationSignConfiguredEntry(
					1, 2, 3, 2, DestinationSignStyle.ARRIVAL_ORDER, true, 4));
			manager.submitSnapshot(destinationSnapshot("Density"), index.snapshot("minecraft/overworld"), "destination-density");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			final List<RouteAssetKey> keys = manager.getRepository().loadManifest(manager.getRepository().loadHead().getRevision())
					.getEntries().keySet().stream().filter(key -> key.getType() == RouteAssetType.DESTINATION_SIGN_ATLAS).toList();
			Assertions.assertEquals(4, keys.size());
			Assertions.assertTrue(keys.stream().allMatch(key -> "4".equals(key.getVariant().getParameters().get("rpb"))));
		}
	}

	@Test
	public void failedConfiguredAtlasRetainsPriorEntriesWhileOtherChangesPublish() throws Exception {
		final AtomicBoolean failDestination = new AtomicBoolean();
		try (final RouteAssetServerManager manager = manager(1, (key, snapshot) -> {
			if (key.getType() == RouteAssetType.DESTINATION_SIGN_ATLAS && failDestination.get()) throw new IOException("destination failure");
			return image(key.getType() == RouteAssetType.DESTINATION_SIGN_ATLAS ? 99 : snapshot.getRouteName().hashCode());
		})) {
			final List<ConfiguredSignAssetIndex.Entry> configured = destinationIndex().snapshot("minecraft/overworld");
			manager.submitSnapshot(destinationSnapshot("Before"), configured, "before");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			final String firstRevision = manager.getRepository().loadHead().getRevision();
			final RouteAssetManifest first = manager.getRepository().loadManifest(firstRevision);

			failDestination.set(true);
			manager.submitSnapshot(destinationSnapshot("After"), configured, "after");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			final String secondRevision = manager.getRepository().loadHead().getRevision();
			final RouteAssetManifest second = manager.getRepository().loadManifest(secondRevision);
			Assertions.assertNotEquals(firstRevision, secondRevision);
			first.getEntries().forEach((key, entry) -> {
				if (key.getType() == RouteAssetType.DESTINATION_SIGN_ATLAS) Assertions.assertEquals(entry, second.getEntries().get(key));
			});
			Assertions.assertEquals(4, manager.getConfiguredAssetDiagnostics().size());
			Assertions.assertEquals(0, manager.getMetrics().getFailedGenerations());
		}
	}

	private RouteAssetServerManager manager(int threads, RouteAssetServerManager.RenderFunction renderer) throws Exception {
		return new RouteAssetServerManager(new RouteAssetRepository(root.resolve("manager-" + System.nanoTime()), RouteAssetProtocol.RENDERER_VERSION, 32), new RouteAssetDataMirror(), new RouteAssetDependencyCatalog(), renderer, threads, "f".repeat(64), new RouteAssetMetrics());
	}

	private static RouteAssetHello hello(int rendererVersion) {
		return new RouteAssetHello(RouteAssetProtocol.PROTOCOL_VERSION, rendererVersion, 2, "NORMAL", "f".repeat(64), "", true, false, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, 1);
	}

	private static boolean isExplicitRailwayRouteSign(RouteAssetKey key) {
		return key.getType() == RouteAssetType.ROUTE_MAP
				&& RouteMapPurpose.ROUTE_SIGN.name().equals(key.getVariant().getParameters().get("p"))
				&& RouteSignStyleMode.RAILWAY.name().equals(key.getVariant().getParameters().get("s"));
	}

	private static ConfiguredSignAssetIndex destinationIndex() {
		final ConfiguredSignAssetIndex index = new ConfiguredSignAssetIndex();
		index.configureDestinationSign(1, destinationConfig());
		return index;
	}

	private static DestinationSignConfiguredEntry destinationConfig() {
		return new DestinationSignConfiguredEntry(1, 2, 3, 2, DestinationSignStyle.ARRIVAL_ORDER, true);
	}

	private static long diffCount(RouteAssetServerManager manager) throws IOException {
		final Path diffs = manager.getRepository().getCas().getOutputRoot().resolve("v" + RouteAssetProtocol.RENDERER_VERSION).resolve("repository").resolve("diffs");
		try (final java.util.stream.Stream<Path> paths = Files.list(diffs)) {
			return paths.count();
		}
	}

	private static RouteAssetDataMirror.Snapshot snapshot(String routeName) {
		final List<RouteAssetRenderSnapshot.Station> stations = List.of(
				new RouteAssetRenderSnapshot.Station(1, "One", false, true, false, false),
				new RouteAssetRenderSnapshot.Station(2, "Two", false, false, false, false)
		);
		final RouteAssetRenderSnapshot.Route route = new RouteAssetRenderSnapshot.Route(10, routeName, 0x14755E, stations);
		final RouteAssetDataMirror.PlatformSnapshot platform = new RouteAssetDataMirror.PlatformSnapshot(20, "Platform", 100, List.of(route));
		final RouteAssetDataMirror.DimensionSnapshot dimension = new RouteAssetDataMirror.DimensionSnapshot("minecraft/overworld", 1, Map.of(20L, platform));
		return new RouteAssetDataMirror.Snapshot(1, Map.of("minecraft/overworld", dimension));
	}

	private static RouteAssetDataMirror.Snapshot multiRouteSignSnapshot(String routeName) {
		final RouteAssetDataMirror.Snapshot base = snapshot(routeName);
		final RouteAssetDataMirror.DimensionSnapshot original = base.getDimensions().get("minecraft/overworld");
		final RouteAssetDataMirror.PlatformSnapshot first = original.getPlatforms().get(20L);
		final RouteAssetDataMirror.PlatformSnapshot second = new RouteAssetDataMirror.PlatformSnapshot(30, "Platform 2", 100, first.getRoutes());
		return new RouteAssetDataMirror.Snapshot(1, Map.of("minecraft/overworld",
				new RouteAssetDataMirror.DimensionSnapshot("minecraft/overworld", 1, Map.of(20L, first, 30L, second))));
	}

	private static RouteAssetDataMirror.Snapshot destinationSnapshot(String routeName) {
		final RouteAssetDataMirror.Snapshot base = snapshot(routeName);
		final DestinationSignTopology topology = new DestinationSignTopology(List.of(
				new DestinationSignTopology.ServiceRoute(10, 0, routeName, 0x14755E, List.of(
						new DestinationSignTopology.StopOccurrence(20, 1, "U1", "Source", "Target"),
						new DestinationSignTopology.StopOccurrence(21, 2, "D1", "Target", "")))
		), List.of(new DestinationSignTopology.StationZone(1, "Source"), new DestinationSignTopology.StationZone(2, "Target")));
		final RouteAssetDataMirror.DimensionSnapshot oldDimension = base.getDimensions().get("minecraft/overworld");
		return new RouteAssetDataMirror.Snapshot(1, Map.of("minecraft/overworld", new RouteAssetDataMirror.DimensionSnapshot(
				"minecraft/overworld", 1, oldDimension.getPlatforms(), topology)));
	}

	private static RouteAssetImage image(int value) {
		final RouteAssetImage image = new RouteAssetImage(2, 2);
		image.fillRect(0, 0, 2, 2, 0xFF000000 | value & 0xFFFFFF);
		return image;
	}
}
