package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

public final class TickLagGateTest {

	@Test
	public void ewmaTracksNormalIntervalsAndResetsAfterAutosaveGaps() {
		final AtomicLong now = new AtomicLong(1);
		final TickLagGate gate = new TickLagGate(55, now::get, millis -> now.addAndGet(millis * 1_000_000L));
		gate.onTick();
		for (int index = 0; index < 20; index++) {
			now.addAndGet(50_000_000L);
			gate.onTick();
		}
		Assertions.assertEquals(50_000_000L, gate.getTickIntervalEwmaNanos(), 1_000_000L);
		now.addAndGet(6_000_000_000L);
		gate.onTick();
		Assertions.assertEquals(0, gate.getTickIntervalEwmaNanos());

		now.addAndGet(50_000_000L);
		gate.onTick();
		Assertions.assertEquals(50_000_000L, gate.getTickIntervalEwmaNanos(), 1_000_000L);
	}

	@Test
	public void staleJobLeavesGateImmediatelyAndDoesNotForceRender() {
		final AtomicLong now = new AtomicLong(1);
		final TickLagGate gate = unhealthyGate(now);
		Assertions.assertFalse(gate.awaitHealthy(() -> true));
		Assertions.assertEquals(0, gate.getForcedCount());
	}

	@Test
	public void unhealthyJobIsForcedAfterTenSeconds() {
		final AtomicLong now = new AtomicLong(1);
		final TickLagGate gate = unhealthyGate(now);
		Assertions.assertTrue(gate.awaitHealthy(() -> false));
		Assertions.assertEquals(1, gate.getForcedCount());
		Assertions.assertTrue(gate.getWaitCount() > 0);
		Assertions.assertTrue(gate.getWaitNanos() >= TickLagGate.MAX_WAIT_MILLIS * 1_000_000L);
	}

	@Test
	public void zeroDisablesGateAndThresholdsAreClamped() {
		final AtomicLong now = new AtomicLong(1);
		Assertions.assertEquals(0, new TickLagGate(0, now::get, millis -> now.addAndGet(millis * 1_000_000L)).getThresholdMillis());
		Assertions.assertEquals(0, new TickLagGate(-1, now::get, millis -> now.addAndGet(millis * 1_000_000L)).getThresholdMillis());
		Assertions.assertEquals(51, new TickLagGate(30, now::get, millis -> now.addAndGet(millis * 1_000_000L)).getThresholdMillis());
		Assertions.assertEquals(1_000, new TickLagGate(5_000, now::get, millis -> now.addAndGet(millis * 1_000_000L)).getThresholdMillis());
	}

	private static TickLagGate unhealthyGate(AtomicLong now) {
		final TickLagGate gate = new TickLagGate(55, now::get, millis -> now.addAndGet(millis * 1_000_000L));
		gate.onTick();
		now.addAndGet(80_000_000L);
		gate.onTick();
		now.addAndGet(80_000_000L);
		gate.onTick();
		return gate;
	}
}
