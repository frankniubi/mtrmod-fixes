package org.mtr.mod.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Immutable, bounded route-sign projection grouped by the immediate next Station Zone. */
public final class RouteSignCorridorModel {

	public static final int MAX_DEPARTING_OCCURRENCES = 32;
	public static final int MAX_FUTURE_STOPS_PER_OCCURRENCE = 256;
	public static final int MAX_TOTAL_FUTURE_STOPS = 4096;
	public static final int MAX_TOKENS_PER_ROW = 256;

	private RouteSignCorridorModel() {
	}

	public static boolean isHighSpeedOnly(RouteAssetRenderSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		if (snapshot.getRoutes().isEmpty()) return false;
		for (final RouteAssetRenderSnapshot.Route route : snapshot.getRoutes()) {
			if (route.getRouteKind() != RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED) return false;
		}
		return true;
	}

	public static Optional<Model> tryBuild(RouteAssetRenderSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot");
		if (snapshot.getRouteMapPurpose() != RouteMapPurpose.ROUTE_SIGN || !isHighSpeedOnly(snapshot)) return Optional.empty();
		return buildValidated(snapshot);
	}

	private static Optional<Model> buildValidated(RouteAssetRenderSnapshot snapshot) {
		final Builder builder = new Builder(snapshot);
		return builder.collectOccurrences() && builder.validateBoundsAndMetadata()
				? Optional.of(builder.groupSortAndFreeze())
				: Optional.empty();
	}

	private static boolean hasValidPlatformMetadata(RouteAssetRenderSnapshot.Station station) {
		return station.getPlatformId() != 0 && station.getStationId() != 0 && station.getOwningStationId() != 0 &&
				station.getOwningStationId() == station.getStationId() && !station.getName().isEmpty();
	}

	private static int compareCodePointsEmptyLast(String first, String second) {
		final boolean firstEmpty = first.isEmpty();
		final boolean secondEmpty = second.isEmpty();
		if (firstEmpty != secondEmpty) return firstEmpty ? 1 : -1;
		int firstOffset = 0;
		int secondOffset = 0;
		while (firstOffset < first.length() && secondOffset < second.length()) {
			final int firstCodePoint = first.codePointAt(firstOffset);
			final int secondCodePoint = second.codePointAt(secondOffset);
			if (firstCodePoint != secondCodePoint) return Integer.compare(firstCodePoint, secondCodePoint);
			firstOffset += Character.charCount(firstCodePoint);
			secondOffset += Character.charCount(secondCodePoint);
		}
		return Integer.compare(first.length() - firstOffset, second.length() - secondOffset);
	}

	private static int comparePlatformPaths(MutableRow first, MutableRow second) {
		final int sharedLength = Math.min(first.orderedStops.size(), second.orderedStops.size());
		for (int index = 0; index < sharedLength; index++) {
			final int comparison = Long.compare(first.orderedStops.get(index).getPlatformId(), second.orderedStops.get(index).getPlatformId());
			if (comparison != 0) return comparison;
		}
		return Integer.compare(first.orderedStops.size(), second.orderedStops.size());
	}

	private static final class Builder {
		private final RouteAssetRenderSnapshot snapshot;
		private final List<MutableRow> rows = new ArrayList<>();
		private int departingOccurrenceCount;
		private int totalFutureStopCount;
		private String selectedStationName = "";

		private Builder(RouteAssetRenderSnapshot snapshot) {
			this.snapshot = snapshot;
		}

