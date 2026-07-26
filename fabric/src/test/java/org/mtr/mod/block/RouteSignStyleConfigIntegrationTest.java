package org.mtr.mod.block;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

public final class RouteSignStyleConfigIntegrationTest {

	@Test
	public void autoIsAbsentAndExplicitModesUseTheDedicatedValidatedPath() throws Exception {
		final String block = readMainJava("block", "BlockRouteSignBase.java");
		final String screen = readMainJava("screen", "RouteSignConfigScreen.java");
		final String packet = readMainJava("packet", "PacketUpdateRouteSignConfig.java");
		final String clientPackets = readMainJava("packet", "ClientPacketHelper.java");
		final String init = readMainJava("", "Init.java");

		Assertions.assertTrue(block.contains("KEY_STYLE_OVERRIDE = \"route_sign_style\""));
		Assertions.assertTrue(block.contains("if (styleMode.isExplicit())"));
		Assertions.assertTrue(block.contains("RouteSignStyleMode.fromPersisted"));
		Assertions.assertTrue(screen.contains("new ButtonWidgetExtension[RouteSignStyleMode.values().length]"));
		Assertions.assertTrue(packet.contains("RouteSignStyleMode.fromNetworkOrdinal(styleOrdinal)"));
		Assertions.assertTrue(packet.contains("setData(platformId, styleMode)"));
		Assertions.assertTrue(packet.contains("blockPos.down(isUpper ? 1 : 0)"));
		Assertions.assertTrue(clientPackets.indexOf("new RouteSignConfigScreen") < clientPackets.indexOf("new RailwaySignScreen"));
		Assertions.assertTrue(init.contains("PacketUpdateRouteSignConfig.class"));
	}

	private static String readMainJava(String packageName, String fileName) throws Exception {
		Path path = Path.of("src", "main", "java", "org", "mtr", "mod");
		if (!packageName.isEmpty()) path = path.resolve(packageName);
		path = path.resolve(fileName);
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		Assertions.assertTrue(Files.exists(path), path.toString());
		return Files.readString(path);
	}
}
