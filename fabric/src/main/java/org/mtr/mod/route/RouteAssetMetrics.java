package org.mtr.mod.route;

import java.util.concurrent.atomic.AtomicLong;

public final class RouteAssetMetrics {

	private final AtomicLong queuedGenerations = new AtomicLong();
	private final AtomicLong cancelledGenerations = new AtomicLong();
	private final AtomicLong failedGenerations = new AtomicLong();
	private final AtomicLong renderedObjects = new AtomicLong();
	private final AtomicLong reusedObjects = new AtomicLong();
	private final AtomicLong renderedBytes = new AtomicLong();
	private volatile Summary lastSummary = new Summary("", 0, 0, 0, 0, 0, 0, 0, 0);

	void queued() { queuedGenerations.incrementAndGet(); }
	void cancelled() { cancelledGenerations.incrementAndGet(); }
	void failed() { failedGenerations.incrementAndGet(); }
	void rendered(long bytes) { renderedObjects.incrementAndGet(); renderedBytes.addAndGet(bytes); }
	void reused() { reusedObjects.incrementAndGet(); }
	void summary(Summary summary) { lastSummary = summary; }

	public long getQueuedGenerations() { return queuedGenerations.get(); }
	public long getCancelledGenerations() { return cancelledGenerations.get(); }
	public long getFailedGenerations() { return failedGenerations.get(); }
	public long getRenderedObjects() { return renderedObjects.get(); }
	public long getReusedObjects() { return reusedObjects.get(); }
	public long getRenderedBytes() { return renderedBytes.get(); }
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

		public Summary(String revision, int add, int modify, int delete, int move, long durationMillis, long bytes, long reused, int originPort) {
			this.revision = revision;
			this.add = add;
			this.modify = modify;
			this.delete = delete;
			this.move = move;
			this.durationMillis = durationMillis;
			this.bytes = bytes;
			this.reused = reused;
			this.originPort = originPort;
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
	}
}
