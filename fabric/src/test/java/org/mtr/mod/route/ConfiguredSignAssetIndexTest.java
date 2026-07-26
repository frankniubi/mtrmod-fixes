package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.CompoundTag;

import java.util.List;

public final class ConfiguredSignAssetIndexTest {

	@Test
	public void explicitRouteSignsRoundTripAndAutoRemovesTheAnchor() {
		final ConfiguredSignAssetIndex index = new ConfiguredSignAssetIndex();
		Assertions.assertTrue(index.configureRouteSign(30, 300, RouteSignStyleMode.NORMAL));
		Assertions.assertTrue(index.configureRouteSign(10, 100, RouteSignStyleMode.RAILWAY));
		Assertions.assertFalse(index.configureRouteSign(10, 100, RouteSignStyleMode.RAILWAY));

		final List<ConfiguredSignAssetIndex.Entry> before = index.snapshot("minecraft/overworld");
		Assertions.assertEquals(List.of(10L, 30L), before.stream().map(ConfiguredSignAssetIndex.Entry::getAnchorPosition).toList());
		Assertions.assertEquals(RouteSignStyleMode.RAILWAY, before.get(0).getRouteSignStyleMode());
		Assertions.assertEquals(100, before.get(0).getPrimaryId());

		final CompoundTag tag = new CompoundTag();
		index.write(tag);
		final ConfiguredSignAssetIndex restored = new ConfiguredSignAssetIndex();
		restored.read(tag);
		Assertions.assertEquals(before, restored.snapshot("minecraft/overworld"));

		Assertions.assertTrue(restored.configureRouteSign(10, 100, RouteSignStyleMode.AUTO));
		Assertions.assertEquals(List.of(30L), restored.snapshot("minecraft/overworld").stream().map(ConfiguredSignAssetIndex.Entry::getAnchorPosition).toList());
		Assertions.assertFalse(restored.configureRouteSign(10, 100, RouteSignStyleMode.AUTO));
	}

	@Test
	public void snapshotAddsWorldIdentityAndDuplicateCanonicalAssetsRemainDistinctAnchors() {
		final ConfiguredSignAssetIndex index = new ConfiguredSignAssetIndex();
		index.configureRouteSign(1, 20, RouteSignStyleMode.RAILWAY);
		index.configureRouteSign(2, 20, RouteSignStyleMode.RAILWAY);

		final List<ConfiguredSignAssetIndex.Entry> entries = index.snapshot("minecraft/the_nether");
		Assertions.assertEquals(2, entries.size());
		Assertions.assertTrue(entries.stream().allMatch(entry -> entry.getDimension().equals("minecraft/the_nether")));
		Assertions.assertEquals(entries.get(0).canonicalAssetIdentity(), entries.get(1).canonicalAssetIdentity());
	}
}
