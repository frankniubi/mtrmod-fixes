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
		Assertions.assertTrue(source.contains("vehicleExtraData.getNextStationId()"));
		Assertions.assertTrue(source.contains("MinecraftClientData.getInterchangeStation(nextStationId)"));
		Assertions.assertFalse(source.contains("vehicleExtraData.iterateInterchanges"), "untyped route-name data cannot classify high-speed and airplane routes");
	}

	@Test
	public void routeMapUsesTypedEntriesAndRouteIdExclusions() throws Exception {
		final String source = readMainSource("org", "mtr", "mod", "client", "RouteMapGenerator.java");
		Assertions.assertTrue(source.contains("InterchangeRouteDisplay.flattenForRouteMap"));
		Assertions.assertTrue(source.contains("excludedRouteIds.add"));
		Assertions.assertFalse(source.contains("colors.contains(color)"), "same-colored routes must be filtered by route ID, not color");
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
