package org.mtr.mod.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

final class NorthTreetrunkDestinationSignFixtures {

	private NorthTreetrunkDestinationSignFixtures() {
	}

	static DestinationSignTopology topology() {
		final LinkedHashMap<Long, RouteAssetRenderSnapshot.Route> routes = new LinkedHashMap<>();
		NorthTreetrunkRouteSignFixtures.u1Routes().forEach(route -> routes.putIfAbsent(route.getId(), route));
		NorthTreetrunkRouteSignFixtures.dRoutes().forEach(route -> routes.putIfAbsent(route.getId(), route));

		final List<DestinationSignTopology.ServiceRoute> serviceRoutes = new ArrayList<>();
		final LinkedHashMap<Long, DestinationSignTopology.StationZone> stationZones = new LinkedHashMap<>();
		int routeOrder = 0;
		for (final RouteAssetRenderSnapshot.Route route : routes.values()) {
			final List<DestinationSignTopology.StopOccurrence> stops = new ArrayList<>();
			for (final RouteAssetRenderSnapshot.Station station : route.getStations()) {
				stops.add(new DestinationSignTopology.StopOccurrence(
						station.getPlatformId(),
						station.getOwningStationId(),
						station.getPlatformDisplayName(),
						station.getName(),
						station.getDestination()
				));
				stationZones.putIfAbsent(station.getOwningStationId(),
						new DestinationSignTopology.StationZone(station.getOwningStationId(), station.getName()));
			}
			serviceRoutes.add(new DestinationSignTopology.ServiceRoute(
					route.getId(), routeOrder++, route.getName(), route.getColor(), stops));
		}
		return new DestinationSignTopology(serviceRoutes, new ArrayList<>(stationZones.values()));
	}
}
