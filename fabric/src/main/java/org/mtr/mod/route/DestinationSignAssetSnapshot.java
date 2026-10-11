package org.mtr.mod.route;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/** Static-only atlas input. Live train state is intentionally not representable here. */
public final class DestinationSignAssetSnapshot {

	public static final String LEAVING_TEXT = "\u5c06\u79bb|Leaving";
	public static final String CURRENT_TEXT = "\u672c\u7ad9|HERE";
	public static final String NO_DIRECT_SERVICE_TEXT = "\u5f53\u524d\u65e0\u76f4\u8fbe\u670d\u52a1|No direct service";
	public static final String NO_SERVICE_TEXT = "\u6682\u65e0\u73ed\u6b21|No service";
	public static final String UNAVAILABLE_TEXT = "--|--";

	private final long sourceStationId;
	private final String sourceStationName;
	private final long destinationStationId;
	private final SortedSet<Long> destinationStationIds;
	private final String customHeader;
	private final String destinationStationName;
	private final DestinationSignStyle style;
	private final int widthBlocks;
	private final int heightBlocks;
	private final int routesPerBlockHeight;
	private final boolean showEta;
	private final DestinationSignDirectServiceModel.Model model;
	private final DestinationSignAtlasLayout.Layout layout;
	private final List<RouteStripRecord> routeStrips;
	private final List<Sprite> sprites;
	private final int atlasWidth;
	private final int atlasHeight;

	private DestinationSignAssetSnapshot(long sourceStationId, String sourceStationName, Set<Long> destinationStationIds, String customHeader, String destinationStationName,
			DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta, int routesPerBlockHeight,
			DestinationSignDirectServiceModel.Model model) {
		this.sourceStationId = sourceStationId;
		this.sourceStationName = validateField(sourceStationName);
		this.destinationStationIds = Collections.unmodifiableSortedSet(new TreeSet<>(destinationStationIds));
		destinationStationId = this.destinationStationIds.first();
		this.customHeader = validateCustomHeader(customHeader);
		this.destinationStationName = validateField(destinationStationName);
		this.style = Objects.requireNonNull(style, "style");
		this.widthBlocks = widthBlocks;
		this.heightBlocks = heightBlocks;
		this.routesPerBlockHeight = routesPerBlockHeight;
		this.showEta = showEta;
		this.model = Objects.requireNonNull(model, "model");
		layout = DestinationSignAtlasLayout.create(model, style, widthBlocks, heightBlocks, showEta, routesPerBlockHeight);
		atlasWidth = layout.getSurfaceWidth();
		final DestinationSignRouteStripLayout.RowMetrics rowMetrics = DestinationSignRouteStripLayout.rowMetrics(widthBlocks, layout.getRowHeight(), showEta);
		final List<RouteStripRecord> mutableRouteStrips = new ArrayList<>();
		for (final DestinationSignDirectServiceModel.Option option : model.getOptions()) {
			mutableRouteStrips.add(new RouteStripRecord(option.getKey(), validateField(option.getRoute().getDisplayName()), option.getRoute().getColor(),
					validateField(option.getSource().getPlatformDisplayName()), rowMetrics, DestinationSignRouteStripLayout.project(option, rowMetrics)));
		}
		routeStrips = Collections.unmodifiableList(mutableRouteStrips);
		final List<Sprite> mutableSprites = new ArrayList<>();
		int y = 0;
		final int headerCycles = Math.max(2, segmentCount(this.destinationStationName));
		for (int phase = 0; phase < headerCycles; phase++) {
			mutableSprites.add(new Sprite(SpriteKind.HEADER, null, null, phase, y, layout.getHeaderHeight()));
			y = Math.addExact(y, layout.getHeaderHeight());
		}
		for (int optionIndex = 0; optionIndex < model.getOptions().size(); optionIndex++) {
			final DestinationSignDirectServiceModel.Option option = model.getOptions().get(optionIndex);
			final RouteStripRecord routeStrip = routeStrips.get(optionIndex);
			int cycles = Math.max(segmentCount(routeStrip.routeName), segmentCount(routeStrip.platformName));
			for (final DestinationSignRouteStripLayout.LabelSlot label : routeStrip.routeStrip.getLabels()) {
				cycles = Math.max(cycles, segmentCount(label.getRole() == DestinationSignRouteStripLayout.MarkerRole.CURRENT ? CURRENT_TEXT : validateField(label.getStationName())));
			}
			for (int phase = 0; phase < cycles; phase++) {
				mutableSprites.add(new Sprite(SpriteKind.ROW, option, routeStrip, phase, y, layout.getRowHeight()));
				y = Math.addExact(y, layout.getRowHeight());
			}
		}
		for (final SpriteKind label : List.of(SpriteKind.LEAVING, SpriteKind.NO_DIRECT_SERVICE, SpriteKind.NO_SERVICE, SpriteKind.UNAVAILABLE)) {
			for (int phase = 0; phase < 2; phase++) {
				mutableSprites.add(new Sprite(label, null, null, phase, y, layout.getRowHeight()));
				y = Math.addExact(y, layout.getRowHeight());
			}
		}
		if (mutableSprites.size() > RouteAssetProtocol.MAX_DESTINATION_SIGN_ATLAS_SPRITES) throw new IllegalArgumentException("Destination sign atlas has too many sprites");
		sprites = Collections.unmodifiableList(mutableSprites);
		atlasHeight = y;
		validateRenderBounds();
	}

