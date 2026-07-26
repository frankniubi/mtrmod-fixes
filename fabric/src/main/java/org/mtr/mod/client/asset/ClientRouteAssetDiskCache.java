package org.mtr.mod.client.asset;

import org.mtr.libraries.com.google.gson.JsonObject;
import org.mtr.libraries.com.google.gson.JsonParser;
import org.mtr.libraries.com.google.gson.stream.JsonReader;
import org.mtr.mod.route.RouteAssetHash;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetManifest;
import org.mtr.mod.route.RouteAssetManifestCodec;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.RouteAssetVariantPolicy;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class ClientRouteAssetDiskCache {

	public static final long MAX_UNUSED_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000;

	private static final Pattern PNG_FILE = Pattern.compile("[0-9a-f]{64}\\.png");
	private static final int MAX_REVISION_PREFIX_BYTES = 4096;
	private static final ConcurrentHashMap<Path, Object> ADMISSION_LOCKS = new ConcurrentHashMap<>();

	private final Path root;
	private final Path casRoot;
	private final Path serversRoot;
	private final Path associationsPath;
	private final int rendererVersion;
	private final Object associationLock = new Object();
	private final Object sessionEpochGuard = new Object();
	private boolean initialized;
	private long sessionEpoch;

	public ClientRouteAssetDiskCache(Path root, int rendererVersion) {
		if (rendererVersion < 0) throw new IllegalArgumentException("Renderer version cannot be negative");
		this.root = root.toAbsolutePath().normalize();
		this.rendererVersion = rendererVersion;
		casRoot = this.root.resolve("cas").resolve(Integer.toString(rendererVersion)).resolve("sha256");
		serversRoot = this.root.resolve("servers");
		associationsPath = serversRoot.resolve("addresses.json");
	}

	public synchronized void initialize() throws IOException {
		if (initialized) return;
		Files.createDirectories(casRoot);
		Files.createDirectories(serversRoot);
		initialized = true;
	}

	public String admitPng(String expectedHash, byte[] bytes) throws IOException {
		final String hash = RouteAssetHash.requireValid(expectedHash);
		validatePng(bytes, hash);
		final Path target = pathForPng(hash);
		final Object lock = ADMISSION_LOCKS.computeIfAbsent(target, ignored -> new Object());
		try {
			synchronized (lock) {
				if (findPng(hash).isPresent()) return hash;
				Files.createDirectories(target.getParent());
				final Path temporary = Files.createTempFile(target.getParent(), "." + hash + '-', ".tmp");
				try {
					Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
					force(temporary);
					validatePng(readBounded(temporary, RouteAssetProtocol.MAX_PNG_BYTES), hash);
					moveAtomically(temporary, target);
					validatePng(readBounded(target, RouteAssetProtocol.MAX_PNG_BYTES), hash);
					return hash;
				} finally {
					Files.deleteIfExists(temporary);
				}
			}
		} finally {
			ADMISSION_LOCKS.remove(target, lock);
		}
	}

	public Optional<Path> promotePngFromPriorVersions(String expectedHash) throws IOException {
		final String hash = RouteAssetHash.requireValid(expectedHash);
		final Path target = pathForPng(hash);
		final Object lock = ADMISSION_LOCKS.computeIfAbsent(target, ignored -> new Object());
		try {
			synchronized (lock) {
				final Optional<Path> current = findPng(hash);
				if (current.isPresent()) return current;
				return promotePngFromPriorVersionsLocked(hash, target);
			}
		} finally {
			ADMISSION_LOCKS.remove(target, lock);
		}
	}

	public Optional<Path> findPng(String hash) {
		final Path path = pathForPng(hash);
		try {
			if (!Files.isRegularFile(path)) return Optional.empty();
			validateStoredPng(readBounded(path, RouteAssetProtocol.MAX_PNG_BYTES), RouteAssetHash.requireValid(hash));
			Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
			return Optional.of(path);
		} catch (IOException | RuntimeException exception) {
			return Optional.empty();
		}
	}

	public Path pathForPng(String hash) {
		final String validHash = RouteAssetHash.requireValid(hash);
		return casRoot.resolve(validHash.substring(0, 2)).resolve(validHash + ".png");
	}

	public synchronized void storeManifest(String serverId, RouteAssetManifest manifest) throws IOException {
		final String id = requireServerId(serverId);
		if (manifest.getRendererVersion() != rendererVersion) throw new IOException("Route asset manifest renderer mismatch");
		writeAtomic(manifestPath(id), RouteAssetManifestCodec.encode(manifest));
	}

	public boolean commitManifest(String multiplayerAddress, String serverId, RouteAssetManifest manifest, long expectedEpoch) throws IOException {
		return commitManifest(multiplayerAddress, serverId, manifest, expectedEpoch, () -> { });
	}

	public boolean commitManifest(String multiplayerAddress, String serverId, RouteAssetManifest manifest, long expectedEpoch, Runnable beforeMove) throws IOException {
		final String id = requireServerId(serverId);
		if (manifest.getRendererVersion() != rendererVersion) throw new IOException("Route asset manifest renderer mismatch");
		final Path manifestTarget = manifestPath(id);
		final Path stagedManifest = writeStaged(manifestTarget, RouteAssetManifestCodec.encode(manifest));
		Path stagedAssociations = null;
		try {
			synchronized (associationLock) {
				final Map<String, String> associations = readAssociations();
				associations.put(normalizeAddress(multiplayerAddress), id);
				final JsonObject associationObject = new JsonObject();
				associations.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> associationObject.addProperty(entry.getKey(), entry.getValue()));
				stagedAssociations = writeStaged(associationsPath, associationObject.toString().getBytes(StandardCharsets.UTF_8));
				beforeMove.run();
				synchronized (sessionEpochGuard) {
					if (sessionEpoch != expectedEpoch) return false;
					moveAtomically(stagedManifest, manifestTarget);
					try {
						moveAtomically(stagedAssociations, associationsPath);
					} catch (IOException ignored) {
						// The address index is a reconnect hint; the manifest is authoritative.
					}
					return true;
				}
			}
		} finally {
			Files.deleteIfExists(stagedManifest);
			if (stagedAssociations != null) Files.deleteIfExists(stagedAssociations);
		}
	}

	public Optional<RouteAssetManifest> loadManifest(String serverId) {
		final Path path;
		try {
			path = manifestPath(requireServerId(serverId));
			if (!Files.isRegularFile(path)) return Optional.empty();
			final RouteAssetManifest manifest = RouteAssetManifestCodec.decodeManifest(readBounded(path, RouteAssetProtocol.MAX_MANIFEST_BYTES));
			return manifest.getRendererVersion() == rendererVersion ? Optional.of(manifest) : Optional.empty();
		} catch (IOException | RuntimeException exception) {
			return Optional.empty();
		}
	}

	public boolean hasEveryObject(RouteAssetManifest manifest) {
		return hasEveryObject(manifest, -1, "");
	}

	public boolean hasEveryObject(RouteAssetManifest manifest, int resolution, String language) {
		if (manifest.getRendererVersion() != rendererVersion) return false;
		int matchingEntries = 0;
		for (final Map.Entry<RouteAssetKey, RouteAssetManifest.Entry> entry : manifest.getEntries().entrySet()) {
			if (resolution < 0 || RouteAssetVariantPolicy.isActive(entry.getKey(), resolution, language)) {
				matchingEntries++;
				if (findPng(entry.getValue().getHash()).isEmpty()) return false;
			}
		}
		return resolution < 0 || matchingEntries > 0;
	}

	public void associate(String multiplayerAddress, String serverId) throws IOException {
		final String address = normalizeAddress(multiplayerAddress);
		final String id = requireServerId(serverId);
		synchronized (associationLock) {
			final Map<String, String> associations = readAssociations();
			associations.put(address, id);
			writeAssociations(associations);
		}
	}

	public Optional<String> findServerId(String multiplayerAddress) {
		try {
			synchronized (associationLock) {
				return Optional.ofNullable(readAssociations().get(normalizeAddress(multiplayerAddress)));
			}
		} catch (IOException | RuntimeException exception) {
			return Optional.empty();
		}
	}

	public Optional<String> findRevision(String multiplayerAddress) {
		return findServerId(multiplayerAddress).flatMap(serverId -> {
			final Path path = manifestPath(serverId);
			try (final InputStream input = new PrefixInputStream(Files.newInputStream(path), MAX_REVISION_PREFIX_BYTES); final JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
				reader.beginObject();
				if (!reader.hasNext() || !"revision".equals(reader.nextName())) return Optional.empty();
				return Optional.of(RouteAssetHash.requireValid(reader.nextString()));
			} catch (IOException | RuntimeException exception) {
				return Optional.empty();
			}
		});
	}

	public void repair(String serverId) throws IOException {
		final String id = requireServerId(serverId);
		Files.deleteIfExists(manifestPath(id));
		synchronized (associationLock) {
			final Map<String, String> associations = readAssociations();
			associations.entrySet().removeIf(entry -> id.equals(entry.getValue()));
			writeAssociations(associations);
		}
	}

	public PruneResult prune(long maximumBytes, Set<String> pinnedHashes) throws IOException {
		return prune(maximumBytes, pinnedHashes, getSessionEpoch(), () -> { });
	}

	public PruneResult prune(long maximumBytes, Set<String> pinnedHashes, long expectedEpoch) throws IOException {
		return prune(maximumBytes, pinnedHashes, expectedEpoch, () -> { });
	}

	PruneResult prune(long maximumBytes, Set<String> pinnedHashes, long expectedEpoch, Runnable beforeDelete) throws IOException {
		if (maximumBytes < 0) throw new IllegalArgumentException("Cache maximum bytes cannot be negative");
		final Set<String> pinned = new HashSet<>();
		for (final String hash : pinnedHashes) pinned.add(RouteAssetHash.requireValid(hash));
		final List<Path> objects = new ArrayList<>();
		try (final Stream<Path> paths = Files.walk(casRoot)) {
			paths.filter(Files::isRegularFile).filter(path -> PNG_FILE.matcher(path.getFileName().toString()).matches()).forEach(objects::add);
		}
		long remainingBytes = 0;
		for (final Path object : objects) remainingBytes += Files.size(object);
		objects.sort(Comparator.comparingLong(ClientRouteAssetDiskCache::lastModified).thenComparing(Path::toString));
		int deletedObjects = 0;
		long deletedBytes = 0;
		final long oldestAllowedMillis = System.currentTimeMillis() - MAX_UNUSED_AGE_MILLIS;
		for (final Path object : objects) {
			final boolean exceedsSizeLimit = remainingBytes > maximumBytes;
			final boolean exceedsAgeLimit = lastModified(object) < oldestAllowedMillis;
			if (!exceedsSizeLimit && !exceedsAgeLimit) continue;
			final String fileName = object.getFileName().toString();
			final String hash = fileName.substring(0, fileName.length() - 4);
			if (!pinned.contains(hash)) {
				beforeDelete.run();
				// Scanning stays outside this guard; transitions contend with one final file deletion only.
				synchronized (sessionEpochGuard) {
					if (sessionEpoch != expectedEpoch) break;
					if (!exceedsSizeLimit && lastModified(object) >= oldestAllowedMillis) continue;
					final long size = Files.size(object);
					if (Files.deleteIfExists(object)) {
						remainingBytes -= size;
						deletedBytes += size;
						deletedObjects++;
					}
				}
			}
		}
		return new PruneResult(deletedObjects, deletedBytes, remainingBytes);
	}

	public long advanceSessionEpoch() {
		synchronized (sessionEpochGuard) {
			sessionEpoch++;
			if (sessionEpoch == 0) sessionEpoch = 1;
			return sessionEpoch;
		}
	}

	public long getSessionEpoch() {
		synchronized (sessionEpochGuard) {
			return sessionEpoch;
		}
	}

	public Path getRoot() {
		return root;
	}

	private Path manifestPath(String serverId) {
		return serversRoot.resolve(serverId).resolve("manifest.json");
	}

	private Map<String, String> readAssociations() throws IOException {
		final Map<String, String> result = new HashMap<>();
		if (!Files.isRegularFile(associationsPath)) return result;
		try {
			final JsonObject object = JsonParser.parseString(new String(readBounded(associationsPath, 1024 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
			object.entrySet().forEach(entry -> result.put(normalizeAddress(entry.getKey()), requireServerId(entry.getValue().getAsString())));
			return result;
		} catch (RuntimeException exception) {
			throw new IOException("Invalid route asset server association index", exception);
		}
	}

	private void writeAssociations(Map<String, String> associations) throws IOException {
		final JsonObject object = new JsonObject();
		associations.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> object.addProperty(entry.getKey(), entry.getValue()));
		writeAtomic(associationsPath, object.toString().getBytes(StandardCharsets.UTF_8));
	}

	private Optional<Path> promotePngFromPriorVersionsLocked(String hash, Path target) throws IOException {
		for (int version = rendererVersion - 1; version >= RouteAssetProtocol.MIN_REUSABLE_PNG_RENDERER_VERSION; version--) {
			final Path prior = pathForPng(version, hash);
			if (promoteVerifiedPng(prior, target, hash)) return Optional.of(target);
		}
		return Optional.empty();
	}

	private boolean promoteVerifiedPng(Path prior, Path target, String hash) throws IOException {
		try {
			validatePng(readBounded(prior, RouteAssetProtocol.MAX_PNG_BYTES), hash);
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
			validatePng(readBounded(temporary, RouteAssetProtocol.MAX_PNG_BYTES), hash);
			moveAtomically(temporary, target);
			moved = true;
			validatePng(readBounded(target, RouteAssetProtocol.MAX_PNG_BYTES), hash);
			return true;
		} catch (IOException | RuntimeException exception) {
			if (moved) Files.deleteIfExists(target);
			return false;
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private Path pathForPng(int version, String hash) {
		final String validHash = RouteAssetHash.requireValid(hash);
		return root.resolve("cas").resolve(Integer.toString(version)).resolve("sha256").resolve(validHash.substring(0, 2)).resolve(validHash + ".png");
	}

	private static Path promotionTemporaryPath(Path target, String hash) {
		final String validHash = RouteAssetHash.requireValid(hash);
		final Path directory = target.toAbsolutePath().normalize().getParent();
		if (directory == null) throw new IllegalArgumentException("Route asset promotion target has no parent");
		while (true) {
			final Path temporary = directory.resolve('.' + validHash + '-' + UUID.randomUUID().toString() + ".tmp");
			if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) return temporary;
		}
	}

	private static void validatePng(byte[] bytes, String expectedHash) throws IOException {
		validateStoredPng(bytes, expectedHash);
		final java.awt.image.BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		if (image == null) throw new IOException("Invalid route asset PNG dimensions");
		try {
			if (image.getWidth() <= 0 || image.getHeight() <= 0 || image.getWidth() > RouteAssetProtocol.MAX_PNG_AXIS || image.getHeight() > RouteAssetProtocol.MAX_PNG_AXIS || (long) image.getWidth() * image.getHeight() > RouteAssetProtocol.MAX_PNG_PIXELS) {
				throw new IOException("Invalid route asset PNG dimensions");
			}
		} finally {
			image.flush();
		}
	}

	private static void validateStoredPng(byte[] bytes, String expectedHash) throws IOException {
		if (bytes == null || bytes.length == 0 || bytes.length > RouteAssetProtocol.MAX_PNG_BYTES || !RouteAssetHash.sha256(bytes).equals(expectedHash)) {
			throw new IOException("Invalid route asset PNG hash or size");
		}
	}

	private static byte[] readBounded(Path path, long maximumBytes) throws IOException {
		final FileStamp before = readStamp(path);
		if (before.size <= 0 || before.size > maximumBytes || before.size > Integer.MAX_VALUE) throw new IOException("Route asset cache file exceeds its size limit");
		final ByteBuffer buffer = ByteBuffer.allocate((int) before.size);
		try (final FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
			if (channel.size() != before.size) throw new IOException("Route asset cache file changed before reading");
			while (buffer.hasRemaining()) {
				if (channel.read(buffer) < 0) throw new IOException("Route asset cache file was truncated while reading");
			}
			final ByteBuffer extra = ByteBuffer.allocate(1);
			int extraBytes;
			do {
				extraBytes = channel.read(extra);
			} while (extraBytes == 0);
			if (extraBytes >= 0) throw new IOException("Route asset cache file grew while reading");
		}
		final FileStamp after = readStamp(path);
		if (!before.equals(after)) throw new IOException("Route asset cache file changed during reading");
		return buffer.array();
	}

	private static FileStamp readStamp(Path path) throws IOException {
		final BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile()) throw new IOException("Route asset cache object is not a regular file");
		return new FileStamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
	}

	private static void writeAtomic(Path target, byte[] bytes) throws IOException {
		final Path temporary = writeStaged(target, bytes);
		try {
			moveAtomically(temporary, target);
		} finally {
			Files.deleteIfExists(temporary);
		}
	}

	private static Path writeStaged(Path target, byte[] bytes) throws IOException {
		Files.createDirectories(target.getParent());
		final Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + '-', ".tmp");
		boolean successful = false;
		try {
			Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
			force(temporary);
			successful = true;
			return temporary;
		} finally {
			if (!successful) Files.deleteIfExists(temporary);
		}
	}

	private static void force(Path path) throws IOException {
		try (final FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
			channel.force(true);
		}
	}

	private static void moveAtomically(Path source, Path target) throws IOException {
		try {
			Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException exception) {
			Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String requireServerId(String serverId) {
		return UUID.fromString(serverId).toString();
	}

	private static String normalizeAddress(String multiplayerAddress) {
		final String address = multiplayerAddress.trim().toLowerCase(Locale.ROOT);
		if (address.isEmpty() || address.length() > 512 || address.indexOf('\u0000') >= 0) throw new IllegalArgumentException("Invalid multiplayer address");
		return address;
	}

	private static long lastModified(Path path) {
		try {
			return Files.getLastModifiedTime(path).toMillis();
		} catch (IOException exception) {
			return Long.MIN_VALUE;
		}
	}

	private static final class FileStamp {
		private final long size;
		private final FileTime modified;
		private final Object fileKey;

		private FileStamp(long size, FileTime modified, Object fileKey) {
			this.size = size;
			this.modified = modified;
			this.fileKey = fileKey;
		}

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof FileStamp)) return false;
			final FileStamp stamp = (FileStamp) object;
			return size == stamp.size && modified.equals(stamp.modified) && java.util.Objects.equals(fileKey, stamp.fileKey);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(size, modified, fileKey);
		}
	}

	private static final class PrefixInputStream extends FilterInputStream {
		private long remaining;

		private PrefixInputStream(InputStream input, long maximumBytes) {
			super(input);
			remaining = maximumBytes;
		}

		@Override
		public int read() throws IOException {
			if (remaining <= 0) return -1;
			final int value = super.read();
			if (value >= 0) remaining--;
			return value;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			if (remaining <= 0) return -1;
			final int read = super.read(bytes, offset, (int) Math.min(length, remaining));
			if (read > 0) remaining -= read;
			return read;
		}
	}

	public static final class PruneResult {
		private final int deletedObjects;
		private final long deletedBytes;
		private final long remainingBytes;

		private PruneResult(int deletedObjects, long deletedBytes, long remainingBytes) {
			this.deletedObjects = deletedObjects;
			this.deletedBytes = deletedBytes;
			this.remainingBytes = remainingBytes;
		}

		public int getDeletedObjects() { return deletedObjects; }
		public long getDeletedBytes() { return deletedBytes; }
		public long getRemainingBytes() { return remainingBytes; }
	}
}
