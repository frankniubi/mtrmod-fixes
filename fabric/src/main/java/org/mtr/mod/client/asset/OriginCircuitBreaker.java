package org.mtr.mod.client.asset;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Small per-origin breaker used by the route-asset downloader.
 *
 * <p>The breaker deliberately knows nothing about HTTP or packet fallback. It
 * only answers whether a new origin request may start and records the result of
 * a request. Keeping that policy separate makes it deterministic to test and
 * prevents a transient CDN outage from turning every render frame into a
 * retry storm.</p>
 */
public final class OriginCircuitBreaker {

	static final long BASE_BACKOFF_MILLIS = 5_000;
	static final long MAX_BACKOFF_MILLIS = 300_000;
	static final int FAILURE_THRESHOLD = 3;

	private final Map<String, State> states = new HashMap<>();
	private final LongSupplier clock;
	private final DoubleSupplier jitter;

	public OriginCircuitBreaker() {
		this(System::currentTimeMillis, () -> 0.8D + ThreadLocalRandom.current().nextDouble() * 0.4D);
	}

	OriginCircuitBreaker(LongSupplier clock) {
		this(clock, () -> 0.8D + ThreadLocalRandom.current().nextDouble() * 0.4D);
	}

	OriginCircuitBreaker(LongSupplier clock, DoubleSupplier jitter) {
		this.clock = Objects.requireNonNull(clock, "clock");
		this.jitter = Objects.requireNonNull(jitter, "jitter");
	}

	/**
	 * Returns whether a request may start. Once the backoff expires exactly one
	 * caller is admitted as the half-open probe; concurrent callers remain
	 * blocked until that probe succeeds or fails.
	 */
	public synchronized boolean allow(String origin) {
		final State state = states.get(requireOrigin(origin));
		if (state == null || !state.open) return true;
		final long now = clock.getAsLong();
		if (now < state.openUntilMillis || state.probeInFlight) return false;
		state.probeInFlight = true;
		return true;
	}

	/** Records a successful HTTP request and closes/reset the origin circuit. */
	public synchronized void onSuccess(String origin) {
		states.remove(requireOrigin(origin));
	}

	/**
	 * Records a failed HTTP request. Three consecutive failures open the
	 * circuit; a failed half-open probe opens it immediately with the next
	 * exponential delay.
	 */
	public synchronized void onFailure(String origin) {
		final String key = requireOrigin(origin);
		final State state = states.computeIfAbsent(key, ignored -> new State());
		if (state.probeInFlight) {
			state.probeInFlight = false;
			open(state);
			return;
		}
		state.consecutiveFailures++;
		if (state.consecutiveFailures >= FAILURE_THRESHOLD) {
			state.consecutiveFailures = 0;
			open(state);
		}
	}

	/**
	 * HTTP 404 means the requested revision/object is absent, not that the
	 * origin is unhealthy. Do not add it to the failure streak and release a
	 * half-open probe if this was one.
	 */
	public synchronized void onNotFound(String origin) {
		states.remove(requireOrigin(origin));
	}

	/**
	 * Releases a half-open probe whose request was cancelled or otherwise
	 * abandoned before an HTTP result was available. Abandonment is not a
	 * health failure and must not extend the current backoff.
	 */
	public synchronized void onAbandoned(String origin) {
		final State state = states.get(requireOrigin(origin));
		if (state != null && state.probeInFlight) state.probeInFlight = false;
	}

	/**
	 * A server revision notice is an explicit freshness signal. Let the next
	 * request probe every known origin immediately instead of waiting for the
	 * current backoff to expire.
	 */
	public synchronized void onRevisionNotice() {
		final long now = clock.getAsLong();
		for (final State state : states.values()) {
			state.open = true;
			state.openUntilMillis = now;
			state.probeInFlight = false;
			state.consecutiveFailures = 0;
		}
	}

	/** Returns true when the origin is currently cooling down. */
	public synchronized boolean isOpen(String origin) {
		final State state = states.get(requireOrigin(origin));
		return state != null && state.open && !state.probeInFlight && clock.getAsLong() < state.openUntilMillis;
	}

	/** Visible for deterministic tests and operator diagnostics. */
	synchronized long retryAtMillis(String origin) {
		final State state = states.get(requireOrigin(origin));
		return state == null ? 0 : state.openUntilMillis;
	}

	/** Visible for deterministic tests. */
	synchronized int failureStreak(String origin) {
		final State state = states.get(requireOrigin(origin));
		return state == null ? 0 : state.consecutiveFailures;
	}

	private void open(State state) {
		state.open = true;
		state.probeInFlight = false;
		final int shift = Math.min(31, state.openCount++);
		final long base = Math.min(MAX_BACKOFF_MILLIS, BASE_BACKOFF_MILLIS << shift);
		final double factor = clampJitter(jitter.getAsDouble());
		final long delay = Math.max(1, Math.min(MAX_BACKOFF_MILLIS, Math.round(base * factor)));
		state.openUntilMillis = saturatingAdd(clock.getAsLong(), delay);
	}

	private static double clampJitter(double factor) {
		if (!Double.isFinite(factor)) return 1D;
		return Math.max(0.8D, Math.min(1.2D, factor));
	}

	private static long saturatingAdd(long value, long increment) {
		return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
	}

	private static String requireOrigin(String origin) {
		final String value = Objects.requireNonNull(origin, "origin").trim();
		if (value.isEmpty()) throw new IllegalArgumentException("Origin cannot be empty");
		return value;
	}

	private static final class State {
		private int consecutiveFailures;
		private int openCount;
		private long openUntilMillis;
		private boolean open;
		private boolean probeInFlight;
	}
}
