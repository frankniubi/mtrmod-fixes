package org.mtr.mod.route;

import java.util.Objects;

public final class DestinationSignAtlasRenderer {

	private static final int WHITE = RouteAssetImage.argbToAbgr(0xFFFFFFFF);
	private static final int BLACK = RouteAssetImage.argbToAbgr(0xFF111111);
	private static final int GRAY = RouteAssetImage.argbToAbgr(0xFF6B7178);
	private static final int LIGHT_GRAY = RouteAssetImage.argbToAbgr(0xFFD8DCE0);

	private DestinationSignAtlasRenderer() {
	}

	public static RouteAssetImage render(DestinationSignAssetSnapshot snapshot, RouteAssetTextRasterizer text, int resolution) {
		final DestinationSignAssetSnapshot checkedSnapshot = Objects.requireNonNull(snapshot, "snapshot");
		final RouteAssetTextRasterizer checkedText = Objects.requireNonNull(text, "text");
		if (resolution < 0 || resolution > 3) throw new IllegalArgumentException("Invalid destination sign resolution");
		final int width = DestinationSignAtlasLayout.scaledSize(checkedSnapshot.getAtlasWidth(), resolution);
		final int height = DestinationSignAtlasLayout.scaledSize(checkedSnapshot.getAtlasHeight(), resolution);
		if ((long) width * height > RouteAssetProtocol.MAX_DESTINATION_SIGN_ATLAS_PIXELS) throw new IllegalArgumentException("Destination sign atlas exceeds the pixel limit");
		final RouteAssetImage image = new RouteAssetImage(width, height);
		image.fillRect(0, 0, width, height, WHITE);
		for (final DestinationSignAssetSnapshot.Sprite sprite : checkedSnapshot.getSprites()) {
			switch (sprite.getKind()) {
				case HEADER: drawHeader(image, checkedSnapshot, checkedText, sprite, resolution); break;
				case ROW: drawRow(image, checkedText, sprite, resolution); break;
				case LEAVING: drawStateLabel(image, checkedSnapshot, checkedText, DestinationSignAssetSnapshot.LEAVING_TEXT, sprite, 0xFFB42318, resolution); break;
				case NO_DIRECT_SERVICE: drawStateLabel(image, checkedSnapshot, checkedText, DestinationSignAssetSnapshot.NO_DIRECT_SERVICE_TEXT, sprite, 0xFF3F464D, resolution); break;
				case NO_SERVICE: drawStateLabel(image, checkedSnapshot, checkedText, DestinationSignAssetSnapshot.NO_SERVICE_TEXT, sprite, 0xFF3F464D, resolution); break;
			}
		}
		return image;
	}

	private static void drawHeader(RouteAssetImage image, DestinationSignAssetSnapshot snapshot, RouteAssetTextRasterizer text,
			DestinationSignAssetSnapshot.Sprite sprite, int resolution) {
		final int logicalHeight = sprite.getHeight();
		final int inset = Math.max(6, logicalHeight / 8);
		final int arrowWidth = Math.max(20, snapshot.getAtlasWidth() / 12);
		final int fontSize = clamp(14, (int) Math.floor(logicalHeight * 0.55), 28);
		final int textWidth = Math.max(1, snapshot.getAtlasWidth() - arrowWidth - inset * 3);
		drawSized(text, image, DestinationSignAtlasLayout.languageSegment(snapshot.getDestinationStationName(), sprite.getSegmentIndex()),
				inset, sprite.getY(), textWidth, logicalHeight, fontSize, BLACK, RouteAssetTextRasterizer.Alignment.RIGHT, resolution);
		drawSized(text, image, "\u2192", snapshot.getAtlasWidth() - arrowWidth - inset, sprite.getY(), arrowWidth, logicalHeight,
				fontSize, GRAY, RouteAssetTextRasterizer.Alignment.CENTER, resolution);
		fillLogicalRect(image, 0, sprite.getY() + logicalHeight - 1, snapshot.getAtlasWidth(), 1, LIGHT_GRAY, resolution);
	}

