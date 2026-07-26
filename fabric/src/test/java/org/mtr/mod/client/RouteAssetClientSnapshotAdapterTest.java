package org.mtr.mod.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.ClientData;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Route;
import org.mtr.core.data.RoutePlatformData;
import org.mtr.core.data.RouteType;
import org.mtr.core.data.SimplifiedRoute;
import org.mtr.core.data.Station;
import org.mtr.core.data.TransportMode;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.mtr.mod.route.RouteAssetCanonicalKeyFactory;
import org.mtr.mod.route.RouteAssetRenderSnapshot;
import org.mtr.mod.route.RouteMapPurpose;

public final class RouteAssetClientSnapshotAdapterTest {

	@AfterEach
	public void resetClientData() {
		MinecraftClientData.reset();
	}

	@Test
	public void localSnapshotResolvesHighSpeedMetadataFromInterchangeData() {
		MinecraftClientData.reset();
		final ClientData interchangeData = MinecraftClientData.getInterchangeData();
		final Station firstStation = station(interchangeData, "First", 0);
		final Station secondStation = station(interchangeData, "Second", 10);
		final Platform firstPlatform = platform(interchangeData, firstStation, 0);
		final Platform secondPlatform = platform(interchangeData, secondStation, 10);
		firstPlatform.setName("U1");
		secondPlatform.setName("R1");
		interchangeData.stations.add(firstStation);
		interchangeData.stations.add(secondStation);
		interchangeData.platforms.add(firstPlatform);
		interchangeData.platforms.add(secondPlatform);
		interchangeData.sync();

		final Route route = new Route(TransportMode.TRAIN, interchangeData);
		route.setName("High Speed");
		route.setRouteType(RouteType.HIGH_SPEED);
		route.getRoutePlatforms().add(new RoutePlatformData(firstPlatform.getId()));
		route.getRoutePlatforms().add(new RoutePlatformData(secondPlatform.getId()));
		interchangeData.routes.add(route);
		interchangeData.sync();

		final MinecraftClientData localData = MinecraftClientData.getInstance();
		final ObjectArrayList<SimplifiedRoute> simplifiedRoutes = new ObjectArrayList<>();
		SimplifiedRoute.addToList(simplifiedRoutes, route);
		localData.simplifiedRoutes.addAll(simplifiedRoutes);
		final RouteAssetRenderSnapshot snapshot = RouteAssetClientSnapshotAdapter.resolve(
				RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", firstPlatform.getId(), 1, "NORMAL", RouteMapPurpose.GENERIC, true, false, 37F / 22, false)
		).orElseThrow().getSnapshot();

		Assertions.assertEquals(1, snapshot.getRoutes().size());
		Assertions.assertEquals(RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, snapshot.getRoutes().get(0).getRouteKind());
		Assertions.assertEquals(firstPlatform.getId(), snapshot.getSelectedPlatformId());
		Assertions.assertEquals(firstStation.getId(), snapshot.getSelectedStationId());
		final RouteAssetRenderSnapshot.Station next = snapshot.getRoutes().get(0).getStations().get(1);
		Assertions.assertEquals("R1", next.getPlatformDisplayName());
		Assertions.assertEquals(secondStation.getId(), next.getOwningStationId());
	}

	private static Station station(ClientData data, String name, int x) {
		final Station station = new Station(data);
		station.setName(name);
		station.setCorners(new Position(x, 0, 0), new Position(x + 5, 5, 5));
		return station;
	}

	private static Platform platform(ClientData data, Station station, int x) {
		final Platform platform = new Platform(new Position(x, 0, 0), new Position(x + 1, 0, 0), TransportMode.TRAIN, data);
		station.savedRails.add(platform);
		return platform;
	}
}
