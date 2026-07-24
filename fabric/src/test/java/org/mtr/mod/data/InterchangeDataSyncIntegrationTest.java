package org.mtr.mod.data;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public final class InterchangeDataSyncIntegrationTest {

	@Test
	public void consumersUseTheRenderDistanceIndependentStationLookup() throws Exception {
		final String vehicleSource = readMainSource("data", "VehicleExtension.java");
		final String routeMapSource = readMainSource("client", "RouteMapGenerator.java");
		Assertions.assertTrue(vehicleSource.contains("MinecraftClientData.getInterchangeStation(nextStationId)"));
		Assertions.assertTrue(routeMapSource.contains("MinecraftClientData.getInterchangeStation(simplifiedRoutePlatform.getStationId())"));
	}

	@Test
	public void fullInterchangeDataIsRequestedAndKeptCurrent() throws Exception {
		final String initSource = readMainSource("InitClient.java");
		final String requestSource = readMainSource("packet", "PacketRequestInterchangeData.java");
		final String updateSource = readMainSource("packet", "PacketUpdateData.java");
		final String deleteSource = readMainSource("packet", "PacketDeleteData.java");
		Assertions.assertTrue(initSource.contains("new PacketRequestInterchangeData()"));
		Assertions.assertTrue(requestSource.contains("OperationProcessor.LIST_DATA"));
		Assertions.assertTrue(requestSource.contains("MinecraftClientData.getInterchangeData()"));
		Assertions.assertTrue(updateSource.contains("MinecraftClientData.getInterchangeData()"));
		Assertions.assertTrue(deleteSource.contains("MinecraftClientData.getInterchangeData()"));
	}

	private static String readMainSource(String... pathParts) throws Exception {
		Path path = Path.of("src", "main", "java", "org", "mtr", "mod");
		for (final String pathPart : pathParts) {
			path = path.resolve(pathPart);
		}
		if (!Files.exists(path)) {
			path = Path.of("fabric").resolve(path);
		}
		Assertions.assertTrue(Files.exists(path), path.toString());
		return Files.readString(path);
	}
}
