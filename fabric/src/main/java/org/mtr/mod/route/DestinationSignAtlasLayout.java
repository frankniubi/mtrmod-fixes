package org.mtr.mod.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class DestinationSignAtlasLayout {

	public static final int LOGICAL_PIXELS_PER_BLOCK = 120;
	public static final int MIN_WIDTH_BLOCKS = 2;
	public static final int MAX_WIDTH_BLOCKS = 16;
	public static final int MIN_HEIGHT_BLOCKS = 2;
	public static final int MAX_HEIGHT_BLOCKS = 8;
	public static final int HEADER_HEIGHT = 52;
	public static final int CONTENT_INSET = 12;

	private DestinationSignAtlasLayout() {
	}

	public static Layout create(DestinationSignDirectServiceModel.Model model, DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta) {
		final DestinationSignDirectServiceModel.Model checkedModel = Objects.requireNonNull(model, "model");
		final DestinationSignStyle checkedStyle = Objects.requireNonNull(style, "style");
		validateFootprint(checkedStyle, widthBlocks, heightBlocks);
		final int surfaceWidth = Math.multiplyExact(widthBlocks, LOGICAL_PIXELS_PER_BLOCK);
		final int surfaceHeight = Math.multiplyExact(heightBlocks, LOGICAL_PIXELS_PER_BLOCK);
		final int capacity = Math.max(1, (surfaceHeight - HEADER_HEIGHT - CONTENT_INSET) / checkedStyle.getRowHeight());
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
					rows.add(new Row(option, CONTENT_INSET, HEADER_HEIGHT + rowIndex * checkedStyle.getRowHeight(), surfaceWidth - CONTENT_INSET * 2, checkedStyle.getRowHeight()));
					languageCycleCount = Math.max(languageCycleCount, pipeSegmentCount(option.getRoute().getDisplayName()));
					languageCycleCount = Math.max(languageCycleCount, pipeSegmentCount(option.getSource().getPlatformDisplayName()));
					languageCycleCount = Math.max(languageCycleCount, pipeSegmentCount(option.getDestination().getStationDisplayName()));
				}
				pages.add(new Page(pages.size(), rows, languageCycleCount));
			}
		}
		return new Layout(checkedStyle, widthBlocks, heightBlocks, surfaceWidth, surfaceHeight, showEta, capacity, pages);
	}

	public static boolean fitsOnePage(DestinationSignDirectServiceModel.Model model, DestinationSignStyle style, int widthBlocks, int heightBlocks, boolean showEta) {
		try {
			return create(model, style, widthBlocks, heightBlocks, showEta).pages.size() == 1;
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static void validateFootprint(DestinationSignStyle style, int widthBlocks, int heightBlocks) {
		if (widthBlocks < Math.max(MIN_WIDTH_BLOCKS, style.getMinimumWidthBlocks()) || widthBlocks > MAX_WIDTH_BLOCKS
				|| heightBlocks < MIN_HEIGHT_BLOCKS || heightBlocks > MAX_HEIGHT_BLOCKS) {
			throw new IllegalArgumentException("Invalid destination sign footprint");
		}
	}

	public static String languageSegment(String value, int languagePhase) {
		final String[] segments = Objects.requireNonNull(value, "value").split("\\|", -1);
		return segments[Math.floorMod(languagePhase, segments.length)];
	}

	private static int pipeSegmentCount(String value) {
		return Math.max(1, Objects.requireNonNull(value, "value").split("\\|", -1).length);
	}

	public static final class Layout {
		private final DestinationSignStyle style;
		private final int widthBlocks;
		private final int heightBlocks;
		private final int surfaceWidth;
		private final int surfaceHeight;
		private final boolean showEta;
		private final int rowsPerPage;
		private final List<Page> pages;

		private Layout(DestinationSignStyle style, int widthBlocks, int heightBlocks, int surfaceWidth, int surfaceHeight, boolean showEta, int rowsPerPage, List<Page> pages) {
			this.style = style;
			this.widthBlocks = widthBlocks;
			this.heightBlocks = heightBlocks;
			this.surfaceWidth = surfaceWidth;
			this.surfaceHeight = surfaceHeight;
			this.showEta = showEta;
			this.rowsPerPage = rowsPerPage;
			this.pages = Collections.unmodifiableList(new ArrayList<>(pages));
		}

		public DestinationSignStyle getStyle() { return style; }
		public int getWidthBlocks() { return widthBlocks; }
		public int getHeightBlocks() { return heightBlocks; }
		public int getSurfaceWidth() { return surfaceWidth; }
		public int getSurfaceHeight() { return surfaceHeight; }
		public boolean isShowEta() { return showEta; }
		public int getRowsPerPage() { return rowsPerPage; }
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
}
