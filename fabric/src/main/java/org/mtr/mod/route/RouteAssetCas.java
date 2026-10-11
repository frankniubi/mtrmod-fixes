package org.mtr.mod.route;

import org.mtr.libraries.com.google.gson.JsonArray;
import org.mtr.libraries.com.google.gson.JsonElement;
import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.mtr.mod.data.JavaCompat;

public final class RouteAssetCas {

	private final Path outputRoot;
	private final int rendererVersion;
	private final Path objectRoot;
	private final Path verifiedIndexPath;
	private final ConcurrentHashMap<String, Object> admissionLocks = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Path, VerifiedStamp> verifiedObjects = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, PersistedStamp> persistedVerified = new ConcurrentHashMap<>();
	private final Object persistedIndexLock = new Object();
	private final AtomicBoolean persistedIndexDirty = new AtomicBoolean(false);
	private final ScheduledExecutorService persistedIndexFlusher;
	private final Thread shutdownHook;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicLong memoryHits = new AtomicLong();
	private final AtomicLong persistedHits = new AtomicLong();
	private final AtomicLong fullVerifications = new AtomicLong();
	private final AtomicLong fullVerificationNanos = new AtomicLong();
	private final AtomicLong flushNanos = new AtomicLong();

	private static final Pattern PREFIX_PATTERN = Pattern.compile("[0-9a-f]{2}");
	private static final String VERIFIED_INDEX_FILE = "verified-index.json";
	private static final long VERIFIED_INDEX_FLUSH_MILLIS = 30_000L;

	public RouteAssetCas(Path outputRoot, int rendererVersion) throws IOException {
		if (rendererVersion < 0) {
			throw new IllegalArgumentException("Renderer version cannot be negative");
		}
		this.outputRoot = outputRoot.toAbsolutePath().normalize();
		this.rendererVersion = rendererVersion;
		objectRoot = this.outputRoot.resolve("v" + rendererVersion).resolve("objects");
		verifiedIndexPath = this.outputRoot.resolve("v" + rendererVersion).resolve(VERIFIED_INDEX_FILE);
		Files.createDirectories(objectRoot);
		persistedIndexFlusher = Executors.newSingleThreadScheduledExecutor(r -> {
			final Thread t = new Thread(r, "mtr-route-verified-index-flusher");
			t.setDaemon(true);
			return t;
		});
		loadPersistedIndex();
		persistedIndexFlusher.scheduleWithFixedDelay(() -> {
			if (persistedIndexDirty.get()) {
				try { flushPersistedIndex(); } catch (IOException ignored) {}
			}
		}, VERIFIED_INDEX_FLUSH_MILLIS, VERIFIED_INDEX_FLUSH_MILLIS, TimeUnit.MILLISECONDS);
		Thread registeredHook = null;
		try {
			registeredHook = new Thread(() -> {
				try { if (persistedIndexDirty.get()) flushPersistedIndex(); } catch (IOException ignored) {}
				try { persistedIndexFlusher.shutdownNow(); } catch (RuntimeException ignored) {}
			}, "mtr-route-verified-index-shutdown");
			Runtime.getRuntime().addShutdownHook(registeredHook);
		} catch (RuntimeException ignored) {}
		shutdownHook = registeredHook;
	}

	public String putPng(byte[] encodedPng) throws IOException {
		validatePng(encodedPng);
		return put(encodedPng, MediaType.PNG);
	}

	public String putJson(byte[] canonicalJson) throws IOException {
		validateJson(canonicalJson);
		return put(canonicalJson, MediaType.JSON);
	}

	Optional<Path> promotePngFromPriorVersions(String hash) throws IOException {
		final String validHash = RouteAssetHash.requireValid(hash);
		final String lockKey = lockKey(validHash, MediaType.PNG);
		final Object lock = admissionLocks.computeIfAbsent(lockKey, ignored -> new Object());
		try {
			synchronized (lock) {
				final Optional<Path> current = find(validHash, MediaType.PNG);
				if (current.isPresent()) return current;
				return promotePngFromPriorVersionsLocked(validHash, pathFor(validHash, MediaType.PNG));
			}
		} finally {
			admissionLocks.remove(lockKey, lock);
		}
	}

