package org.mtr.mod.data;

import org.mtr.mod.InitClient;
import org.mtr.mod.client.DestinationSignDynamicTextCache;
import org.mtr.mod.packet.PacketFetchDestinationSignArrivals;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

public final class DestinationSignArrivalsClientCache {

	public static final long REQUEST_INTERVAL_MILLIS = 3_000;
	private static final long CALLBACK_EXPIRY_MILLIS = 15_000;
	private static final int PERSISTENT_AGE = 5;
	private static final int MAX_CALLBACKS = 32;
	private static final int MAX_VISIBLE_KEYS = 4_096;

	public static final DestinationSignArrivalsClientCache INSTANCE = new DestinationSignArrivalsClientCache(System::currentTimeMillis,
			payload -> InitClient.REGISTRY_CLIENT.sendPacketToServer(new PacketFetchDestinationSignArrivals(payload)));

	private final LongSupplier clock;
	private final Consumer<PacketFetchDestinationSignArrivals.RequestPayload> sender;
	private final LinkedHashMap<DestinationSignArrivalKey, Integer> visibleAges = new LinkedHashMap<>();
	private final LinkedHashSet<DestinationSignArrivalKey> unsent = new LinkedHashSet<>();
	private final Map<DestinationSignArrivalKey, Long> lastSentMillis = new HashMap<>();
	private final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> results = new TreeMap<>();
	private final Set<DestinationSignArrivalKey> authoritativeKeys = new TreeSet<>();
	private final Map<Long, PendingRequest> callbacks = new LinkedHashMap<>();
	private final AtomicLong callbackSequence = new AtomicLong();
	private long nextRequestMillis;
	private long serverMillisOffset;

	public DestinationSignArrivalsClientCache(LongSupplier clock, Consumer<PacketFetchDestinationSignArrivals.RequestPayload> sender) {
		this.clock = clock;
		this.sender = sender;
	}

	public synchronized Snapshot request(Collection<DestinationSignArrivalKey> keys) {
		final long now = clock.getAsLong();
		for (final DestinationSignArrivalKey key : new TreeSet<>(keys)) {
			if (!visibleAges.containsKey(key) && visibleAges.size() >= MAX_VISIBLE_KEYS) break;
			visibleAges.put(key, 0);
			final Long lastSent = lastSentMillis.get(key);
			if (lastSent == null || now - lastSent >= REQUEST_INTERVAL_MILLIS) unsent.add(key);
		}
		return snapshot(now);
	}

	public void tick() {
		final PacketFetchDestinationSignArrivals.RequestPayload payload;
		final long now = clock.getAsLong();
		synchronized (this) {
			callbacks.entrySet().removeIf(entry -> now - entry.getValue().createdMillis >= CALLBACK_EXPIRY_MILLIS);
			ageVisible();
			if (now < nextRequestMillis || unsent.isEmpty() || callbacks.size() >= MAX_CALLBACKS) return;
			final List<DestinationSignArrivalKey> batch = new ArrayList<>(PacketFetchDestinationSignArrivals.MAX_KEYS);
			final Iterator<DestinationSignArrivalKey> iterator = unsent.iterator();
			while (iterator.hasNext() && batch.size() < PacketFetchDestinationSignArrivals.MAX_KEYS) {
				final DestinationSignArrivalKey key = iterator.next();
				iterator.remove();
				if (visibleAges.containsKey(key)) {
					batch.add(key);
					lastSentMillis.put(key, now);
				}
			}
			if (batch.isEmpty()) return;
			long callbackId = callbackSequence.incrementAndGet();
			if (callbackId == 0) callbackId = callbackSequence.incrementAndGet();
			payload = new PacketFetchDestinationSignArrivals.RequestPayload(callbackId, batch);
			callbacks.put(callbackId, new PendingRequest(now, new TreeSet<>(batch)));
			nextRequestMillis = now + REQUEST_INTERVAL_MILLIS;
		}
		sender.accept(payload);
	}

	public synchronized void accept(PacketFetchDestinationSignArrivals.ResponsePayload payload) {
		final PendingRequest pending = callbacks.remove(payload.getCallbackId());
		if (pending == null) return;
		serverMillisOffset = payload.getResponseTimeMillis() - clock.getAsLong();
		payload.getResults().forEach((key, result) -> {
			if (!pending.keys.contains(key)) return;
			results.put(key, result);
			authoritativeKeys.add(key);
			if (result.isPresent()) DestinationSignDynamicTextCache.INSTANCE.prepare(result.getDestination());
		});
	}

	public synchronized void clear() {
		visibleAges.clear(); unsent.clear(); lastSentMillis.clear(); results.clear(); authoritativeKeys.clear(); callbacks.clear();
		nextRequestMillis = 0; serverMillisOffset = 0;
	}

	private void ageVisible() {
		final List<DestinationSignArrivalKey> remove = new ArrayList<>();
		visibleAges.replaceAll((key, age) -> {
			if (age >= PERSISTENT_AGE) remove.add(key);
			return age + 1;
		});
		remove.forEach(key -> {
			visibleAges.remove(key);
			unsent.remove(key);
			lastSentMillis.remove(key);
			results.remove(key);
			authoritativeKeys.remove(key);
		});
	}

	private Snapshot snapshot(long now) {
		return new Snapshot(saturatingAdd(now, serverMillisOffset), results, authoritativeKeys);
	}

	public static final class Snapshot {
		private final long serverNowMillis;
		private final Map<DestinationSignArrivalKey, DestinationSignArrivalResult> results;
		private final Set<DestinationSignArrivalKey> authoritativeKeys;
		private Snapshot(long serverNowMillis, Map<DestinationSignArrivalKey, DestinationSignArrivalResult> results, Set<DestinationSignArrivalKey> authoritativeKeys) {
			this.serverNowMillis = serverNowMillis;
			this.results = Collections.unmodifiableMap(new TreeMap<>(results));
			this.authoritativeKeys = Collections.unmodifiableSet(new TreeSet<>(authoritativeKeys));
		}
		public long getServerNowMillis() { return serverNowMillis; }
		public Map<DestinationSignArrivalKey, DestinationSignArrivalResult> getResults() { return results; }
		public Set<DestinationSignArrivalKey> getAuthoritativeKeys() { return authoritativeKeys; }
	}

	private static final class PendingRequest {
		private final long createdMillis;
		private final Set<DestinationSignArrivalKey> keys;
		private PendingRequest(long createdMillis, Set<DestinationSignArrivalKey> keys) { this.createdMillis = createdMillis; this.keys = keys; }
	}

	private static long saturatingAdd(long first, long second) {
		try { return Math.addExact(first, second); } catch (ArithmeticException ignored) { return second < 0 ? Long.MIN_VALUE : Long.MAX_VALUE; }
	}
}
