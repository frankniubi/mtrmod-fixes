package org.mtr.mod.client;

public final class RouteMapStationNameLayout {

	private RouteMapStationNameLayout() {
	}

	public static Layout getHorizontal(int imageWidth, int imageHeight, int stationX, int textY, int textWidth, int textHeight, int iconCount, int iconSize, int gap, boolean textBelow) {
		final int groupWidth = iconCount * (iconSize + gap) + textWidth;
		final int groupLeft = clamp(stationX - groupWidth / 2, 0, imageWidth - groupWidth);
		final int textX = groupLeft + iconCount * (iconSize + gap) + textWidth / 2;
		final int textTop = textBelow ? textY : textY - textHeight;
		final int iconY = clamp(textTop + (textHeight - iconSize) / 2, 0, imageHeight - iconSize);
		return new Layout(textX, textY, groupLeft, iconY, iconCount, iconSize, gap, false);
	}

	public static Layout getVertical(int imageWidth, int imageHeight, int stationX, int textY, int textWidth, int textHeight, int iconCount, int iconSize, int gap) {
		final int boundedTextY = clamp(textY, 0, imageHeight - textWidth - iconCount * (iconSize + gap));
		final int iconX = clamp(stationX - iconSize / 2, 0, imageWidth - iconSize);
		return new Layout(stationX, boundedTextY, iconX, boundedTextY + textWidth + gap, iconCount, iconSize, gap, true);
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(value, Math.max(minimum, maximum)));
	}

	public static final class Layout {

		private final int textX;
		private final int textY;
		private final int iconX;
		private final int iconY;
		private final int iconCount;
		private final int iconSize;
		private final int gap;
		private final boolean vertical;

		private Layout(int textX, int textY, int iconX, int iconY, int iconCount, int iconSize, int gap, boolean vertical) {
			this.textX = textX;
			this.textY = textY;
			this.iconX = iconX;
			this.iconY = iconY;
			this.iconCount = iconCount;
			this.iconSize = iconSize;
			this.gap = gap;
			this.vertical = vertical;
		}

		public int getTextX() {
			return textX;
		}

		public int getTextY() {
			return textY;
		}

		public int getIconX(int index) {
			return vertical ? iconX : iconX + boundedIndex(index) * (iconSize + gap);
		}

		public int getIconY(int index) {
			return vertical ? iconY + (iconCount - 1 - boundedIndex(index)) * (iconSize + gap) : iconY;
		}

		private int boundedIndex(int index) {
			return Math.max(0, Math.min(index, iconCount - 1));
		}
	}
}
