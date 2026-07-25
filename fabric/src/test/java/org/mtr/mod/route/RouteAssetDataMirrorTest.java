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
		Assertions.assertEquals(32, first.size());
		Assertions.assertEquals(Set.of(0, 1, 2, 3), first.keySet().stream().map(key -> key.getVariant().getResolution()).collect(Collectors.toSet()));
		Assertions.assertEquals(first, second);
		Assertions.assertEquals(Set.of(RouteAssetType.ROUTE_MAP, RouteAssetType.DIRECTION_ARROW, RouteAssetType.ROUTE_COLOR_STRIP, RouteAssetType.ROUTE_SQUARE), first.keySet().stream().map(RouteAssetKey::getType).collect(Collectors.toSet()));
		for (int resolution = 0; resolution <= 3; resolution++) {
			Assertions.assertTrue(first.containsKey(RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", 7, resolution, "NORMAL", true, false, 37F / 22, false)));
			for (int direction = 0; direction <= 3; direction++) {
				Assertions.assertTrue(first.containsKey(RouteAssetCanonicalKeyFactory.directionArrow("minecraft/overworld", 7, resolution, "NORMAL", (direction & 1) != 0, (direction & 2) != 0, RouteAssetTextRasterizer.Alignment.CENTER, true, 0.2F, 22F / 5, 0xFF000000, 0xFFFFFFFF, 0)));
			}
			Assertions.assertTrue(first.containsKey(RouteAssetCanonicalKeyFactory.routeSquare("minecraft/overworld", 700, resolution, "NORMAL", RouteAssetTextRasterizer.Alignment.LEFT)));
			Assertions.assertTrue(first.containsKey(RouteAssetCanonicalKeyFactory.routeSquare("minecraft/overworld", 700, resolution, "NORMAL", RouteAssetTextRasterizer.Alignment.RIGHT)));
		}
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

	@Test
	public void observedKeysReconstructEveryMapAndArrowRenderParameter() {
		final RouteAssetDataMirror.Snapshot snapshot = snapshot(dimension("minecraft/overworld", platform(7, "Central")));
		final RouteAssetDependencyCatalog catalog = new RouteAssetDependencyCatalog();
		final String fingerprint = "d".repeat(64);
		final RouteAssetKey map = RouteAssetCanonicalKeyFactory.routeMap("minecraft/overworld", 7, 2, "NORMAL", false, true, 3F / 2, true);
		final RouteAssetRenderSnapshot mapSnapshot = catalog.resolveObserved(map, snapshot, fingerprint).orElseThrow().getSnapshot();
		Assertions.assertFalse(mapSnapshot.isVertical());
		Assertions.assertTrue(mapSnapshot.isFlip());
		Assertions.assertEquals(1.5F, mapSnapshot.getAspectRatio());
		Assertions.assertEquals(0xFFFFFFFF, mapSnapshot.getTransparentColor());

		final RouteAssetKey arrow = RouteAssetCanonicalKeyFactory.directionArrow("minecraft/overworld", 7, 2, "NORMAL", false, true, RouteAssetTextRasterizer.Alignment.RIGHT, false, 0.25F, 3F / 2, 0x7F010203, 0xFFABCDEF, 0xFF010203);
		final RouteAssetRenderSnapshot arrowSnapshot = catalog.resolveObserved(arrow, snapshot, fingerprint).orElseThrow().getSnapshot();
		Assertions.assertFalse(arrowSnapshot.hasLeft());
		Assertions.assertTrue(arrowSnapshot.hasRight());
		Assertions.assertFalse(arrowSnapshot.isShowToString());
		Assertions.assertEquals(0.25F, arrowSnapshot.getPaddingScale());
		Assertions.assertEquals(1.5F, arrowSnapshot.getAspectRatio());
		Assertions.assertEquals(0x7F010203, arrowSnapshot.getBackgroundColor());
		Assertions.assertEquals(0xFFABCDEF, arrowSnapshot.getTextColor());
		Assertions.assertEquals(0xFF010203, arrowSnapshot.getTransparentColor());
	}

	@Test
	public void observedKeysRejectMissingOrNonCanonicalPixelParameters() {
		final RouteAssetDataMirror.Snapshot snapshot = snapshot(dimension("minecraft/overworld", platform(7, "Central")));
		final RouteAssetDependencyCatalog catalog = new RouteAssetDependencyCatalog();
		final String fingerprint = "c".repeat(64);
		Assertions.assertTrue(catalog.resolveObserved(RouteAssetKey.parse("minecraft/overworld|DIRECTION_ARROW|7|2|NORMAL|a=3:2,align=RIGHT,bg=FF010203,pad=1:4,right=1,show=0,text=FFFFFFFF,transparent=00000000"), snapshot, fingerprint).isEmpty());
		Assertions.assertTrue(catalog.resolveObserved(RouteAssetKey.parse("minecraft/overworld|DIRECTION_ARROW|7|2|NORMAL|a=3:2,align=RIGHT,bg=ff010203,left=0,pad=1:4,right=1,show=0,text=FFFFFFFF,transparent=00000000"), snapshot, fingerprint).isEmpty());
		Assertions.assertTrue(catalog.resolveObserved(RouteAssetKey.parse("minecraft/overworld|DIRECTION_ARROW|7|2|NORMAL|a=3:2,align=RIGHT,bg=FF010203,left=0,pad=1:2,right=1,show=0,text=FFFFFFFF,transparent=00000000"), snapshot, fingerprint).isEmpty());
		Assertions.assertTrue(catalog.resolveObserved(RouteAssetKey.parse("minecraft/overworld|ROUTE_MAP|7|2|NORMAL|a=2:4,f=1,t=1,v=0"), snapshot, fingerprint).isEmpty());
	}

	@Test
	public void dependencyFingerprintCoversCanonicalKeyAndAuthoritativeData() {
		final RouteAssetDependencyCatalog catalog = new RouteAssetDependencyCatalog();
		final String fingerprint = "b".repeat(64);
		final RouteAssetDataMirror.Snapshot firstData = snapshot(dimension("minecraft/overworld", platform(7, "Central")));
		final RouteAssetKey firstKey = RouteAssetCanonicalKeyFactory.directionArrow("minecraft/overworld", 7, 2, "NORMAL", true, false, RouteAssetTextRasterizer.Alignment.CENTER, true, 0.2F, 22F / 5, 0xFF000000, 0xFFFFFFFF, 0);
		final RouteAssetKey changedKey = RouteAssetCanonicalKeyFactory.directionArrow("minecraft/overworld", 7, 2, "NORMAL", true, false, RouteAssetTextRasterizer.Alignment.CENTER, true, 0.2F, 22F / 5, 0xFF000001, 0xFFFFFFFF, 0);
		final String first = catalog.resolveObserved(firstKey, firstData, fingerprint).orElseThrow().getDependencyFingerprint();
		final String variantChanged = catalog.resolveObserved(changedKey, firstData, fingerprint).orElseThrow().getDependencyFingerprint();
		final String dataChanged = catalog.resolveObserved(firstKey, snapshot(dimension("minecraft/overworld", platform(7, "Renamed"))), fingerprint).orElseThrow().getDependencyFingerprint();
		Assertions.assertNotEquals(first, variantChanged);
		Assertions.assertNotEquals(first, dataChanged);
	}

	@Test
	public void platformSnapshotRetainsEveryRouteMapSemantic() {
		final RouteAssetRenderSnapshot.Interchange interchange = new RouteAssetRenderSnapshot.Interchange(
				List.of(0x25B407, 0x3E405E), List.of("Green|綠線", "Airport|機場"), true, true
		);
		final List<RouteAssetRenderSnapshot.Station> stops = List.of(
				new RouteAssetRenderSnapshot.Station(101, 11, "Alpha|甲", "Beta|乙", interchange),
				new RouteAssetRenderSnapshot.Station(102, 12, "Beta|乙", "Gamma|丙", RouteAssetRenderSnapshot.Interchange.empty()),
				new RouteAssetRenderSnapshot.Station(103, 13, "Gamma|丙", "", RouteAssetRenderSnapshot.Interchange.empty())
		);
		final RouteAssetRenderSnapshot.Route occurrence = new RouteAssetRenderSnapshot.Route(
				700, "R7||Internal", 0x14755E, RouteAssetRenderSnapshot.CircularState.CLOCKWISE,
				RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, 1, stops
		);
		final RouteAssetDataMirror.PlatformSnapshot platform = new RouteAssetDataMirror.PlatformSnapshot(102, "P2", List.of(occurrence));

		Assertions.assertEquals("P2", platform.getDisplayName());
		Assertions.assertEquals(1, platform.getRoutes().get(0).getCurrentStationIndex());
		Assertions.assertEquals("Beta|乙", platform.getRoutes().get(0).getCurrentStation().getName());
		Assertions.assertEquals("Gamma|丙", platform.getRoutes().get(0).getCurrentStation().getDestination());
		Assertions.assertEquals(RouteAssetRenderSnapshot.CircularState.CLOCKWISE, platform.getRoutes().get(0).getCircularState());
		Assertions.assertEquals(RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, platform.getRoutes().get(0).getRouteKind());
		Assertions.assertEquals(List.of(101L, 102L, 103L), platform.getRoutes().get(0).getStations().stream().map(RouteAssetRenderSnapshot.Station::getPlatformId).collect(Collectors.toList()));
		Assertions.assertEquals(List.of(0x25B407, 0x3E405E), platform.getRoutes().get(0).getStations().get(0).getInterchange().getColors());
		Assertions.assertTrue(platform.getRoutes().get(0).getStations().get(0).getInterchange().hasRailway());
		Assertions.assertTrue(platform.getRoutes().get(0).getStations().get(0).getInterchange().hasAirport());
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
