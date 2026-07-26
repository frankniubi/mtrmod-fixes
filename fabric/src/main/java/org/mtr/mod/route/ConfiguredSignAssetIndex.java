package org.mtr.mod.route;

import org.mtr.mapping.holder.CompoundTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * World-scoped identities for signs which require proactive server assets.
 */
public final class ConfiguredSignAssetIndex {

	public static final int MAX_ENTRIES = 100_000;

	private static final String KEY_COUNT = "configured_sign_asset_count";
	private static final String KEY_PREFIX = "configured_sign_asset_";
	private static final String TYPE_ROUTE_SIGN = "ROUTE_SIGN";

	private final TreeMap<Long, StoredEntry> entries = new TreeMap<>();

	public synchronized boolean configureRouteSign(long anchorPosition, long platformId, RouteSignStyleMode styleMode) {
		final RouteSignStyleMode checkedMode = Objects.requireNonNull(styleMode, "styleMode");
		if (!checkedMode.isExplicit()) return entries.remove(anchorPosition) != null;
		final StoredEntry replacement = new StoredEntry(anchorPosition, platformId, checkedMode);
		final StoredEntry previous = entries.put(anchorPosition, replacement);
		if (previous == null && entries.size() > MAX_ENTRIES) {
			entries.remove(anchorPosition);
			throw new IllegalStateException("Too many configured sign assets");
		}
		return !replacement.equals(previous);
	}

	public synchronized boolean remove(long anchorPosition) {
		return entries.remove(anchorPosition) != null;
	}

	public synchronized int size() {
		return entries.size();
	}

	public synchronized List<Entry> snapshot(String dimension) {
		final String checkedDimension = Objects.requireNonNull(dimension, "dimension").trim();
		if (checkedDimension.isEmpty()) throw new IllegalArgumentException("Configured sign dimension is empty");
		final List<Entry> result = new ArrayList<>(entries.size());
		entries.values().forEach(entry -> result.add(new Entry(checkedDimension, entry.anchorPosition, entry.primaryId, entry.styleMode)));
		return Collections.unmodifiableList(result);
	}

	public synchronized void read(CompoundTag compoundTag) {
		entries.clear();
		final long rawCount = compoundTag.getLong(KEY_COUNT);
		final int count = (int) Math.min(Math.max(0, rawCount), MAX_ENTRIES);
		for (int index = 0; index < count; index++) {
			final String prefix = KEY_PREFIX + index + "_";
			if (!TYPE_ROUTE_SIGN.equals(compoundTag.getString(prefix + "type"))) continue;
			final RouteSignStyleMode styleMode = RouteSignStyleMode.fromPersisted(compoundTag.getString(prefix + "style"));
			if (!styleMode.isExplicit()) continue;
			final long anchorPosition = compoundTag.getLong(prefix + "anchor");
			entries.put(anchorPosition, new StoredEntry(anchorPosition, compoundTag.getLong(prefix + "primary_id"), styleMode));
		}
	}

	public synchronized void write(CompoundTag compoundTag) {
		final int previousCount = (int) Math.min(Math.max(0, compoundTag.getLong(KEY_COUNT)), MAX_ENTRIES);
		int index = 0;
		for (final StoredEntry entry : entries.values()) {
			final String prefix = KEY_PREFIX + index++ + "_";
			compoundTag.putString(prefix + "type", TYPE_ROUTE_SIGN);
			compoundTag.putLong(prefix + "anchor", entry.anchorPosition);
			compoundTag.putLong(prefix + "primary_id", entry.primaryId);
			compoundTag.putString(prefix + "style", entry.styleMode.name());
		}
		for (int stale = index; stale < previousCount; stale++) {
			final String prefix = KEY_PREFIX + stale + "_";
			compoundTag.remove(prefix + "type");
			compoundTag.remove(prefix + "anchor");
			compoundTag.remove(prefix + "primary_id");
			compoundTag.remove(prefix + "style");
		}
		compoundTag.putLong(KEY_COUNT, index);
	}

	public static final class Entry implements Comparable<Entry> {
		private final String dimension;
		private final long anchorPosition;
		private final long primaryId;
		private final RouteSignStyleMode routeSignStyleMode;

		private Entry(String dimension, long anchorPosition, long primaryId, RouteSignStyleMode routeSignStyleMode) {
			this.dimension = dimension;
			this.anchorPosition = anchorPosition;
			this.primaryId = primaryId;
			this.routeSignStyleMode = routeSignStyleMode;
		}

		public String getDimension() { return dimension; }
		public long getAnchorPosition() { return anchorPosition; }
		public long getPrimaryId() { return primaryId; }
		public RouteSignStyleMode getRouteSignStyleMode() { return routeSignStyleMode; }

		public String canonicalAssetIdentity() {
			return dimension + "|" + TYPE_ROUTE_SIGN + "|" + primaryId + "|" + routeSignStyleMode.name();
		}

		@Override
		public int compareTo(Entry other) {
			int comparison = dimension.compareTo(other.dimension);
			if (comparison == 0) comparison = Long.compare(anchorPosition, other.anchorPosition);
			return comparison;
		}

		@Override
		public boolean equals(Object object) {
			return this == object || object instanceof Entry && dimension.equals(((Entry) object).dimension)
					&& anchorPosition == ((Entry) object).anchorPosition && primaryId == ((Entry) object).primaryId
					&& routeSignStyleMode == ((Entry) object).routeSignStyleMode;
		}

		@Override
		public int hashCode() {
			return Objects.hash(dimension, anchorPosition, primaryId, routeSignStyleMode);
		}
	}

	private static final class StoredEntry {
		private final long anchorPosition;
		private final long primaryId;
		private final RouteSignStyleMode styleMode;

		private StoredEntry(long anchorPosition, long primaryId, RouteSignStyleMode styleMode) {
			this.anchorPosition = anchorPosition;
			this.primaryId = primaryId;
			this.styleMode = styleMode;
		}

		@Override
		public boolean equals(Object object) {
			return this == object || object instanceof StoredEntry && anchorPosition == ((StoredEntry) object).anchorPosition
					&& primaryId == ((StoredEntry) object).primaryId && styleMode == ((StoredEntry) object).styleMode;
		}

		@Override
		public int hashCode() {
			return Objects.hash(anchorPosition, primaryId, styleMode);
		}
	}
}
