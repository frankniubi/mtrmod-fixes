package org.mtr.mod.route;

import org.mtr.mod.generated.lang.TranslationProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** The legacy RouteMapGenerator raster algorithms with no client or GPU linkage. */
public final class RouteAssetRenderer {

	private static final int RGB_WHITE = 0xFFFFFF;
	private static final int ARGB_WHITE = 0xFFFFFFFF;
	private static final int ARGB_BLACK = 0xFF000000;
	private static final int ARGB_LIGHT_GRAY = 0xFFAAAAAA;
	private static final int MIN_VERTICAL_SIZE = 5;
	private static final int RAILWAY_INTERCHANGE_COLOR = 0x21679F;
	private static final String ARROW_RESOURCE = "textures/block/sign/arrow.png";
	private static final String CIRCLE_RESOURCE = "textures/block/sign/circle.png";
	private static final String RAILWAY_INTERCHANGE_RESOURCE = "textures/block/sign/railway_interchange.png";
	private static final String AIRPORT_INTERCHANGE_RESOURCE = "textures/block/sign/airplane.png";
	private static final String CIRCULAR_CLOCKWISE = "\u0001mtr-clockwise\u0001";
	private static final String CIRCULAR_ANTICLOCKWISE = "\u0001mtr-anticlockwise\u0001";

	public RouteAssetImage render(RouteAssetKey key, RouteAssetRenderSnapshot snapshot, RouteAssetTextRasterizer text, RouteAssetSourceImages sources) {
		return render(key, snapshot, text, sources, key.getVariant().getResolution());
	}

	public RouteAssetImage render(RouteAssetKey key, RouteAssetRenderSnapshot snapshot, RouteAssetTextRasterizer text, RouteAssetSourceImages sources, int resolution) {
		if (resolution < 0 || resolution > 8) throw new IllegalArgumentException("Invalid local route texture resolution");
		final Context context = new Context(Objects.requireNonNull(key, "key"), Objects.requireNonNull(snapshot, "snapshot"), Objects.requireNonNull(text, "text"), Objects.requireNonNull(sources, "sources"), resolution);
		switch (key.getType()) {
			case ROUTE_COLOR_STRIP: return generateColorStrip(context);
			case ROUTE_SQUARE: return generateRouteSquare(context);
			case DIRECTION_ARROW: return generateDirectionArrow(context);
			case ROUTE_MAP: return generateRouteMap(context);
			default: throw new IllegalArgumentException("Unsupported route asset type: " + key.getType());
		}
	}

	private static RouteAssetImage generateColorStrip(Context context) {
		final List<Integer> colors = context.snapshot.getColorStripColors();
		if (colors.isEmpty()) return new RouteAssetImage(1, 1);
		final RouteAssetImage image = new RouteAssetImage(1, colors.size());
		for (int index = 0; index < colors.size(); index++) drawPixelSafe(image, 0, index, ARGB_BLACK | colors.get(index));
		return image;
	}

	private static RouteAssetImage generateRouteSquare(Context context) {
		final int padding = context.scale / 32;
		final RouteAssetTextRasterizer.RasterizedText text = context.text(context.snapshot.getRouteName(), Integer.MAX_VALUE, (int) ((context.fontSizeBig + context.fontSizeSmall) * RouteAssetTextRasterizer.LINE_HEIGHT_MULTIPLIER), context.fontSizeBig, context.fontSizeSmall, padding, alignment(context.key));
		final int width = text.getWidth() + padding * 2;
		final int height = text.getHeight() + padding * 2;
		if (width <= 0 || height <= 0) throw new IllegalArgumentException("Route square has invalid dimensions");
		final RouteAssetImage image = new RouteAssetImage(width, height);
		image.fillRect(0, 0, width, height, RouteAssetImage.argbToAbgr(ARGB_BLACK | context.snapshot.getRouteColor()));
		drawString(image, text, width / 2, height / 2, Alignment.CENTER, VerticalAlignment.CENTER, 0, ARGB_WHITE, false);
		return image;
	}