	public Optional<Path> find(String hash, MediaType type) {
		final String validHash = RouteAssetHash.requireValid(hash);
		final Path path = pathFor(validHash, type);
		try {
			final VerifiedStamp before = readStamp(path);
			final VerifiedStamp cached = verifiedObjects.get(path);
			if (cached != null && before.equals(cached)) {
				memoryHits.incrementAndGet();
				return Optional.of(path);
			}
			// Persistent index fast path — same trust model as the in-memory cache (size/mtime/fileKey)
			// but survives JVM restarts. This eliminates the ~6 min / 120k-object re-validation on cold start
			// without weakening integrity (serving path still validates on miss, put() always validates).
			final PersistedStamp persisted = persistedVerified.get(path.toAbsolutePath().normalize().toString());
			if (persisted != null && persisted.matches(before)) {
				persistedHits.incrementAndGet();
				verifiedObjects.put(path, before);
				return Optional.of(path);
			}
			final long validationStarted = System.nanoTime();
			final VerifiedStamp after = validateObject(path, validHash, type);
			fullVerifications.incrementAndGet();
			fullVerificationNanos.addAndGet(Math.max(0, System.nanoTime() - validationStarted));
			if (!before.equals(after)) throw new IOException("Route asset CAS object changed during validation");
			verifiedObjects.put(path, after);
			persistedVerified.put(path.toAbsolutePath().normalize().toString(), PersistedStamp.from(before));
			persistedIndexDirty.set(true);
			return Optional.of(path);
		} catch (IOException | RuntimeException exception) {
			verifiedObjects.remove(path);
			return Optional.empty();
		}
	}

	public Optional<RouteAssetObject> findObject(String hash, MediaType type) {
		return find(hash, type).map(path -> {
			try {
				return new RouteAssetObject(hash, type, path, Files.size(path));
			} catch (IOException exception) {
				return null;
			}
		});
	}

	public Path resolvePublicObject(String renderer, String prefix, String hash, String extension) {
		final String validHash = RouteAssetHash.requireValid(hash);
		if (!renderer.equals("v" + rendererVersion) || !PREFIX_PATTERN.matcher(prefix).matches() || !prefix.equals(validHash.substring(0, 2))) {
			throw new IllegalArgumentException("Invalid route asset object path");
		}
		final MediaType type = MediaType.fromExtension(extension);
		final Path path = outputRoot.resolve(renderer).resolve("objects").resolve(prefix).resolve(validHash + '.' + type.extension).normalize();
		if (!path.startsWith(objectRoot)) {
			throw new IllegalArgumentException("Route asset object path escapes the CAS root");
		}
		return path;
	}

	public GcResult garbageCollect(long maximumBytes, Set<String> pinnedHashes) throws IOException {
		if (maximumBytes < 0) {
			throw new IllegalArgumentException("CAS maximum bytes cannot be negative");
		}
		final Set<String> pinned = new HashSet<>();
		for (final String hash : pinnedHashes) {
			pinned.add(RouteAssetHash.requireValid(hash));
		}
		final List<Path> objects = new ArrayList<>();
		try (final Stream<Path> paths = Files.walk(objectRoot)) {
			paths.filter(Files::isRegularFile).filter(path -> MediaType.isSupportedFileName(path.getFileName().toString())).forEach(objects::add);
		}
		long totalBytes = 0;
		for (final Path object : objects) {
			totalBytes += Files.size(object);
		}
		objects.sort(Comparator.comparingLong(RouteAssetCas::lastModified).thenComparing(Path::toString));
		long deletedBytes = 0;
		int deletedObjects = 0;
		for (final Path object : objects) {
			if (totalBytes <= maximumBytes) {
				break;
			}
			final String fileName = object.getFileName().toString();
			final String hash = fileName.substring(0, fileName.indexOf('.'));
			if (!pinned.contains(hash)) {
				final long size = Files.size(object);
				Files.deleteIfExists(object);
				verifiedObjects.remove(object);
				persistedVerified.remove(object.toAbsolutePath().normalize().toString());
				persistedIndexDirty.set(true);
				totalBytes -= size;
				deletedBytes += size;
				deletedObjects++;
			}
		}
		return new GcResult(deletedObjects, deletedBytes, totalBytes);
	}

	public Path getOutputRoot() {
		return outputRoot;
	}

	public int getRendererVersion() {
		return rendererVersion;
	}

