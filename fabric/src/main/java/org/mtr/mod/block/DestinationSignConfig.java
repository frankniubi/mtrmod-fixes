package org.mtr.mod.block;

import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mod.route.RouteAssetProtocol;
import org.mtr.mod.route.DestinationSignAssetSnapshot;
import org.mtr.mod.route.DestinationSignStyle;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/** Immutable configuration stored only on the lower-left destination sign anchor. */
public final class DestinationSignConfig {

	public static final int DEFAULT_WIDTH = 3;
	public static final int DEFAULT_HEIGHT = 2;
	public static final int MAX_DESTINATIONS = RouteAssetProtocol.MAX_DESTINATION_SIGN_DESTINATIONS;
	public static final int MAX_CUSTOM_HEADER_UTF8_BYTES = RouteAssetProtocol.MAX_DESTINATION_SIGN_CUSTOM_HEADER_UTF8_BYTES;

	private static final String KEY_SOURCE_STATION_ID = "source_station_id";
	private static final String KEY_DESTINATION_STATION_ID = "destination_station_id";
	private static final String KEY_DESTINATION_STATION_IDS_COUNT = "destination_station_ids_count";
	private static final String KEY_DESTINATION_STATION_ID_PREFIX = "destination_station_id_";
	private static final String KEY_CUSTOM_HEADER = "custom_header";
	private static final String KEY_WIDTH = "width";
	private static final String KEY_HEIGHT = "height";
	private static final String KEY_STYLE = "style";
	private static final String KEY_SHOW_ETA = "show_eta";

	private final long sourceStationId;
	private final SortedSet<Long> destinationStationIds;
	private final String customHeader;
	private final int width;
	private final int height;
	private final DestinationSignStyle style;
	private final boolean showEta;

	private DestinationSignConfig(long sourceStationId, Set<Long> destinationStationIds, int width, int height,
			DestinationSignStyle style, boolean showEta, String customHeader) {
		this.style = Objects.requireNonNull(style, "style");
		DestinationSignFootprint.validateDimensions(width, height);
		if (width < style.getMinimumWidthBlocks()) throw new IllegalArgumentException("Destination sign is too narrow for its style");
		final TreeSet<Long> sortedDestinations = new TreeSet<>(Objects.requireNonNull(destinationStationIds, "destinationStationIds"));
		if (sortedDestinations.size() > MAX_DESTINATIONS || sortedDestinations.contains(0L)) throw new IllegalArgumentException("Invalid destination sign destinations");
		if ((sourceStationId == 0) != sortedDestinations.isEmpty()) throw new IllegalArgumentException("Destination sign is only partially configured");
		final String checkedHeader = DestinationSignAssetSnapshot.validateCustomHeader(customHeader);
		if (sourceStationId == 0 && !checkedHeader.isEmpty()) throw new IllegalArgumentException("Unconfigured destination sign cannot have a custom header");
		this.sourceStationId = sourceStationId;
		this.destinationStationIds = Collections.unmodifiableSortedSet(sortedDestinations);
		this.customHeader = checkedHeader;
		this.width = width;
		this.height = height;
		this.showEta = showEta;
	}

	public static DestinationSignConfig configured(long sourceStationId, long destinationStationId, int width, int height, DestinationSignStyle style, boolean showEta) {
		return configured(sourceStationId, Set.of(destinationStationId), width, height, style, showEta, "");
	}

	public static DestinationSignConfig configured(long sourceStationId, Set<Long> destinationStationIds, int width, int height,
			DestinationSignStyle style, boolean showEta, String customHeader) {
		if (sourceStationId == 0 || Objects.requireNonNull(destinationStationIds, "destinationStationIds").isEmpty()) throw new IllegalArgumentException("Configured destination sign station is not set");
		return new DestinationSignConfig(sourceStationId, destinationStationIds, width, height, style, showEta, customHeader);
	}

	public static DestinationSignConfig unconfigured(int width, int height) {
		return unconfigured(width, height, DestinationSignStyle.ARRIVAL_ORDER, true);
	}

	public static DestinationSignConfig unconfigured(int width, int height, DestinationSignStyle style, boolean showEta) {
		return new DestinationSignConfig(0, Collections.emptySet(), width, height, style, showEta, "");
	}

