package org.mtr.mod.screen;

import org.mtr.mapping.holder.BlockPos;
import org.mtr.mod.block.DestinationSignConfigResult;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class DestinationSignSaveState {

	public static final int TIMEOUT_TICKS = 200;
	private static final AtomicLong SESSION_REQUEST_IDS = new AtomicLong();
	private static final Map<AtomicLong, Set<Long>> PENDING_IDS = new WeakHashMap<>();

	private final AtomicLong requestIds;
	private BlockPos pendingAnchor;
	private long pendingRequestId;
	private int pendingTicks;
	private DestinationSignConfigResult lastFailure;
	private boolean timedOut;

	public DestinationSignSaveState() {
		this(SESSION_REQUEST_IDS);
	}

	DestinationSignSaveState(AtomicLong requestIds) {
		this.requestIds = Objects.requireNonNull(requestIds, "requestIds");
	}

	public long begin(BlockPos anchor) {
		if (isPending()) throw new IllegalStateException("Destination sign save is already pending");
		pendingAnchor = Objects.requireNonNull(anchor, "anchor");
		synchronized (PENDING_IDS) {
			final Set<Long> pendingIds = PENDING_IDS.computeIfAbsent(requestIds, ignored -> new HashSet<>());
			do {
				pendingRequestId = requestIds.updateAndGet(value -> value == Long.MAX_VALUE || value < 0 ? 1 : value + 1);
			} while (!pendingIds.add(pendingRequestId));
		}
		pendingTicks = 0;
		lastFailure = null;
		timedOut = false;
		return pendingRequestId;
	}

	public ResultDisposition handleResult(BlockPos anchor, long requestId, DestinationSignConfigResult result) {
		if (!isPending() || !pendingAnchor.equals(anchor) || pendingRequestId != requestId) return ResultDisposition.IGNORED;
		clearPending();
		final DestinationSignConfigResult checkedResult = Objects.requireNonNull(result, "result");
		if (checkedResult == DestinationSignConfigResult.SUCCESS) return ResultDisposition.MATCHED_SUCCESS;
		lastFailure = checkedResult;
		return ResultDisposition.MATCHED_FAILURE;
	}

	public boolean tick() {
		if (!isPending() || ++pendingTicks < TIMEOUT_TICKS) return false;
		clearPending();
		timedOut = true;
		return true;
	}

	public void cancelPending() {
		if (isPending()) clearPending();
	}

	private void clearPending() {
		synchronized (PENDING_IDS) {
			final Set<Long> pendingIds = PENDING_IDS.get(requestIds);
			if (pendingIds != null) pendingIds.remove(pendingRequestId);
		}
		pendingAnchor = null;
		pendingRequestId = 0;
		pendingTicks = 0;
	}

	public boolean isPending() {
		return pendingRequestId > 0;
	}

	public long getPendingRequestId() {
		return pendingRequestId;
	}

	@Nullable
	public DestinationSignConfigResult getLastFailure() {
		return lastFailure;
	}

	public boolean isTimedOut() {
		return timedOut;
	}

	public enum ResultDisposition {
		IGNORED,
		MATCHED_SUCCESS,
		MATCHED_FAILURE
	}
}