	private static RouteAssetImage generateDirectionArrow(Context context) {
		final List<String> destinations = new ArrayList<>();
		for (final RouteAssetRenderSnapshot.Route route : context.routes()) {
			if (!route.isTerminating()) {
				final String marker = route.getCircularState() == RouteAssetRenderSnapshot.CircularState.CLOCKWISE ? CIRCULAR_CLOCKWISE : route.getCircularState() == RouteAssetRenderSnapshot.CircularState.ANTICLOCKWISE ? CIRCULAR_ANTICLOCKWISE : "";
				destinations.add(marker + route.getCurrentStation().getDestination());
			}
		}
		if (destinations.isEmpty() && !context.snapshot.getDestination().isEmpty()) destinations.add(context.snapshot.getDestination());
		final List<Integer> colors = context.snapshot.getColorStripColors();
		final boolean terminating = destinations.isEmpty();
		final Alignment requestedAlignment = alignment(context.key);
		final boolean leftToRight = requestedAlignment == Alignment.CENTER ? context.snapshot.hasLeft() || !context.snapshot.hasRight() : requestedAlignment != Alignment.RIGHT;
		final int height = context.scale;
		final int width = Math.round(height * context.snapshot.getAspectRatio());
		final int padding = Math.round(height * context.snapshot.getPaddingScale());
		final int tileSize = height - padding * 2;
		if (width <= 0 || height <= 0 || tileSize <= 0) throw new IllegalArgumentException("Direction arrow has invalid dimensions");
		final RouteAssetImage image = new RouteAssetImage(width, height);
		image.fillRect(0, 0, width, height, RouteAssetImage.argbToAbgr(context.snapshot.getBackgroundColor()));

		final int circleX;
		if (terminating) {
			circleX = (int) requestedAlignment.offset(0, tileSize - width);
		} else {
			String destinationString = RouteAssetText.mergeStations(destinations);
			final boolean clockwise = destinationString.startsWith(CIRCULAR_CLOCKWISE);
			final boolean anticlockwise = destinationString.startsWith(CIRCULAR_ANTICLOCKWISE);
			destinationString = destinationString.replace(CIRCULAR_CLOCKWISE, "").replace(CIRCULAR_ANTICLOCKWISE, "");
			if (!destinationString.isEmpty()) {
				if (clockwise) destinationString = RouteAssetText.insertTranslation(TranslationProvider.GUI_MTR_CLOCKWISE_VIA_CJK, TranslationProvider.GUI_MTR_CLOCKWISE_VIA, destinationString);
				else if (anticlockwise) destinationString = RouteAssetText.insertTranslation(TranslationProvider.GUI_MTR_ANTICLOCKWISE_VIA_CJK, TranslationProvider.GUI_MTR_ANTICLOCKWISE_VIA, destinationString);
				else if (context.snapshot.isShowToString()) destinationString = RouteAssetText.insertTranslation(TranslationProvider.GUI_MTR_TO_CJK, TranslationProvider.GUI_MTR_TO, destinationString);
			}
			final int tilePadding = tileSize / 4;
			final int leftSize = ((context.snapshot.hasLeft() ? 1 : 0) + (leftToRight ? 1 : 0)) * (tileSize + tilePadding);
			final int rightSize = ((context.snapshot.hasRight() ? 1 : 0) + (leftToRight ? 0 : 1)) * (tileSize + tilePadding);
			final RouteAssetTextRasterizer.RasterizedText destinationText = context.text(destinationString, width - leftSize - rightSize - padding * (context.snapshot.isShowToString() ? 2 : 1), (int) (tileSize * RouteAssetTextRasterizer.LINE_HEIGHT_MULTIPLIER), tileSize * 3 / 5, tileSize * 3 / 10, tilePadding, leftToRight ? Alignment.LEFT : Alignment.RIGHT);
			final int leftPadding = (int) requestedAlignment.offset(0, leftSize + rightSize + destinationText.getWidth() - tilePadding * 2 - width);
			drawString(image, destinationText, leftPadding + leftSize - tilePadding, height / 2, Alignment.LEFT, VerticalAlignment.CENTER, context.snapshot.getBackgroundColor(), context.snapshot.getTextColor(), false);
			if (context.snapshot.hasLeft()) drawResource(context, image, ARROW_RESOURCE, leftPadding, padding, tileSize, tileSize, false, 0, 1, context.snapshot.getTextColor());
			if (context.snapshot.hasRight()) drawResource(context, image, ARROW_RESOURCE, leftPadding + leftSize + destinationText.getWidth() - tilePadding * 2 + rightSize - tileSize, padding, tileSize, tileSize, true, 0, 1, context.snapshot.getTextColor());
			circleX = leftPadding + leftSize + (leftToRight ? -tileSize - tilePadding : destinationText.getWidth() - tilePadding);
		}
		for (int index = 0; index < colors.size(); index++) drawResource(context, image, CIRCLE_RESOURCE, circleX, padding, tileSize, tileSize, false, (float) index / colors.size(), (index + 1F) / colors.size(), colors.get(index));
		if (!context.snapshot.getPlatformDisplayName().isEmpty()) {
			final RouteAssetTextRasterizer.RasterizedText platformText = context.text(context.snapshot.getPlatformDisplayName(), tileSize, (int) (tileSize * RouteAssetTextRasterizer.LINE_HEIGHT_MULTIPLIER * 3 / 4), tileSize * 3 / 4, tileSize * 3 / 4, 0, Alignment.CENTER);
			drawString(image, platformText, circleX + tileSize / 2, padding + tileSize / 2, Alignment.CENTER, VerticalAlignment.CENTER, 0, ARGB_WHITE, false);
		}
		if (context.snapshot.getTransparentColor() != 0) clearColor(image, RouteAssetImage.argbToAbgr(context.snapshot.getTransparentColor()));
		return image;
	}

