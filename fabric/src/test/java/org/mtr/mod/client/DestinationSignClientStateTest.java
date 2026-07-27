package org.mtr.mod.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.route.DestinationSignAssetSnapshot;
import org.mtr.mod.route.DestinationSignDirectServiceModel;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.DestinationSignTopology;
import org.mtr.mod.route.RouteAssetCanonicalKeyFactory;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.data.DestinationSignArrivalKey;
import org.mtr.mod.data.DestinationSignArrivalResult;
import org.mtr.mod.data.DestinationSignRows;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

public final class DestinationSignClientStateTest {

	@Test
	public void preparesOncePerAnchorAndCanonicalKey() {
		final MutableClock clock = new MutableClock();
		final AtomicInteger resolves = new AtomicInteger();
		final DestinationSignAssetSnapshot snapshot = snapshot();
		final DestinationSignClientState state = state(clock, key -> {
			resolves.incrementAndGet();
			return Optional.of(snapshot);
		});
		final RouteAssetKey key = key();

		final DestinationSignClientState.Prepared first = state.request(42, key).orElseThrow();
		final DestinationSignClientState.Prepared second = state.request(42, key).orElseThrow();

		Assertions.assertSame(first, second);
		Assertions.assertEquals(key, first.getStaticKey());
		Assertions.assertEquals(snapshot.getLayout(), first.getLayout());
		Assertions.assertEquals(1, resolves.get());
	}

	@Test
	public void routeInvalidationRepreparesButArrivalChangesDoNotParticipateInTheKey() {
		final MutableClock clock = new MutableClock();
		final AtomicInteger resolves = new AtomicInteger();
		final DestinationSignClientState state = state(clock, key -> {
			resolves.incrementAndGet();
			return Optional.of(snapshot());
		});
		final RouteAssetKey key = key();

		state.request(7, key).orElseThrow();
		state.request(7, key).orElseThrow();
		Assertions.assertEquals(1, resolves.get());

		state.invalidateRouteData();
		state.request(7, key).orElseThrow();
		Assertions.assertEquals(2, resolves.get());
		Assertions.assertEquals(key, state.request(7, key).orElseThrow().getStaticKey());
	}

	@Test
	public void boundsAnchorsAndExpiresIdleEntries() {
		final MutableClock clock = new MutableClock();
		final AtomicInteger resolves = new AtomicInteger();
		final DestinationSignClientState state = state(clock, key -> {
			resolves.incrementAndGet();
			return Optional.of(snapshot());
		});
		final RouteAssetKey key = key();

		for (int anchor = 0; anchor <= DestinationSignClientState.MAX_ANCHORS; anchor++) state.request(anchor, key);
		Assertions.assertEquals(DestinationSignClientState.MAX_ANCHORS, state.size());

		clock.value = DestinationSignClientState.EXPIRY_MILLIS + 1;
		state.tick();
		Assertions.assertEquals(0, state.size());
	}

	@Test
	public void failedPreparationHasOneBoundedRetryWindow() {
		final MutableClock clock = new MutableClock();
		final AtomicInteger resolves = new AtomicInteger();
		final DestinationSignClientState state = state(clock, key -> {
			resolves.incrementAndGet();
			return Optional.empty();
		});

		Assertions.assertTrue(state.request(1, key()).isEmpty());
		Assertions.assertTrue(state.request(1, key()).isEmpty());
		Assertions.assertEquals(1, resolves.get());
		clock.value = DestinationSignClientState.RETRY_MILLIS;
		state.request(1, key());
		Assertions.assertEquals(2, resolves.get());
	}

	@Test
	public void pageCyclesIncludeSelectedStationLabelsAndIgnorePhysicalTerminal() {
		final DestinationSignAssetSnapshot snapshot = snapshotWithThreePhaseIntermediate();
		final DestinationSignClientState.Prepared prepared = state(new MutableClock(), ignored -> Optional.of(snapshot)).request(1, key()).orElseThrow();
		final DestinationSignDirectServiceModel.Option option = snapshot.getModel().getOptions().get(0);
		final DestinationSignArrivalKey arrivalKey = new DestinationSignArrivalKey(option.getRoute().getRouteId(), option.getSource().getPlatformId());
		final DestinationSignRows.Snapshot first = DestinationSignRows.resolve(snapshot.getModel(),
				java.util.Map.of(arrivalKey, DestinationSignArrivalResult.present(60_000, "Terminal A", true)), true, 0, true, DestinationSignStyle.ARRIVAL_ORDER);
		final DestinationSignRows.Snapshot second = DestinationSignRows.resolve(snapshot.getModel(),
				java.util.Map.of(arrivalKey, DestinationSignArrivalResult.present(60_000, "Other|Terminal|Value|Ignored", true)), true, 0, true, DestinationSignStyle.ARRIVAL_ORDER);
		Assertions.assertEquals(List.of(3), DestinationSignClientState.buildRenderRows(prepared, first).getLanguageCyclesByPage());
		Assertions.assertEquals(List.of(3), DestinationSignClientState.buildRenderRows(prepared, second).getLanguageCyclesByPage());
	}

	private static DestinationSignClientState state(LongSupplier clock, DestinationSignClientState.Resolver resolver) {
		final Executor direct = Runnable::run;
		return new DestinationSignClientState(clock, direct, direct, resolver);
	}

	private static RouteAssetKey key() {
		return RouteAssetCanonicalKeyFactory.destinationSign("minecraft/overworld", -100, -200, 1, DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
	}

	private static DestinationSignAssetSnapshot snapshot() {
		final DestinationSignTopology topology = new DestinationSignTopology(List.of(
				new DestinationSignTopology.ServiceRoute(-1, 0, "IG5|Intercity 5", 0x008A72, List.of(
						new DestinationSignTopology.StopOccurrence(-10, -100, "U1", "Source", ""),
						new DestinationSignTopology.StopOccurrence(-20, -200, "D", "Target", "")))
		), List.of(new DestinationSignTopology.StationZone(-100, "Source|Source EN"), new DestinationSignTopology.StationZone(-200, "Target|Target EN")));
		return DestinationSignAssetSnapshot.create(topology, -100, -200, DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
	}

	private static DestinationSignAssetSnapshot snapshotWithThreePhaseIntermediate() {
		final DestinationSignTopology topology = new DestinationSignTopology(List.of(
				new DestinationSignTopology.ServiceRoute(-1, 0, "R", 0x008A72, List.of(
						new DestinationSignTopology.StopOccurrence(-10, -100, "U1", "Source", ""),
						new DestinationSignTopology.StopOccurrence(-15, -150, "U2", "Middle|Middle EN|Milieu", ""),
						new DestinationSignTopology.StopOccurrence(-20, -200, "D", "Target", "")))
		), List.of(new DestinationSignTopology.StationZone(-100, "Source"), new DestinationSignTopology.StationZone(-150, "Middle|Middle EN|Milieu"),
				new DestinationSignTopology.StationZone(-200, "Target")));
		return DestinationSignAssetSnapshot.create(topology, -100, -200, DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
	}

	private static final class MutableClock implements LongSupplier {
		private long value;
		@Override public long getAsLong() { return value; }
	}
}
