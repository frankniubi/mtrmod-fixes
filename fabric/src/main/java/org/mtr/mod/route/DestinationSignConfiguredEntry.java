package org.mtr.mod.route;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

public final class DestinationSignConfiguredEntry {

	private final long sourceStationId;
	private final SortedSet<Long> destinationStationIds;
	private final String customHeader;
	private final int widthBlocks;
	private final int heightBlocks;
	private final DestinationSignStyle style;
	private final boolean showEta;

	public DestinationSignConfiguredEntry(long sourceStationId, long destinationStationId, int widthBlocks, int heightBlocks, DestinationSignStyle style, boolean showEta) {
		this(sourceStationId, Set.of(destinationStationId), "", widthBlocks, heightBlocks, style, showEta);
	}

	public DestinationSignConfiguredEntry(long sourceStationId, Set<Long> destinationStationIds, String customHeader,
			int widthBlocks, int heightBlocks, DestinationSignStyle style, boolean showEta) {
		final DestinationSignStyle checkedStyle = Objects.requireNonNull(style, "style");
		final TreeSet<Long> checkedDestinations = new TreeSet<>(Objects.requireNonNull(destinationStationIds, "destinationStationIds"));
		final String checkedHeader = DestinationSignAssetSnapshot.validateCustomHeader(customHeader);
		if (sourceStationId == 0 || checkedDestinations.isEmpty() || checkedDestinations.size() > RouteAssetProtocol.MAX_DESTINATION_SIGN_DESTINATIONS
				|| checkedDestinations.contains(0L)
				|| !DestinationSignAtlasLayout.isValidFootprint(checkedStyle, widthBlocks, heightBlocks)) {
			throw new IllegalArgumentException("Invalid configured destination sign");
		}
		this.sourceStationId = sourceStationId;
		this.destinationStationIds = Collections.unmodifiableSortedSet(checkedDestinations);
		this.customHeader = checkedHeader;
		this.widthBlocks = widthBlocks;
		this.heightBlocks = heightBlocks;
		this.style = checkedStyle;
		this.showEta = showEta;
	}

	public long getSourceStationId() { return sourceStationId; }
	public long getDestinationStationId() { return destinationStationIds.first(); }
	public SortedSet<Long> getDestinationStationIds() { return destinationStationIds; }
	public String getCustomHeader() { return customHeader; }
	public int getWidthBlocks() { return widthBlocks; }
	public int getHeightBlocks() { return heightBlocks; }
	public DestinationSignStyle getStyle() { return style; }
	public boolean isShowEta() { return showEta; }

	@Override
	public boolean equals(Object object) {
		return this == object || object instanceof DestinationSignConfiguredEntry
				&& sourceStationId == ((DestinationSignConfiguredEntry) object).sourceStationId
				&& destinationStationIds.equals(((DestinationSignConfiguredEntry) object).destinationStationIds)
				&& customHeader.equals(((DestinationSignConfiguredEntry) object).customHeader)
				&& widthBlocks == ((DestinationSignConfiguredEntry) object).widthBlocks
				&& heightBlocks == ((DestinationSignConfiguredEntry) object).heightBlocks
				&& style == ((DestinationSignConfiguredEntry) object).style
				&& showEta == ((DestinationSignConfiguredEntry) object).showEta;
	}

	@Override
	public int hashCode() {
		return Objects.hash(sourceStationId, destinationStationIds, customHeader, widthBlocks, heightBlocks, style, showEta);
	}
}