	private static RouteAssetImage generateRouteMap(Context context) {
		final List<RouteAssetRenderSnapshot.Route> routeDetails = new ArrayList<>();
		for (final RouteAssetRenderSnapshot.Route route : context.routes()) if (!route.isTerminating()) routeDetails.add(route);
		if (routeDetails.isEmpty()) return singlePixelMap(context.snapshot.getTransparentColor() == ARGB_WHITE);
		final DenseRouteMapLayout.Layout denseLayout = buildDenseRouteMapLayout(routeDetails);
		if (DenseRouteMapLayout.shouldUseDenseLayout(context.snapshot.isVertical(), classifyDensePlatform(context.routes()), denseLayout)) return generateDenseVerticalRouteMap(context, denseLayout);

		final int routeCount = routeDetails.size();
		final List<List<Long>> stationIdsBefore = new ArrayList<>();
		final List<List<Long>> stationIdsAfter = new ArrayList<>();
		final List<NavigableMap<Integer, StationPosition>> stationPositions = new ArrayList<>();
		final int[] colorIndices = new int[routeCount];
		int colorIndex = -1;
		int previousColor = -1;
		for (int routeIndex = 0; routeIndex < routeCount; routeIndex++) {
			stationIdsBefore.add(new ArrayList<>());
			stationIdsAfter.add(new ArrayList<>());
			stationPositions.add(new TreeMap<>());
			final RouteAssetRenderSnapshot.Route route = routeDetails.get(routeIndex);
			for (int stationIndex = 0; stationIndex < route.getStations().size(); stationIndex++) if (stationIndex != route.getCurrentStationIndex()) {
				final long stationId = route.getStations().get(stationIndex).getStationId();
				if (stationIndex < route.getCurrentStationIndex()) stationIdsBefore.get(routeIndex).add(0, stationId); else stationIdsAfter.get(routeIndex).add(stationId);
			}
			if (route.getColor() != previousColor) { colorIndex++; previousColor = route.getColor(); }
			colorIndices[routeIndex] = colorIndex;
		}
		for (int routeIndex = 0; routeIndex < routeCount; routeIndex++) stationPositions.get(routeIndex).put(0, new StationPosition(0, getLineOffset(context, routeIndex, colorIndices), true));
		final float[] bounds = new float[3];
		setup(context, stationPositions, context.snapshot.isFlip() ? stationIdsBefore : stationIdsAfter, colorIndices, bounds, context.snapshot.isFlip(), true);
		final float xOffset = bounds[0] + 0.5F;
		setup(context, stationPositions, context.snapshot.isFlip() ? stationIdsAfter : stationIdsBefore, colorIndices, bounds, !context.snapshot.isFlip(), false);
		final float rawHeightPart = Math.abs(bounds[1]) + (context.snapshot.isVertical() ? 0.6F : 1);
		final float rawWidth = xOffset + bounds[0] + 0.5F;
		final float rawHeightTotal = rawHeightPart + bounds[2] + (context.snapshot.isVertical() ? 0.6F : 1);
		final float rawHeight;
		final float yOffset;
		final float extraPadding;
		if (context.snapshot.isVertical() && rawHeightTotal < MIN_VERTICAL_SIZE) { rawHeight = MIN_VERTICAL_SIZE; extraPadding = (MIN_VERTICAL_SIZE - rawHeightTotal) / 2; yOffset = rawHeightPart + extraPadding; }
		else { rawHeight = rawHeightTotal; extraPadding = 0; yOffset = rawHeightPart; }
		final int height;
		final int width;
		final float widthScale;
		final float heightScale;
		if (rawWidth / rawHeight > context.snapshot.getAspectRatio()) { width = Math.round(rawWidth * context.scale); height = Math.round(width / context.snapshot.getAspectRatio()); widthScale = 1; heightScale = (float) height / rawHeight / context.scale; }
		else { height = Math.round(rawHeight * context.scale); width = Math.round(height * context.snapshot.getAspectRatio()); heightScale = 1; widthScale = (float) width / rawWidth / context.scale; }
		if (width <= 0 || height <= 0) throw new IllegalArgumentException("Route map has invalid dimensions");
		final RouteAssetImage image = new RouteAssetImage(width, height);
		image.fillRect(0, 0, width, height, RouteAssetImage.argbToAbgr(ARGB_WHITE));

		final Map<String, List<StationPositionGrouped>> grouped = new LinkedHashMap<>();
		for (int routeIndex = 0; routeIndex < routeCount; routeIndex++) {
			final RouteAssetRenderSnapshot.Route route = routeDetails.get(routeIndex);
			final NavigableMap<Integer, StationPosition> routePositions = stationPositions.get(routeIndex);
			for (int stationIndex = 0; stationIndex < route.getStations().size(); stationIndex++) {
				final StationPosition position = routePositions.get(stationIndex - route.getCurrentStationIndex());
				if (stationIndex < route.getStations().size() - 1) drawLine(context, image, position, routePositions.get(stationIndex + 1 - route.getCurrentStationIndex()), widthScale, heightScale, xOffset, yOffset, stationIndex < route.getCurrentStationIndex() ? ARGB_LIGHT_GRAY : ARGB_BLACK | route.getColor());
				final RouteAssetRenderSnapshot.Station station = route.getStations().get(stationIndex);
				final String stationKey = station.getName() + "||" + station.getStationId();
				final List<StationPositionGrouped> values = grouped.computeIfAbsent(stationKey, ignored -> new ArrayList<>());
				boolean duplicate = false;
				if (position.isCommon) for (final StationPositionGrouped value : values) if (value.position.x == position.x) { duplicate = true; break; }
				if (!duplicate) values.add(new StationPositionGrouped(position, stationIndex - route.getCurrentStationIndex(), station.getInterchange()));
			}
		}

		final int maxStringWidth = (int) (context.scale * 0.9 * ((context.snapshot.isVertical() ? heightScale : widthScale) / 2 + extraPadding / routeCount));
		for (final Map.Entry<String, List<StationPositionGrouped>> group : grouped.entrySet()) for (final StationPositionGrouped station : group.getValue()) {
			final int x = Math.round((station.position.x + xOffset) * context.scale * widthScale);
			final int y = Math.round((station.position.y + yOffset) * context.scale * heightScale);
			final int lines = station.position.isCommon ? colorIndices[colorIndices.length - 1] : 0;
			final boolean textBelow = context.snapshot.isVertical() || (station.position.isCommon ? Math.abs(station.stationOffset) % 2 == 0 : y >= yOffset * context.scale);
			final boolean currentStation = station.stationOffset == 0;
			final boolean passed = station.stationOffset < 0;
			if (!station.interchange.getColors().isEmpty() && !currentStation) {
				final int lineHeight = context.lineSize * 2;
				final int lineWidth = (int) Math.ceil((float) context.lineSize / station.interchange.getColors().size());
				for (int index = 0; index < station.interchange.getColors().size(); index++) for (int drawX = 0; drawX < lineWidth; drawX++) for (int drawY = 0; drawY < lineHeight; drawY++) drawPixelSafe(image, x + drawX + lineWidth * index - lineWidth * station.interchange.getColors().size() / 2, y + (textBelow ? -1 : lines * context.lineSpacing) + (textBelow ? -drawY : drawY), passed ? ARGB_LIGHT_GRAY : ARGB_BLACK | station.interchange.getColors().get(index));
				final RouteAssetTextRasterizer.RasterizedText interchangeText = context.text(RouteAssetText.mergeStations(station.interchange.getNames()), maxStringWidth - (context.snapshot.isVertical() ? lineHeight : 0), (int) ((context.fontSizeBig + context.fontSizeSmall) * RouteAssetTextRasterizer.LINE_HEIGHT_MULTIPLIER / 2), context.fontSizeBig / 2, context.fontSizeSmall / 2, 0, context.snapshot.isVertical() ? Alignment.LEFT : Alignment.CENTER);
				drawString(image, interchangeText, x, y + (textBelow ? -1 - lineHeight : lines * context.lineSpacing + lineHeight), Alignment.CENTER, textBelow ? VerticalAlignment.BOTTOM : VerticalAlignment.TOP, 0, passed ? ARGB_LIGHT_GRAY : ARGB_BLACK, context.snapshot.isVertical());
			}
			drawStation(context, image, x, y, heightScale, lines, passed);
			final boolean railway = station.interchange.hasRailway() && !currentStation;
			final boolean airport = station.interchange.hasAirport() && !currentStation;
			final int iconCount = (railway ? 1 : 0) + (airport ? 1 : 0);
			final int iconSize = context.lineSize * 3 / 2;
			final int iconGap = Math.max(1, context.lineSize / 2);
			final int stationNameY = y + (textBelow ? lines * context.lineSpacing : -1) + (textBelow ? 1 : -1) * context.lineSize * 5 / 4;
			final int stationNameMaxWidth = Math.max(1, maxStringWidth - iconCount * (iconSize + iconGap));
			final String stationName = group.getKey().split("\\|\\|", -1)[0];
			final RouteAssetTextRasterizer.RasterizedText stationText = context.text(stationName, stationNameMaxWidth, (int) ((context.fontSizeBig + context.fontSizeSmall) * RouteAssetTextRasterizer.LINE_HEIGHT_MULTIPLIER), context.fontSizeBig, context.fontSizeSmall, context.fontSizeSmall / 4, context.snapshot.isVertical() ? Alignment.RIGHT : Alignment.CENTER);
			int stationNameX = x;
			int adjustedY = stationNameY;
			if (iconCount > 0) {
				final RouteMapStationNameLayout.Layout layout = context.snapshot.isVertical() ? RouteMapStationNameLayout.getVertical(image.getWidth(), image.getHeight(), x, stationNameY, stationText.getWidth(), stationText.getHeight(), iconCount, iconSize, iconGap) : RouteMapStationNameLayout.getHorizontal(image.getWidth(), image.getHeight(), x, stationNameY, stationText.getWidth(), stationText.getHeight(), iconCount, iconSize, iconGap, textBelow);
				stationNameX = layout.getTextX(); adjustedY = layout.getTextY(); int iconIndex = 0;
				if (railway) drawResource(context, image, RAILWAY_INTERCHANGE_RESOURCE, layout.getIconX(iconIndex++), layout.getIconY(iconIndex - 1), iconSize, iconSize, false, 0, 1, passed ? ARGB_LIGHT_GRAY : RAILWAY_INTERCHANGE_COLOR, context.snapshot.isVertical());
				if (airport) drawResource(context, image, AIRPORT_INTERCHANGE_RESOURCE, layout.getIconX(iconIndex), layout.getIconY(iconIndex), iconSize, iconSize, false, 0, 1, passed ? ARGB_LIGHT_GRAY : RAILWAY_INTERCHANGE_COLOR, context.snapshot.isVertical());
			}
			drawString(image, stationText, stationNameX, adjustedY, Alignment.CENTER, textBelow ? VerticalAlignment.TOP : VerticalAlignment.BOTTOM, currentStation ? ARGB_BLACK : 0, passed ? ARGB_LIGHT_GRAY : currentStation ? ARGB_WHITE : ARGB_BLACK, context.snapshot.isVertical());
		}
		if (context.snapshot.getTransparentColor() == ARGB_WHITE) clearColor(image, RouteAssetImage.argbToAbgr(ARGB_WHITE));
		return image;
	}