	public static DestinationSignAssetSnapshot create(DestinationSignTopology topology, long sourceStationId, long destinationStationId,
			DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta) {
		return create(topology, sourceStationId, Set.of(destinationStationId), "", style, widthBlocks, heightBlocks, showEta,
				RouteAssetProtocol.DEFAULT_DESTINATION_SIGN_ROUTES_PER_BLOCK_HEIGHT);
	}

	public static DestinationSignAssetSnapshot create(DestinationSignTopology topology, long sourceStationId, long destinationStationId,
			DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta, int routesPerBlockHeight) {
		return create(topology, sourceStationId, Set.of(destinationStationId), "", style, widthBlocks, heightBlocks, showEta, routesPerBlockHeight);
	}

	public static DestinationSignAssetSnapshot create(DestinationSignTopology topology, long sourceStationId, Set<Long> destinationStationIds,
			String customHeader, DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta) {
		return create(topology, sourceStationId, destinationStationIds, customHeader, style, widthBlocks, heightBlocks, showEta,
				RouteAssetProtocol.DEFAULT_DESTINATION_SIGN_ROUTES_PER_BLOCK_HEIGHT);
	}

	public static DestinationSignAssetSnapshot create(DestinationSignTopology topology, long sourceStationId, Set<Long> destinationStationIds,
			String customHeader, DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta, int routesPerBlockHeight) {
		final DestinationSignTopology checkedTopology = Objects.requireNonNull(topology, "topology");
		final DestinationSignTopology.StationZone source = checkedTopology.getStation(sourceStationId).orElseThrow(() -> new IllegalArgumentException("Unknown destination sign source station"));
		final TreeSet<Long> sortedDestinations = new TreeSet<>(Objects.requireNonNull(destinationStationIds, "destinationStationIds"));
		if (sortedDestinations.isEmpty()) throw new IllegalArgumentException("Destination sign has no destinations");
		final List<DestinationSignTopology.StationZone> destinations = new ArrayList<>();
		for (final long destinationStationId : sortedDestinations) {
			destinations.add(checkedTopology.getStation(destinationStationId).orElseThrow(() -> new IllegalArgumentException("Unknown destination sign destination station")));
		}
		final String checkedHeader = Objects.requireNonNull(customHeader, "customHeader");
		final String header = checkedHeader.isEmpty() ? automaticHeader(destinations) : checkedHeader;
		return new DestinationSignAssetSnapshot(sourceStationId, source.getDisplayName(), sortedDestinations, checkedHeader, header,
				style, widthBlocks, heightBlocks, showEta, routesPerBlockHeight,
				DestinationSignDirectServiceModel.project(checkedTopology, sourceStationId, sortedDestinations));
	}

	private static String automaticHeader(List<DestinationSignTopology.StationZone> destinations) {
		final List<String> primary = new ArrayList<>();
		final List<String> english = new ArrayList<>();
		for (final DestinationSignTopology.StationZone destination : destinations) {
			final String[] segments = destination.getDisplayName().split("\\|", -1);
			final String primaryName = segments.length == 0 ? "" : segments[0];
			primary.add(primaryName);
			english.add(segments.length > 1 && !segments[1].isEmpty() ? segments[1] : primaryName);
		}
		return "\u5f80" + String.join("/", primary) + "\u65b9\u5411|To " + String.join("/", english);
	}

	private static String validateField(String value) {
		final String checked = Objects.requireNonNull(value, "value");
		if (checked.getBytes(StandardCharsets.UTF_8).length > RouteAssetProtocol.MAX_DESTINATION_SIGN_FIELD_UTF8_BYTES || segmentCount(checked) > RouteAssetProtocol.MAX_DESTINATION_SIGN_PIPE_SEGMENTS) {
			throw new IllegalArgumentException("Destination sign stable field exceeds protocol limits");
		}
		return checked;
	}

	public static String validateCustomHeader(String value) {
		final String checked = Objects.requireNonNull(value, "customHeader");
		if (checked.getBytes(StandardCharsets.UTF_8).length > RouteAssetProtocol.MAX_DESTINATION_SIGN_CUSTOM_HEADER_UTF8_BYTES
				|| segmentCount(checked) > RouteAssetProtocol.MAX_DESTINATION_SIGN_PIPE_SEGMENTS) {
			throw new IllegalArgumentException("Destination sign custom header exceeds protocol limits");
		}
		return checked;
	}

