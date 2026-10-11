package org.mtr.mod.route;

import java.util.concurrent.atomic.AtomicLong;

public final class RouteAssetMetrics {

	private final AtomicLong queuedGenerations = new AtomicLong();
	private final AtomicLong requestedGenerations = new AtomicLong();
	private final AtomicLong coalescedGenerations = new AtomicLong();
	private final AtomicLong completedGenerations = new AtomicLong();
	private final AtomicLong cancelledGenerations = new AtomicLong();
	private final AtomicLong failedGenerations = new AtomicLong();
	private final AtomicLong renderedObjects = new AtomicLong();
	private final AtomicLong reusedObjects = new AtomicLong();
	private final AtomicLong renderedBytes = new AtomicLong();
	private final AtomicLong completedDurationMillis = new AtomicLong();
	private final AtomicLong callerRuns = new AtomicLong();
	private final AtomicLong periodicDeferred = new AtomicLong();
	private final AtomicLong periodicSkipped = new AtomicLong();
	private volatile Summary lastSummary = new Summary("", 0, 0, 0, 0, 0, 0, 0, 0);

	void requested() { requestedGenerations.incrementAndGet(); }
	void coalesced() { coalescedGenerations.incrementAndGet(); }
	void queued() { queuedGenerations.incrementAndGet(); }
	void completed(long durationMillis) { completedGenerations.incrementAndGet(); completedDurationMillis.addAndGet(Math.max(0, durationMillis)); }
	void cancelled() { cancelledGenerations.incrementAndGet(); }
	void failed() { failedGenerations.incrementAndGet(); }
	void rendered(long bytes) { renderedObjects.incrementAndGet(); renderedBytes.addAndGet(bytes); }
	void reused() { reusedObjects.incrementAndGet(); }
	void callerRuns() { callerRuns.incrementAndGet(); }
	void periodicDeferred() { periodicDeferred.incrementAndGet(); }
	void periodicSkipped() { periodicSkipped.incrementAndGet(); }
	void summary(Summary summary) { lastSummary = summary; }

	public long getRequestedGenerations() { return requestedGenerations.get(); }
	public long getCoalescedGenerations() { return coalescedGenerations.get(); }
	public long getCompletedGenerations() { return completedGenerations.get(); }
	public long getQueuedGenerations() { return queuedGenerations.get(); }
	public long getCancelledGenerations() { return cancelledGenerations.get(); }
	public long getFailedGenerations() { return failedGenerations.get(); }
	public long getRenderedObjects() { return renderedObjects.get(); }
	public long getReusedObjects() { return reusedObjects.get(); }
	public long getRenderedBytes() { return renderedBytes.get(); }
	public long getCompletedDurationMillis() { return completedDurationMillis.get(); }
	public long getCallerRuns() { return callerRuns.get(); }
	public long getPeriodicDeferred() { return periodicDeferred.get(); }
	public long getPeriodicSkipped() { return periodicSkipped.get(); }
	public Summary getLastSummary() { return lastSummary; }

	public static final class Summary {
		private final String revision;
		private final int add;
		private final int modify;
		private final int delete;
		private final int move;
		private final long durationMillis;
		private final long bytes;
		private final long reused;
		private final int originPort;
		private final long enumerateMillis;
		private final long fingerprintMillis;
		private final long reuseMillis;
		private final long casReuseMillis;
		private final long renderMillis;
		private final long publishMillis;
		private final boolean skippedUnchanged;

		public Summary(String revision, int add, int modify, int delete, int move, long durationMillis, long bytes, long reused, int originPort) {
			this(revision, add, modify, delete, move, durationMillis, bytes, reused, originPort, 0, 0, 0, 0, false);
		}

		public Summary(String revision, int add, int modify, int delete, int move, long durationMillis, long bytes, long reused, int originPort,
				long enumerateMillis, long reuseMillis, long renderMillis, long publishMillis, boolean skippedUnchanged) {
			this(revision, add, modify, delete, move, durationMillis, bytes, reused, originPort,
					enumerateMillis, 0, reuseMillis, 0, renderMillis, publishMillis, skippedUnchanged);
		}

		public Summary(String revision, int add, int modify, int delete, int move, long durationMillis, long bytes, long reused, int originPort,
				long enumerateMillis, long fingerprintMillis, long reuseMillis, long casReuseMillis, long renderMillis, long publishMillis, boolean skippedUnchanged) {
			this.revision = revision;
			this.add = add;
			this.modify = modify;
			this.delete = delete;
			this.move = move;
			this.durationMillis = durationMillis;
			this.bytes = bytes;
			this.reused = reused;
			this.originPort = originPort;
			this.enumerateMillis = Math.max(0, enumerateMillis);
			this.fingerprintMillis = Math.max(0, fingerprintMillis);
			this.reuseMillis = Math.max(0, reuseMillis);
			this.casReuseMillis = Math.max(0, casReuseMillis);
			this.renderMillis = Math.max(0, renderMillis);
			this.publishMillis = Math.max(0, publishMillis);
			this.skippedUnchanged = skippedUnchanged;
		}

		public String getRevision() { return revision; }
		public int getAdd() { return add; }
		public int getModify() { return modify; }
		public int getDelete() { return delete; }
		public int getMove() { return move; }
		public long getDurationMillis() { return durationMillis; }
		public long getBytes() { return bytes; }
		public long getReused() { return reused; }
		public int getOriginPort() { return originPort; }
		public long getEnumerateMillis() { return enumerateMillis; }
		public long getFingerprintMillis() { return fingerprintMillis; }
		public long getReuseMillis() { return reuseMillis; }
		public long getCasReuseMillis() { return casReuseMillis; }
		public long getRenderMillis() { return renderMillis; }
		public long getPublishMillis() { return publishMillis; }
		public boolean isSkippedUnchanged() { return skippedUnchanged; }
	}
}
