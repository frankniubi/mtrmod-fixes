package org.mtr.mod.client.asset;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.RouteAssetCanonicalKeyFactory;
import org.mtr.mod.route.RouteAssetKey;
import org.mtr.mod.route.RouteAssetTextRasterizer;
import org.mtr.mod.route.RouteAssetVariantPolicy;

public final class RouteAssetVariantPolicyTest {

	@Test
	public void normalClientsActivateExactLanguagePlusMultilingualDestinationAtlases() {
		final RouteAssetKey normal = RouteAssetCanonicalKeyFactory.routeSquare("minecraft/overworld", 1, 2, "NORMAL", RouteAssetTextRasterizer.Alignment.LEFT);
		final RouteAssetKey cjk = RouteAssetCanonicalKeyFactory.routeSquare("minecraft/overworld", 1, 2, "CJK", RouteAssetTextRasterizer.Alignment.LEFT);
		final RouteAssetKey multi = RouteAssetCanonicalKeyFactory.destinationSign("minecraft/overworld", -10, -30, 2, DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);
		final RouteAssetKey otherResolution = RouteAssetCanonicalKeyFactory.destinationSign("minecraft/overworld", -10, -30, 1, DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true);

		Assertions.assertTrue(RouteAssetVariantPolicy.isActive(normal, 2, "NORMAL"));
		Assertions.assertTrue(RouteAssetVariantPolicy.isActive(multi, 2, "NORMAL"));
		Assertions.assertFalse(RouteAssetVariantPolicy.isActive(cjk, 2, "NORMAL"));
		Assertions.assertFalse(RouteAssetVariantPolicy.isActive(otherResolution, 2, "NORMAL"));
	}
}
