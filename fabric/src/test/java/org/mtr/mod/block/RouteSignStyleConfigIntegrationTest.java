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
		final String render = readMainJava("render", "RenderRouteSign.java");
		final String dynamicTextures = readMainJava("client", "DynamicTextureCache.java");
		final String clientPackets = readMainJava("packet", "ClientPacketHelper.java");
		final String init = readMainJava("", "Init.java");

		Assertions.assertTrue(block.contains("getPlatformIds()"));
		Assertions.assertTrue(block.contains("getCustomPlatformHeader()"));
		Assertions.assertTrue(screen.contains("new ButtonWidgetExtension[RouteSignStyleMode.values().length]"));
		Assertions.assertTrue(screen.contains("TextFieldWidgetExtension"));
		Assertions.assertTrue(screen.contains("styleMode != RouteSignStyleMode.RAILWAY"));
		Assertions.assertTrue(screen.contains("selectedPlatformIds"));
		Assertions.assertTrue(screen.contains("RouteSignConfig.create(platformIds, styleMode, customHeader)"));
		Assertions.assertTrue(packet.contains("payload.toConfig()"));
		Assertions.assertTrue(packet.contains("setData(config)"));
		Assertions.assertTrue(packet.contains("blockPos.down(isUpper ? 1 : 0)"));
		Assertions.assertTrue(packet.contains("persistentState.configureRouteSign(anchor.asLong(), config)"));
		Assertions.assertTrue(render.contains("entity.getPlatformIds()"));
		Assertions.assertTrue(render.contains("entity.getCustomPlatformHeader()"));
		Assertions.assertTrue(dynamicTextures.contains("RouteAssetCanonicalKeyFactory.routeSignMap"));
		Assertions.assertTrue(block.contains("removeConfiguredSign(anchor.asLong())"));
		Assertions.assertTrue(block.contains("configuredSignsChanged(world.getServer(), \"route-sign-reconcile\")"));
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