	private static int segmentCount(String value) {
		return Math.max(1, value.split("\\|", -1).length);
	}

	private void validateRenderBounds() {
		for (int resolution = 0; resolution <= 3; resolution++) {
			renderResolution(resolution);
		}
	}

	public int renderResolution(int requestedResolution) {
		if (requestedResolution < 0 || requestedResolution > 3) throw new IllegalArgumentException("Invalid destination sign resolution");
		for (int resolution = requestedResolution; resolution >= 0; resolution--) {
			final int width = DestinationSignAtlasLayout.scaledSize(atlasWidth, resolution);
			final int height = DestinationSignAtlasLayout.scaledSize(atlasHeight, resolution);
			if (width <= RouteAssetProtocol.MAX_PNG_AXIS && height <= RouteAssetProtocol.MAX_PNG_AXIS
					&& (long) width * height <= RouteAssetProtocol.MAX_DESTINATION_SIGN_ATLAS_PIXELS) return resolution;
		}
		throw new IllegalArgumentException("Destination sign atlas exceeds the render limits");
	}

	public long getSourceStationId() { return sourceStationId; }
	public String getSourceStationName() { return sourceStationName; }
	public long getDestinationStationId() { return destinationStationId; }
	public SortedSet<Long> getDestinationStationIds() { return destinationStationIds; }
	public String getCustomHeader() { return customHeader; }
	public String getDestinationStationName() { return destinationStationName; }
	public DestinationSignStyle getStyle() { return style; }
	public int getWidthBlocks() { return widthBlocks; }
	public int getHeightBlocks() { return heightBlocks; }
	public int getRoutesPerBlockHeight() { return routesPerBlockHeight; }
	public boolean isShowEta() { return showEta; }
	public DestinationSignDirectServiceModel.Model getModel() { return model; }
	public DestinationSignAtlasLayout.Layout getLayout() { return layout; }
	public List<RouteStripRecord> getRouteStrips() { return routeStrips; }
	public List<Sprite> getSprites() { return sprites; }
	public int getAtlasWidth() { return atlasWidth; }
	public int getAtlasHeight() { return atlasHeight; }

	public enum SpriteKind { HEADER, ROW, LEAVING, NO_DIRECT_SERVICE, NO_SERVICE, UNAVAILABLE }

	public static final class Sprite {
		private final SpriteKind kind;
		private final DestinationSignDirectServiceModel.Option option;
		private final RouteStripRecord routeStrip;
		private final int segmentIndex;
		private final int y;
		private final int height;

		private Sprite(SpriteKind kind, DestinationSignDirectServiceModel.Option option, RouteStripRecord routeStrip, int segmentIndex, int y, int height) {
			this.kind = kind;
			this.option = option;
			this.routeStrip = routeStrip;
			this.segmentIndex = segmentIndex;
			this.y = y;
			this.height = height;
		}

		public SpriteKind getKind() { return kind; }
		public DestinationSignDirectServiceModel.Option getOption() { return option; }
		public RouteStripRecord getRouteStrip() { return routeStrip; }
		public int getSegmentIndex() { return segmentIndex; }
		public int getY() { return y; }
		public int getHeight() { return height; }
	}

	public static final class RouteStripRecord {
		private final DestinationSignDirectServiceModel.OptionKey optionKey;
		private final String routeName;
		private final int routeColor;
		private final String platformName;
		private final DestinationSignRouteStripLayout.RowMetrics rowMetrics;
		private final DestinationSignRouteStripLayout.RouteStrip routeStrip;

		private RouteStripRecord(DestinationSignDirectServiceModel.OptionKey optionKey, String routeName, int routeColor, String platformName,
				DestinationSignRouteStripLayout.RowMetrics rowMetrics, DestinationSignRouteStripLayout.RouteStrip routeStrip) {
			this.optionKey = Objects.requireNonNull(optionKey);
			this.routeName = routeName;
			this.routeColor = routeColor;
			this.platformName = platformName;
			this.rowMetrics = Objects.requireNonNull(rowMetrics);
			this.routeStrip = Objects.requireNonNull(routeStrip);
		}

		public DestinationSignDirectServiceModel.OptionKey getOptionKey() { return optionKey; }
		public String getRouteName() { return routeName; }
		public int getRouteColor() { return routeColor; }
		public String getPlatformName() { return platformName; }
		public DestinationSignRouteStripLayout.RowMetrics getRowMetrics() { return rowMetrics; }
		public DestinationSignRouteStripLayout.RouteStrip getRouteStrip() { return routeStrip; }
	}
}
