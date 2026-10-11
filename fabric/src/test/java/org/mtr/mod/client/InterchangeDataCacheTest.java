package org.mtr.mod.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.ClientData;
import org.mtr.core.data.Position;
import org.mtr.core.data.Station;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.TransportMode;
import org.mtr.core.serializer.JsonReader;
import org.mtr.core.tool.Utilities;
import org.mtr.libraries.it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import org.mtr.mod.data.InterchangeRouteDisplay;

public final class InterchangeDataCacheTest {

	@AfterEach
	public void resetClientData() {
		MinecraftClientData.reset();
	}

	@Test
	public void findsStationsOutsideTheLocalRenderDistanceCache() {
		MinecraftClientData.reset();
		final ClientData interchangeData = MinecraftClientData.getInterchangeData();
		final Station remoteStation = new Station(interchangeData);
		remoteStation.setName("Remote Interchange");
		remoteStation.setCorners(new Position(10000, 0, 10000), new Position(10010, 10, 10010));
		interchangeData.stations.add(remoteStation);
		interchangeData.sync();

		Assertions.assertFalse(MinecraftClientData.getInstance().stationIdMap.containsKey(remoteStation.getId()));
		Assertions.assertSame(remoteStation, MinecraftClientData.getInterchangeStation(remoteStation.getId()));
	}

	@Test
	public void completeInterchangeGraphIsNotShadowedByNearbyPartialData() {
		MinecraftClientData.reset();
		final ClientData complete = MinecraftClientData.getInterchangeData();
		final Station station = new Station(complete);
		station.setName("Interchange");
		station.setCorners(new Position(0, 0, 0), new Position(10, 10, 10));
		final Station connected = new Station(complete);
		connected.setName("Connected");
		connected.setCorners(new Position(100, 0, 0), new Position(110, 10, 10));
		complete.stations.add(station);
		complete.stations.add(connected);
		final Platform platform = new Platform(new Position(101, 0, 1), new Position(102, 0, 1), TransportMode.TRAIN, complete);
		complete.platforms.add(platform);
		final Route route = new Route(TransportMode.TRAIN, complete);
		route.setName("Transfer Line");
		route.setColor(0x123456);
		route.getRoutePlatforms().add(new RoutePlatformData(platform.getId()));
		complete.routes.add(route);
		complete.sync();
		station.connectedStations.add(connected);

		final MinecraftClientData local = MinecraftClientData.getInstance();
		final Station partialStation = new Station(new JsonReader(Utilities.getJsonObjectFromData(station)), local);
		local.stationIdMap.put(station.getId(), partialStation);
		local.routeIdMap.put(route.getId(), new Route(new JsonReader(Utilities.getJsonObjectFromData(route)), local));
		local.platformIdMap.put(platform.getId(), new Platform(new JsonReader(Utilities.getJsonObjectFromData(platform)), local));
		Assertions.assertTrue(partialStation.connectedStations.isEmpty());
		Assertions.assertTrue(InterchangeRouteDisplay.getStationGroups(partialStation, new IntAVLTreeSet()).isEmpty());

		final Station resolved = MinecraftClientData.getInterchangeStation(station.getId());
		final InterchangeRouteDisplay.RouteMapDisplay display = InterchangeRouteDisplay.getRouteMapDisplay(
				InterchangeRouteDisplay.getStationGroups(resolved, new IntAVLTreeSet()));
		Assertions.assertEquals(1, display.getEntries().size(), "nearby partial data must not hide route sign interchanges");
		Assertions.assertEquals("Transfer Line", display.getEntries().get(0).getText());
		Assertions.assertEquals(1, InterchangeRouteDisplay.deduplicateForBroadcast(
				InterchangeRouteDisplay.getStationGroups(resolved, new IntAVLTreeSet()), station.getId()).size(),
				"broadcasts must use the same complete connected-station graph");
		Assertions.assertSame(station, resolved);
		Assertions.assertSame(route, MinecraftClientData.getInterchangeRoute(route.getId()));
		Assertions.assertSame(platform, MinecraftClientData.getInterchangePlatform(platform.getId()));
	}

	@Test
	public void localDataRemainsAvailableBeforeInterchangeDataArrives() {
		MinecraftClientData.reset();
		final MinecraftClientData local = MinecraftClientData.getInstance();
		final Station station = new Station(local);
		final Route route = new Route(TransportMode.TRAIN, local);
		final Platform platform = new Platform(new Position(0, 0, 0), new Position(1, 0, 0), TransportMode.TRAIN, local);
		local.stationIdMap.put(station.getId(), station);
		local.routeIdMap.put(route.getId(), route);
		local.platformIdMap.put(platform.getId(), platform);
		Assertions.assertSame(station, MinecraftClientData.getInterchangeStation(station.getId()));
		Assertions.assertSame(route, MinecraftClientData.getInterchangeRoute(route.getId()));
		Assertions.assertSame(platform, MinecraftClientData.getInterchangePlatform(platform.getId()));
		Assertions.assertNull(MinecraftClientData.getInterchangeStation(-1));
		Assertions.assertNull(MinecraftClientData.getInterchangeRoute(-1));
		Assertions.assertNull(MinecraftClientData.getInterchangePlatform(-1));
	}

	@Test
	public void resettingAWorldDropsThePreviousInterchangeCache() {
		final ClientData previous = MinecraftClientData.getInterchangeData();
		MinecraftClientData.reset();
		Assertions.assertNotSame(previous, MinecraftClientData.getInterchangeData());
	}
}
