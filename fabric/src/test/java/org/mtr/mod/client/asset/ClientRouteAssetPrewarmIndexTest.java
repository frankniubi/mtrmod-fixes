package org.mtr.mod.client.asset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetManifest;
import org.mtr.mod.route.RouteAssetType;

import java.util.List;

public final class ClientRouteAssetPrewarmIndexTest {

	@Test
	public void indexContainsOnlyBoundedCurrentVariantPlatformAssets() {
		final RouteAssetManifest.Builder builder = RouteAssetManifest.builder();
		for (int index = 0; index < 12; index++) {
			final RouteAssetType type = index == 11 ? RouteAssetType.ROUTE_SQUARE : index % 3 == 0 ? RouteAssetType.ROUTE_MAP : index % 3 == 1 ? RouteAssetType.DIRECTION_ARROW : RouteAssetType.ROUTE_COLOR_STRIP;
			builder.put(key("minecraft/overworld", type, 20, 2, "NORMAL", index), hash(index), "dependency-" + index);
		}
		builder.put(key("minecraft/overworld", RouteAssetType.ROUTE_MAP, 20, 3, "NORMAL", 20), hash(20), "wrong-resolution");
		builder.put(key("minecraft/overworld", RouteAssetType.ROUTE_MAP, 20, 2, "CJK", 21), hash(21), "wrong-language");
		builder.put(key("minecraft/the_nether", RouteAssetType.ROUTE_MAP, 20, 2, "NORMAL", 22), hash(22), "other-dimension");

		final ClientRouteAssetManager.PrewarmIndex index = ClientRouteAssetManager.buildPrewarmIndex(builder.build(), 2, "NORMAL");
		final List<RouteAssetKey> overworld = index.find("minecraft/overworld", 20);
		Assertions.assertEquals(8, overworld.size(), "one nearby platform may prewarm at most eight logical assets");
		Assertions.assertTrue(overworld.stream().noneMatch(key -> key.getType() == RouteAssetType.ROUTE_SQUARE));
		Assertions.assertTrue(overworld.stream().allMatch(key -> key.getVariant().getResolution() == 2 && key.getVariant().getLanguage().equals("NORMAL")));
		Assertions.assertEquals(1, index.find("minecraft/the_nether", 20).size());
		Assertions.assertTrue(index.find("minecraft/overworld", 999).isEmpty());
	}

	private static RouteAssetKey key(String dimension, RouteAssetType type, long platformId, int resolution, String language, int suffix) {
		return RouteAssetKey.parse(dimension + '|' + type + '|' + platformId + '|' + resolution + '|' + language + "|style=V" + suffix);
	}

	private static String hash(int value) {
		return String.format("%064x", value + 1);
	}
}
