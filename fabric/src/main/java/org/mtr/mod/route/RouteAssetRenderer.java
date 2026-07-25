package org.mtr.mod.route;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RouteAssetRenderer {

	private static final int WHITE = RouteAssetImage.argbToAbgr(0xFFFFFFFF);
	private static final int BLACK = RouteAssetImage.argbToAbgr(0xFF000000);
	private static final int PASSED = RouteAssetImage.argbToAbgr(0xFFB5B8BA);
	private static final int INTERCHANGE_BLUE = RouteAssetImage.argbToAbgr(0xFF21679F);
	private static final String ARROW_RESOURCE = "textures/block/sign/arrow.png";
	private static final String RAILWAY_RESOURCE = "textures/block/sign/railway_interchange.png";
	private static final String AIRPORT_RESOURCE = "textures/block/sign/airplane.png";

	public RouteAssetImage render(RouteAssetKey key, RouteAssetRenderSnapshot snapshot, RouteAssetTextRasterizer text, RouteAssetSourceImages sources) {
		Objects.requireNonNull(key, "key");
		Objects.requireNonNull(snapshot, "snapshot");
		Objects.requireNonNull(text, "text");
		Objects.requireNonNull(sources, "sources");
		switch (key.getType()) {
			case ROUTE_COLOR_STRIP:
				return renderColorStrip(snapshot);
			case ROUTE_SQUARE:
				return renderRouteSquare(key, snapshot, text);
			case DIRECTION_ARROW:
				return renderDirectionArrow(key, snapshot, text, sources);
			case ROUTE_MAP:
				return renderRouteMap(key, snapshot, text, sources);
			default:
				throw new IllegalArgumentException("Unsupported route asset type: " + key.getType());
		}
	}

	private static RouteAssetImage renderColorStrip(RouteAssetRenderSnapshot snapshot) {
		final List<Integer> colors = snapshot.getRouteColors().isEmpty() ? Collections.singletonList(snapshot.getRouteColor()) : snapshot.getRouteColors();
		final RouteAssetImage image = new RouteAssetImage(1, Math.max(1, colors.size()));
		for (int index = 0; index < colors.size(); index++) {
			image.setPixel(0, index, opaqueRgb(colors.get(index)));
		}
		return image;
	}

	private static RouteAssetImage renderRouteSquare(RouteAssetKey key, RouteAssetRenderSnapshot snapshot, RouteAssetTextRasterizer text) {
		final int scale = scale(key);
		final RouteAssetImage image = new RouteAssetImage(scale * 2, scale);
		image.fillRect(0, 0, image.getWidth(), image.getHeight(), opaqueRgb(snapshot.getRouteColor()));
		final int padding = Math.max(2, scale / 16);
		text.draw(image, snapshot.getRouteName(), padding, padding, image.getWidth() - padding * 2, image.getHeight() - padding * 2, WHITE, alignment(key));
		return image;
	}

	private static RouteAssetImage renderDirectionArrow(RouteAssetKey key, RouteAssetRenderSnapshot snapshot, RouteAssetTextRasterizer text, RouteAssetSourceImages sources) {
		final int height = scale(key);
		final int width = Math.max(1, Math.round(height * snapshot.getAspectRatio()));
		final RouteAssetImage image = new RouteAssetImage(width, height);
		image.fillRect(0, 0, width, height, RouteAssetImage.argbToAbgr(snapshot.getBackgroundColor()));
		final int padding = Math.max(1, Math.round(height * snapshot.getPaddingScale()));
		final int iconSize = Math.max(1, height - padding * 2);
		int textLeft = padding;
		int textRight = width - padding;
		try {
			final RouteAssetImage arrow = sources.get(ARROW_RESOURCE);
			if (snapshot.hasLeft()) {
				drawTinted(image, arrow, padding, padding, iconSize, iconSize, RouteAssetImage.argbToAbgr(snapshot.getTextColor()), true);
				textLeft += iconSize + padding;
			}
			if (snapshot.hasRight()) {
				drawTinted(image, arrow, Math.max(padding, width - padding - iconSize), padding, iconSize, iconSize, RouteAssetImage.argbToAbgr(snapshot.getTextColor()), false);
				textRight -= iconSize + padding;
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to load direction arrow source", exception);
		}
		final String label = snapshot.isShowToString() ? snapshot.getDestination() : "";
		if (!label.isEmpty() && textRight > textLeft) {
			text.draw(image, label, textLeft, padding, textRight - textLeft, iconSize, RouteAssetImage.argbToAbgr(snapshot.getTextColor()), alignment(key));
		}
		return image;
	}

	private static RouteAssetImage renderRouteMap(RouteAssetKey key, RouteAssetRenderSnapshot snapshot, RouteAssetTextRasterizer text, RouteAssetSourceImages sources) {
		final int longSide = scale(key) * 4;
		final float aspectRatio = snapshot.getAspectRatio();
		final int width;
		final int height;
		if (aspectRatio >= 1) {
			width = longSide;
			height = Math.max(scale(key), Math.round(longSide / aspectRatio));
		} else {
			height = longSide;
			width = Math.max(scale(key), Math.round(longSide * aspectRatio));
		}
		final RouteAssetImage image = new RouteAssetImage(width, height);
		image.fillRect(0, 0, width, height, RouteAssetImage.argbToAbgr(snapshot.getBackgroundColor()));
		if (snapshot.isDense()) {
			drawDenseMap(image, snapshot, text, sources);
		} else {
			drawTopologyMap(image, snapshot, text, sources);
		}
		return image;
	}

	private static void drawTopologyMap(RouteAssetImage image, RouteAssetRenderSnapshot snapshot, RouteAssetTextRasterizer text, RouteAssetSourceImages sources) {
		final List<RouteAssetRenderSnapshot.Station> stations = orderedStations(snapshot);
		if (stations.isEmpty()) return;
		final int padding = Math.max(6, Math.min(image.getWidth(), image.getHeight()) / 12);
		final int lineWidth = Math.max(2, Math.min(image.getWidth(), image.getHeight()) / 32);
		final int radius = Math.max(4, lineWidth * 2);
		final int routeColor = opaqueRgb(snapshot.getRouteColor());
		if (snapshot.isVertical()) {
			final int lineX = Math.max(padding, image.getWidth() / 3);
			fillClamped(image, lineX - lineWidth / 2, padding, lineWidth, Math.max(1, image.getHeight() - padding * 2), routeColor);
			for (int index = 0; index < stations.size(); index++) {
				final int stationY = position(index, stations.size(), padding, image.getHeight() - padding);
				drawStation(image, stations.get(index), lineX, stationY, radius, routeColor);
				final int rowHeight = Math.max(12, (image.getHeight() - padding * 2) / Math.max(1, stations.size()));
				final int textX = Math.min(image.getWidth() - 1, lineX + radius + Math.max(3, lineWidth));
				final int textY = Math.max(0, Math.min(image.getHeight() - rowHeight, stationY - rowHeight / 2));
				final int iconWidth = drawStationIcons(image, sources, stations.get(index), image.getWidth() - padding, textY, rowHeight);
				final int textWidth = Math.max(1, image.getWidth() - padding - textX - iconWidth);
				text.draw(image, stations.get(index).getName(), textX, textY, textWidth, rowHeight, stations.get(index).isPassed() ? PASSED : RouteAssetImage.argbToAbgr(snapshot.getTextColor()), RouteAssetTextRasterizer.Alignment.LEFT);
			}
		} else {
			final int lineY = image.getHeight() / 2;
			fillClamped(image, padding, lineY - lineWidth / 2, Math.max(1, image.getWidth() - padding * 2), lineWidth, routeColor);
			for (int index = 0; index < stations.size(); index++) {
				final int stationX = position(index, stations.size(), padding, image.getWidth() - padding);
				drawStation(image, stations.get(index), stationX, lineY, radius, routeColor);
				final boolean below = index % 2 == 0;
				final int textY = below ? lineY + radius + 2 : padding;
				final int textHeight = Math.max(1, (below ? image.getHeight() - padding : lineY - radius - 2) - textY);
				final int columnWidth = Math.max(16, (image.getWidth() - padding * 2) / Math.max(1, stations.size()));
				final int textX = Math.max(0, Math.min(image.getWidth() - columnWidth, stationX - columnWidth / 2));
				text.draw(image, stations.get(index).getName(), textX, textY, columnWidth, textHeight, stations.get(index).isPassed() ? PASSED : RouteAssetImage.argbToAbgr(snapshot.getTextColor()), RouteAssetTextRasterizer.Alignment.CENTER);
			}
		}
	}

	private static void drawDenseMap(RouteAssetImage image, RouteAssetRenderSnapshot snapshot, RouteAssetTextRasterizer text, RouteAssetSourceImages sources) {
		final List<RouteAssetRenderSnapshot.Station> stations = orderedStations(snapshot);
		final List<Integer> colors = snapshot.getRouteColors().isEmpty() ? Collections.singletonList(snapshot.getRouteColor()) : snapshot.getRouteColors();
		final int headerHeight = Math.max(8, image.getHeight() / 9);
		for (int index = 0; index < colors.size(); index++) {
			final int left = index * image.getWidth() / colors.size();
			final int right = (index + 1) * image.getWidth() / colors.size();
			image.fillRect(left, 0, right - left, headerHeight, opaqueRgb(colors.get(index)));
		}
		final int padding = Math.max(5, image.getWidth() / 20);
		final int rowHeight = Math.max(1, (image.getHeight() - headerHeight - padding) / Math.max(1, stations.size()));
		final int lineX = padding * 2;
		for (int colorIndex = 0; colorIndex < colors.size(); colorIndex++) {
			fillClamped(image, lineX + colorIndex * Math.max(1, padding / Math.max(1, colors.size())), headerHeight, Math.max(1, padding / Math.max(1, colors.size())), image.getHeight() - headerHeight, opaqueRgb(colors.get(colorIndex)));
		}
		for (int index = 0; index < stations.size(); index++) {
			final RouteAssetRenderSnapshot.Station station = stations.get(index);
			final int rowY = headerHeight + index * rowHeight;
			final int centerY = Math.min(image.getHeight() - 1, rowY + rowHeight / 2);
			drawStation(image, station, lineX, centerY, Math.max(3, padding / 2), opaqueRgb(snapshot.getRouteColor()));
			final int textX = Math.min(image.getWidth() - 1, lineX + padding);
			final int iconWidth = drawStationIcons(image, sources, station, image.getWidth() - padding, rowY, rowHeight);
			text.draw(image, station.getName(), textX, rowY, Math.max(1, image.getWidth() - padding - textX - iconWidth), Math.max(1, Math.min(rowHeight, image.getHeight() - rowY)), station.isPassed() ? PASSED : RouteAssetImage.argbToAbgr(snapshot.getTextColor()), RouteAssetTextRasterizer.Alignment.LEFT);
		}
	}

	private static List<RouteAssetRenderSnapshot.Station> orderedStations(RouteAssetRenderSnapshot snapshot) {
		final ArrayList<RouteAssetRenderSnapshot.Station> stations = new ArrayList<>(snapshot.getStations());
		if (snapshot.isFlip()) Collections.reverse(stations);
		return stations;
	}

	private static void drawStation(RouteAssetImage image, RouteAssetRenderSnapshot.Station station, int centerX, int centerY, int radius, int routeColor) {
		final int outline = station.isPassed() ? PASSED : routeColor;
		for (int offsetX = -radius; offsetX <= radius; offsetX++) {
			for (int offsetY = -radius; offsetY <= radius; offsetY++) {
				final int x = centerX + offsetX;
				final int y = centerY + offsetY;
				if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight() && offsetX * offsetX + offsetY * offsetY <= radius * radius) {
					final int inner = Math.max(0, radius - Math.max(2, radius / 3));
					image.setPixel(x, y, offsetX * offsetX + offsetY * offsetY <= inner * inner ? (station.isCurrent() ? outline : WHITE) : outline);
				}
			}
		}
	}

	private static int drawStationIcons(RouteAssetImage image, RouteAssetSourceImages sources, RouteAssetRenderSnapshot.Station station, int right, int top, int availableHeight) {
		final int count = (station.hasRailwayInterchange() ? 1 : 0) + (station.hasAirportInterchange() ? 1 : 0);
		if (count == 0) return 0;
		final int size = Math.max(1, Math.min(availableHeight, Math.max(8, image.getWidth() / 12)));
		final int gap = Math.max(1, size / 5);
		int x = right - count * size - (count - 1) * gap;
		final int y = Math.max(0, Math.min(image.getHeight() - size, top + Math.max(0, (availableHeight - size) / 2)));
		final int tint = station.isPassed() ? PASSED : INTERCHANGE_BLUE;
		try {
			if (station.hasRailwayInterchange()) {
				drawTinted(image, sources.get(RAILWAY_RESOURCE), x, y, size, size, tint, false);
				x += size + gap;
			}
			if (station.hasAirportInterchange()) drawTinted(image, sources.get(AIRPORT_RESOURCE), x, y, size, size, tint, false);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to load route interchange source", exception);
		}
		return count * size + (count - 1) * gap + gap;
	}

	private static void drawTinted(RouteAssetImage target, RouteAssetImage source, int x, int y, int width, int height, int tint, boolean flipX) {
		for (int drawX = 0; drawX < width; drawX++) {
			for (int drawY = 0; drawY < height; drawY++) {
				final int targetX = x + drawX;
				final int targetY = y + drawY;
				if (targetX < 0 || targetY < 0 || targetX >= target.getWidth() || targetY >= target.getHeight()) continue;
				final int sampleX = (flipX ? width - drawX - 1 : drawX) * source.getWidth() / Math.max(1, width);
				final int sampleY = drawY * source.getHeight() / Math.max(1, height);
				final int alpha = source.getPixel(Math.min(source.getWidth() - 1, sampleX), Math.min(source.getHeight() - 1, sampleY)) >>> 24;
				if (alpha != 0) blend(target, targetX, targetY, alpha << 24 | tint & 0xFFFFFF);
			}
		}
	}

	private static void blend(RouteAssetImage target, int x, int y, int source) {
		final int sourceAlpha = source >>> 24;
		final int destination = target.getPixel(x, y);
		final int inverse = 255 - sourceAlpha;
		final int red = ((source & 0xFF) * sourceAlpha + (destination & 0xFF) * inverse) / 255;
		final int green = (((source >>> 8) & 0xFF) * sourceAlpha + ((destination >>> 8) & 0xFF) * inverse) / 255;
		final int blue = (((source >>> 16) & 0xFF) * sourceAlpha + ((destination >>> 16) & 0xFF) * inverse) / 255;
		final int alpha = Math.min(255, sourceAlpha + ((destination >>> 24) * inverse) / 255);
		target.setPixel(x, y, alpha << 24 | blue << 16 | green << 8 | red);
	}

	private static void fillClamped(RouteAssetImage image, int x, int y, int width, int height, int color) {
		final int left = Math.max(0, x);
		final int top = Math.max(0, y);
		final int right = Math.min(image.getWidth(), x + width);
		final int bottom = Math.min(image.getHeight(), y + height);
		if (right > left && bottom > top) image.fillRect(left, top, right - left, bottom - top, color);
	}

	private static int position(int index, int count, int start, int end) {
		return count <= 1 ? (start + end) / 2 : start + Math.round((float) index * (end - start) / (count - 1));
	}

	private static int scale(RouteAssetKey key) {
		return 1 << key.getVariant().getResolution() + 5;
	}

	private static int opaqueRgb(int rgb) {
		return RouteAssetImage.argbToAbgr(0xFF000000 | rgb & 0xFFFFFF);
	}

	private static RouteAssetTextRasterizer.Alignment alignment(RouteAssetKey key) {
		final Map<String, String> parameters = key.getVariant().getParameters();
		try {
			return RouteAssetTextRasterizer.Alignment.valueOf(parameters.getOrDefault("align", "CENTER"));
		} catch (IllegalArgumentException exception) {
			return RouteAssetTextRasterizer.Alignment.CENTER;
		}
	}
}
