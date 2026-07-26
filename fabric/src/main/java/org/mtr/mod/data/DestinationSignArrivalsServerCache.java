package org.mtr.mod.data;

import org.mtr.core.operation.ArrivalsRequest;
import org.mtr.core.operation.ArrivalsResponse;
import org.mtr.core.servlet.OperationProcessor;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongImmutableList;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mapping.holder.World;
import org.mtr.mapping.mapper.MinecraftServerHelper;
import org.mtr.mod.Init;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class DestinationSignArrivalsServerCache implements AutoCloseable {

	public static final int MAX_KEYS = 512;
	public static final int MAX_CANDIDATES_PER_PLATFORM = 128;
	public static final long CACHE_MILLIS = 1_000;
	public static final long QUERY_TIMEOUT_MILLIS = 5_000;

	private static final int MAX_ADAPTIVE_CANDIDATES_PER_PLATFORM = 512;
	private static final int MAX_PLATFORMS_PER_CORE_QUERY = 16;
	private static final int MAX_TOTAL_CORE_CANDIDATES = 8_192;
	private static final int MAX_CACHE_ENTRIES = 8_192;
	private static final int MAX_IN_FLIGHT_KEYS = 2_048;
	private static final int MAX_IN_FLIGHT_PLATFORMS = 256;
	private static final long TRANSIENT_BACKOFF_MILLIS = 1_000;

	private static final Map<String, DestinationSignArrivalsServerCache> INSTANCES = new HashMap<>();

	private final LongSupplier clock;
	private final Query query;
	private final LinkedHashMap<DestinationSignArrivalKey, CacheEntry> cached = new LinkedHashMap<>(16, 0.75F, true);
	private final Map<DestinationSignArrivalKey, List<Pending>> waitersByKey = new HashMap<>();
	private final Map<Long, PlatformFlight> flightsByPlatform = new HashMap<>();
	private final Map<Long, Integer> nextLimitByPlatform = new HashMap<>();
	private final Map<DestinationSignArrivalKey, Long> unavailableUntil = new HashMap<>();
	private long serverMillisOffset;
	private long flightSequence;
	private boolean closed;

	public DestinationSignArrivalsServerCache(LongSupplier clock, Query query) {
		this.clock = clock;
		this.query = query;
	}

	public void request(Collection<DestinationSignArrivalKey> requestedKeys, Consumer<Response> callback) {
		final TreeSet<DestinationSignArrivalKey> keys = checkedKeys(requestedKeys);
		final Pending pending = new Pending(callback);
		final List<QueryBatch> launches = new ArrayList<>();
		final long now = clock.getAsLong();
		synchronized (this) {
			expireCache(now);
			if (!closed) {
				final Map<Integer, List<Long>> platformsByLimit = new TreeMap<>();
				for (final DestinationSignArrivalKey key : keys) {
					final CacheEntry entry = cached.get(key);
					if (entry != null) {
						pending.results.put(key, entry.result);
						continue;
					}
					if (unavailableUntil.getOrDefault(key, Long.MIN_VALUE) > now) continue;
					List<Pending> keyWaiters = waitersByKey.get(key);
					if (keyWaiters == null) {
						if (waitersByKey.size() >= MAX_IN_FLIGHT_KEYS) continue;
						PlatformFlight flight = flightsByPlatform.get(key.getPlatformId());
						if (flight == null) {
							if (flightsByPlatform.size() >= MAX_IN_FLIGHT_PLATFORMS) continue;
							final int limit = nextLimitByPlatform.getOrDefault(key.getPlatformId(), MAX_CANDIDATES_PER_PLATFORM);
							flight = new PlatformFlight(++flightSequence, now, limit);
							flightsByPlatform.put(key.getPlatformId(), flight);
							platformsByLimit.computeIfAbsent(limit, ignored -> new ArrayList<>()).add(key.getPlatformId());
						}
						keyWaiters = new ArrayList<>();
						waitersByKey.put(key, keyWaiters);
					}
					pending.remaining++;
					keyWaiters.add(pending);
				}
				platformsByLimit.forEach((limit, platforms) -> {
					for (int start = 0; start < platforms.size(); start += MAX_PLATFORMS_PER_CORE_QUERY) {
						final List<Long> batchPlatforms = List.copyOf(platforms.subList(start, Math.min(platforms.size(), start + MAX_PLATFORMS_PER_CORE_QUERY)));
						final Map<Long, Long> generations = new HashMap<>();
						batchPlatforms.forEach(platform -> generations.put(platform, flightsByPlatform.get(platform).generation));
						launches.add(new QueryBatch(batchPlatforms, generations, limit));
					}
				});
			}
		}
		if (pending.remaining == 0) deliverSafely(pending.callback, new Response(saturatingAdd(now, serverMillisOffset), pending.results));
		for (final QueryBatch batch : launches) {
			try {
				query.query(batch.platforms, batch.limit, response -> complete(batch, response));
			} catch (RuntimeException exception) {
				fail(batch, clock.getAsLong());
			}
		}
	}

	public void tick() {
		final List<Completion> completions = new ArrayList<>();
		final long now = clock.getAsLong();
		synchronized (this) {
			expireCache(now);
			final List<QueryBatch> expired = new ArrayList<>();
			flightsByPlatform.forEach((platform, flight) -> {
				if (now - flight.startedMillis >= QUERY_TIMEOUT_MILLIS) expired.add(new QueryBatch(List.of(platform), Map.of(platform, flight.generation), flight.limit));
			});
			expired.forEach(batch -> collectFailure(batch, now, completions));
		}
		deliverAll(completions);
	}

	private void complete(QueryBatch batch, QueryResponse response) {
		final List<Completion> completions = new ArrayList<>();
		final long now = clock.getAsLong();
		synchronized (this) {
			if (closed) return;
			final Set<Long> validPlatforms = validPlatforms(batch);
			if (validPlatforms.isEmpty()) return;
			final Set<DestinationSignArrivalKey> requested = new TreeSet<>();
			waitersByKey.keySet().forEach(key -> { if (validPlatforms.contains(key.getPlatformId())) requested.add(key); });
			final Map<Long, Integer> candidateCounts = new HashMap<>();
			final Map<DestinationSignArrivalKey, Candidate> earliest = new HashMap<>();
			for (final Candidate candidate : response.candidates) {
				if (!validPlatforms.contains(candidate.platformId)) continue;
				candidateCounts.merge(candidate.platformId, 1, Integer::sum);
				final DestinationSignArrivalKey candidateKey;
				try { candidateKey = new DestinationSignArrivalKey(candidate.routeId, candidate.platformId); }
				catch (IllegalArgumentException ignored) { continue; }
				if (requested.contains(candidateKey)) earliest.merge(candidateKey, candidate, (first, second) -> first.arrivalMillis <= second.arrivalMillis ? first : second);
			}
			serverMillisOffset = response.responseTimeMillis - now;
			for (final long platform : validPlatforms) {
				final int candidateCount = candidateCounts.getOrDefault(platform, 0);
				final boolean exhausted = candidateCount >= batch.limit;
				if (exhausted) nextLimitByPlatform.put(platform, Math.min(MAX_ADAPTIVE_CANDIDATES_PER_PLATFORM, batch.limit * 2));
				else nextLimitByPlatform.remove(platform);
				final List<DestinationSignArrivalKey> platformKeys = new ArrayList<>();
				requested.forEach(key -> { if (key.getPlatformId() == platform) platformKeys.add(key); });
				for (final DestinationSignArrivalKey key : platformKeys) {
					final Candidate candidate = earliest.get(key);
					final DestinationSignArrivalResult result = candidate != null
							? DestinationSignArrivalResult.present(candidate.arrivalMillis, candidate.destination, candidate.realtime)
							: exhausted ? null : DestinationSignArrivalResult.noService();
					if (result != null) putCache(key, new CacheEntry(now, result));
					else unavailableUntil.put(key, now + TRANSIENT_BACKOFF_MILLIS);
					collectKeyCompletion(key, result, response.responseTimeMillis, completions);
				}
				flightsByPlatform.remove(platform);
			}
		}
		deliverAll(completions);
	}

	private void fail(QueryBatch batch, long now) {
		final List<Completion> completions = new ArrayList<>();
		synchronized (this) { collectFailure(batch, now, completions); }
		deliverAll(completions);
	}

	private void collectFailure(QueryBatch batch, long now, List<Completion> completions) {
		for (final long platform : validPlatforms(batch)) {
			final List<DestinationSignArrivalKey> keys = new ArrayList<>();
			waitersByKey.keySet().forEach(key -> { if (key.getPlatformId() == platform) keys.add(key); });
			keys.forEach(key -> {
				unavailableUntil.put(key, now + TRANSIENT_BACKOFF_MILLIS);
				collectKeyCompletion(key, null, saturatingAdd(now, serverMillisOffset), completions);
			});
			flightsByPlatform.remove(platform);
		}
	}

	private Set<Long> validPlatforms(QueryBatch batch) {
		final Set<Long> result = new TreeSet<>();
		batch.platforms.forEach(platform -> {
			final PlatformFlight flight = flightsByPlatform.get(platform);
			if (flight != null && flight.generation == batch.generations.getOrDefault(platform, Long.MIN_VALUE)) result.add(platform);
		});
		return result;
	}

	private void collectKeyCompletion(DestinationSignArrivalKey key, DestinationSignArrivalResult result, long responseTime, List<Completion> completions) {
		final List<Pending> waiters = waitersByKey.remove(key);
		if (waiters != null) waiters.forEach(waiter -> completions.add(new Completion(waiter, key, result, responseTime)));
	}

	private void putCache(DestinationSignArrivalKey key, CacheEntry entry) {
		cached.put(key, entry);
		while (cached.size() > MAX_CACHE_ENTRIES) cached.remove(cached.keySet().iterator().next());
	}

	private void expireCache(long now) {
		cached.entrySet().removeIf(entry -> now - entry.getValue().createdMillis >= CACHE_MILLIS);
		unavailableUntil.entrySet().removeIf(entry -> entry.getValue() <= now);
	}

	@Override
	public void close() {
		final List<Completion> completions = new ArrayList<>();
		final long now = clock.getAsLong();
		synchronized (this) {
			if (closed) return;
			closed = true;
			final List<QueryBatch> flights = new ArrayList<>();
			flightsByPlatform.forEach((platform, flight) -> flights.add(new QueryBatch(List.of(platform), Map.of(platform, flight.generation), flight.limit)));
			flights.forEach(batch -> collectFailure(batch, now, completions));
			cached.clear(); unavailableUntil.clear(); nextLimitByPlatform.clear();
		}
		deliverAll(completions);
	}

	private static TreeSet<DestinationSignArrivalKey> checkedKeys(Collection<DestinationSignArrivalKey> requestedKeys) {
		if (requestedKeys == null || requestedKeys.size() > MAX_KEYS) throw new IllegalArgumentException("Too many destination sign arrival keys");
		final TreeSet<DestinationSignArrivalKey> keys = new TreeSet<>();
		for (final DestinationSignArrivalKey key : requestedKeys) if (key == null || !keys.add(key)) throw new IllegalArgumentException("Duplicate destination sign arrival key");
		return keys;
	}

	private static void deliverAll(List<Completion> completions) { completions.forEach(Completion::deliver); }
	private static void deliverSafely(Consumer<Response> callback, Response response) { try { callback.accept(response); } catch (RuntimeException ignored) { } }

	public static synchronized DestinationSignArrivalsServerCache getInstance(ServerWorld serverWorld) {
		final World world = new World(serverWorld.data);
		final String id = MinecraftServerHelper.getWorldId(world).data.toString();
		return INSTANCES.computeIfAbsent(id, ignored -> new DestinationSignArrivalsServerCache(System::currentTimeMillis, (platformIds, maximum, callback) -> {
			final LongAVLTreeSet ids = new LongAVLTreeSet(platformIds);
			final int total = Math.min(MAX_TOTAL_CORE_CANDIDATES, Math.multiplyExact(maximum, platformIds.size()));
			Init.sendMessageC2S(OperationProcessor.ARRIVALS, world.getServer(), world, new ArrivalsRequest(new LongImmutableList(ids), maximum, total), response -> {
				final List<Candidate> candidates = new ArrayList<>();
				response.getArrivals().forEach(arrival -> candidates.add(new Candidate(arrival.getRouteId(), arrival.getPlatformId(), arrival.getArrival(), arrival.getDestination(), arrival.getRealtime())));
				callback.accept(new QueryResponse(response.getCurrentTime(), candidates));
			}, ArrivalsResponse.class);
		}));
	}

	public static synchronized void tickAll() { INSTANCES.values().forEach(DestinationSignArrivalsServerCache::tick); }
	public static synchronized void clearAll() { INSTANCES.values().forEach(DestinationSignArrivalsServerCache::close); INSTANCES.clear(); }

	public interface Query { void query(List<Long> platformIds, int maximumCandidatesPerPlatform, Consumer<QueryResponse> callback); }

	public static final class Candidate {
		private final long routeId, platformId, arrivalMillis;
		private final String destination;
		private final boolean realtime;
		public Candidate(long routeId, long platformId, long arrivalMillis, String destination, boolean realtime) {
			this.routeId = routeId; this.platformId = platformId; this.arrivalMillis = arrivalMillis; this.destination = destination == null ? "" : destination; this.realtime = realtime;
		}
	}

	public static final class QueryResponse {
		private final long responseTimeMillis;
		private final List<Candidate> candidates;
		public QueryResponse(long responseTimeMillis, List<Candidate> candidates) { this.responseTimeMillis = responseTimeMillis; this.candidates = List.copyOf(candidates); }
	}

	public static final class Response {
		private final long responseTimeMillis;
		private final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> results;
		private Response(long responseTimeMillis, Map<DestinationSignArrivalKey, DestinationSignArrivalResult> results) { this.responseTimeMillis = responseTimeMillis; this.results = Collections.unmodifiableMap(new TreeMap<>(results)); }
		public long getResponseTimeMillis() { return responseTimeMillis; }
		public Map<DestinationSignArrivalKey, DestinationSignArrivalResult> getResults() { return results; }
	}

	private static final class CacheEntry {
		private final long createdMillis;
		private final DestinationSignArrivalResult result;
		private CacheEntry(long createdMillis, DestinationSignArrivalResult result) { this.createdMillis = createdMillis; this.result = result; }
	}

	private static final class PlatformFlight {
		private final long generation, startedMillis;
		private final int limit;
		private PlatformFlight(long generation, long startedMillis, int limit) { this.generation = generation; this.startedMillis = startedMillis; this.limit = limit; }
	}

	private static final class QueryBatch {
		private final List<Long> platforms;
		private final Map<Long, Long> generations;
		private final int limit;
		private QueryBatch(List<Long> platforms, Map<Long, Long> generations, int limit) { this.platforms = platforms; this.generations = generations; this.limit = limit; }
	}

	private static final class Pending {
		private final Consumer<Response> callback;
		private final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> results = new LinkedHashMap<>();
		private int remaining;
		private Pending(Consumer<Response> callback) { this.callback = callback; }
	}

	private static final class Completion {
		private final Pending pending;
		private final DestinationSignArrivalKey key;
		private final DestinationSignArrivalResult result;
		private final long responseTime;
		private Completion(Pending pending, DestinationSignArrivalKey key, DestinationSignArrivalResult result, long responseTime) { this.pending = pending; this.key = key; this.result = result; this.responseTime = responseTime; }
		private void deliver() {
			final Response response;
			synchronized (pending) {
				if (result != null) pending.results.put(key, result);
				pending.remaining--;
				response = pending.remaining == 0 ? new Response(responseTime, pending.results) : null;
			}
			if (response != null) deliverSafely(pending.callback, response);
		}
	}

	private static long saturatingAdd(long first, long second) { try { return Math.addExact(first, second); } catch (ArithmeticException ignored) { return second < 0 ? Long.MIN_VALUE : Long.MAX_VALUE; } }
}