	private static void drawRow(RouteAssetImage image, RouteAssetTextRasterizer text, DestinationSignAssetSnapshot.Sprite sprite, int resolution) {
		final DestinationSignAssetSnapshot.RouteStripRecord record = Objects.requireNonNull(sprite.getRouteStrip(), "row route strip");
		final DestinationSignRouteStripLayout.RowMetrics metrics = record.getRowMetrics();
		final DestinationSignRouteStripLayout.RouteStrip strip = record.getRouteStrip();
		final int rowY = sprite.getY();
		final int routeColor = RouteAssetImage.argbToAbgr(0xFF000000 | record.getRouteColor());
		final int phase = sprite.getSegmentIndex();

		fillLogicalRect(image, metrics.getIdentityX(), rowY, metrics.getIdentityWidth(), metrics.getRowHeight(), routeColor, resolution);
		drawSized(text, image, DestinationSignAtlasLayout.languageSegment(record.getRouteName(), phase),
				metrics.getIdentityX() + 2, rowY + metrics.getIdentityTextTop(), metrics.getIdentityWidth() - 4,
				metrics.getRowFontSize(), metrics.getRowFontSize(), WHITE, RouteAssetTextRasterizer.Alignment.CENTER, resolution);
		drawSized(text, image, DestinationSignAtlasLayout.languageSegment(record.getPlatformName(), phase),
				metrics.getIdentityX() + 2, rowY + metrics.getIdentityTextTop() + metrics.getRowFontSize() + 2,
				metrics.getIdentityWidth() - 4, metrics.getRowFontSize(), metrics.getRowFontSize(), WHITE,
				RouteAssetTextRasterizer.Alignment.CENTER, resolution);

		final int stripX = metrics.getRouteStripX();
		final DestinationSignRouteStripLayout.Rail rail = strip.getRail();
		fillLogicalRect(image, stripX + rail.getStartX(), rowY + rail.getCenterY() - rail.getThickness() / 2,
				Math.max(1, rail.getEndX() - rail.getStartX() + 1), rail.getThickness(), routeColor, resolution);
		strip.getContinuationArrow().ifPresent(arrow -> drawArrow(image, stripX, rowY, arrow, routeColor, resolution));
		for (final DestinationSignRouteStripLayout.Marker marker : strip.getMarkers()) {
			drawMarker(image, stripX, rowY, marker, routeColor, resolution);
		}
		for (final DestinationSignRouteStripLayout.LabelSlot label : strip.getLabels()) {
			final String value = label.getRole() == DestinationSignRouteStripLayout.MarkerRole.CURRENT
					? DestinationSignAssetSnapshot.CURRENT_TEXT : label.getStationName();
			drawSized(text, image, DestinationSignAtlasLayout.languageSegment(value, phase), stripX + label.getX(), rowY + label.getY(),
					label.getWidth(), label.getHeight(), label.getFontSize(), BLACK, RouteAssetTextRasterizer.Alignment.CENTER, resolution);
		}
		if (metrics.getEtaWidth() > 0) {
			fillLogicalRect(image, metrics.getEtaX() - metrics.getColumnGap(), rowY, 1, metrics.getRowHeight(), LIGHT_GRAY, resolution);
		}
	}

	private static void drawMarker(RouteAssetImage image, int stripX, int rowY, DestinationSignRouteStripLayout.Marker marker,
			int routeColor, int resolution) {
		final int centerX = stripX + marker.getCenterX();
		final int centerY = rowY + marker.getCenterY();
		switch (marker.getRole()) {
			case ELLIPSIS:
				for (int offset = -2; offset <= 2; offset += 2) fillLogicalRect(image, centerX + offset - 1, centerY - 1, 2, 2, routeColor, resolution);
				break;
			case CURRENT:
				fillLogicalCircle(image, centerX, centerY, marker.getOuterDiameter(), BLACK, 1, resolution);
				break;
			case TARGET:
				fillLogicalCircle(image, centerX, centerY, marker.getOuterDiameter(), routeColor, 1, resolution);
				fillLogicalCircle(image, centerX, centerY, Math.max(0, marker.getOuterDiameter() - 2), WHITE, 1, resolution);
				fillLogicalCircle(image, centerX, centerY, marker.getInnerDiameter(), routeColor, 1, resolution);
				break;
			default:
				fillLogicalCircle(image, centerX, centerY, marker.getOuterDiameter(), routeColor, marker.getOpacity(), resolution);
				fillLogicalCircle(image, centerX, centerY, Math.max(1, marker.getOuterDiameter() - 2), WHITE, 1, resolution);
				break;
		}
	}

