package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mtr.core.data.ClientData;
import org.mtr.core.operation.DeleteDataResponse;
import org.mtr.core.operation.ListDataResponse;
import org.mtr.core.operation.UpdateDataResponse;
import org.mtr.core.tool.Utilities;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class RouteAssetDataMirrorTest {

	@Test
	public void dimensionsMayArriveOutOfOrderAndStaleGenerationsAreRejected() {
		final RouteAssetDataMirror mirror = new RouteAssetDataMirror();
		final long firstGeneration = mirror.beginGeneration(Set.of("minecraft/overworld", "minecraft/the_nether"));
		Assertions.assertTrue(mirror.acceptDimension(firstGeneration, dimension("minecraft/the_nether", platform(2, "Nether"))));
		Assertions.assertTrue(mirror.snapshot(firstGeneration).isEmpty());
		Assertions.assertTrue(mirror.acceptDimension(firstGeneration, dimension("minecraft/overworld", platform(1, "Overworld"))));
		Assertions.assertEquals(Set.of("minecraft/overworld", "minecraft/the_nether"), mirror.snapshot(firstGeneration).orElseThrow().getDimensions().keySet());

		final long secondGeneration = mirror.beginGeneration(Set.of("minecraft/overworld"));
		Assertions.assertFalse(mirror.acceptDimension(firstGeneration, dimension("minecraft/overworld", platform(99, "Stale"))));
		Assertions.assertTrue(mirror.acceptDimension(secondGeneration, dimension("minecraft/overworld", platform(3, "Fresh"))));
		Assertions.assertTrue(mirror.snapshot(firstGeneration).isEmpty());
	}

	@Test
	public void coreJsonUpdatesAndDeletesAdvanceTheDimensionEpoch() throws Exception {
		final RouteAssetDataMirror mirror = new RouteAssetDataMirror();
		final long generation = mirror.beginGeneration(Set.of("minecraft/overworld"));
		final ClientData initialData = new ClientData();
		Assertions.assertTrue(mirror.acceptListJson(generation, "minecraft/overworld", Utilities.getJsonObjectFromData(new ListDataResponse(initialData).list())));
		final long initialEpoch = mirror.snapshot(generation).orElseThrow().getDimensions().get("minecraft/overworld").getEpoch();
		Assertions.assertTrue(mirror.applyUpdateJson("minecraft/overworld", Utilities.getJsonObjectFromData(new UpdateDataResponse(new ClientData()))));
		Assertions.assertTrue(mirror.applyDeleteJson("minecraft/overworld", Utilities.getJsonObjectFromData(new DeleteDataResponse())));
		Assertions.assertTrue(mirror.currentSnapshot().getDimensions().get("minecraft/overworld").getEpoch() >= initialEpoch + 2);
	}

	@Test
	public void directMutationsProduceImmutableWorkerSnapshotsAndTombstones() {
		final RouteAssetDataMirror mirror = new RouteAssetDataMirror();
		final long generation = mirror.beginGeneration(Set.of("minecraft/overworld"));
		mirror.acceptDimension(generation, dimension("minecraft/overworld", platform(1, "One")));
		mirror.applyPlatformUpdate("minecraft/overworld", platform(2, "Two"));
		final RouteAssetDataMirror.Snapshot beforeDelete = mirror.currentSnapshot();
		mirror.applyPlatformDelete("minecraft/overworld", 1);
		Assertions.assertEquals(Set.of(1L, 2L), beforeDelete.getDimensions().get("minecraft/overworld").getPlatforms().keySet());
		Assertions.assertEquals(Set.of(2L), mirror.currentSnapshot().getDimensions().get("minecraft/overworld").getPlatforms().keySet());
		Assertions.assertThrows(UnsupportedOperationException.class, () -> beforeDelete.getDimensions().clear());
	}

	@Test
	public void catalogEnumeratesFixedResolutionsAndStableFingerprints() {
		final RouteAssetDataMirror.Snapshot snapshot = snapshot(dimension("minecraft/overworld", platform(7, "Central")));
		final RouteAssetDependencyCatalog catalog = new RouteAssetDependencyCatalog();
		final String resourceFingerprint = "f".repeat(64);
		final Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> first = catalog.enumerateFixed(snapshot, resourceFingerprint, "NORMAL");
		final Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> second = catalog.enumerateFixed(snapshot, resourceFingerprint, "NORMAL");
		Assertions.assertEquals(16, first.size());
		Assertions.assertEquals(Set.of(0, 1, 2, 3), first.keySet().stream().map(key -> key.getVariant().getResolution()).collect(Collectors.toSet()));
		Assertions.assertEquals(first, second);
		Assertions.assertEquals(Set.of(RouteAssetType.ROUTE_MAP, RouteAssetType.DIRECTION_ARROW, RouteAssetType.ROUTE_COLOR_STRIP, RouteAssetType.ROUTE_SQUARE), first.keySet().stream().map(RouteAssetKey::getType).collect(Collectors.toSet()));
	}

	@Test
	public void observedKeysRejectArbitraryParametersAndAspects() {
		final RouteAssetDataMirror.Snapshot snapshot = snapshot(dimension("minecraft/overworld", platform(7, "Central")));
		final RouteAssetDependencyCatalog catalog = new RouteAssetDependencyCatalog();
		final String fingerprint = "e".repeat(64);
		final RouteAssetKey valid = RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|7|2|CJK|a=4:9,f=0,t=0,v=1");
		Assertions.assertTrue(catalog.resolveObserved(valid, snapshot, fingerprint).isPresent());
		Assertions.assertTrue(catalog.resolveObserved(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|7|2|CJK|a=99:1,f=0,t=0,v=1"), snapshot, fingerprint).isEmpty());
		Assertions.assertTrue(catalog.resolveObserved(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|7|2|CJK|text=arbitrary"), snapshot, fingerprint).isEmpty());
		Assertions.assertTrue(catalog.resolveObserved(RouteAssetKey.parse("minecraft/unknown|ROUTE_MAP|7|2|CJK|a=4:9,f=0,t=0,v=1"), snapshot, fingerprint).isEmpty());
		Assertions.assertThrows(IllegalArgumentException.class, () -> RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|7|4|CJK|a=4:9,f=0,t=0,v=1"));
	}

	private static RouteAssetDataMirror.Snapshot snapshot(RouteAssetDataMirror.DimensionSnapshot dimension) {
		return new RouteAssetDataMirror.Snapshot(1, Map.of(dimension.getDimension(), dimension));
	}

	private static RouteAssetDataMirror.DimensionSnapshot dimension(String id, RouteAssetDataMirror.PlatformSnapshot... platforms) {
		return new RouteAssetDataMirror.DimensionSnapshot(id, 1, java.util.Arrays.stream(platforms).collect(Collectors.toMap(RouteAssetDataMirror.PlatformSnapshot::getId, platform -> platform)));
	}

	private static RouteAssetDataMirror.PlatformSnapshot platform(long id, String name) {
		final List<RouteAssetRenderSnapshot.Station> stations = List.of(
				new RouteAssetRenderSnapshot.Station(id * 10 + 1, name + " A", false, true, false, false),
				new RouteAssetRenderSnapshot.Station(id * 10 + 2, name + " B", false, false, true, false)
		);
		final RouteAssetRenderSnapshot.Route route = new RouteAssetRenderSnapshot.Route(id * 100, "R" + id, 0x14755E, stations);
		return new RouteAssetDataMirror.PlatformSnapshot(id, name, List.of(route));
	}
}
