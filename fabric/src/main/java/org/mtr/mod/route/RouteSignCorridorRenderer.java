package org.mtr.mod.route;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Draws an accepted corridor layout in physical portrait coordinates into the native landscape texture. */
public final class RouteSignCorridorRenderer {

	private static final int LOGICAL_WIDTH = RouteSignCorridorLayout.LOGICAL_WIDTH;
	private static final int LOGICAL_HEIGHT = RouteSignCorridorLayout.LOGICAL_HEIGHT;
	private static final int BASE_PHYSICAL_WIDTH = 160;
	private static final float NATIVE_ASPECT_RATIO = 37F / 22;

	private static final int ABGR_WHITE = 0xFFFFFFFF;
	private static final int ABGR_PRIMARY = 0xFF1D1A17;
	private static final int ABGR_SECONDARY = 0xFF7A7268;
	private static final int ABGR_SEPARATOR = 0xFFDEDAD6;
	private static final int ABGR_ICON = 0xFF9F6721;

	private static final int CURRENT_RING_OUTER = 18;
	private static final int CURRENT_RING_INNER = 10;
	private static final int NEXT_RING_OUTER = 16;
	private static final int NEXT_RING_INNER = 10;
	private static final int BADGE_HEIGHT = 20;
	private static final int BADGE_TEXT_PADDING = 4;
	private static final int INLINE_GAP = 5;
	private static final int HEADING_ICON_SIZE = 12;
	private static final int TOKEN_ICON_SIZE = 7;
	private static final int ICON_GAP = 2;

	private static final String RAILWAY_INTERCHANGE_RESOURCE = "textures/block/sign/railway_interchange.png";
	private static final String AIRPORT_INTERCHANGE_RESOURCE = "textures/block/sign/airplane.png";

	private final RouteSignCorridorLayout.Layout layout;
	private final RouteAssetTextRasterizer text;
	private final RouteAssetSourceImages sources;
	private final String language;
	private final int physicalWidth;
	private final int physicalHeight;
	private final RouteAssetImage image;

	private RouteSignCorridorRenderer(RouteSignCorridorLayout.Layout layout, RouteAssetTextRasterizer text,
			RouteAssetSourceImages sources, int resolution, String language) {
		this.layout = Objects.requireNonNull(layout, "layout");
		this.text = Objects.requireNonNull(text, "text");
		this.sources = Objects.requireNonNull(sources, "sources");
		if (layout.getWidth() != LOGICAL_WIDTH || layout.getHeight() != LOGICAL_HEIGHT) {
			throw new IllegalArgumentException("Unsupported route-sign corridor canvas");
		}
		if (resolution < 0 || resolution > 3) throw new IllegalArgumentException("Invalid route-sign resolution");
		this.language = normalizeLanguage(language);
		physicalWidth = BASE_PHYSICAL_WIDTH << resolution;
		physicalHeight = Math.max(1, Math.round(physicalWidth * NATIVE_ASPECT_RATIO));
		image = new RouteAssetImage(physicalHeight, physicalWidth);
	}

	static RouteAssetImage render(RouteSignCorridorLayout.Layout layout, RouteAssetTextRasterizer text,
			RouteAssetSourceImages sources, int resolution, String language) {
		return new RouteSignCorridorRenderer(layout, text, sources, resolution, language).draw();
	}

	static int scale(int logicalValue, int physicalWidth) {
		if (logicalValue < 0 || physicalWidth <= 0) throw new IllegalArgumentException("Invalid logical scale input");
		return Math.max(1, Math.round(logicalValue * physicalWidth / (float) LOGICAL_WIDTH));
	}

	private RouteAssetImage draw() {
		drawPhysicalRect(0, 0, physicalWidth, physicalHeight, ABGR_WHITE);
		drawCurrentBand(layout.getCurrentBand());
		for (int index = 0; index < layout.getCorridors().size(); index++) {
			final RouteSignCorridorLayout.CorridorBox corridor = layout.getCorridors().get(index);
			if (index > 0) drawLogicalRect(0, corridor.getY() - 1, LOGICAL_WIDTH, RouteSignCorridorLayout.SEPARATOR_HEIGHT, ABGR_SEPARATOR);
			drawCorridor(corridor);
		}
		return image;
	}

