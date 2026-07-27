package org.mtr.mod.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class DestinationSignRouteStripLayout {

	private static final int DENSE_EDGE_RESERVE = 12;
	private static final int ROLE_GAP = 36;

	private DestinationSignRouteStripLayout() {
	}

	public static RowMetrics rowMetrics(int widthBlocks, int rowHeight, boolean showEta) {
		if (widthBlocks < 1 || widthBlocks > 16) throw new IllegalArgumentException("Destination sign width must be 1..16 blocks");
		if (rowHeight < 24) throw new IllegalArgumentException("Destination sign row height must be at least 24 pixels");
		final boolean dense = widthBlocks >= 3;
		final int surfaceWidth = widthBlocks * 120;
		final int outerInset = dense ? 6 : 4;
		final int columnGap = dense ? 4 : 2;
		final int identityWidth = dense ? clamp(40, roundHalfUp(rowHeight * 1.5), 64) : 28;
		final int etaWidth = showEta ? dense ? clamp(40, roundHalfUp(rowHeight * 1.35), 56) : 28 : 0;
		final int identityX = outerInset;
		final int routeStripX = identityX + identityWidth + columnGap;
		final int etaX = showEta ? surfaceWidth - outerInset - etaWidth : surfaceWidth - outerInset;
		final int routeStripRight = etaX - (showEta ? columnGap : 0);
		final int normalDiameter = rowHeight < 30 ? 4 : rowHeight < 40 ? 5 : 6;
		final int currentDiameter = rowHeight < 30 ? 5 : rowHeight < 40 ? 6 : 8;
		final int targetOuterDiameter = rowHeight < 30 ? 6 : rowHeight < 40 ? 8 : 10;
		final int targetInnerDiameter = rowHeight < 30 ? 2 : rowHeight < 40 ? 4 : 6;
		final int rowFontSize = clamp(9, (int) Math.floor(rowHeight * 0.36), 13);
		final int targetFontSize = Math.min(14, Math.min(rowFontSize + 1, (rowHeight - targetOuterDiameter) / 2));
		final int identityTextTop = (rowHeight - (2 * rowFontSize + 2)) / 2;
		return new RowMetrics(dense, widthBlocks, rowHeight, surfaceWidth, outerInset, columnGap, identityX, identityWidth,
				routeStripX, routeStripRight, etaX, etaWidth, rowFontSize, targetFontSize, normalDiameter,
				currentDiameter, targetOuterDiameter, targetInnerDiameter, rowHeight < 30 ? 1 : 2, identityTextTop);
	}

	public static RouteStrip project(DestinationSignDirectServiceModel.Option option, RowMetrics metrics) {
		final DestinationSignDirectServiceModel.Option checkedOption = Objects.requireNonNull(option, "option");
		final RowMetrics checkedMetrics = Objects.requireNonNull(metrics, "metrics");
		final List<DestinationSignTopology.StopOccurrence> stops = checkedOption.getRoute().getStops();
		final int sourceIndex = checkedOption.getKey().getSourceOccurrenceIndex();
		final int destinationIndex = checkedOption.getKey().getDestinationOccurrenceIndex();
		if (sourceIndex < 0 || destinationIndex <= sourceIndex || destinationIndex >= stops.size()
				|| checkedOption.getSource() != stops.get(sourceIndex) || checkedOption.getDestination() != stops.get(destinationIndex)) {
			throw new IllegalArgumentException("Option occurrence indices do not match its route");
		}
		return checkedMetrics.dense
				? projectDense(stops, sourceIndex, destinationIndex, checkedMetrics)
				: projectCompatibility(stops, sourceIndex, destinationIndex, checkedMetrics);
	}

	private static RouteStrip projectDense(List<DestinationSignTopology.StopOccurrence> stops, int sourceIndex, int destinationIndex, RowMetrics metrics) {
		final List<Token> mandatory = mandatoryTokens(stops, sourceIndex, destinationIndex);
		final boolean hasFollowing = destinationIndex + 1 < stops.size();
		final boolean hasContinuation = destinationIndex + 1 < stops.size() - 1;
		final int railLeft = DENSE_EDGE_RESERVE;
		final int railRight = metrics.getRouteStripWidth() - DENSE_EDGE_RESERVE;
		final int lastRoleX = railRight - (hasContinuation ? 8 : 0);
		mandatory.get(0).x = railLeft;
		for (int index = 1; index < mandatory.size(); index++) {
			mandatory.get(index).x = lastRoleX - ROLE_GAP * (mandatory.size() - 1 - index);
		}

		final int firstRightRoleX = mandatory.size() == 1 ? lastRoleX : mandatory.get(1).x;
		final int intermediateLeft = railLeft + 12;
		final int intermediateRight = firstRightRoleX - 12;
		final int intermediateSpan = Math.max(0, intermediateRight - intermediateLeft);
		final int intermediateCapacity = intermediateRight < intermediateLeft ? 0 : intermediateSpan / 12 + 1;
		final int firstRightOccurrence = mandatory.size() == 1 ? destinationIndex : mandatory.get(1).occurrenceIndex;
		final List<Token> candidates = new ArrayList<>();
		for (int occurrence = sourceIndex + 1; occurrence < firstRightOccurrence; occurrence++) {
			candidates.add(stationToken(stops, occurrence, MarkerRole.INTERMEDIATE));
		}
		final List<Token> intermediate = selectIntermediate(candidates, intermediateCapacity);
		spread(intermediate, intermediateLeft, intermediateSpan);

		final List<Token> allTokens = new ArrayList<>(mandatory);
		allTokens.addAll(intermediate);
		allTokens.sort(Comparator.comparingDouble(token -> token.order));
		final int centerY = metrics.rowHeight / 2;
		List<Marker> markers = createMarkers(allTokens, metrics, centerY);
		final List<LabelSlot> labels = allocateLabels(markers, sourceIndex, metrics);
		markers = attachLabelSlots(markers, labels);
		final ContinuationArrow arrow = hasContinuation ? new ContinuationArrow(lastRoleX, centerY - 3, 8, 6) : null;
		final int railEnd = hasContinuation ? lastRoleX : mandatory.get(mandatory.size() - 1).x;
		return new RouteStrip(markers, labels, new Rail(railLeft, railEnd, centerY, metrics.railThickness), arrow, intermediateCapacity);
	}

	private static RouteStrip projectCompatibility(List<DestinationSignTopology.StopOccurrence> stops, int sourceIndex, int destinationIndex, RowMetrics metrics) {
		final List<Token> mandatory = mandatoryTokens(stops, sourceIndex, destinationIndex);
		final int totalCapacity = Math.max(mandatory.size(), (metrics.getRouteStripWidth() - 8) / 8 + 1);
		final int intermediateCapacity = Math.max(0, totalCapacity - mandatory.size());
		final List<Token> candidates = new ArrayList<>();
		for (int occurrence = sourceIndex + 1; occurrence < destinationIndex - 1; occurrence++) {
			candidates.add(stationToken(stops, occurrence, MarkerRole.INTERMEDIATE));
		}
		final List<Token> intermediate = selectIntermediate(candidates, intermediateCapacity);
		final List<Token> allTokens = new ArrayList<>(mandatory);
		allTokens.addAll(intermediate);
		allTokens.sort(Comparator.comparingDouble(token -> token.order));
		final int leftInset = Math.max(4, markerOuterDiameter(allTokens.get(0), metrics) / 2);
		final int rightInset = Math.max(4, (markerOuterDiameter(allTokens.get(allTokens.size() - 1), metrics) + 1) / 2);
		spread(allTokens, leftInset, Math.max(0, metrics.getRouteStripWidth() - leftInset - rightInset));
		final int centerY = metrics.rowHeight / 2;
		final List<Marker> markers = createMarkers(allTokens, metrics, centerY);
		return new RouteStrip(markers, List.of(), new Rail(markers.get(0).centerX,
				markers.get(markers.size() - 1).centerX, centerY, metrics.railThickness), null, intermediateCapacity);
	}

	private static List<Token> mandatoryTokens(List<DestinationSignTopology.StopOccurrence> stops, int sourceIndex, int destinationIndex) {
		final List<Token> mandatory = new ArrayList<>();
		mandatory.add(stationToken(stops, sourceIndex, MarkerRole.CURRENT));
		if (destinationIndex - 1 > sourceIndex) mandatory.add(stationToken(stops, destinationIndex - 1, MarkerRole.PREVIOUS));
		mandatory.add(stationToken(stops, destinationIndex, MarkerRole.TARGET));
		if (destinationIndex + 1 < stops.size()) mandatory.add(stationToken(stops, destinationIndex + 1, MarkerRole.FOLLOWING));
		return mandatory;
	}

	private static Token stationToken(List<DestinationSignTopology.StopOccurrence> stops, int occurrence, MarkerRole role) {
		final DestinationSignTopology.StopOccurrence stop = stops.get(occurrence);
		return new Token(occurrence, occurrence, stop.getStationZoneId(), stop.getStationDisplayName(), role);
	}

	private static List<Token> selectIntermediate(List<Token> candidates, int capacity) {
		if (capacity <= 0 || candidates.isEmpty()) return List.of();
		if (candidates.size() <= capacity) return new ArrayList<>(candidates);
		if (capacity == 1) {
			final Token ellipsis = ellipsis(candidates.get(0).occurrenceIndex);
			return new ArrayList<>(List.of(ellipsis));
		}
		final int realSlots = capacity - 1;
		final int prefixCount = (realSlots + 1) / 2;
		final int suffixCount = realSlots / 2;
		final List<Token> selected = new ArrayList<>();
		selected.addAll(candidates.subList(0, prefixCount));
		final int suffixStart = candidates.size() - suffixCount;
		final int firstHidden = candidates.get(prefixCount).occurrenceIndex;
		selected.add(ellipsis(firstHidden));
		selected.addAll(candidates.subList(suffixStart, candidates.size()));
		return selected;
	}

	private static Token ellipsis(int firstHiddenOccurrence) {
		return new Token(-1, firstHiddenOccurrence - 0.5, 0, "", MarkerRole.ELLIPSIS);
	}

	private static void spread(List<Token> tokens, int left, int span) {
		if (tokens.isEmpty()) return;
		if (tokens.size() == 1) {
			tokens.get(0).x = left + roundHalfUp(span / 2.0);
			return;
		}
		for (int index = 0; index < tokens.size(); index++) {
			tokens.get(index).x = left + roundHalfUp(index * (double) span / (tokens.size() - 1));
		}
	}

	private static List<Marker> createMarkers(List<Token> tokens, RowMetrics metrics, int centerY) {
		final int ellipsisIndex = indexOfEllipsis(tokens);
		final List<Marker> markers = new ArrayList<>();
		for (int index = 0; index < tokens.size(); index++) {
			final Token token = tokens.get(index);
			final int outerDiameter = markerOuterDiameter(token, metrics);
			final int innerDiameter;
			if (token.role == MarkerRole.TARGET) {
				innerDiameter = metrics.targetInnerDiameter;
			} else if (token.role == MarkerRole.ELLIPSIS) {
				innerDiameter = 2;
			} else {
				innerDiameter = 0;
			}
			final double opacity = token.role == MarkerRole.INTERMEDIATE
					? ellipsisIndex < 0 ? 0.72 : Math.min(0.72, 0.36 + 0.12 * Math.abs(index - ellipsisIndex))
					: token.role == MarkerRole.ELLIPSIS ? 0.72 : 1.0;
			markers.add(new Marker(token.occurrenceIndex, token.stationId, token.stationName, token.role, token.x, centerY,
					outerDiameter, innerDiameter, opacity, -1, -1, 0));
		}
		return markers;
	}

	private static int markerOuterDiameter(Token token, RowMetrics metrics) {
		if (token.role == MarkerRole.TARGET) return metrics.targetOuterDiameter;
		if (token.role == MarkerRole.CURRENT) return metrics.currentMarkerDiameter;
		if (token.role == MarkerRole.ELLIPSIS) return 6;
		return metrics.normalMarkerDiameter;
	}

	private static int indexOfEllipsis(List<Token> tokens) {
		for (int index = 0; index < tokens.size(); index++) if (tokens.get(index).role == MarkerRole.ELLIPSIS) return index;
		return -1;
	}

	private static List<LabelSlot> allocateLabels(List<Marker> markers, int sourceIndex, RowMetrics metrics) {
		final List<Marker> mandatory = markers.stream().filter(marker -> marker.role != MarkerRole.INTERMEDIATE && marker.role != MarkerRole.ELLIPSIS)
				.sorted(Comparator.comparingInt(marker -> marker.occurrenceIndex)).toList();
		final List<LabelSlot> labels = new ArrayList<>();
		for (int mandatoryIndex = 0; mandatoryIndex < mandatory.size(); mandatoryIndex++) {
			final Marker marker = mandatory.get(mandatoryIndex);
			final int lane = mandatoryIndex % 2;
			final List<Marker> sameLane = new ArrayList<>();
			for (int index = lane; index < mandatory.size(); index += 2) sameLane.add(mandatory.get(index));
			final int laneIndex = sameLane.indexOf(marker);
			final int cellLeft = laneIndex == 0 ? 0 : roundHalfUp((sameLane.get(laneIndex - 1).centerX + marker.centerX) / 2.0);
			final int cellRight = laneIndex == sameLane.size() - 1 ? metrics.getRouteStripWidth()
					: roundHalfUp((marker.centerX + sameLane.get(laneIndex + 1).centerX) / 2.0);
			final int width = Math.min(96, Math.max(0, cellRight - cellLeft));
			final int x = clamp(cellLeft, roundHalfUp(marker.centerX - width / 2.0), cellRight - width);
			labels.add(label(marker, lane, x, width, true, metrics));
		}

		for (final Marker marker : markers.stream().filter(value -> value.role == MarkerRole.INTERMEDIATE)
				.sorted(Comparator.comparingInt(value -> value.occurrenceIndex)).toList()) {
			final int x = marker.centerX - 24;
			if (x < 0 || x + 48 > metrics.getRouteStripWidth()) continue;
			final int preferredLane = Math.floorMod(marker.occurrenceIndex - sourceIndex, 2);
			for (final int lane : new int[]{preferredLane, 1 - preferredLane}) {
				if (labels.stream().noneMatch(label -> label.lane == lane && intersects(x, x + 48, label.x, label.x + label.width))) {
					labels.add(label(marker, lane, x, 48, false, metrics));
					break;
				}
			}
		}
		labels.sort(Comparator.comparingInt(LabelSlot::getOccurrenceIndex));
		return labels;
	}

	private static LabelSlot label(Marker marker, int lane, int x, int width, boolean mandatory, RowMetrics metrics) {
		final int y = lane == 0 ? 0 : metrics.rowHeight - metrics.targetFontSize;
		final int fontSize = marker.role == MarkerRole.TARGET ? metrics.targetFontSize : metrics.rowFontSize;
		return new LabelSlot(marker.occurrenceIndex, marker.stationId, marker.stationName, marker.role, lane, x, y, width,
				metrics.targetFontSize, fontSize, mandatory, marker.role == MarkerRole.TARGET || marker.role == MarkerRole.CURRENT);
	}

	private static List<Marker> attachLabelSlots(List<Marker> markers, List<LabelSlot> labels) {
		final Map<Integer, LabelSlot> byOccurrence = new HashMap<>();
		for (final LabelSlot label : labels) byOccurrence.put(label.occurrenceIndex, label);
		final List<Marker> result = new ArrayList<>();
		for (final Marker marker : markers) {
			final LabelSlot label = byOccurrence.get(marker.occurrenceIndex);
			result.add(label == null ? marker : new Marker(marker.occurrenceIndex, marker.stationId, marker.stationName, marker.role,
					marker.centerX, marker.centerY, marker.outerDiameter, marker.innerDiameter, marker.opacity,
					label.lane, label.x, label.width));
		}
		return result;
	}

	private static boolean intersects(int left1, int right1, int left2, int right2) {
		return left1 < right2 && left2 < right1;
	}

	private static int clamp(int minimum, int value, int maximum) {
		return Math.max(minimum, Math.min(value, maximum));
	}

	private static int roundHalfUp(double value) {
		return (int) Math.floor(value + 0.5);
	}

	public enum MarkerRole {
		CURRENT, INTERMEDIATE, PREVIOUS, TARGET, FOLLOWING, ELLIPSIS
	}

	public static final class RowMetrics {
		private final boolean dense;
		private final int widthBlocks;
		private final int rowHeight;
		private final int surfaceWidth;
		private final int outerInset;
		private final int columnGap;
		private final int identityX;
		private final int identityWidth;
		private final int routeStripX;
		private final int routeStripRight;
		private final int etaX;
		private final int etaWidth;
		private final int rowFontSize;
		private final int targetFontSize;
		private final int normalMarkerDiameter;
		private final int currentMarkerDiameter;
		private final int targetOuterDiameter;
		private final int targetInnerDiameter;
		private final int railThickness;
		private final int identityTextTop;

		private RowMetrics(boolean dense, int widthBlocks, int rowHeight, int surfaceWidth, int outerInset, int columnGap,
				int identityX, int identityWidth, int routeStripX, int routeStripRight, int etaX, int etaWidth,
				int rowFontSize, int targetFontSize, int normalMarkerDiameter, int currentMarkerDiameter,
				int targetOuterDiameter, int targetInnerDiameter, int railThickness, int identityTextTop) {
			this.dense = dense;
			this.widthBlocks = widthBlocks;
			this.rowHeight = rowHeight;
			this.surfaceWidth = surfaceWidth;
			this.outerInset = outerInset;
			this.columnGap = columnGap;
			this.identityX = identityX;
			this.identityWidth = identityWidth;
			this.routeStripX = routeStripX;
			this.routeStripRight = routeStripRight;
			this.etaX = etaX;
			this.etaWidth = etaWidth;
			this.rowFontSize = rowFontSize;
			this.targetFontSize = targetFontSize;
			this.normalMarkerDiameter = normalMarkerDiameter;
			this.currentMarkerDiameter = currentMarkerDiameter;
			this.targetOuterDiameter = targetOuterDiameter;
			this.targetInnerDiameter = targetInnerDiameter;
			this.railThickness = railThickness;
			this.identityTextTop = identityTextTop;
		}

		public boolean isDense() { return dense; }
		public int getWidthBlocks() { return widthBlocks; }
		public int getRowHeight() { return rowHeight; }
		public int getSurfaceWidth() { return surfaceWidth; }
		public int getOuterInset() { return outerInset; }
		public int getColumnGap() { return columnGap; }
		public int getIdentityX() { return identityX; }
		public int getIdentityWidth() { return identityWidth; }
		public int getRouteStripX() { return routeStripX; }
		public int getRouteStripRight() { return routeStripRight; }
		public int getRouteStripWidth() { return routeStripRight - routeStripX; }
		public int getEtaX() { return etaX; }
		public int getEtaWidth() { return etaWidth; }
		public int getRowFontSize() { return rowFontSize; }
		public int getTargetFontSize() { return targetFontSize; }
		public int getNormalMarkerDiameter() { return normalMarkerDiameter; }
		public int getCurrentMarkerDiameter() { return currentMarkerDiameter; }
		public int getTargetOuterDiameter() { return targetOuterDiameter; }
		public int getTargetInnerDiameter() { return targetInnerDiameter; }
		public int getRailThickness() { return railThickness; }
		public int getIdentityTextTop() { return identityTextTop; }
	}

	public static final class RouteStrip {
		private final List<Marker> markers;
		private final List<LabelSlot> labels;
		private final Rail rail;
		private final ContinuationArrow continuationArrow;
		private final int intermediateCapacity;

		private RouteStrip(List<Marker> markers, List<LabelSlot> labels, Rail rail, ContinuationArrow continuationArrow, int intermediateCapacity) {
			this.markers = Collections.unmodifiableList(new ArrayList<>(markers));
			this.labels = Collections.unmodifiableList(new ArrayList<>(labels));
			this.rail = rail;
			this.continuationArrow = continuationArrow;
			this.intermediateCapacity = intermediateCapacity;
		}

		public List<Marker> getMarkers() { return markers; }
		public List<LabelSlot> getLabels() { return labels; }
		public Rail getRail() { return rail; }
		public Optional<ContinuationArrow> getContinuationArrow() { return Optional.ofNullable(continuationArrow); }
		public int getIntermediateCapacity() { return intermediateCapacity; }
	}

	public static final class Marker {
		private final int occurrenceIndex;
		private final long stationId;
		private final String stationName;
		private final MarkerRole role;
		private final int centerX;
		private final int centerY;
		private final int outerDiameter;
		private final int innerDiameter;
		private final double opacity;
		private final int labelLane;
		private final int labelX;
		private final int labelWidth;

		private Marker(int occurrenceIndex, long stationId, String stationName, MarkerRole role, int centerX, int centerY,
				int outerDiameter, int innerDiameter, double opacity, int labelLane, int labelX, int labelWidth) {
			this.occurrenceIndex = occurrenceIndex;
			this.stationId = stationId;
			this.stationName = stationName;
			this.role = role;
			this.centerX = centerX;
			this.centerY = centerY;
			this.outerDiameter = outerDiameter;
			this.innerDiameter = innerDiameter;
			this.opacity = opacity;
			this.labelLane = labelLane;
			this.labelX = labelX;
			this.labelWidth = labelWidth;
		}

		public int getOccurrenceIndex() { return occurrenceIndex; }
		public long getStationId() { return stationId; }
		public String getStationName() { return stationName; }
		public MarkerRole getRole() { return role; }
		public int getCenterX() { return centerX; }
		public int getCenterY() { return centerY; }
		public int getDiameter() { return outerDiameter; }
		public int getOuterDiameter() { return outerDiameter; }
		public int getInnerDiameter() { return innerDiameter; }
		public double getOpacity() { return opacity; }
		public int getLabelLane() { return labelLane; }
		public int getLabelX() { return labelX; }
		public int getLabelWidth() { return labelWidth; }

		@Override
		public String toString() { return role + "@" + occurrenceIndex + "(" + centerX + "," + centerY + ")"; }
	}

	public static final class LabelSlot {
		private final int occurrenceIndex;
		private final long stationId;
		private final String stationName;
		private final MarkerRole role;
		private final int lane;
		private final int x;
		private final int y;
		private final int width;
		private final int height;
		private final int fontSize;
		private final boolean mandatory;
		private final boolean semibold;

		private LabelSlot(int occurrenceIndex, long stationId, String stationName, MarkerRole role, int lane, int x, int y,
				int width, int height, int fontSize, boolean mandatory, boolean semibold) {
			this.occurrenceIndex = occurrenceIndex;
			this.stationId = stationId;
			this.stationName = stationName;
			this.role = role;
			this.lane = lane;
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			this.fontSize = fontSize;
			this.mandatory = mandatory;
			this.semibold = semibold;
		}

		public int getOccurrenceIndex() { return occurrenceIndex; }
		public long getStationId() { return stationId; }
		public String getStationName() { return stationName; }
		public MarkerRole getRole() { return role; }
		public int getLane() { return lane; }
		public int getX() { return x; }
		public int getY() { return y; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
		public int getFontSize() { return fontSize; }
		public boolean isMandatory() { return mandatory; }
		public boolean isSemibold() { return semibold; }

		@Override
		public String toString() { return role + " label@" + occurrenceIndex + "[" + x + "," + (x + width) + ") lane " + lane; }
	}

	public static final class Rail {
		private final int startX;
		private final int endX;
		private final int centerY;
		private final int thickness;

		private Rail(int startX, int endX, int centerY, int thickness) {
			this.startX = startX;
			this.endX = endX;
			this.centerY = centerY;
			this.thickness = thickness;
		}

		public int getStartX() { return startX; }
		public int getEndX() { return endX; }
		public int getCenterY() { return centerY; }
		public int getThickness() { return thickness; }
	}

	public static final class ContinuationArrow {
		private final int baseX;
		private final int topY;
		private final int width;
		private final int height;

		private ContinuationArrow(int baseX, int topY, int width, int height) {
			this.baseX = baseX;
			this.topY = topY;
			this.width = width;
			this.height = height;
		}

		public int getBaseX() { return baseX; }
		public int getTopY() { return topY; }
		public int getWidth() { return width; }
		public int getHeight() { return height; }
	}

	private static final class Token {
		private final int occurrenceIndex;
		private final double order;
		private final long stationId;
		private final String stationName;
		private final MarkerRole role;
		private int x;

		private Token(int occurrenceIndex, double order, long stationId, String stationName, MarkerRole role) {
			this.occurrenceIndex = occurrenceIndex;
			this.order = order;
			this.stationId = stationId;
			this.stationName = stationName;
			this.role = role;
		}
	}
}
