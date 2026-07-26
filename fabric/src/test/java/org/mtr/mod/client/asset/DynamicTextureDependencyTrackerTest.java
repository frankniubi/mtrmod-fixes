package org.mtr.mod.client.asset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class DynamicTextureDependencyTrackerTest {

	@Test
	public void routeEpochIsLazyAndUnchangedFingerprintReusesToken() {
		final DynamicTextureDependencyTracker tracker = new DynamicTextureDependencyTracker();
		final AtomicInteger firstEvaluations = new AtomicInteger();
		final AtomicInteger secondEvaluations = new AtomicInteger();
		Assertions.assertNull(tracker.current("first"));
		final DynamicTextureDependencyTracker.Token first = tracker.evaluate("first", () -> fingerprint("first", firstEvaluations));
		final DynamicTextureDependencyTracker.Token second = tracker.evaluate("second", () -> fingerprint("second", secondEvaluations));
		Assertions.assertSame(first, tracker.current("first"));

		Assertions.assertEquals(1, firstEvaluations.get());
		Assertions.assertEquals(1, secondEvaluations.get());
		Assertions.assertSame(first, tracker.evaluate("first", () -> fingerprint("unexpected", firstEvaluations)));
		Assertions.assertEquals(1, firstEvaluations.get(), "a key already checked in the current epochs must not recompute its fingerprint");

		final long routeEpoch = tracker.onRouteDataChanged();
		Assertions.assertEquals(1, routeEpoch);
		Assertions.assertEquals(1, firstEvaluations.get(), "an O(1) epoch change must not scan or evaluate tracked keys");
		Assertions.assertEquals(1, secondEvaluations.get(), "an O(1) epoch change must not scan or evaluate tracked keys");
		Assertions.assertFalse(tracker.isCurrent("first", first), "a token is conservatively stale until its key is lazily checked in the new epoch");
		Assertions.assertFalse(first.isCurrent());
		Assertions.assertNull(tracker.current("first"));

		final DynamicTextureDependencyTracker.Token unchanged = tracker.evaluate("first", () -> fingerprint("first", firstEvaluations));
		Assertions.assertSame(first, unchanged, "an unchanged immutable dependency fingerprint must retain its token and resident resource");
		Assertions.assertTrue(first.isCurrent(), "unchanged fingerprint evaluation revalidates the existing token");
		Assertions.assertEquals(2, firstEvaluations.get());
		Assertions.assertEquals(1, secondEvaluations.get(), "other keys remain lazy until first use in the new route epoch");
		Assertions.assertSame(unchanged, tracker.evaluate("first", () -> fingerprint("unexpected", firstEvaluations)));
		Assertions.assertEquals(2, firstEvaluations.get(), "the unchanged result must be memoized for the rest of the epoch");
	}

	@Test
	public void changedRouteFingerprintSupersedesOnlyThatKey() {
		final DynamicTextureDependencyTracker tracker = new DynamicTextureDependencyTracker();
		final AtomicReference<String> firstFingerprint = new AtomicReference<>("first-v1");
		final AtomicReference<String> secondFingerprint = new AtomicReference<>("second-v1");
		final DynamicTextureDependencyTracker.Token first = tracker.evaluate("first", firstFingerprint::get);
		final DynamicTextureDependencyTracker.Token second = tracker.evaluate("second", secondFingerprint::get);

		tracker.onRouteDataChanged();
		firstFingerprint.set("first-v2");
		final DynamicTextureDependencyTracker.Token changed = tracker.evaluate("first", firstFingerprint::get);
		final DynamicTextureDependencyTracker.Token unchanged = tracker.evaluate("second", secondFingerprint::get);

		Assertions.assertNotSame(first, changed);
		Assertions.assertEquals("first-v2", changed.getFingerprint());
		Assertions.assertTrue(changed.getSequence() > first.getSequence());
		Assertions.assertFalse(tracker.isCurrent("first", first));
		Assertions.assertTrue(tracker.isCurrent("first", changed));
		Assertions.assertSame(second, unchanged, "an unrelated unchanged key must retain its token");
		Assertions.assertTrue(tracker.isCurrent("second", second));
	}

	@Test
	public void resourceAndVariantEpochsLazilySupersedeEvenEqualFingerprints() {
		final DynamicTextureDependencyTracker tracker = new DynamicTextureDependencyTracker();
		final AtomicInteger evaluations = new AtomicInteger();
		final DynamicTextureDependencyTracker.Token initial = tracker.evaluate("key", () -> fingerprint("stable", evaluations));

		Assertions.assertEquals(1, tracker.onResourceReload());
		Assertions.assertEquals(1, evaluations.get(), "resource reload must remain an O(1) scalar epoch change");
		final DynamicTextureDependencyTracker.Token afterResourceReload = tracker.evaluate("key", () -> fingerprint("stable", evaluations));
		Assertions.assertNotSame(initial, afterResourceReload, "resource reload invalidates every used key even when its route-data fingerprint is equal");

		Assertions.assertEquals(1, tracker.onVariantChanged());
		Assertions.assertEquals(2, evaluations.get(), "variant change must remain an O(1) scalar epoch change");
		final DynamicTextureDependencyTracker.Token afterVariantChange = tracker.evaluate("key", () -> fingerprint("stable", evaluations));
		Assertions.assertNotSame(afterResourceReload, afterVariantChange, "resolution or language changes must supersede the previous variant token");
		Assertions.assertEquals(3, evaluations.get());
	}

	@Test
	public void forgetInvalidatesOldTokenAndRecreationCannotAliasIt() {
		final DynamicTextureDependencyTracker tracker = new DynamicTextureDependencyTracker();
		final DynamicTextureDependencyTracker.Token deleted = tracker.evaluate("deleted", () -> "same");
		final DynamicTextureDependencyTracker.Token unaffected = tracker.evaluate("unaffected", () -> "other");

		Assertions.assertTrue(tracker.forget("deleted"));
		Assertions.assertFalse(tracker.forget("deleted"));
		Assertions.assertFalse(tracker.isCurrent("deleted", deleted));
		Assertions.assertTrue(tracker.isCurrent("unaffected", unaffected));

		final DynamicTextureDependencyTracker.Token recreated = tracker.evaluate("deleted", () -> "same");
		Assertions.assertTrue(recreated.getSequence() > deleted.getSequence());
		Assertions.assertFalse(tracker.isCurrent("deleted", deleted));
		Assertions.assertTrue(tracker.isCurrent("deleted", recreated));
	}

	@Test
	public void argumentsAndTokensAreImmutableAndKeyScoped() {
		final DynamicTextureDependencyTracker tracker = new DynamicTextureDependencyTracker();
		final DynamicTextureDependencyTracker.Token token = tracker.evaluate("key", () -> "fingerprint");

		Assertions.assertEquals("fingerprint", token.getFingerprint());
		Assertions.assertTrue(token.getSequence() > 0);
		Assertions.assertTrue(token.isCurrent());
		Assertions.assertFalse(tracker.isCurrent("other", token));
		Assertions.assertThrows(NullPointerException.class, () -> tracker.evaluate(null, () -> "fingerprint"));
		Assertions.assertThrows(NullPointerException.class, () -> tracker.evaluate("key", null));
		Assertions.assertThrows(NullPointerException.class, () -> new DynamicTextureDependencyTracker().evaluate("key", () -> null));
		Assertions.assertThrows(NullPointerException.class, () -> tracker.isCurrent("key", null));
		Assertions.assertThrows(NullPointerException.class, () -> tracker.forget(null));
	}

	@Test
	public void slowFingerprintDoesNotBlockScalarInvalidation() throws Exception {
		final DynamicTextureDependencyTracker tracker = new DynamicTextureDependencyTracker();
		final CountDownLatch calculating = new CountDownLatch(1);
		final CountDownLatch release = new CountDownLatch(1);
		final ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			final java.util.concurrent.Future<DynamicTextureDependencyTracker.Token> evaluation = executor.submit(() -> tracker.evaluate("key", () -> {
				calculating.countDown();
				try {
					if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
				return "fingerprint";
			}));
			Assertions.assertTrue(calculating.await(5, TimeUnit.SECONDS));
			final java.util.concurrent.Future<Long> invalidation = executor.submit(tracker::onRouteDataChanged);
			Assertions.assertEquals(1, invalidation.get(1, TimeUnit.SECONDS));
			release.countDown();
			Assertions.assertEquals("fingerprint", evaluation.get(5, TimeUnit.SECONDS).getFingerprint());
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void slowResolvedValueIsDiscardedWhenRouteEpochChanges() throws Exception {
		final DynamicTextureDependencyTracker tracker = new DynamicTextureDependencyTracker();
		final AtomicInteger resolverCalls = new AtomicInteger();
		final AtomicReference<Object> obsoleteValue = new AtomicReference<>();
		final AtomicReference<Object> acceptedValue = new AtomicReference<>();
		final AtomicReference<Object> lastFingerprintValue = new AtomicReference<>();
		final CountDownLatch resolvingFirst = new CountDownLatch(1);
		final CountDownLatch releaseFirst = new CountDownLatch(1);
		final ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			final java.util.concurrent.Future<DynamicTextureDependencyTracker.Resolution<Object>> evaluation = executor.submit(() -> tracker.evaluateResolved("key", () -> {
				final Object value = new Object();
				if (resolverCalls.incrementAndGet() == 1) {
					obsoleteValue.set(value);
					resolvingFirst.countDown();
					try {
						if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						throw new IllegalStateException(exception);
					}
				} else {
					acceptedValue.set(value);
				}
				return value;
			}, value -> {
				lastFingerprintValue.set(value);
				return "stable";
			}));

			Assertions.assertTrue(resolvingFirst.await(5, TimeUnit.SECONDS));
			Assertions.assertEquals(1, tracker.onRouteDataChanged(), "epoch invalidation must not wait for the resolver");
			releaseFirst.countDown();

			final DynamicTextureDependencyTracker.Resolution<Object> resolution = evaluation.get(5, TimeUnit.SECONDS);
			Assertions.assertEquals(2, resolverCalls.get(), "the value resolved against the obsolete epoch must be discarded and recomputed");
			Assertions.assertNotSame(obsoleteValue.get(), resolution.getValue());
			Assertions.assertSame(acceptedValue.get(), resolution.getValue());
			Assertions.assertSame(resolution.getValue(), lastFingerprintValue.get(), "fingerprinting and rendering must use the same accepted immutable object");

			final DynamicTextureDependencyTracker.Resolution<Object> current = tracker.currentResolved("key", Object.class);
			Assertions.assertNotNull(current);
			Assertions.assertSame(resolution.getToken(), current.getToken());
			Assertions.assertSame(resolution.getValue(), current.getValue());
		} finally {
			releaseFirst.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	public void routeEpochWithEqualFingerprintReusesTokenAndReplacesResolvedValue() {
		final DynamicTextureDependencyTracker tracker = new DynamicTextureDependencyTracker();
		final Object firstValue = new Object();
		final Object secondValue = new Object();
		final AtomicInteger resolverCalls = new AtomicInteger();
		final DynamicTextureDependencyTracker.Resolution<Object> first = tracker.evaluateResolved("key", () -> {
			resolverCalls.incrementAndGet();
			return firstValue;
		}, ignored -> "stable");

		tracker.onRouteDataChanged();
		final DynamicTextureDependencyTracker.Resolution<Object> second = tracker.evaluateResolved("key", () -> {
			resolverCalls.incrementAndGet();
			return secondValue;
		}, ignored -> "stable");

		Assertions.assertEquals(2, resolverCalls.get());
		Assertions.assertSame(first.getToken(), second.getToken(), "route-only revalidation with equal content must preserve the resident token");
		Assertions.assertNotSame(first.getValue(), second.getValue());
		Assertions.assertSame(secondValue, second.getValue(), "the accepted immutable snapshot must still be replaced");
		Assertions.assertSame(secondValue, tracker.currentResolved("key", Object.class).getValue());
	}

	@Test
	public void resourceAndVariantEpochsSupersedeEqualResolvedFingerprints() {
		final DynamicTextureDependencyTracker tracker = new DynamicTextureDependencyTracker();
		final DynamicTextureDependencyTracker.Resolution<Object> initial = tracker.evaluateResolved("key", Object::new, ignored -> "stable");

		tracker.onResourceReload();
		final DynamicTextureDependencyTracker.Resolution<Object> afterResource = tracker.evaluateResolved("key", Object::new, ignored -> "stable");
		Assertions.assertNotSame(initial.getToken(), afterResource.getToken());
		Assertions.assertNotSame(initial.getValue(), afterResource.getValue());

		tracker.onVariantChanged();
		final DynamicTextureDependencyTracker.Resolution<Object> afterVariant = tracker.evaluateResolved("key", Object::new, ignored -> "stable");
		Assertions.assertNotSame(afterResource.getToken(), afterVariant.getToken());
		Assertions.assertNotSame(afterResource.getValue(), afterVariant.getValue());
	}

	@Test
	public void fingerprintOnlyEvaluationDoesNotExposeAResolvedValue() {
		final DynamicTextureDependencyTracker tracker = new DynamicTextureDependencyTracker();
		final DynamicTextureDependencyTracker.Token token = tracker.evaluate("key", () -> "stable");

		Assertions.assertSame(token, tracker.current("key"));
		Assertions.assertNull(tracker.currentResolved("key", Object.class));
	}

	private static String fingerprint(String value, AtomicInteger evaluations) {
		evaluations.incrementAndGet();
		return value;
	}
}
