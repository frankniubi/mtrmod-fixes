package org.mtr.mod.client.asset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.HashSet;
import java.util.Set;

public final class OriginCircuitBreakerTest {

	private static final String ORIGIN = "https://cdn.example:443";

	@Test
	public void opensAfterThreeFailuresAndAdmitsOnlyOneHalfOpenProbe() {
		final AtomicLong now = new AtomicLong(1_000);
		final OriginCircuitBreaker breaker = new OriginCircuitBreaker(now::get, () -> 1D);

		for (int i = 0; i < 3; i++) {
			Assertions.assertTrue(breaker.allow(ORIGIN));
			breaker.onFailure(ORIGIN);
		}
		Assertions.assertFalse(breaker.allow(ORIGIN));
		Assertions.assertEquals(6_000, breaker.retryAtMillis(ORIGIN));

		now.set(6_000);
		Assertions.assertTrue(breaker.allow(ORIGIN));
		Assertions.assertFalse(breaker.allow(ORIGIN));
		breaker.onSuccess(ORIGIN);
		Assertions.assertTrue(breaker.allow(ORIGIN));
	}

	@Test
	public void backoffDoublesAndIsCapped() {
		final AtomicLong now = new AtomicLong();
		final OriginCircuitBreaker breaker = new OriginCircuitBreaker(now::get, () -> 1D);

		Assertions.assertEquals(5_000, tripAndReadRetry(breaker, now));
		now.set(5_000);
		Assertions.assertTrue(breaker.allow(ORIGIN));
		breaker.onFailure(ORIGIN);
		Assertions.assertEquals(15_000, breaker.retryAtMillis(ORIGIN));

		now.set(15_000);
		Assertions.assertTrue(breaker.allow(ORIGIN));
		breaker.onFailure(ORIGIN);
		Assertions.assertEquals(35_000, breaker.retryAtMillis(ORIGIN));

		// Continue probing until the five-minute cap is reached.
		long expected = 35_000;
		for (int i = 0; i < 16; i++) {
			now.set(expected);
			Assertions.assertTrue(breaker.allow(ORIGIN));
			breaker.onFailure(ORIGIN);
			expected = breaker.retryAtMillis(ORIGIN);
		}
		Assertions.assertEquals(now.get() + OriginCircuitBreaker.MAX_BACKOFF_MILLIS, expected);
	}

	@Test
	public void jitterIsClampedToTwentyPercent() {
		final AtomicLong now = new AtomicLong();
		final OriginCircuitBreaker upper = new OriginCircuitBreaker(now::get, () -> 2D);
		Assertions.assertEquals(6_000, tripAndReadRetry(upper, now));

		final AtomicLong lowerClock = new AtomicLong();
		final OriginCircuitBreaker lower = new OriginCircuitBreaker(lowerClock::get, () -> 0D);
		Assertions.assertEquals(4_000, tripAndReadRetry(lower, lowerClock));
	}

	@Test
	public void notFoundDoesNotCreateFailureStreak() {
		final AtomicLong now = new AtomicLong();
		final OriginCircuitBreaker breaker = new OriginCircuitBreaker(now::get, () -> 1D);

		Assertions.assertTrue(breaker.allow(ORIGIN));
		breaker.onNotFound(ORIGIN);
		Assertions.assertEquals(0, breaker.failureStreak(ORIGIN));
		Assertions.assertTrue(breaker.allow(ORIGIN));
	}

	@Test
	public void revisionNoticeMakesAnOpenCircuitImmediatelyHalfOpen() {
		final AtomicLong now = new AtomicLong();
		final OriginCircuitBreaker breaker = new OriginCircuitBreaker(now::get, () -> 1D);
		tripAndReadRetry(breaker, now);
		Assertions.assertFalse(breaker.allow(ORIGIN));

		breaker.onRevisionNotice();
		Assertions.assertTrue(breaker.allow(ORIGIN));
		Assertions.assertFalse(breaker.allow(ORIGIN));
	}

	@Test
	public void abandonedHalfOpenProbeCanBeRetriedWithoutChangingBackoff() {
		final AtomicLong now = new AtomicLong();
		final OriginCircuitBreaker breaker = new OriginCircuitBreaker(now::get, () -> 1D);
		tripAndReadRetry(breaker, now);
		now.set(breaker.retryAtMillis(ORIGIN));
		Assertions.assertTrue(breaker.allow(ORIGIN));
		Assertions.assertFalse(breaker.allow(ORIGIN));

		breaker.onAbandoned(ORIGIN);

		Assertions.assertTrue(breaker.allow(ORIGIN));
		Assertions.assertEquals(5_000, breaker.retryAtMillis(ORIGIN), "abandonment must not extend or reset the scheduled backoff");
	}

	@Test
	public void defaultJitterSpreadsOneHundredBackoffSamplesWithinTwentyPercent() {
		final Set<Long> delays = new HashSet<>();
		for (int index = 0; index < 100; index++) {
			final AtomicLong now = new AtomicLong();
			final OriginCircuitBreaker breaker = new OriginCircuitBreaker(now::get);
			final long retryAt = tripAndReadRetry(breaker, now);
			final long delay = retryAt - now.get();
			Assertions.assertTrue(delay >= 4_000 && delay <= 6_000, "delay must stay within +/-20%: " + delay);
			delays.add(delay);
		}
		Assertions.assertTrue(delays.size() > 1, "default jitter must not synchronize every breaker");
	}

	private static long tripAndReadRetry(OriginCircuitBreaker breaker, AtomicLong now) {
		for (int i = 0; i < 3; i++) {
			Assertions.assertTrue(breaker.allow(ORIGIN));
			breaker.onFailure(ORIGIN);
		}
		return breaker.retryAtMillis(ORIGIN);
	}
}
