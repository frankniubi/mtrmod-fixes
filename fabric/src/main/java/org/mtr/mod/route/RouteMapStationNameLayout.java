package org.mtr.mod.route;

public final class RouteMapStationNameLayout {
	private RouteMapStationNameLayout() { }
	public static Layout getHorizontal(int imageWidth, int imageHeight, int stationX, int textY, int textWidth, int textHeight, int iconCount, int iconSize, int gap, boolean textBelow) {
		final int groupWidth = iconCount * (iconSize + gap) + textWidth;
		final int groupLeft = clamp(stationX - groupWidth / 2, 0, imageWidth - groupWidth);
		final int textX = groupLeft + iconCount * (iconSize + gap) + textWidth / 2;
		final int textTop = textBelow ? textY : textY - textHeight;
		return new Layout(textX, textY, groupLeft, clamp(textTop + (textHeight - iconSize) / 2, 0, imageHeight - iconSize), iconCount, iconSize, gap, false);
	}
	public static Layout getVertical(int imageWidth, int imageHeight, int stationX, int textY, int textWidth, int textHeight, int iconCount, int iconSize, int gap) {
		final int boundedTextY = clamp(textY, 0, imageHeight - textWidth - iconCount * (iconSize + gap));
		return new Layout(stationX, boundedTextY, clamp(stationX - iconSize / 2, 0, imageWidth - iconSize), boundedTextY + textWidth + gap, iconCount, iconSize, gap, true);
	}
	private static int clamp(int value, int minimum, int maximum) { return Math.max(minimum, Math.min(value, Math.max(minimum, maximum))); }
	public static final class Layout {
		private final int textX, textY, iconX, iconY, iconCount, iconSize, gap; private final boolean vertical;
		private Layout(int textX, int textY, int iconX, int iconY, int iconCount, int iconSize, int gap, boolean vertical) { this.textX = textX; this.textY = textY; this.iconX = iconX; this.iconY = iconY; this.iconCount = iconCount; this.iconSize = iconSize; this.gap = gap; this.vertical = vertical; }
		public int getTextX() { return textX; } public int getTextY() { return textY; }
		public int getIconX(int index) { return vertical ? iconX : iconX + bounded(index) * (iconSize + gap); }
		public int getIconY(int index) { return vertical ? iconY + (iconCount - 1 - bounded(index)) * (iconSize + gap) : iconY; }
		private int bounded(int index) { return Math.max(0, Math.min(index, iconCount - 1)); }
	}
}
