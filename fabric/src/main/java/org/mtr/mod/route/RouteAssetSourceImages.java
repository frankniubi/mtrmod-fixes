package org.mtr.mod.route;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

public final class RouteAssetSourceImages {

	private final ResourceLoader resourceLoader;
	private final Decoder decoder;
	private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
	private final AtomicLong generation = new AtomicLong();

	private static final Pattern RESOURCE_PATH_PATTERN = Pattern.compile("[A-Za-z0-9_./-]{1,256}");

	public RouteAssetSourceImages(ResourceLoader resourceLoader) {
		this(resourceLoader, RouteAssetSourceImages::decodePng);
	}

	public RouteAssetSourceImages(ResourceLoader resourceLoader, Decoder decoder) {
		this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader");
		this.decoder = Objects.requireNonNull(decoder, "decoder");
	}

	public RouteAssetImage get(String resourcePath) throws IOException {
		final String path = validatePath(resourcePath);
		while (true) {
			final long expectedGeneration = generation.get();
			final CacheEntry existing = cache.get(path);
			if (existing != null) {
				return existing.image;
			}
			final Object lock = locks.computeIfAbsent(path, ignored -> new Object());
			try {
				synchronized (lock) {
					if (expectedGeneration != generation.get()) {
						continue;
					}
					final CacheEntry rechecked = cache.get(path);
					if (rechecked != null) {
						return rechecked.image;
					}
					final byte[] bytes = Objects.requireNonNull(resourceLoader.load(path), "Resource loader returned null").clone();
					if (bytes.length == 0 || bytes.length > RouteAssetProtocol.MAX_PNG_BYTES) {
						throw new IOException("Invalid route asset source size: " + path);
					}
					final RouteAssetImage image = Objects.requireNonNull(decoder.decode(bytes.clone()), "Decoder returned null").immutableCopy();
					if (expectedGeneration == generation.get()) {
						cache.put(path, new CacheEntry(image, bytes));
						return image;
					}
				}
			} finally {
				locks.remove(path, lock);
			}
		}
	}

	public String getFingerprint() {
		final TreeMap<String, CacheEntry> sorted = new TreeMap<>(cache);
		try {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (final DataOutputStream output = new DataOutputStream(bytes)) {
				for (final Map.Entry<String, CacheEntry> entry : sorted.entrySet()) {
					final byte[] path = entry.getKey().getBytes(StandardCharsets.UTF_8);
					output.writeInt(path.length);
					output.write(path);
					output.writeInt(entry.getValue().rawBytes.length);
					output.write(entry.getValue().rawBytes);
				}
			}
			return RouteAssetHash.sha256(bytes.toByteArray());
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to fingerprint route asset sources", exception);
		}
	}

	public void clear() {
		generation.incrementAndGet();
		cache.clear();
		locks.clear();
	}

	public static RouteAssetImage decodePng(byte[] bytes) throws IOException {
		final BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
		if (image == null) {
			throw new IOException("Unable to decode route asset source PNG");
		}
		try {
			final int width = image.getWidth();
			final int height = image.getHeight();
			final int[] pixels = new int[RouteAssetImage.checkedPixelCount(width, height)];
			for (int y = 0; y < height; y++) {
				for (int x = 0; x < width; x++) {
					pixels[y * width + x] = RouteAssetImage.argbToAbgr(image.getRGB(x, y));
				}
			}
			return RouteAssetImage.immutable(width, height, pixels);
		} catch (ArithmeticException | IllegalArgumentException exception) {
			throw new IOException("Invalid route asset source dimensions", exception);
		} finally {
			image.flush();
		}
	}

	private static String validatePath(String resourcePath) {
		final String path = Objects.requireNonNull(resourcePath, "resourcePath").trim();
		if (!RESOURCE_PATH_PATTERN.matcher(path).matches() || path.startsWith("/") || path.contains("..")) {
			throw new IllegalArgumentException("Invalid route asset source path");
		}
		return path;
	}

	@FunctionalInterface
	public interface ResourceLoader {
		byte[] load(String resourcePath) throws IOException;
	}

	@FunctionalInterface
	public interface Decoder {
		RouteAssetImage decode(byte[] bytes) throws IOException;
	}

	private static final class CacheEntry {
		private final RouteAssetImage image;
		private final byte[] rawBytes;

		private CacheEntry(RouteAssetImage image, byte[] rawBytes) {
			this.image = image;
			this.rawBytes = rawBytes;
		}
	}
}
