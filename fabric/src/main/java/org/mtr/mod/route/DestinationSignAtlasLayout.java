package org.mtr.mod.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class DestinationSignAtlasLayout {

	public static final int LOGICAL_PIXELS_PER_BLOCK = 120;
	public static final int MIN_WIDTH_BLOCKS = 1;
	public static final int MAX_WIDTH_BLOCKS = 16;
	public static final int MIN_HEIGHT_BLOCKS = 1;
	public static final int MAX_HEIGHT_BLOCKS = 8;
	public static final int MIN_AREA_BLOCKS = 2;
	public static final int HEADER_HEIGHT = 52;
	public static final int CONTENT_INSET = 12;

	private DestinationSignAtlasLayout() {
	}

	public static Layout create(DestinationSignDirectServiceModel.Model model, DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta) {
		return create(model, style, widthBlocks, heightBlocks, showEta, RouteAssetProtocol.DEFAULT_DESTINATION_SIGN_ROUTES_PER_BLOCK_HEIGHT);
	}

	public static Layout create(DestinationSignDirectServiceModel.Model model, DestinationSignStyle style, int widthBlocks, int heightBlocks,
			boolean showEta, int routesPerBlockHeight) {
		final DestinationSignDirectServiceModel.Model checkedModel = Objects.requireNonNull(model, "model");
		final DestinationSignStyle checkedStyle = Objects.requireNonNull(style, "style");
		validateFootprint(checkedStyle, widthBlocks, heightBlocks);
		if (routesPerBlockHeight < RouteAssetProtocol.MIN_DESTINATION_SIGN_ROUTES_PER_BLOCK_HEIGHT
				|| routesPerBlockHeight > RouteAssetProtocol.MAX_DESTINATION_SIGN_ROUTES_PER_BLOCK_HEIGHT) {
			throw new IllegalArgumentException("Invalid destination sign density");
		}
		final int surfaceWidth = Math.multiplyExact(widthBlocks, LOGICAL_PIXELS_PER_BLOCK);
		final int surfaceHeight = Math.multiplyExact(heightBlocks, LOGICAL_PIXELS_PER_BLOCK);
		final int capacity = Math.multiplyExact(heightBlocks, routesPerBlockHeight);
		final int rowHeight = surfaceHeight / (capacity + 1);
		final int headerHeight = surfaceHeight - capacity * rowHeight;
		final List<DestinationSignDirectServiceModel.Option> ordered = new ArrayList<>(checkedModel.getOptions());
		if (checkedStyle == DestinationSignStyle.PLATFORM_GROUPS) {
			ordered.sort(Comparator.comparing((DestinationSignDirectServiceModel.Option option) -> option.getSource().getPlatformDisplayName())
					.thenComparingInt(option -> option.getRoute().getRouteOrder())
					.thenComparing(option -> option.getKey().toString()));
		}
		final List<Page> pages = new ArrayList<>();
		if (ordered.isEmpty()) {
			pages.add(new Page(0, Collections.emptyList(), 2));
		} else {
			for (int start = 0; start < ordered.size(); start += capacity) {
				final int end = Math.min(ordered.size(), start + capacity);
				final List<Row> rows = new ArrayList<>();
				int languageCycleCount = 1;
				for (int index = start; index < end; index++) {
					final DestinationSignDirectServiceModel.Option option = ordered.get(index);
					final int rowIndex = index - start;
					rows.add(new Row(option, 0, headerHeight + rowIndex * rowHeight, surfaceWidth, rowHeight));
					languageCycleCount = Math.max(languageCycleCount, pipeSegmentCount(option.getRoute().getDisplayName()));
					languageCycleCount = Math.max(languageCycleCount, pipeSegmentCount(option.getSource().getPlatformDisplayName()));
				}
				pages.add(new Page(pages.size(), rows, languageCycleCount));
			}
		}
		return new Layout(checkedStyle, widthBlocks, heightBlocks, routesPerBlockHeight, surfaceWidth, surfaceHeight,
				showEta, capacity, headerHeight, rowHeight, pages);
	}

	public static boolean fitsOnePage(DestinationSignDirectServiceModel.Model model, DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta) {
		return fitsOnePage(model, style, widthBlocks, heightBlocks, showEta, RouteAssetProtocol.DEFAULT_DESTINATION_SIGN_ROUTES_PER_BLOCK_HEIGHT);
	}

	public static boolean fitsOnePage(DestinationSignDirectServiceModel.Model model, DestinationSignStyle style, int widthBlocks, int heightBlocks,
			boolean showEta, int routesPerBlockHeight) {
		try {
			return create(model, style, widthBlocks, heightBlocks, showEta, routesPerBlockHeight).pages.size() == 1;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static void validateFootprint(DestinationSignStyle style, int widthBlocks, int heightBlocks) {
		if (!isValidFootprint(style, widthBlocks, heightBlocks)) {
			throw new IllegalArgumentException("Invalid destination sign footprint");
		}
	}

	public static boolean isValidFootprint(DestinationSignStyle style, int widthBlocks, int heightBlocks) {
		return style != null
				&& widthBlocks >= Math.max(MIN_WIDTH_BLOCKS, style.getMinimumWidthBlocks()) && widthBlocks <= MAX_WIDTH_BLOCKS
				&& heightBlocks >= MIN_HEIGHT_BLOCKS && heightBlocks <= MAX_HEIGHT_BLOCKS
				&& (long) widthBlocks * heightBlocks >= MIN_AREA_BLOCKS;
	}

	public static String languageSegment(String value, int languagePhase) {
		final String[] segments = Objects.requireNonNull(value, "value").split("\\|", -1);
		return segments[Math.floorMod(languagePhase, segments.length)];
	}

	public static int scaledSize(int logical, int resolution) {
		validateScale(logical, resolution);
		return resolution == 0 ? Math.max(1, (logical + 1) / 2) : Math.multiplyExact(logical, 1 << resolution - 1);
	}

	public static int scaledEdge(int logical, int resolution) {
		validateScale(logical, resolution);
		return resolution == 0 ? (logical + 1) / 2 : Math.multiplyExact(logical, 1 << resolution - 1);
	}

	public static int scaledSpan(int start, int length, int resolution) {
		if (start < 0 || length <= 0) throw new IllegalArgumentException("Invalid destination sign scaled span");
		return Math.max(1, scaledEdge(Math.addExact(start, length), resolution) - scaledEdge(start, resolution));
	}

	private static void validateScale(int logical, int resolution) {
		if (logical < 0 || resolution < 0 || resolution > 3) throw new IllegalArgumentException("Invalid destination sign scale");
	}

	/** Shared fixed columns for server row rasterization and client live-text overlays. */
	public static RowGeometry rowGeometry(DestinationSignStyle style, int surfaceWidth, int rowHeight, boolean showEta) {
		final DestinationSignStyle checkedStyle = Objects.requireNonNull(style, "style");
		if (surfaceWidth <= 0 || rowHeight <= 0) throw new IllegalArgumentException("Invalid destination sign row geometry");
		if (surfaceWidth < LOGICAL_PIXELS_PER_BLOCK * 2) return compactRowGeometry(checkedStyle, surfaceWidth, rowHeight, showEta);
		final int inset = Math.max(4, rowHeight / 8);
		final int etaWidth = showEta ? Math.max(44, surfaceWidth / 5) : 0;
		final int etaX = surfaceWidth - etaWidth;
		final int contentRight = (showEta ? etaX : surfaceWidth) - inset;
		final int routeX;
		final int routeWidth;
		final int platformX;
		final int platformWidth;
		switch (checkedStyle) {
			case ARRIVAL_ORDER:
				routeX = inset;
				routeWidth = Math.max(54, surfaceWidth / 5);
				platformWidth = Math.max(44, surfaceWidth / 6);
				platformX = contentRight - platformWidth;
				break;
			case PLATFORM_GROUPS:
				platformX = inset * 2;
				platformWidth = Math.max(64, surfaceWidth / 4) - inset;
				routeX = platformX + platformWidth + inset;
				routeWidth = Math.max(54, surfaceWidth / 5);
				break;
			case DESTINATION_FLAG:
			default:
				routeX = inset * 2;
				routeWidth = Math.max(54, surfaceWidth / 5);
				platformX = routeX + routeWidth + inset;
				platformWidth = Math.max(44, surfaceWidth / 6);
				break;
		}
		final int dynamicX;
		switch (checkedStyle) {
			case ARRIVAL_ORDER: dynamicX = routeX + routeWidth + inset; break;
			case PLATFORM_GROUPS: dynamicX = routeX + routeWidth + inset; break;
			case DESTINATION_FLAG:
			default: dynamicX = platformX + platformWidth + inset; break;
		}
		final int dynamicRight = checkedStyle == DestinationSignStyle.ARRIVAL_ORDER ? platformX - inset : contentRight;
		return new RowGeometry(inset, routeX, Math.max(1, routeWidth), platformX, Math.max(1, platformWidth),
				dynamicX, Math.max(1, dynamicRight - dynamicX), showEta ? etaX + inset : surfaceWidth,
				showEta ? Math.max(1, etaWidth - inset * 2) : 0, etaX);
	}

	private static RowGeometry compactRowGeometry(DestinationSignStyle style, int surfaceWidth, int rowHeight, boolean showEta) {
		final int inset = Math.max(4, rowHeight / 8);
		final int etaBandWidth = showEta ? Math.max(24, surfaceWidth / 4) : 0;
		final int etaDividerX = showEta ? surfaceWidth - etaBandWidth : surfaceWidth;
		final int contentRight = etaDividerX - inset;
		final int contentLeft = style == DestinationSignStyle.ARRIVAL_ORDER ? inset : inset * 2;
		final int columnWidth = Math.max(3, contentRight - contentLeft - inset * 2);
		final int routeWidth = Math.max(1, columnWidth * 32 / 100);
		final int platformWidth = Math.max(1, columnWidth * 28 / 100);
		final int destinationWidth = Math.max(1, columnWidth - routeWidth - platformWidth);
		final int routeX;
		final int platformX;
		final int destinationX;
		switch (style) {
			case ARRIVAL_ORDER:
				routeX = contentLeft;
				destinationX = routeX + routeWidth + inset;
				platformX = destinationX + destinationWidth + inset;
				break;
			case PLATFORM_GROUPS:
				platformX = contentLeft;
				routeX = platformX + platformWidth + inset;
				destinationX = routeX + routeWidth + inset;
				break;
			case DESTINATION_FLAG:
			default:
				routeX = contentLeft;
				platformX = routeX + routeWidth + inset;
				destinationX = platformX + platformWidth + inset;
				break;
		}
		return new RowGeometry(inset, routeX, routeWidth, platformX, platformWidth, destinationX, destinationWidth,
				showEta ? etaDividerX + inset : surfaceWidth, showEta ? Math.max(1, etaBandWidth - inset * 2) : 0, etaDividerX);
	}

	private static int pipeSegmentCount(String value) {
		return Math.max(1, Objects.requireNonNull(value, "value").split("\\|", -1).length);
	}

	public static final class Layout {
		private final DestinationSignStyle style;
		private final int widthBlocks;
		private final int heightBlocks;
		private final int routesPerBlockHeight;
		private final int surfaceWidth;
		private final int surfaceHeight;
		private final boolean showEta;
		private final int rowsPerPage;
		private final int headerHeight;
		private final int rowHeight;
		private final List<Page> pages;

		private Layout(DestinationSignStyle style, int widthBlocks, int heightBlocks, int routesPerBlockHeight, int surfaceWidth,
				int surfaceHeight, boolean showEta, int rowsPerPage, int headerHeight, int rowHeight, List<Page> pages) {
			this.style = style;
			this.widthBlocks = widthBlocks;
			this.heightBlocks = heightBlocks;
			this.routesPerBlockHeight = routesPerBlockHeight;
			this.surfaceWidth = surfaceWidth;
			this.surfaceHeight = surfaceHeight;
			this.showEta = showEta;
			this.rowsPerPage = rowsPerPage;
			this.headerHeight = headerHeight;
			this.rowHeight = rowHeight;
			this.pages = Collections.unmodifiableList(new ArrayList<>(pages));
		}

		public DestinationSignStyle getStyle() { return style; }
		public int getWidthBlocks() { return widthBlocks; }
		public int getHeightBlocks() { return heightBlocks; }
		public int getRoutesPerBlockHeight() { return routesPerBlockHeight; }
		public int getSurfaceWidth() { return surfaceWidth; }
		public int getSurfaceHeight() { return surfaceHeight; }
		public boolean isShowEta() { return showEta; }
		public int getRowsPerPage() { return rowsPerPage; }
		public int getHeaderHeight() { return headerHeight; }
		public int getRowHeight() { return rowHeight; }
		public List<Page> getPages() { return pages; }
	}

	public static final class Page {
		private final int index;
		private final List<Row> rows;
		private final int languageCycleCount;

		private Page(int index, List<Row> rows, int languageCycleCount) {
			this.index = index;
			this.rows = Collections.unmodifiableList(new ArrayList<>(rows));
			this.languageCycleCount = languageCycleCount;
		}

		public int getIndex() { return index; }
		public List<Row> getRows() { return rows; }
		public int getLanguageCycleCount() { return languageCycleCount; }
	}

	public static final class Row {
		private final DestinationSignDirectServiceModel.Option option;
		private final int x;
		private final int y;
		private final int width;
		private final int height;

		private Row(DestinationSignDirectServiceModel.Option option, int x, int y, int width, int height) {
			this.option = option;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
		}

		public DestinationSignDirectServiceModel.Option getOption() { return option; }
		public int getX() { return x; }
		public int getY() { return y; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
	}

	public static final class RowGeometry {
		private final int inset;
		private final int routeX;
		private final int routeWidth;
		private final int platformX;
		private final int platformWidth;
		private final int destinationX;
		private final int destinationWidth;
		private final int etaX;
		private final int etaWidth;
		private final int etaDividerX;

		private RowGeometry(int inset, int routeX, int routeWidth, int platformX, int platformWidth,
				int destinationX, int destinationWidth, int etaX, int etaWidth, int etaDividerX) {
			this.inset = inset;
			this.routeX = routeX;
			this.routeWidth = routeWidth;
			this.platformX = platformX;
			this.platformWidth = platformWidth;
			this.destinationX = destinationX;
			this.destinationWidth = destinationWidth;
			this.etaX = etaX;
			this.etaWidth = etaWidth;
			this.etaDividerX = etaDividerX;
		}

		public int getInset() { return inset; }
		public int getRouteX() { return routeX; }
		public int getRouteWidth() { return routeWidth; }
		public int getPlatformX() { return platformX; }
		public int getPlatformWidth() { return platformWidth; }
		public int getDestinationX() { return destinationX; }
		public int getDestinationWidth() { return destinationWidth; }
		public int getEtaX() { return etaX; }
		public int getEtaWidth() { return etaWidth; }
		public int getEtaDividerX() { return etaDividerX; }
	}
}
