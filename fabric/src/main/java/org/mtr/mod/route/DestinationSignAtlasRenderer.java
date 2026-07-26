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
		final int width = scaled(checkedSnapshot.getAtlasWidth(), resolution);
		final int height = scaled(checkedSnapshot.getAtlasHeight(), resolution);
		if ((long) width * height > RouteAssetProtocol.MAX_DESTINATION_SIGN_ATLAS_PIXELS) throw new IllegalArgumentException("Destination sign atlas exceeds the pixel limit");
		final RouteAssetImage image = new RouteAssetImage(width, height);
		image.fillRect(0, 0, width, height, WHITE);
		for (final DestinationSignAssetSnapshot.Sprite sprite : checkedSnapshot.getSprites()) {
			final int y = scaled(sprite.getY(), resolution);
			final int bottom = scaled(sprite.getY() + sprite.getHeight(), resolution);
			final int spriteHeight = bottom - y;
			switch (sprite.getKind()) {
				case HEADER: drawHeader(image, checkedSnapshot, checkedText, sprite.getSegmentIndex(), y, spriteHeight); break;
				case ROW: drawRow(image, checkedSnapshot, checkedText, sprite, y, spriteHeight); break;
				case LEAVING: drawLabel(image, checkedText, DestinationSignAssetSnapshot.LEAVING_TEXT, sprite.getSegmentIndex(), y, spriteHeight, 0xFFB42318); break;
				case NO_DIRECT_SERVICE: drawLabel(image, checkedText, DestinationSignAssetSnapshot.NO_DIRECT_SERVICE_TEXT, sprite.getSegmentIndex(), y, spriteHeight, 0xFF3F464D); break;
				case NO_SERVICE: drawLabel(image, checkedText, DestinationSignAssetSnapshot.NO_SERVICE_TEXT, sprite.getSegmentIndex(), y, spriteHeight, 0xFF3F464D); break;
			}
		}
		return image;
	}

	private static void drawHeader(RouteAssetImage image, DestinationSignAssetSnapshot snapshot, RouteAssetTextRasterizer text, int phase, int y, int height) {
		final int inset = Math.max(4, height / 6);
		final int arrowWidth = Math.max(20, image.getWidth() / 12);
		final String source = DestinationSignAtlasLayout.languageSegment(snapshot.getSourceStationName(), phase);
		final String destination = DestinationSignAtlasLayout.languageSegment(snapshot.getDestinationStationName(), phase);
		text.draw(image, source, inset, y + inset / 2, Math.max(1, image.getWidth() / 3 - inset * 2), Math.max(1, height - inset), BLACK, RouteAssetTextRasterizer.Alignment.LEFT);
		text.draw(image, "\u2192", image.getWidth() / 3, y + inset / 2, arrowWidth, Math.max(1, height - inset), GRAY, RouteAssetTextRasterizer.Alignment.CENTER);
		text.draw(image, destination, image.getWidth() / 3 + arrowWidth, y + inset / 2, Math.max(1, image.getWidth() * 2 / 3 - arrowWidth - inset), Math.max(1, height - inset), BLACK, RouteAssetTextRasterizer.Alignment.LEFT);
		drawSeparator(image, y + height - 1);
	}

	private static void drawRow(RouteAssetImage image, DestinationSignAssetSnapshot snapshot, RouteAssetTextRasterizer text,
			DestinationSignAssetSnapshot.Sprite sprite, int y, int height) {
		final DestinationSignDirectServiceModel.Option option = Objects.requireNonNull(sprite.getOption(), "row option");
		final int phase = sprite.getSegmentIndex();
		final int inset = Math.max(4, height / 8);
		final int etaWidth = snapshot.isShowEta() ? Math.max(44, image.getWidth() / 5) : 0;
		final int contentRight = image.getWidth() - inset - etaWidth;
		final int routeColor = RouteAssetImage.argbToAbgr(0xFF000000 | option.getRoute().getColor());
		final String route = DestinationSignAtlasLayout.languageSegment(option.getRoute().getDisplayName(), phase);
		final String platform = DestinationSignAtlasLayout.languageSegment(option.getSource().getPlatformDisplayName(), phase);
		final String destination = DestinationSignAtlasLayout.languageSegment(option.getDestination().getStationDisplayName(), phase);

		switch (snapshot.getStyle()) {
			case ARRIVAL_ORDER: {
				final int badgeWidth = Math.max(54, image.getWidth() / 5);
				image.fillRect(inset, y + inset, badgeWidth, Math.max(1, height - inset * 2), routeColor);
				text.draw(image, route, inset + 3, y + inset, badgeWidth - 6, Math.max(1, height - inset * 2), WHITE, RouteAssetTextRasterizer.Alignment.CENTER);
				final int platformWidth = Math.max(44, image.getWidth() / 6);
				text.draw(image, platform, inset + badgeWidth + inset, y + inset, platformWidth, Math.max(1, height - inset * 2), BLACK, RouteAssetTextRasterizer.Alignment.LEFT);
				text.draw(image, destination, inset + badgeWidth + inset + platformWidth, y + inset, Math.max(1, contentRight - inset - badgeWidth - inset - platformWidth), Math.max(1, height - inset * 2), GRAY, RouteAssetTextRasterizer.Alignment.LEFT);
				break;
			}
			case PLATFORM_GROUPS: {
				final int platformWidth = Math.max(64, image.getWidth() / 4);
				image.fillRect(inset, y + inset, Math.max(2, inset / 2), Math.max(1, height - inset * 2), routeColor);
				text.draw(image, platform, inset * 2, y + inset, platformWidth - inset, Math.max(1, height - inset * 2), BLACK, RouteAssetTextRasterizer.Alignment.LEFT);
				text.draw(image, route, inset + platformWidth, y + inset, Math.max(1, contentRight - inset - platformWidth), Math.max(1, height - inset * 2), routeColor, RouteAssetTextRasterizer.Alignment.LEFT);
				break;
			}
			case DESTINATION_FLAG: {
				final int bandWidth = Math.max(72, image.getWidth() / 3);
				image.fillRect(inset, y + inset, Math.max(3, inset / 2), Math.max(1, height - inset * 2), routeColor);
				text.draw(image, destination, inset * 2, y + inset / 2, bandWidth - inset, Math.max(1, height / 2), BLACK, RouteAssetTextRasterizer.Alignment.LEFT);
				text.draw(image, route + "  " + platform, inset * 2, y + height / 2, Math.max(1, contentRight - inset * 2), Math.max(1, height / 2 - inset), GRAY, RouteAssetTextRasterizer.Alignment.LEFT);
				break;
			}
		}
		if (etaWidth > 0) image.fillRect(image.getWidth() - etaWidth, y + inset, Math.max(1, inset / 4), Math.max(1, height - inset * 2), LIGHT_GRAY);
		drawSeparator(image, y + height - 1);
	}

	private static void drawLabel(RouteAssetImage image, RouteAssetTextRasterizer text, String label, int phase, int y, int height, int argb) {
		final int inset = Math.max(4, height / 8);
		text.draw(image, DestinationSignAtlasLayout.languageSegment(label, phase), inset, y + inset, image.getWidth() - inset * 2, Math.max(1, height - inset * 2), RouteAssetImage.argbToAbgr(argb), RouteAssetTextRasterizer.Alignment.CENTER);
		drawSeparator(image, y + height - 1);
	}

	private static void drawSeparator(RouteAssetImage image, int y) {
		if (y >= 0 && y < image.getHeight()) image.fillRect(0, y, image.getWidth(), 1, LIGHT_GRAY);
	}

	private static int scaled(int logical, int resolution) {
		return resolution == 0 ? Math.max(1, (logical + 1) / 2) : Math.multiplyExact(logical, 1 << resolution - 1);
	}
}