	private static RouteAssetImage singlePixelMap(boolean transparent) {
		final RouteAssetImage image = new RouteAssetImage(1, 1);
		image.setPixel(0, 0, transparent ? 0 : RouteAssetImage.argbToAbgr(ARGB_WHITE));
		return image;
	}

	private static DenseRouteMapLayout.Layout buildDenseRouteMapLayout(List<RouteAssetRenderSnapshot.Route> routes) {
		final List<DenseRouteMapLayout.RouteInput> inputs = new ArrayList<>();
		for (final RouteAssetRenderSnapshot.Route route : routes) {
			final List<DenseRouteMapLayout.StationInput> stations = new ArrayList<>();
			for (int index = route.getCurrentStationIndex(); index < route.getStations().size(); index++) stations.add(new DenseRouteMapLayout.StationInput(route.getStations().get(index).getStationId(), route.getStations().get(index).getName()));
			inputs.add(new DenseRouteMapLayout.RouteInput(route.getName().split("\\|\\|", -1)[0], route.getColor(), stations));
		}
		return DenseRouteMapLayout.build(inputs);
	}

	private static DenseRouteMapLayout.PlatformType classifyDensePlatform(List<RouteAssetRenderSnapshot.Route> routes) {
		final List<DenseRouteMapLayout.RouteType> types = new ArrayList<>();
		for (final RouteAssetRenderSnapshot.Route route : routes) types.add(route.getRouteKind() == RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED ? DenseRouteMapLayout.RouteType.HIGH_SPEED : route.getRouteKind() == RouteAssetRenderSnapshot.RouteKind.METRO ? DenseRouteMapLayout.RouteType.METRO : DenseRouteMapLayout.RouteType.UNRESOLVED);
		return DenseRouteMapLayout.classifyPlatform(types);
	}

	private static RouteAssetImage generateDenseVerticalRouteMap(Context context, DenseRouteMapLayout.Layout layout) {
		final int physicalWidth = context.scale * MIN_VERTICAL_SIZE;
		final int physicalHeight = Math.max(1, Math.round(physicalWidth * context.snapshot.getAspectRatio()));
		final RouteAssetImage image = new RouteAssetImage(physicalHeight, physicalWidth);
		image.fillRect(0, 0, image.getWidth(), image.getHeight(), RouteAssetImage.argbToAbgr(ARGB_WHITE));
		final int margin = denseScale(10, physicalWidth);
		final int gap = denseScale(5, physicalWidth);
		final int labelHeight = denseScale(16, physicalWidth);
		final int currentHeight = denseScale(48, physicalWidth);
		final int routeCount = layout.getRoutes().size();
		final int chipRows = Math.max(1, (routeCount + 3) / 4);
		final int serviceBandHeight = denseScale(chipRows == 1 ? 36 : 61, physicalWidth);
		final boolean hasPrefixes = layout.getRoutes().stream().anyMatch(route -> !route.getPrefixStations().isEmpty());
		final boolean hasCommonStations = !layout.getCommonStations().isEmpty();
		final int prefixHeight = hasPrefixes ? denseScale(Math.max(78, routeCount * 28), physicalWidth) : 0;
		final int commonHeight = hasCommonStations ? denseScale(Math.max(68, layout.getCommonStations().size() * 38), physicalWidth) : 0;
		final int labels = (hasPrefixes ? 1 : 0) + (hasCommonStations ? 1 : 0) + 1;
		final int tailHeight = Math.max(denseScale(routeCount * 42, physicalWidth), physicalHeight - serviceBandHeight - currentHeight - prefixHeight - commonHeight - labelHeight * labels);
		int y = 0;
		drawDenseServiceBand(context, image, physicalWidth, layout.getRoutes(), y, serviceBandHeight, margin, gap); y += serviceBandHeight;
		drawPhysicalRect(image, physicalWidth, 0, y, physicalWidth, currentHeight, ARGB_WHITE);
		drawPhysicalStationCircle(image, physicalWidth, margin + denseScale(7, physicalWidth), y + currentHeight / 2, denseScale(7, physicalWidth), ARGB_BLACK);
		drawPhysicalString(context, image, physicalWidth, layout.getCurrentStation().getName(), margin + denseScale(25, physicalWidth), y + denseScale(4, physicalWidth), physicalWidth - margin * 2 - denseScale(25, physicalWidth), currentHeight - denseScale(8, physicalWidth), denseScale(14, physicalWidth), denseScale(8, physicalWidth), ARGB_BLACK, false); y += currentHeight;
		if (hasPrefixes) { drawDenseSectionLabel(context, image, physicalWidth, "汇合前|Before convergence", y, labelHeight); y += labelHeight; drawDenseRouteRows(context, image, physicalWidth, layout.getRoutes(), true, y, prefixHeight, margin, gap); y += prefixHeight; }
		if (hasCommonStations) { drawDenseSectionLabel(context, image, physicalWidth, "共同经过|All services", y, labelHeight); y += labelHeight; drawDenseCommonStations(context, image, physicalWidth, layout.getCommonStations(), layout.getRoutes(), y, commonHeight, margin); y += commonHeight; }
		drawDenseSectionLabel(context, image, physicalWidth, "其后各线|Route-specific stops", y, labelHeight); y += labelHeight;
		drawDenseRouteRows(context, image, physicalWidth, layout.getRoutes(), false, y, Math.min(tailHeight, physicalHeight - y), margin, gap);
		if (context.snapshot.getTransparentColor() == ARGB_WHITE) clearColor(image, RouteAssetImage.argbToAbgr(ARGB_WHITE));
		return image;
	}

