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
			final int y = DestinationSignAtlasLayout.scaledEdge(sprite.getY(), resolution);
			final int bottom = DestinationSignAtlasLayout.scaledEdge(sprite.getY() + sprite.getHeight(), resolution);
			final int spriteHeight = bottom - y;
			switch (sprite.getKind()) {
				case HEADER: drawHeader(image, checkedSnapshot, checkedText, sprite.getSegmentIndex(), y, spriteHeight); break;
				case ROW: drawRow(image, checkedSnapshot, checkedText, sprite, y, spriteHeight, resolution); break;
				case LEAVING: drawLabel(image, checkedSnapshot, checkedText, DestinationSignAssetSnapshot.LEAVING_TEXT, sprite.getKind(), sprite.getSegmentIndex(), y, spriteHeight, 0xFFB42318, resolution); break;
				case NO_DIRECT_SERVICE: drawLabel(image, checkedSnapshot, checkedText, DestinationSignAssetSnapshot.NO_DIRECT_SERVICE_TEXT, sprite.getKind(), sprite.getSegmentIndex(), y, spriteHeight, 0xFF3F464D, resolution); break;
				case NO_SERVICE: drawLabel(image, checkedSnapshot, checkedText, DestinationSignAssetSnapshot.NO_SERVICE_TEXT, sprite.getKind(), sprite.getSegmentIndex(), y, spriteHeight, 0xFF3F464D, resolution); break;
			}
		}
		return image;
	}

	private static void drawHeader(RouteAssetImage image, DestinationSignAssetSnapshot snapshot, RouteAssetTextRasterizer text, int phase, int y, int height) {
		final int inset = Math.max(4, height / 6);
		final String destination = DestinationSignAtlasLayout.languageSegment(snapshot.getDestinationStationName(), phase);
		final int arrowWidth = Math.max(20, image.getWidth() / 12);
		final int destinationWidth = Math.max(1, image.getWidth() - arrowWidth - inset * 3);
		final int arrowX = image.getWidth() - arrowWidth - inset;
		text.draw(image, destination, inset, y + inset / 2,
				destinationWidth, Math.max(1, height - inset), BLACK, RouteAssetTextRasterizer.Alignment.RIGHT);
		text.draw(image, "\u2192", arrowX, y + inset / 2,
				arrowWidth, Math.max(1, height - inset), GRAY, RouteAssetTextRasterizer.Alignment.CENTER);
		drawSeparator(image, y);
	}

	private static void drawRow(RouteAssetImage image, DestinationSignAssetSnapshot snapshot, RouteAssetTextRasterizer text,
			DestinationSignAssetSnapshot.Sprite sprite, int y, int height, int resolution) {
		final DestinationSignDirectServiceModel.Option option = Objects.requireNonNull(sprite.getOption(), "row option");
		final int phase = sprite.getSegmentIndex();
		final DestinationSignAtlasLayout.RowGeometry geometry = DestinationSignAtlasLayout.rowGeometry(snapshot.getStyle(), snapshot.getAtlasWidth(), snapshot.getStyle().getRowHeight(), snapshot.isShowEta());
		final int inset = DestinationSignAtlasLayout.scaledSpan(0, geometry.getInset(), resolution);
		final int routeX = DestinationSignAtlasLayout.scaledEdge(geometry.getRouteX(), resolution);
		final int routeWidth = DestinationSignAtlasLayout.scaledSpan(geometry.getRouteX(), geometry.getRouteWidth(), resolution);
		final int platformX = DestinationSignAtlasLayout.scaledEdge(geometry.getPlatformX(), resolution);
		final int platformWidth = DestinationSignAtlasLayout.scaledSpan(geometry.getPlatformX(), geometry.getPlatformWidth(), resolution);
		final int routeColor = RouteAssetImage.argbToAbgr(0xFF000000 | option.getRoute().getColor());
		final String route = DestinationSignAtlasLayout.languageSegment(option.getRoute().getDisplayName(), phase);
		final String platform = DestinationSignAtlasLayout.languageSegment(option.getSource().getPlatformDisplayName(), phase);

		switch (snapshot.getStyle()) {
			case ARRIVAL_ORDER: {
				image.fillRect(routeX, y + inset, routeWidth, Math.max(1, height - inset * 2), routeColor);
				text.draw(image, route, routeX + DestinationSignAtlasLayout.scaledSpan(0, 3, resolution), y + inset, Math.max(1, routeWidth - DestinationSignAtlasLayout.scaledSpan(0, 6, resolution)), Math.max(1, height - inset * 2), WHITE, RouteAssetTextRasterizer.Alignment.CENTER);
				text.draw(image, platform, platformX, y + inset, platformWidth, Math.max(1, height - inset * 2), BLACK, RouteAssetTextRasterizer.Alignment.CENTER);
				break;
			}
			case PLATFORM_GROUPS: {
				final int barWidth = Math.max(1, DestinationSignAtlasLayout.scaledSpan(0, Math.max(2, geometry.getInset() / 2), resolution));
				image.fillRect(inset, y + inset, barWidth, Math.max(1, height - inset * 2), routeColor);
				text.draw(image, platform, platformX, y + inset, platformWidth, Math.max(1, height - inset * 2), BLACK, RouteAssetTextRasterizer.Alignment.RIGHT);
				text.draw(image, route, routeX, y + inset, routeWidth, Math.max(1, height - inset * 2), routeColor, RouteAssetTextRasterizer.Alignment.RIGHT);
				break;
			}
			case DESTINATION_FLAG: {
				final int barWidth = Math.max(1, DestinationSignAtlasLayout.scaledSpan(0, Math.max(3, geometry.getInset() / 2), resolution));
				image.fillRect(inset, y + inset, barWidth, Math.max(1, height - inset * 2), routeColor);
				text.draw(image, route, routeX, y + inset, routeWidth, Math.max(1, height - inset * 2), routeColor, RouteAssetTextRasterizer.Alignment.RIGHT);
				text.draw(image, platform, platformX, y + inset, platformWidth, Math.max(1, height - inset * 2), BLACK, RouteAssetTextRasterizer.Alignment.CENTER);
				break;
			}
		}
		if (snapshot.isShowEta()) {
			final int dividerX = DestinationSignAtlasLayout.scaledEdge(geometry.getEtaDividerX(), resolution);
			final int dividerWidth = Math.max(1, DestinationSignAtlasLayout.scaledSpan(0, Math.max(1, geometry.getInset() / 4), resolution));
			image.fillRect(dividerX, y + inset, dividerWidth, Math.max(1, height - inset * 2), LIGHT_GRAY);
		}
		drawSeparator(image, y);
	}

	private static void drawLabel(RouteAssetImage image, DestinationSignAssetSnapshot snapshot, RouteAssetTextRasterizer text, String label,
			DestinationSignAssetSnapshot.SpriteKind kind, int phase, int y, int height, int argb, int resolution) {
		final int x;
		final int width;
		final int inset;
		if (kind == DestinationSignAssetSnapshot.SpriteKind.NO_DIRECT_SERVICE || !snapshot.isShowEta()) {
			inset = Math.max(4, height / 8);
			x = inset;
			width = image.getWidth() - inset * 2;
		} else {
			final DestinationSignAtlasLayout.RowGeometry geometry = DestinationSignAtlasLayout.rowGeometry(snapshot.getStyle(), snapshot.getAtlasWidth(), snapshot.getStyle().getRowHeight(), true);
			inset = DestinationSignAtlasLayout.scaledSpan(0, geometry.getInset(), resolution);
			width = DestinationSignAtlasLayout.scaledSpan(geometry.getEtaX(), geometry.getEtaWidth(), resolution);
			x = DestinationSignAtlasLayout.scaledEdge(geometry.getEtaX(), resolution);
		}
		text.draw(image, DestinationSignAtlasLayout.languageSegment(label, phase), x, y + inset, Math.max(1, width), Math.max(1, height - inset * 2), RouteAssetImage.argbToAbgr(argb), RouteAssetTextRasterizer.Alignment.CENTER);
		drawSeparator(image, y);
	}

	private static void drawSeparator(RouteAssetImage image, int y) {
		if (y >= 0 && y < image.getHeight()) image.fillRect(0, y, image.getWidth(), 1, LIGHT_GRAY);
	}

}