	private String put(byte[] bytes, MediaType type) throws IOException {
		final String hash = RouteAssetHash.sha256(bytes);
		final String lockKey = lockKey(hash, type);
		final Object lock = admissionLocks.computeIfAbsent(lockKey, ignored -> new Object());
		try {
			synchronized (lock) {
				final Path target = pathFor(hash, type);
				if (find(hash, type).isPresent()) {
					return hash;
				}
				if (type == MediaType.PNG && promotePngFromPriorVersionsLocked(hash, target).isPresent()) return hash;
				Files.createDirectories(target.getParent());
				final Path temporary = Files.createTempFile(target.getParent(), "." + hash + '-', ".tmp");
				try {
					Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
					try (final FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
						channel.force(true);
					}
					validateObject(temporary, hash, type);
					moveAtomically(temporary, target);
					{
						final VerifiedStamp stamp = validateObject(target, hash, type);
						verifiedObjects.put(target, stamp);
						persistedVerified.put(target.toAbsolutePath().normalize().toString(), PersistedStamp.from(stamp));
						persistedIndexDirty.set(true);
					}
					return hash;
				} finally {
					Files.deleteIfExists(temporary);
				}
			}
		} finally {
			admissionLocks.remove(lockKey, lock);
		}
	}

	private Path pathFor(String hash, MediaType type) {
		return objectPath(objectRoot, hash, type);
	}

	private static String lockKey(String hash, MediaType type) {
		return type.extension + ':' + hash;
	}

	private static Path objectPath(Path root, String hash, MediaType type) {
		final Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
		final String validHash = RouteAssetHash.requireValid(hash);
		final MediaType validType = Objects.requireNonNull(type, "type");
		final Path path = normalizedRoot.resolve(validHash.substring(0, 2)).resolve(validHash + '.' + validType.extension).normalize();
		if (!path.startsWith(normalizedRoot)) throw new IllegalArgumentException("Route asset object path escapes the CAS root");
		return path;
	}

	private Optional<Path> promotePngFromPriorVersionsLocked(String hash, Path target) throws IOException {
		for (int version = rendererVersion - 1; version >= RouteAssetProtocol.MIN_REUSABLE_PNG_RENDERER_VERSION; version--) {
			final Path prior = objectPath(outputRoot.resolve("v" + version).resolve("objects"), hash, MediaType.PNG);
			if (promoteVerifiedPng(prior, target, hash)) return Optional.of(target);
		}
		return Optional.empty();
	}

	private boolean promoteVerifiedPng(Path prior, Path target, String hash) throws IOException {
		try {
			validateObject(prior, hash, MediaType.PNG);
		} catch (IOException | RuntimeException exception) {
			return false;
		}
		Files.createDirectories(target.getParent());
		final Path temporary = promotionTemporaryPath(target, hash);
		boolean moved = false;
		try {
			try {
				Files.createLink(temporary, prior);
			} catch (IOException | UnsupportedOperationException | SecurityException exception) {
				Files.copy(prior, temporary, LinkOption.NOFOLLOW_LINKS);
			}
			force(temporary);
			validateObject(temporary, hash, MediaType.PNG);
			moveAtomically(temporary, target);
			moved = true;
			{
				final VerifiedStamp stamp = validateObject(target, hash, MediaType.PNG);
				verifiedObjects.put(target, stamp);
				persistedVerified.put(target.toAbsolutePath().normalize().toString(), PersistedStamp.from(stamp));
				persistedIndexDirty.set(true);
			}
			return true;
		} catch (IOException | RuntimeException exception) {
			verifiedObjects.remove(target);
			if (moved) Files.deleteIfExists(target);
			return false;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	static Path promotionTemporaryPath(Path target, String hash) {
		final String validHash = RouteAssetHash.requireValid(hash);
		final Path directory = Objects.requireNonNull(target, "target").toAbsolutePath().normalize().getParent();
		if (directory == null) throw new IllegalArgumentException("Route asset promotion target has no parent");
		while (true) {
			final Path temporary = directory.resolve('.' + validHash + '-' + UUID.randomUUID().toString() + ".tmp");
			if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) return temporary;
		}
	}

	private static void force(Path path) throws IOException {
		try (final FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			channel.force(true);
		}
	}

	private static VerifiedStamp validateObject(Path path, String expectedHash, MediaType type) throws IOException {
		final VerifiedStamp before = readStamp(path);
		final long maximumBytes = type == MediaType.PNG ? RouteAssetProtocol.MAX_PNG_BYTES : RouteAssetProtocol.MAX_MANIFEST_BYTES;
		if (before.size <= 0 || before.size > maximumBytes || before.size > Integer.MAX_VALUE) throw new IOException("Invalid route asset CAS object size");
		final byte[] bytes = readBounded(path, before.size);
		if (!RouteAssetHash.sha256(bytes).equals(expectedHash)) {
			throw new IOException("Route asset CAS hash mismatch");
		}
		if (type == MediaType.PNG) {
			validatePng(bytes);
		} else {
			validateJson(bytes);
		}
		final VerifiedStamp after = readStamp(path);
		if (!before.equals(after)) throw new IOException("Route asset CAS object changed during validation");
		return after;
	}

	private static byte[] readBounded(Path path, long expectedSize) throws IOException {
		final ByteBuffer buffer = ByteBuffer.allocate((int) expectedSize);
		try (final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			if (channel.size() != expectedSize) throw new IOException("Route asset CAS object changed before reading");
			while (buffer.hasRemaining()) {
				if (channel.read(buffer) < 0) throw new IOException("Route asset CAS object was truncated while reading");
			}
			final ByteBuffer extra = ByteBuffer.allocate(1);
			int extraBytes;
			do {
				extraBytes = channel.read(extra);
			} while (extraBytes == 0);
			if (extraBytes >= 0) throw new IOException("Route asset CAS object grew while reading");
		}
		return buffer.array();
	}

	private static VerifiedStamp readStamp(Path path) throws IOException {
		final BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile()) throw new IOException("Route asset CAS object is not a regular file");
		return new VerifiedStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
	}