	public static DestinationSignConfig read(CompoundTag tag) {
		try {
			final long source = tag.getLong(KEY_SOURCE_STATION_ID);
			final TreeSet<Long> destinations = new TreeSet<>();
			final long destinationCount = tag.getLong(KEY_DESTINATION_STATION_IDS_COUNT);
			if (destinationCount < 0 || destinationCount > MAX_DESTINATIONS) throw new IllegalArgumentException("Invalid destination count");
			if (destinationCount == 0) {
				final long legacyDestination = tag.getLong(KEY_DESTINATION_STATION_ID);
				if (legacyDestination != 0) destinations.add(legacyDestination);
			} else {
				for (int index = 0; index < destinationCount; index++) {
					final long destinationId = tag.getLong(KEY_DESTINATION_STATION_ID_PREFIX + index);
					if (destinationId == 0 || !destinations.add(destinationId)) throw new IllegalArgumentException("Invalid destination id");
				}
			}
			final int width = Math.toIntExact(tag.getLong(KEY_WIDTH));
			final int height = Math.toIntExact(tag.getLong(KEY_HEIGHT));
			final DestinationSignStyle style = DestinationSignStyle.valueOf(tag.getString(KEY_STYLE));
			final boolean showEta = !tag.contains(KEY_SHOW_ETA) || tag.getBoolean(KEY_SHOW_ETA);
			return source == 0 && destinations.isEmpty() ? unconfigured(width, height, style, showEta)
					: configured(source, destinations, width, height, style, showEta, tag.getString(KEY_CUSTOM_HEADER));
		} catch (IllegalArgumentException | ArithmeticException ignored) {
			return unconfigured(DEFAULT_WIDTH, DEFAULT_HEIGHT);
		}
	}

	public void write(CompoundTag tag) {
		tag.putLong(KEY_SOURCE_STATION_ID, sourceStationId);
		tag.putLong(KEY_DESTINATION_STATION_ID, getDestinationStationId());
		tag.putLong(KEY_DESTINATION_STATION_IDS_COUNT, destinationStationIds.size());
		for (int index = 0; index < MAX_DESTINATIONS; index++) tag.remove(KEY_DESTINATION_STATION_ID_PREFIX + index);
		int destinationIndex = 0;
		for (final long destinationStationId : destinationStationIds) tag.putLong(KEY_DESTINATION_STATION_ID_PREFIX + destinationIndex++, destinationStationId);
		tag.putString(KEY_CUSTOM_HEADER, customHeader);
		tag.putLong(KEY_WIDTH, width);
		tag.putLong(KEY_HEIGHT, height);
		tag.putString(KEY_STYLE, style.name());
		tag.putBoolean(KEY_SHOW_ETA, showEta);
	}

	public boolean isConfigured() { return sourceStationId != 0 && !destinationStationIds.isEmpty(); }
	public long getSourceStationId() { return sourceStationId; }
	public long getDestinationStationId() { return destinationStationIds.isEmpty() ? 0 : destinationStationIds.first(); }
	public SortedSet<Long> getDestinationStationIds() { return destinationStationIds; }
	public String getCustomHeader() { return customHeader; }
	public int getWidth() { return width; }
	public int getHeight() { return height; }
	public DestinationSignStyle getStyle() { return style; }
	public boolean isShowEta() { return showEta; }

	@Override
	public boolean equals(Object object) {
		return this == object || object instanceof DestinationSignConfig
				&& sourceStationId == ((DestinationSignConfig) object).sourceStationId
				&& destinationStationIds.equals(((DestinationSignConfig) object).destinationStationIds)
				&& customHeader.equals(((DestinationSignConfig) object).customHeader)
				&& width == ((DestinationSignConfig) object).width
				&& height == ((DestinationSignConfig) object).height
				&& style == ((DestinationSignConfig) object).style
				&& showEta == ((DestinationSignConfig) object).showEta;
	}

	@Override
	public int hashCode() {
		return Objects.hash(sourceStationId, destinationStationIds, customHeader, width, height, style, showEta);
	}
}
