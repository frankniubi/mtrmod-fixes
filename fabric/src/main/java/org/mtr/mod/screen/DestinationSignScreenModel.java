package org.mtr.mod.screen;

import org.mtr.mod.block.DestinationSignConfig;
import org.mtr.mod.block.DestinationSignFootprint;
import org.mtr.mod.route.DestinationSignAtlasLayout;
import org.mtr.mod.route.DestinationSignAssetSnapshot;
import org.mtr.mod.route.DestinationSignDirectServiceModel;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.DestinationSignTopology;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Shared mutable draft used by the content and style pages. */
public final class DestinationSignScreenModel {

	private final long sourceStationId;
	private final String sourceStationName;
	private final DestinationSignTopology topology;
	private final List<DestinationSignTopology.StationZone> destinations;
	private long destinationStationId;
	private int width;
	private int height;
	private DestinationSignStyle style;
	private boolean showEta;

	public DestinationSignScreenModel(long sourceStationId, String sourceStationName, DestinationSignTopology topology, DestinationSignConfig initial) {
		if (sourceStationId == 0) throw new IllegalArgumentException("Destination sign source station is not set");
		this.sourceStationId = sourceStationId;
		this.sourceStationName = Objects.requireNonNull(sourceStationName, "sourceStationName");
		this.topology = Objects.requireNonNull(topology, "topology");
		destinations = DestinationSignDirectServiceModel.reachableDestinations(topology, sourceStationId);
		width = initial.getWidth();
		height = initial.getHeight();
		style = initial.getStyle();
		showEta = initial.isShowEta();
		if (initial.getDestinationStationId() != 0 && containsDestination(initial.getDestinationStationId())) destinationStationId = initial.getDestinationStationId();
	}

	public void selectDestination(long stationId) {
		if (!containsDestination(stationId)) throw new IllegalArgumentException("Destination is not directly reachable");
		destinationStationId = stationId;
	}

	public void adjustWidth(int delta) {
		if (canAdjustWidth(delta)) width += Integer.signum(delta);
	}

	public void adjustHeight(int delta) {
		if (canAdjustHeight(delta)) height += Integer.signum(delta);
	}

	public boolean canAdjustWidth(int delta) {
		final int candidate = width + Integer.signum(delta);
		return delta != 0 && candidate >= Math.max(DestinationSignFootprint.MIN_WIDTH, style.getMinimumWidthBlocks())
				&& candidate <= DestinationSignFootprint.MAX_WIDTH && DestinationSignFootprint.isValidDimensions(candidate, height);
	}

	public boolean canAdjustHeight(int delta) {
		final int candidate = height + Integer.signum(delta);
		return delta != 0 && candidate >= DestinationSignFootprint.MIN_HEIGHT && candidate <= DestinationSignFootprint.MAX_HEIGHT
				&& DestinationSignFootprint.isValidDimensions(width, candidate);
	}

	public void setStyle(DestinationSignStyle style) {
		this.style = Objects.requireNonNull(style, "style");
		width = Math.max(width, style.getMinimumWidthBlocks());
	}

	public void setShowEta(boolean showEta) { this.showEta = showEta; }

	public Footprint minimumFootprint(DestinationSignStyle candidateStyle) {
		return findMinimumFootprint(candidateStyle).orElseThrow(() -> new IllegalStateException("Direct services exceed destination sign render limits"));
	}

	public Optional<Footprint> findMinimumFootprint(DestinationSignStyle candidateStyle) {
		final int minimumWidth = Math.max(DestinationSignFootprint.MIN_WIDTH, candidateStyle.getMinimumWidthBlocks());
		for (int candidateHeight = DestinationSignFootprint.MIN_HEIGHT; candidateHeight <= DestinationSignFootprint.MAX_HEIGHT; candidateHeight++) {
			for (int candidateWidth = minimumWidth; candidateWidth <= DestinationSignFootprint.MAX_WIDTH; candidateWidth++) {
				if (destinationStationId == 0) {
					if (DestinationSignAtlasLayout.isValidFootprint(candidateStyle, candidateWidth, candidateHeight)) return Optional.of(new Footprint(candidateWidth, candidateHeight));
				} else if (projectedSnapshot(candidateStyle, candidateWidth, candidateHeight) != null) {
					return Optional.of(new Footprint(candidateWidth, candidateHeight));
				}
			}
		}
		return Optional.empty();
	}

	public boolean canSave() {
		final DestinationSignAssetSnapshot snapshot = projectedSnapshot(style, width, height);
		return snapshot != null && !snapshot.getModel().getOptions().isEmpty();
	}

	public DestinationSignConfig toConfig() {
		if (!canSave()) throw new IllegalStateException("Destination sign draft is incomplete or undersized");
		return DestinationSignConfig.configured(sourceStationId, destinationStationId, width, height, style, showEta);
	}

	private DestinationSignAssetSnapshot projectedSnapshot(DestinationSignStyle candidateStyle, int candidateWidth, int candidateHeight) {
		if (destinationStationId == 0) return null;
		try {
			return DestinationSignAssetSnapshot.create(topology, sourceStationId, destinationStationId, candidateStyle, candidateWidth, candidateHeight, showEta);
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private boolean containsDestination(long stationId) {
		return stationId != 0 && destinations.stream().anyMatch(destination -> destination.getId() == stationId);
	}

	public long getSourceStationId() { return sourceStationId; }
	public String getSourceStationName() { return sourceStationName; }
	public List<DestinationSignTopology.StationZone> getDestinations() { return destinations; }
	public long getDestinationStationId() { return destinationStationId; }
	public int getWidth() { return width; }
	public int getHeight() { return height; }
	public DestinationSignStyle getStyle() { return style; }
	public boolean isShowEta() { return showEta; }

	public static final class Footprint {
		private final int width;
		private final int height;

		public Footprint(int width, int height) { this.width = width; this.height = height; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
		@Override public boolean equals(Object object) { return this == object || object instanceof Footprint && width == ((Footprint) object).width && height == ((Footprint) object).height; }
		@Override public int hashCode() { return 31 * width + height; }
		@Override public String toString() { return width + "x" + height; }
	}
}
