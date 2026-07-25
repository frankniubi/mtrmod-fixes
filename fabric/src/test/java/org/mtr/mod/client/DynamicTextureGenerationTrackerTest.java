package org.mtr.mod.client;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.client.asset.DynamicTextureDependencyTracker;

public final class DynamicTextureGenerationTrackerTest {

	@Test
	public void testCurrentGenerationLifecycle() {
		final DynamicTextureGenerationTracker tracker = new DynamicTextureGenerationTracker();
		final DynamicTextureGenerationTracker.Token token = tracker.start("key");

		Assertions.assertTrue(tracker.isActive("key"));
		Assertions.assertTrue(tracker.isCurrent("key", token));

		tracker.completeSuccess("key", token);

		Assertions.assertFalse(tracker.isActive("key"));
		Assertions.assertFalse(tracker.isRetryBlocked("key", 100));
	}

	@Test
	public void testRefreshSupersedesQueuedCompletion() {
		final DynamicTextureGenerationTracker tracker = new DynamicTextureGenerationTracker();
		final DynamicTextureGenerationTracker.Token staleToken = tracker.start("key");
		tracker.refresh();
		final DynamicTextureGenerationTracker.Token currentToken = tracker.start("key");

		tracker.completeFailure("key", staleToken, 200);

		Assertions.assertTrue(tracker.isCurrent("key", currentToken));
		Assertions.assertFalse(tracker.isRetryBlocked("key", 100));

		tracker.completeSuccess("key", staleToken);

		Assertions.assertTrue(tracker.isCurrent("key", currentToken));
	}

	@Test
	public void testFailureBackoffExpires() {
		final DynamicTextureGenerationTracker tracker = new DynamicTextureGenerationTracker();
		final DynamicTextureGenerationTracker.Token token = tracker.start("key");
		tracker.completeFailure("key", token, 200);

		Assertions.assertFalse(tracker.isActive("key"));
		Assertions.assertTrue(tracker.isRetryBlocked("key", 199));
		Assertions.assertFalse(tracker.isRetryBlocked("key", 200));
		Assertions.assertFalse(tracker.isRetryBlocked("key", 201));
	}

	@Test
	public void testRefreshClearsFailureBackoff() {
		final DynamicTextureGenerationTracker tracker = new DynamicTextureGenerationTracker();
		final DynamicTextureGenerationTracker.Token token = tracker.start("key");
		tracker.completeFailure("key", token, Long.MAX_VALUE);

		tracker.refresh();

		Assertions.assertFalse(tracker.isRetryBlocked("key", 0));
	}

	@Test
	public void testDependencyFingerprintRejectsStaleCompletion() {
		final DynamicTextureDependencyTracker dependencies = new DynamicTextureDependencyTracker();
		final DynamicTextureGenerationTracker tracker = new DynamicTextureGenerationTracker();
		final DynamicTextureDependencyTracker.Token oldDependency = dependencies.evaluate("key", () -> "old");
		final DynamicTextureGenerationTracker.Token generation = tracker.start("key", oldDependency);

		dependencies.onRouteDataChanged();
		final DynamicTextureDependencyTracker.Token newDependency = dependencies.evaluate("key", () -> "new");

		Assertions.assertTrue(tracker.isActive("key"));
		Assertions.assertFalse(tracker.isActive("key", newDependency));
		Assertions.assertFalse(tracker.isCurrent("key", generation, newDependency));
		Assertions.assertFalse(tracker.completeSuccess("key", generation, newDependency));
		Assertions.assertFalse(tracker.isActive("key"), "a rejected stale completion must not leave the key permanently active");
		Assertions.assertFalse(tracker.isRetryBlocked("key", 0));
	}

	@Test
	public void testDependencyFingerprintRejectsStaleFailureWithoutBackoff() {
		final DynamicTextureDependencyTracker dependencies = new DynamicTextureDependencyTracker();
		final DynamicTextureGenerationTracker tracker = new DynamicTextureGenerationTracker();
		final DynamicTextureDependencyTracker.Token oldDependency = dependencies.evaluate("key", () -> "old");
		final DynamicTextureGenerationTracker.Token generation = tracker.start("key", oldDependency);

		dependencies.onRouteDataChanged();
		final DynamicTextureDependencyTracker.Token newDependency = dependencies.evaluate("key", () -> "new");

		Assertions.assertFalse(tracker.completeFailure("key", generation, newDependency, Long.MAX_VALUE));
		Assertions.assertFalse(tracker.isActive("key"));
		Assertions.assertFalse(tracker.isRetryBlocked("key", 0), "stale work must not impose a retry delay on the current fingerprint");
	}