	private void drawCurrentBand(RouteSignCorridorLayout.CurrentBand band) {
		final int ringX = band.getX() + band.getXPadding();
		final int ringY = band.getY() + (band.getHeight() - CURRENT_RING_OUTER) / 2;
		drawLogicalRing(ringX, ringY, CURRENT_RING_OUTER, CURRENT_RING_INNER);

		int right = band.getX() + band.getWidth() - band.getXPadding();
		if (!band.getPlatformName().isEmpty()) {
			final int naturalWidth = measureLogicalWidth(band.getPlatformName(), 8, 6, false);
			final int badgeWidth = Math.max(28, naturalWidth + BADGE_TEXT_PADDING * 2);
			final int badgeX = right - badgeWidth;
			final int badgeY = band.getY() + (band.getHeight() - BADGE_HEIGHT) / 2;
			drawLogicalRect(badgeX, badgeY, badgeWidth, BADGE_HEIGHT, ABGR_PRIMARY);
			drawLogicalText(band.getPlatformName(), badgeX + BADGE_TEXT_PADDING, badgeY,
					badgeWidth - BADGE_TEXT_PADDING * 2, BADGE_HEIGHT, 8, 6, ABGR_WHITE, Horizontal.CENTER, true);
			right = badgeX - INLINE_GAP;
		}

		final int nameX = ringX + CURRENT_RING_OUTER + INLINE_GAP;
		drawLogicalText(band.getStationName(), nameX, band.getY(), Math.max(1, right - nameX), band.getHeight(),
				16, 8, ABGR_PRIMARY, Horizontal.LEFT, true);
	}

	private void drawCorridor(RouteSignCorridorLayout.CorridorBox corridor) {
		final int ringX = corridor.getHeadingX();
		final int ringY = corridor.getHeadingY() + (corridor.getHeadingHeight() - NEXT_RING_OUTER) / 2;
		drawLogicalRing(ringX, ringY, NEXT_RING_OUTER, NEXT_RING_INNER);

		final int headingRight = corridor.getHeadingX() + corridor.getHeadingWidth();
		final int nextWidth = Math.max(36, measureLogicalWidth(RouteSignCorridorLayout.NEXT, 8, 6, true));
		final int nextX = headingRight - nextWidth;
		drawLogicalText(RouteSignCorridorLayout.NEXT, nextX, corridor.getHeadingY(), nextWidth,
				corridor.getHeadingHeight(), 8, 6, ABGR_SECONDARY, Horizontal.RIGHT, true);

		final RouteAssetRenderSnapshot.Interchange interchange = corridor.getHeadingStop().getInterchange();
		final int iconCount = (interchange.hasRailway() ? 1 : 0) + (interchange.hasAirport() ? 1 : 0);
		final int iconsWidth = iconCount == 0 ? 0 : iconCount * HEADING_ICON_SIZE + (iconCount - 1) * ICON_GAP;
		final int iconsX = nextX - (iconsWidth == 0 ? 0 : iconsWidth + INLINE_GAP);
		final int iconsY = corridor.getHeadingY() + (corridor.getHeadingHeight() - HEADING_ICON_SIZE) / 2;
		drawInterchangeIcons(interchange, iconsX, iconsY, HEADING_ICON_SIZE, true);

		final int nameX = ringX + NEXT_RING_OUTER + INLINE_GAP;
		final int nameRight = iconsWidth == 0 ? nextX - INLINE_GAP : iconsX - INLINE_GAP;
		drawLogicalText(corridor.getStationName(), nameX, corridor.getHeadingY(), Math.max(1, nameRight - nameX),
				corridor.getHeadingHeight(), 16, 8, ABGR_PRIMARY, Horizontal.LEFT, true);

		for (final RouteSignCorridorLayout.RouteRowBox row : corridor.getRows()) drawRouteRow(row);
	}

