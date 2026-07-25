package org.mtr.mod.client;

public final class RouteMapStationNameLayout {

	private RouteMapStationNameLayout() {
	}

	public static Layout getHorizontal(int imageWidth, int imageHeight, int stationX, int textY, int textWidth, int textHeight, int iconSize, int gap, boolean textBelow) {
		final int groupWidth = iconSize + gap + textWidth;
		final int groupLeft = clamp(stationX - groupWidth / 2, 0, imageWidth - groupWidth);
		final int textX = groupLeft + iconSize + gap + textWidth / 2;
		final int textTop = textBelow ? textY : textY - textHeight;
		final int iconY = clamp(textTop + (textHeight - iconSize) / 2, 0, imageHeight - iconSize);
		return new Layout(textX, textY, groupLeft, iconY);
	}

	public static Layout getVertical(int imageWidth, int imageHeight, int stationX, int textY, int textWidth, int textHeight, int iconSize, int gap) {
		final int boundedTextY = clamp(textY, 0, imageHeight - textWidth - gap - iconSize);
		final int iconX = clamp(stationX - iconSize / 2, 0, imageWidth - iconSize);
		return new Layout(stationX, boundedTextY, iconX, boundedTextY + textWidth + gap);
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(value, Math.max(minimum, maximum)));
	}

	public static final class Layout {

		private final int textX;
		private final int textY;
		private final int iconX;
		private final int iconY;

		private Layout(int textX, int textY, int iconX, int iconY) {
			this.textX = textX;
			this.textY = textY;
			this.iconX = iconX;
			this.iconY = iconY;
		}

		public int getTextX() {
			return textX;
		}

		public int getTextY() {
			return textY;
		}

		public int getIconX() {
			return iconX;
		}

		public int getIconY() {
			return iconY;
		}
	}
}
