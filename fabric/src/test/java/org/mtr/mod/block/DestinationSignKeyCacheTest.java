package org.mtr.mod.block;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mod.route.DestinationSignStyle;
import org.mtr.mod.route.RouteAssetCanonicalKeyFactory;
import org.mtr.mod.route.RouteAssetKey;

import java.nio.file.Files;
import java.nio.file.Path;

public final class DestinationSignKeyCacheTest {

	private static final String DIMENSION = "minecraft/overworld";

	@Test
	public void reusesKeyForSameAndSemanticallyEqualInputs() {
		final BlockDestinationSign.DestinationSignKeyCache cache = new BlockDestinationSign.DestinationSignKeyCache();
		final DestinationSignConfig first = config(DestinationSignStyle.ARRIVAL_ORDER, true);
		final RouteAssetKey key = cache.get(first, DIMENSION, 1);

		Assertions.assertSame(key, cache.get(first, DIMENSION, 1));
		Assertions.assertSame(key, cache.get(config(DestinationSignStyle.ARRIVAL_ORDER, true), DIMENSION, 1));
	}

	@Test
	public void invalidatesForEveryCanonicalInput() {
		final BlockDestinationSign.DestinationSignKeyCache cache = new BlockDestinationSign.DestinationSignKeyCache();
		final DestinationSignConfig config = config(DestinationSignStyle.ARRIVAL_ORDER, true);
		final RouteAssetKey first = cache.get(config, DIMENSION, 1);
		final RouteAssetKey changedConfig = cache.get(config(DestinationSignStyle.PLATFORM_GROUPS, true), DIMENSION, 1);
		final RouteAssetKey changedDimension = cache.get(config, "minecraft/the_nether", 1);
		final RouteAssetKey changedResolution = cache.get(config, DIMENSION, 2);

		Assertions.assertNotSame(first, changedConfig);
		Assertions.assertNotSame(changedConfig, changedDimension);
		Assertions.assertNotSame(changedDimension, changedResolution);
		Assertions.assertEquals(RouteAssetCanonicalKeyFactory.destinationSign(DIMENSION, -100, -200, 2,
				DestinationSignStyle.ARRIVAL_ORDER, 3, 2, true), changedResolution);
	}

	@Test
	public void failedRebuildDoesNotReplaceLastValidEntry() {
		final BlockDestinationSign.DestinationSignKeyCache cache = new BlockDestinationSign.DestinationSignKeyCache();
		final DestinationSignConfig config = config(DestinationSignStyle.ARRIVAL_ORDER, true);
		final RouteAssetKey valid = cache.get(config, DIMENSION, 1);

		Assertions.assertThrows(IllegalArgumentException.class, () -> cache.get(config, "../invalid", 1));
		Assertions.assertSame(valid, cache.get(config, DIMENSION, 1));
	}

	@Test
	public void rendererDelegatesDimensionAndKeyCachingToTheBlockEntity() throws Exception {
		Path path = Path.of("src", "main", "java", "org", "mtr", "mod", "render", "RenderDestinationSign.java");
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		final String source = Files.readString(path);
		Assertions.assertTrue(source.contains("entity.getCachedKey(currentResolution())"));
		Assertions.assertFalse(source.contains("entity.getCachedKey(Init.getWorldId(world)"));
	}

	private static DestinationSignConfig config(DestinationSignStyle style, boolean showEta) {
		return DestinationSignConfig.configured(-100, -200, 3, 2, style, showEta);
	}
}