	private void drawRouteRow(RouteSignCorridorLayout.RouteRowBox row) {
		final int routeColor = opaqueRgb(row.getRouteColor());
		drawLogicalRect(row.getRuleX(), row.getRuleY(), row.getRuleWidth(), row.getRuleHeight(), routeColor);
		drawLogicalRect(row.getRouteBadgeX(), row.getBadgeY(), row.getRouteBadgeWidth(), row.getBadgeHeight(), routeColor);
		drawLogicalText(displayRouteName(row.getRouteName()), row.getRouteBadgeX() + BADGE_TEXT_PADDING, row.getBadgeY(),
				row.getRouteBadgeWidth() - BADGE_TEXT_PADDING * 2, row.getBadgeHeight(), 9, 7, ABGR_WHITE, Horizontal.CENTER, true);

		if (row.getPlatformBadgeWidth() > 0) {
			drawLogicalRect(row.getPlatformBadgeX(), row.getBadgeY(), row.getPlatformBadgeWidth(), row.getBadgeHeight(), routeColor);
			drawLogicalText(row.getNextPlatformName(), row.getPlatformBadgeX() + BADGE_TEXT_PADDING, row.getBadgeY(),
					row.getPlatformBadgeWidth() - BADGE_TEXT_PADDING * 2, row.getBadgeHeight(), 8, 6, ABGR_WHITE, Horizontal.CENTER, true);
		}

		final List<RouteSignCorridorLayout.DisplayToken> tokens = row.getTokens();
		for (int index = 0; index < tokens.size(); index++) {
			final RouteSignCorridorLayout.DisplayToken token = tokens.get(index);
			final int color = token.getKind() == RouteSignCorridorLayout.DisplayToken.Kind.COLLAPSED ? ABGR_SECONDARY : ABGR_PRIMARY;
			drawLogicalText(token.getDisplayText(), token.getX(), token.getY(), token.getWidth(), token.getHeight(),
					9, 6, color, Horizontal.LEFT, false);
			drawTokenIcons(row, tokens, index);
		}
	}

	private void drawTokenIcons(RouteSignCorridorLayout.RouteRowBox row,
			List<RouteSignCorridorLayout.DisplayToken> tokens, int tokenIndex) {
		final RouteSignCorridorLayout.DisplayToken token = tokens.get(tokenIndex);
		if (token.getKind() == RouteSignCorridorLayout.DisplayToken.Kind.COLLAPSED) return;
		final RouteAssetRenderSnapshot.Interchange interchange = token.getInterchange();
		final int iconCount = (interchange.hasRailway() ? 1 : 0) + (interchange.hasAirport() ? 1 : 0);
		if (iconCount == 0) return;
		final int requiredWidth = iconCount * TOKEN_ICON_SIZE + (iconCount - 1) * ICON_GAP;
		final int iconX = token.getX() + token.getWidth() + ICON_GAP;
		int availableEnd = row.getTextX() + row.getTextWidth();
		if (tokenIndex + 1 < tokens.size() && tokens.get(tokenIndex + 1).getLine() == token.getLine()) {
			availableEnd = tokens.get(tokenIndex + 1).getX() - ICON_GAP;
		}
		if (iconX + requiredWidth > availableEnd) return;
		final int iconY = token.getY() + (token.getHeight() - TOKEN_ICON_SIZE) / 2;
		drawInterchangeIcons(interchange, iconX, iconY, TOKEN_ICON_SIZE, true);
	}

	private void drawInterchangeIcons(RouteAssetRenderSnapshot.Interchange interchange, int x, int y, int size, boolean tint) {
		int iconX = x;
		if (interchange.hasRailway()) {
			drawLogicalResource(RAILWAY_INTERCHANGE_RESOURCE, iconX, y, size, size, tint ? ABGR_ICON : ABGR_PRIMARY);
			iconX += size + ICON_GAP;
		}
		if (interchange.hasAirport()) drawLogicalResource(AIRPORT_INTERCHANGE_RESOURCE, iconX, y, size, size, tint ? ABGR_ICON : ABGR_PRIMARY);
	}

	private int measureLogicalWidth(String value, int cjkSize, int latinSize, boolean stacked) {
		final RouteAssetTextRasterizer.RasterizedText rendered = text.rasterize(value, Integer.MAX_VALUE, Integer.MAX_VALUE,
				cjkSize, latinSize, 0, stacked ? RouteAssetTextRasterizer.Alignment.LEFT : null, language);
		return rendered.getWidth();
	}

