package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.mapping.holder.CompoundTag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

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

	@Test
	public void routeAndDestinationEntriesShareOneTaggedPersistentIndex() {
		final ConfiguredSignAssetIndex index = new ConfiguredSignAssetIndex();
		final DestinationSignConfiguredEntry destination = new DestinationSignConfiguredEntry(-10, Set.of(-30L, -40L), "Custom|Header",
				3, 2, DestinationSignStyle.PLATFORM_GROUPS, true);
		index.configureRouteSign(1, 20, RouteSignStyleMode.RAILWAY);
		index.configureDestinationSign(2, destination);
		index.configureDestinationSign(3, destination);
		final CompoundTag tag = new CompoundTag();
		index.write(tag);

		final ConfiguredSignAssetIndex restored = new ConfiguredSignAssetIndex();
		restored.read(tag);
		final List<ConfiguredSignAssetIndex.Entry> entries = restored.snapshot("minecraft/overworld");
		Assertions.assertEquals(List.of(ConfiguredSignAssetIndex.SignType.ROUTE_SIGN, ConfiguredSignAssetIndex.SignType.DESTINATION_SIGN, ConfiguredSignAssetIndex.SignType.DESTINATION_SIGN), entries.stream().map(ConfiguredSignAssetIndex.Entry::getType).toList());
		Assertions.assertEquals(destination, entries.get(1).getDestinationSign());
		Assertions.assertEquals(entries.get(1).canonicalAssetIdentity(), entries.get(2).canonicalAssetIdentity());

		Assertions.assertTrue(restored.configureDestinationSign(1, destination), "an anchor may atomically change sign type");
		Assertions.assertTrue(restored.snapshot("minecraft/overworld").get(0).isDestinationSign());
	}

	@Test
	public void destinationDensityPersistsDefaultsAndSeparatesIdentity() {
		final DestinationSignConfiguredEntry dense = new DestinationSignConfiguredEntry(-10, Set.of(-30L), "",
				3, 2, DestinationSignStyle.ARRIVAL_ORDER, true, 4);
		final DestinationSignConfiguredEntry defaulted = new DestinationSignConfiguredEntry(-10, Set.of(-30L), "",
				3, 2, DestinationSignStyle.ARRIVAL_ORDER, true);
		final ConfiguredSignAssetIndex index = new ConfiguredSignAssetIndex();
		index.configureDestinationSign(1, dense);
		index.configureDestinationSign(2, defaulted);
		final List<ConfiguredSignAssetIndex.Entry> before = index.snapshot("minecraft/overworld");
		Assertions.assertNotEquals(before.get(0).canonicalAssetIdentity(), before.get(1).canonicalAssetIdentity());

		final CompoundTag tag = new CompoundTag();
		index.write(tag);
		Assertions.assertEquals(4, tag.getLong("configured_sign_asset_0_routes_per_block_height"));
		Assertions.assertEquals(3, tag.getLong("configured_sign_asset_1_routes_per_block_height"));
		final ConfiguredSignAssetIndex restored = new ConfiguredSignAssetIndex();
		restored.read(tag);
		Assertions.assertEquals(before, restored.snapshot("minecraft/overworld"));

		final CompoundTag legacy = new CompoundTag();
		legacy.putLong("configured_sign_asset_count", 1);
		legacy.putString("configured_sign_asset_0_type", "DESTINATION_SIGN");
		legacy.putLong("configured_sign_asset_0_anchor", 9);
		legacy.putLong("configured_sign_asset_0_source_id", -10);
		legacy.putLong("configured_sign_asset_0_destination_id", -30);
		legacy.putLong("configured_sign_asset_0_width", 3);
		legacy.putLong("configured_sign_asset_0_height", 2);
		legacy.putString("configured_sign_asset_0_style", DestinationSignStyle.ARRIVAL_ORDER.name());
		legacy.putLong("configured_sign_asset_0_eta", 1);
		final ConfiguredSignAssetIndex legacyIndex = new ConfiguredSignAssetIndex();
		legacyIndex.read(legacy);
		Assertions.assertEquals(3, legacyIndex.snapshot("minecraft/overworld").get(0).getDestinationSign().getRoutesPerBlockHeight());
	}

	@Test
	public void rewritingEntryClearsStaleDensity() {
		final CompoundTag tag = new CompoundTag();
		tag.putLong("configured_sign_asset_count", 1);
		tag.putLong("configured_sign_asset_0_routes_per_block_height", 4);
		final ConfiguredSignAssetIndex index = new ConfiguredSignAssetIndex();
		index.configureRouteSign(1, 20, RouteSignStyleMode.RAILWAY);
		index.write(tag);
		Assertions.assertFalse(tag.contains("configured_sign_asset_0_routes_per_block_height"));
	}

	@Test
	public void destinationIdentityIsOrderIndependentAndIncludesCustomHeader() {
		final DestinationSignConfiguredEntry first = new DestinationSignConfiguredEntry(-10, Set.of(-20L, -30L), "A|B",
				3, 2, DestinationSignStyle.ARRIVAL_ORDER, true);
		final DestinationSignConfiguredEntry reordered = new DestinationSignConfiguredEntry(-10, Set.of(-30L, -20L), "A|B",
				3, 2, DestinationSignStyle.ARRIVAL_ORDER, true);
		final DestinationSignConfiguredEntry otherHeader = new DestinationSignConfiguredEntry(-10, Set.of(-30L, -20L), "Other|Header",
				3, 2, DestinationSignStyle.ARRIVAL_ORDER, true);
		final ConfiguredSignAssetIndex index = new ConfiguredSignAssetIndex();
		index.configureDestinationSign(1, first);
		index.configureDestinationSign(2, reordered);
		index.configureDestinationSign(3, otherHeader);
		final List<ConfiguredSignAssetIndex.Entry> entries = index.snapshot("minecraft/overworld");
		Assertions.assertEquals(entries.get(0).canonicalAssetIdentity(), entries.get(1).canonicalAssetIdentity());
		Assertions.assertNotEquals(entries.get(0).canonicalAssetIdentity(), entries.get(2).canonicalAssetIdentity());
		final String tooManySegments = String.join("|", java.util.Collections.nCopies(
				RouteAssetProtocol.MAX_DESTINATION_SIGN_PIPE_SEGMENTS + 1, "x"));
		Assertions.assertThrows(IllegalArgumentException.class, () -> new DestinationSignConfiguredEntry(
				-10, Set.of(-20L), tooManySegments, 3, 2, DestinationSignStyle.ARRIVAL_ORDER, true));
	}

	@Test
	public void routeSignIdentityAndPersistenceIncludeEverySortedPlatformAndHeader() {
		final ConfiguredSignAssetIndex index = new ConfiguredSignAssetIndex();
		Assertions.assertTrue(index.configureRouteSign(1, Set.of(30L, 10L, 20L), RouteSignStyleMode.RAILWAY, "Custom|Header"));
		Assertions.assertFalse(index.configureRouteSign(1, Set.of(20L, 30L, 10L), RouteSignStyleMode.RAILWAY, "Custom|Header"));
		Assertions.assertTrue(index.configureRouteSign(2, Set.of(20L, 10L, 30L), RouteSignStyleMode.RAILWAY, "Custom|Header"));
		Assertions.assertTrue(index.configureRouteSign(3, Set.of(10L, 20L, 30L), RouteSignStyleMode.RAILWAY, "Other|Header"));

		final List<ConfiguredSignAssetIndex.Entry> before = index.snapshot("minecraft/overworld");
		Assertions.assertEquals(Set.of(10L, 20L, 30L), before.get(0).getPlatformIds());
		Assertions.assertEquals(10, before.get(0).getPrimaryId());
		Assertions.assertEquals("Custom|Header", before.get(0).getCustomPlatformHeader());
		Assertions.assertEquals(before.get(0).canonicalAssetIdentity(), before.get(1).canonicalAssetIdentity());
		Assertions.assertNotEquals(before.get(0).canonicalAssetIdentity(), before.get(2).canonicalAssetIdentity());

		final CompoundTag tag = new CompoundTag();
		index.write(tag);
		final ConfiguredSignAssetIndex restored = new ConfiguredSignAssetIndex();
		restored.read(tag);
		Assertions.assertEquals(before, restored.snapshot("minecraft/overworld"));
	}

	@Test
	public void serverChunkLoadReconcilesOnlyIndexedAnchors() throws Exception {
		final String index = Files.readString(sourcePath("route", "ConfiguredSignAssetIndex.java"));
		Assertions.assertTrue(index.contains("anchorsByChunk"));
		Assertions.assertTrue(index.contains("reconcileChunk(WorldChunk chunk)"));
		Assertions.assertTrue(index.contains("anchorsByChunk.get(chunk.getPos().toLong())"));

		final String init = Files.readString(sourcePath("", "Init.java"));
		Assertions.assertTrue(init.contains("eventRegistry.registerChunkLoad("));
		Assertions.assertTrue(init.contains("persistentState.reconcileConfiguredSigns(worldChunk)"));
		Assertions.assertTrue(init.contains("configured-sign-chunk-reconcile"));
		final String persistentState = Files.readString(sourcePath("data", "PersistentStateData.java"));
		Assertions.assertTrue(persistentState.contains("configuredSignAssetIndex.reconcileChunk(chunk)"));
		Assertions.assertTrue(persistentState.contains("if (changed) markDirty2();"));
	}

	private static Path sourcePath(String packageName, String fileName) {
		Path path = Path.of("src", "main", "java", "org", "mtr", "mod");
		if (!packageName.isEmpty()) path = path.resolve(packageName);
		path = path.resolve(fileName);
		if (!Files.exists(path)) path = Path.of("fabric").resolve(path);
		return path;
	}
}