	private static void drawDenseServiceBand(Context context, RouteAssetImage image, int physicalWidth, List<DenseRouteMapLayout.RouteSummary> routes, int y, int height, int margin, int gap) {
		drawPhysicalRect(image, physicalWidth, 0, y, physicalWidth, height, 0xFFE7EAEC);
		final int columns = Math.min(4, routes.size()); final int rows = Math.max(1, (routes.size() + columns - 1) / columns); final int chipHeight = (height - margin - gap * (rows - 1)) / rows; final int chipWidth = (physicalWidth - margin * 2 - gap * (columns - 1)) / columns;
		for (int index = 0; index < routes.size(); index++) { final int row = index / columns; final int column = index % columns; final int x = margin + column * (chipWidth + gap); final int chipY = y + margin / 2 + row * (chipHeight + gap); final DenseRouteMapLayout.RouteSummary route = routes.get(index); drawPhysicalRect(image, physicalWidth, x, chipY, chipWidth, chipHeight, ARGB_BLACK | route.getColor()); drawPhysicalString(context, image, physicalWidth, route.getName(), x + denseScale(3, physicalWidth), chipY, chipWidth - denseScale(6, physicalWidth), chipHeight, denseScale(9, physicalWidth), denseScale(7, physicalWidth), ARGB_WHITE, true); }
	}

	private static void drawDenseSectionLabel(Context context, RouteAssetImage image, int physicalWidth, String value, int y, int height) {
		drawPhysicalRect(image, physicalWidth, 0, y, physicalWidth, height, 0xFFC4C9CC);
		drawPhysicalString(context, image, physicalWidth, value, denseScale(10, physicalWidth), y, physicalWidth - denseScale(20, physicalWidth), height, denseScale(8, physicalWidth), denseScale(6, physicalWidth), 0xFF596269, false);
	}

	private static void drawDenseRouteRows(Context context, RouteAssetImage image, int physicalWidth, List<DenseRouteMapLayout.RouteSummary> routes, boolean prefixes, int y, int height, int margin, int gap) {
		if (routes.isEmpty() || height <= 0) return;
		final int rowHeight = Math.max(1, (height - gap * (routes.size() + 1)) / routes.size());
		for (int index = 0; index < routes.size(); index++) {
			final DenseRouteMapLayout.RouteSummary route = routes.get(index); final List<DenseRouteMapLayout.StationInput> stations = prefixes ? route.getPrefixStations() : route.getSuffixStations(); final int rowY = y + gap + index * (rowHeight + gap);
			drawPhysicalRect(image, physicalWidth, margin, rowY, physicalWidth - margin * 2, rowHeight, 0xFFF6F7F7); final int badgeWidth = denseScale(38, physicalWidth); drawPhysicalRect(image, physicalWidth, margin + denseScale(5, physicalWidth), rowY + denseScale(5, physicalWidth), badgeWidth, rowHeight - denseScale(10, physicalWidth), ARGB_BLACK | route.getColor()); drawPhysicalString(context, image, physicalWidth, route.getName(), margin + denseScale(7, physicalWidth), rowY + denseScale(5, physicalWidth), badgeWidth - denseScale(4, physicalWidth), rowHeight - denseScale(10, physicalWidth), denseScale(8, physicalWidth), denseScale(6, physicalWidth), ARGB_WHITE, true);
			final int ruleX = margin + denseScale(49, physicalWidth); drawPhysicalRect(image, physicalWidth, ruleX, rowY + denseScale(5, physicalWidth), denseScale(4, physicalWidth), rowHeight - denseScale(10, physicalWidth), ARGB_BLACK | route.getColor()); final DenseStationInfo info = getDenseStationInfo(context, stations); final int iconSize = denseScale(13, physicalWidth); final int iconGap = denseScale(3, physicalWidth); final int iconCount = (info.railway ? 1 : 0) + (info.airport ? 1 : 0); int textX = ruleX + denseScale(9, physicalWidth); int iconIndex = 0;
			if (info.railway) drawPhysicalResource(context, image, physicalWidth, RAILWAY_INTERCHANGE_RESOURCE, textX + iconIndex++ * (iconSize + iconGap), rowY + (rowHeight - iconSize) / 2, iconSize, iconSize, RAILWAY_INTERCHANGE_COLOR);
			if (info.airport) drawPhysicalResource(context, image, physicalWidth, AIRPORT_INTERCHANGE_RESOURCE, textX + iconIndex * (iconSize + iconGap), rowY + (rowHeight - iconSize) / 2, iconSize, iconSize, RAILWAY_INTERCHANGE_COLOR);
			if (iconCount > 0) textX += iconCount * (iconSize + iconGap);
			drawPhysicalString(context, image, physicalWidth, stations.isEmpty() ? "—" : joinDenseStationNames(stations), textX, rowY + denseScale(3, physicalWidth), physicalWidth - margin - textX - denseScale(5, physicalWidth), rowHeight - denseScale(6, physicalWidth), denseScale(9, physicalWidth), denseScale(6, physicalWidth), stations.isEmpty() ? 0xFF8B9195 : ARGB_BLACK, false);
		}
	}

	private static void drawDenseCommonStations(Context context, RouteAssetImage image, int physicalWidth, List<DenseRouteMapLayout.StationInput> stations, List<DenseRouteMapLayout.RouteSummary> routes, int y, int height, int margin) {
		drawPhysicalRect(image, physicalWidth, 0, y, physicalWidth, height, 0xFFD7D9DA); final int rowHeight = Math.max(1, height / stations.size()); final int lineX = margin + denseScale(7, physicalWidth); final int stripeWidth = Math.max(1, denseScale(6, physicalWidth) / routes.size());
		for (int routeIndex = 0; routeIndex < routes.size(); routeIndex++) drawPhysicalRect(image, physicalWidth, lineX - stripeWidth * routes.size() / 2 + routeIndex * stripeWidth, y, stripeWidth, height, ARGB_BLACK | routes.get(routeIndex).getColor());
		for (int index = 0; index < stations.size(); index++) {
			final DenseRouteMapLayout.StationInput station = stations.get(index); final int rowY = y + index * rowHeight; final int centerY = rowY + rowHeight / 2; drawPhysicalStationCircle(image, physicalWidth, lineX, centerY, denseScale(7, physicalWidth), ARGB_BLACK); final DenseStationInfo info = getDenseStationInfo(context, List.of(station)); final int iconSize = denseScale(14, physicalWidth); final int iconGap = denseScale(3, physicalWidth); int nameX = margin + denseScale(24, physicalWidth);
			if (info.railway) { drawPhysicalResource(context, image, physicalWidth, RAILWAY_INTERCHANGE_RESOURCE, nameX, centerY - iconSize / 2, iconSize, iconSize, RAILWAY_INTERCHANGE_COLOR); nameX += iconSize + iconGap; }
			if (info.airport) { drawPhysicalResource(context, image, physicalWidth, AIRPORT_INTERCHANGE_RESOURCE, nameX, centerY - iconSize / 2, iconSize, iconSize, RAILWAY_INTERCHANGE_COLOR); nameX += iconSize + iconGap; }
			final int interchangeWidth = info.normalText.isEmpty() ? 0 : denseScale(62, physicalWidth); drawPhysicalString(context, image, physicalWidth, station.getName(), nameX, rowY + denseScale(3, physicalWidth), physicalWidth - margin - nameX - interchangeWidth, rowHeight - denseScale(6, physicalWidth), denseScale(12, physicalWidth), denseScale(7, physicalWidth), ARGB_BLACK, false); if (!info.normalText.isEmpty()) drawPhysicalString(context, image, physicalWidth, info.normalText, physicalWidth - margin - interchangeWidth, rowY + denseScale(3, physicalWidth), interchangeWidth, rowHeight - denseScale(6, physicalWidth), denseScale(7, physicalWidth), denseScale(5, physicalWidth), 0xFF596269, false);
		}
	}

