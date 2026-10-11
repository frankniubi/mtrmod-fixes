package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mtr.mod.packet.PacketRouteAssetRefresh;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RouteAssetRevisionNotificationTest {

	@TempDir
	Path root;

	@Test
	public void successfulPublicationNotifiesOnlyConnectedHelloClientsOnce() throws Exception {
		final List<Notification> notifications = new ArrayList<>();
		try (final RouteAssetServerManager manager = manager(new RouteAssetDataMirror(), (playerId, payload) -> notifications.add(new Notification(playerId, payload)), (key, snapshot) -> image(snapshot.getRouteName().hashCode()))) {
			final UUID client = UUID.randomUUID();
			Assertions.assertEquals(RouteAssetNegotiation.Mode.FALLBACK, manager.negotiate(client, hello("NORMAL", "", 7)).getMode(), "an empty HEAD is temporary local fallback, not a usable manifest");

			manager.submitSnapshot(snapshot("First"), "initial");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			final String firstRevision = manager.getRepository().loadHead().getRevision();
			Assertions.assertEquals(1, notifications.size());
			Assertions.assertEquals(client, notifications.get(0).playerId);
			Assertions.assertEquals(firstRevision, notifications.get(0).payload.getRevision());
			Assertions.assertEquals(7, notifications.get(0).payload.getConnectionNonce());

			manager.submitSnapshot(snapshot("First"), "unchanged");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			Assertions.assertEquals(1, notifications.size(), "an unchanged revision must not emit a duplicate notification");

			manager.onPlayerDisconnect(client);
			manager.submitSnapshot(snapshot("Second"), "after-disconnect");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			Assertions.assertEquals(1, notifications.size(), "disconnected clients must be forgotten before later publications");
		}
	}

	@Test
	public void languageNegotiationWaitsForThatLanguagePublication() throws Exception {
		final RouteAssetDataMirror mirror = new RouteAssetDataMirror();
		final RouteAssetDataMirror.Snapshot initial = snapshot("Language");
		final long mirrorGeneration = mirror.beginGeneration(initial.getDimensions().keySet());
		initial.getDimensions().forEach((dimension, value) -> Assertions.assertTrue(mirror.acceptDimension(mirrorGeneration, value)));
		final List<Notification> notifications = new ArrayList<>();
		try (final RouteAssetServerManager manager = manager(mirror, (playerId, payload) -> notifications.add(new Notification(playerId, payload)), (key, snapshot) -> image((snapshot.getRouteName() + key.getVariant().getLanguage()).hashCode()))) {
			manager.submitSnapshot(mirror.currentSnapshot(), "normal");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			final String normalRevision = manager.getRepository().loadHead().getRevision();

			final UUID client = UUID.randomUUID();
			Assertions.assertEquals(RouteAssetNegotiation.Mode.FALLBACK, manager.negotiate(client, hello("CJK", normalRevision, 11)).getMode(), "CJK must stay local until the CJK generation publishes");
			Assertions.assertTrue(manager.awaitIdle(10_000));
			Assertions.assertEquals(1, notifications.size());
			Assertions.assertEquals(11, notifications.get(0).payload.getConnectionNonce());

			final RouteAssetNegotiation negotiation = manager.negotiate(client, hello("CJK", normalRevision, 12));
			Assertions.assertNotEquals(RouteAssetNegotiation.Mode.FALLBACK, negotiation.getMode());
			Assertions.assertNotEquals(RouteAssetNegotiation.Mode.DISABLED, negotiation.getMode());
			Assertions.assertEquals(notifications.get(0).payload.getRevision(), negotiation.getAuthoritativeRevision());
		}
	}

	@Test
	public void staleWorkerCancellationIsNotCountedAsGenerationFailure() throws Exception {
		final CountDownLatch started = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final AtomicBoolean first = new AtomicBoolean(true);
		try (final RouteAssetServerManager manager = manager(new RouteAssetDataMirror(), (playerId, payload) -> { }, (key, snapshot) -> {
			if (first.compareAndSet(true, false)) {
				started.countDown();
				if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
			}
			return image(snapshot.getRouteName().hashCode());
		})) {
			manager.submitSnapshot(snapshot("Old"), "old");
			Assertions.assertTrue(started.await(10, TimeUnit.SECONDS));
			manager.submitSnapshot(snapshot("Current"), "current");
			release.countDown();
			Assertions.assertTrue(manager.awaitIdle(10_000));
			Assertions.assertTrue(manager.getMetrics().getCancelledGenerations() >= 1);
			Assertions.assertEquals(0, manager.getMetrics().getFailedGenerations(), "CancellationException wrapped by Future.get must remain cancellation, not failure");
		}
	}

	private RouteAssetServerManager manager(RouteAssetDataMirror mirror, RouteAssetServerManager.RefreshNotificationSender notificationSender, RouteAssetServerManager.RenderFunction renderer) throws Exception {
		return new RouteAssetServerManager(new RouteAssetRepository(root.resolve("manager-" + System.nanoTime()), RouteAssetProtocol.RENDERER_VERSION, 32), mirror, new RouteAssetDependencyCatalog(), renderer, 1, "f".repeat(64), new RouteAssetMetrics(), notificationSender);
	}

	private static RouteAssetHello hello(String language, String cachedRevision, long nonce) {
		return new RouteAssetHello(RouteAssetProtocol.PROTOCOL_VERSION, RouteAssetProtocol.RENDERER_VERSION, 2, language, "f".repeat(64), cachedRevision, true, false, RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, nonce);
	}

	private static RouteAssetDataMirror.Snapshot snapshot(String routeName) {
		final List<RouteAssetRenderSnapshot.Station> stations = List.of(new RouteAssetRenderSnapshot.Station(1, "One", false, true, false, false));
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

	private static final class Notification {
		private final UUID playerId;
		private final PacketRouteAssetRefresh.RefreshPayload payload;

		private Notification(UUID playerId, PacketRouteAssetRefresh.RefreshPayload payload) {
			this.playerId = playerId;
			this.payload = payload;
		}
	}
}
