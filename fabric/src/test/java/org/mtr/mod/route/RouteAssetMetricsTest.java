package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class RouteAssetMetricsTest {

	@Test
	public void countersAndSummaryAreMonotonicAndReadable() {
		final RouteAssetMetrics metrics = new RouteAssetMetrics();
		metrics.requested();
		metrics.requested();
		metrics.coalesced();
		metrics.queued();
		metrics.completed(12);
		metrics.cancelled();
		metrics.failed();
		metrics.rendered(128);
		metrics.reused();
		metrics.callerRuns();
		metrics.periodicDeferred();
		metrics.periodicSkipped();
		final RouteAssetMetrics.Summary summary = new RouteAssetMetrics.Summary("a".repeat(64), 1, 2, 3, 4, 55, 128, 1, 8080);
		metrics.summary(summary);

		Assertions.assertEquals(2, metrics.getRequestedGenerations());
		Assertions.assertEquals(1, metrics.getCoalescedGenerations());
		Assertions.assertEquals(1, metrics.getQueuedGenerations());
		Assertions.assertEquals(1, metrics.getCompletedGenerations());
		Assertions.assertEquals(12, metrics.getCompletedDurationMillis());
		Assertions.assertEquals(1, metrics.getCancelledGenerations());
		Assertions.assertEquals(1, metrics.getFailedGenerations());
		Assertions.assertEquals(1, metrics.getRenderedObjects());
		Assertions.assertEquals(128, metrics.getRenderedBytes());
		Assertions.assertEquals(1, metrics.getReusedObjects());
		Assertions.assertEquals(1, metrics.getCallerRuns());
		Assertions.assertEquals(1, metrics.getPeriodicDeferred());
		Assertions.assertEquals(1, metrics.getPeriodicSkipped());
		Assertions.assertSame(summary, metrics.getLastSummary());
	}
}
