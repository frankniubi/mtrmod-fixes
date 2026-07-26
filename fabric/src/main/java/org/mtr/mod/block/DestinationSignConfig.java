package org.mtr.mod.block;

import org.mtr.mapping.holder.CompoundTag;
import org.mtr.mod.route.DestinationSignStyle;

import java.util.Objects;

/** Immutable configuration stored only on the lower-left destination sign anchor. */
public final class DestinationSignConfig {

	public static final int DEFAULT_WIDTH = 3;
	public static final int DEFAULT_HEIGHT = 2;

	private static final String KEY_SOURCE_STATION_ID = "source_station_id";
	private static final String KEY_DESTINATION_STATION_ID = "destination_station_id";
	private static final String KEY_WIDTH = "width";
	private static final String KEY_HEIGHT = "height";
	private static final String KEY_STYLE = "style";
	private static final String KEY_SHOW_ETA = "show_eta";

	private final long sourceStationId;
	private final long destinationStationId;
	private final int width;
	private final int height;
	private final DestinationSignStyle style;
	private final boolean showEta;

	private DestinationSignConfig(long sourceStationId, long destinationStationId, int width, int height, DestinationSignStyle style, boolean showEta) {
		this.style = Objects.requireNonNull(style, "style");
		DestinationSignFootprint.validateDimensions(width, height);
		if (width < style.getMinimumWidthBlocks()) throw new IllegalArgumentException("Destination sign is too narrow for its style");
		if ((sourceStationId == 0) != (destinationStationId == 0)) throw new IllegalArgumentException("Destination sign is only partially configured");
		this.sourceStationId = sourceStationId;
		this.destinationStationId = destinationStationId;
		this.width = width;
		this.height = height;
		this.showEta = showEta;
	}

	public static DestinationSignConfig configured(long sourceStationId, long destinationStationId, int width, int height, DestinationSignStyle style, boolean showEta) {
		if (sourceStationId == 0 || destinationStationId == 0) throw new IllegalArgumentException("Configured destination sign station is not set");
		return new DestinationSignConfig(sourceStationId, destinationStationId, width, height, style, showEta);
	}

	public static DestinationSignConfig unconfigured(int width, int height) {
		return unconfigured(width, height, DestinationSignStyle.ARRIVAL_ORDER, true);
	}

	public static DestinationSignConfig unconfigured(int width, int height, DestinationSignStyle style, boolean showEta) {
		return new DestinationSignConfig(0, 0, width, height, style, showEta);
	}

	public static DestinationSignConfig read(CompoundTag tag) {
		try {
			final long source = tag.getLong(KEY_SOURCE_STATION_ID);
			final long destination = tag.getLong(KEY_DESTINATION_STATION_ID);
			final int width = Math.toIntExact(tag.getLong(KEY_WIDTH));
			final int height = Math.toIntExact(tag.getLong(KEY_HEIGHT));
			final DestinationSignStyle style = DestinationSignStyle.valueOf(tag.getString(KEY_STYLE));
			final boolean showEta = !tag.contains(KEY_SHOW_ETA) || tag.getBoolean(KEY_SHOW_ETA);
			return source == 0 && destination == 0 ? unconfigured(width, height, style, showEta) : configured(source, destination, width, height, style, showEta);
		} catch (IllegalArgumentException | ArithmeticException ignored) {
			return unconfigured(DEFAULT_WIDTH, DEFAULT_HEIGHT);
		}
	}

	public void write(CompoundTag tag) {
		tag.putLong(KEY_SOURCE_STATION_ID, sourceStationId);
		tag.putLong(KEY_DESTINATION_STATION_ID, destinationStationId);
		tag.putLong(KEY_WIDTH, width);
		tag.putLong(KEY_HEIGHT, height);
		tag.putString(KEY_STYLE, style.name());
		tag.putBoolean(KEY_SHOW_ETA, showEta);
	}

	public boolean isConfigured() { return sourceStationId != 0 && destinationStationId != 0; }
	public long getSourceStationId() { return sourceStationId; }
	public long getDestinationStationId() { return destinationStationId; }
	public int getWidth() { return width; }
	public int getHeight() { return height; }
	public DestinationSignStyle getStyle() { return style; }
	public boolean isShowEta() { return showEta; }

	@Override
	public boolean equals(Object object) {
		return this == object || object instanceof DestinationSignConfig
				&& sourceStationId == ((DestinationSignConfig) object).sourceStationId
				&& destinationStationId == ((DestinationSignConfig) object).destinationStationId
				&& width == ((DestinationSignConfig) object).width
				&& height == ((DestinationSignConfig) object).height
				&& style == ((DestinationSignConfig) object).style
				&& showEta == ((DestinationSignConfig) object).showEta;
	}

	@Override
	public int hashCode() {
		return Objects.hash(sourceStationId, destinationStationId, width, height, style, showEta);
	}
}
