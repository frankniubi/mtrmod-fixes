package org.mtr.mod.data;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.*;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
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

		final ObjectArrayList<InterchangeRouteDisplay.StationGroup> groups = InterchangeRouteDisplay.getStationGroups(metro, new IntAVLTreeSet());
		final InterchangeRouteDisplay.StationGroup metroGroup = findGroup(groups, metro.getId());
		final InterchangeRouteDisplay.StationGroup railwayGroup = findGroup(groups, railway.getId());
		final InterchangeRouteDisplay.StationGroup airportGroup = findGroup(groups, airport.getId());

		Assertions.assertEquals(2, metroGroup.getEntries().size());
		Assertions.assertTrue(metroGroup.getEntries().stream().allMatch(entry -> entry.getCategory() == InterchangeRouteDisplay.Category.NORMAL));
		Assertions.assertTrue(metroGroup.getEntries().stream().anyMatch(entry -> entry.getText().equals("Highspeed Shuttle")), "names must not determine high-speed classification");

		Assertions.assertEquals(1, railwayGroup.getEntries().size());
		Assertions.assertEquals(InterchangeRouteDisplay.Category.RAILWAY, railwayGroup.getEntries().get(0).getCategory());
		Assertions.assertEquals("鐵路-Railway", railwayGroup.getEntries().get(0).getCategory().getDisplayName());
		Assertions.assertEquals("可換鐵路|Railway Routes Changable", railwayGroup.getEntries().get(0).getText());

		Assertions.assertEquals(1, airportGroup.getEntries().size());
		Assertions.assertEquals(InterchangeRouteDisplay.Category.AIRPORT, airportGroup.getEntries().get(0).getCategory());
		Assertions.assertEquals("機場-Airport：桃園國際機場|Taoyuan International Airport", airportGroup.getEntries().get(0).getText());
		Assertions.assertFalse(airportGroup.getEntries().get(0).getText().contains("Flight"));
	}

	@Test
	public void exclusionsUseNormalizedRgbColorsInsteadOfRouteIds() {
		final ClientData data = new ClientData();
		final Station station = station(data, "Shared Colour", 0);
		final Route excluded = route(data, TransportMode.TRAIN, RouteType.NORMAL, "Excluded", 0xABCDEF);
		final Route oppositeDirection = route(data, TransportMode.TRAIN, RouteType.NORMAL, "Opposite Direction", 0xABCDEF);
		final Route retained = route(data, TransportMode.TRAIN, RouteType.NORMAL, "Retained", 0xABCDEE);
		addRoutes(data, station, TransportMode.TRAIN, excluded, oppositeDirection, retained);
		final IntAVLTreeSet excludedColors = new IntAVLTreeSet();
		excludedColors.add(0x12ABCDEF);

		final ObjectArrayList<InterchangeRouteDisplay.Entry> entries = InterchangeRouteDisplay.getStationGroups(station, excludedColors).get(0).getEntries();
		Assertions.assertEquals(1, entries.size());
		Assertions.assertEquals(retained.getId(), entries.get(0).getSourceRouteId());
		Assertions.assertEquals("Retained", entries.get(0).getText());
		Assertions.assertEquals(0xABCDEF, InterchangeRouteDisplay.normalizeColor(0x12ABCDEF));
	}

	@Test
	public void routeMapFlattensRailwayAndAirportIntoIconFlags() {
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

		final InterchangeRouteDisplay.RouteMapDisplay routeMapDisplay = InterchangeRouteDisplay.getRouteMapDisplay(
				InterchangeRouteDisplay.getStationGroups(metro, new IntAVLTreeSet())
		);
		final ObjectArrayList<InterchangeRouteDisplay.Entry> entries = routeMapDisplay.getEntries();
		Assertions.assertTrue(routeMapDisplay.hasRailwayInterchange());
		Assertions.assertTrue(routeMapDisplay.hasAirportInterchange());
		Assertions.assertEquals(0, entries.stream().filter(entry -> entry.getCategory() == InterchangeRouteDisplay.Category.RAILWAY).count());
		Assertions.assertEquals(0, entries.stream().filter(entry -> entry.getCategory() == InterchangeRouteDisplay.Category.AIRPORT).count());
		Assertions.assertTrue(entries.isEmpty());
	}

	@Test
	public void routeMapKeepsNormalEntryBesideMergedRailwayFlag() {
		final ClientData data = new ClientData();
		final Station metro = station(data, "Metro", 0);
		final Station railway = station(data, "Railway", 100);
		metro.connectedStations.add(railway);
		addRoutes(data, metro, TransportMode.TRAIN, route(data, TransportMode.TRAIN, RouteType.NORMAL, "Circular Line", 0xF2C500));
		addRoutes(data, railway, TransportMode.TRAIN,
				route(data, TransportMode.TRAIN, RouteType.HIGH_SPEED, "HSR North", 0x112233),
				route(data, TransportMode.TRAIN, RouteType.HIGH_SPEED, "HSR South", 0x445566));

		final InterchangeRouteDisplay.RouteMapDisplay display = InterchangeRouteDisplay.getRouteMapDisplay(
				InterchangeRouteDisplay.getStationGroups(metro, new IntAVLTreeSet())
		);
		Assertions.assertTrue(display.hasRailwayInterchange());
		Assertions.assertFalse(display.hasAirportInterchange());
		Assertions.assertEquals(1, display.getEntries().size());
		Assertions.assertEquals("Circular Line", display.getEntries().get(0).getText());
		Assertions.assertEquals(0xF2C500, display.getEntries().get(0).getColor());
	}

	@Test
	public void nearbyTransferIncludesItsStationAndPairedLanguages() {
		final ClientData data = new ClientData();
		final Station gallery = station(data, "画廊|Gallery", 0);
		final Station nearby = station(data, "晥墓|Wanmu", 100);
		gallery.connectedStations.add(nearby);
		final Route local = route(data, TransportMode.TRAIN, RouteType.NORMAL, "2号线|Line 2", 0x334455);
		final Route transfer = route(data, TransportMode.TRAIN, RouteType.NORMAL, "3号线|Line 3||ignored", 0x123456);
		addRoutes(data, gallery, TransportMode.TRAIN, local);
		addRoutes(data, nearby, TransportMode.TRAIN, transfer);
		final ObjectArrayList<InterchangeRouteDisplay.StationGroup> groups = InterchangeRouteDisplay.getStationGroups(gallery, new IntAVLTreeSet());
		final InterchangeRouteDisplay.RouteMapDisplay display = InterchangeRouteDisplay.getRouteMapDisplay(groups, gallery.getId());
		Assertions.assertEquals(2, display.getEntries().size());
		Assertions.assertEquals("2号线|Line 2", display.getEntries().get(0).getText());
		final InterchangeRouteDisplay.Entry entry = display.getEntries().get(1);
		Assertions.assertEquals("⇄ 晥墓|Wanmu", entry.getText());
		Assertions.assertEquals(nearby.getId(), entry.getStationId());
		Assertions.assertEquals(transfer.getColor(), entry.getColor());
		Assertions.assertEquals("3号线|Line 3", findGroup(groups, nearby.getId()).getEntries().get(0).getText(),
				"map formatting must not change broadcast route names");
	}

	@Test
	public void nearbyStationWithSeveralLinesKeepsEveryColourButOneName() {
		final ClientData data = new ClientData();
		final Station own = station(data, "钻石湾|Zursat Wae", 0);
		final Station nearby = station(data, "井村|Village Well", 100);
		own.connectedStations.add(nearby);
		addRoutes(data, nearby, TransportMode.TRAIN,
				route(data, TransportMode.TRAIN, RouteType.NORMAL, "2号线|Line 2", 0xE5DE10),
				route(data, TransportMode.TRAIN, RouteType.NORMAL, "4号线|Line 4", 0x00A0E9));
		final InterchangeRouteDisplay.RouteMapDisplay display = InterchangeRouteDisplay.getRouteMapDisplay(
				InterchangeRouteDisplay.getStationGroups(own, new IntAVLTreeSet()), own.getId());
		Assertions.assertEquals(2, display.getEntries().size(), "the colour bar still shows each line");
		Assertions.assertTrue(display.getEntries().stream().allMatch(entry -> entry.getText().equals("⇄ 井村|Village Well")));
		final ObjectArrayList<String> names = new ObjectArrayList<>();
		display.getEntries().forEach(entry -> names.add(entry.getText()));
		Assertions.assertEquals("⇄ 井村|Village Well", IGui.mergeStations(names), "the label names the station once");
	}

	@Test
	public void singleLanguageStationDoesNotProduceDuplicateNearbyLabels() {
		final ClientData data = new ClientData();
		final Station own = station(data, "Gallery", 0);
		final Station nearby = station(data, "晥墓", 100);
		own.connectedStations.add(nearby);
		addRoutes(data, nearby, TransportMode.TRAIN, route(data, TransportMode.TRAIN, RouteType.NORMAL, "Line 3", 0x123456));
		Assertions.assertEquals("⇄ 晥墓", InterchangeRouteDisplay.getRouteMapDisplay(
				InterchangeRouteDisplay.getStationGroups(own, new IntAVLTreeSet()), own.getId()).getEntries().get(0).getText());
	}

	@Test
	public void inStationTransferWinsOverTheSameRouteAtANearbyStation() {
		final ClientData data = new ClientData();
		final Station own = station(data, "Zulu", 0);
		final Station nearby = station(data, "Alpha", 100);
		own.connectedStations.add(nearby);
		final Route route = route(data, TransportMode.TRAIN, RouteType.NORMAL, "Shared Line", 0x123456);
		addRoutes(data, own, TransportMode.TRAIN, route);
		addRoutes(data, nearby, TransportMode.TRAIN, route);
		final InterchangeRouteDisplay.RouteMapDisplay display = InterchangeRouteDisplay.getRouteMapDisplay(
				InterchangeRouteDisplay.getStationGroups(own, new IntAVLTreeSet()), own.getId());
		Assertions.assertEquals(1, display.getEntries().size());
		Assertions.assertEquals("Shared Line", display.getEntries().get(0).getText());
		Assertions.assertEquals(own.getId(), display.getEntries().get(0).getStationId());
	}

	@Test
	public void nearbyLabelsRespectSameColorExclusionAndDoNotInventConnections() {
		final ClientData data = new ClientData();
		final Station own = station(data, "Gallery", 0);
		final Station nearby = station(data, "Wanmu", 100);
		addRoutes(data, nearby, TransportMode.TRAIN, route(data, TransportMode.TRAIN, RouteType.NORMAL, "Line 3", 0x123456));
		Assertions.assertTrue(InterchangeRouteDisplay.getRouteMapDisplay(
				InterchangeRouteDisplay.getStationGroups(own, new IntAVLTreeSet()), own.getId()).getEntries().isEmpty());
		own.connectedStations.add(nearby);
		final IntAVLTreeSet excluded = new IntAVLTreeSet();
		excluded.add(0x12123456);
		Assertions.assertTrue(InterchangeRouteDisplay.getRouteMapDisplay(
				InterchangeRouteDisplay.getStationGroups(own, excluded), own.getId()).getEntries().isEmpty());
		Assertions.assertEquals("⇄ Wanmu", InterchangeRouteDisplay.getRouteMapDisplay(
				InterchangeRouteDisplay.getStationGroups(own, new IntAVLTreeSet()), own.getId()).getEntries().get(0).getText());
	}

	@Test
	public void broadcastMergesDuplicateDisplayNamesAcrossStationZones() {
		final ClientData data = new ClientData();
		final Station metro = station(data, "Metro", 0);
		final Station connected = station(data, "Connected", 100);
		metro.connectedStations.add(connected);
		addRoutes(data, metro, TransportMode.TRAIN, route(data, TransportMode.TRAIN, RouteType.NORMAL, "Shared Line", 0x123456));
		addRoutes(data, connected, TransportMode.TRAIN, route(data, TransportMode.TRAIN, RouteType.NORMAL, "Shared Line", 0x654321));

		final ObjectArrayList<InterchangeRouteDisplay.StationGroup> groups = InterchangeRouteDisplay.deduplicateForBroadcast(
				InterchangeRouteDisplay.getStationGroups(metro, new IntAVLTreeSet()), metro.getId()
		);
		Assertions.assertEquals(1, groups.size());
		Assertions.assertEquals(metro.getId(), groups.get(0).getStationId());
		Assertions.assertEquals(1, groups.get(0).getEntries().size());
		Assertions.assertEquals("Shared Line", groups.get(0).getEntries().get(0).getText());
		Assertions.assertEquals(0x123456, groups.get(0).getEntries().get(0).getColor());
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