	private static void drawArrow(RouteAssetImage image, int stripX, int rowY, DestinationSignRouteStripLayout.ContinuationArrow arrow,
			int color, int resolution) {
		final int centerY = rowY + arrow.getTopY() + arrow.getHeight() / 2;
		for (int x = 0; x < arrow.getWidth(); x++) {
			final int halfHeight = Math.max(0, x * arrow.getHeight() / Math.max(1, arrow.getWidth() - 1) / 2);
			fillLogicalRect(image, stripX + arrow.getBaseX() + x, centerY - halfHeight, 1, halfHeight * 2 + 1, color, resolution);
		}
	}

	private static void drawStateLabel(RouteAssetImage image, DestinationSignAssetSnapshot snapshot, RouteAssetTextRasterizer text,
			String label, DestinationSignAssetSnapshot.Sprite sprite, int argb, int resolution) {
		final DestinationSignRouteStripLayout.RowMetrics metrics = DestinationSignRouteStripLayout.rowMetrics(
				snapshot.getWidthBlocks(), snapshot.getLayout().getRowHeight(), snapshot.isShowEta());
		final boolean fullWidth = sprite.getKind() == DestinationSignAssetSnapshot.SpriteKind.NO_DIRECT_SERVICE || !snapshot.isShowEta();
		final int x = fullWidth ? metrics.getOuterInset() : metrics.getEtaX();
		final int width = fullWidth ? snapshot.getAtlasWidth() - metrics.getOuterInset() * 2 : metrics.getEtaWidth();
		drawSized(text, image, DestinationSignAtlasLayout.languageSegment(label, sprite.getSegmentIndex()), x, sprite.getY(), width,
				sprite.getHeight(), metrics.getRowFontSize(), RouteAssetImage.argbToAbgr(argb), RouteAssetTextRasterizer.Alignment.CENTER, resolution);
	}

	private static void drawSized(RouteAssetTextRasterizer text, RouteAssetImage image, String value, int x, int y, int width, int height,
			int fontSize, int color, RouteAssetTextRasterizer.Alignment alignment, int resolution) {
		final int scaledX = DestinationSignAtlasLayout.scaledEdge(x, resolution);
		final int scaledY = DestinationSignAtlasLayout.scaledEdge(y, resolution);
		final int scaledWidth = Math.max(1, DestinationSignAtlasLayout.scaledEdge(x + width, resolution) - scaledX);
		final int scaledHeight = Math.max(1, DestinationSignAtlasLayout.scaledEdge(y + height, resolution) - scaledY);
		text.drawSized(image, value, scaledX, scaledY, scaledWidth, scaledHeight,
				DestinationSignAtlasLayout.scaledSize(fontSize, resolution), color, alignment);
	}

	private static void fillLogicalCircle(RouteAssetImage image, int centerX, int centerY, int diameter, int color, double opacity, int resolution) {
		if (diameter <= 0) return;
		final int left = centerX - diameter / 2;
		final int top = centerY - diameter / 2;
		final int radius = diameter;
		for (int y = 0; y < diameter; y++) {
			for (int x = 0; x < diameter; x++) {
				final int dx = 2 * x + 1 - diameter;
				final int dy = 2 * y + 1 - diameter;
				if (dx * dx + dy * dy <= radius * radius) fillLogicalRect(image, left + x, top + y, 1, 1, withOpacity(color, opacity), resolution);
			}
		}
	}

	private static int withOpacity(int abgr, double opacity) {
		return ((int) Math.floor(255 * opacity + 0.5) << 24) | abgr & 0xFFFFFF;
	}

	private static void fillLogicalRect(RouteAssetImage image, int x, int y, int width, int height, int color, int resolution) {
		if (width <= 0 || height <= 0) return;
		final int left = DestinationSignAtlasLayout.scaledEdge(Math.max(0, x), resolution);
		final int top = DestinationSignAtlasLayout.scaledEdge(Math.max(0, y), resolution);
		final int right = Math.min(image.getWidth(), DestinationSignAtlasLayout.scaledEdge(Math.max(0, x + width), resolution));
		final int bottom = Math.min(image.getHeight(), DestinationSignAtlasLayout.scaledEdge(Math.max(0, y + height), resolution));
		if ((color >>> 24) == 0xFF) {
			image.fillRect(left, top, Math.max(0, right - left), Math.max(0, bottom - top), color);
		} else {
			for (int drawY = top; drawY < bottom; drawY++) {
				for (int drawX = left; drawX < right; drawX++) RouteAssetTextRasterizer.blend(image, drawX, drawY, color);
			}
		}
	}

	private static int clamp(int minimum, int value, int maximum) {
		return Math.max(minimum, Math.min(value, maximum));
	}
}
