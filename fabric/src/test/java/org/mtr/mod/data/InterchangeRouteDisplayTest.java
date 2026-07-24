package org.mtr.mod.data;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.longs.LongAVLTreeSet;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

public final class InterchangeRouteDisplayTest {

	@Test
	public void classifiesAndMergesActualRouteTypesAcrossStationZones() {
		final ClientData data = new ClientData();
		final Station metro = station(data, "都會站|Metro", 0);
		final Station railway = station(data, "鐵路總站|Railway Terminus", 100);
		final Station airport = station(data, "桃園國際機場|Taoyuan International Airport", 200);
		metro.connectedStations.add(railway);
		metro.connectedStations.add(airport);

		final Route normal = route(data, TransportMode.TRAIN, RouteType.NORMAL, "Metro Line", 0x123456);
		final Route normalNamedHighSpeed = route(data, TransportMode.TRAIN, RouteType.NORMAL, "Highspeed Shuttle", 0x223344);
		final Route highSpeed1 = route(data, TransportMode.TRAIN, RouteType.HIGH_SPEED, "HSR North", 0x334455);
		final Route highSpeed2 = route(data, TransportMode.TRAIN, RouteType.HIGH_SPEED, "HSR South", 0x445566);
		final Route airplane1 = route(data, TransportMode.AIRPLANE, RouteType.NORMAL, "Flight 1", 0x556677);
		final Route airplane2 = route(data, TransportMode.AIRPLANE, RouteType.HIGH_SPEED, "Flight 2", 0x667788);
		addRoutes(data, metro, TransportMode.TRAIN, normal, normalNamedHighSpeed);
		addRoutes(data, railway, TransportMode.TRAIN, highSpeed1, highSpeed2);
		addRoutes(data, airport, TransportMode.AIRPLANE, airplane1, airplane2);

		final ObjectArrayList<InterchangeRouteDisplay.StationGroup> groups = InterchangeRouteDisplay.getStationGroups(metro, new LongAVLTreeSet());
		final InterchangeRouteDisplay.StationGroup metroGroup = findGroup(groups, metro.getId());
		final InterchangeRouteDisplay.StationGroup railwayGroup = findGroup(groups, railway.getId());
		final InterchangeRouteDisplay.StationGroup airportGroup = findGroup(groups, airport.getId());

		Assertions.assertEquals(2, metroGroup.getEntries().size());
		Assertions.assertTrue(metroGroup.getEntries().stream().allMatch(entry -> entry.getCategory() == InterchangeRouteDisplay.Category.NORMAL));
		Assertions.assertTrue(metroGroup.getEntries().stream().anyMatch(entry -> entry.getText().equals("Highspeed Shuttle")), "names must not determine high-speed classification");

		Assertions.assertEquals(1, railwayGroup.getEntries().size());
		Assertions.assertEquals(InterchangeRouteDisplay.Category.RAILWAY, railwayGroup.getEntries().get(0).getCategory());
		Assertions.assertEquals("鐵路-Railway", railwayGroup.getEntries().get(0).getCategory().getDisplayName());
		Assertions.assertEquals("可換鐵路 Railway Routes Changable", railwayGroup.getEntries().get(0).getText());

		Assertions.assertEquals(1, airportGroup.getEntries().size());
		Assertions.assertEquals(InterchangeRouteDisplay.Category.AIRPORT, airportGroup.getEntries().get(0).getCategory());
		Assertions.assertEquals("機場-Airport：桃園國際機場|Taoyuan International Airport", airportGroup.getEntries().get(0).getText());
		Assertions.assertFalse(airportGroup.getEntries().get(0).getText().contains("Flight"));
	}

