package org.mtr.mod.route;

import org.mtr.core.data.Platform;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.SimplifiedRoutePlatform;
import org.mtr.core.data.Station;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/** Immutable ordered route occurrences used by Destination Station Signs. */
public final class DestinationSignTopology {

	private final List<ServiceRoute> routes;
	private final Map<Long, StationZone> stations;

	public DestinationSignTopology(List<ServiceRoute> routes, List<StationZone> stations) {
		final List<ServiceRoute> sortedRoutes = new ArrayList<>(Objects.requireNonNull(routes, "routes"));
		sortedRoutes.forEach(route -> Objects.requireNonNull(route, "route"));
		sortedRoutes.sort(Comparator.comparingInt(ServiceRoute::getRouteOrder).thenComparingLong(ServiceRoute::getRouteId));
		this.routes = Collections.unmodifiableList(sortedRoutes);
		final TreeMap<Long, StationZone> stationMap = new TreeMap<>();
		Objects.requireNonNull(stations, "stations").forEach(station -> {
			final StationZone checked = Objects.requireNonNull(station, "station");
			if (checked.id != 0) stationMap.putIfAbsent(checked.id, checked);
		});
		for (final ServiceRoute route : sortedRoutes) {
			for (final StopOccurrence stop : route.stops) {
				if (stop.stationZoneId != 0) stationMap.putIfAbsent(stop.stationZoneId, new StationZone(stop.stationZoneId, stop.stationDisplayName));
			}
		}
		this.stations = Collections.unmodifiableMap(stationMap);
	}

	public List<ServiceRoute> getRoutes() { return routes; }
	public Optional<StationZone> getStation(long stationZoneId) { return Optional.ofNullable(stations.get(stationZoneId)); }
	public List<StationZone> getStations() { return List.copyOf(stations.values()); }

	public static DestinationSignTopology empty() {
		return new DestinationSignTopology(Collections.emptyList(), Collections.emptyList());
	}

	public static DestinationSignTopology materialize(Iterable<SimplifiedRoute> routes, Function<Long, Platform> platformResolver, Function<Long, Station> stationResolver) {
		Objects.requireNonNull(routes, "routes");
		Objects.requireNonNull(platformResolver, "platformResolver");
		Objects.requireNonNull(stationResolver, "stationResolver");
		final List<SimplifiedRoute> sorted = new ArrayList<>();
		routes.forEach(sorted::add);
		sorted.sort(null);
		final List<ServiceRoute> serviceRoutes = new ArrayList<>();
		final TreeMap<Long, StationZone> stationZones = new TreeMap<>();
		for (int routeOrder = 0; routeOrder < sorted.size(); routeOrder++) {
			final SimplifiedRoute route = sorted.get(routeOrder);
			final List<StopOccurrence> stops = new ArrayList<>();
			for (final SimplifiedRoutePlatform routePlatform : route.getPlatforms()) {
				final Platform platform = platformResolver.apply(routePlatform.getPlatformId());
				final long stationZoneId = platform != null && platform.area != null ? platform.area.getId() : routePlatform.getStationId();
				final Station station = stationResolver.apply(routePlatform.getStationId());
				final String stationName = platform != null && platform.area != null ? platform.area.getName() : station == null ? routePlatform.getStationName() : station.getName();
				final String platformName = platform == null ? "" : platform.getName();
				stops.add(new StopOccurrence(routePlatform.getPlatformId(), stationZoneId, platformName, stationName, routePlatform.getDestination()));
				if (stationZoneId != 0) stationZones.putIfAbsent(stationZoneId, new StationZone(stationZoneId, stationName));
			}
			serviceRoutes.add(new ServiceRoute(route.getId(), routeOrder, route.getName(), route.getColor(), stops));
		}
		return new DestinationSignTopology(serviceRoutes, new ArrayList<>(stationZones.values()));
	}

	public static final class ServiceRoute {
		private final long routeId;
		private final int routeOrder;
		private final String displayName;
		private final int color;
		private final List<StopOccurrence> stops;

		public ServiceRoute(long routeId, int routeOrder, String displayName, int color, List<StopOccurrence> stops) {
			if (routeId == 0 || routeOrder < 0) throw new IllegalArgumentException("Invalid destination sign route identity");
			this.routeId = routeId;
			this.routeOrder = routeOrder;
			this.displayName = Objects.requireNonNull(displayName, "displayName");
			final List<StopOccurrence> copiedStops = new ArrayList<>(Objects.requireNonNull(stops, "stops"));
			copiedStops.forEach(stop -> Objects.requireNonNull(stop, "stop"));
			this.stops = Collections.unmodifiableList(copiedStops);
			this.color = color;
		}

		public long getRouteId() { return routeId; }
		public int getRouteOrder() { return routeOrder; }
		public String getDisplayName() { return displayName; }
		public int getColor() { return color; }
		public List<StopOccurrence> getStops() { return stops; }
	}

	public static final class StopOccurrence {
		private final long platformId;
		private final long stationZoneId;
		private final String platformDisplayName;
		private final String stationDisplayName;
		private final String destination;

		public StopOccurrence(long platformId, long stationZoneId, String platformDisplayName, String stationDisplayName, String destination) {
			if (platformId == 0) throw new IllegalArgumentException("Destination sign stop platform is not configured");
			this.platformId = platformId;
			this.stationZoneId = stationZoneId;
			this.platformDisplayName = Objects.requireNonNull(platformDisplayName, "platformDisplayName");
			this.stationDisplayName = Objects.requireNonNull(stationDisplayName, "stationDisplayName");
			this.destination = Objects.requireNonNull(destination, "destination");
		}

		public long getPlatformId() { return platformId; }
		public long getStationZoneId() { return stationZoneId; }
		public String getPlatformDisplayName() { return platformDisplayName; }
		public String getStationDisplayName() { return stationDisplayName; }
		public String getDestination() { return destination; }
	}

	public static final class StationZone {
		private final long id;
		private final String displayName;

		public StationZone(long id, String displayName) {
			if (id == 0) throw new IllegalArgumentException("Destination sign station is not configured");
			this.id = id;
			this.displayName = Objects.requireNonNull(displayName, "displayName");
		}

		public long getId() { return id; }
		public String getDisplayName() { return displayName; }
	}
}