	private static DenseStationInfo getDenseStationInfo(Context context, List<DenseRouteMapLayout.StationInput> stations) {
		boolean railway = false; boolean airport = false; final List<String> names = new ArrayList<>();
		for (final DenseRouteMapLayout.StationInput station : stations) { final RouteAssetRenderSnapshot.Station snapshot = context.snapshot.findStation(station.getId()).orElse(null); if (snapshot != null) { railway |= snapshot.getInterchange().hasRailway(); airport |= snapshot.getInterchange().hasAirport(); names.addAll(snapshot.getInterchange().getNames()); } }
		return new DenseStationInfo(railway, airport, RouteAssetText.mergeStations(names));
	}

	private static String joinDenseStationNames(List<DenseRouteMapLayout.StationInput> stations) {
		final StringBuilder primary = new StringBuilder(); final StringBuilder secondary = new StringBuilder();
		for (final DenseRouteMapLayout.StationInput station : stations) { final String[] parts = station.getName().split("\\|", -1); if (primary.length() > 0) { primary.append(" · "); secondary.append(" · "); } primary.append(parts[0]); secondary.append(parts.length > 1 ? parts[1] : parts[0]); }
		return primary + "|" + secondary;
	}

	private static void drawPhysicalString(Context context, RouteAssetImage image, int physicalWidth, String value, int x, int y, int width, int height, int cjkSize, int latinSize, int color, boolean centered) {
		if (width <= 0 || height <= 0 || value.isEmpty()) return; final RouteAssetTextRasterizer.RasterizedText text = context.text(value, width, height, cjkSize, latinSize, 0, centered ? Alignment.CENTER : Alignment.LEFT); final int left = x + (centered ? Math.max(0, (width - text.getWidth()) / 2) : 0); final int top = y + Math.max(0, (height - text.getHeight()) / 2); final byte[] pixels = text.pixels();
		for (int sourceY = 0; sourceY < text.getHeight(); sourceY++) for (int sourceX = 0; sourceX < text.getWidth(); sourceX++) blendPhysicalPixel(image, physicalWidth, left + sourceX, top + sourceY, ((pixels[sourceY * text.getWidth() + sourceX] & 0xFF) << 24) | (color & RGB_WHITE));
	}

	private static void drawPhysicalRect(RouteAssetImage image, int physicalWidth, int x, int y, int width, int height, int color) { for (int drawX = 0; drawX < width; drawX++) for (int drawY = 0; drawY < height; drawY++) drawPhysicalPixel(image, physicalWidth, x + drawX, y + drawY, color); }
	private static void drawPhysicalStationCircle(RouteAssetImage image, int physicalWidth, int centerX, int centerY, int radius, int outlineColor) { final int inner = Math.max(0, radius - denseScale(3, physicalWidth)); for (int x = -radius; x <= radius; x++) for (int y = -radius; y <= radius; y++) { final int distance = x * x + y * y; if (distance <= radius * radius) drawPhysicalPixel(image, physicalWidth, centerX + x, centerY + y, distance <= inner * inner ? ARGB_WHITE : outlineColor); } }
	private static void drawPhysicalResource(Context context, RouteAssetImage image, int physicalWidth, String resource, int x, int y, int width, int height, int color) { final RouteAssetImage source = context.source(resource); for (int drawX = 0; drawX < width; drawX++) for (int drawY = 0; drawY < height; drawY++) { final int sourceX = clamp(drawX * source.getWidth() / width, 0, source.getWidth() - 1); final int sourceY = clamp(drawY * source.getHeight() / height, 0, source.getHeight() - 1); blendPhysicalPixel(image, physicalWidth, x + drawX, y + drawY, (source.getPixel(sourceX, sourceY) >>> 24) << 24 | (color & RGB_WHITE)); } }
	private static void drawPhysicalPixel(RouteAssetImage image, int physicalWidth, int x, int y, int color) { drawPixelSafe(image, y, physicalWidth - x - 1, color); }
	private static void blendPhysicalPixel(RouteAssetImage image, int physicalWidth, int x, int y, int color) { blendPixel(image, y, physicalWidth - x - 1, color); }
	private static int denseScale(int value, int physicalWidth) { return Math.max(1, Math.round((float) value * physicalWidth / 320)); }

	private static void setup(Context context, List<NavigableMap<Integer, StationPosition>> stationPositions, List<List<Long>> stationIdLists, int[] colorIndices, float[] bounds, boolean passed, boolean reverse) {
		final int passedMultiplier = passed ? -1 : 1; final int reverseMultiplier = reverse ? -1 : 1; bounds[0] = 0; final List<Long> commonStationIds = new ArrayList<>();
		for (final long stationId : stationIdLists.get(0)) if (stationId != 0 && !commonStationIds.contains(stationId) && stationIdLists.stream().allMatch(ids -> ids.contains(stationId))) commonStationIds.add(stationId);
		int positionXOffset = 0; final int routeCount = stationIdLists.size(); final int[] traverseIndex = new int[routeCount];
		for (int commonIndex = 0; commonIndex <= commonStationIds.size(); commonIndex++) {
			final boolean last = commonIndex == commonStationIds.size(); final long commonId = last ? -1 : commonStationIds.get(commonIndex); int maxSegments = 0; final int[] segments = new int[routeCount];
			for (int routeIndex = 0; routeIndex < routeCount; routeIndex++) { segments[routeIndex] = (last ? stationIdLists.get(routeIndex).size() : stationIdLists.get(routeIndex).indexOf(commonId) + 1) - traverseIndex[routeIndex]; maxSegments = Math.max(maxSegments, segments[routeIndex]); }
			final List<Integer> routesInSection = new ArrayList<>(); for (int routeIndex = 0; routeIndex < routeCount; routeIndex++) if (!last || segments[routeIndex] > 0) routesInSection.add(routeIndex);
			for (int routeIndex = 0; routeIndex < routeCount; routeIndex++) if (segments[routeIndex] > 0) { final float increment = (float) maxSegments / segments[routeIndex]; for (int index = 0; index < segments[routeIndex] - (last ? 0 : 1); index++) { final float stationX = positionXOffset + increment * (index + 1); bounds[0] = Math.max(bounds[0], stationX / 2); final float stationY = routesInSection.indexOf(routeIndex) - (routesInSection.size() - 1) / 2F + getLineOffset(context, routeIndex, colorIndices); bounds[1] = Math.min(bounds[1], stationY); bounds[2] = Math.max(bounds[2], stationY); stationPositions.get(routeIndex).put(passedMultiplier * (index + traverseIndex[routeIndex] + 1), new StationPosition(reverseMultiplier * stationX / 2, stationY, false)); } traverseIndex[routeIndex] += segments[routeIndex]; }
			if (!last) { positionXOffset += maxSegments; for (int routeIndex = 0; routeIndex < routeCount; routeIndex++) { final float stationY = getLineOffset(context, routeIndex, colorIndices); bounds[1] = Math.min(bounds[1], stationY); bounds[2] = Math.max(bounds[2], stationY); stationPositions.get(routeIndex).put(passedMultiplier * traverseIndex[routeIndex], new StationPosition(reverseMultiplier * positionXOffset / 2F, stationY, true)); } bounds[0] = positionXOffset / 2F; }
		}
	}

