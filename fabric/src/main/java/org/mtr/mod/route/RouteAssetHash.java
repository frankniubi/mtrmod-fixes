package org.mtr.mod.route;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RouteAssetHash {

	private static final Pattern HASH_PATTERN = Pattern.compile("[0-9a-f]{64}");

	private RouteAssetHash() {
	}

	public static String requireValid(String hash) {
		final String value = Objects.requireNonNull(hash, "hash");
		if (!HASH_PATTERN.matcher(value).matches()) {
			throw new IllegalArgumentException("Expected a lowercase SHA-256 hash");
		}
		return value;
	}

	public static boolean isValid(String hash) {
		return hash != null && HASH_PATTERN.matcher(hash).matches();
	}

	public static String sha256(byte[] bytes) {
		try {
			final byte[] digest = MessageDigest.getInstance("SHA-256").digest(Objects.requireNonNull(bytes, "bytes"));
			final StringBuilder builder = new StringBuilder(digest.length * 2);
			for (final byte value : digest) {
				builder.append(String.format("%02x", value & 0xFF));
			}
			return builder.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