		private boolean collectOccurrences() {
			if (snapshot.getSelectedPlatformId() == 0 || snapshot.getSelectedStationId() == 0) return false;
			int occurrenceOrder = 0;
			for (int routeOrder = 0; routeOrder < snapshot.getRoutes().size(); routeOrder++) {
				final RouteAssetRenderSnapshot.Route route = snapshot.getRoutes().get(routeOrder);
				final List<RouteAssetRenderSnapshot.Station> stations = route.getStations();
				final List<Integer> selectedIndices = new ArrayList<>();
				for (int index = 0; index < stations.size(); index++) {
					if (stations.get(index).getPlatformId() == snapshot.getSelectedPlatformId()) selectedIndices.add(index);
				}
				if (selectedIndices.isEmpty()) return false;

				for (final int selectedIndex : selectedIndices) {
					final RouteAssetRenderSnapshot.Station selected = stations.get(selectedIndex);
					if (!hasValidPlatformMetadata(selected) || selected.getStationId() != snapshot.getSelectedStationId()) return false;
					if (selectedStationName.isEmpty()) selectedStationName = selected.getName();
					if (selectedIndex + 1 < stations.size()) {
						final int futureStopCount = stations.size() - selectedIndex - 1;
						departingOccurrenceCount++;
						totalFutureStopCount += futureStopCount;
						if (departingOccurrenceCount > MAX_DEPARTING_OCCURRENCES ||
								futureStopCount > MAX_FUTURE_STOPS_PER_OCCURRENCE ||
								futureStopCount > MAX_TOKENS_PER_ROW ||
								totalFutureStopCount > MAX_TOTAL_FUTURE_STOPS) return false;
					}
				}

				final int firstSelectedIndex = selectedIndices.get(0);
				final List<StopOccurrence> routeStops = new ArrayList<>(stations.size() - firstSelectedIndex);
				for (int index = firstSelectedIndex; index < stations.size(); index++) {
					final RouteAssetRenderSnapshot.Station station = stations.get(index);
					if (!hasValidPlatformMetadata(station)) return false;
					routeStops.add(new StopOccurrence(index, station.getPlatformId(), station.getPlatformDisplayName(),
							station.getStationId(), station.getOwningStationId(), station.getName(), station.getDestination(), station.getInterchange()));
				}
				final List<StopOccurrence> immutableRouteStops = immutableCopy(routeStops);
				for (final int selectedIndex : selectedIndices) {
					if (selectedIndex + 1 >= stations.size()) continue;
					final int relativeIndex = selectedIndex - firstSelectedIndex;
					final List<StopOccurrence> orderedStops = immutableCopy(immutableRouteStops.subList(relativeIndex, immutableRouteStops.size()));
					rows.add(new MutableRow(route, routeOrder, occurrenceOrder++, selectedIndex, orderedStops, snapshot.getSelectedStationId()));
				}
			}
			return !rows.isEmpty();
		}

		private boolean validateBoundsAndMetadata() {
			return departingOccurrenceCount == rows.size() && departingOccurrenceCount <= MAX_DEPARTING_OCCURRENCES &&
					totalFutureStopCount <= MAX_TOTAL_FUTURE_STOPS && !selectedStationName.isEmpty();
		}

		private Model groupSortAndFreeze() {
			final Map<Long, List<MutableRow>> groupedRows = new LinkedHashMap<>();
			for (final MutableRow row : rows) groupedRows.computeIfAbsent(row.next().getStationId(), ignored -> new ArrayList<>()).add(row);

			final List<MutableCorridor> mutableCorridors = new ArrayList<>();
			for (final Map.Entry<Long, List<MutableRow>> entry : groupedRows.entrySet()) {
				final List<MutableRow> corridorRows = entry.getValue();
				final Map<Long, Integer> sharedZoneCounts = corridorSharedZoneCounts(corridorRows);
				for (final MutableRow row : corridorRows) row.markMandatoryStops(sharedZoneCounts);
				corridorRows.sort(Comparator
						.comparing((MutableRow row) -> row.next().getPlatformDisplayName(), RouteSignCorridorModel::compareCodePointsEmptyLast)
						.thenComparingInt(row -> row.originalRouteOrder)
						.thenComparingLong(row -> row.route.getId())
						.thenComparingInt(row -> row.currentOccurrenceIndex)
						.thenComparing(RouteSignCorridorModel::comparePlatformPaths));
				mutableCorridors.add(new MutableCorridor(entry.getKey(), corridorRows));
			}

			mutableCorridors.sort(Comparator
					.comparingInt(MutableCorridor::rowCount).reversed()
					.thenComparing(Comparator.comparingInt(MutableCorridor::totalFutureStopCount).reversed())
					.thenComparingInt(MutableCorridor::firstOccurrenceOrder)
					.thenComparingLong(corridor -> corridor.stationId));
			final List<Corridor> corridors = new ArrayList<>(mutableCorridors.size());
			for (final MutableCorridor corridor : mutableCorridors) corridors.add(corridor.freeze());
			return new Model(snapshot.getSelectedPlatformId(), snapshot.getSelectedStationId(), snapshot.getPlatformDisplayName(), selectedStationName, corridors);
		}

		private Map<Long, Integer> corridorSharedZoneCounts(List<MutableRow> corridorRows) {
			final Map<Long, Integer> counts = new HashMap<>();
			for (final MutableRow row : corridorRows) {
				for (final long stationId : row.downstreamZoneCandidates()) counts.merge(stationId, 1, Integer::sum);
			}
			return counts;
		}
	}

