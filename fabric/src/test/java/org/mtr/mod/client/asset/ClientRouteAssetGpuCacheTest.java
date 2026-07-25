package org.mtr.mod.client.asset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public final class ClientRouteAssetGpuCacheTest {

	@Test
	public void contentHashSharesOneDecodeAndRegistrationAcrossLogicalKeys() {
		final QueueExecutor executor = new QueueExecutor();
		final AtomicInteger decodeCount = new AtomicInteger();
		final AtomicInteger registrationCount = new AtomicInteger();
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = new ClientRouteAssetGpuCache<>(
				executor,
				hash -> {
					decodeCount.incrementAndGet();
					return decoded(hash, 16);
				},
				(hash, ownership) -> {
					registrationCount.incrementAndGet();
					return new FakeHandle(hash, ownership.transferOwnership());
				},
				() -> 0,
				64
		);

		Assertions.assertNull(cache.request("logical-a", "shared-hash"));
		Assertions.assertNull(cache.request("logical-b", "shared-hash"));
		Assertions.assertEquals(1, executor.size());
		Assertions.assertEquals(0, decodeCount.get());
		Assertions.assertEquals(0, registrationCount.get());

		executor.runNext();
		Assertions.assertEquals(1, decodeCount.get());
		Assertions.assertEquals(1, cache.getDecodedQueueSize());
		Assertions.assertEquals(1, cache.drainUploads(8, 2_000_000));
		Assertions.assertEquals(1, registrationCount.get());

		final FakeHandle first = cache.lookup("logical-a");
		final FakeHandle second = cache.lookup("logical-b");
		Assertions.assertNotNull(first);
		Assertions.assertSame(first, second);
		Assertions.assertSame(first, cache.request("logical-c", "shared-hash"));
		Assertions.assertEquals(0, executor.size());
	}

	@Test
	public void logicalMoveAndRemovalPreserveSharedResidentWithoutNewDecode() {
		final QueueExecutor executor = new QueueExecutor();
		final AtomicInteger decodes = new AtomicInteger();
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = new ClientRouteAssetGpuCache<>(
				executor,
				hash -> {
					decodes.incrementAndGet();
					return decoded(hash, 4);
				},
				(hash, ownership) -> new FakeHandle(hash, ownership.transferOwnership()),
				() -> 0,
				64
		);

		cache.request("old-key", "shared-hash");
		executor.runNext();
		cache.drainUploads(1, Long.MAX_VALUE);
		final FakeHandle handle = cache.request("sibling-key", "shared-hash");
		Assertions.assertNotNull(handle);

		Assertions.assertTrue(cache.moveLogicalBinding("old-key", "new-key"));
		Assertions.assertNull(cache.lookup("old-key"));
		Assertions.assertSame(handle, cache.lookup("new-key"));
		Assertions.assertSame(handle, cache.lookup("sibling-key"));
		Assertions.assertTrue(cache.removeLogicalBinding("sibling-key"));
		Assertions.assertNull(cache.lookup("sibling-key"));
		Assertions.assertFalse(cache.removeLogicalBinding("missing-key"));
		Assertions.assertFalse(cache.moveLogicalBinding("missing-key", "unused-key"));
		Assertions.assertEquals(1, decodes.get());
		Assertions.assertEquals(0, executor.size());
		Assertions.assertEquals(1, cache.getResidentCount(), "incremental binding changes leave the hash resident for LRU reuse");
		Assertions.assertEquals(0, handle.closeCount);
	}

	@Test
	public void decoderRunsOnInjectedExecutorAndRegistrarRunsOnDrainCaller() throws Exception {
		final QueueExecutor executor = new QueueExecutor();
		final AtomicReference<Thread> decodeThread = new AtomicReference<>();
		final AtomicReference<Thread> registrationThread = new AtomicReference<>();
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = new ClientRouteAssetGpuCache<>(
				executor,
				hash -> {
					decodeThread.set(Thread.currentThread());
					return decoded(hash, 4);
				},
				(hash, ownership) -> {
					registrationThread.set(Thread.currentThread());
					return new FakeHandle(hash, ownership.transferOwnership());
				},
				() -> 0,
				64
		);

		cache.request("logical", "threaded-hash");
		Assertions.assertNull(decodeThread.get());
		Assertions.assertNull(registrationThread.get());

		final Thread workerThread = new Thread(executor::runNext, "route-asset-test-decode");
		workerThread.start();
		workerThread.join();
		Assertions.assertSame(workerThread, decodeThread.get());
		Assertions.assertNull(registrationThread.get());

		final AtomicInteger uploaded = new AtomicInteger();
		final Thread renderThread = new Thread(() -> uploaded.set(cache.drainUploads(8, Long.MAX_VALUE)), "route-asset-test-render");
		renderThread.start();
		renderThread.join();
		Assertions.assertEquals(1, uploaded.get());
		Assertions.assertSame(renderThread, registrationThread.get());
		cache.reset();
	}

	@Test
	public void queuedDecodeFromAnOldGenerationIsRejectedBeforeDecoderIo() {
		final QueueExecutor executor = new QueueExecutor();
		final AtomicInteger decodes = new AtomicInteger();
		final AtomicInteger registrations = new AtomicInteger();
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = new ClientRouteAssetGpuCache<>(
				executor,
				hash -> {
					decodes.incrementAndGet();
					return decoded(hash, 4);
				},
				(hash, ownership) -> {
					registrations.incrementAndGet();
					return new FakeHandle(hash, ownership.transferOwnership());
				},
				() -> 0,
				64
		);

		cache.request("logical", "late-hash");
		final long oldGeneration = cache.getGeneration();
		cache.reset();
		Assertions.assertNotEquals(oldGeneration, cache.getGeneration());
		executor.runNext();

		Assertions.assertEquals(0, decodes.get());
		Assertions.assertEquals(0, registrations.get());
		Assertions.assertEquals(0, cache.drainUploads(8, Long.MAX_VALUE));
		Assertions.assertEquals(0, cache.getInFlightCount());
	}

	@Test
	public void rejectedDecodeSubmissionClearsInFlightForRetry() {
		final AtomicInteger submissions = new AtomicInteger();
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = new ClientRouteAssetGpuCache<>(
				runnable -> {
					submissions.incrementAndGet();
					throw new RejectedExecutionException("full");
				},
				hash -> decoded(hash, 4),
				(hash, ownership) -> new FakeHandle(hash, ownership.transferOwnership()),
				() -> 0,
				64
		);

		Assertions.assertNull(cache.request("logical", "hash"));
		Assertions.assertEquals(0, cache.getInFlightCount());
		Assertions.assertNull(cache.request("logical", "hash"));
		Assertions.assertEquals(2, submissions.get(), "rejection must not leave the hash permanently in flight");
		Assertions.assertEquals(0, cache.getInFlightCount());
	}

	@Test
	public void resetClosesQueuedImagesAndResidentHandlesExactlyOnce() {
		final QueueExecutor executor = new QueueExecutor();
		final Map<String, FakeImage> images = new HashMap<>();
		final Map<String, FakeHandle> handles = new HashMap<>();
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = new ClientRouteAssetGpuCache<>(
				executor,
				hash -> {
					final FakeImage image = new FakeImage(hash);
					images.put(hash, image);
					return new ClientRouteAssetGpuCache.DecodedResource<>(image, 8);
				},
				(hash, ownership) -> {
					final FakeImage image = ownership.transferOwnership();
					final FakeHandle handle = new FakeHandle(hash, image);
					handles.put(hash, handle);
					return handle;
				},
				() -> 0,
				64
		);

		cache.request("resident-key", "resident-hash");
		executor.runNext();
		Assertions.assertEquals(1, cache.drainUploads(1, Long.MAX_VALUE));
		cache.request("queued-key", "queued-hash");
		executor.runNext();

		cache.reset();
		Assertions.assertEquals(1, handles.get("resident-hash").closeCount);
		Assertions.assertEquals(1, images.get("resident-hash").closeCount, "the resident handle owns its decoded image");
		Assertions.assertEquals(1, images.get("queued-hash").closeCount);
		Assertions.assertEquals(0, cache.getResidentCount());
		Assertions.assertEquals(0, cache.getDecodedQueueSize());

		cache.reset();
		Assertions.assertEquals(1, handles.get("resident-hash").closeCount);
		Assertions.assertEquals(1, images.get("resident-hash").closeCount);
		Assertions.assertEquals(1, images.get("queued-hash").closeCount);
	}

	@Test
	public void renderDrainHonorsSuppliedAndAbsoluteEightUploadCaps() {
		final QueueExecutor executor = new QueueExecutor();
		final AtomicInteger registrations = new AtomicInteger();
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = cache(executor, registrations, () -> 0, 1024, 1);
		for (int index = 0; index < 12; index++) {
			cache.request("logical-" + index, "hash-" + index);
		}
		executor.runAll();

		Assertions.assertEquals(3, cache.drainUploads(3, Long.MAX_VALUE));
		Assertions.assertEquals(3, registrations.get());
		Assertions.assertEquals(8, cache.drainUploads(99, Long.MAX_VALUE));
		Assertions.assertEquals(11, registrations.get());
		Assertions.assertEquals(1, cache.drainUploads(99, Long.MAX_VALUE));
		Assertions.assertEquals(12, registrations.get());
	}

	@Test
	public void renderDrainPerformsFirstUploadBeforeCheckingSoftDeadline() {
		final QueueExecutor executor = new QueueExecutor();
		final AtomicInteger registrations = new AtomicInteger();
		final SequenceClock clock = new SequenceClock(10, 2_000_011);
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = cache(executor, registrations, clock, 1024, 1);
		for (int index = 0; index < 3; index++) {
			cache.request("logical-" + index, "hash-" + index);
		}
		executor.runAll();

		Assertions.assertEquals(1, cache.drainUploads(8, 2_000_000));
		Assertions.assertEquals(1, registrations.get());
		Assertions.assertEquals(2, cache.getDecodedQueueSize());
	}

	@Test
	public void accessOrderLruPreservesPinsAndStaysWithinBudget() {
		final QueueExecutor executor = new QueueExecutor();
		final Map<String, FakeHandle> handles = new HashMap<>();
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = new ClientRouteAssetGpuCache<>(
				executor,
				hash -> decoded(hash, 10),
				(hash, ownership) -> {
					final FakeImage image = ownership.transferOwnership();
					final FakeHandle handle = new FakeHandle(hash, image);
					handles.put(hash, handle);
					return handle;
				},
				() -> 0,
				20
		);

		requestAndDecode(cache, executor, "key-a", "hash-a");
		requestAndDecode(cache, executor, "key-b", "hash-b");
		Assertions.assertEquals(2, cache.drainUploads(8, Long.MAX_VALUE));
		cache.replacePinnedContentHashes(Set.of("hash-a"));
		requestAndDecode(cache, executor, "key-c", "hash-c");
		Assertions.assertEquals(1, cache.drainUploads(8, Long.MAX_VALUE));

		Assertions.assertSame(handles.get("hash-a"), cache.lookup("key-a"));
		Assertions.assertNull(cache.lookup("key-b"));
		Assertions.assertSame(handles.get("hash-c"), cache.lookup("key-c"));
		Assertions.assertEquals(0, handles.get("hash-a").closeCount);
		Assertions.assertEquals(1, handles.get("hash-b").closeCount);
		Assertions.assertTrue(cache.getResidentBytes() <= 20);

		cache.replacePinnedContentHashes(Set.of());
		requestAndDecode(cache, executor, "key-d", "hash-d");
		cache.drainUploads(8, Long.MAX_VALUE);
		Assertions.assertTrue(cache.getResidentBytes() <= 20);
		Assertions.assertEquals(2, cache.getResidentCount());
		Assertions.assertEquals(1, handles.get("hash-a").closeCount, "hash-a became the least-recently used unpinned entry");
		Assertions.assertEquals(0, handles.get("hash-c").closeCount);
		Assertions.assertEquals(0, handles.get("hash-d").closeCount);
	}

	@Test
	public void pinsArePreferentialButCannotExceedTheHardByteBudget() {
		final QueueExecutor executor = new QueueExecutor();
		final Map<String, FakeHandle> handles = new HashMap<>();
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = new ClientRouteAssetGpuCache<>(
				executor,
				hash -> decoded(hash, 10),
				(hash, ownership) -> {
					final FakeHandle handle = new FakeHandle(hash, ownership.transferOwnership());
					handles.put(hash, handle);
					return handle;
				},
				() -> 0,
				20
		);
		cache.replacePinnedContentHashes(Set.of("hash-a", "hash-b", "hash-c"));
		requestAndDecode(cache, executor, "key-a", "hash-a");
		requestAndDecode(cache, executor, "key-b", "hash-b");
		requestAndDecode(cache, executor, "key-c", "hash-c");

		cache.drainUploads(8, Long.MAX_VALUE);

		Assertions.assertEquals(20, cache.getResidentBytes());
		Assertions.assertEquals(2, cache.getResidentCount());
		Assertions.assertEquals(1, handles.values().stream().mapToInt(handle -> handle.closeCount).sum(), "the eldest pin must yield when every resident is pinned above the hard limit");
	}

	@Test
	public void residentSizeAccountingCannotWrapAroundLongMaximum() {
		final QueueExecutor executor = new QueueExecutor();
		final Map<String, FakeHandle> handles = new HashMap<>();
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = new ClientRouteAssetGpuCache<>(
				executor,
				hash -> decoded(hash, hash.equals("huge") ? Long.MAX_VALUE : 1),
				(hash, ownership) -> {
					final FakeImage image = ownership.transferOwnership();
					final FakeHandle handle = new FakeHandle(hash, image);
					handles.put(hash, handle);
					return handle;
				},
				() -> 0,
				Long.MAX_VALUE
		);

		requestAndDecode(cache, executor, "huge-key", "huge");
		cache.drainUploads(1, Long.MAX_VALUE);
		cache.replacePinnedContentHashes(Set.of("huge"));
		requestAndDecode(cache, executor, "small-key", "small");
		cache.drainUploads(1, Long.MAX_VALUE);

		Assertions.assertSame(handles.get("huge"), cache.lookup("huge-key"));
		Assertions.assertNull(cache.lookup("small-key"));
		Assertions.assertEquals(1, handles.get("small").closeCount);
		Assertions.assertEquals(Long.MAX_VALUE, cache.getResidentBytes());
	}

	@Test
	public void decodeAndRegistrationFailuresClearInFlightForRetry() {
		final QueueExecutor executor = new QueueExecutor();
		final AtomicInteger decodeAttempts = new AtomicInteger();
		final AtomicInteger registrationAttempts = new AtomicInteger();
		final FakeImage[] rejectedImage = new FakeImage[1];
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = new ClientRouteAssetGpuCache<>(
				executor,
				hash -> {
					if (decodeAttempts.incrementAndGet() == 1) throw new IllegalStateException("decode failure");
					final FakeImage image = new FakeImage(hash + "-" + decodeAttempts.get());
					rejectedImage[0] = image;
					return new ClientRouteAssetGpuCache.DecodedResource<>(image, 4);
				},
				(hash, ownership) -> {
					if (registrationAttempts.incrementAndGet() == 1) {
						throw new IllegalStateException("registration failure");
					}
					return new FakeHandle(hash, ownership.transferOwnership());
				},
				() -> 0,
				64
		);

		cache.request("logical", "retry-hash");
		executor.runNext();
		Assertions.assertEquals(0, cache.getInFlightCount());
		cache.request("logical", "retry-hash");
		executor.runNext();
		Assertions.assertEquals(0, cache.drainUploads(1, Long.MAX_VALUE));
		Assertions.assertEquals(1, rejectedImage[0].closeCount);
		Assertions.assertEquals(0, cache.getInFlightCount());

		cache.request("logical", "retry-hash");
		executor.runNext();
		Assertions.assertEquals(1, cache.drainUploads(1, Long.MAX_VALUE));
		Assertions.assertNotNull(cache.lookup("logical"));
		Assertions.assertEquals(3, decodeAttempts.get());
		Assertions.assertEquals(2, registrationAttempts.get());
	}

	@Test
	public void registrarThatThrowsAfterOwnershipTransferClosesWithoutCoreDoubleClose() {
		final QueueExecutor executor = new QueueExecutor();
		final AtomicReference<FakeImage> transferredImage = new AtomicReference<>();
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = new ClientRouteAssetGpuCache<>(
				executor,
				hash -> decoded(hash, 4),
				(hash, ownership) -> {
					final FakeImage image = ownership.transferOwnership();
					transferredImage.set(image);
					image.close();
					throw new IllegalStateException("failed after taking ownership");
				},
				() -> 0,
				64
		);

		cache.request("logical", "ownership-hash");
		executor.runNext();
		Assertions.assertEquals(0, cache.drainUploads(1, Long.MAX_VALUE));
		Assertions.assertEquals(1, transferredImage.get().closeCount);
		Assertions.assertEquals(0, cache.getInFlightCount());
		cache.reset();
		Assertions.assertEquals(1, transferredImage.get().closeCount);
	}

	@Test
	public void lookupAndEmptyDrainDoNotCrossDecodeOrRegistrationBoundaries() {
		final AtomicInteger decodes = new AtomicInteger();
		final AtomicInteger registrations = new AtomicInteger();
		final ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache = new ClientRouteAssetGpuCache<>(
				Runnable::run,
				hash -> {
					decodes.incrementAndGet();
					return decoded(hash, 4);
				},
				(hash, ownership) -> {
					registrations.incrementAndGet();
					return new FakeHandle(hash, ownership.transferOwnership());
				},
				() -> 0,
				64
		);

		Assertions.assertNull(cache.lookup("absent"));
		Assertions.assertEquals(0, cache.drainUploads(8, Long.MAX_VALUE));
		Assertions.assertEquals(0, decodes.get());
		Assertions.assertEquals(0, registrations.get());
	}

	private static ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache(QueueExecutor executor, AtomicInteger registrations, LongSupplier clock, long maximumBytes, long imageBytes) {
		return new ClientRouteAssetGpuCache<>(
				executor,
				hash -> decoded(hash, imageBytes),
				(hash, ownership) -> {
					registrations.incrementAndGet();
					return new FakeHandle(hash, ownership.transferOwnership());
				},
				clock,
				maximumBytes
		);
	}

	private static ClientRouteAssetGpuCache.DecodedResource<FakeImage> decoded(String hash, long sizeBytes) {
		return new ClientRouteAssetGpuCache.DecodedResource<>(new FakeImage(hash), sizeBytes);
	}

	private static void requestAndDecode(ClientRouteAssetGpuCache<FakeImage, FakeHandle> cache, QueueExecutor executor, String logicalKey, String hash) {
		Assertions.assertNull(cache.request(logicalKey, hash));
		executor.runNext();
	}

	private static final class FakeImage implements AutoCloseable {

		private final String name;
		private int closeCount;

		private FakeImage(String name) {
			this.name = name;
		}

		@Override
		public void close() {
			closeCount++;
		}
	}

	private static final class FakeHandle implements AutoCloseable {

		private final String name;
		private final FakeImage ownedImage;
		private int closeCount;

		private FakeHandle(String name, FakeImage ownedImage) {
			this.name = name;
			this.ownedImage = ownedImage;
		}

		@Override
		public void close() {
			closeCount++;
			ownedImage.close();
		}
	}

	private static final class QueueExecutor implements Executor {

		private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void execute(Runnable command) {
			tasks.addLast(command);
		}

		private int size() {
			return tasks.size();
		}

		private void runNext() {
			final Runnable task = tasks.pollFirst();
			if (task == null) throw new AssertionError("Expected a queued task");
			task.run();
		}

		private void runAll() {
			while (!tasks.isEmpty()) runNext();
		}
	}

	private static final class SequenceClock implements LongSupplier {

		private final long[] values;
		private int index;

		private SequenceClock(long... values) {
			this.values = values;
		}

		@Override
		public long getAsLong() {
			if (index >= values.length) return values[values.length - 1];
			return values[index++];
		}
	}
}
