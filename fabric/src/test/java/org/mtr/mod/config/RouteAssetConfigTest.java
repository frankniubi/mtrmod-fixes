package org.mtr.mod.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.core.serializer.JsonReader;
import org.mtr.libraries.com.google.gson.JsonObject;

public final class RouteAssetConfigTest {

	@Test
	public void defaultsEnableBoundedRouteAssets() {
		final Server server = new Server(new JsonReader(new JsonObject()));
		final Client client = new Client(new JsonReader(new JsonObject()));
		Assertions.assertTrue(server.getRouteTextureAssetsEnabled());
		Assertions.assertEquals("", server.getRouteTexturePublicBaseUrl());
		Assertions.assertEquals("route-textures", server.getRouteTextureOutputDirectory());
		Assertions.assertEquals(1, server.getRouteTextureGenerationThreads());
		Assertions.assertEquals(32, server.getRouteTextureRetainedRevisions());
		Assertions.assertEquals(7, server.getRouteTextureStaleAssetDays());
		Assertions.assertTrue(client.getServerRouteTexturesEnabled());
		Assertions.assertEquals(2048, client.getRouteTextureCacheMiB());
		Assertions.assertEquals(30, client.getRouteTextureStartupTimeoutSeconds());
		Assertions.assertEquals(4, client.getRouteTextureDownloadConcurrency());
	}

	@Test
	public void unsafeNumericValuesAreClamped() {
		final JsonObject serverJson = new JsonObject();
		serverJson.addProperty("routeTextureGenerationThreads", 99);
		serverJson.addProperty("routeTextureRetainedRevisions", 0);
		serverJson.addProperty("routeTextureStaleAssetDays", -1);
		final Server server = new Server(new JsonReader(serverJson));
		Assertions.assertEquals(4, server.getRouteTextureGenerationThreads());
		Assertions.assertEquals(1, server.getRouteTextureRetainedRevisions());
		Assertions.assertEquals(0, server.getRouteTextureStaleAssetDays());

		final JsonObject clientJson = new JsonObject();
		clientJson.addProperty("routeTextureCacheMiB", 1);
		clientJson.addProperty("routeTextureStartupTimeoutSeconds", 99);
		clientJson.addProperty("routeTextureDownloadConcurrency", 0);
		final Client client = new Client(new JsonReader(clientJson));
		Assertions.assertEquals(256, client.getRouteTextureCacheMiB());
		Assertions.assertEquals(30, client.getRouteTextureStartupTimeoutSeconds());
		Assertions.assertEquals(1, client.getRouteTextureDownloadConcurrency());
	}

	@Test
	public void blankOutputDirectoryUsesDefaultAndPublicUrlIsTrimmed() {
		final JsonObject serverJson = new JsonObject();
		serverJson.addProperty("routeTextureOutputDirectory", "   ");
		serverJson.addProperty("routeTexturePublicBaseUrl", "  https://cdn.example.test/assets  ");
		final Server server = new Server(new JsonReader(serverJson));
		Assertions.assertEquals("route-textures", server.getRouteTextureOutputDirectory());
		Assertions.assertEquals("https://cdn.example.test/assets", server.getRouteTexturePublicBaseUrl());
	}
}
