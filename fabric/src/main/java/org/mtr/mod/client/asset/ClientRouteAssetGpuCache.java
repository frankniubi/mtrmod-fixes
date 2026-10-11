package org.mtr.mod.client.asset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Connection-scoped content-hash cache. Decode work runs on the supplied executor, while GPU registration happens
 * only when {@link #drainUploads(int, long)} is called from the render thread.
 *
 * @param <D> decoded image type
 * @param <H> registered GPU handle type
 */
public final class ClientRouteAssetGpuCache<D extends AutoCloseable, H extends AutoCloseable> implements AutoCloseable {

	public static final int MAX_UPLOADS_PER_DRAIN = 8;

	private final Object lock = new Object();
	private final Executor decodeExecutor;
	private final Decoder<D> decoder;
	private final Registrar<D, H> registrar;
	private final LongSupplier nanoClock;
	private final long maximumResidentBytes;
	private final ConcurrentLinkedQueue<DecodedTexture<D>> decodedTextures = new ConcurrentLinkedQueue<>();
	private final Map<String, String> logicalBindings = new HashMap<>();
	private final Map<String, Long> inFlight = new HashMap<>();
	private final LinkedHashMap<String, ResidentTexture<H>> residents = new LinkedHashMap<>(16, 0.75F, true);
	private final Set<String> pinnedContentHashes = new HashSet<>();
	private long generation = 1;
	private long residentBytes;
	private boolean residentBytesOverflow;
	private boolean closed;

	public ClientRouteAssetGpuCache(Executor decodeExecutor, Decoder<D> decoder, Registrar<D, H> registrar, LongSupplier nanoClock, long maximumResidentBytes) {
		this.decodeExecutor = Objects.requireNonNull(decodeExecutor, "decodeExecutor");
		this.decoder = Objects.requireNonNull(decoder, "decoder");
		this.registrar = Objects.requireNonNull(registrar, "registrar");
		this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
		this.maximumResidentBytes = Math.max(0, maximumResidentBytes);
	}

	/**
	 * Returns a resident handle or schedules one decode for the content hash. This method never performs decode or
	 * registration inline unless the injected executor itself deliberately runs tasks inline.
	 */
	public H request(String logicalKey, String contentHash) {
		Objects.requireNonNull(logicalKey, "logicalKey");
		Objects.requireNonNull(contentHash, "contentHash");
		final long scheduledGeneration;
		synchronized (lock) {
			if (closed) return null;
			logicalBindings.put(logicalKey, contentHash);
			final ResidentTexture<H> residentTexture = residents.get(contentHash);
			if (residentTexture != null) return residentTexture.handle;
			if (inFlight.containsKey(contentHash)) return null;
			scheduledGeneration = generation;
			inFlight.put(contentHash, scheduledGeneration);
		}

		try {
			decodeExecutor.execute(() -> decode(contentHash, scheduledGeneration));
		} catch (RuntimeException exception) {
			clearInFlight(contentHash, scheduledGeneration);
		}
		return null;
	}

	/**
	 * Returns the current resident handle for a logical key. This is a memory-only lookup and updates LRU access order.
	 */
	public H lookup(String logicalKey) {
		Objects.requireNonNull(logicalKey, "logicalKey");
		synchronized (lock) {
			final String contentHash = logicalBindings.get(logicalKey);
			if (contentHash == null) return null;
			final ResidentTexture<H> residentTexture = residents.get(contentHash);
			return residentTexture == null ? null : residentTexture.handle;
		}
	}

	/**
	 * Returns a resident handle directly by content hash without scheduling work.
	 */
	public H lookupContentHash(String contentHash) {
		Objects.requireNonNull(contentHash, "contentHash");
		synchronized (lock) {
			final ResidentTexture<H> residentTexture = residents.get(contentHash);
			return residentTexture == null ? null : residentTexture.handle;
		}
	}

	/**
	 * Removes one logical reference without evicting its content hash. The resident remains available for other keys
	 * and later revisions until normal LRU collection removes it.
	 */
	public boolean removeLogicalBinding(String logicalKey) {
		Objects.requireNonNull(logicalKey, "logicalKey");
		synchronized (lock) {
			return logicalBindings.remove(logicalKey) != null;
		}
	}

	/**
	 * Moves one logical reference without decode, registration, or a global cache reset.
	 */
	public boolean moveLogicalBinding(String oldLogicalKey, String newLogicalKey) {
		Objects.requireNonNull(oldLogicalKey, "oldLogicalKey");
		Objects.requireNonNull(newLogicalKey, "newLogicalKey");
		synchronized (lock) {
			final String contentHash = logicalBindings.remove(oldLogicalKey);
			if (contentHash == null) return false;
			logicalBindings.put(newLogicalKey, contentHash);
			return true;
		}
	}

	/**
	 * Replaces the visible/prewarm pin set and immediately applies the resident memory budget.
	 */
	public void replacePinnedContentHashes(Collection<String> contentHashes) {
		Objects.requireNonNull(contentHashes, "contentHashes");
		final List<H> evictedHandles;
		synchronized (lock) {
			if (closed) return;
			pinnedContentHashes.clear();
			for (final String contentHash : contentHashes) {
				if (contentHash != null) pinnedContentHashes.add(contentHash);
			}
			evictedHandles = evictToBudgetLocked();
		}
		closeAll(evictedHandles);
	}

	/**
	 * Registers at most {@code min(maximumUploads, 8)} decoded textures. The first registration attempt always runs
	 * before the soft deadline is checked; subsequent attempts stop when the elapsed budget is reached.
	 */
	public int drainUploads(int maximumUploads, long softBudgetNanoseconds) {
		final int uploadLimit = Math.min(MAX_UPLOADS_PER_DRAIN, Math.max(0, maximumUploads));
		if (uploadLimit == 0) return 0;
		final long startedAt = nanoClock.getAsLong();
		final long budget = Math.max(0, softBudgetNanoseconds);
		int attempts = 0;
		int successfulUploads = 0;

		while (attempts < uploadLimit) {
			if (attempts > 0 && nanoClock.getAsLong() - startedAt >= budget) break;
			final DecodedTexture<D> decodedTexture = decodedTextures.poll();
			if (decodedTexture == null) break;
			if (!isCurrent(decodedTexture.contentHash, decodedTexture.generation)) {
				closeQuietly(decodedTexture.decodedResource);
				continue;
			}

			attempts++;
			H handle = null;
			try {
				handle = Objects.requireNonNull(registrar.register(decodedTexture.contentHash, decodedTexture.decodedResource), "registrar returned null");
				if (!decodedTexture.decodedResource.isOwnershipTransferred()) throw new IllegalStateException("registrar did not take decoded image ownership");
			} catch (Exception exception) {
				clearInFlight(decodedTexture.contentHash, decodedTexture.generation);
				if (handle != null) closeQuietly(handle);
				closeQuietly(decodedTexture.decodedResource);
				continue;
			}

			final List<H> handlesToClose = new ArrayList<>();
			boolean installed = false;
			try {
				synchronized (lock) {
					if (isCurrentLocked(decodedTexture.contentHash, decodedTexture.generation) && !residents.containsKey(decodedTexture.contentHash)) {
						residents.put(decodedTexture.contentHash, new ResidentTexture<>(handle, decodedTexture.decodedResource.getSizeBytes()));
						inFlight.remove(decodedTexture.contentHash);
						addResidentBytesLocked(decodedTexture.decodedResource.getSizeBytes());
						handlesToClose.addAll(evictToBudgetLocked());
						installed = true;
					} else {
						removeInFlightLocked(decodedTexture.contentHash, decodedTexture.generation);
					}
				}
			} finally {
				if (!installed) handlesToClose.add(handle);
				closeAll(handlesToClose);
			}
			if (installed) successfulUploads++;
		}

		return successfulUploads;
	}

	/**
	 * Starts a new connection generation and releases all queued and resident resources. Running decodes observe the
	 * generation mismatch on completion and close their own image.
	 */
	public long reset() {
		return reset(false);
	}

	@Override
	public void close() {
		reset(true);
	}

	public long getGeneration() {
		synchronized (lock) {
			return generation;
		}
	}

	public int getInFlightCount() {
		synchronized (lock) {
			return inFlight.size();
		}
	}

	public int getDecodedQueueSize() {
		return decodedTextures.size();
	}

	public int getResidentCount() {
		synchronized (lock) {
			return residents.size();
		}
	}

	/**
	 * Returns the exact resident size when representable, otherwise {@link Long#MAX_VALUE}.
	 */
	public long getResidentBytes() {
		synchronized (lock) {
			return residentBytesOverflow ? Long.MAX_VALUE : residentBytes;
		}
	}

	private void decode(String contentHash, long scheduledGeneration) {
		DecodedResource<D> decodedResource = null;
		boolean queued = false;
		try {
			if (!isCurrent(contentHash, scheduledGeneration)) return;
			decodedResource = Objects.requireNonNull(decoder.decode(contentHash), "decoder returned null");
			final DecodedTexture<D> decodedTexture = new DecodedTexture<>(contentHash, scheduledGeneration, decodedResource);
			synchronized (lock) {
				if (isCurrentLocked(contentHash, scheduledGeneration)) {
					decodedTextures.add(decodedTexture);
					queued = true;
				} else {
					removeInFlightLocked(contentHash, scheduledGeneration);
				}
			}
		} catch (Exception exception) {
			clearInFlight(contentHash, scheduledGeneration);
		} finally {
			if (!queued && decodedResource != null) closeQuietly(decodedResource);
		}
	}

	private boolean isCurrent(String contentHash, long expectedGeneration) {
		synchronized (lock) {
			return isCurrentLocked(contentHash, expectedGeneration);
		}
	}

	private boolean isCurrentLocked(String contentHash, long expectedGeneration) {
		final Long pendingGeneration = inFlight.get(contentHash);
		return !closed && generation == expectedGeneration && pendingGeneration != null && pendingGeneration == expectedGeneration;
	}

	private void clearInFlight(String contentHash, long expectedGeneration) {
		synchronized (lock) {
			removeInFlightLocked(contentHash, expectedGeneration);
		}
	}

	private void removeInFlightLocked(String contentHash, long expectedGeneration) {
		final Long pendingGeneration = inFlight.get(contentHash);
		if (pendingGeneration != null && pendingGeneration == expectedGeneration) inFlight.remove(contentHash);
	}

	private long reset(boolean permanentlyClose) {
		final List<AutoCloseable> resourcesToClose = new ArrayList<>();
		synchronized (lock) {
			generation++;
			if (permanentlyClose) closed = true;
			logicalBindings.clear();
			pinnedContentHashes.clear();
			inFlight.clear();
			DecodedTexture<D> decodedTexture;
			while ((decodedTexture = decodedTextures.poll()) != null) resourcesToClose.add(decodedTexture.decodedResource);
			for (final ResidentTexture<H> residentTexture : residents.values()) resourcesToClose.add(residentTexture.handle);
			residents.clear();
			residentBytes = 0;
			residentBytesOverflow = false;
		}
		closeAll(resourcesToClose);
		return generation;
	}

	private void addResidentBytesLocked(long sizeBytes) {
		if (residentBytesOverflow) return;
		if (sizeBytes > Long.MAX_VALUE - residentBytes) {
			residentBytes = Long.MAX_VALUE;
			residentBytesOverflow = true;
		} else {
			residentBytes += sizeBytes;
		}
	}

	private List<H> evictToBudgetLocked() {
		final List<H> evictedHandles = new ArrayList<>();
		while (residentBytesOverflow || residentBytes > maximumResidentBytes) {
			boolean evicted = false;
			String eldestPinnedHash = null;
			ResidentTexture<H> eldestPinnedTexture = null;
			final Iterator<Map.Entry<String, ResidentTexture<H>>> iterator = residents.entrySet().iterator();
			while (iterator.hasNext()) {
				final Map.Entry<String, ResidentTexture<H>> entry = iterator.next();
				if (pinnedContentHashes.contains(entry.getKey())) {
					if (eldestPinnedHash == null) {
						eldestPinnedHash = entry.getKey();
						eldestPinnedTexture = entry.getValue();
					}
					continue;
				}
				evictedHandles.add(entry.getValue().handle);
				iterator.remove();
				evicted = true;
				break;
			}
			if (!evicted && eldestPinnedHash != null) {
				residents.remove(eldestPinnedHash);
				evictedHandles.add(eldestPinnedTexture.handle);
				evicted = true;
			}
			if (!evicted) break;
			recomputeResidentBytesLocked();
		}
		return evictedHandles;
	}

	private void recomputeResidentBytesLocked() {
		residentBytes = 0;
		residentBytesOverflow = false;
		for (final ResidentTexture<H> residentTexture : residents.values()) {
			addResidentBytesLocked(residentTexture.sizeBytes);
			if (residentBytesOverflow) return;
		}
	}

	private static void closeAll(Collection<? extends AutoCloseable> closeables) {
		for (final AutoCloseable closeable : closeables) closeQuietly(closeable);
	}

	private static void closeQuietly(AutoCloseable closeable) {
		try {
			closeable.close();
		} catch (Exception ignored) {
		}
	}

	@FunctionalInterface
	public interface Decoder<D extends AutoCloseable> {

		DecodedResource<D> decode(String contentHash) throws Exception;
	}

	/**
	 * The registrar must call {@link DecodedResource#transferOwnership()} exactly once before returning successfully.
	 * Before that call the cache owns the image and closes it if registration fails. After that call the registrar owns
	 * the image: its returned handle must close it on success, and the registrar must close it itself if it later throws.
	 * This explicit handoff prevents the cache and a partially-created GPU texture from both closing the same image.
	 */
	@FunctionalInterface
	public interface Registrar<D extends AutoCloseable, H extends AutoCloseable> {

		H register(String contentHash, DecodedResource<D> decodedResource) throws Exception;
	}

	public static final class DecodedResource<D extends AutoCloseable> implements AutoCloseable {

		private D image;
		private final long sizeBytes;
		private boolean ownershipTransferred;

		public DecodedResource(D image, long sizeBytes) {
			this.image = Objects.requireNonNull(image, "image");
			if (sizeBytes < 0) throw new IllegalArgumentException("sizeBytes must be non-negative");
			this.sizeBytes = sizeBytes;
		}

		public synchronized D transferOwnership() {
			if (image == null) throw new IllegalStateException("decoded image ownership is no longer available");
			final D transferredImage = image;
			image = null;
			ownershipTransferred = true;
			return transferredImage;
		}

		public long getSizeBytes() {
			return sizeBytes;
		}

		@Override
		public void close() {
			final D imageToClose;
			synchronized (this) {
				imageToClose = image;
				image = null;
			}
			if (imageToClose != null) closeQuietly(imageToClose);
		}

		private synchronized boolean isOwnershipTransferred() {
			return ownershipTransferred;
		}
	}

	private static final class DecodedTexture<D extends AutoCloseable> {

		private final String contentHash;
		private final long generation;
		private final DecodedResource<D> decodedResource;

		private DecodedTexture(String contentHash, long generation, DecodedResource<D> decodedResource) {
			this.contentHash = contentHash;
			this.generation = generation;
			this.decodedResource = decodedResource;
		}
	}

	private static final class ResidentTexture<H extends AutoCloseable> {

		private final H handle;
		private final long sizeBytes;

		private ResidentTexture(H handle, long sizeBytes) {
			this.handle = handle;
			this.sizeBytes = sizeBytes;
		}
	}
}