	private static final class MutableRow {
		private final RouteAssetRenderSnapshot.Route route;
		private final int originalRouteOrder;
		private final int occurrenceOrder;
		private final int currentOccurrenceIndex;
		private final List<StopOccurrence> orderedStops;
		private final long selectedStationId;
		private final TreeSet<Integer> mandatoryStopIndices = new TreeSet<>();
		private final List<Integer> selectedZoneRevisitIndices = new ArrayList<>();
		private boolean continuesAfterReturn;

		private MutableRow(RouteAssetRenderSnapshot.Route route, int originalRouteOrder, int occurrenceOrder, int currentOccurrenceIndex,
				List<StopOccurrence> orderedStops, long selectedStationId) {
			this.route = route;
			this.originalRouteOrder = originalRouteOrder;
			this.occurrenceOrder = occurrenceOrder;
			this.currentOccurrenceIndex = currentOccurrenceIndex;
			this.orderedStops = orderedStops;
			this.selectedStationId = selectedStationId;
		}

		private StopOccurrence next() {
			return orderedStops.get(1);
		}

		private Set<Long> downstreamZoneCandidates() {
			final Set<Long> stationIds = new HashSet<>();
			for (int index = 2; index < orderedStops.size(); index++) {
				final StopOccurrence stop = orderedStops.get(index);
				if (stop.getStationId() == selectedStationId) break;
				stationIds.add(stop.getStationId());
			}
			return stationIds;
		}

		private void markMandatoryStops(Map<Long, Integer> sharedZoneCounts) {
			mandatoryStopIndices.add(next().getStopIndex());
			if (orderedStops.size() > 2) mandatoryStopIndices.add(orderedStops.get(2).getStopIndex());
			final StopOccurrence terminal = orderedStops.get(orderedStops.size() - 1);
			mandatoryStopIndices.add(terminal.getStopIndex());

			boolean hasNonTerminalSelectedZoneRevisit = false;
			for (int index = 1; index < orderedStops.size(); index++) {
				final StopOccurrence stop = orderedStops.get(index);
				if (stop.getStationId() == selectedStationId) {
					selectedZoneRevisitIndices.add(stop.getStopIndex());
					mandatoryStopIndices.add(stop.getStopIndex());
					if (index < orderedStops.size() - 1) hasNonTerminalSelectedZoneRevisit = true;
				}
				if (stop.getInterchange().hasAirport()) mandatoryStopIndices.add(stop.getStopIndex());
			}
			continuesAfterReturn = hasNonTerminalSelectedZoneRevisit;
			if (!hasNonTerminalSelectedZoneRevisit && orderedStops.size() > 2) {
				mandatoryStopIndices.add(orderedStops.get(orderedStops.size() - 2).getStopIndex());
			}

			int firstSharedIndex = -1;
			int lastSharedIndex = -1;
			for (int index = 2; index < orderedStops.size(); index++) {
				final StopOccurrence stop = orderedStops.get(index);
				if (stop.getStationId() == selectedStationId) break;
				if (sharedZoneCounts.getOrDefault(stop.getStationId(), 0) >= 2) {
					if (firstSharedIndex < 0) firstSharedIndex = stop.getStopIndex();
					lastSharedIndex = stop.getStopIndex();
				}
			}
			if (firstSharedIndex >= 0) mandatoryStopIndices.add(firstSharedIndex);
			if (lastSharedIndex >= 0) mandatoryStopIndices.add(lastSharedIndex);
		}

		private RouteRow freeze() {
			return new RouteRow(new OccurrenceKey(route.getId(), orderedStops.get(0).getPlatformId(), currentOccurrenceIndex), route.getName(),
					route.getColor(), route.getCircularState(), originalRouteOrder, occurrenceOrder, orderedStops,
					new ArrayList<>(mandatoryStopIndices), selectedZoneRevisitIndices, continuesAfterReturn);
		}
	}

	private static final class MutableCorridor {
		private final long stationId;
		private final String stationName;
		private final List<MutableRow> rows;

		private MutableCorridor(long stationId, List<MutableRow> rows) {
			this.stationId = stationId;
			this.stationName = rows.get(0).next().getStationName();
			this.rows = rows;
		}

		private int rowCount() {
			return rows.size();
		}

		private int totalFutureStopCount() {
			int count = 0;
			for (final MutableRow row : rows) count += row.orderedStops.size() - 1;
			return count;
		}

		private int firstOccurrenceOrder() {
			int first = Integer.MAX_VALUE;
			for (final MutableRow row : rows) first = Math.min(first, row.occurrenceOrder);
			return first;
		}

