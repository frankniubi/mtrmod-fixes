package org.mtr.mod.data;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public final class InterchangeConsumerIntegrationTest {

	@Test
	public void broadcastUsesTypedStationGroups() throws Exception {
		final String source = readMainSource("org", "mtr", "mod", "data", "VehicleExtension.java");
		Assertions.assertTrue(source.contains("InterchangeRouteDisplay.getStationGroups"));
		Assertions.assertTrue(source.contains("InterchangeRouteDisplay.deduplicateForBroadcast"));
		Assertions.assertTrue(source.contains("vehicleExtraData.getNextStationId()"));
		Assertions.assertTrue(source.contains("vehicleExtraData.getThisRouteColor()"));
		Assertions.assertTrue(source.contains("vehicleExtraData.getNextRouteColor()"));
		Assertions.assertTrue(source.contains("IntAVLTreeSet excludedRouteColors"));
		Assertions.assertTrue(source.contains("MinecraftClientData.getInterchangeStation(nextStationId)"));
		Assertions.assertFalse(source.contains("vehicleExtraData.iterateInterchanges"), "untyped route-name data cannot classify high-speed and airplane routes");
	}

	@Test
	public void routeMapUsesTypedEntriesAndRgbExclusions() throws Exception {
		final String source = readMainSource("org", "mtr", "mod", "client", "RouteMapGenerator.java");
		Assertions.assertTrue(source.contains("InterchangeRouteDisplay.getRouteMapDisplay"));
		Assertions.assertTrue(source.contains("routeMapDisplay.hasRailwayInterchange()"));
		Assertions.assertTrue(source.contains("routeMapDisplay.hasAirportInterchange()"));
		Assertions.assertTrue(source.contains("RouteMapStationNameLayout"));
		Assertions.assertTrue(source.contains("RAILWAY_INTERCHANGE_RESOURCE"));
		Assertions.assertTrue(source.contains("AIRPORT_INTERCHANGE_RESOURCE"));
		Assertions.assertFalse(source.contains("DenseRouteMapLayout"));
		Assertions.assertFalse(source.contains("classifyDensePlatform"));
		Assertions.assertFalse(source.contains("generateDenseVerticalRouteMap"));
		Assertions.assertFalse(source.contains("drawDense"));
		Assertions.assertFalse(source.contains("InterchangeRouteDisplay.flattenForRouteMap"));
		Assertions.assertFalse(source.contains("InterchangeRouteDisplay.deduplicateForBroadcast"));
		Assertions.assertTrue(source.contains("excludedRouteColors.add(InterchangeRouteDisplay.normalizeColor"));
	}

	private static String readMainSource(String... pathParts) throws Exception {
		Path path = Path.of("src", "main", "java");
		for (final String pathPart : pathParts) {
			path = path.resolve(pathPart);
		}
		if (!Files.exists(path)) {
			path = Path.of("fabric").resolve(path);
		}
		Assertions.assertTrue(Files.exists(path));
		return Files.readString(path);
	}
}