	@Test
	public void exclusionsUseRouteIdsInsteadOfColors() {
		final ClientData data = new ClientData();
		final Station station = station(data, "Shared Colour", 0);
		final Route excluded = route(data, TransportMode.TRAIN, RouteType.NORMAL, "Excluded", 0xABCDEF);
		final Route retained = route(data, TransportMode.TRAIN, RouteType.NORMAL, "Retained", 0xABCDEF);
		addRoutes(data, station, TransportMode.TRAIN, excluded, retained);
		final LongAVLTreeSet excludedIds = new LongAVLTreeSet();
		excludedIds.add(excluded.getId());

		final ObjectArrayList<InterchangeRouteDisplay.Entry> entries = InterchangeRouteDisplay.getStationGroups(station, excludedIds).get(0).getEntries();
		Assertions.assertEquals(1, entries.size());
		Assertions.assertEquals(retained.getId(), entries.get(0).getSourceRouteId());
		Assertions.assertEquals("Retained", entries.get(0).getText());
	}

	@Test
	public void routeMapFlattensRailwayButKeepsDistinctAirportNames() {
		final ClientData data = new ClientData();
		final Station metro = station(data, "Metro", 0);
		final Station railway1 = station(data, "Railway One", 100);
		final Station railway2 = station(data, "Railway Two", 200);
		final Station airport1 = station(data, "Airport One", 300);
		final Station airport2 = station(data, "Airport Two", 400);
		metro.connectedStations.add(railway1);
		metro.connectedStations.add(railway2);
		metro.connectedStations.add(airport1);
		metro.connectedStations.add(airport2);
		addRoutes(data, railway1, TransportMode.TRAIN, route(data, TransportMode.TRAIN, RouteType.HIGH_SPEED, "Rail 1", 1));
		addRoutes(data, railway2, TransportMode.TRAIN, route(data, TransportMode.TRAIN, RouteType.HIGH_SPEED, "Rail 2", 2));
		addRoutes(data, airport1, TransportMode.AIRPLANE, route(data, TransportMode.AIRPLANE, RouteType.NORMAL, "Air 1", 3));
		addRoutes(data, airport2, TransportMode.AIRPLANE, route(data, TransportMode.AIRPLANE, RouteType.NORMAL, "Air 2", 4));

		final ObjectArrayList<InterchangeRouteDisplay.Entry> entries = InterchangeRouteDisplay.flattenForRouteMap(InterchangeRouteDisplay.getStationGroups(metro, new LongAVLTreeSet()));
		Assertions.assertEquals(1, entries.stream().filter(entry -> entry.getCategory() == InterchangeRouteDisplay.Category.RAILWAY).count());
		Assertions.assertEquals(2, entries.stream().filter(entry -> entry.getCategory() == InterchangeRouteDisplay.Category.AIRPORT).count());
		Assertions.assertTrue(entries.stream().anyMatch(entry -> entry.getText().equals("機場-Airport：Airport One")));
		Assertions.assertTrue(entries.stream().anyMatch(entry -> entry.getText().equals("機場-Airport：Airport Two")));
	}

	private static Station station(ClientData data, String name, long x) {
		final Station station = new Station(data);
		station.setName(name);
		station.setCorners(new Position(x, 0, 0), new Position(x + 10, 10, 10));
		return station;
	}

	private static Route route(ClientData data, TransportMode transportMode, RouteType routeType, String name, int color) {
		final Route route = new Route(transportMode, data);
		route.setRouteType(routeType);
		route.setName(name);
		route.setColor(color);
		return route;
	}

	private static void addRoutes(ClientData data, Station station, TransportMode transportMode, Route... routes) {
		final long x = station.getMinX();
		final Platform platform = new Platform(new Position(x, 0, 0), new Position(x + 1, 0, 0), transportMode, data);
		station.savedRails.add(platform);
		for (final Route route : routes) {
			platform.routes.add(route);
		}
	}

	private static InterchangeRouteDisplay.StationGroup findGroup(ObjectArrayList<InterchangeRouteDisplay.StationGroup> groups, long stationId) {
		return groups.stream().filter(group -> group.getStationId() == stationId).findFirst().orElseThrow();
	}
}
