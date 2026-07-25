package org.mtr.mod.client.asset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ClientRouteAssetRenderIntegrationTest {

	@Test
	public void routeTextureEntrypointsUseCanonicalServerKeysAndPreserveLocalFallbacks() throws IOException {
		final String source = readSource("client", "DynamicTextureCache.java");

		Assertions.assertAll(
				() -> Assertions.assertTrue(source.contains("RouteAssetCanonicalKeyFactory.routeColorStrip(")),
				() -> Assertions.assertTrue(source.contains("RouteAssetCanonicalKeyFactory.directionArrow(")),
				() -> Assertions.assertTrue(source.contains("RouteAssetCanonicalKeyFactory.routeMap(")),
				() -> Assertions.assertTrue(source.contains("RouteAssetCanonicalKeyFactory.routeSquare(")),
				() -> Assertions.assertTrue(source.contains("ClientRouteAssetManager.getInstance().lookupRouteTexture(")),
				() -> Assertions.assertTrue(source.contains("case PENDING:"), "negotiation and decode must return the existing placeholder without scheduling local rasterization"),
				() -> Assertions.assertTrue(source.contains("case LOCAL:"), "disabled, fallback, high-resolution, and unmapped keys must retain the old local supplier")
		);
	}

	@Test
	public void localFallbackUsesTheLegacyOnDemandRendererBeyondTheServerAspectLimit() throws IOException {
		final String generatorSource = readSource("client", "RouteMapGenerator.java");
		final String keySource = readSource("route", "RouteAssetCanonicalKeyFactory.java");

		Assertions.assertTrue(keySource.contains("MAX_ASPECT_RATIO = 8"), "the server must remain bounded to route textures covering at most three door blocks");
		Assertions.assertFalse(generatorSource.contains("SHARED_ROUTE_ASSET_RENDERER"), "LOCAL fallback must not re-enter server key validation instead of the retained legacy rasterizer");
		Assertions.assertTrue(generatorSource.contains("getRouteStream(platformId"), "the original client route-map generator must remain available on demand");
	}

	@Test
	public void routeMapPixelChangesInvalidateOnlyRouteMapDependencies() throws IOException {
		final String catalogSource = readSource("route", "RouteAssetDependencyCatalog.java");

		Assertions.assertTrue(catalogSource.contains("key.getType() == RouteAssetType.ROUTE_MAP"));
		Assertions.assertTrue(catalogSource.contains("ROUTE_MAP_RENDERER_VERSION"));
	}

	@Test
	public void renderThreadOwnsBoundedUploadsAfterLegacyTextureTick() throws IOException {
		final String source = readSource("render", "MainRenderer.java");
		final int legacyTick = source.indexOf("DynamicTextureCache.instance.tick();");
		final int routeAssetUploads = source.indexOf("ClientRouteAssetManager.getInstance().beginRenderFrame(8, 2_000_000L);", legacyTick);

		Assertions.assertTrue(legacyTick >= 0 && routeAssetUploads > legacyTick, "route asset registration must run once on the render thread immediately after the legacy cache tick");
	}

	@Test
	public void routeSquaresUseStableRouteIdentityInsteadOfColor() throws IOException {
		final String cacheSource = readSource("client", "DynamicTextureCache.java");
		final String signSource = readSource("render", "RenderRailwaySign.java");

		Assertions.assertTrue(cacheSource.contains("getRouteSquare(long routeId, int color,"));
		Assertions.assertTrue(signSource.contains("ObjectArrayList<SimplifiedRoute> selectedRoutesSorted"));
		Assertions.assertTrue(signSource.contains("getRouteSquare(route.getId(), route.getColor(),"), "route color is not a globally stable route ID");
	}

	@Test
	public void managerKeepsManifestLookupDecodeAndUploadAsSeparateStages() throws IOException {
		final String source = readSource("client", "asset", "ClientRouteAssetManager.java");

		Assertions.assertAll(
				() -> Assertions.assertTrue(source.contains("enum RouteTextureState")),
				() -> Assertions.assertTrue(source.contains("RouteTextureState.PENDING")),
				() -> Assertions.assertTrue(source.contains("RouteTextureState.LOCAL")),
				() -> Assertions.assertTrue(source.contains("gpuCache.request(key.toString(), contentHash)")),
				() -> Assertions.assertTrue(source.contains(".drainUploads(maximumUploads, budgetNanos)")),
				() -> Assertions.assertTrue(source.contains("diskCache.pathForPng(contentHash)")),
				() -> Assertions.assertTrue(source.contains("RouteAssetHash.sha256(bytes).equals(contentHash)")),
				() -> Assertions.assertFalse(source.contains("MessageQueue"), "server route assets must not reuse the legacy per-frame MessageQueue")
		);
	}

	@Test
	public void clientSignedIdsDoNotUseNegativeSentinels() throws IOException {
		final String adapter = readSource("client", "RouteAssetClientSnapshotAdapter.java");
		final String manager = readSource("client", "asset", "ClientRouteAssetManager.java");

		Assertions.assertFalse(adapter.contains("found < 0"), "negative route and platform IDs are valid in MTR Core");
		Assertions.assertFalse(manager.contains("nearestPlatformId[0] < 0"), "negative platform IDs must still be prewarmed");
	}

	@Test
	public void changedClientDataLazilyInvalidatesRouteDependencies() throws IOException {
		final String source = readSource("packet", "PacketRequestData.java");
		final int write = source.indexOf("new DataResponse(jsonReader, clientData).write();");
		final int invalidate = source.indexOf("DynamicTextureCache.instance.onRouteDataChanged();", write);

		Assertions.assertTrue(write >= 0 && invalidate > write);
		Assertions.assertTrue(source.contains("iterateReaderArray(\"stations\""));
		Assertions.assertTrue(source.contains("iterateReaderArray(\"platforms\""));
		Assertions.assertTrue(source.contains("iterateReaderArray(\"simplifiedRoutes\""));
		Assertions.assertTrue(source.contains("routeDataReceived[0] ||"), "same-sized route edits must not retain stale textures");
	}

	private static String readSource(String... pathParts) throws IOException {
		Path path = Path.of("src", "main", "java", "org", "mtr", "mod");
		for (final String pathPart : pathParts) path = path.resolve(pathPart);
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		Assertions.assertTrue(Files.exists(path), "source must be available: " + path);
		return Files.readString(path);
	}
}