	private static float getLineOffset(Context context, int routeIndex, int[] colorIndices) { return (float) context.lineSpacing / context.scale * (colorIndices[routeIndex] - colorIndices[colorIndices.length - 1] / 2F); }

	private static void drawLine(Context context, RouteAssetImage image, StationPosition first, StationPosition second, float widthScale, float heightScale, float xOffset, float yOffset, int color) {
		final int x1 = Math.round((first.x + xOffset) * context.scale * widthScale); final int x2 = Math.round((second.x + xOffset) * context.scale * widthScale); final int y1 = Math.round((first.y + yOffset) * context.scale * heightScale); final int y2 = Math.round((second.y + yOffset) * context.scale * heightScale); final int xChange = x2 - x1; final int yChange = y2 - y1; final int xAbs = Math.abs(xChange); final int yAbs = Math.abs(yChange); final int difference = Math.abs(yAbs - xAbs);
		if (xAbs > yAbs) { final boolean firstGreater = Math.abs(y1 - yOffset * context.scale) > Math.abs(y2 - yOffset * context.scale); drawLine(context, image, x1, y1, xChange, firstGreater ? 0 : yChange, firstGreater ? difference : yAbs, color); drawLine(context, image, x2, y2, -xChange, firstGreater ? -yChange : 0, firstGreater ? yAbs : difference, color); }
		else { final int half = xAbs / 2; drawLine(context, image, x1, y1, xChange, yChange, half, color); drawLine(context, image, x2, y2, -xChange, -yChange, half, color); drawLine(context, image, (x1 + x2) / 2, y1 + (int) Math.copySign(half, yChange), 0, yChange, difference, color); }
	}

	private static void drawLine(Context context, RouteAssetImage image, int x, int y, int directionX, int directionY, int length, int color) {
		final int half = context.lineSize / 2; final int xWidth = directionX == 0 ? half : 0; final int yWidth = directionX == 0 ? 0 : directionY == 0 ? half : Math.round(context.lineSize * (float) Math.sqrt(2) / 2); final int yMin = y - half - (directionY < 0 ? length : 0) + 1; final int yMax = y + half + (directionY > 0 ? length : 0) - 1; final int drawOffset = directionX != 0 && directionY != 0 ? half : 0;
		for (int index = -drawOffset; index < Math.abs(length) + drawOffset; index++) { final int drawX = x + (directionX == 0 ? 0 : (int) Math.copySign(index, directionX)) + (directionX < 0 ? -1 : 0); final int drawY = y + (directionY == 0 ? 0 : (int) Math.copySign(index, directionY)) + (directionY < 0 ? -1 : 0); for (int offset = 0; offset < xWidth; offset++) { drawPixelSafe(image, drawX - offset - 1, drawY, color); drawPixelSafe(image, drawX + offset, drawY, color); } for (int offset = 0; offset < yWidth; offset++) { drawPixelSafe(image, drawX, Math.max(drawY - offset, yMin) - 1, color); drawPixelSafe(image, drawX, Math.min(drawY + offset, yMax), color); } }
	}

	private static void drawStation(Context context, RouteAssetImage image, int x, int y, float heightScale, int lines, boolean passed) { for (int offsetX = -context.lineSize; offsetX < context.lineSize; offsetX++) for (int offsetY = -context.lineSize; offsetY < context.lineSize; offsetY++) { final int extraY = offsetY > 0 ? (int) (lines * context.lineSpacing * heightScale) : 0; final int repeat = offsetY == 0 ? (int) (lines * context.lineSpacing * heightScale) : 0; final double sum = (offsetX + 0.5) * (offsetX + 0.5) + (offsetY + 0.5) * (offsetY + 0.5); if (sum <= 0.5 * context.lineSize * context.lineSize) for (int index = 0; index <= repeat; index++) drawPixelSafe(image, x + offsetX, y + offsetY + extraY + index, ARGB_WHITE); else if (sum <= context.lineSize * context.lineSize) for (int index = 0; index <= repeat; index++) drawPixelSafe(image, x + offsetX, y + offsetY + extraY + index, passed ? ARGB_LIGHT_GRAY : ARGB_BLACK); } }

	static void drawString(RouteAssetImage image, RouteAssetTextRasterizer.RasterizedText text, int x, int y, Alignment horizontal, VerticalAlignment vertical, int backgroundColor, int textColor, boolean rotate90) {
		final int renderedWidth = rotate90 ? text.getHeight() : text.getWidth();
		final int renderedHeight = rotate90 ? text.getWidth() : text.getHeight();
		if ((backgroundColor >>> 24) > 0) for (int drawX = 0; drawX < renderedWidth; drawX++) for (int drawY = 0; drawY < renderedHeight; drawY++) drawPixelSafe(image, (int) horizontal.offset(drawX + x, renderedWidth), (int) vertical.offset(drawY + y, renderedHeight), backgroundColor);
		final byte[] pixels = text.pixels(); int drawX = 0; int drawY = rotate90 ? text.getWidth() - 1 : 0;
		for (final byte pixel : pixels) { blendPixel(image, (int) horizontal.offset(x + drawX, renderedWidth), (int) vertical.offset(y + drawY, renderedHeight), ((pixel & 0xFF) << 24) | (textColor & RGB_WHITE)); if (rotate90) { drawY--; if (drawY < 0) { drawY = text.getWidth() - 1; drawX++; } } else { drawX++; if (drawX == text.getWidth()) { drawX = 0; drawY++; } } }
	}

	private static void drawResource(Context context, RouteAssetImage target, String resource, int x, int y, int width, int height, boolean flipX, float v1, float v2, int color) {
		drawResource(context, target, resource, x, y, width, height, flipX, v1, v2, color, false);
	}

