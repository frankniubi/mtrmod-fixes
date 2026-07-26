package org.mtr.mod.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DestinationSignDirectServiceModel {

	public static final int MAX_OPTIONS = 128;
	public static final int MAX_SCANNED_FUTURE_OCCURRENCES = 4096;

	private DestinationSignDirectServiceModel() {
	}

	public static Model project(DestinationSignTopology topology, long sourceStationId, long destinationStationId) {
		final DestinationSignTopology checkedTopology = Objects.requireNonNull(topology, "topology");
		requireConfiguredStation(sourceStationId);
		requireConfiguredStation(destinationStationId);
		final List<Option> options = new ArrayList<>();
		int scanned = 0;
		for (final DestinationSignTopology.ServiceRoute route : checkedTopology.getRoutes()) {
			final List<DestinationSignTopology.StopOccurrence> stops = route.getStops();
			for (int sourceIndex = 0; sourceIndex < stops.size(); sourceIndex++) {
				final DestinationSignTopology.StopOccurrence source = stops.get(sourceIndex);
				if (source.getStationZoneId() != sourceStationId) continue;
				for (int destinationIndex = sourceIndex + 1; destinationIndex < stops.size(); destinationIndex++) {
					if (++scanned > MAX_SCANNED_FUTURE_OCCURRENCES) throw new ProjectionLimitException("Too many future stop occurrences");
					final DestinationSignTopology.StopOccurrence destination = stops.get(destinationIndex);
					if (destination.getStationZoneId() == destinationStationId) {
						options.add(new Option(new OptionKey(route.getRouteId(), source.getPlatformId(), sourceIndex, destinationIndex), route, source, destination));
						if (options.size() > MAX_OPTIONS) throw new ProjectionLimitException("Too many direct service options");
						break;
					}
				}
			}
		}
		return new Model(sourceStationId, destinationStationId, options);
	}

	public static List<DestinationSignTopology.StationZone> reachableDestinations(DestinationSignTopology topology, long sourceStationId) {
		final DestinationSignTopology checkedTopology = Objects.requireNonNull(topology, "topology");
		requireConfiguredStation(sourceStationId);
		final Map<Long, DestinationSignTopology.StationZone> result = new LinkedHashMap<>();
		int scanned = 0;
		for (final DestinationSignTopology.ServiceRoute route : checkedTopology.getRoutes()) {
			final List<DestinationSignTopology.StopOccurrence> stops = route.getStops();
			for (int sourceIndex = 0; sourceIndex < stops.size(); sourceIndex++) {
				if (stops.get(sourceIndex).getStationZoneId() != sourceStationId) continue;
				for (int index = sourceIndex + 1; index < stops.size(); index++) {
					if (++scanned > MAX_SCANNED_FUTURE_OCCURRENCES) throw new ProjectionLimitException("Too many future stop occurrences");
					final long stationId = stops.get(index).getStationZoneId();
					if (stationId != 0) {
						checkedTopology.getStation(stationId).ifPresent(station -> result.putIfAbsent(stationId, station));
						if (result.size() > MAX_OPTIONS) throw new ProjectionLimitException("Too many reachable destinations");
					}
				}
			}
		}
		return Collections.unmodifiableList(new ArrayList<>(result.values()));
	}

	private static void requireConfiguredStation(long stationId) {
		if (stationId == 0) throw new IllegalArgumentException("Destination sign station is not configured");
	}

	public static final class Model {
		private final long sourceStationId;
		private final long destinationStationId;
		private final List<Option> options;

		private Model(long sourceStationId, long destinationStationId, List<Option> options) {
			this.sourceStationId = sourceStationId;
			this.destinationStationId = destinationStationId;
			this.options = Collections.unmodifiableList(new ArrayList<>(options));
		}

		public long getSourceStationId() { return sourceStationId; }
		public long getDestinationStationId() { return destinationStationId; }
		public List<Option> getOptions() { return options; }
	}

	public static final class Option {
		private final OptionKey key;
		private final DestinationSignTopology.ServiceRoute route;
		private final DestinationSignTopology.StopOccurrence source;
		private final DestinationSignTopology.StopOccurrence destination;

		private Option(OptionKey key, DestinationSignTopology.ServiceRoute route, DestinationSignTopology.StopOccurrence source, DestinationSignTopology.StopOccurrence destination) {
			this.key = key;
			this.route = route;
			this.source = source;
			this.destination = destination;
		}

		public OptionKey getKey() { return key; }
		public DestinationSignTopology.ServiceRoute getRoute() { return route; }
		public DestinationSignTopology.StopOccurrence getSource() { return source; }
		public DestinationSignTopology.StopOccurrence getDestination() { return destination; }
	}

	public static final class OptionKey {
		private final long routeId;
		private final long sourcePlatformId;
		private final int sourceOccurrenceIndex;
		private final int destinationOccurrenceIndex;

		public OptionKey(long routeId, long sourcePlatformId, int sourceOccurrenceIndex, int destinationOccurrenceIndex) {
			this.routeId = routeId;
			this.sourcePlatformId = sourcePlatformId;
			this.sourceOccurrenceIndex = sourceOccurrenceIndex;
			this.destinationOccurrenceIndex = destinationOccurrenceIndex;
		}

		public long getRouteId() { return routeId; }
		public long getSourcePlatformId() { return sourcePlatformId; }
		public int getSourceOccurrenceIndex() { return sourceOccurrenceIndex; }
		public int getDestinationOccurrenceIndex() { return destinationOccurrenceIndex; }

		@Override
		public boolean equals(Object object) {
			return this == object || object instanceof OptionKey && routeId == ((OptionKey) object).routeId
					&& sourcePlatformId == ((OptionKey) object).sourcePlatformId
					&& sourceOccurrenceIndex == ((OptionKey) object).sourceOccurrenceIndex
					&& destinationOccurrenceIndex == ((OptionKey) object).destinationOccurrenceIndex;
		}

		@Override
		public int hashCode() {
			return Objects.hash(routeId, sourcePlatformId, sourceOccurrenceIndex, destinationOccurrenceIndex);
		}

		@Override
		public String toString() {
			return routeId + ":" + sourcePlatformId + ":" + sourceOccurrenceIndex + ":" + destinationOccurrenceIndex;
		}
	}

	public static final class ProjectionLimitException extends IllegalArgumentException {
		public ProjectionLimitException(String message) {
			super(message);
		}
	}
}