	@Test
	public void testUnchangedDependencyFingerprintKeepsGenerationCurrent() {
		final DynamicTextureDependencyTracker dependencies = new DynamicTextureDependencyTracker();
		final DynamicTextureGenerationTracker tracker = new DynamicTextureGenerationTracker();
		final DynamicTextureDependencyTracker.Token dependency = dependencies.evaluate("key", () -> "same");
		final DynamicTextureGenerationTracker.Token generation = tracker.start("key", dependency);

		dependencies.onRouteDataChanged();
		final DynamicTextureDependencyTracker.Token unchangedDependency = dependencies.evaluate("key", () -> "same");

		Assertions.assertSame(dependency, unchangedDependency);
		Assertions.assertTrue(tracker.isActive("key", unchangedDependency));
		Assertions.assertTrue(tracker.isCurrent("key", generation, unchangedDependency));
		Assertions.assertTrue(tracker.completeSuccess("key", generation, unchangedDependency));
		Assertions.assertFalse(tracker.isActive("key"));
	}

	@Test
	public void testNewDependencyGenerationSupersedesOldCompletion() {
		final DynamicTextureDependencyTracker dependencies = new DynamicTextureDependencyTracker();
		final DynamicTextureGenerationTracker tracker = new DynamicTextureGenerationTracker();
		final DynamicTextureDependencyTracker.Token oldDependency = dependencies.evaluate("key", () -> "old");
		final DynamicTextureGenerationTracker.Token staleGeneration = tracker.start("key", oldDependency);

		dependencies.onRouteDataChanged();
		final DynamicTextureDependencyTracker.Token newDependency = dependencies.evaluate("key", () -> "new");
		final DynamicTextureGenerationTracker.Token currentGeneration = tracker.start("key", newDependency);
		tracker.completeFailure("key", staleGeneration, oldDependency, Long.MAX_VALUE);

		Assertions.assertTrue(tracker.isCurrent("key", currentGeneration, newDependency));
		Assertions.assertFalse(tracker.isRetryBlocked("key", 0));
	}

	@Test
	public void testRetryBackoffIsScopedToDependencyFingerprint() {
		final DynamicTextureDependencyTracker dependencies = new DynamicTextureDependencyTracker();
		final DynamicTextureGenerationTracker tracker = new DynamicTextureGenerationTracker();
		final DynamicTextureDependencyTracker.Token oldDependency = dependencies.evaluate("key", () -> "old");
		final DynamicTextureGenerationTracker.Token oldGeneration = tracker.start("key", oldDependency);
		Assertions.assertTrue(tracker.completeFailure("key", oldGeneration, oldDependency, Long.MAX_VALUE));
		Assertions.assertTrue(tracker.isRetryBlocked("key", oldDependency, 0));

		dependencies.onRouteDataChanged();
		final DynamicTextureDependencyTracker.Token newDependency = dependencies.evaluate("key", () -> "new");

		Assertions.assertFalse(tracker.isRetryBlocked("key", newDependency, 0), "backoff for an obsolete fingerprint must not delay current content");
		Assertions.assertFalse(tracker.isRetryBlocked("key", 0), "discarding obsolete backoff must update the compatible legacy view too");
	}

	@Test
	public void testUnevaluatedNewEpochRejectsOldDependencyToken() {
		final DynamicTextureDependencyTracker dependencies = new DynamicTextureDependencyTracker();
		final DynamicTextureGenerationTracker tracker = new DynamicTextureGenerationTracker();
		final DynamicTextureDependencyTracker.Token dependency = dependencies.evaluate("key", () -> "same");
		final DynamicTextureGenerationTracker.Token generation = tracker.start("key", dependency);

		dependencies.onResourceReload();

		Assertions.assertFalse(dependency.isCurrent());
		Assertions.assertFalse(tracker.isCurrent("key", generation, dependency));
		Assertions.assertFalse(tracker.completeSuccess("key", generation, dependency));
		Assertions.assertFalse(tracker.isActive("key"));
	}

	@Test
	public void testStaleRegistrationCanDiscardActivityWithoutBackoff() {
		final DynamicTextureDependencyTracker dependencies = new DynamicTextureDependencyTracker();
		final DynamicTextureGenerationTracker tracker = new DynamicTextureGenerationTracker();
		final DynamicTextureDependencyTracker.Token dependency = dependencies.evaluate("key", () -> "same");
		final DynamicTextureGenerationTracker.Token generation = tracker.start("key", dependency);

		dependencies.onRouteDataChanged();
		Assertions.assertFalse(tracker.isCurrent("key", generation, dependency));
		Assertions.assertTrue(tracker.discard("key", generation));
		Assertions.assertFalse(tracker.isActive("key"));
		Assertions.assertFalse(tracker.isRetryBlocked("key", 0));

		final DynamicTextureDependencyTracker.Token revalidated = dependencies.evaluate("key", () -> "same");
		Assertions.assertSame(dependency, revalidated);
		Assertions.assertNotNull(tracker.start("key", revalidated));
	}
}