		private Corridor freeze() {
			final List<RouteRow> frozenRows = new ArrayList<>(rows.size());
			for (final MutableRow row : rows) frozenRows.add(row.freeze());
			return new Corridor(stationId, stationName, totalFutureStopCount(), firstOccurrenceOrder(), frozenRows);
		}
	}

	public static final class OccurrenceKey {
		private final long routeId;
		private final long currentPlatformId;
		private final int currentOccurrenceIndex;

		public OccurrenceKey(long routeId, long currentPlatformId, int currentOccurrenceIndex) {
			this.routeId = routeId;
			this.currentPlatformId = currentPlatformId;
			this.currentOccurrenceIndex = currentOccurrenceIndex;
		}

		public long getRouteId() { return routeId; }
		public long getCurrentPlatformId() { return currentPlatformId; }
		public int getCurrentOccurrenceIndex() { return currentOccurrenceIndex; }

		@Override
		public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof OccurrenceKey)) return false;
			final OccurrenceKey that = (OccurrenceKey) object;
			return routeId == that.routeId && currentPlatformId == that.currentPlatformId && currentOccurrenceIndex == that.currentOccurrenceIndex;
		}

		@Override
		public int hashCode() {
			return Objects.hash(routeId, currentPlatformId, currentOccurrenceIndex);
		}
	}

	public static final class StopOccurrence {
		private final int stopIndex;
		private final long platformId;
		private final String platformDisplayName;
		private final long stationId;
		private final long owningStationId;
		private final String stationName;
		private final String destination;
		private final RouteAssetRenderSnapshot.Interchange interchange;

		public StopOccurrence(int stopIndex, long platformId, String platformDisplayName, long stationId, long owningStationId,
				String stationName, String destination, RouteAssetRenderSnapshot.Interchange interchange) {
			this.stopIndex = stopIndex;
			this.platformId = platformId;
			this.platformDisplayName = Objects.requireNonNull(platformDisplayName, "platformDisplayName");
			this.stationId = stationId;
			this.owningStationId = owningStationId;
			this.stationName = Objects.requireNonNull(stationName, "stationName");
			this.destination = Objects.requireNonNull(destination, "destination");
			this.interchange = Objects.requireNonNull(interchange, "interchange");
		}

		public int getStopIndex() { return stopIndex; }
		public long getPlatformId() { return platformId; }
		public String getPlatformDisplayName() { return platformDisplayName; }
		public long getStationId() { return stationId; }
		public long getOwningStationId() { return owningStationId; }
		public String getStationName() { return stationName; }
		public String getName() { return stationName; }
		public String getDestination() { return destination; }
		public RouteAssetRenderSnapshot.Interchange getInterchange() { return interchange; }
		public boolean hasRailwayInterchange() { return interchange.hasRailway(); }
		public boolean hasAirportInterchange() { return interchange.hasAirport(); }
	}

	public static final class RouteRow {
		private final OccurrenceKey occurrenceKey;
		private final String routeName;
		private final int routeColor;
		private final RouteAssetRenderSnapshot.CircularState circularState;
		private final int originalRouteOrder;
		private final int occurrenceOrder;
		private final List<StopOccurrence> orderedStops;
		private final List<StopOccurrence> futureStops;
		private final List<Integer> mandatoryStopIndices;
		private final List<Integer> selectedZoneRevisitIndices;
		private final boolean continuesAfterReturn;

		private RouteRow(OccurrenceKey occurrenceKey, String routeName, int routeColor, RouteAssetRenderSnapshot.CircularState circularState,
				int originalRouteOrder, int occurrenceOrder, List<StopOccurrence> orderedStops, List<Integer> mandatoryStopIndices,
				List<Integer> selectedZoneRevisitIndices, boolean continuesAfterReturn) {
			this.occurrenceKey = occurrenceKey;
			this.routeName = routeName;
			this.routeColor = routeColor & 0xFFFFFF;
			this.circularState = circularState;
			this.originalRouteOrder = originalRouteOrder;
			this.occurrenceOrder = occurrenceOrder;
			this.orderedStops = immutableCopy(orderedStops);
			this.futureStops = immutableCopy(orderedStops.subList(1, orderedStops.size()));
			this.mandatoryStopIndices = immutableCopy(mandatoryStopIndices);
			this.selectedZoneRevisitIndices = immutableCopy(selectedZoneRevisitIndices);
			this.continuesAfterReturn = continuesAfterReturn;
		}

		public OccurrenceKey getOccurrenceKey() { return occurrenceKey; }
		public long getRouteId() { return occurrenceKey.getRouteId(); }
		public String getRouteName() { return routeName; }
		public int getRouteColor() { return routeColor; }
		public RouteAssetRenderSnapshot.CircularState getCircularState() { return circularState; }
		public int getOriginalRouteOrder() { return originalRouteOrder; }
		public int getOccurrenceOrder() { return occurrenceOrder; }
		public int getCurrentOccurrenceIndex() { return occurrenceKey.getCurrentOccurrenceIndex(); }
		public StopOccurrence getCurrent() { return orderedStops.get(0); }
		public StopOccurrence getCurrentStop() { return getCurrent(); }
		public StopOccurrence getNext() { return orderedStops.get(1); }
		public StopOccurrence getNextStop() { return getNext(); }
		public List<StopOccurrence> getOrderedStops() { return orderedStops; }
		public List<StopOccurrence> getFutureStops() { return futureStops; }
		public List<Integer> getMandatoryStopIndices() { return mandatoryStopIndices; }
		public List<Integer> getSelectedZoneRevisitIndices() { return selectedZoneRevisitIndices; }
		public boolean isMandatory(int stopIndex) { return Collections.binarySearch(mandatoryStopIndices, stopIndex) >= 0; }
		public boolean isMandatoryStopIndex(int stopIndex) { return isMandatory(stopIndex); }
		public boolean continuesAfterReturn() { return continuesAfterReturn; }
		public boolean hasNonTerminalSelectedZoneRevisit() { return continuesAfterReturn; }
		public int getFullPathTokenCount() { return futureStops.size(); }
	}

	public static final class Corridor {
		private final long stationId;
		private final String stationName;
		private final int totalFutureStopCount;
		private final int firstOccurrenceOrder;
		private final List<RouteRow> rows;

		private Corridor(long stationId, String stationName, int totalFutureStopCount, int firstOccurrenceOrder, List<RouteRow> rows) {
			this.stationId = stationId;
			this.stationName = stationName;
			this.totalFutureStopCount = totalFutureStopCount;
			this.firstOccurrenceOrder = firstOccurrenceOrder;
			this.rows = immutableCopy(rows);
		}

		public long getStationId() { return stationId; }
		public long getNextStationId() { return stationId; }
		public String getStationName() { return stationName; }
		public String getNextStationName() { return stationName; }
		public int getTotalFutureStopCount() { return totalFutureStopCount; }
		public int getFirstOccurrenceOrder() { return firstOccurrenceOrder; }
		public List<RouteRow> getRows() { return rows; }

		public List<String> routeNames() {
			final List<String> names = new ArrayList<>(rows.size());
			for (final RouteRow row : rows) names.add(row.getRouteName());
			return immutableCopy(names);
		}

		public List<String> nextPlatformNames() {
			final List<String> names = new ArrayList<>(rows.size());
			for (final RouteRow row : rows) names.add(row.getNext().getPlatformDisplayName());
			return immutableCopy(names);
		}
	}

	public static final class Model {
		private final long selectedPlatformId;
		private final long selectedStationId;
		private final String selectedPlatformDisplayName;
		private final String selectedStationName;
		private final List<Corridor> corridors;
		private final List<RouteRow> rows;

		private Model(long selectedPlatformId, long selectedStationId, String selectedPlatformDisplayName, String selectedStationName, List<Corridor> corridors) {
			this.selectedPlatformId = selectedPlatformId;
			this.selectedStationId = selectedStationId;
			this.selectedPlatformDisplayName = selectedPlatformDisplayName;
			this.selectedStationName = selectedStationName;
			this.corridors = immutableCopy(corridors);
			final List<RouteRow> allRows = new ArrayList<>();
			for (final Corridor corridor : corridors) allRows.addAll(corridor.getRows());
			this.rows = immutableCopy(allRows);
		}

		public long getSelectedPlatformId() { return selectedPlatformId; }
		public long getSelectedStationId() { return selectedStationId; }
		public String getSelectedPlatformDisplayName() { return selectedPlatformDisplayName; }
		public String getPlatformDisplayName() { return selectedPlatformDisplayName; }
		public String getSelectedStationName() { return selectedStationName; }
		public List<Corridor> getCorridors() { return corridors; }
		public List<RouteRow> getRows() { return rows; }

		public List<Long> corridorStationIds() {
			final List<Long> stationIds = new ArrayList<>(corridors.size());
			for (final Corridor corridor : corridors) stationIds.add(corridor.getStationId());
			return immutableCopy(stationIds);
		}
	}

	private static <T> List<T> immutableCopy(List<? extends T> source) {
		return Collections.unmodifiableList(new ArrayList<>(source));
	}
}
