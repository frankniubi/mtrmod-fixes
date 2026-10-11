package org.mtr.mod.packet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RouteAssetRefreshIntegrationTest {

	@Test
	public void refreshPayloadIsTinyAndStrictlyBounded() {
		final PacketRouteAssetRefresh.RefreshPayload payload = new PacketRouteAssetRefresh.RefreshPayload("a".repeat(64), 9);
		Assertions.assertEquals("a".repeat(64), payload.getRevision());
		Assertions.assertEquals(9, payload.getConnectionNonce());
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PacketRouteAssetRefresh.RefreshPayload("short", 9));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new PacketRouteAssetRefresh.RefreshPayload("a".repeat(64), 0));
	}

	@Test
	public void refreshPacketIsRegisteredAndUsesClientManager() throws IOException {
		final String init = Files.readString(sourcePath("", "Init.java"));
		final String refresh = Files.readString(sourcePath("packet", "PacketRouteAssetRefresh.java"));
		final String hello = Files.readString(sourcePath("packet", "PacketRouteAssetHello.java"));

		Assertions.assertTrue(init.contains("REGISTRY.registerPacket(PacketRouteAssetRefresh.class"));
		Assertions.assertTrue(refresh.contains("ClientRouteAssetManager.getInstance().handleRefresh("));
		Assertions.assertTrue(hello.contains("manager.negotiate(serverPlayerEntity, hello)"), "server notifications require retaining the actual new-client player");
	}

	@Test
	public void helloRequiresAUsableHttpOrigin() {
		Assertions.assertFalse(PacketRouteAssetHello.hasUsableHttpOrigin("", 0));
		Assertions.assertFalse(PacketRouteAssetHello.hasUsableHttpOrigin("   ", 0));
		Assertions.assertTrue(PacketRouteAssetHello.hasUsableHttpOrigin("https://cdn.example.test/mtr", 0));
		Assertions.assertTrue(PacketRouteAssetHello.hasUsableHttpOrigin("", 8080));
	}

	private static Path sourcePath(String packageName, String fileName) {
		Path path = Path.of("src", "main", "java", "org", "mtr", "mod");
		if (!packageName.isEmpty()) path = path.resolve(packageName);
		path = path.resolve(fileName);
		return Files.exists(path) ? path : Path.of("fabric").resolve(path);
	}
}
