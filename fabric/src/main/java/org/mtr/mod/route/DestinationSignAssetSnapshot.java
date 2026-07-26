package org.mtr.mod.route;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Static-only atlas input. Live train state is intentionally not representable here. */
public final class DestinationSignAssetSnapshot {

	public static final String LEAVING_TEXT = "\u5c06\u79bb|Leaving";
	public static final String NO_DIRECT_SERVICE_TEXT = "\u5f53\u524d\u65e0\u76f4\u8fbe\u670d\u52a1|No direct service";
	public static final String NO_SERVICE_TEXT = "\u6682\u65e0\u73ed\u6b21|No service";

	private final long sourceStationId;
	private final String sourceStationName;
	private final long destinationStationId;
	private final String destinationStationName;
	private final DestinationSignStyle style;
	private final int widthBlocks;
	private final int heightBlocks;
	private final boolean showEta;
	private final DestinationSignDirectServiceModel.Model model;
	private final DestinationSignAtlasLayout.Layout layout;
	private final List<Sprite> sprites;
	private final int atlasWidth;
	private final int atlasHeight;

	private DestinationSignAssetSnapshot(long sourceStationId, String sourceStationName, long destinationStationId, String destinationStationName,
			DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta, DestinationSignDirectServiceModel.Model model) {
		this.sourceStationId = sourceStationId;
		this.sourceStationName = validateField(sourceStationName);
		this.destinationStationId = destinationStationId;
		this.destinationStationName = validateField(destinationStationName);
		this.style = Objects.requireNonNull(style, "style");
		this.widthBlocks = widthBlocks;
		this.heightBlocks = heightBlocks;
		this.showEta = showEta;
		this.model = Objects.requireNonNull(model, "model");
		layout = DestinationSignAtlasLayout.create(model, style, widthBlocks, heightBlocks, showEta);
		atlasWidth = layout.getSurfaceWidth();
		final List<Sprite> mutableSprites = new ArrayList<>();
		int y = 0;
		final int headerCycles = Math.max(2, segmentCount(this.destinationStationName));
		for (int phase = 0; phase < headerCycles; phase++) {
			mutableSprites.add(new Sprite(SpriteKind.HEADER, null, phase, y, DestinationSignAtlasLayout.HEADER_HEIGHT));
			y = Math.addExact(y, DestinationSignAtlasLayout.HEADER_HEIGHT);
		}
		for (final DestinationSignDirectServiceModel.Option option : model.getOptions()) {
			validateField(option.getRoute().getDisplayName());
			validateField(option.getSource().getPlatformDisplayName());
			validateField(option.getDestination().getStationDisplayName());
			final int cycles = Math.max(segmentCount(option.getRoute().getDisplayName()), segmentCount(option.getSource().getPlatformDisplayName()));
			for (int phase = 0; phase < cycles; phase++) {
				mutableSprites.add(new Sprite(SpriteKind.ROW, option, phase, y, style.getRowHeight()));
				y = Math.addExact(y, style.getRowHeight());
			}
		}
		for (final SpriteKind label : List.of(SpriteKind.LEAVING, SpriteKind.NO_DIRECT_SERVICE, SpriteKind.NO_SERVICE)) {
			for (int phase = 0; phase < 2; phase++) {
				mutableSprites.add(new Sprite(label, null, phase, y, style.getRowHeight()));
				y = Math.addExact(y, style.getRowHeight());
			}
		}
		if (mutableSprites.size() > RouteAssetProtocol.MAX_DESTINATION_SIGN_ATLAS_SPRITES) throw new IllegalArgumentException("Destination sign atlas has too many sprites");
		sprites = Collections.unmodifiableList(mutableSprites);
		atlasHeight = y;
		validateRenderBounds();
	}

	public static DestinationSignAssetSnapshot create(DestinationSignTopology topology, long sourceStationId, long destinationStationId,
			DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta) {
		final DestinationSignTopology checkedTopology = Objects.requireNonNull(topology, "topology");
		final DestinationSignTopology.StationZone source = checkedTopology.getStation(sourceStationId).orElseThrow(() -> new IllegalArgumentException("Unknown destination sign source station"));
		final DestinationSignTopology.StationZone destination = checkedTopology.getStation(destinationStationId).orElseThrow(() -> new IllegalArgumentException("Unknown destination sign destination station"));
		return new DestinationSignAssetSnapshot(sourceStationId, source.getDisplayName(), destinationStationId, destination.getDisplayName(), style, widthBlocks, heightBlocks, showEta,
				DestinationSignDirectServiceModel.project(checkedTopology, sourceStationId, destinationStationId));
	}

	private static String validateField(String value) {
		final String checked = Objects.requireNonNull(value, "value");
		if (checked.getBytes(StandardCharsets.UTF_8).length > RouteAssetProtocol.MAX_DESTINATION_SIGN_FIELD_UTF8_BYTES || segmentCount(checked) > RouteAssetProtocol.MAX_DESTINATION_SIGN_PIPE_SEGMENTS) {
			throw new IllegalArgumentException("Destination sign stable field exceeds protocol limits");
		}
		return checked;
	}

	private static int segmentCount(String value) {
		return Math.max(1, value.split("\\|", -1).length);
	}

	private void validateRenderBounds() {
		for (int resolution = 0; resolution <= 3; resolution++) {
			final int width = DestinationSignAtlasLayout.scaledSize(atlasWidth, resolution);
			final int height = DestinationSignAtlasLayout.scaledSize(atlasHeight, resolution);
			if (width > RouteAssetProtocol.MAX_PNG_AXIS || height > RouteAssetProtocol.MAX_PNG_AXIS
					|| (long) width * height > RouteAssetProtocol.MAX_DESTINATION_SIGN_ATLAS_PIXELS) {
				throw new IllegalArgumentException("Destination sign atlas exceeds the render limits");
			}
		}
	}

	public long getSourceStationId() { return sourceStationId; }
	public String getSourceStationName() { return sourceStationName; }
	public long getDestinationStationId() { return destinationStationId; }
	public String getDestinationStationName() { return destinationStationName; }
	public DestinationSignStyle getStyle() { return style; }
	public int getWidthBlocks() { return widthBlocks; }
	public int getHeightBlocks() { return heightBlocks; }
	public boolean isShowEta() { return showEta; }
	public DestinationSignDirectServiceModel.Model getModel() { return model; }
	public DestinationSignAtlasLayout.Layout getLayout() { return layout; }
	public List<Sprite> getSprites() { return sprites; }
	public int getAtlasWidth() { return atlasWidth; }
	public int getAtlasHeight() { return atlasHeight; }

	public enum SpriteKind { HEADER, ROW, LEAVING, NO_DIRECT_SERVICE, NO_SERVICE }

	public static final class Sprite {
		private final SpriteKind kind;
		private final DestinationSignDirectServiceModel.Option option;
		private final int segmentIndex;
		private final int y;
		private final int height;

		private Sprite(SpriteKind kind, DestinationSignDirectServiceModel.Option option, int segmentIndex, int y, int height) {
			this.kind = kind;
			this.option = option;
			this.segmentIndex = segmentIndex;
			this.y = y;
			this.height = height;
		}

		public SpriteKind getKind() { return kind; }
		public DestinationSignDirectServiceModel.Option getOption() { return option; }
		public int getSegmentIndex() { return segmentIndex; }
		public int getY() { return y; }
		public int getHeight() { return height; }
	}
}