	private void drawLogicalText(String value, int logicalX, int logicalY, int logicalWidth, int logicalHeight,
			int logicalCjkSize, int logicalLatinSize, int color, Horizontal horizontal, boolean stacked) {
		if (value.isEmpty() || logicalWidth <= 0 || logicalHeight <= 0) return;
		final int x1 = position(logicalX);
		final int y1 = position(logicalY);
		final int x2 = Math.max(x1 + 1, position(logicalX + logicalWidth));
		final int y2 = Math.max(y1 + 1, position(logicalY + logicalHeight));
		final int width = x2 - x1;
		final int height = y2 - y1;
		final RouteAssetTextRasterizer.RasterizedText rendered = text.rasterize(value,
				stacked ? width : Integer.MAX_VALUE, stacked ? height : Integer.MAX_VALUE,
				scale(logicalCjkSize, physicalWidth), scale(logicalLatinSize, physicalWidth), 0,
				stacked ? toTextAlignment(horizontal) : null, language);
		final int drawX = horizontal == Horizontal.CENTER ? x1 + (width - rendered.getWidth()) / 2 :
				horizontal == Horizontal.RIGHT ? x2 - rendered.getWidth() : x1;
		final int drawY = y1 + (height - rendered.getHeight()) / 2;
		drawAlphaMask(rendered, drawX, drawY, x1, y1, x2, y2, color);
	}

	private void drawAlphaMask(RouteAssetTextRasterizer.RasterizedText rendered, int x, int y,
			int clipX1, int clipY1, int clipX2, int clipY2, int color) {
		final byte[] pixels = rendered.pixels();
		for (int sourceY = 0; sourceY < rendered.getHeight(); sourceY++) {
			final int physicalY = y + sourceY;
			if (physicalY < clipY1 || physicalY >= clipY2) continue;
			for (int sourceX = 0; sourceX < rendered.getWidth(); sourceX++) {
				final int physicalX = x + sourceX;
				if (physicalX < clipX1 || physicalX >= clipX2) continue;
				blendPhysicalPixel(physicalX, physicalY, pixels[sourceY * rendered.getWidth() + sourceX] & 0xFF, color);
			}
		}
	}

	private void drawLogicalResource(String path, int logicalX, int logicalY, int logicalWidth, int logicalHeight, int color) {
		final RouteAssetImage source = source(path);
		final int x1 = position(logicalX);
		final int y1 = position(logicalY);
		final int x2 = Math.max(x1 + 1, position(logicalX + logicalWidth));
		final int y2 = Math.max(y1 + 1, position(logicalY + logicalHeight));
		final int width = x2 - x1;
		final int height = y2 - y1;
		for (int drawY = 0; drawY < height; drawY++) {
			final int sourceY = Math.min(source.getHeight() - 1, drawY * source.getHeight() / height);
			for (int drawX = 0; drawX < width; drawX++) {
				final int sourceX = Math.min(source.getWidth() - 1, drawX * source.getWidth() / width);
				blendPhysicalPixel(x1 + drawX, y1 + drawY, source.getPixel(sourceX, sourceY) >>> 24, color);
			}
		}
	}

	private void drawLogicalRing(int logicalX, int logicalY, int outerSize, int innerSize) {
		drawLogicalEllipse(logicalX, logicalY, outerSize, outerSize, ABGR_PRIMARY);
		final int inset = (outerSize - innerSize) / 2;
		drawLogicalEllipse(logicalX + inset, logicalY + inset, innerSize, innerSize, ABGR_WHITE);
	}

