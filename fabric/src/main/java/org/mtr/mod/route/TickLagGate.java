package org.mtr.mod.route;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Stage-1 route-generation backpressure based on the observed interval between
 * server start-tick callbacks. It detects an already lagging tick loop without
 * requiring a Fabric/Forge-specific MinecraftServer mixin.
 */
public final class TickLagGate {

	static final long MAX_IGNORED_INTERVAL_NANOS = 5_000_000_000L;
	static final long WAIT_SLICE_MILLIS = 250L;
	static final long MAX_WAIT_MILLIS = 10_000L;

	private final long thresholdNanos;
	private final LongSupplier nanoClock;
	private final Sleeper sleeper;
	private final AtomicLong waitCount = new AtomicLong();
	private final AtomicLong waitNanos = new AtomicLong();
	private final AtomicLong forcedCount = new AtomicLong();
	private volatile long ewmaNanos;
	private long previousTickNanos;

	public TickLagGate(int maxTickMillis) {
		this(maxTickMillis, System::nanoTime, millis -> Thread.sleep(millis));
	}

	TickLagGate(int maxTickMillis, LongSupplier nanoClock, Sleeper sleeper) {
		this.thresholdNanos = maxTickMillis <= 0 ? 0 : Math.max(51L, Math.min(1_000L, maxTickMillis)) * 1_000_000L;
		this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
		this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
	}

	/**
	 * Feed one start-tick timestamp. The tick thread is the only writer.
	 */
	public void onTick() {
		final long now = nanoClock.getAsLong();
		if (previousTickNanos == 0) {
			previousTickNanos = now;
			return;
		}
		final long interval = now - previousTickNanos;
		previousTickNanos = now;
		if (interval <= 0 || interval > MAX_IGNORED_INTERVAL_NANOS) {
			// A stopped/paused server must not resume with the pre-pause lag
			// estimate. The next normal tick starts a fresh EWMA sample.
			ewmaNanos = 0;
			return;
		}
		final long previous = ewmaNanos;
		ewmaNanos = previous == 0 ? interval : previous + (interval - previous) / 5;
	}

	/**
	 * Wait before starting one new render job. Returns false when the job became
	 * stale, the manager is closing, or the waiting thread was interrupted.
	 */
	public boolean awaitHealthy(BooleanSupplier staleOrClosed) {
		Objects.requireNonNull(staleOrClosed, "staleOrClosed");
		if (thresholdNanos == 0 || ewmaNanos <= thresholdNanos) return true;
		final long started = nanoClock.getAsLong();
		boolean waited = false;
		while (ewmaNanos > thresholdNanos) {
			if (staleOrClosed.getAsBoolean()) return false;
			final long elapsedNanos = nanoClock.getAsLong() - started;
			if (elapsedNanos >= MAX_WAIT_MILLIS * 1_000_000L) {
				forcedCount.incrementAndGet();
				if (waited) waitNanos.addAndGet(Math.max(0, elapsedNanos));
				return true;
			}
			if (!waited) {
				waitCount.incrementAndGet();
				waited = true;
			}
			try {
				sleeper.sleep(WAIT_SLICE_MILLIS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				if (waited) waitNanos.addAndGet(Math.max(0, nanoClock.getAsLong() - started));
				return false;
			}
		}
		if (waited) waitNanos.addAndGet(Math.max(0, nanoClock.getAsLong() - started));
		return true;
	}

	public long getTickIntervalEwmaNanos() {
		return ewmaNanos;
	}

	public long getWaitCount() {
		return waitCount.get();
	}

	public long getWaitNanos() {
		return waitNanos.get();
	}

	public long getForcedCount() {
		return forcedCount.get();
	}

	public int getThresholdMillis() {
		return (int) (thresholdNanos / 1_000_000L);
	}

	@FunctionalInterface
	interface Sleeper {
		void sleep(long millis) throws InterruptedException;
	}
}
