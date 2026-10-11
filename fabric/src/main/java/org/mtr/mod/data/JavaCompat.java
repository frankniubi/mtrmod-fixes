package org.mtr.mod.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Java 8 stand-ins for the Java 9+ factories used by this fork, so the shared sources also compile for Minecraft 1.16.5.
 * Same contracts as {@code List.of} and friends: unmodifiable results, nulls rejected, duplicate set elements or map keys rejected.
 */
public final class JavaCompat {

	@SafeVarargs
	public static <T> List<T> listOf(T... values) {
		return listCopyOf(Arrays.asList(values));
	}

	public static <T> List<T> listCopyOf(Collection<? extends T> values) {
		final List<T> list = new ArrayList<>(values.size());
		for (final T value : values) {
			list.add(Objects.requireNonNull(value));
		}
		return Collections.unmodifiableList(list);
	}

	@SafeVarargs
	public static <T> Set<T> setOf(T... values) {
		final Set<T> set = new LinkedHashSet<>(values.length * 2);
		for (final T value : values) {
			if (!set.add(Objects.requireNonNull(value))) {
				throw new IllegalArgumentException("duplicate element: " + value);
			}
		}
		return Collections.unmodifiableSet(set);
	}

	public static <T> Set<T> setCopyOf(Collection<? extends T> values) {
		final Set<T> set = new LinkedHashSet<>(values.size() * 2);
		for (final T value : values) {
			set.add(Objects.requireNonNull(value));
		}
		return Collections.unmodifiableSet(set);
	}

	/**
	 * @param keysAndValues alternating keys and values
	 */
	@SuppressWarnings("unchecked")
	public static <K, V> Map<K, V> mapOf(Object... keysAndValues) {
		if (keysAndValues.length % 2 != 0) {
			throw new IllegalArgumentException("keys and values must be paired");
		}
		final Map<K, V> map = new LinkedHashMap<>(keysAndValues.length);
		for (int i = 0; i < keysAndValues.length; i += 2) {
			if (map.put((K) Objects.requireNonNull(keysAndValues[i]), (V) Objects.requireNonNull(keysAndValues[i + 1])) != null) {
				throw new IllegalArgumentException("duplicate key: " + keysAndValues[i]);
			}
		}
		return Collections.unmodifiableMap(map);
	}

	public static String readString(Path path) throws IOException {
		return readString(path, StandardCharsets.UTF_8);
	}

	public static String readString(Path path, Charset charset) throws IOException {
		return new String(Files.readAllBytes(path), charset);
	}

	public static String repeat(String text, int count) {
		if (count < 0) {
			throw new IllegalArgumentException("count is negative: " + count);
		}
		final StringBuilder stringBuilder = new StringBuilder(text.length() * count);
		for (int i = 0; i < count; i++) {
			stringBuilder.append(text);
		}
		return stringBuilder.toString();
	}

	/**
	 * Reads up to {@code length} bytes, stopping early only at end of stream.
	 */
	public static byte[] readNBytes(InputStream inputStream, int length) throws IOException {
		final ByteArrayOutputStream outputStream = new ByteArrayOutputStream(Math.min(length, 8192));
		final byte[] buffer = new byte[8192];
		int remaining = length;
		while (remaining > 0) {
			final int read = inputStream.read(buffer, 0, Math.min(buffer.length, remaining));
			if (read < 0) {
				break;
			}
			outputStream.write(buffer, 0, read);
			remaining -= read;
		}
		return outputStream.toByteArray();
	}

	private JavaCompat() {
	}
}