	private void drawLogicalEllipse(int logicalX, int logicalY, int logicalWidth, int logicalHeight, int color) {
		final int x1 = position(logicalX);
		final int y1 = position(logicalY);
		final int x2 = Math.max(x1 + 1, position(logicalX + logicalWidth));
		final int y2 = Math.max(y1 + 1, position(logicalY + logicalHeight));
		final double radiusX = (x2 - x1) / 2D;
		final double radiusY = (y2 - y1) / 2D;
		for (int y = y1; y < y2; y++) {
			final double offsetY = (y + 0.5D - (y1 + radiusY)) / radiusY;
			for (int x = x1; x < x2; x++) {
				final double offsetX = (x + 0.5D - (x1 + radiusX)) / radiusX;
				if (offsetX * offsetX + offsetY * offsetY <= 1) putPhysicalPixel(x, y, color);
			}
		}
	}

	private void drawLogicalRect(int logicalX, int logicalY, int logicalWidth, int logicalHeight, int color) {
		final int x1 = position(logicalX);
		final int y1 = position(logicalY);
		final int x2 = Math.max(x1 + 1, position(logicalX + logicalWidth));
		final int y2 = Math.max(y1 + 1, position(logicalY + logicalHeight));
		drawPhysicalRect(x1, y1, x2 - x1, y2 - y1, color);
	}

	private void drawPhysicalRect(int x, int y, int width, int height, int color) {
		final int startX = Math.max(0, x);
		final int startY = Math.max(0, y);
		final int endX = Math.min(physicalWidth, x + width);
		final int endY = Math.min(physicalHeight, y + height);
		for (int drawY = startY; drawY < endY; drawY++) {
			for (int drawX = startX; drawX < endX; drawX++) putPhysicalPixel(drawX, drawY, color);
		}
	}

	private void blendPhysicalPixel(int physicalX, int physicalY, int alpha, int color) {
		if (alpha <= 0 || physicalX < 0 || physicalY < 0 || physicalX >= physicalWidth || physicalY >= physicalHeight) return;
		if (alpha >= 255) {
			putPhysicalPixel(physicalX, physicalY, color);
			return;
		}
		final int existing = getPhysicalPixel(physicalX, physicalY);
		final int inverse = 255 - alpha;
		final int red = ((existing & 0xFF) * inverse + (color & 0xFF) * alpha + 127) / 255;
		final int green = ((existing >>> 8 & 0xFF) * inverse + (color >>> 8 & 0xFF) * alpha + 127) / 255;
		final int blue = ((existing >>> 16 & 0xFF) * inverse + (color >>> 16 & 0xFF) * alpha + 127) / 255;
		putPhysicalPixel(physicalX, physicalY, 0xFF000000 | blue << 16 | green << 8 | red);
	}

	private int getPhysicalPixel(int physicalX, int physicalY) {
		return image.getPixel(physicalY, physicalWidth - physicalX - 1);
	}

	private void putPhysicalPixel(int physicalX, int physicalY, int abgr) {
		if (physicalX < 0 || physicalY < 0 || physicalX >= physicalWidth || physicalY >= physicalHeight) return;
		image.setPixel(physicalY, physicalWidth - physicalX - 1, abgr);
	}

	private int position(int logicalValue) {
		return Math.round(logicalValue * physicalWidth / (float) LOGICAL_WIDTH);
	}

	private RouteAssetImage source(String path) {
		try {
			return sources.get(path);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to load route-sign source " + path, exception);
		}
	}

	private static int opaqueRgb(int rgb) {
		return RouteAssetImage.argbToAbgr(0xFF000000 | rgb & 0xFFFFFF);
	}

	private static String displayRouteName(String value) {
		return value.split("\\|\\|", -1)[0];
	}

	private static RouteAssetTextRasterizer.Alignment toTextAlignment(Horizontal horizontal) {
		return horizontal == Horizontal.CENTER ? RouteAssetTextRasterizer.Alignment.CENTER :
				horizontal == Horizontal.RIGHT ? RouteAssetTextRasterizer.Alignment.RIGHT : RouteAssetTextRasterizer.Alignment.LEFT;
	}

	private static String normalizeLanguage(String language) {
		final String value = Objects.requireNonNull(language, "language").trim().toUpperCase(Locale.ROOT);
		if (!value.equals("NORMAL") && !value.equals("CJK") && !value.equals("LATIN")) {
			throw new IllegalArgumentException("Unsupported route-sign language");
		}
		return value;
	}

	private enum Horizontal { LEFT, CENTER, RIGHT }
}
