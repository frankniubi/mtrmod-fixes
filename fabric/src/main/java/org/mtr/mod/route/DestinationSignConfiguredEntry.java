package org.mtr.mod.route;

import java.util.Objects;

public final class DestinationSignConfiguredEntry {

	private final long sourceStationId;
	private final long destinationStationId;
	private final int widthBlocks;
	private final int heightBlocks;
	private final DestinationSignStyle style;
	private final boolean showEta;

	public DestinationSignConfiguredEntry(long sourceStationId, long destinationStationId, int widthBlocks, int heightBlocks, DestinationSignStyle style, boolean showEta) {
		final DestinationSignStyle checkedStyle = Objects.requireNonNull(style, "style");
		if (sourceStationId == 0 || destinationStationId == 0
				|| widthBlocks < Math.max(DestinationSignAtlasLayout.MIN_WIDTH_BLOCKS, checkedStyle.getMinimumWidthBlocks())
				|| widthBlocks > DestinationSignAtlasLayout.MAX_WIDTH_BLOCKS
				|| heightBlocks < DestinationSignAtlasLayout.MIN_HEIGHT_BLOCKS || heightBlocks > DestinationSignAtlasLayout.MAX_HEIGHT_BLOCKS) {
			throw new IllegalArgumentException("Invalid configured destination sign");
		}
		this.sourceStationId = sourceStationId;
		this.destinationStationId = destinationStationId;
		this.widthBlocks = widthBlocks;
		this.heightBlocks = heightBlocks;
		this.style = checkedStyle;
		this.showEta = showEta;
	}

	public long getSourceStationId() { return sourceStationId; }
	public long getDestinationStationId() { return destinationStationId; }
	public int getWidthBlocks() { return widthBlocks; }
	public int getHeightBlocks() { return heightBlocks; }
	public DestinationSignStyle getStyle() { return style; }
	public boolean isShowEta() { return showEta; }

	@Override
	public boolean equals(Object object) {
		return this == object || object instanceof DestinationSignConfiguredEntry
				&& sourceStationId == ((DestinationSignConfiguredEntry) object).sourceStationId
				&& destinationStationId == ((DestinationSignConfiguredEntry) object).destinationStationId
				&& widthBlocks == ((DestinationSignConfiguredEntry) object).widthBlocks
				&& heightBlocks == ((DestinationSignConfiguredEntry) object).heightBlocks
				&& style == ((DestinationSignConfiguredEntry) object).style
				&& showEta == ((DestinationSignConfiguredEntry) object).showEta;
	}

	@Override
	public int hashCode() {
		return Objects.hash(sourceStationId, destinationStationId, widthBlocks, heightBlocks, style, showEta);
	}
}
