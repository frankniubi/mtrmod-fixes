package org.mtr.mod.route;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

public final class RouteAssetDependencyCatalogTest {

	private static final String DIMENSION = "minecraft/overworld";
	private static final long PLATFORM_ID = 7;
	private static final long ROUTE_ID = 700;
	private static final String RESOURCE_FINGERPRINT = "f".repeat(64);

	@Test
	public void fixedCatalogContainsBothPurposesAndIsolatesPlatformRenames() {
		final RouteAssetDependencyCatalog catalog = new RouteAssetDependencyCatalog();
		final Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> original = catalog.enumerateFixed(snapshot("U1", "R1"), RESOURCE_FINGERPRINT, "NORMAL");
		final Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> selectedRename = catalog.enumerateFixed(snapshot("U2", "R1"), RESOURCE_FINGERPRINT, "NORMAL");
		final Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> onwardRename = catalog.enumerateFixed(snapshot("U1", "R2"), RESOURCE_FINGERPRINT, "NORMAL");

		Assertions.assertEquals(36, original.size());
		for (int resolution = 0; resolution <= 3; resolution++) {
			final RouteAssetKey generic = RouteAssetCanonicalKeyFactory.routeMap(DIMENSION, PLATFORM_ID, resolution, "NORMAL", RouteMapPurpose.GENERIC, true, false, 37F / 22, false);
			final RouteAssetKey routeSign = RouteAssetCanonicalKeyFactory.routeMap(DIMENSION, PLATFORM_ID, resolution, "NORMAL", RouteMapPurpose.ROUTE_SIGN, true, false, 37F / 22, false);
			final RouteAssetKey arrow = RouteAssetCanonicalKeyFactory.directionArrow(DIMENSION, PLATFORM_ID, resolution, "NORMAL", false, false, RouteAssetTextRasterizer.Alignment.CENTER, true, 0.2F, 22F / 5, 0xFF000000, 0xFFFFFFFF, 0);
			final RouteAssetKey strip = RouteAssetCanonicalKeyFactory.routeColorStrip(DIMENSION, PLATFORM_ID, resolution, "NORMAL");
			final RouteAssetKey square = RouteAssetCanonicalKeyFactory.routeSquare(DIMENSION, ROUTE_ID, resolution, "NORMAL", RouteAssetTextRasterizer.Alignment.LEFT);

			Assertions.assertEquals(RouteMapPurpose.GENERIC, original.get(generic).getSnapshot().getRouteMapPurpose());
			Assertions.assertEquals(RouteMapPurpose.ROUTE_SIGN, original.get(routeSign).getSnapshot().getRouteMapPurpose());
			assertChanged(original, selectedRename, routeSign, arrow);
			assertUnchanged(original, selectedRename, generic, strip, square);
			assertChanged(original, onwardRename, routeSign);
			assertUnchanged(original, onwardRename, generic, arrow, strip, square);
		}
	}

	private static void assertChanged(Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> first, Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> second, RouteAssetKey... keys) {
		for (final RouteAssetKey key : keys) Assertions.assertNotEquals(first.get(key).getDependencyFingerprint(), second.get(key).getDependencyFingerprint(), key.toString());
	}

	private static void assertUnchanged(Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> first, Map<RouteAssetKey, RouteAssetDependencyCatalog.Entry> second, RouteAssetKey... keys) {
		for (final RouteAssetKey key : keys) Assertions.assertEquals(first.get(key).getDependencyFingerprint(), second.get(key).getDependencyFingerprint(), key.toString());
	}

	private static RouteAssetDataMirror.Snapshot snapshot(String selectedPlatformName, String onwardPlatformName) {
		final RouteAssetRenderSnapshot.Interchange interchange = new RouteAssetRenderSnapshot.Interchange(List.of(0x25B407), List.of("I1"), true, false);
		final List<RouteAssetRenderSnapshot.Station> stations = List.of(
				new RouteAssetRenderSnapshot.Station(PLATFORM_ID, selectedPlatformName, 100, 100, "Current|Current", "Terminal|Terminal", interchange),
				new RouteAssetRenderSnapshot.Station(8, onwardPlatformName, 200, 200, "Next|Next", "Terminal|Terminal", interchange),
				new RouteAssetRenderSnapshot.Station(9, "T1", 300, 300, "Terminal|Terminal", "", RouteAssetRenderSnapshot.Interchange.empty())
		);
		final RouteAssetRenderSnapshot.Route route = new RouteAssetRenderSnapshot.Route(ROUTE_ID, "R7", 0x14755E, RouteAssetRenderSnapshot.CircularState.NONE, RouteAssetRenderSnapshot.RouteKind.HIGH_SPEED, 0, stations);
		final RouteAssetDataMirror.PlatformSnapshot platform = new RouteAssetDataMirror.PlatformSnapshot(PLATFORM_ID, selectedPlatformName, 100, List.of(route));
		final RouteAssetDataMirror.DimensionSnapshot dimension = new RouteAssetDataMirror.DimensionSnapshot(DIMENSION, 1, Map.of(PLATFORM_ID, platform));
		return new RouteAssetDataMirror.Snapshot(1, Map.of(DIMENSION, dimension));
	}
}
