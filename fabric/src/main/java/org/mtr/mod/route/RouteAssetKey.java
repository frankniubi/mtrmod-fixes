package org.mtr.mod.route;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RouteAssetKey implements Comparable<RouteAssetKey> {

	private final String dimension;
	private final RouteAssetType type;
	private final long primaryId;
	private final RouteAssetVariant variant;
	private final String canonicalValue;

	private static final Pattern DIMENSION_PATTERN = Pattern.compile("[A-Za-z0-9_.:/-]{1,160}");

	public RouteAssetKey(String dimension, RouteAssetType type, long primaryId, RouteAssetVariant variant) {
		this.dimension = Objects.requireNonNull(dimension, "dimension").trim();
		this.type = Objects.requireNonNull(type, "type");
		this.variant = Objects.requireNonNull(variant, "variant");
		if (!DIMENSION_PATTERN.matcher(this.dimension).matches() || this.dimension.startsWith("/") || this.dimension.contains("..")) {
			throw new IllegalArgumentException("Invalid route asset dimension");
		}
		if (primaryId < 0) {
			throw new IllegalArgumentException("Route asset primary ID cannot be negative");
		}
		this.primaryId = primaryId;
		canonicalValue = this.dimension + '|' + type.name() + '|' + primaryId + '|' + variant.getResolution() + '|' + variant.getLanguage() + '|' + variant.getCanonicalParameters();
		if (canonicalValue.getBytes(StandardCharsets.UTF_8).length > RouteAssetProtocol.MAX_KEY_UTF8_BYTES) {
			throw new IllegalArgumentException("Route asset key is too large");
		}
	}

	public static RouteAssetKey parse(String value) {
		final String input = Objects.requireNonNull(value, "value");
		if (input.getBytes(StandardCharsets.UTF_8).length > RouteAssetProtocol.MAX_KEY_UTF8_BYTES) {
			throw new IllegalArgumentException("Route asset key is too large");
		}
		final String[] parts = input.split("\\|", -1);
		if (parts.length != 6) {
			throw new IllegalArgumentException("Route asset key must have six fields");
		}
		try {
			return new RouteAssetKey(parts[0], RouteAssetType.valueOf(parts[1]), Long.parseLong(parts[2]), RouteAssetVariant.parse(Integer.parseInt(parts[3]), parts[4], parts[5]));
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Invalid route asset key", exception);
		}
	}

	public RouteAssetKey withPrimaryId(long newPrimaryId) {
		return new RouteAssetKey(dimension, type, newPrimaryId, variant);
	}

	public String getDimension() {
		return dimension;
	}

	public RouteAssetType getType() {
		return type;
	}

	public long getPrimaryId() {
		return primaryId;
	}

	public RouteAssetVariant getVariant() {
		return variant;
	}

	@Override
	public int compareTo(RouteAssetKey other) {
		return canonicalValue.compareTo(other.canonicalValue);
	}

	@Override
	public String toString() {
		return canonicalValue;
	}

	@Override
	public boolean equals(Object object) {
		return this == object || object instanceof RouteAssetKey && canonicalValue.equals(((RouteAssetKey) object).canonicalValue);
	}

	@Override
	public int hashCode() {
		return canonicalValue.hashCode();
	}
}