	private static void validatePng(byte[] bytes) throws IOException {
		if (bytes == null || bytes.length == 0 || bytes.length > RouteAssetProtocol.MAX_PNG_BYTES) {
			throw new IOException("Invalid route asset PNG size");
		}
		final BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		if (image == null) throw new IOException("Invalid route asset PNG dimensions");
		try {
			if (image.getWidth() <= 0 || image.getHeight() <= 0 || image.getWidth() > RouteAssetProtocol.MAX_PNG_AXIS || image.getHeight() > RouteAssetProtocol.MAX_PNG_AXIS || (long) image.getWidth() * image.getHeight() > RouteAssetProtocol.MAX_PNG_PIXELS) {
				throw new IOException("Invalid route asset PNG dimensions");
			}
		} finally {
			image.flush();
		}
	}

	private static void validateJson(byte[] bytes) throws IOException {
		if (bytes == null || bytes.length == 0 || bytes.length > RouteAssetProtocol.MAX_MANIFEST_BYTES) {
			throw new IOException("Invalid route asset JSON size");
		}
		try {
			JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
		} catch (RuntimeException exception) {
			throw new IOException("Invalid route asset JSON", exception);
		}
	}

	static void moveAtomically(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static long lastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException exception) {
			return Long.MIN_VALUE;
		}
	}

	/** Visible for testing. */
	public void flushVerifiedIndex() throws IOException { flushPersistedIndex(); }
	/** Visible for testing. */
	public int persistedVerifiedSize() { return persistedVerified.size(); }
	public long getMemoryHits() { return memoryHits.get(); }
	public long getPersistedHits() { return persistedHits.get(); }
	public long getFullVerifications() { return fullVerifications.get(); }
	public long getFullVerificationNanos() { return fullVerificationNanos.get(); }
	public long getFlushNanos() { return flushNanos.get(); }
	public void close() {
		if (!closed.compareAndSet(false, true)) return;
		try {
			if (persistedIndexDirty.get()) flushPersistedIndex();
		} catch (IOException ignored) {
		}
		if (shutdownHook != null) {
			try {
				Runtime.getRuntime().removeShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
				// JVM shutdown is already in progress.
			} catch (RuntimeException ignored) {
				// A shutdown hook may already have been removed by the runtime.
			}
		}
		try {
			persistedIndexFlusher.shutdownNow();
		} catch (RuntimeException ignored) {
		}
	}

	private void loadPersistedIndex() {
		if (!Files.isRegularFile(verifiedIndexPath)) return;
		try {
			final String text = JavaCompat.readString(verifiedIndexPath, StandardCharsets.UTF_8);
			final JsonObject root = JsonParser.parseString(text).getAsJsonObject();
			final JsonArray arr = root.has("entries") && root.get("entries").isJsonArray() ? root.getAsJsonArray("entries") : new JsonArray();
			for (final JsonElement el : arr) {
				if (!el.isJsonObject()) continue;
				final JsonObject o = el.getAsJsonObject();
				if (!o.has("path") || !o.has("size") || !o.has("mtime")) continue;
				final String p = o.get("path").getAsString();
				final long size = o.get("size").getAsLong();
				final long mtime = o.get("mtime").getAsLong();
				final String fileKeyStr = o.has("fileKey") && !o.get("fileKey").isJsonNull() ? o.get("fileKey").getAsString() : "";
				persistedVerified.put(p, new PersistedStamp(size, mtime, fileKeyStr));
			}
		} catch (IOException | RuntimeException ignored) {}
	}

	private void flushPersistedIndex() throws IOException {
		final long started = System.nanoTime();
		synchronized (persistedIndexLock) {
			final JsonObject root = new JsonObject();
			root.addProperty("rendererVersion", rendererVersion);
			final JsonArray arr = new JsonArray();
			for (final Map.Entry<String, PersistedStamp> e : persistedVerified.entrySet()) {
				final JsonObject o = new JsonObject();
				o.addProperty("path", e.getKey());
				o.addProperty("size", e.getValue().size);
				o.addProperty("mtime", e.getValue().mtimeMillis);
				o.addProperty("fileKey", e.getValue().fileKeyStr);
				arr.add(o);
			}
			root.add("entries", arr);
			final byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
			final Path tmp = verifiedIndexPath.resolveSibling("." + VERIFIED_INDEX_FILE + ".tmp");
			Files.createDirectories(verifiedIndexPath.getParent());
			Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			try { Files.move(tmp, verifiedIndexPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); } catch (AtomicMoveNotSupportedException ex) { Files.move(tmp, verifiedIndexPath, StandardCopyOption.REPLACE_EXISTING); }
			persistedIndexDirty.set(false);
		}
		flushNanos.addAndGet(Math.max(0, System.nanoTime() - started));
	}

	private static final class PersistedStamp {
		private final long size;
		private final long mtimeMillis;
		private final String fileKeyStr;
		private PersistedStamp(long size, long mtimeMillis, String fileKeyStr) { this.size = size; this.mtimeMillis = mtimeMillis; this.fileKeyStr = fileKeyStr == null ? "" : fileKeyStr; }
		static PersistedStamp from(VerifiedStamp s) { return new PersistedStamp(s.size, s.modified.toMillis(), s.fileKey == null ? "" : s.fileKey.toString()); }
		boolean matches(VerifiedStamp s) { if (s.size != size) return false; if (s.modified.toMillis() != mtimeMillis) return false; final String cur = s.fileKey == null ? "" : s.fileKey.toString(); return cur.equals(fileKeyStr); }
	}

	private static final class VerifiedStamp {
		private final long size;
		private final FileTime modified;
		private final Object fileKey;

		private VerifiedStamp(long size, FileTime modified, Object fileKey) {
			this.size = size;
			this.modified = modified;
			this.fileKey = fileKey;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof VerifiedStamp)) return false;
			final VerifiedStamp stamp = (VerifiedStamp) object;
			return size == stamp.size && modified.equals(stamp.modified) && java.util.Objects.equals(fileKey, stamp.fileKey);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(size, modified, fileKey);
		}
	}

	public enum MediaType {
		PNG("png", "image/png"),
		JSON("json", "application/json; charset=utf-8");

		private final String extension;
		private final String contentType;

		MediaType(String extension, String contentType) {
			this.extension = extension;
			this.contentType = contentType;
		}

		public String getExtension() { return extension; }
		public String getContentType() { return contentType; }

		public static MediaType fromExtension(String extension) {
			for (final MediaType value : values()) {
				if (value.extension.equals(extension)) return value;
			}
			throw new IllegalArgumentException("Unsupported route asset object extension");
		}

		static boolean isSupportedFileName(String fileName) {
			return fileName.matches("[0-9a-f]{64}\\.(png|json)");
		}
	}

	public static final class RouteAssetObject {
		private final String hash;
		private final MediaType mediaType;
		private final Path path;
		private final long size;

		private RouteAssetObject(String hash, MediaType mediaType, Path path, long size) {
			this.hash = hash;
			this.mediaType = mediaType;
			this.path = path;
			this.size = size;
		}

		public String getHash() { return hash; }
		public MediaType getMediaType() { return mediaType; }
		public Path getPath() { return path; }
		public long getSize() { return size; }
	}

	public static final class GcResult {
		private final int deletedObjects;
		private final long deletedBytes;
		private final long remainingBytes;

		private GcResult(int deletedObjects, long deletedBytes, long remainingBytes) {
			this.deletedObjects = deletedObjects;
			this.deletedBytes = deletedBytes;
			this.remainingBytes = remainingBytes;
		}

		public int getDeletedObjects() { return deletedObjects; }
		public long getDeletedBytes() { return deletedBytes; }
		public long getRemainingBytes() { return remainingBytes; }
	}
}
