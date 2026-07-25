package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.core.data.ClientData;
import org.mtr.core.operation.ListDataResponse;
import org.mtr.core.tool.Utilities;

import java.nio.file.Path;
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
			Assertions.assertEquals(16, renderCalls.get());
			Assertions.assertEquals(16, manager.getMetrics().getLastSummary().getAdd());
			Assertions.assertEquals(0, manager.getMetrics().getLastSummary().getModify());
			final String firstRevision = manager.getRepository().loadHead().getRevision();

			manager.submitSnapshot(snapshot("A"), "unchanged");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			Assertions.assertEquals(16, renderCalls.get());
			Assertions.assertEquals(firstRevision, manager.getRepository().loadHead().getRevision());

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

	private RouteAssetServerManager manager(int threads, RouteAssetServerManager.RenderFunction renderer) throws Exception {
		return new RouteAssetServerManager(new RouteAssetRepository(root.resolve("manager-" + System.nanoTime()), 1, 32), new RouteAssetDataMirror(), new RouteAssetDependencyCatalog(), renderer, threads, "f".repeat(64), new RouteAssetMetrics());
	}

	private static RouteAssetDataMirror.Snapshot snapshot(String routeName) {
		final List<RouteAssetRenderSnapshot.Station> stations = List.of(
				new RouteAssetRenderSnapshot.Station(1, "One", false, true, false, false),
				new RouteAssetRenderSnapshot.Station(2, "Two", false, false, false, false)
		);
		final RouteAssetRenderSnapshot.Route route = new RouteAssetRenderSnapshot.Route(10, routeName, 0x14755E, stations);
		final RouteAssetDataMirror.PlatformSnapshot platform = new RouteAssetDataMirror.PlatformSnapshot(20, "Platform", List.of(route));
		final RouteAssetDataMirror.DimensionSnapshot dimension = new RouteAssetDataMirror.DimensionSnapshot("minecraft/overworld", 1, Map.of(20L, platform));
		return new RouteAssetDataMirror.Snapshot(1, Map.of("minecraft/overworld", dimension));
	}

	private static RouteAssetImage image(int value) {
		final RouteAssetImage image = new RouteAssetImage(2, 2);
		image.fillRect(0, 0, 2, 2, 0xFF000000 | value & 0xFFFFFF);
		return image;
	}
}
