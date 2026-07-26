package org.mtr.mod.client.asset;

import org.mtr.libraries.okhttp3.Call;
import org.mtr.libraries.okhttp3.Dns;
import org.mtr.libraries.okhttp3.OkHttpClient;
import org.mtr.libraries.okhttp3.Request;
import org.mtr.libraries.okhttp3.Response;
import org.mtr.libraries.okhttp3.ResponseBody;
import org.mtr.mod.packet.PacketRouteAssetChunkRequest;
import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetManifest;
import org.mtr.mod.route.RouteAssetManifestCodec;
import org.mtr.mod.route.RouteAssetManifestDiff;
import org.mtr.mod.route.RouteAssetNegotiation;
import org.mtr.mod.route.RouteAssetProtocol;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public final class ClientRouteAssetDownloader {

	private final ClientRouteAssetDiskCache diskCache;
	private final ExecutorService executor;
	private final Transport transport;
	private final Runnable beforeManifestCommit;
	private final PacketFallbackTransport packetFallbackTransport;
	private final int maximumConcurrency;
	private final Set<ActiveTask> activeTasks = ConcurrentHashMap.newKeySet();
	private final Set<Future<?>> activeWorkerFutures = ConcurrentHashMap.newKeySet();
	private final ConcurrentHashMap<String, CompletableFuture<byte[]>> downloadsByHash = new ConcurrentHashMap<>();
	private final AtomicLong cancellationEpoch = new AtomicLong();

	public ClientRouteAssetDownloader(ClientRouteAssetDiskCache diskCache, ExecutorService executor) {
		this(diskCache, executor, new OkHttpTransport(), () -> { }, PacketFallbackTransport.DISABLED, inferConcurrency(executor));
	}

	public ClientRouteAssetDownloader(ClientRouteAssetDiskCache diskCache, ExecutorService executor, Transport transport) {
		this(diskCache, executor, transport, () -> { }, PacketFallbackTransport.DISABLED, inferConcurrency(executor));
	}

	public ClientRouteAssetDownloader(ClientRouteAssetDiskCache diskCache, ExecutorService executor, Runnable beforeManifestCommit) {
		this(diskCache, executor, new OkHttpTransport(), beforeManifestCommit, PacketFallbackTransport.DISABLED, inferConcurrency(executor));
	}

	public ClientRouteAssetDownloader(ClientRouteAssetDiskCache diskCache, ExecutorService executor, Transport transport, Runnable beforeManifestCommit) {
		this(diskCache, executor, transport, beforeManifestCommit, PacketFallbackTransport.DISABLED, inferConcurrency(executor));
	}

	public ClientRouteAssetDownloader(ClientRouteAssetDiskCache diskCache, ExecutorService executor, int maximumConcurrency) {
		this(diskCache, executor, new OkHttpTransport(), () -> { }, PacketFallbackTransport.DISABLED, maximumConcurrency);
	}

	public ClientRouteAssetDownloader(ClientRouteAssetDiskCache diskCache, ExecutorService executor, Transport transport, Runnable beforeManifestCommit, int maximumConcurrency) {
		this(diskCache, executor, transport, beforeManifestCommit, PacketFallbackTransport.DISABLED, maximumConcurrency);
	}

	private ClientRouteAssetDownloader(ClientRouteAssetDiskCache diskCache, ExecutorService executor, Transport transport, Runnable beforeManifestCommit, PacketFallbackTransport packetFallbackTransport, int maximumConcurrency) {
		this.diskCache = Objects.requireNonNull(diskCache, "diskCache");
		this.executor = Objects.requireNonNull(executor, "executor");
		this.transport = Objects.requireNonNull(transport, "transport");
		this.beforeManifestCommit = Objects.requireNonNull(beforeManifestCommit, "beforeManifestCommit");
		this.packetFallbackTransport = Objects.requireNonNull(packetFallbackTransport, "packetFallbackTransport");
		if (maximumConcurrency < 1 || maximumConcurrency > 8) throw new IllegalArgumentException("Invalid route asset download concurrency");
		this.maximumConcurrency = maximumConcurrency;
	}

	public static ClientRouteAssetDownloader withPacketFallback(ClientRouteAssetDiskCache diskCache, ExecutorService executor, PacketFallbackTransport packetFallbackTransport, int maximumConcurrency) {
		return new ClientRouteAssetDownloader(diskCache, executor, new OkHttpTransport(), () -> { }, packetFallbackTransport, maximumConcurrency);
	}

	public static ClientRouteAssetDownloader withPacketFallback(ClientRouteAssetDiskCache diskCache, ExecutorService executor, Transport transport, PacketFallbackTransport packetFallbackTransport, int maximumConcurrency) {
		return new ClientRouteAssetDownloader(diskCache, executor, transport, () -> { }, packetFallbackTransport, maximumConcurrency);
	}

	public CompletableFuture<byte[]> download(DownloadRequest request) {
		Objects.requireNonNull(request, "request");
		return downloadsByHash.computeIfAbsent(request.expectedHash, ignored -> submit(request));
	}

	public CompletableFuture<Path> downloadPng(DownloadRequest request) {
		return download(request).thenApply(bytes -> {
			try {
				diskCache.admitPng(request.expectedHash, bytes);
				return diskCache.pathForPng(request.expectedHash);
			} catch (IOException exception) {
				throw new java.util.concurrent.CompletionException(exception);
			}
		});
	}

	public CompletableFuture<SyncResult> synchronize(ClientRouteAssetManager.DocumentRequest request, int resolution, String language, long configuredRevisionMaximumBytes, long cacheMaximumBytes, long expectedCacheEpoch, BooleanSupplier generationCurrent, ProgressListener progressListener) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(language, "language");
		Objects.requireNonNull(generationCurrent, "generationCurrent");
		Objects.requireNonNull(progressListener, "progressListener");
		final CompletableFuture<SyncResult> completion = new CompletableFuture<>();
		final ActiveTask task = new ActiveTask(completion);
		activeTasks.add(task);
		final long expectedCancellationEpoch = cancellationEpoch.get();
		try {
			task.future = executor.submit(() -> {
				try {
					completion.complete(synchronizeNow(request, resolution, language, configuredRevisionMaximumBytes, cacheMaximumBytes, expectedCacheEpoch, generationCurrent, progressListener, expectedCancellationEpoch));
				} catch (Throwable throwable) {
					completion.completeExceptionally(throwable);
				} finally {
					activeTasks.remove(task);
				}
			});
		} catch (RuntimeException exception) {
			activeTasks.remove(task);
			completion.completeExceptionally(exception);
		}
		return completion;
	}

	public void cancelAll() {
		cancellationEpoch.incrementAndGet();
		transport.cancelAll();
		for (final ActiveTask task : activeTasks) task.cancel();
		for (final Future<?> future : activeWorkerFutures) future.cancel(true);
		downloadsByHash.clear();
	}

	private CompletableFuture<byte[]> submit(DownloadRequest request) {
		final CompletableFuture<byte[]> completion = new CompletableFuture<>();
		final ActiveTask task = new ActiveTask(completion);
		activeTasks.add(task);
		final long expectedCancellationEpoch = cancellationEpoch.get();
		try {
			task.future = executor.submit(() -> {
				try {
					completion.complete(downloadWithRetries(request, expectedCancellationEpoch));
				} catch (Throwable throwable) {
					completion.completeExceptionally(throwable);
				} finally {
					activeTasks.remove(task);
					downloadsByHash.remove(request.expectedHash, completion);
				}
			});
		} catch (RuntimeException exception) {
			activeTasks.remove(task);
			downloadsByHash.remove(request.expectedHash, completion);
			completion.completeExceptionally(exception);
		}
		return completion;
	}

	private byte[] downloadWithRetries(DownloadRequest request, long expectedCancellationEpoch) throws IOException {
		IOException lastFailure = null;
		for (int attempt = 0; attempt <= RouteAssetProtocol.MAX_HTTP_RETRIES; attempt++) {
			checkCurrent(request, expectedCancellationEpoch);
			try {
				return downloadOnce(request, expectedCancellationEpoch);
			} catch (IOException exception) {
				lastFailure = exception;
			}
		}
		return downloadWithPacketFallback(request, expectedCancellationEpoch, Objects.requireNonNull(lastFailure));
	}

	private byte[] downloadWithPacketFallback(DownloadRequest request, long expectedCancellationEpoch, IOException httpFailure) throws IOException {
		if (!packetFallbackTransport.isAvailable() || request.expectedLength > RouteAssetProtocol.MAX_PACKET_FALLBACK_OBJECT_BYTES) throw httpFailure;
		checkCurrent(request, expectedCancellationEpoch);
		final CompletableFuture<byte[]> fallback;
		try {
			fallback = Objects.requireNonNull(packetFallbackTransport.request(request), "packetFallbackTransport returned null");
		} catch (RuntimeException exception) {
			throw new IOException("Route asset packet fallback request failed", exception);
		}
		final byte[] bytes;
		try {
			bytes = fallback.get(RouteAssetProtocol.PACKET_FALLBACK_EXPIRY_MILLIS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			fallback.cancel(true);
			throw new IOException("Route asset packet fallback was interrupted", exception);
		} catch (TimeoutException exception) {
			fallback.cancel(true);
			throw new IOException("Route asset packet fallback timed out", exception);
		} catch (ExecutionException exception) {
			final Throwable cause = exception.getCause();
			if (cause instanceof IOException) throw (IOException) cause;
			throw new IOException("Route asset packet fallback failed", cause);
		}
		checkCurrent(request, expectedCancellationEpoch);
		if (bytes == null || bytes.length <= 0 || bytes.length > RouteAssetProtocol.MAX_PACKET_FALLBACK_OBJECT_BYTES || bytes.length > request.maximumBytes || request.expectedLength >= 0 && bytes.length != request.expectedLength) throw new IOException("Route asset packet fallback object length is invalid");
		if (!RouteAssetHash.sha256(bytes).equals(request.expectedHash)) throw new IOException("Route asset packet fallback SHA-256 mismatch");
		request.budget.consume(bytes.length);
		return bytes;
	}

	private SyncResult synchronizeNow(ClientRouteAssetManager.DocumentRequest request, int resolution, String language, long configuredRevisionMaximumBytes, long cacheMaximumBytes, long expectedCacheEpoch, BooleanSupplier generationCurrent, ProgressListener progressListener, long expectedCancellationEpoch) throws IOException {
		final long revisionMaximumBytes = Math.min(RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, configuredRevisionMaximumBytes);
		if (revisionMaximumBytes < 0 || request.getPayload().getDocumentLength() > revisionMaximumBytes) throw new IOException("Route asset revision exceeds its aggregate byte limit");
		final DownloadBudget budget = new DownloadBudget(revisionMaximumBytes);
		final DownloadRequest documentRequest = new DownloadRequest(
				request.getGeneration(),
				request.getUrlPolicy(),
				request.getDocumentTarget(),
				request.getPayload().getDocumentHash(),
				request.getPayload().getDocumentLength(),
				RouteAssetProtocol.MAX_MANIFEST_BYTES,
				generationCurrent,
				budget
		);
		final byte[] document = downloadWithRetries(documentRequest, expectedCancellationEpoch);
		checkCurrent(documentRequest, expectedCancellationEpoch);

		final RouteAssetManifest previous = diskCache.loadManifest(request.getPayload().getServerId()).orElse(null);
		final RouteAssetManifest next;
		try {
			if (request.getPayload().getMode() == RouteAssetNegotiation.Mode.SNAPSHOT) {
				next = RouteAssetManifestCodec.decodeManifest(document);
			} else if (request.getPayload().getMode() == RouteAssetNegotiation.Mode.DIFF) {
				if (previous == null) throw new IOException("Route asset diff has no persisted parent");
				final RouteAssetManifestDiff diff = RouteAssetManifestCodec.decodeDiff(document);
				if (!diff.getParentRevision().equals(previous.getRevision())) throw new IOException("Route asset diff parent mismatch");
				next = diff.apply(previous);
			} else {
				throw new IOException("Route asset document mode is not downloadable");
			}
		} catch (IllegalArgumentException exception) {
			throw new IOException("Invalid route asset manifest document", exception);
		}
		if (next.getRendererVersion() != request.getPayload().getRendererVersion() || !next.getRevision().equals(request.getPayload().getAuthoritativeRevision())) {
			throw new IOException("Route asset manifest metadata mismatch");
		}

		final Set<String> activeHashes = activeHashes(next, resolution, language);
		if (activeHashes.isEmpty()) throw new IOException("Route asset manifest has no entries for the active variant");
		checkCurrent(documentRequest, expectedCancellationEpoch);
		if (diskCache.getSessionEpoch() != expectedCacheEpoch) throw new IOException("Route asset cache session changed before promotion");
		diskCache.initialize();
		for (final String hash : activeHashes) {
			checkCurrent(documentRequest, expectedCancellationEpoch);
			if (diskCache.getSessionEpoch() != expectedCacheEpoch) throw new IOException("Route asset cache session changed during promotion");
			diskCache.promotePngFromPriorVersions(hash);
		}
		checkCurrent(documentRequest, expectedCancellationEpoch);
		if (diskCache.getSessionEpoch() != expectedCacheEpoch) throw new IOException("Route asset cache session changed during promotion");
		final Set<String> previousActivePins = previous == null ? Collections.emptySet() : activeHashes(previous, resolution, language);
		final Set<String> pins = new HashSet<>(previousActivePins);
		pins.addAll(activeHashes);
		final ClientRouteAssetDiskCache.PruneResult pruneResult = diskCache.prune(cacheMaximumBytes, pins, expectedCacheEpoch);
		checkCurrent(documentRequest, expectedCancellationEpoch);
		if (diskCache.getSessionEpoch() != expectedCacheEpoch) throw new IOException("Route asset cache session changed during synchronization");
		final Set<String> previousHashes = new HashSet<>();
		if (previous != null) previous.getEntries().values().forEach(entry -> previousHashes.add(entry.getHash()));
		final Set<String> introducedActiveHashes = new LinkedHashSet<>(activeHashes);
		if (request.getPayload().getMode() == RouteAssetNegotiation.Mode.DIFF) introducedActiveHashes.removeAll(previousHashes);
		for (final String hash : activeHashes) if (diskCache.findPng(hash).isEmpty()) introducedActiveHashes.add(hash);
		final long remainingCacheBytes = Math.max(0, cacheMaximumBytes - pruneResult.getRemainingBytes());
		final DownloadBudget admissionBudget = new DownloadBudget(Math.min(RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES, remainingCacheBytes));
		final List<String> missingIntroducedHashes = new ArrayList<>();
		for (final String hash : introducedActiveHashes) {
			if (diskCache.findPng(hash).isEmpty()) missingIntroducedHashes.add(hash);
		}
		progressListener.planned(missingIntroducedHashes.size());
		final int completedObjects = downloadPngBatch(request, missingIntroducedHashes, budget, admissionBudget, generationCurrent, progressListener, expectedCancellationEpoch);
		for (final String hash : activeHashes) {
			if (diskCache.findPng(hash).isEmpty()) throw new IOException("Route asset object validation failed after synchronization");
		}
		checkCurrent(documentRequest, expectedCancellationEpoch);
		if (!diskCache.commitManifest(request.getMultiplayerAddress(), request.getPayload().getServerId(), next, expectedCacheEpoch, beforeManifestCommit)) throw new IOException("Route asset cache session changed before manifest commit");
		return new SyncResult(next, activeHashes, completedObjects, budget.getConsumedBytes());
	}

	private int downloadPngBatch(ClientRouteAssetManager.DocumentRequest request, List<String> hashes, DownloadBudget transferBudget, DownloadBudget admissionBudget, BooleanSupplier generationCurrent, ProgressListener progressListener, long expectedCancellationEpoch) throws IOException {
		if (hashes.isEmpty()) return 0;
		final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>(hashes);
		final AtomicReference<Throwable> failure = new AtomicReference<>();
		final AtomicInteger completed = new AtomicInteger();
		final BooleanSupplier batchCurrent = () -> failure.get() == null && generationCurrent.getAsBoolean();
		final Runnable worker = () -> {
			while (failure.get() == null) {
				final String hash = queue.poll();
				if (hash == null) return;
				try {
					final String path = "v" + request.getPayload().getRendererVersion() + '/' + hash.substring(0, 2) + '/' + hash + ".png";
					final DownloadRequest pngRequest = new DownloadRequest(
							request.getGeneration(),
							request.getUrlPolicy(),
							request.getUrlPolicy().resolve(path),
							hash,
							-1,
							RouteAssetProtocol.MAX_PNG_BYTES,
							batchCurrent,
							transferBudget
					);
					final byte[] png = downloadWithRetries(pngRequest, expectedCancellationEpoch);
					admissionBudget.consume(png.length);
					diskCache.admitPng(hash, png);
					progressListener.completed(completed.incrementAndGet(), hashes.size());
				} catch (Throwable throwable) {
					failure.compareAndSet(null, throwable);
					return;
				}
			}
		};

		final int workerCount = Math.min(maximumConcurrency, hashes.size());
		final List<Future<?>> submittedWorkers = new ArrayList<>();
		try {
			for (int index = 1; index < workerCount; index++) {
				final Future<?> future = executor.submit(worker);
				submittedWorkers.add(future);
				activeWorkerFutures.add(future);
			}
			worker.run();
			for (final Future<?> future : submittedWorkers) {
				try {
					future.get();
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					failure.compareAndSet(null, exception);
				} catch (ExecutionException exception) {
					failure.compareAndSet(null, exception.getCause());
				}
			}
		} catch (RuntimeException exception) {
			failure.compareAndSet(null, exception);
			throw exception;
		} finally {
			activeWorkerFutures.removeAll(submittedWorkers);
			if (failure.get() != null) for (final Future<?> future : submittedWorkers) future.cancel(true);
		}
		final Throwable throwable = failure.get();
		if (throwable != null) {
			if (throwable instanceof IOException) throw (IOException) throwable;
			throw new IOException("Route asset PNG batch failed", throwable);
		}
		return completed.get();
	}

	private static int inferConcurrency(ExecutorService executor) {
		if (executor instanceof ThreadPoolExecutor) return Math.max(1, Math.min(8, ((ThreadPoolExecutor) executor).getMaximumPoolSize()));
		return 1;
	}

	private static Set<String> activeHashes(RouteAssetManifest manifest, int resolution, String language) {
		final Set<String> hashes = new LinkedHashSet<>();
		for (final Map.Entry<RouteAssetKey, RouteAssetManifest.Entry> entry : manifest.getEntries().entrySet()) {
			if (entry.getKey().getVariant().getResolution() == resolution && entry.getKey().getVariant().getLanguage().equals(language)) hashes.add(entry.getValue().getHash());
		}
		return Collections.unmodifiableSet(hashes);
	}

	private byte[] downloadOnce(DownloadRequest request, long expectedCancellationEpoch) throws IOException {
		ClientRouteAssetUrlPolicy.ResolvedTarget target = request.target;
		for (int redirects = 0; ; redirects++) {
			checkCurrent(request, expectedCancellationEpoch);
			try (final TransportResponse response = transport.execute(target)) {
				checkCurrent(request, expectedCancellationEpoch);
				if (isRedirect(response.getCode())) {
					final String location = response.getLocation();
					if (location == null || redirects >= RouteAssetProtocol.MAX_HTTP_REDIRECTS) throw new IOException("Route asset redirect limit exceeded");
					target = request.policy.resolveRedirect(target, URI.create(location), redirects + 1).orElseThrow(() -> new IOException("Route asset redirect was rejected"));
					continue;
				}
				if (response.getCode() != 200) throw new IOException("Route asset HTTP status " + response.getCode());
				final long contentLength = response.getContentLength();
				if (contentLength > request.maximumBytes || request.expectedLength >= 0 && contentLength >= 0 && contentLength != request.expectedLength) {
					throw new IOException("Route asset Content-Length is invalid");
				}
				final byte[] bytes;
				try (final InputStream input = response.getBody()) {
					bytes = readBounded(input, request.maximumBytes, request, expectedCancellationEpoch);
				}
				if (request.expectedLength >= 0 && bytes.length != request.expectedLength) throw new IOException("Route asset response was truncated");
				if (!RouteAssetHash.sha256(bytes).equals(request.expectedHash)) throw new IOException("Route asset SHA-256 mismatch");
				return bytes;
			} catch (IllegalArgumentException exception) {
				throw new IOException("Invalid route asset redirect", exception);
			}
		}
	}

	private static Dns createPinnedDns(ClientRouteAssetUrlPolicy.ResolvedTarget target) {
		final String expectedHost = normalizeHost(target.getUri().getHost());
		final List<InetAddress> addresses = new ArrayList<>(target.getAddresses());
		return hostname -> {
			if (!expectedHost.equals(normalizeHost(hostname))) throw new UnknownHostException("Unpinned route asset host");
			return addresses;
		};
	}

	private byte[] readBounded(InputStream input, long maximumBytes, DownloadRequest request, long expectedCancellationEpoch) throws IOException {
		final ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maximumBytes, 8192));
		final byte[] buffer = new byte[8192];
		long total = 0;
		while (true) {
			checkCurrent(request, expectedCancellationEpoch);
			final int read = input.read(buffer);
			if (read < 0) break;
			if (read == 0) continue;
			total += read;
			if (total > maximumBytes) throw new IOException("Route asset response exceeds its byte limit");
			request.budget.consume(read);
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	private void checkCurrent(DownloadRequest request, long expectedCancellationEpoch) {
		if (cancellationEpoch.get() != expectedCancellationEpoch || !request.generationCurrent.getAsBoolean() || Thread.currentThread().isInterrupted()) {
			throw new CancellationException("Route asset download generation was cancelled");
		}
	}

	private static boolean isRedirect(int code) {
		return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
	}

	private static String normalizeHost(String host) {
		return Objects.requireNonNull(host, "host").toLowerCase(Locale.ROOT);
	}

	@FunctionalInterface
	public interface Transport {
		TransportResponse execute(ClientRouteAssetUrlPolicy.ResolvedTarget target) throws IOException;

		default void cancelAll() {
		}
	}

	@FunctionalInterface
	public interface PacketFallbackTransport {
		PacketFallbackTransport DISABLED = new PacketFallbackTransport() {
			@Override public CompletableFuture<byte[]> request(DownloadRequest request) { return new CompletableFuture<>(); }
			@Override public boolean isAvailable() { return false; }
		};

		CompletableFuture<byte[]> request(DownloadRequest request);

		default boolean isAvailable() { return true; }
	}

	public static final class TransportResponse implements Closeable {
		private final int code;
		private final long contentLength;
		private final String location;
		private final InputStream body;
		private final Closeable closeAction;

		public TransportResponse(int code, long contentLength, String location, InputStream body) {
			this(code, contentLength, location, body, body);
		}

		private TransportResponse(int code, long contentLength, String location, InputStream body, Closeable closeAction) {
			if (code < 100 || code > 599 || contentLength < -1) throw new IllegalArgumentException("Invalid route asset HTTP response");
			this.code = code;
			this.contentLength = contentLength;
			this.location = location;
			this.body = Objects.requireNonNull(body, "body");
			this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
		}

		public int getCode() { return code; }
		public long getContentLength() { return contentLength; }
		public String getLocation() { return location; }
		public InputStream getBody() { return body; }

		@Override
		public void close() throws IOException {
			closeAction.close();
		}
	}

	private static final class OkHttpTransport implements Transport {
		private final Set<Call> activeCalls = ConcurrentHashMap.newKeySet();
		private final OkHttpClient baseClient = new OkHttpClient.Builder()
				.followRedirects(false)
				.followSslRedirects(false)
				.connectTimeout(5, TimeUnit.SECONDS)
				.readTimeout(15, TimeUnit.SECONDS)
				.build();

		@Override
		public TransportResponse execute(ClientRouteAssetUrlPolicy.ResolvedTarget target) throws IOException {
			final OkHttpClient client = baseClient.newBuilder().dns(createPinnedDns(target)).build();
			final Request request = new Request.Builder()
					.url(target.getUri().toString())
					.header("Accept-Encoding", "identity")
					.get()
					.build();
			final Call call = client.newCall(request);
			activeCalls.add(call);
			try {
				final Response response = call.execute();
				final ResponseBody responseBody = response.body();
				if (responseBody == null) {
					response.close();
					throw new IOException("Route asset response has no body");
				}
				return new TransportResponse(response.code(), responseBody.contentLength(), response.header("Location"), responseBody.byteStream(), () -> {
					try {
						response.close();
					} finally {
						activeCalls.remove(call);
					}
				});
			} catch (IOException | RuntimeException exception) {
				activeCalls.remove(call);
				throw exception;
			}
		}

		@Override
		public void cancelAll() {
			for (final Call call : activeCalls) call.cancel();
		}
	}

	public static final class DownloadRequest {
		private final long generation;
		private final ClientRouteAssetUrlPolicy policy;
		private final ClientRouteAssetUrlPolicy.ResolvedTarget target;
		private final String expectedHash;
		private final long expectedLength;
		private final long maximumBytes;
		private final BooleanSupplier generationCurrent;
		private final DownloadBudget budget;

		public DownloadRequest(long generation, ClientRouteAssetUrlPolicy policy, ClientRouteAssetUrlPolicy.ResolvedTarget target, String expectedHash, long expectedLength, long maximumBytes, BooleanSupplier generationCurrent) {
			this(generation, policy, target, expectedHash, expectedLength, maximumBytes, generationCurrent, new DownloadBudget(RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES));
		}

		public DownloadRequest(long generation, ClientRouteAssetUrlPolicy policy, ClientRouteAssetUrlPolicy.ResolvedTarget target, String expectedHash, long expectedLength, long maximumBytes, BooleanSupplier generationCurrent, DownloadBudget budget) {
			if (generation == 0 || expectedLength < -1 || maximumBytes <= 0 || maximumBytes > RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES || expectedLength > maximumBytes) throw new IllegalArgumentException("Invalid route asset download bounds");
			this.generation = generation;
			this.policy = Objects.requireNonNull(policy, "policy");
			this.target = Objects.requireNonNull(target, "target");
			this.expectedHash = RouteAssetHash.requireValid(expectedHash);
			this.expectedLength = expectedLength;
			this.maximumBytes = maximumBytes;
			this.generationCurrent = Objects.requireNonNull(generationCurrent, "generationCurrent");
			this.budget = Objects.requireNonNull(budget, "budget");
		}

		public long getGeneration() { return generation; }
		public ClientRouteAssetUrlPolicy getPolicy() { return policy; }
		public ClientRouteAssetUrlPolicy.ResolvedTarget getTarget() { return target; }
		public String getExpectedHash() { return expectedHash; }
		public long getExpectedLength() { return expectedLength; }
		public long getMaximumBytes() { return maximumBytes; }
		public PacketRouteAssetChunkRequest.ObjectType getPacketObjectType() {
			return target.getUri().getPath().endsWith(".json") ? PacketRouteAssetChunkRequest.ObjectType.JSON : PacketRouteAssetChunkRequest.ObjectType.PNG;
		}
	}

	public static final class DownloadBudget {
		private final long maximumBytes;
		private final AtomicLong consumedBytes = new AtomicLong();

		public DownloadBudget(long maximumBytes) {
			if (maximumBytes < 0 || maximumBytes > RouteAssetProtocol.MAX_REVISION_DOWNLOAD_BYTES) throw new IllegalArgumentException("Invalid route asset revision byte limit");
			this.maximumBytes = maximumBytes;
		}

		private void consume(long bytes) throws IOException {
			if (bytes < 0) throw new IOException("Invalid route asset byte count");
			while (true) {
				final long consumed = consumedBytes.get();
				if (bytes > maximumBytes - consumed) throw new IOException("Route asset revision exceeds its aggregate byte limit");
				if (consumedBytes.compareAndSet(consumed, consumed + bytes)) return;
			}
		}

		public long getConsumedBytes() { return consumedBytes.get(); }
		public long getMaximumBytes() { return maximumBytes; }
	}

	public interface ProgressListener {
		void planned(int totalObjects);
		void completed(int completedObjects, int totalObjects);
	}

	public static final class SyncResult {
		private final RouteAssetManifest manifest;
		private final Set<String> activeHashes;
		private final int downloadedObjects;
		private final long downloadedBytes;

		private SyncResult(RouteAssetManifest manifest, Set<String> activeHashes, int downloadedObjects, long downloadedBytes) {
			this.manifest = manifest;
			this.activeHashes = Collections.unmodifiableSet(new HashSet<>(activeHashes));
			this.downloadedObjects = downloadedObjects;
			this.downloadedBytes = downloadedBytes;
		}

		public RouteAssetManifest getManifest() { return manifest; }
		public Set<String> getActiveHashes() { return activeHashes; }
		public int getDownloadedObjects() { return downloadedObjects; }
		public long getDownloadedBytes() { return downloadedBytes; }
	}

	private static final class ActiveTask {
		private final CompletableFuture<?> completion;
		private volatile Future<?> future;

		private ActiveTask(CompletableFuture<?> completion) {
			this.completion = completion;
		}

		private void cancel() {
			completion.completeExceptionally(new CancellationException("Route asset download cancelled"));
			final Future<?> submittedFuture = future;
			if (submittedFuture != null) submittedFuture.cancel(true);
		}
	}
}
