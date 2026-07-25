package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public final class RouteAssetCanonicalKeyFactoryTest {

	@Test
	public void routeSignRatiosAndArrowColorsHaveReadableCanonicalKeys() {
		final RouteAssetKey map = RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", 7, 2, "normal", true, false, 37F / 22, false);
		Assertions.assertEquals("minecraft/overworld|ROUTE_MAP|7|2|NORMAL|a=37:22,f=0,t=0,v=1", map.toString());

		final RouteAssetKey arrow = RouteAssetCanonicalKeyFactory.directionArrow("minecraft/overworld", 7, 2, "normal", true, false, RouteAssetTextRasterizer.Alignment.CENTER, true, 0.2F, 22F / 5, 0xFF000000, 0xFFFFFFFF, 0);
		Assertions.assertEquals("minecraft/overworld|DIRECTION_ARROW|7|2|NORMAL|a=22:5,align=CENTER,bg=FF000000,left=1,pad=1:5,right=0,show=1,text=FFFFFFFF,transparent=00000000", arrow.toString());
	}

	@Test
	public void signedCoreIdsRoundTripThroughCanonicalKeys() {
		final long platformId = Long.MIN_VALUE + 17;
		final RouteAssetKey key = RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", platformId, 2, "NORMAL", true, false, 37F / 22, false);

		Assertions.assertEquals(platformId, key.getPrimaryId());
		Assertions.assertEquals(key, RouteAssetKey.parse(key.toString()));
		Assertions.assertEquals(key, key.withPrimaryId(platformId));
	}

	@Test
	public void everyPixelAffectingArrowInputChangesTheKey() {
		final RouteAssetKey base = arrow(false, false, RouteAssetTextRasterizer.Alignment.LEFT, false, 0.125F, 2, 0xFF010203, 0xFF040506, 0);
		Assertions.assertNotEquals(base, arrow(true, false, RouteAssetTextRasterizer.Alignment.LEFT, false, 0.125F, 2, 0xFF010203, 0xFF040506, 0));
		Assertions.assertNotEquals(base, arrow(false, true, RouteAssetTextRasterizer.Alignment.LEFT, false, 0.125F, 2, 0xFF010203, 0xFF040506, 0));
		Assertions.assertNotEquals(base, arrow(false, false, RouteAssetTextRasterizer.Alignment.LEFT, true, 0.125F, 2, 0xFF010203, 0xFF040506, 0));
		Assertions.assertNotEquals(base, arrow(false, false, RouteAssetTextRasterizer.Alignment.LEFT, false, 0.25F, 2, 0xFF010203, 0xFF040506, 0));
		Assertions.assertNotEquals(base, arrow(false, false, RouteAssetTextRasterizer.Alignment.LEFT, false, 0.125F, 3, 0xFF010203, 0xFF040506, 0));
		Assertions.assertNotEquals(base, arrow(false, false, RouteAssetTextRasterizer.Alignment.RIGHT, false, 0.125F, 2, 0xFF010203, 0xFF040506, 0));
		Assertions.assertNotEquals(base, arrow(false, false, RouteAssetTextRasterizer.Alignment.LEFT, false, 0.125F, 2, 0xFF010204, 0xFF040506, 0));
		Assertions.assertNotEquals(base, arrow(false, false, RouteAssetTextRasterizer.Alignment.LEFT, false, 0.125F, 2, 0xFF010203, 0xFF040507, 0));
		Assertions.assertNotEquals(base, arrow(false, false, RouteAssetTextRasterizer.Alignment.LEFT, false, 0.125F, 2, 0xFF010203, 0xFF040506, 0xFF010203));
	}

	@Test
	public void numericInputsAreBoundedAndNonCanonicalAliasesAreRejected() {
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", 7, 2, "NORMAL", true, false, 0, false));
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", 7, 2, "NORMAL", true, false, 9, false));
		Assertions.assertThrows(IllegalArgumentException.class, () -> arrow(false, false, RouteAssetTextRasterizer.Alignment.CENTER, true, 0.5F, 2, 0, 0, 0));
		Assertions.assertThrows(IllegalArgumentException.class, () -> arrow(false, false, RouteAssetTextRasterizer.Alignment.CENTER, true, Float.NaN, 2, 0, 0, 0));
	}

	private static RouteAssetKey arrow(boolean left, boolean right, RouteAssetTextRasterizer.Alignment alignment, boolean show, float padding, float aspect, int background, int text, int transparent) {
		return RouteAssetCanonicalKeyFactory.directionArrow("minecraft/overworld", 7, 2, "NORMAL", left, right, alignment, show, padding, aspect, background, text, transparent);
	}
}
