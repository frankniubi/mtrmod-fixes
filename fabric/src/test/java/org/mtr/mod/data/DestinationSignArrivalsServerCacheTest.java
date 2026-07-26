package org.mtr.mod.data;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class DestinationSignArrivalsServerCacheTest {

	@Test
	public void exactRoutePlatformPairsRemainSeparateAndEarliestWins() {
		final AtomicLong now = new AtomicLong(1_000);
		final FakeQuery query = new FakeQuery();
		final DestinationSignArrivalsServerCache cache = new DestinationSignArrivalsServerCache(now::get, query);
		final DestinationSignArrivalKey first = new DestinationSignArrivalKey(-1, -10);
		final DestinationSignArrivalKey second = new DestinationSignArrivalKey(-1, -20);
		final List<DestinationSignArrivalsServerCache.Response> responses = new ArrayList<>();
		cache.request(List.of(first, second), responses::add);
		query.complete(5_000, List.of(
				candidate(-1, -10, 9_000, "Late"),
				candidate(-1, -20, 7_000, "Second"),
				candidate(-1, -10, 6_000, "First")));
		Assertions.assertEquals(1, responses.size());
		Assertions.assertEquals(6_000, responses.get(0).getResults().get(first).getArrivalMillis());
		Assertions.assertEquals("Second", responses.get(0).getResults().get(second).getDestination());
	}

	@Test
	public void otherRoutesCannotStarveRequestedPairWithinBoundedCandidates() {
		final FakeQuery query = new FakeQuery();
		final DestinationSignArrivalsServerCache cache = new DestinationSignArrivalsServerCache(() -> 0, query);
		final DestinationSignArrivalKey requested = new DestinationSignArrivalKey(-99, -10);
		final List<DestinationSignArrivalsServerCache.Response> responses = new ArrayList<>();
		cache.request(List.of(requested), responses::add);
		final List<DestinationSignArrivalsServerCache.Candidate> candidates = new ArrayList<>();
		for (int index = 0; index < 100; index++) candidates.add(candidate(-1, -10, index, "Other"));
		candidates.add(candidate(-99, -10, 1_000, "Target"));
		query.complete(0, candidates);
		Assertions.assertTrue(responses.get(0).getResults().get(requested).isPresent());
	}

	@Test
	public void concurrentDuplicatesCoalesceAndCachedValuesExpireAfterOneSecond() {
		final AtomicLong now = new AtomicLong(10_000);
		final FakeQuery query = new FakeQuery();
		final DestinationSignArrivalsServerCache cache = new DestinationSignArrivalsServerCache(now::get, query);
		final DestinationSignArrivalKey key = new DestinationSignArrivalKey(-1, -10);
		final AtomicInteger completions = new AtomicInteger();
		cache.request(List.of(key), ignored -> completions.incrementAndGet());
		cache.request(List.of(key), ignored -> completions.incrementAndGet());
		Assertions.assertEquals(1, query.requests);
		query.complete(20_000, List.of(candidate(-1, -10, 30_000, "A")));
		Assertions.assertEquals(2, completions.get());

		now.addAndGet(999);
		cache.request(List.of(key), ignored -> completions.incrementAndGet());
		Assertions.assertEquals(1, query.requests);
		now.incrementAndGet();
		cache.request(List.of(key), ignored -> completions.incrementAndGet());
		Assertions.assertEquals(2, query.requests);
	}

	@Test
	public void differentRoutesOnTheSamePlatformJoinOneCoreQuery() {
		final FakeQuery query = new FakeQuery();
		final DestinationSignArrivalsServerCache cache = new DestinationSignArrivalsServerCache(() -> 0, query);
		final DestinationSignArrivalKey first = new DestinationSignArrivalKey(-1, -10);
		final DestinationSignArrivalKey second = new DestinationSignArrivalKey(-2, -10);
		final AtomicInteger completions = new AtomicInteger();
		cache.request(List.of(first), ignored -> completions.incrementAndGet());
		cache.request(List.of(second), ignored -> completions.incrementAndGet());
		Assertions.assertEquals(1, query.requests);
		query.complete(0, List.of(candidate(-1, -10, 1, "A"), candidate(-2, -10, 2, "B")));
		Assertions.assertEquals(2, completions.get());
	}

	@Test
	public void timedOutQueryReleasesWaitersAndCanBeRetried() {
		final AtomicLong now = new AtomicLong();
		final MultiQuery query = new MultiQuery();
		final DestinationSignArrivalsServerCache cache = new DestinationSignArrivalsServerCache(now::get, query);
		final DestinationSignArrivalKey key = new DestinationSignArrivalKey(-1, -10);
		final AtomicInteger completions = new AtomicInteger();
		cache.request(List.of(key), ignored -> completions.incrementAndGet());
		now.addAndGet(DestinationSignArrivalsServerCache.QUERY_TIMEOUT_MILLIS);
		cache.tick();
		Assertions.assertEquals(1, completions.get());
		now.addAndGet(1_000);
		cache.request(List.of(key), ignored -> completions.incrementAndGet());
		Assertions.assertEquals(2, query.callbacks.size());
		query.callbacks.get(0).accept(new DestinationSignArrivalsServerCache.QueryResponse(now.get(), List.of(candidate(-1, -10, 1, "stale"))));
		Assertions.assertEquals(1, completions.get(), "late generation must not complete the retry");
		query.callbacks.get(1).accept(new DestinationSignArrivalsServerCache.QueryResponse(now.get(), List.of(candidate(-1, -10, 2, "fresh"))));
		Assertions.assertEquals(2, completions.get());
	}

	@Test
	public void oneThrowingWaiterDoesNotBlockOtherWaiters() {
		final FakeQuery query = new FakeQuery();
		final DestinationSignArrivalsServerCache cache = new DestinationSignArrivalsServerCache(() -> 0, query);
		final DestinationSignArrivalKey key = new DestinationSignArrivalKey(-1, -10);
		final AtomicInteger completed = new AtomicInteger();
		cache.request(List.of(key), ignored -> { throw new IllegalStateException("test"); });
		cache.request(List.of(key), ignored -> completed.incrementAndGet());
		query.complete(0, List.of(candidate(-1, -10, 1, "A")));
		Assertions.assertEquals(1, completed.get());
	}

	@Test
	public void absentPairIsAuthoritativeOnlyWhenCandidateWindowWasNotExhausted() {
		final DestinationSignArrivalKey missing = new DestinationSignArrivalKey(-2, -10);
		final FakeQuery shortQuery = new FakeQuery();
		final DestinationSignArrivalsServerCache shortCache = new DestinationSignArrivalsServerCache(() -> 0, shortQuery);
		final List<DestinationSignArrivalsServerCache.Response> authoritative = new ArrayList<>();
		shortCache.request(List.of(missing), authoritative::add);
		shortQuery.complete(0, List.of(candidate(-1, -10, 1, "Other")));
		Assertions.assertTrue(authoritative.get(0).getResults().get(missing).isNoService());

		final FakeQuery fullQuery = new FakeQuery();
		final DestinationSignArrivalsServerCache fullCache = new DestinationSignArrivalsServerCache(() -> 0, fullQuery);
		final List<DestinationSignArrivalsServerCache.Response> transientResponse = new ArrayList<>();
		fullCache.request(List.of(missing), transientResponse::add);
		final List<DestinationSignArrivalsServerCache.Candidate> full = new ArrayList<>();
		for (int index = 0; index < DestinationSignArrivalsServerCache.MAX_CANDIDATES_PER_PLATFORM; index++) full.add(candidate(-1, -10, index, "Other"));
		fullQuery.complete(0, full);
		Assertions.assertFalse(transientResponse.get(0).getResults().containsKey(missing));
	}

	private static DestinationSignArrivalsServerCache.Candidate candidate(long route, long platform, long arrival, String destination) {
		return new DestinationSignArrivalsServerCache.Candidate(route, platform, arrival, destination, true);
	}

	private static final class FakeQuery implements DestinationSignArrivalsServerCache.Query {
		private int requests;
		private Consumer<DestinationSignArrivalsServerCache.QueryResponse> callback;

		@Override
		public void query(List<Long> platformIds, int maximumCandidatesPerPlatform, Consumer<DestinationSignArrivalsServerCache.QueryResponse> callback) {
			requests++;
			this.callback = callback;
		}

		private void complete(long responseTime, List<DestinationSignArrivalsServerCache.Candidate> candidates) {
			final Consumer<DestinationSignArrivalsServerCache.QueryResponse> current = callback;
			callback = null;
			current.accept(new DestinationSignArrivalsServerCache.QueryResponse(responseTime, candidates));
		}
	}

	private static final class MultiQuery implements DestinationSignArrivalsServerCache.Query {
		private final List<Consumer<DestinationSignArrivalsServerCache.QueryResponse>> callbacks = new ArrayList<>();
		@Override public void query(List<Long> platformIds, int maximumCandidatesPerPlatform, Consumer<DestinationSignArrivalsServerCache.QueryResponse> callback) { callbacks.add(callback); }
	}
}