	private static void drawResource(Context context, RouteAssetImage target, String resource, int x, int y, int width, int height, boolean flipX, float v1, float v2, int color, boolean rotate90) {
		final RouteAssetImage source = context.source(resource); final int sourceWidth = source.getWidth(); final int sourceHeight = source.getHeight();
		for (int drawX = 0; drawX < width; drawX++) for (int drawY = Math.round(v1 * height); drawY < Math.round(v2 * height); drawY++) { final float pixelX = rotate90 ? (float) (height - drawY - 1) / height * sourceWidth : (float) drawX / width * sourceWidth; final float pixelY = rotate90 ? (float) drawX / width * sourceHeight : (float) drawY / height * sourceHeight; final int floorX = (int) pixelX; final int floorY = (int) pixelY; final int ceilX = floorX + 1; final int ceilY = floorY + 1; final float px1 = ceilX - pixelX; final float py1 = ceilY - pixelY; final float px2 = pixelX - floorX; final float py2 = pixelY - floorY; final int pixel1 = source.getPixel(clamp(floorX, 0, sourceWidth - 1), clamp(floorY, 0, sourceHeight - 1)); final int pixel2 = source.getPixel(clamp(ceilX, 0, sourceWidth - 1), clamp(floorY, 0, sourceHeight - 1)); final int pixel3 = source.getPixel(clamp(floorX, 0, sourceWidth - 1), clamp(ceilY, 0, sourceHeight - 1)); final int pixel4 = source.getPixel(clamp(ceilX, 0, sourceWidth - 1), clamp(ceilY, 0, sourceHeight - 1)); final int alpha = (int) (((pixel1 >>> 24) * px1 * py1) + ((pixel2 >>> 24) * px2 * py1) + ((pixel3 >>> 24) * px1 * py2) + ((pixel4 >>> 24) * px2 * py2)); blendPixel(target, (flipX ? width - drawX - 1 : drawX) + x, drawY + y, alpha << 24 | (color & RGB_WHITE)); }
	}

	private static void blendPixel(RouteAssetImage image, int x, int y, int argb) {
		if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) return; final float percent = (float) (argb >>> 24) / 255; if (percent <= 0) return; final int existing = image.getPixel(x, y); final boolean transparent = (existing >>> 24) == 0; final int r1 = transparent ? 255 : existing & 0xFF; final int g1 = transparent ? 255 : existing >>> 8 & 0xFF; final int b1 = transparent ? 255 : existing >>> 16 & 0xFF; final int r2 = argb >>> 16 & 0xFF; final int g2 = argb >>> 8 & 0xFF; final int b2 = argb & 0xFF; final float inverse = 1 - percent; drawPixelSafe(image, x, y, ARGB_BLACK | (int) (r1 * inverse + r2 * percent) << 16 | (int) (g1 * inverse + g2 * percent) << 8 | (int) (b1 * inverse + b2 * percent));
	}

	private static void drawPixelSafe(RouteAssetImage image, int x, int y, int argb) { if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight()) image.setPixel(x, y, RouteAssetImage.argbToAbgr(argb)); }
	private static void clearColor(RouteAssetImage image, int abgr) { for (int x = 0; x < image.getWidth(); x++) for (int y = 0; y < image.getHeight(); y++) if (image.getPixel(x, y) == abgr) image.setPixel(x, y, 0); }
	private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(value, max)); }
	private static Alignment alignment(RouteAssetKey key) { try { return Alignment.valueOf(key.getVariant().getParameters().getOrDefault("align", "CENTER")); } catch (IllegalArgumentException exception) { return Alignment.CENTER; } }

	enum Alignment { LEFT, CENTER, RIGHT; float offset(float value, float size) { return this == CENTER ? value - size / 2 : this == RIGHT ? value - size : value; } }
	enum VerticalAlignment { TOP, CENTER, BOTTOM; float offset(float value, float size) { return this == CENTER ? value - size / 2 : this == BOTTOM ? value - size : value; } }

	private static final class Context {
		private final RouteAssetKey key; private final RouteAssetRenderSnapshot snapshot; private final RouteAssetTextRasterizer rasterizer; private final RouteAssetSourceImages sources; private final int scale; private final int lineSize; private final int lineSpacing; private final int fontSizeBig; private final int fontSizeSmall;
		private Context(RouteAssetKey key, RouteAssetRenderSnapshot snapshot, RouteAssetTextRasterizer rasterizer, RouteAssetSourceImages sources, int resolution) { this.key = key; this.snapshot = snapshot; this.rasterizer = rasterizer; this.sources = sources; scale = 1 << resolution + 5; lineSize = scale / 8; lineSpacing = lineSize * 3 / 2; fontSizeBig = lineSize * 2; fontSizeSmall = fontSizeBig / 2; }
		private RouteAssetTextRasterizer.RasterizedText text(String value, int maxWidth, int maxHeight, int cjkSize, int latinSize, int padding, Alignment alignment) { return rasterizer.rasterize(value, maxWidth, maxHeight, cjkSize, latinSize, padding, RouteAssetTextRasterizer.Alignment.valueOf(alignment.name()), key.getVariant().getLanguage()); }
		private RouteAssetImage source(String path) { try { return sources.get(path); } catch (IOException exception) { throw new IllegalStateException("Unable to load route texture source " + path, exception); } }
		private List<RouteAssetRenderSnapshot.Route> routes() {
			if (!snapshot.getRoutes().isEmpty()) return snapshot.getRoutes();
			if (snapshot.getStations().isEmpty()) return Collections.emptyList();
			return List.of(new RouteAssetRenderSnapshot.Route(0, snapshot.getRouteName(), snapshot.getRouteColor(), RouteAssetRenderSnapshot.CircularState.NONE, snapshot.isDense() ? RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED : RouteAssetRenderSnapshot.RouteKind.METRO, legacyCurrent(snapshot.getStations()), snapshot.getStations()));
		}
		private static int legacyCurrent(List<RouteAssetRenderSnapshot.Station> stations) { for (int index = 0; index < stations.size(); index++) if (stations.get(index).isCurrent()) return index; return 0; }
	}

	private static final class StationPosition { private final float x, y; private final boolean isCommon; private StationPosition(float x, float y, boolean isCommon) { this.x = x; this.y = y; this.isCommon = isCommon; } }
	private static final class StationPositionGrouped { private final StationPosition position; private final int stationOffset; private final RouteAssetRenderSnapshot.Interchange interchange; private StationPositionGrouped(StationPosition position, int stationOffset, RouteAssetRenderSnapshot.Interchange interchange) { this.position = position; this.stationOffset = stationOffset; this.interchange = interchange; } }
	private static final class DenseStationInfo { private final boolean railway, airport; private final String normalText; private DenseStationInfo(boolean railway, boolean airport, String normalText) { this.railway = railway; this.airport = airport; this.normalText = normalText; } }
}
