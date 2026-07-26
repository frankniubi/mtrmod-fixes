package org.mtr.mod.data;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.packet.PacketFetchDestinationSignArrivals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class DestinationSignArrivalsClientCacheTest {

	@Test
	public void appliesResponseTimeOffsetOnceAndKeepsAuthoritativeResults() {
		final AtomicLong now = new AtomicLong(1_000);
		final List<PacketFetchDestinationSignArrivals.RequestPayload> sent = new ArrayList<>();
		final DestinationSignArrivalsClientCache cache = new DestinationSignArrivalsClientCache(now::get, sent::add);
		final DestinationSignArrivalKey key = new DestinationSignArrivalKey(-1, -10);
		cache.request(List.of(key));
		cache.tick();
		Assertions.assertEquals(1, sent.size());
		final long callback = sent.get(0).getCallbackId();
		cache.accept(new PacketFetchDestinationSignArrivals.ResponsePayload(callback, 5_000, Map.of(key, DestinationSignArrivalResult.present(8_000, "A|B", true))));
		now.set(2_000);
		final DestinationSignArrivalsClientCache.Snapshot snapshot = cache.request(List.of(key));
		Assertions.assertEquals(6_000, snapshot.getServerNowMillis());
		Assertions.assertEquals(8_000, snapshot.getResults().get(key).getArrivalMillis());
		Assertions.assertTrue(snapshot.getAuthoritativeKeys().contains(key));
	}

	@Test
	public void splitsSixHundredVisiblePairsWithoutStarvingTheTail() {
		final AtomicLong now = new AtomicLong();
		final List<PacketFetchDestinationSignArrivals.RequestPayload> sent = new ArrayList<>();
		final DestinationSignArrivalsClientCache cache = new DestinationSignArrivalsClientCache(now::get, sent::add);
		final List<DestinationSignArrivalKey> keys = new ArrayList<>();
		for (int index = 1; index <= 600; index++) keys.add(new DestinationSignArrivalKey(-index, -index));
		cache.request(keys);
		cache.tick();
		Assertions.assertEquals(512, sent.get(0).getKeys().size());
		now.addAndGet(DestinationSignArrivalsClientCache.REQUEST_INTERVAL_MILLIS);
		cache.tick();
		Assertions.assertEquals(88, sent.get(1).getKeys().size());
		final HashSet<DestinationSignArrivalKey> combined = new HashSet<>(sent.get(0).getKeys());
		combined.addAll(sent.get(1).getKeys());
		Assertions.assertEquals(new HashSet<>(keys), combined);
	}

	@Test
	public void transientMissingResponseDoesNotEraseDwellingTrain() {
		final AtomicLong now = new AtomicLong();
		final List<PacketFetchDestinationSignArrivals.RequestPayload> sent = new ArrayList<>();
		final DestinationSignArrivalsClientCache cache = new DestinationSignArrivalsClientCache(now::get, sent::add);
		final DestinationSignArrivalKey key = new DestinationSignArrivalKey(-1, -10);
		cache.request(List.of(key));
		cache.tick();
		cache.accept(new PacketFetchDestinationSignArrivals.ResponsePayload(sent.get(0).getCallbackId(), 0, Map.of(key, DestinationSignArrivalResult.present(-1, "A", true))));
		now.addAndGet(DestinationSignArrivalsClientCache.REQUEST_INTERVAL_MILLIS);
		cache.request(List.of(key));
		cache.tick();
		cache.accept(new PacketFetchDestinationSignArrivals.ResponsePayload(sent.get(1).getCallbackId(), now.get(), Map.of()));
		Assertions.assertTrue(cache.request(List.of(key)).getResults().get(key).isPresent());
	}

	@Test
	public void expiringInvisibleResultsAdvancesTheGeneration() {
		final AtomicLong now = new AtomicLong();
		final List<PacketFetchDestinationSignArrivals.RequestPayload> sent = new ArrayList<>();
		final DestinationSignArrivalsClientCache cache = new DestinationSignArrivalsClientCache(now::get, sent::add);
		final DestinationSignArrivalKey key = new DestinationSignArrivalKey(-1, -10);
		cache.request(List.of(key));
		cache.tick();
		cache.accept(new PacketFetchDestinationSignArrivals.ResponsePayload(sent.get(0).getCallbackId(), 0, Map.of(key, DestinationSignArrivalResult.present(1, "A", true))));
		final long generation = cache.request(List.of()).getGeneration();
		for (int index = 0; index < 10; index++) cache.tick();
		final DestinationSignArrivalsClientCache.Snapshot expired = cache.request(List.of());
		Assertions.assertTrue(expired.getResults().isEmpty());
		Assertions.assertTrue(expired.getGeneration() > generation);
	}

	@Test
	public void clearDropsCallbacksAndLateResponsesCannotRepopulateState() {
		final AtomicLong now = new AtomicLong();
		final List<PacketFetchDestinationSignArrivals.RequestPayload> sent = new ArrayList<>();
		final DestinationSignArrivalsClientCache cache = new DestinationSignArrivalsClientCache(now::get, sent::add);
		final DestinationSignArrivalKey key = new DestinationSignArrivalKey(-1, -10);
		cache.request(List.of(key));
		cache.tick();
		final long beforeClear = cache.request(List.of()).getGeneration();

		cache.clear();
		final long afterClear = cache.request(List.of()).getGeneration();
		cache.accept(new PacketFetchDestinationSignArrivals.ResponsePayload(sent.get(0).getCallbackId(), 5_000,
				Map.of(key, DestinationSignArrivalResult.present(8_000, "late", true))));

		final DestinationSignArrivalsClientCache.Snapshot snapshot = cache.request(List.of());
		Assertions.assertTrue(afterClear > beforeClear);
		Assertions.assertEquals(afterClear, snapshot.getGeneration());
		Assertions.assertTrue(snapshot.getResults().isEmpty());
		Assertions.assertTrue(snapshot.getAuthoritativeKeys().isEmpty());
	}
}
