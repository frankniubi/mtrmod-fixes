package org.mtr.mod.route;

import org.mtr.mapping.holder.CompoundTag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** World-scoped identities for signs which require proactive server assets. */
public final class ConfiguredSignAssetIndex {

	public static final int MAX_ENTRIES = 100_000;

	private static final String KEY_COUNT = "configured_sign_asset_count";
	private static final String KEY_PREFIX = "configured_sign_asset_";
	private final TreeMap<Long, StoredEntry> entries = new TreeMap<>();

	public synchronized boolean configureRouteSign(long anchorPosition, long platformId, RouteSignStyleMode styleMode) {
		final RouteSignStyleMode checkedMode = Objects.requireNonNull(styleMode, "styleMode");
		if (!checkedMode.isExplicit()) return entries.remove(anchorPosition) != null;
		if (platformId == 0) throw new IllegalArgumentException("Configured Route Sign platform is not set");
		return put(anchorPosition, StoredEntry.routeSign(anchorPosition, platformId, checkedMode));
	}

	public synchronized boolean configureDestinationSign(long anchorPosition, DestinationSignConfiguredEntry destinationSign) {
		return put(anchorPosition, StoredEntry.destinationSign(anchorPosition, Objects.requireNonNull(destinationSign, "destinationSign")));
	}

	private boolean put(long anchorPosition, StoredEntry replacement) {
		final StoredEntry previous = entries.put(anchorPosition, replacement);
		if (previous == null && entries.size() > MAX_ENTRIES) {
			entries.remove(anchorPosition);
			throw new IllegalStateException("Too many configured sign assets");
		}
		return !replacement.equals(previous);
	}

	public synchronized boolean remove(long anchorPosition) { return entries.remove(anchorPosition) != null; }
	public synchronized int size() { return entries.size(); }

	public synchronized List<Entry> snapshot(String dimension) {
		final String checkedDimension = Objects.requireNonNull(dimension, "dimension").trim();
		if (checkedDimension.isEmpty()) throw new IllegalArgumentException("Configured sign dimension is empty");
		final List<Entry> result = new ArrayList<>(entries.size());
		entries.values().forEach(entry -> result.add(new Entry(checkedDimension, entry)));
		return Collections.unmodifiableList(result);
	}

	public synchronized void read(CompoundTag compoundTag) {
		entries.clear();
		final int count = (int) Math.min(Math.max(0, compoundTag.getLong(KEY_COUNT)), MAX_ENTRIES);
		for (int index = 0; index < count; index++) {
			final String prefix = KEY_PREFIX + index + "_";
			try {
				final SignType type = SignType.valueOf(compoundTag.getString(prefix + "type"));
				final long anchor = compoundTag.getLong(prefix + "anchor");
				if (entries.containsKey(anchor)) continue;
				if (type == SignType.ROUTE_SIGN) {
					final long platformId = compoundTag.getLong(prefix + "primary_id");
					final RouteSignStyleMode style = RouteSignStyleMode.fromPersisted(compoundTag.getString(prefix + "style"));
					if (platformId != 0 && style.isExplicit()) entries.put(anchor, StoredEntry.routeSign(anchor, platformId, style));
				} else {
					final DestinationSignConfiguredEntry destination = new DestinationSignConfiguredEntry(
							compoundTag.getLong(prefix + "source_id"), compoundTag.getLong(prefix + "destination_id"),
							Math.toIntExact(compoundTag.getLong(prefix + "width")), Math.toIntExact(compoundTag.getLong(prefix + "height")),
							DestinationSignStyle.valueOf(compoundTag.getString(prefix + "style")), compoundTag.getLong(prefix + "eta") == 1);
					entries.put(anchor, StoredEntry.destinationSign(anchor, destination));
				}
			} catch (IllegalArgumentException | ArithmeticException ignored) {
				// A malformed entry is skipped without discarding other valid anchors.
			}
		}
	}

	public synchronized void write(CompoundTag compoundTag) {
		final int previousCount = (int) Math.min(Math.max(0, compoundTag.getLong(KEY_COUNT)), MAX_ENTRIES);
		int index = 0;
		for (final StoredEntry entry : entries.values()) {
			final String prefix = KEY_PREFIX + index++ + "_";
			clearEntry(compoundTag, prefix);
			compoundTag.putString(prefix + "type", entry.type.name());
			compoundTag.putLong(prefix + "anchor", entry.anchorPosition);
			if (entry.type == SignType.ROUTE_SIGN) {
				compoundTag.putLong(prefix + "primary_id", entry.primaryId);
				compoundTag.putString(prefix + "style", entry.routeSignStyleMode.name());
			} else {
				compoundTag.putLong(prefix + "source_id", entry.destinationSign.getSourceStationId());
				compoundTag.putLong(prefix + "destination_id", entry.destinationSign.getDestinationStationId());
				compoundTag.putLong(prefix + "width", entry.destinationSign.getWidthBlocks());
				compoundTag.putLong(prefix + "height", entry.destinationSign.getHeightBlocks());
				compoundTag.putString(prefix + "style", entry.destinationSign.getStyle().name());
				compoundTag.putLong(prefix + "eta", entry.destinationSign.isShowEta() ? 1 : 0);
			}
		}
		for (int stale = index; stale < previousCount; stale++) clearEntry(compoundTag, KEY_PREFIX + stale + "_");
		compoundTag.putLong(KEY_COUNT, index);
	}

	private static void clearEntry(CompoundTag tag, String prefix) {
		for (final String field : List.of("type", "anchor", "primary_id", "source_id", "destination_id", "width", "height", "style", "eta")) tag.remove(prefix + field);
	}

	public enum SignType { ROUTE_SIGN, DESTINATION_SIGN }

	public static final class Entry implements Comparable<Entry> {
		private final String dimension;
		private final long anchorPosition;
		private final SignType type;
		private final long primaryId;
		private final RouteSignStyleMode routeSignStyleMode;
		private final DestinationSignConfiguredEntry destinationSign;

		private Entry(String dimension, StoredEntry stored) {
			this.dimension = dimension;
			anchorPosition = stored.anchorPosition;
			type = stored.type;
			primaryId = stored.primaryId;
			routeSignStyleMode = stored.routeSignStyleMode;
			destinationSign = stored.destinationSign;
		}

		public String getDimension() { return dimension; }
		public long getAnchorPosition() { return anchorPosition; }
		public SignType getType() { return type; }
		public boolean isRouteSign() { return type == SignType.ROUTE_SIGN; }
		public boolean isDestinationSign() { return type == SignType.DESTINATION_SIGN; }
		public long getPrimaryId() { return primaryId; }
		public RouteSignStyleMode getRouteSignStyleMode() {
			if (!isRouteSign()) throw new IllegalStateException("Configured asset is not a Route Sign");
			return routeSignStyleMode;
		}
		public DestinationSignConfiguredEntry getDestinationSign() {
			if (!isDestinationSign()) throw new IllegalStateException("Configured asset is not a Destination Sign");
			return destinationSign;
		}

		public String canonicalAssetIdentity() {
			if (isRouteSign()) return dimension + "|ROUTE_SIGN|" + primaryId + "|" + routeSignStyleMode.name();
			return dimension + "|DESTINATION_SIGN|" + destinationSign.getSourceStationId() + '|' + destinationSign.getDestinationStationId() + '|'
					+ destinationSign.getStyle().name() + '|' + destinationSign.getWidthBlocks() + '|' + destinationSign.getHeightBlocks() + '|' + destinationSign.isShowEta();
		}

		@Override public int compareTo(Entry other) {
			final int dimensionComparison = dimension.compareTo(other.dimension);
			return dimensionComparison == 0 ? Long.compare(anchorPosition, other.anchorPosition) : dimensionComparison;
		}
		@Override public boolean equals(Object object) {
			return this == object || object instanceof Entry && dimension.equals(((Entry) object).dimension) && anchorPosition == ((Entry) object).anchorPosition
					&& type == ((Entry) object).type && primaryId == ((Entry) object).primaryId && routeSignStyleMode == ((Entry) object).routeSignStyleMode
					&& Objects.equals(destinationSign, ((Entry) object).destinationSign);
		}
		@Override public int hashCode() { return Objects.hash(dimension, anchorPosition, type, primaryId, routeSignStyleMode, destinationSign); }
	}

	private static final class StoredEntry {
		private final long anchorPosition;
		private final SignType type;
		private final long primaryId;
		private final RouteSignStyleMode routeSignStyleMode;
		private final DestinationSignConfiguredEntry destinationSign;

		private StoredEntry(long anchorPosition, SignType type, long primaryId, RouteSignStyleMode routeSignStyleMode, DestinationSignConfiguredEntry destinationSign) {
			this.anchorPosition = anchorPosition;
			this.type = type;
			this.primaryId = primaryId;
			this.routeSignStyleMode = routeSignStyleMode;
			this.destinationSign = destinationSign;
		}
		private static StoredEntry routeSign(long anchor, long platformId, RouteSignStyleMode style) { return new StoredEntry(anchor, SignType.ROUTE_SIGN, platformId, style, null); }
		private static StoredEntry destinationSign(long anchor, DestinationSignConfiguredEntry destination) { return new StoredEntry(anchor, SignType.DESTINATION_SIGN, destination.getSourceStationId(), null, destination); }
		@Override public boolean equals(Object object) {
			return this == object || object instanceof StoredEntry && anchorPosition == ((StoredEntry) object).anchorPosition && type == ((StoredEntry) object).type
					&& primaryId == ((StoredEntry) object).primaryId && routeSignStyleMode == ((StoredEntry) object).routeSignStyleMode
					&& Objects.equals(destinationSign, ((StoredEntry) object).destinationSign);
		}
		@Override public int hashCode() { return Objects.hash(anchorPosition, type, primaryId, routeSignStyleMode, destinationSign); }
	}
}
